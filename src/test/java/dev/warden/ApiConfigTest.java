package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.ConfigLoader;
import dev.warden.config.ConnectionCommand;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.UserConfig;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;
import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** User-owned connection lifecycle, verification binding and CLI/API identity integration. */
public final class ApiConfigTest implements Suite {
    @Override public String name() { return "api-config"; }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-api-config-");
        try (LocalApi api = new LocalApi()) {
            connectionLifecycle(check, sandbox.resolve("lifecycle"), api);
            nativeVerificationAndInvalidation(check, sandbox.resolve("verification"), api);
            legacyIdentityIsPreserved(check, sandbox.resolve("legacy"));
            optionalConnectionsCannotDisableCli(check, sandbox.resolve("optional-connections"));
            roleRunnerReceivesSourceAndRefusesUnsafeContext(check, sandbox.resolve("role-context"), api);
        } finally {
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    // Git's loose objects are read-only on Windows. Only this scratch tree is touched.
                    if (!path.toAbsolutePath().normalize().startsWith(sandbox.toAbsolutePath().normalize())) {
                        throw new IOException("refused cleanup outside the API test scratch directory");
                    }
                    if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void connectionLifecycle(Check check, Path home, LocalApi api) throws Exception {
        String secret = "API_CONFIG_SYNTHETIC_SECRET_NEVER_PERSIST";
        ConnectionCommand command = new ConnectionCommand(name -> "API_CONFIG_KEY".equals(name) ? secret : null);
        Map<String, Object> added = command.run(home, new String[] {"connections", "add", "local-api",
                "--provider", "custom", "--base-url", api.base(), "--env", "API_CONFIG_KEY"});
        check.eq("connection add does not activate model profiles", false, added.get("profiles_activated"));
        check.that("add report contains no resolved secret", !Json.write(added).contains(secret));
        Path connectionFile = home.resolve("connections/local-api.yaml");
        check.contains("the stored connection has a reference", Files.readString(connectionFile), "env:API_CONFIG_KEY");
        check.that("the stored connection has no credential value", !Files.readString(connectionFile).contains(secret));
        Map<String, Object> listed = command.run(home, new String[] {"connections", "list"});
        check.eq("list returns the saved connection", 1, ((List<?>) listed.get("connections")).size());
        check.that("list reports no resolved secret", !Json.write(listed).contains(secret));
        check.rejects("add never overwrites an existing connection", "already exists",
                () -> command.run(home, new String[] {"connections", "add", "local-api", "--provider", "custom",
                        "--base-url", api.base(), "--no-auth"}));
        check.rejects("CLI arguments cannot accept an API key value", "key values are not accepted",
                () -> command.run(home, new String[] {"connections", "add", "unsafe", "--api-key", secret}));
        check.that("rejected key arguments do not create a file", !Files.exists(home.resolve("connections/unsafe.yaml")));

        int beforeCheck = api.calls.get();
        Map<String, Object> checked = command.run(home, new String[] {"connections", "check", "local-api"});
        check.eq("a custom connection check is explicit about unverified authentication", false, checked.get("auth_verified"));
        check.eq("a connection check does no inference", false, checked.get("inference_performed"));
        check.eq("a key check does not verify a model", false, checked.get("model_verified"));
        check.eq("local credential check sends no network request", beforeCheck, api.calls.get());
        check.that("check report contains no resolved secret", !Json.write(checked).contains(secret));

        Path profileFile = writeProfile(home, "local-api", "deepseek", "", "");
        UserConfig loaded = UserConfig.load(home);
        check.eq("binding a connection loads cleanly", Map.of(), loaded.problems());
        Profile profile = loaded.profiles().get("api-review");
        check.eq("a loaded API profile holds its connection snapshot", "local-api", profile.apiConnection().id());
        check.eq("binding supplies the complete HTTP endpoint", api.base() + "/chat/completions", profile.endpoint());
        check.eq("binding supplies the referenced variable name", "API_CONFIG_KEY", profile.apiKeyEnv());
        check.that("binding does not stamp a newly added profile", !profile.verified());

        String original = Files.readString(profileFile);
        Files.writeString(profileFile, original.replace("connection: local-api", "connection: missing-api"));
        UserConfig missing = UserConfig.load(home);
        check.that("a dangling API connection cannot enter the roster", !missing.profiles().containsKey("api-review"));
        check.contains("the missing connection is diagnosed", missing.problems().get("api-review"), "unavailable connection");
        Files.writeString(profileFile, original);

        String commented = "# Keep the operator's endpoint notes\n" + Files.readString(connectionFile)
                .replace("enabled: true", "enabled: true # Enable only for selected roles");
        Files.writeString(connectionFile, commented);
        command.run(home, new String[] {"connections", "disable", "local-api"});
        check.eq("disabling preserves connection comments and layout",
                commented.replace("enabled: true", "enabled: false"), Files.readString(connectionFile));
        Profile disabled = UserConfig.load(home).profiles().get("api-review");
        check.that("disabled connection remains bound for diagnostics", !disabled.apiConnection().enabled());
        Policy policy = reviewPolicy("api-review");
        RoleResolver.Candidate rejected = new RoleResolver().candidates("reviewer", policy,
                Map.of("api-review", disabled), RoleResolver.Writers.NONE, ignored -> true, Set.of()).getFirst();
        check.eq("the resolver refuses a disabled connection before profile verification", "connection_unavailable", rejected.rejected());
        check.eq("disabled check sends no inference", "role_api_connection_disabled",
                command.run(home, new String[] {"connections", "check", "local-api"}).get("code"));
        command.run(home, new String[] {"connections", "enable", "local-api"});
        check.eq("reenabling preserves the original connection text", commented, Files.readString(connectionFile));
        check.that("enable restores the bound connection", UserConfig.load(home).profiles().get("api-review").apiConnection().enabled());
        check.rejects("remove protects an unverified profile reference", "referenced by a profile",
                () -> command.run(home, new String[] {"connections", "remove", "local-api"}));
        Files.delete(profileFile);
        check.eq("an unreferenced connection can be removed", true,
                command.run(home, new String[] {"connections", "remove", "local-api"}).get("ok"));
        check.that("removal removes only the connection file", !Files.exists(connectionFile));
    }

    private void nativeVerificationAndInvalidation(Check check, Path home, LocalApi api) throws Exception {
        ConnectionCommand command = new ConnectionCommand(ignored -> null);
        command.run(home, new String[] {"connections", "add", "probe-api", "--provider", "custom",
                "--base-url", api.base(), "--no-auth"});
        Path template = home.resolve("prompts/reviewer.md");
        Path schema = home.resolve("schemas/reviewer.json");
        Files.createDirectories(template.getParent());
        Files.createDirectories(schema.getParent());
        Files.writeString(template, "Review the supplied context for {{goal}}.");
        Files.writeString(schema, "{\"type\":\"object\",\"required\":[\"status\",\"verdict\",\"summary\",\"findings\"]}");
        Path shellMarker = home.resolve("shell-probe-must-not-run.txt");
        String shellProbe = "echo shell-probe-was-executed > \"" + shellMarker.toAbsolutePath() + "\"";
        Path file = writeProfile(home, "probe-api", "deepseek",
                "prompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\nenforce_schema: true\n",
                "  probe: " + yaml(shellProbe) + "\n");
        Profile profile = requireProfile(home);
        ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner());
        int before = api.calls.get();
        ProfileVerifier.Probe probe = verifier.run(profile, home, Duration.ofSeconds(10));
        check.that("the native API verifier accepts a synthetic structured artifact", probe.ok());
        check.eq("native verification makes one bounded completion", before + 1, api.calls.get());
        check.that("verification.probe is not executed by an API profile", !Files.exists(shellMarker));
        check.contains("the native verifier sends a synthetic transport task", api.lastBody, "synthetic transport probe");
        check.contains("the probe carries its declared schema", api.lastBody, "required");
        check.eq("native probe command is a safe built-in description", "warden profiles --verify api-review", probe.command());
        Map<String, Object> receipt = Json.parseObject(Files.readString(probe.transcript()));
        check.eq("verification explicitly excludes coding capabilities", "text_artifact_only", receipt.get("verification_scope"));
        check.that("a successful probe alone does not stamp the profile", !requireProfile(home).verified());
        check.that("stamping the successful configuration writes its fingerprint",
                verifier.stamp(file, profile, LocalDate.of(2026, 10, 26)).written());
        Profile verified = requireProfile(home);
        check.that("the unchanged bound profile becomes eligible after stamping", verified.verified());
        String verifiedFingerprint = verified.apiFingerprint();
        check.eq("the receipt fingerprint matches the bound configuration", verifiedFingerprint, receipt.get("profile_fingerprint"));
        var store = new dev.warden.config.ConnectionStore(home);
        Path connectionPath = home.resolve("connections/probe-api.yaml");
        String savedConnection = Files.readString(connectionPath);
        var pending = new dev.warden.execution.RoleExecutor.Request("runtime", "runtime", "reviewer", verified,
                null, null, home, home.resolve("runtime"), template, schema, "", "reviewer", List.of());
        int beforeRecheck = api.calls.get();
        store.setEnabled("probe-api", false);
        var disabledPending = new dev.warden.execution.ApiExecutor().execute(pending);
        check.eq("a running loop sees a newly disabled connection before its next dispatch",
                "role_api_connection_disabled", disabledPending.code());
        check.eq("disabled cached profiles send no HTTP request", beforeRecheck, api.calls.get());
        check.eq("the refused cached dispatch is not a vendor turn", false, disabledPending.evidence().get("vendor_turn_started"));
        Files.writeString(connectionPath, savedConnection.replace(api.base(), api.base() + "/changed"));
        check.eq("a running loop never silently switches to an edited endpoint",
                "role_api_connection_changed", new dev.warden.execution.ApiExecutor().execute(pending).code());
        check.eq("edited cached routes send no HTTP request", beforeRecheck, api.calls.get());
        Files.delete(connectionPath);
        check.eq("a removed cached connection cannot dispatch", "role_api_connection_missing",
                new dev.warden.execution.ApiExecutor().execute(pending).code());
        Files.writeString(connectionPath, savedConnection);
        String stamped = Files.readString(file);
        String connectionText = Files.readString(home.resolve("connections/probe-api.yaml"));
        String templateText = Files.readString(template);
        String schemaText = Files.readString(schema);

        List<Mutation> mutations = List.of(
                new Mutation("model", () -> Files.writeString(file, stamped.replace("model: test/model", "model: test/other"))),
                new Mutation("output token limit", () -> Files.writeString(file, stamped.replace("max_output_tokens: 128", "max_output_tokens: 256"))),
                new Mutation("connection endpoint", () -> Files.writeString(home.resolve("connections/probe-api.yaml"),
                        connectionText.replace(api.base(), api.base() + "/changed"))),
                new Mutation("schema reference", () -> {
                    Files.writeString(schema.resolveSibling("other.json"), schemaText);
                    Files.writeString(file, stamped.replace("schemas/reviewer.json", "schemas/other.json"));
                }),
                new Mutation("schema content", () -> Files.writeString(schema,
                        "{\"type\":\"object\",\"description\":\"changed schema\",\"required\":[\"status\",\"verdict\",\"summary\",\"findings\"]}")),
                new Mutation("prompt template content", () -> Files.writeString(template, templateText + " Read every supplied finding.")));
        for (Mutation mutation : mutations) {
            Files.writeString(file, stamped);
            Files.writeString(home.resolve("connections/probe-api.yaml"), connectionText);
            Files.writeString(template, templateText);
            Files.writeString(schema, schemaText);
            mutation.edit.run();
            if (mutation.name.equals("schema content") || mutation.name.equals("prompt template content")) {
                check.that("cached profile detects changed " + mutation.name + " without reload", !verified.verified());
            }
            Profile changed = requireProfile(home);
            check.that("changing " + mutation.name + " invalidates the stamp", !changed.verified());
            check.that("changing " + mutation.name + " changes the configuration fingerprint",
                    !verifiedFingerprint.equals(changed.apiFingerprint()));
        }
        Files.writeString(file, stamped);
        Files.writeString(home.resolve("connections/probe-api.yaml"), connectionText);
        Files.writeString(template, templateText);
        Files.writeString(schema, schemaText);
        check.that("restoring the exact configuration restores its verified identity", requireProfile(home).verified());

        Files.writeString(file, stamped.replace(verifiedFingerprint, "stale-fingerprint")
                .replace("2026-10-26", "2026-10-01"));
        Profile stale = requireProfile(home);
        check.that("a stale API stamp is not accepted by load", !stale.verified());
        check.that("stamping a stale block replaces its old keys", verifier.stamp(file, stale, LocalDate.of(2026, 10, 26)).written());
        String replaced = Files.readString(file);
        check.eq("stamping keeps exactly one date", 1L, replaced.lines().filter(line -> line.stripLeading().startsWith("verified_on:")).count());
        check.eq("stamping keeps exactly one fingerprint", 1L, replaced.lines().filter(line -> line.stripLeading().startsWith("connection_fingerprint:")).count());
        check.that("the replaced stamp is usable", requireProfile(home).verified());
        check.that("the operator's dormant legacy probe is preserved", replaced.contains(yaml(shellProbe)));
    }

