package dev.warden.execution;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.ConnectionCommand;
import dev.warden.config.ConnectionStore;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** PKCE against a loopback stub. Reports are booleans so a failure does not print a key. */
public final class OpenRouterLoginTest implements Suite {
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CODE = "synthetic-auth-code";
    private static final String KEY = "synthetic-login-key";

    @Override public String name() { return "openrouter-login"; }

    @Override public void run(Check check) throws Exception {
        challengeAndUrl(check);
        endpoints(check);
        callback(check);
        exchange(check);
        command(check);
    }

    private void challengeAndUrl(Check check) {
        check.eq("S256 matches the RFC 7636 vector", CHALLENGE, OpenRouterLogin.challenge(VERIFIER));
        String headless = OpenRouterLogin.authorizeUrl(null, CHALLENGE, null);
        check.that("headless URL is OpenRouter S256",
                headless.startsWith("https://openrouter.ai/auth?")
                        && headless.contains("code_challenge=" + CHALLENGE)
                        && headless.contains("code_challenge_method=S256")
                        && headless.contains("key_label=warden")
                        && !headless.contains("callback_url")
                        && !headless.contains("state=")
                        && !headless.contains(VERIFIER));
        String local = OpenRouterLogin.authorizeUrl(4242, CHALLENGE, VERIFIER);
        check.that("callback URL pins loopback and state",
                local.contains("callback_url=http%3A%2F%2F127.0.0.1%3A4242%2Fcallback")
                        && local.contains("state=" + VERIFIER)
                        && local.contains("code_challenge_method=S256")
                        && !local.contains("code_verifier"));
    }

    private void endpoints(Check check) {
        check.that("the documented exchange endpoint is allowed", OpenRouterLogin.allowedEndpoint(OpenRouterLogin.EXCHANGE));
        check.that("a loopback stub is allowed",
                OpenRouterLogin.allowedEndpoint(URI.create("http://127.0.0.1:9/api/v1/auth/keys")));
        check.that("plain localhost is not an exchange host",
                !OpenRouterLogin.allowedEndpoint(URI.create("http://localhost:9/api/v1/auth/keys")));
        check.that("another host is refused",
                !OpenRouterLogin.allowedEndpoint(URI.create("https://evil.example/api/v1/auth/keys")));
        check.that("a query on the token endpoint is refused",
                !OpenRouterLogin.allowedEndpoint(URI.create("https://openrouter.ai/api/v1/auth/keys?access_token=secret-query")));
        check.that("userinfo on the token endpoint is refused",
                !OpenRouterLogin.allowedEndpoint(URI.create("https://user:secret-user@openrouter.ai/api/v1/auth/keys")));
        String leaked = "secret-query";
        try {
            OpenRouterLogin.exchange(URI.create("https://openrouter.ai/api/v1/auth/keys?" + leaked), CODE, VERIFIER);
            check.that("a queried token endpoint does not connect", false);
        } catch (IllegalArgumentException rejected) {
            check.that("the refusal does not repeat the query",
                    rejected.getMessage().contains("refused the token endpoint")
                            && !rejected.getMessage().contains(leaked) && rejected.getCause() == null);
        }
    }

