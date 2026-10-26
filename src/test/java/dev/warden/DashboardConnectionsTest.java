package dev.warden;

import dev.warden.dashboard.Dashboard;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Real loopback HTTP exercises settings writes without the operator's home or any inference. */
public final class DashboardConnectionsTest implements Suite {
    @Override public String name() { return "dashboard-connections"; }
    private HttpClient client;
    private String base;
    private String token;

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-dashboard-connections-");
        Path home = scratch.resolve("user");
        try (Dashboard server = new Dashboard(scratch, 0, null, home,
                name -> "DASH_API_KEY".equals(name) ? "synthetic-dashboard-secret" : null);
             HttpClient http = HttpClient.newHttpClient()) {
            client = http;
            server.start(); base = server.url();
            HttpResponse<String> page = get("");
            var match = Pattern.compile("name=\"warden-settings-token\" content=\"([^\"]+)\"").matcher(page.body());
            check.that("settings has a process token even without decision actions", match.find());
            token = match.group(1);
            check.that("the process token is not a placeholder", token.length() >= 24 && !token.contains("{{"));
            check.contains("the page contains the reference editor", page.body(), "connection-editor-form");
            check.contains("the form names the environment source", page.body(), "Переменная окружения с ключом");
            check.that("an unavailable vault does not render a key field", !page.body().contains("type=\"password\""));
            check.that("settings HTML never embeds a resolved credential", !page.body().contains("synthetic-dashboard-secret"));
            check.eq("connections is a read-only GET projection", 200, get("api/connections").statusCode());

            Map<String, Object> add = settings("http://127.0.0.1:1/v1", "env:DASH_API_KEY");
            check.eq("missing token cannot add a connection", 403, post("add", add, null, null).statusCode());
            check.eq("incorrect token cannot add a connection", 403, post("add", add, "wrong", null).statusCode());
            check.eq("foreign origin cannot use a valid token", 403, post("add", add, token, "https://foreign.invalid").statusCode());
            check.that("unauthorized writes do not create config", !Files.exists(home.resolve("connections/test.yaml")));
            HttpResponse<String> wrongType = client.send(request("api/connections/add")
                    .header("X-Warden-Token", token).header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(add))).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("write API requires JSON content type", 415, wrongType.statusCode());
            HttpResponse<String> oversized = raw("add", "x".repeat(16 * 1024 + 1));
            check.eq("write bodies are bounded before JSON parsing", 413, oversized.statusCode());
            HttpResponse<String> malformed = raw("add", "{\"api_key\":\"secret-input-not-for-errors");
            check.eq("malformed JSON is rejected", 400, malformed.statusCode());
            check.that("JSON diagnostics do not echo request bytes", !malformed.body().contains("secret-input"));
            Map<String, Object> unsafe = new LinkedHashMap<>(add); unsafe.put("api_key", "secret-input-not-for-errors");
            HttpResponse<String> rejected = post("add", unsafe);
            check.eq("key-value fields are unsupported", 400, rejected.statusCode());
            check.that("unsupported input is not echoed", !rejected.body().contains("secret-input"));
            check.eq("unknown operations cannot mutate config", 400, post("execute", add).statusCode());

            HttpResponse<String> added = post("add", add);
            check.eq("valid add creates the connection", 201, added.statusCode());
            Map<String, Object> first = connection(added);
            String firstRevision = (String) first.get("revision");
            check.that("an edit revision is a content digest", firstRevision.matches("[a-f0-9]{64}"));
            check.eq("the new connection reports a configured key", "ready", ((Map<?, ?>) first.get("auth")).get("state"));
            check.eq("adding a connection never activates a profile", false, json(added).get("profiles_activated"));
            check.that("stored YAML has a reference only", !Files.readString(home.resolve("connections/test.yaml")).contains("synthetic-dashboard-secret"));
            check.that("GET cannot reveal the key value", !get("api/connections").body().contains("synthetic-dashboard-secret"));
            check.eq("add refuses overwrite", 409, post("add", add).statusCode());

