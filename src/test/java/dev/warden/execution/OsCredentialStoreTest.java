package dev.warden.execution;

import dev.warden.config.Connection;
import dev.warden.config.ConnectionCommand;
import dev.warden.config.ConnectionService;
import dev.warden.config.Profile;
import dev.warden.dashboard.Dashboard;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.ByteArrayInputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** OS vault references, with no fallback into env or YAML. Values are compared in-process and never printed. */
public final class OsCredentialStoreTest implements Suite {
    private static final String SECRET = "synthetic-os-store-secret";
    private static final String OTHER = "synthetic-env-secret-not-for-vault";

    @Override public String name() { return "os-credential"; }

    @Override public void run(Check check) throws Exception {
        references(check);
        resolverStaysOnItsSource(check);
        commandRoundTrip(check);
        dashboardRoundTrip(check);
        harnessNamesTheIssuedVariable(check);
        windowsVault(check);
    }

    private void references(Check check) {
        Connection stored = new Connection("personal", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "os-store:warden/personal", true);
        check.eq("an OS reference has no environment name", null, stored.apiKeyEnv());
        check.eq("an OS reference names only this connection", "warden/personal", stored.osStoreTarget());
        check.eq("an OS reference round-trips through YAML", stored, Connection.parse(stored.toYaml(), "os.yaml"));
        check.that("serialized connection has no credential value", !stored.toYaml().contains(SECRET));
        Connection env = new Connection("personal", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "env:OPENROUTER_API_KEY", true);
        check.that("switching the credential source changes the fingerprint",
                !env.fingerprint().equals(stored.fingerprint()));
        check.rejects("an OS reference cannot name another connection", "os-store:warden/", () ->
                new Connection("personal", "openrouter", "openai_chat",
                        "https://openrouter.ai/api/v1", "os-store:warden/other", true));
        check.rejects("an OS reference cannot escape its prefix", "os-store:warden/", () ->
                new Connection("personal", "custom", "openai_chat", "https://example.com/v1",
                        "os-store:../personal", true));
        check.rejects("a raw credential is still not a reference", "env:", () ->
                Connection.parse(stored.toYaml().replace("os-store:warden/personal", SECRET), "raw.yaml"));
    }

    private void resolverStaysOnItsSource(Check check) {
        Connection stored = new Connection("personal", "custom", "openai_chat",
                "https://example.com/v1", "os-store:warden/personal", true);
        MemoryStore closed = new MemoryStore();
        closed.open = false;
        CredentialResolver blocked = new CredentialResolver(name -> OTHER, closed);
        check.eq("a closed vault is unavailable", "unavailable", blocked.status(stored).get("state"));
        try {
            blocked.resolve(stored);
            check.that("a closed vault fails before dispatch", false);
        } catch (IllegalStateException failure) {
            check.that("a closed vault does not fall back to an environment value",
                    failure.getCause() == null && !String.valueOf(failure.getMessage()).contains(OTHER));
        }
        MemoryStore open = new MemoryStore();
        open.values.put("warden/personal", SECRET);
        CredentialResolver resolver = new CredentialResolver(name -> OTHER, open);
        check.eq("a stored credential is ready", "ready", resolver.status(stored).get("state"));
        check.that("status omits the credential", !resolver.status(stored).toString().contains(SECRET));
        check.that("dispatch reads the vault rather than the environment", SECRET.equals(resolver.resolve(stored)));
        open.values.remove("warden/personal");
        check.eq("an empty vault entry is missing", "missing", resolver.status(stored).get("state"));
        Connection env = new Connection("personal", "custom", "openai_chat",
                "https://example.com/v1", "env:OTHER_KEY", true);
        open.values.put("warden/personal", SECRET);
        CredentialResolver envResolver = new CredentialResolver(name -> "OTHER_KEY".equals(name) ? OTHER : null, open);
        check.that("an environment reference does not read the vault", OTHER.equals(envResolver.resolve(env)));
        check.eq("a disabled vault connection is explicit", "disabled",
                resolver.status(stored.withEnabled(false)).get("state"));
    }