    private void callback(Check check) throws Exception {
        String state = OpenRouterLogin.verifier();
        HttpClient client = client();
        try (OpenRouterLogin.Callback callback = OpenRouterLogin.Callback.bind(state)) {
            HttpResponse<String> wrong = client.send(request(callback, "code=" + CODE + "&state=wrong-state-value"),
                    HttpResponse.BodyHandlers.ofString());
            check.eq("a mismatched state is not accepted", 400, wrong.statusCode());
            check.that("the callback page does not repeat the code", !wrong.body().contains(CODE));
            try {
                callback.await(Duration.ofSeconds(2));
                check.that("a mismatched state fails the login", false);
            } catch (IllegalArgumentException rejected) {
                check.that("the state failure is static",
                        rejected.getMessage().contains("state did not match")
                                && !rejected.getMessage().contains("wrong-state-value")
                                && !rejected.getMessage().contains(CODE)
                                && rejected.getCause() == null);
            }
        }
        try (OpenRouterLogin.Callback callback = OpenRouterLogin.Callback.bind(state)) {
            HttpResponse<String> declined = client.send(request(callback, "error=access_denied&state=" + state),
                    HttpResponse.BodyHandlers.ofString());
            check.eq("a provider decline is not a success page", 400, declined.statusCode());
            try {
                callback.await(Duration.ofSeconds(2));
                check.that("a decline fails the login", false);
            } catch (IllegalArgumentException rejected) {
                check.that("a decline message does not repeat the provider error",
                        rejected.getMessage().contains("was declined") && !rejected.getMessage().contains("access_denied"));
            }
        }
        try (OpenRouterLogin.Callback callback = OpenRouterLogin.Callback.bind(state)) {
            HttpResponse<String> first = client.send(request(callback, "code=" + CODE + "&state=" + state),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> second = client.send(request(callback, "code=other-code&state=" + state),
                    HttpResponse.BodyHandlers.ofString());
            check.eq("the first callback is accepted", 200, first.statusCode());
            check.that("the success page does not repeat the code",
                    !first.body().contains(CODE) && "no-store".equals(first.headers().firstValue("Cache-Control").orElse("")));
            check.eq("a second callback is ignored", 404, second.statusCode());
            check.that("the ignored callback does not repeat a code", !second.body().contains("other-code"));
            check.that("the first code is the only one delivered", CODE.equals(callback.await(Duration.ofSeconds(2))));
        }
        try (OpenRouterLogin.Callback callback = OpenRouterLogin.Callback.bind(state)) {
            check.rejects("silence is a timeout", "timed out", () -> callback.await(Duration.ofMillis(200)));
        }
    }

    private void exchange(Check check) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        AtomicReference<String> posted = new AtomicReference<>("");
        int port = bind(server);
        server.createContext("/api/v1/auth/keys", exchange -> {
            paths.add(exchange.getRequestURI().getRawPath());
            posted.set(new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8));
            byte[] body = ("{\"key\":\"" + KEY + "\",\"extra\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String issued = OpenRouterLogin.exchange(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/keys"), CODE, VERIFIER);
            check.that("the stub key is returned to the caller", KEY.equals(issued));
            check.that("the token request carries the code and verifier",
                    posted.get().contains(CODE) && posted.get().contains(VERIFIER));
            check.eq("the token request is a single exchange", List.of("/api/v1/auth/keys"), paths);
        } finally {
            server.stop(0);
        }

