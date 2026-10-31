package dev.warden;

import dev.warden.config.ConfigException;
import dev.warden.config.Connection;
import dev.warden.config.ConnectionCommand;
import dev.warden.config.ConnectionStore;
import dev.warden.execution.CredentialResolver;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class ConnectionTest implements Suite {
    private static final String OPENROUTER = """
            version: 1
            connection: openrouter-main
            provider: openrouter
            protocol: openai_chat
            base_url: https://openrouter.ai/api/v1
            auth:
              type: api_key
              credential_ref: env:WARDEN_OPENROUTER_KEY
            enabled: true
            """;

    @Override public String name() { return "connections"; }

    @Override public void run(Check check) throws Exception {
        parsingAndDestinations(check);
        credentials(check);
        store(check);
    }

    private void parsingAndDestinations(Check check) {
        Connection connection = Connection.parse(OPENROUTER, "connection.yaml");
        check.eq("OpenRouter endpoint is normalized", "https://openrouter.ai/api/v1/chat/completions",
                connection.endpoint());
        check.eq("environment reference is a name", "WARDEN_OPENROUTER_KEY", connection.apiKeyEnv());
        check.eq("connection serialization round trips", connection,
                Connection.parse(connection.toYaml(), "round-trip.yaml"));
        check.eq("trailing slash does not duplicate endpoint path", connection,
                Connection.parse(OPENROUTER.replace("/api/v1\n", "/api/v1/\n"), "slash.yaml"));
        check.eq("fingerprint has SHA-256 width", 64, connection.fingerprint().length());
        check.eq("fingerprint is deterministic", connection.fingerprint(),
                Connection.parse(OPENROUTER, "elsewhere.yaml").fingerprint());
        check.that("disabling changes configuration fingerprint",
                !connection.fingerprint().equals(connection.withEnabled(false).fingerprint()));
        check.that("credential reference change invalidates fingerprint",
                !connection.fingerprint().equals(Connection.parse(OPENROUTER.replace("WARDEN_OPENROUTER_KEY",
                        "ANOTHER_KEY"), "other.yaml").fingerprint()));

        Connection gonka = new Connection("gonka", "gonkarouter", "openai_chat",
                "https://api.gonkarouter.io/v1", "env:GONKA_KEY", true);
        check.eq("GonkaRouter has its own fixed endpoint", "https://api.gonkarouter.io/v1/chat/completions",
                gonka.endpoint());
        Connection join = new Connection("join", "joingonka", "openai_chat",
                "https://gate.joingonka.ai/v1", "env:JOINGONKA_API_KEY", true);
        check.eq("JoinGonka has its own fixed endpoint", "https://gate.joingonka.ai/v1/chat/completions",
                join.endpoint());
        check.eq("JoinGonka references round trip through YAML", join,
                Connection.parse(join.toYaml(), "join.yaml"));
        check.eq("JoinGonka may use its own vault reference", "warden/join",
                new Connection("join", "joingonka", "openai_chat", join.baseUrl(),
                        "os-store:warden/join", true).osStoreTarget());
        for (String destination : new String[]{"https://api.gonkarouter.io/v1",
                "https://gate.joingonka.ai/api/v1", "https://gate.joingonka.ai:444/v1"}) {
            check.rejects("JoinGonka credentials remain bound to the selected endpoint", "selected provider", () ->
                    new Connection("join", "joingonka", "openai_chat", destination, "env:JOINGONKA_API_KEY", true));
        }
        check.rejects("JoinGonka cannot silently omit credentials", "auth none", () ->
                new Connection("join", "joingonka", "openai_chat", join.baseUrl(), null, true));
        check.rejects("preset cannot send its credential to a different host", "selected provider",
                () -> Connection.parse(OPENROUTER.replace("openrouter.ai", "example.com"), "wrong-host.yaml"));
        check.rejects("preset cannot send its credential to a different API path", "selected provider",
                () -> Connection.parse(OPENROUTER.replace("/api/v1", "/another"), "wrong-path.yaml"));
        check.rejects("preset cannot use a different port", "selected provider",
                () -> Connection.parse(OPENROUTER.replace("openrouter.ai", "openrouter.ai:444"), "port.yaml"));
        check.rejects("remote endpoints need HTTPS", "HTTPS", () ->
                new Connection("remote", "custom", "openai_chat", "http://example.com/v1", "env:KEY", true));
        check.rejects("remote anonymous endpoints are refused", "auth none", () ->
                new Connection("remote", "custom", "openai_chat", "https://example.com/v1", null, true));
        check.rejects("only the implemented protocol is accepted", "openai_chat", () ->
                Connection.parse(OPENROUTER.replace("openai_chat", "anthropic_messages"), "protocol.yaml"));
        check.rejects("version is mandatory", "version must be 1", () ->
                Connection.parse(OPENROUTER.replace("version: 1\n", ""), "version.yaml"));
        check.rejects("unknown authentication fields cannot store a raw key", "unsupported key 'auth.api_key'", () ->
                Connection.parse(OPENROUTER.replace("  type: api_key", "  type: api_key\n  api_key: dummy-secret"), "key.yaml"));
        check.rejects("authentication must be explicit", "auth.type", () ->
                Connection.parse(OPENROUTER.replace("  type: api_key\n", ""), "missing-auth.yaml"));
        check.rejects("credential references accept environment names only", "env:", () ->
                Connection.parse(OPENROUTER.replace("env:WARDEN_OPENROUTER_KEY", "raw-secret"), "raw.yaml"));
        check.rejects("environment name cannot contain punctuation", "environment variable name", () ->
                new Connection("remote", "custom", "openai_chat", "https://example.com/v1", "env:KEY;command", true));
        check.rejects("id cannot escape the configuration directory", "connection id", () ->
                new Connection("../elsewhere", "custom", "openai_chat", "https://example.com/v1", "env:KEY", true));

        for (String destination : new String[]{"https://user:dummy-secret@example.com/v1",
                "https://example.com/v1?key=dummy-secret", "https://example.com/v1#dummy-secret",
                "not a URL dummy-secret"}) {
            try {
                new Connection("remote", "custom", "openai_chat", destination, "env:KEY", true);
                check.that("invalid credential-bearing destination is rejected", false);
            } catch (ConfigException failure) {
                check.that("destination validation does not echo a pasted secret",
                        !failure.getMessage().contains("dummy-secret"));
            }
        }
        for (String host : new String[]{"localhost", "127.0.0.1", "127.23.45.67", "[::1]"}) {
            Connection local = new Connection("local", "custom", "openai_chat", "http://" + host + ":8080/v1", null, true);
            check.eq("explicit loopback permits anonymous local inference", null, local.apiKeyEnv());
            check.eq("anonymous loopback serializes explicit auth none", local,
                    Connection.parse(local.toYaml(), "local.yaml"));
        }
        for (String host : new String[]{"localhost.example.com", "127.0.0.1.example.com", "0.0.0.0", "192.168.0.1"}) {
            check.rejects("remote-looking host is not trusted as loopback", "HTTPS", () ->
                    new Connection("remote", "custom", "openai_chat", "http://" + host + "/v1", "env:KEY", true));
        }
    }

    private void credentials(Check check) {
        Connection connection = Connection.parse(OPENROUTER, "connection.yaml");
        AtomicReference<String> key = new AtomicReference<>("dummy-secret-one");
        CredentialResolver resolver = new CredentialResolver(name -> key.get());
        String before = connection.fingerprint();
        check.eq("dispatch resolves the current environment value", "dummy-secret-one", resolver.resolve(connection));
        check.eq("credential status has only nonsecret fields", Map.of(
                "credential_present", true, "source", "env:WARDEN_OPENROUTER_KEY", "state", "ready"),
                resolver.status(connection));
        check.that("status never contains the credential", !resolver.status(connection).toString().contains("dummy-secret"));
        check.that("record never contains the credential", !connection.toString().contains("dummy-secret"));
        check.that("serialized file never contains the credential", !connection.toYaml().contains("dummy-secret"));
        key.set("dummy-secret-two");
        check.eq("rotation is picked up at the next dispatch", "dummy-secret-two", resolver.resolve(connection));
        check.eq("rotation leaves nonsecret fingerprint stable", before, connection.fingerprint());
        key.set("dummy-secret\r\ninjected: value");
        check.eq("header injection is an invalid credential", "invalid", resolver.status(connection).get("state"));
        check.rejects("invalid token fails before HTTP can quote the header", "unsupported characters", () -> resolver.resolve(connection));
        key.set(" ");
        check.eq("blank credential is missing", "missing", resolver.status(connection).get("state"));
        check.rejects("missing credential fails before dispatch", "credential is missing", () -> resolver.resolve(connection));
        check.eq("disabled connection is explicit", "disabled", resolver.status(connection.withEnabled(false)).get("state"));
        check.rejects("disabled credentials cannot dispatch", "disabled", () -> resolver.resolve(connection.withEnabled(false)));
        CredentialResolver failed = new CredentialResolver(name -> { throw new IllegalArgumentException("dummy-secret"); });
        check.eq("source failures are a safe status", "unavailable", failed.status(connection).get("state"));
        try {
            failed.resolve(connection);
            check.that("credential source error fails", false);
        } catch (IllegalStateException failure) {
            check.that("credential source exceptions never expose values or causes",
                    !failure.getMessage().contains("dummy-secret") && failure.getCause() == null);
        }
        Connection local = new Connection("local", "custom", "openai_chat", "http://localhost:8080/v1", null, true);
        check.eq("auth none never reaches an environment source", null, failed.resolve(local));
        check.eq("auth none has a distinct status", "not_required", failed.status(local).get("state"));
    }

    private void store(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-connections-");
        try {
            Path presetHome = home.resolve("preset");
            ConnectionCommand command = new ConnectionCommand(ignored -> null);
            Map<String, Object> added = command.run(presetHome, new String[]{"connections", "add", "join",
                    "--provider", "joingonka", "--env", "JOINGONKA_API_KEY"});
            Connection preset = new ConnectionStore(presetHome).load().connections().get("join");
            check.eq("CLI JoinGonka preset selects its documented URL", "https://gate.joingonka.ai/v1", preset.baseUrl());
            check.eq("CLI JoinGonka preset does not activate profiles", false, added.get("profiles_activated"));
            Map<String, Object> checked = command.run(presetHome, new String[]{"connections", "check", "join"});
            check.eq("missing JoinGonka credential fails locally", "role_api_key_missing", checked.get("code"));
            check.eq("JoinGonka credential check performs no inference", false, checked.get("inference_performed"));
            ConnectionStore store = new ConnectionStore(home);
            check.eq("an empty home has no connections", Map.of(), store.load().connections());
            check.that("read-only load does not create the connections directory", !Files.exists(home.resolve("connections")));
            Connection connection = Connection.parse(OPENROUTER, "connection.yaml");
            Path file = store.add(connection);
            check.eq("connection file belongs to supplied home", home.resolve("connections/openrouter-main.yaml"), file);
            check.eq("saved connection loads", connection, store.load().connections().get(connection.id()));
            String original = Files.readString(file);
            check.rejects("add never replaces an existing connection", "already exists", () -> store.add(connection.withEnabled(false)));
            check.eq("refused add preserves the existing file byte for byte", original, Files.readString(file));
            check.eq("disable persists", false, store.setEnabled(connection.id(), false).enabled());
            check.eq("disabled connection remains visible", false, store.load().connections().get(connection.id()).enabled());
            check.eq("enable persists", true, store.setEnabled(connection.id(), true).enabled());

            Files.writeString(home.resolve("connections/malformed.yaml"), "auth: [unterminated dummy-secret");
            Files.writeString(home.resolve("connections/mismatch.yaml"), connection.toYaml());
            Files.writeString(home.resolve("connections/invalid.yaml"), OPENROUTER.replace("openrouter-main", "invalid")
                    .replace("env:WARDEN_OPENROUTER_KEY", "dummy-secret"));
            ConnectionStore.Snapshot snapshot = store.load();
            check.eq("bad connections do not hide the good one", 1, snapshot.connections().size());
            check.eq("all malformed connections remain diagnosable", 3, snapshot.problems().size());
            check.that("malformed input and invalid credentials are not echoed", !snapshot.problems().toString().contains("dummy-secret"));
            check.rejects("snapshot connections are immutable", null, () -> snapshot.connections().clear());
            check.rejects("snapshot diagnostics are immutable", null, () -> snapshot.problems().clear());

            Files.createDirectories(home.resolve("profiles"));
            Path profile = home.resolve("profiles/api-review.yaml");
            Files.writeString(profile, "version: 1\nprofile: api-review\nconnection: openrouter-main\n");
            check.rejects("referenced connection cannot be removed", "referenced by a profile", () -> store.remove(connection.id()));
            check.that("refused removal preserves connection", Files.exists(file));
            Files.writeString(profile, "invalid: [unterminated dummy-secret");
            check.rejects("unparseable profiles cannot hide a reference", "cannot check profile references", () -> store.remove(connection.id()));
            Files.writeString(profile, "version: 1\nprofile: api-review\nconnection: another\n");
            store.remove(connection.id());
            check.that("unreferenced connection is removed", !Files.exists(file));
            check.rejects("invalid remove cannot traverse outside home", "connection id", () -> store.remove("../outside"));
            try (Stream<Path> files = Files.list(home.resolve("connections"))) {
                check.that("successful and refused writes leave no temporary files",
                        files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            try (Stream<Path> paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