    private void commandRoundTrip(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-os-command-");
        try {
            MemoryStore store = new MemoryStore();
            ConnectionCommand command = new ConnectionCommand(name -> OTHER, store);
            Map<String, Object> added = command.run(home, new String[] {
                    "connections", "add", "personal", "--provider", "openrouter", "--os-store"});
            check.eq("adding a vault reference does not activate profiles", false, added.get("profiles_activated"));
            check.that("add report has no credential", !Json.write(added).contains(SECRET));
            Path file = home.resolve("connections/personal.yaml");
            String stored = Files.readString(file);
            check.contains("the file stores the vault reference", stored, "os-store:warden/personal");
            check.that("the file has no credential", !stored.contains(SECRET) && !stored.contains(OTHER));
            Map<String, Object> saved = command.run(home, new String[] {"connections", "auth-set", "personal"},
                    new ByteArrayInputStream((SECRET + "\n").getBytes(StandardCharsets.UTF_8)));
            check.eq("stdin stores the credential", true, saved.get("ok"));
            check.eq("storing a credential does not activate profiles", false, saved.get("profiles_activated"));
            check.eq("storing a credential does not revoke the provider key", false, saved.get("service_key_revoked"));
            check.that("auth report has no credential", !Json.write(saved).contains(SECRET));
            check.eq("auth-set does not rewrite the connection file", stored, Files.readString(file));
            check.that("the vault received the stdin credential", SECRET.equals(store.values.get("warden/personal")));
            String listed = Json.write(command.run(home, new String[] {"connections", "list"}));
            check.that("list has no credential", !listed.contains(SECRET));
            check.contains("list reports the vault source", listed, "os-store:warden/personal");
            check.eq("remove deletes the local entry only", true, command.run(home,
                    new String[] {"connections", "auth-remove", "personal"}).get("ok"));
            check.eq("the local entry is gone", null, store.values.get("warden/personal"));
            check.eq("removal leaves the reference file", stored, Files.readString(file));

            MemoryStore denied = new MemoryStore();
            denied.open = false;
            ConnectionCommand unavailable = new ConnectionCommand(name -> null, denied);
            check.rejects("an unavailable vault cannot be selected", "unavailable", () ->
                    unavailable.run(home, new String[] {"connections", "add", "other", "--os-store"}));
            check.that("a refused vault add creates no file", !Files.exists(home.resolve("connections/other.yaml")));
        } finally {
            delete(home);
        }
    }