        HttpServer redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> redirected = new ArrayList<>();
        int redirectPort = bind(redirect);
        redirect.createContext("/api/v1/auth/keys", exchange -> {
            redirected.add(exchange.getRequestURI().getRawPath());
            byte[] body = ("{\"key\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + redirectPort + "/stolen");
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        redirect.createContext("/stolen", exchange -> {
            redirected.add("stolen");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        redirect.start();
        try {
            OpenRouterLogin.exchange(URI.create("http://127.0.0.1:" + redirectPort + "/api/v1/auth/keys"), CODE, VERIFIER);
            check.that("a redirect is not treated as a key", false);
        } catch (IllegalArgumentException rejected) {
            check.that("a redirect is not followed and its body is not the error",
                    rejected.getMessage().contains("did not issue a key")
                            && !rejected.getMessage().contains(KEY)
                            && rejected.getCause() == null);
            check.eq("the redirect target is not requested", List.of("/api/v1/auth/keys"), redirected);
        } finally {
            redirect.stop(0);
        }

        HttpServer ugly = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int uglyPort = bind(ugly);
        ugly.createContext("/api/v1/auth/keys", exchange -> {
            byte[] body = "{\"key\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ugly.start();
        try {
            OpenRouterLogin.exchange(URI.create("http://127.0.0.1:" + uglyPort + "/api/v1/auth/keys"), CODE, VERIFIER);
            check.that("an unacceptable key is refused", false);
        } catch (IllegalArgumentException rejected) {
            check.that("the refusal does not repeat the unacceptable key",
                    rejected.getMessage().contains("will not store")
                            && !rejected.getMessage().contains("bad key")
                            && rejected.getCause() == null);
        } finally {
            ugly.stop(0);
        }
    }

    private void command(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-pkce-");
        try {
            MemoryStore store = new MemoryStore();
            AtomicBoolean called = new AtomicBoolean();
            AtomicReference<String> seenCode = new AtomicReference<>();
            AtomicReference<String> seenVerifier = new AtomicReference<>();
            OpenRouterLogin.Exchanger exchanger = (code, verifier) -> {
                called.set(true);
                seenCode.set(code);
                seenVerifier.set(verifier);
                return KEY;
            };
            ConnectionCommand command = new ConnectionCommand(name -> null, store, exchanger);
            command.run(home, new String[] {"connections", "add", "personal", "--provider", "openrouter", "--os-store"});
            Path file = home.resolve("connections/personal.yaml");
            String stored = Files.readString(file);
            Map<String, Object> saved = command.run(home, new String[] {"connections", "auth-login", "personal", "--headless"},
                    new ByteArrayInputStream((CODE + "\n").getBytes(StandardCharsets.UTF_8)));
            String report = Json.write(saved);
            check.eq("headless login stores the issued key", true, saved.get("ok"));
            check.eq("login does not activate profiles", false, saved.get("profiles_activated"));
            check.eq("login does not revoke the provider key", false, saved.get("service_key_revoked"));
            check.that("the exchanger saw the pasted code and a verifier",
                    called.get() && CODE.equals(seenCode.get()) && OpenRouterLogin.authorizationVerifier(seenVerifier.get()));
            String issuedVerifier = seenVerifier.get();
            check.that("the report and file omit the code, verifier and key",
                    issuedVerifier != null && !report.contains(KEY) && !report.contains(CODE)
                            && !report.contains(issuedVerifier)
                            && stored.equals(Files.readString(file)) && !Files.readString(file).contains(KEY));
            check.that("the vault received only the issued key", KEY.equals(store.values.get("warden/personal")));
            String listed = Json.write(command.run(home, new String[] {"connections", "list"}));
            check.that("list omits the key and shows the vault source",
                    !listed.contains(KEY) && listed.contains("os-store:warden/personal"));
            check.that("login creates no policy", !Files.exists(home.resolve("policy.yaml")));

            called.set(false);
            check.rejects("a code with a space is not exchanged", "code was rejected", () -> command.run(home,
                    new String[] {"connections", "auth-login", "personal", "--headless"},
                    new ByteArrayInputStream("bad code\n".getBytes(StandardCharsets.UTF_8))));
            check.that("a rejected code does not replace the stored key",
                    !called.get() && KEY.equals(store.values.get("warden/personal")));

            AtomicBoolean gonkaCalled = new AtomicBoolean();
            ConnectionCommand gonka = new ConnectionCommand(name -> null, store,
                    (code, verifier) -> { gonkaCalled.set(true); return KEY; });
            gonka.run(home, new String[] {"connections", "add", "gonka", "--provider", "gonkarouter", "--os-store"});
            check.rejects("Gonka login is refused", "only available for the OpenRouter", () -> gonka.run(home,
                    new String[] {"connections", "auth-login", "gonka", "--headless"},
                    new ByteArrayInputStream((CODE + "\n").getBytes(StandardCharsets.UTF_8))));
            check.that("Gonka login does not exchange", !gonkaCalled.get() && store.values.get("warden/gonka") == null);

            ConnectionCommand env = new ConnectionCommand(name -> null, store, exchanger);
            env.run(home, new String[] {"connections", "add", "envone", "--provider", "openrouter", "--env", "OPENROUTER_API_KEY"});
            called.set(false);
            check.rejects("an environment connection does not mint a vault key", "only in the OS credential store",
                    () -> env.run(home, new String[] {"connections", "auth-login", "envone", "--headless"},
                            new ByteArrayInputStream((CODE + "\n").getBytes(StandardCharsets.UTF_8))));
            check.that("an environment connection is not exchanged", !called.get());

            MemoryStore denied = new MemoryStore();
            denied.open = false;
            AtomicBoolean deniedCalled = new AtomicBoolean();
            ConnectionCommand unavailable = new ConnectionCommand(name -> null, denied,
                    (code, verifier) -> { deniedCalled.set(true); return KEY; });
            check.rejects("a closed vault does not start login", "no authorization was requested",
                    () -> unavailable.run(home, new String[] {"connections", "auth-login", "personal", "--headless"},
                            new ByteArrayInputStream((CODE + "\n").getBytes(StandardCharsets.UTF_8))));
            check.that("a closed vault is not asked to exchange", !deniedCalled.get());

            String conflictKey = "synthetic-conflict-key";
            AtomicBoolean conflictCalled = new AtomicBoolean();
            ConnectionCommand conflicted = new ConnectionCommand(name -> null, store, (code, verifier) -> {
                conflictCalled.set(true);
                try {
                    new ConnectionStore(home).setEnabled("personal", false);
                } catch (Exception failure) {
                    throw new IllegalStateException("test setup failed");
                }
                return conflictKey;
            });
            Map<String, Object> conflict = conflicted.run(home,
                    new String[] {"connections", "auth-login", "personal", "--headless"},
                    new ByteArrayInputStream((CODE + "\n").getBytes(StandardCharsets.UTF_8)));
            String conflictReport = Json.write(conflict);
            check.that("a changed file does not replace the stored key",
                    conflictCalled.get()
                            && Boolean.FALSE.equals(conflict.get("ok"))
                            && "connection_revision_conflict".equals(conflict.get("code"))
                            && !conflictReport.contains(conflictKey)
                            && !conflictReport.contains(CODE)
                            && KEY.equals(store.values.get("warden/personal")));
        } finally {
            delete(home);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static HttpRequest request(OpenRouterLogin.Callback callback, String query) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + callback.port() + "/callback?" + query))
                .timeout(Duration.ofSeconds(5)).GET().build();
    }

    private static int bind(HttpServer server) {
        return server.getAddress().getPort();
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