            Map<String, Object> edit = settings("http://127.0.0.1:2/v1", "env:OTHER_KEY");
            edit.put("revision", firstRevision);
            HttpResponse<String> updated = post("update", edit);
            check.eq("update uses the supplied revision", 200, updated.statusCode());
            Map<String, Object> second = connection(updated);
            String secondRevision = (String) second.get("revision");
            check.that("an update changes the revision", !firstRevision.equals(secondRevision));
            check.eq("changed credential reference has its own local status", "missing", ((Map<?, ?>) second.get("auth")).get("state"));
            check.eq("an old editor cannot disable the new settings", 409, post("disable", action(firstRevision)).statusCode());
            check.eq("stale checks do not inspect another version", 409, post("check", action(firstRevision)).statusCode());
            check.eq("stale updates never silently overwrite", 409, post("update", edit).statusCode());
            HttpResponse<String> disabled = post("disable", action(secondRevision));
            check.eq("a current editor can disable", false, connection(disabled).get("enabled"));
            HttpResponse<String> enabled = post("enable", action((String) connection(disabled).get("revision")));
            check.eq("a current editor can enable", true, connection(enabled).get("enabled"));
            String currentRevision = (String) connection(enabled).get("revision");
            HttpResponse<String> missingKey = post("check", action(currentRevision));
            check.eq("local check is explicit about missing credentials", "role_api_key_missing", json(missingKey).get("code"));
            check.eq("connection checks do not call a model", false, json(missingKey).get("inference_performed"));

            Path file = home.resolve("connections/test.yaml");
            Files.writeString(file, "# operator note\n" + Files.readString(file));
            check.eq("a comment-only external edit protects its bytes too", 409, post("disable", action(currentRevision)).statusCode());
            Map<String, Object> listed = (Map<String, Object>) ((List<?>) json(get("api/connections")).get("connections")).get(0);
            currentRevision = (String) listed.get("revision");
            Map<String, Object> noAuth = settings("http://127.0.0.1:2/v1", null);
            noAuth.put("revision", currentRevision);
            HttpResponse<String> local = post("update", noAuth);
            check.eq("the editor can explicitly select local no-auth", 200, local.statusCode());
            check.contains("the service preserves operator comments", Files.readString(file), "# operator note");
            currentRevision = (String) connection(local).get("revision");
            HttpResponse<String> checked = post("check", action(currentRevision));
            check.eq("local no-auth check remains unverified by a service", false, json(checked).get("auth_verified"));
            check.eq("no-auth check remains no-inference", false, json(checked).get("inference_performed"));
            HttpResponse<String> stateWrite = client.send(request("api/state")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("state projection remains read-only", 405, stateWrite.statusCode());

            Files.createDirectories(home.resolve("profiles"));
            Path profile = home.resolve("profiles/ref.yaml");
            Files.writeString(profile, "connection: test\n");
            HttpResponse<String> inUse = post("remove", action(currentRevision));
            check.eq("references protect deletion", 409, inUse.statusCode());
            check.eq("reference refusal is actionable", "connection_in_use", json(inUse).get("code"));
            check.that("reference refusal preserves the connection", Files.exists(file));
            Files.delete(profile);
            check.eq("an unreferenced current connection can be removed", 200, post("remove", action(currentRevision)).statusCode());
            check.eq("list reflects the deletion", 0, ((List<?>) json(get("api/connections")).get("connections")).size());
            check.eq("missing rows have a stable not-found response", 404, post("remove", action(currentRevision)).statusCode());
            check.eq("the server never writes policy as a side effect", false, Files.exists(home.resolve("policy.yaml")));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private Map<String, Object> settings(String url, String reference) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "test"); payload.put("provider", "custom"); payload.put("base_url", url);
        payload.put("credential_ref", reference); payload.put("enabled", true);
        return payload;
    }
    private Map<String, Object> action(String revision) { return Map.of("id", "test", "revision", revision); }
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)); }
    private HttpResponse<String> get(String path) throws Exception { return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(String operation, Map<String, Object> payload) throws Exception { return post(operation, payload, token, null); }
    private HttpResponse<String> post(String operation, Map<String, Object> payload, String suppliedToken, String origin) throws Exception {
        HttpRequest.Builder request = request("api/connections/" + operation).header("Content-Type", "application/json");
        if (suppliedToken != null) request.header("X-Warden-Token", suppliedToken);
        if (origin != null) request.header("Origin", origin);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> raw(String operation, String body) throws Exception {
        return client.send(request("api/connections/" + operation).header("X-Warden-Token", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<String, Object> json(HttpResponse<String> response) { return Json.parseObject(response.body()); }
    @SuppressWarnings("unchecked") private Map<String, Object> connection(HttpResponse<String> response) { return (Map<String, Object>) json(response).get("connection"); }
}