    private void dashboardRoundTrip(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-os-dashboard-");
        Path home = scratch.resolve("user");
        MemoryStore store = new MemoryStore();
        try (Dashboard server = new Dashboard(scratch, 0, null, home, name -> OTHER, store);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String base = server.url();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base)).build(),
                    HttpResponse.BodyHandlers.ofString());
            check.contains("a working vault renders a write-only field", page.body(), "type=\"password\"");
            check.that("the page does not embed a credential", !page.body().contains(SECRET));
            var tokenMatch = Pattern.compile("name=\"warden-settings-token\" content=\"([^\"]+)\"").matcher(page.body());
            check.that("settings token is present", tokenMatch.find());
            String token = tokenMatch.group(1);
            Map<String, Object> add = new LinkedHashMap<>();
            add.put("id", "personal");
            add.put("provider", "custom");
            add.put("base_url", "https://example.com/v1");
            add.put("credential_ref", "os-store:warden/personal");
            add.put("enabled", true);
            HttpResponse<String> added = post(client, base, token, "add", add);
            check.eq("vault reference can be saved", 201, added.statusCode());
            check.eq("saving the reference does not activate profiles", false, Json.parseObject(added.body()).get("profiles_activated"));
            String revision = String.valueOf(((Map<?, ?>) Json.parseObject(added.body()).get("connection")).get("revision"));
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("id", "personal");
            secret.put("revision", revision);
            secret.put("secret", SECRET);
            HttpResponse<String> stored = post(client, base, token, "auth-set", secret);
            check.eq("write-only post stores the credential", 200, stored.statusCode());
            check.that("the store response has no credential", !stored.body().contains(SECRET));
            check.eq("the post does not activate profiles", false, Json.parseObject(stored.body()).get("profiles_activated"));
            String yaml = Files.readString(home.resolve("connections/personal.yaml"));
            check.that("dashboard YAML has no credential", !yaml.contains(SECRET));
            HttpResponse<String> listed = client.send(HttpRequest.newBuilder(URI.create(base + "api/connections")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check.that("GET has no credential", !listed.body().contains(SECRET));
            check.contains("GET shows that the vault works", listed.body(), "\"os_store_available\":true");
            check.that("the dashboard vault holds the posted credential", SECRET.equals(store.values.get("warden/personal")));
            Map<String, Object> env = new LinkedHashMap<>(add);
            env.put("id", "envone");
            env.put("credential_ref", "env:OTHER_KEY");
            HttpResponse<String> envAdded = post(client, base, token, "add", env);
            String envRevision = String.valueOf(((Map<?, ?>) Json.parseObject(envAdded.body()).get("connection")).get("revision"));
            Map<String, Object> misplaced = new LinkedHashMap<>();
            misplaced.put("id", "envone");
            misplaced.put("revision", envRevision);
            misplaced.put("secret", SECRET);
            HttpResponse<String> rejected = post(client, base, token, "auth-set", misplaced);
            check.eq("an environment connection cannot receive a vault write", 400, rejected.statusCode());
            check.that("the rejected write is not stored under the other id", store.values.get("warden/envone") == null);
            check.eq("the policy file is untouched", false, Files.exists(home.resolve("policy.yaml")));
        } finally {
            delete(scratch);
        }
    }

    private void harnessNamesTheIssuedVariable(Check check) throws Exception {
        Connection stored = new Connection("personal", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "os-store:warden/personal", true);
        Profile profile = Profile.parse(Files.readString(Path.of("examples/api/opencode-implementer.yaml")),
                "opencode-implementer.yaml");
        String config = OpenCodeExecutor.configJson(stored, profile);
        check.eq("a vault credential is issued under one child variable",
                OpenCodeExecutor.ISSUED_ENV, OpenCodeExecutor.credentialVariable(stored));
        check.contains("generated config names that variable", config, "{env:" + OpenCodeExecutor.ISSUED_ENV + "}");
        check.that("generated config has no credential", !config.contains(SECRET));
        Connection env = new Connection("personal", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "env:OPENROUTER_API_KEY", true);
        check.eq("an environment connection keeps its own variable", "OPENROUTER_API_KEY",
                OpenCodeExecutor.credentialVariable(env));
    }

    private void windowsVault(Check check) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        OsCredentialStore platform = OsCredentialStores.platform();
        if (!platform.available()) {
            String failure = String.valueOf(WindowsOsCredentialStore.lastFailureForTest());
            if (failure.contains(SECRET)) failure = "failure text contained the credential";
            check.eq("this platform reports vault availability honestly",
                    windows ? "available" : "unavailable", windows ? failure : "unavailable");
            return;
        }
        check.that("the helper script does not contain a credential", !WindowsOsCredentialStore.script().contains(SECRET));
        Path helper = WindowsOsCredentialStore.helperDirectory();
        check.that("the helper directory is limited to its owner", ownerOnly(helper));
        String id = "zzos" + UUID.randomUUID().toString().replace("-", "");
        String target = "warden/" + id;
        String existing = platform.read(target);
        check.that("the windows test target starts empty", existing == null);
        if (existing != null) return;
        try {
            platform.write(target, SECRET);
            boolean commandLeak = WindowsOsCredentialStore.lastCommandForTest().stream().anyMatch(argument -> argument.contains(SECRET));
            check.that("vault command arguments do not contain the credential", !commandLeak);
            check.that("windows credential round trip matches", SECRET.equals(platform.read(target)));
            platform.delete(target);
            check.that("windows credential delete removes it", platform.read(target) == null);
        } finally {
            try { platform.delete(target); } catch (RuntimeException ignored) { /* already absent */ }
        }
    }

    private static boolean ownerOnly(Path dir) {
        try {
            var view = Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class);
            if (view == null) return false;
            String owner = Files.getOwner(dir).getName();
            String simple = owner.substring(Math.max(owner.lastIndexOf('\\'), owner.lastIndexOf('/')) + 1);
            for (var entry : view.getAcl()) {
                if (entry.type() != java.nio.file.attribute.AclEntryType.ALLOW) return false;
                String name = entry.principal().getName();
                String entrySimple = name.substring(Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/')) + 1);
                if (!name.equalsIgnoreCase(owner) && !entrySimple.equalsIgnoreCase(simple)) return false;
            }
            return !view.getAcl().isEmpty();
        } catch (Exception failure) {
            return false;
        }
    }

    private static HttpResponse<String> post(HttpClient client, String base, String token, String operation,
                                              Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "api/connections/" + operation))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Warden-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class MemoryStore implements OsCredentialStore {
        private boolean open = true;
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public boolean available() { return open; }
        @Override public String read(String target) {
            if (!open) throw new IllegalStateException("connection credential source is unavailable");
            return values.get(target);
        }
        @Override public void write(String target, String secret) {
            if (!open) throw new IllegalStateException("connection credential source is unavailable");
            values.put(target, secret);
        }
        @Override public void delete(String target) {
            if (!open) throw new IllegalStateException("connection credential source is unavailable");
            values.remove(target);
        }
    }
}