    private void legacyIdentityIsPreserved(Check check, Path home) {
        Profile legacy = new Profile("cli-review", "reviewer", "codex", "cli-model", null,
                "codex", List.of(), true, 1, null, null, null, true, List.of(), List.of(),
                "argv", null, null, "direct", null, null, null, List.of(), true);
        UserConfig oldConstructor = new UserConfig(home, null, Map.of("cli-review", legacy), Map.of(), false);
        check.eq("legacy UserConfig callers have no implicit API connections", Map.of(), oldConstructor.connections());
        check.eq("legacy Profile constructor still represents its original runner", "direct", legacy.runner());
        check.eq("legacy Profile constructor creates no API connection", null, legacy.apiConnection());
        check.that("Codex and OpenAI share a model developer identity", RoleResolver.Writers.of("codex").contains("openai"));
        check.that("identity matching works in both directions", RoleResolver.Writers.of("openai").contains("codex"));
        check.that("unrelated developers stay distinct", !RoleResolver.Writers.of("codex").contains("deepseek"));
        RoleResolver.Candidate candidate = new RoleResolver().candidates("reviewer", reviewPolicy("cli-review"),
                Map.of("cli-review", legacy), RoleResolver.Writers.of("openai"), ignored -> true, Set.of()).getFirst();
        check.eq("a CLI alias cannot become an independent reader of its own API vendor", "same_vendor_as_writer", candidate.rejected());
        check.rejects("a known model prefix cannot be assigned an unrelated developer identity",
                "model_vendor does not match the known model id prefix", () -> Profile.parse("""
                        version: 1
                        profile: false-independent
                        role: reviewer
                        model_vendor: deepseek
                        model: openai/test-model
                        runner: api
                        connection: local-api
                        read_only: true
                        """, "synthetic API identity"));
    }

