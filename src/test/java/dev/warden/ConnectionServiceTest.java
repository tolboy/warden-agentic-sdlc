package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.Connection;
import dev.warden.config.ConnectionService;
import dev.warden.config.ConnectionStore;
import dev.warden.execution.OsCredentialStore;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Two settings editors share file revisions, not mutable in-memory connection state. */
public final class ConnectionServiceTest implements Suite {
    private static final String SECRET = "synthetic-service-credential-never-persist";
    @Override public String name() { return "connection-service"; }

    @Override public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-connection-service-");
        try {
            revisionsAndComments(check, home.resolve("edits"));
            explicitAuthentication(check, home.resolve("auth"));
            injectedVaultCheck(check, home.resolve("vault-check"));
            vaultEditsHoldConnectionRevision(check, home.resolve("vault-write"), "auth-set");
            vaultEditsHoldConnectionRevision(check, home.resolve("vault-delete"), "auth-remove");
            rejectedPayloads(check, home.resolve("invalid"));
            referencesAndRaces(check, home.resolve("race"));
            unreadableAndMissing(check, home.resolve("missing"));
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static ConnectionService service(Path home) { return new ConnectionService(home, ignored -> SECRET); }

    private static void revisionsAndComments(Check check, Path home) throws Exception {
        ConnectionService service = service(home);
        check.eq("list on an empty home does not create files", List.of(), service.list().get("connections"));
        check.that("empty settings read leaves the directory absent", !Files.exists(home));
        var added = service.apply("add", settings("personal", "openrouter", "https://openrouter.ai/api/v1", "env:KEY_ONE", true));
        check.eq("settings add returns created", 201, added.status());
        check.eq("add does not activate a model", false, added.report().get("profiles_activated"));
        Path file = home.resolve("connections/personal.yaml");
        String initialRevision = revision(added);
        String commented = "# connection rationale\r\nversion: 1\r\nconnection: personal\r\n"
                + "provider: 'openrouter' # route note\r\nprotocol: openai_chat\r\n"
                + "base_url: \"https://openrouter.ai/api/v1\" # endpoint note\r\n"
                + "auth: # authorization note\r\n    type: api_key # auth type note\r\n"
                + "    credential_ref: 'env:KEY_ONE' # key reference note\r\n"
                + "enabled: true # operator choice\r\n# keep trailing comment\r\n";
        Files.writeString(file, commented, StandardCharsets.UTF_8);
        Map<String, Object> row = first(service.list());
        String expected = (String) row.get("revision");
        check.that("comment-only edits change the optimistic revision", !initialRevision.equals(expected));
        check.eq("revision hashes exact file bytes", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file))), expected);
        check.eq("the panel receives an environment reference", "env:KEY_ONE", row.get("credential_ref"));
        check.that("the panel receives no credential value", !Json.write(row).contains(SECRET));

        Map<String, Object> edited = settings("personal", "custom", "https://other.example/v1", "env:KEY_TWO", false);
        edited.put("revision", expected);
        var saved = service.apply("update", edited);
        check.eq("a current editor saves", 200, saved.status());
        String updated = Files.readString(file);
        for (String comment : List.of("connection rationale", "route note", "endpoint note", "authorization note",
                "auth type note", "key reference note", "operator choice", "keep trailing comment")) {
            check.contains("saving preserves comment " + comment, updated, comment);
        }
        check.that("saving preserves CRLF line endings", !updated.replace("\r\n", "").contains("\n"));
        Connection actual = new ConnectionStore(home).versioned("personal").connection();
        check.eq("provider change reaches the stored connection", "custom", actual.provider());
        check.eq("destination change reaches the stored connection", "https://other.example/v1", actual.baseUrl());
        check.eq("reference change reaches the stored connection", "env:KEY_TWO", actual.credentialRef());
        check.that("editing a disabled value does not re-enable it", !actual.enabled());
        check.that("saving never stores the resolved credential", !updated.contains(SECRET));

        var stale = service.apply("update", edited);
        check.eq("a second editor cannot overwrite a changed file", 409, stale.status());
        check.eq("stale editor has a distinct machine code", "connection_revision_conflict", stale.report().get("code"));
        check.eq("stale editor leaves bytes untouched", updated, Files.readString(file));
        String current = revision(saved);
        edited.put("revision", current);
        var unchanged = service.apply("update", edited);
        check.eq("saving unchanged settings preserves exact revision", current, revision(unchanged));
        var enabled = service.apply("enable", action("personal", current));
        check.eq("enable compares current revision", 200, enabled.status());
        check.eq("enable preserves unrelated bytes", updated.replace("enabled: false", "enabled: true"), Files.readString(file));
        check.eq("a stale toggle is refused", 409, service.apply("disable", action("personal", current)).status());
        check.eq("a duplicate add does not overwrite", "connection_exists",
                service.apply("add", settings("personal", "custom", "https://other.example/v1", "env:KEY_TWO", true)).report().get("code"));

        String flow = "version: 1\nconnection: personal\nprovider: custom\nprotocol: openai_chat\n"
                + "base_url: https://other.example/v1\nauth: { type: api_key, credential_ref: env:KEY_TWO }\nenabled: true\n";
        Files.writeString(file, flow);
        Map<String, Object> flowEdit = settings("personal", "custom", "https://other.example/v1", "env:ANOTHER", true);
        flowEdit.put("revision", new ConnectionStore(home).versioned("personal").revision());
        var refused = service.apply("update", flowEdit);
        check.eq("unfamiliar auth layout is refused before rewriting", "connection_layout_unsupported", refused.report().get("code"));
        check.eq("a refused flow edit preserves comments and bytes", flow, Files.readString(file));
    }

    private static void explicitAuthentication(Check check, Path home) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ConnectionService service = service(home);
            var added = service.apply("add", settings("local", "custom", base, null, true));
            check.eq("explicit local noauth can be added", 201, added.status());
            Path file = home.resolve("connections/local.yaml");
            String current = revision(added);
            Map<String, Object> apiKey = settings("local", "custom", base, "env:LOCAL_KEY", true);
            apiKey.put("revision", current);
            var keyed = service.apply("update", apiKey);
            check.eq("changing local auth inserts a reference", 200, keyed.status());
            check.contains("new reference is in the auth block", Files.readString(file), "  credential_ref: env:LOCAL_KEY");
            Files.writeString(file, Files.readString(file).replace("env:LOCAL_KEY", "env:LOCAL_KEY # keep the auth explanation"));
            current = new ConnectionStore(home).versioned("local").revision();
            var checked = service.apply("check", action("local", current));
            check.eq("local check is a normal explicit action", 200, checked.status());
            check.eq("local check does not claim authentication", false, checked.report().get("auth_verified"));
            check.eq("connection check does not perform inference", false, checked.report().get("inference_performed"));
            check.eq("local check reaches no endpoint", 0, calls.get());
            check.eq("stale checks do not inspect a replacement connection", 409,
                    service.apply("check", action("local", revision(keyed))).status());
            Map<String, Object> noAuth = settings("local", "custom", base, null, true);
            noAuth.put("revision", current);
            var anonymous = service.apply("update", noAuth);
            check.eq("switching to local noauth removes the reference", 200, anonymous.status());
            String content = Files.readString(file);
            check.that("the reference scalar is removed", !content.contains("credential_ref:"));
            check.contains("reference removal retains its explanation", content, "  # keep the auth explanation");
            check.eq("stored noauth is explicit", null, new ConnectionStore(home).versioned("local").connection().credentialRef());
            check.that("API secrets are absent from successful action reports", !Json.write(checked.report()).contains(SECRET));
        } finally { server.stop(0); }
    }

    private static void injectedVaultCheck(Check check, Path home) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        OsCredentialStore vault = new OsCredentialStore() {
            @Override public boolean available() { return true; }
            @Override public String read(String target) { reads.incrementAndGet(); return SECRET; }
            @Override public void write(String target, String secret) { throw new AssertionError("check must not write"); }
            @Override public void delete(String target) { throw new AssertionError("check must not delete"); }
        };
        ConnectionService service = new ConnectionService(home, ignored -> null, vault);
        var added = service.apply("add", settings("local", "custom", "https://example.com/v1",
                "os-store:warden/local", true));
        reads.set(0);
        var checked = service.apply("check", action("local", revision(added)));
        check.eq("settings check uses the same injected vault as settings list", 1, reads.get());
        check.eq("the injected vault credential is configured", true, checked.report().get("credential_configured"));
        check.eq("a custom vault check does not assert remote authentication", false, checked.report().get("auth_verified"));
        check.eq("a custom vault check sends no inference", false, checked.report().get("inference_performed"));
        check.that("the injected vault check report contains no credential", !Json.write(checked.report()).contains(SECRET));
    }

    private static void vaultEditsHoldConnectionRevision(Check check, Path home, String operation) throws Exception {
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch allowMutation = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>(SECRET);
        AtomicInteger mutations = new AtomicInteger();
        OsCredentialStore vault = new OsCredentialStore() {
            @Override public boolean available() { return true; }
            @Override public String read(String target) { return value.get(); }
            private void block() {
                mutations.incrementAndGet();
                mutationEntered.countDown();
                try {
                    if (!allowMutation.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test mutation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test mutation interrupted");
                }
            }
            @Override public void write(String target, String secret) { block(); value.set(secret); }
            @Override public void delete(String target) { block(); value.set(null); }
        };
        ConnectionService service = new ConnectionService(home, ignored -> null, vault);
        var added = service.apply("add", settings("personal", "custom", "https://example.com/v1",
                "os-store:warden/personal", true));
        String originalRevision = revision(added);
        Path file = home.resolve("connections/personal.yaml");
        String originalYaml = Files.readString(file);
        Map<String, Object> credential = new LinkedHashMap<>(action("personal", originalRevision));
        if ("auth-set".equals(operation)) credential.put("secret", "synthetic-replacement-credential");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var mutation = pool.submit(() -> service.apply(operation, credential));
            check.that(operation + " reaches the vault", mutationEntered.await(10, TimeUnit.SECONDS));
            CountDownLatch editorStarted = new CountDownLatch(1);
            var editor = pool.submit(() -> {
                editorStarted.countDown();
                Map<String, Object> update = settings("personal", "custom", "https://example.com/v1", "env:NEW_KEY", true);
                update.put("revision", originalRevision);
                return service.apply("update", update);
            });
            check.that(operation + " concurrent editor starts", editorStarted.await(10, TimeUnit.SECONDS));
            boolean blocked = false;
            try { editor.get(200, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waitingForMutation) { blocked = true; }
            check.that(operation + " holds the connection edit lock through the vault side effect", blocked);
            check.eq(operation + " leaves the file unchanged while its vault mutation runs", originalYaml, Files.readString(file));
            allowMutation.countDown();
            check.eq(operation + " completes before a queued reference edit", 200, mutation.get(10, TimeUnit.SECONDS).status());
            check.eq(operation + " queued reference edit can then save", 200, editor.get(10, TimeUnit.SECONDS).status());
            check.that(operation + " performs its intended vault side effect",
                    "auth-set".equals(operation) ? "synthetic-replacement-credential".equals(value.get()) : value.get() == null);
            check.eq(operation + " stale vault edit is refused after a reference change", 409,
                    service.apply(operation, credential).status());
            check.eq(operation + " stale request does not touch the vault", 1, mutations.get());
            check.eq(operation + " final reference belongs to the later editor", "env:NEW_KEY",
                    new ConnectionStore(home).versioned("personal").connection().credentialRef());
            check.that(operation + " never stores the credential in the connection file", !Files.readString(file).contains(SECRET));
        } finally {
            allowMutation.countDown();
            pool.shutdownNow();
        }
    }

    private static void rejectedPayloads(Check check, Path home) throws Exception {
        ConnectionService service = service(home);
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, Object> raw = settings("bad", "openrouter", "https://openrouter.ai/api/v1", "env:KEY", true);
        raw.put("api_key", SECRET);
        invalid.add(raw);
        invalid.add(settings("bad", "openrouter", "https://openrouter.ai/api/v1", SECRET, true));
        invalid.add(settings("bad", "openrouter", "https://example.com/v1?key=" + SECRET, "env:KEY", true));
        invalid.add(settings("../escape", "custom", "https://example.com/v1", "env:KEY", true));
        invalid.add(settings("bad", "custom", "https://example.com/v1", null, true));
        Map<String, Object> booleanString = settings("bad", "custom", "https://example.com/v1", "env:KEY", true);
        booleanString.put("enabled", "true");
        invalid.add(booleanString);
        Map<String, Object> implicitAuth = settings("bad", "custom", "http://localhost:9999/v1", null, true);
        implicitAuth.remove("credential_ref");
        invalid.add(implicitAuth);
        for (Map<String, Object> input : invalid) {
            var outcome = service.apply("add", input);
            check.eq("invalid settings are a client error", 400, outcome.status());
            check.that("client errors do not reflect a supplied secret", !Json.write(outcome.report()).contains(SECRET));
        }
        check.that("refused input does not create configuration", !Files.exists(home));
        check.eq("unknown operations fail safely", 400, service.apply(SECRET, Map.of()).status());
        check.eq("null input fails safely", 400, service.apply("add", null).status());
        check.eq("mutation requires a revision", 400, service.apply("remove", Map.of("id", "missing")).status());
        check.eq("invalid revision format is a client error", 400,
                service.apply("disable", action("missing", SECRET)).status());
    }

    private static void referencesAndRaces(Check check, Path home) throws Exception {
        ConnectionService service = service(home);
        var added = service.apply("add", settings("shared", "custom", "https://example.com/v1", "env:KEY", true));
        String original = revision(added);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return service.apply("disable", action("shared", original)); });
            var two = pool.submit(() -> { start.await();
                Map<String, Object> update = settings("shared", "custom", "https://example.com/other", "env:KEY", true);
                update.put("revision", original);
                return service.apply("update", update);
            });
            start.countDown();
            List<Integer> statuses = List.of(one.get(10, TimeUnit.SECONDS).status(), two.get(10, TimeUnit.SECONDS).status())
                    .stream().sorted().toList();
            check.eq("concurrent editors have one winner and one conflict", List.of(200, 409), statuses);
        } finally { pool.shutdownNow(); }
        String current = new ConnectionStore(home).versioned("shared").revision();
        check.eq("a stale remove cannot delete a replacement", 409, service.apply("remove", action("shared", original)).status());
        Files.createDirectories(home.resolve("profiles"));
        Path profile = home.resolve("profiles/reader.yaml");
        Files.writeString(profile, "connection: shared\n");
        check.eq("a referenced connection cannot be removed from settings", "connection_in_use",
                service.apply("remove", action("shared", current)).report().get("code"));
        Files.writeString(profile, "invalid: [" + SECRET);
        var unsafeReferences = service.apply("remove", action("shared", current));
        check.eq("unreadable references fail closed", 409, unsafeReferences.status());
        check.that("reference errors do not expose malformed profile content", !Json.write(unsafeReferences.report()).contains(SECRET));
        Files.delete(profile);
        check.eq("the current unreferenced connection can be removed", 200, service.apply("remove", action("shared", current)).status());
        check.eq("an already removed connection is not found", 404, service.apply("remove", action("shared", current)).status());
        try (var files = Files.list(home.resolve("connections"))) {
            check.that("races and refusals leave no temporary files", files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    private static void unreadableAndMissing(Check check, Path home) throws Exception {
        ConnectionService service = service(home);
        check.eq("a missing connection has a stable error", "connection_not_found",
                service.apply("check", action("absent", "0".repeat(64))).report().get("code"));
        Files.createDirectories(home.resolve("connections"));
        Files.writeString(home.resolve("connections/not-yaml.yaml"), "auth: [" + SECRET);
        Map<String, Object> listed = service.list();
        check.eq("malformed files remain diagnosable", false, listed.get("ok"));
        check.that("list diagnostics contain no malformed values", !Json.write(listed).contains(SECRET));
        Path binary = home.resolve("connections/binary.yaml");
        byte[] original = new byte[] {(byte) 0xff, 0x00, 0x41};
        Files.write(binary, original);
        Map<String, Object> binaryEdit = settings("binary", "custom", "http://127.0.0.1:9/v1", "env:KEY", true);
        binaryEdit.put("revision", "ab".repeat(32));
        var refusedEncoding = service.apply("update", binaryEdit);
        check.eq("a non-UTF-8 connection file is refused", 400, refusedEncoding.status());
        check.that("refusal does not rewrite non-UTF-8 bytes", java.util.Arrays.equals(original, Files.readAllBytes(binary)));
        check.that("list diagnostics do not echo non-UTF-8 bytes", !Json.write(service.list()).contains("\uFFFD"));
        Files.delete(binary);
        Files.delete(home.resolve("connections/not-yaml.yaml"));
        Files.deleteIfExists(home.resolve("connections/.connections.lock"));
        Files.delete(home.resolve("connections"));
        Files.writeString(home.resolve("connections"), "not a directory");
        check.eq("a malformed directory is a safe list diagnostic", false, service.list().get("ok"));
        check.eq("an unavailable store has a safe status", 503,
                service.apply("add", settings("new", "openrouter", "https://openrouter.ai/api/v1", "env:KEY", true)).status());
    }

    private static Map<String, Object> settings(String id, String provider, String base, String reference, boolean enabled) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("provider", provider); result.put("base_url", base);
        result.put("credential_ref", reference); result.put("enabled", enabled);
        return result;
    }

    private static Map<String, Object> action(String id, String revision) { return Map.of("id", id, "revision", revision); }
    @SuppressWarnings("unchecked") private static String revision(ConnectionService.Outcome outcome) {
        return (String) ((Map<String, Object>) outcome.report().get("connection")).get("revision");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> first(Map<String, Object> listed) {
        return (Map<String, Object>) ((List<?>) listed.get("connections")).get(0);
    }
}