    private void optionalConnectionsCannotDisableCli(Check check, Path home) throws Exception {
        Files.createDirectories(home.resolve("profiles"));
        Files.writeString(home.resolve("connections"), "A misplaced file, not a directory.");
        Files.writeString(home.resolve("profiles/cli-review.yaml"), """
                version: 1
                profile: cli-review
                role: reviewer
                vendor: codex
                command: codex
                read_only: true
                verification:
                  verified_on: "2026-10-26"
                """);
        UserConfig user = UserConfig.load(home);
        check.that("a malformed optional connections directory preserves existing CLI profiles",
                user.profiles().containsKey("cli-review"));
        check.eq("existing CLI verification remains usable", true, user.profiles().get("cli-review").verified());
        check.eq("a malformed optional directory contributes no API connections", Map.of(), user.connections());
        check.that("the optional directory error has a stable diagnostic key", user.problems().containsKey("connections/directory"));
    }

    private void roleRunnerReceivesSourceAndRefusesUnsafeContext(Check check, Path sandbox, LocalApi api)
            throws Exception {
        Path project = sandbox.resolve("project");
        Path home = sandbox.resolve("home");
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".gitignore"), ".warden/runs/\n");
        Files.writeString(project.resolve("src/Example.java"), "class Original {}\n");
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: api-context-integration
                base_ref: HEAD
                checks: { fast: ["git diff --check"] }
                scopes: { app: ["src"] }
                defaults: { checks: fast, risk: medium }
                """);
        Files.writeString(project.resolve(".warden/tasks/review-source.yaml"), """
                version: 1
                id: review-source
                goal: Review the changed example source
                risk: medium
                scope: app
                """);
        git(project, "init", "-q", "-b", "main");
        git(project, "config", "core.autocrlf", "false");
        git(project, "add", "-A");
        git(project, "-c", "user.email=fixture@example.invalid", "-c", "user.name=fixture", "-c",
                "commit.gpgsign=false", "-c", "core.hooksPath=", "commit", "-qm", "test: seed disposable API context");

        new ConnectionCommand(ignored -> null).run(home, new String[] {"connections", "add", "source-api",
                "--provider", "custom", "--base-url", api.base(), "--no-auth"});
        Files.createDirectories(home.resolve("prompts"));
        Files.writeString(home.resolve("prompts/reviewer.md"), "Review the supplied source for {{goal}}. Return the required JSON artifact.");
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  reviewer: { profiles: [api-review], strategy: first, require_independent_vendor: true }
                """);
        Path profileFile = writeProfile(home, "source-api", "deepseek", "prompt_template: prompts/reviewer.md\n", "");
        new ProfileVerifier(new ProcessRunner()).stamp(profileFile, requireProfile(home), LocalDate.of(2026, 10, 26));
        Files.writeString(project.resolve("src/Example.java"), "class Changed { int answer = 42; }\n");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "review-source");
        UserConfig user = UserConfig.load(home);
        check.that("the source integration profile has a valid configuration stamp", user.profiles().get("api-review").verified());
        int calls = api.calls.get();
        RoleRunner.Outcome dry = new RoleRunner(new ProcessRunner())
                .run(loaded, user, "reviewer", "api-source-dry", (String) null, null, true);
        check.eq("an API role dry run renders without dispatching", "dry_run", dry.code());
        Path promptFile = project.resolve(".warden/runs/api-source-dry/prompts/reviewer.md");
        String prompt = Files.readString(promptFile);
        check.contains("RoleRunner supplies full changed source to the API prompt", prompt, "class Changed { int answer = 42; }");
        check.contains("RoleRunner supplies the pinned source diff too", prompt, "-class Original");
        check.contains("RoleRunner identifies the changed-source snapshot", prompt, "API CHANGED-SOURCE CONTEXT v1");
        check.contains("text-only limitations are part of the actual role prompt", prompt, "no filesystem, shell, network");
        check.eq("the source dry run sends no HTTP request", calls, api.calls.get());

        Path secretFile = project.resolve(".env");
        String privateText = "SOURCE_CONTEXT_SECRET_MUST_NEVER_BE_PERSISTED";
        Files.writeString(secretFile, "API_KEY=" + privateText + "\n");
        RoleRunner.Outcome secret = new RoleRunner(new ProcessRunner())
                .run(loaded, user, "reviewer", "api-source-secret", (String) null, null, true);
        check.eq("RoleRunner refuses changed credentials before dispatch", "role_api_context_unavailable", secret.code());
        check.eq("context refusal is not a vendor turn", false, secret.details().get("vendor_turn_started"));
        check.that("a refused secret context is never persisted as a prompt",
                !Files.exists(project.resolve(".warden/runs/api-source-secret/prompts/reviewer.md")));
        check.that("refusal diagnostics do not retain source secrets", !Json.write(secret.details()).contains(privateText));
        check.eq("secret context refusal sends no HTTP request", calls, api.calls.get());
        Files.delete(secretFile);

        Path binaryFile = project.resolve("src/binary.dat");
        Files.write(binaryFile, new byte[] {0, 1, 2, 3});
        RoleRunner.Outcome binary = new RoleRunner(new ProcessRunner())
                .run(loaded, user, "reviewer", "api-source-binary", (String) null, null, true);
        check.eq("RoleRunner refuses binary context before dispatch", "role_api_context_unavailable", binary.code());
        check.that("a refused binary context is never persisted as a prompt",
                !Files.exists(project.resolve(".warden/runs/api-source-binary/prompts/reviewer.md")));
        check.eq("binary context refusal sends no HTTP request", calls, api.calls.get());
    }

    private static void git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessRunner.Result result = new ProcessRunner().run(command, root, Duration.ofSeconds(30));
        if (!result.ok()) throw new IOException("could not prepare disposable API integration fixture: " + result.stderr());
    }

    private static Profile requireProfile(Path home) throws IOException {
        UserConfig loaded = UserConfig.load(home);
        Profile profile = loaded.profiles().get("api-review");
        if (profile == null) throw new IOException("API test profile did not load: " + loaded.problems());
        return profile;
    }

    private static Path writeProfile(Path home, String connection, String vendor, String extra,
                                     String verification) throws IOException {
        Files.createDirectories(home.resolve("profiles"));
        Path file = home.resolve("profiles/api-review.yaml");
        Files.writeString(file, """
                version: 1
                profile: api-review
                role: reviewer
                model_vendor: %s
                model: test/model
                runner: api
                connection: %s
                read_only: true
                limits: { wall_clock_minutes: 1, max_output_tokens: 128 }
                artifact:
                  required_fields: [status, verdict, summary, findings]
                %sverification:
                %s""".formatted(vendor, connection, extra,
                verification.isEmpty() ? "  what_to_check: [text artifact only]\n" : verification));
        return file;
    }

    private static Policy reviewPolicy(String profile) {
        return Policy.parse("version: 1\nroles:\n  reviewer: { profiles: [" + profile
                + "], strategy: first, require_independent_vendor: true }\n", "test policy");
    }

    private static String yaml(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @FunctionalInterface private interface Edit { void run() throws Exception; }
    private record Mutation(String name, Edit edit) { }

    private static final class LocalApi implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String lastBody = "";
        private LocalApi() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String artifact = Json.write(Map.of("role", "reviewer", "task_id", "api-probe", "run_id", "api-probe",
                        "status", "completed", "verdict", "PASS", "summary", "Synthetic text artifact", "findings", List.of()));
                byte[] response = Json.write(Map.of("model", "test/model", "choices", List.of(Map.of(
                        "finish_reason", "stop", "message", Map.of("content", artifact))))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
        }
        private String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }
}
