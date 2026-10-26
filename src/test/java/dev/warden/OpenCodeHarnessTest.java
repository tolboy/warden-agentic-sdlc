package dev.warden;

import dev.warden.config.Connection;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.TaskSpec;
import dev.warden.execution.Executors;
import dev.warden.execution.OpenCodeExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline OpenCode contract. Synthetic credential values stay inside the nested driver;
 * the outer suite only sees booleans.
 */
public final class OpenCodeHarnessTest implements Suite {
    private static final String KEY = "WARDEN_HARNESS_KEY";
    private static final String OTHER = "WARDEN_HARNESS_OTHER_KEY";
    private static final String SECRET = "synthetic-harness-secret-one";
    private static final String OTHER_SECRET = "synthetic-harness-secret-two";

    @Override public String name() { return "opencode-harness"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-opencode-");
        try {
            refusesUnsafeProfiles(check);
            locksApiFingerprint(check, scratch.resolve("fingerprint"));
            refusesEligibilityAndStamp(check, scratch.resolve("stamp"));
            refusesBeforeProcess(check, scratch.resolve("closed"));
            runDriver(check);
        } finally {
            deleteTree(scratch);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("mode required");
        switch (args[0]) {
            case "driver" -> driver();
            case "marker" -> Files.writeString(Path.of("started.txt"), "started");
            case "sleep-child" -> Thread.sleep(60_000);
            case "debug" -> debugConfig();
            case "run" -> runMode();
            default -> throw new IllegalArgumentException("unknown mode");
        }
    }

    private static void refusesUnsafeProfiles(Check check) throws Exception {
        check.rejects("a reviewer harness cannot write", "allows writes only for role implementer",
                () -> Profile.parse(profileYaml("reviewer", "false", "max_cost_usd: 1\n"), "reviewer"));
        check.rejects("strict cost is refused", "cannot prove a strict max_cost_usd",
                () -> Profile.parse(profileYaml("implementer", "false", "  max_cost_usd: 1\n"), "cost"));
        check.rejects("the adapter owns the command", "cannot take command",
                () -> Profile.parse(profileYaml("implementer", "false", "") + "command: opencode\n", "command"));
        check.rejects("harness is not runner api", "requires runner: direct",
                () -> Profile.parse(profileYaml("implementer", "false", "").replace("runner: direct", "runner: api"), "api"));
        check.rejects("the router is not the model vendor", "model developer",
                () -> Profile.parse(profileYaml("implementer", "false", "").replace("model_vendor: z-ai", "model_vendor: openrouter"), "vendor"));
        Profile parsed = Profile.parse(Files.readString(Path.of("examples/api/opencode-implementer.yaml")),
                "examples/api/opencode-implementer.yaml");
        check.eq("example harness", "opencode", parsed.harness());
        check.eq("example is not verified", false, parsed.verified());
        check.rejects("Orca cannot host a harness", "cannot be hosted on Orca", parsed::hostedOnOrca);
    }

    private static void locksApiFingerprint(Check check, Path home) throws Exception {
        Connection connection = writeConnection(home, "local-api", "custom", "http://127.0.0.1:9/v1", "env:" + KEY, true);
        String yaml = """
                version: 1
                profile: api-review
                role: reviewer
                runner: api
                connection: local-api
                model_vendor: z-ai
                model: z-ai/glm-5.3-flash
                read_only: true
                prompt_template: prompts/reviewer.md
                json_schema: schemas/reviewer.json
                """;
        Profile profile = Profile.parse(yaml, "api").withConnection(connection, home);
        String contract = String.join("\n", "api-chat-v1", "api-context-v1", connection.fingerprint(),
                "reviewer", "z-ai/glm-5.3-flash", "z-ai", "2048",
                "prompts/reviewer.md", "schemas/reviewer.json", "true", "[]", "unavailable", "unavailable");
        check.eq("API fingerprint stays the text-role contract", sha256(contract), profile.apiFingerprint());
        Profile harness = Profile.parse(profileYaml("reviewer", "true", ""), "harness").withConnection(connection, home);
        Profile writing = Profile.parse(profileYaml("implementer", "false", ""), "writer").withConnection(connection, home);
        check.that("read-only is part of the harness fingerprint",
                harness.apiFingerprint() != null && !harness.apiFingerprint().equals(writing.apiFingerprint()));
        check.that("a harness fingerprint is not an API fingerprint",
                !harness.apiFingerprint().equals(profile.apiFingerprint()));
    }

    private static void refusesEligibilityAndStamp(Check check, Path home) throws Exception {
        Connection connection = writeConnection(home, "local-api", "custom", "http://127.0.0.1:9/v1", "env:" + KEY, true);
        Path file = home.resolve("profiles/opencode-implementer.yaml");
        Files.createDirectories(file.getParent());
        String text = profileYaml("implementer", "false", "") + """
                verification:
                  note: not a coding proof
                """;
        Files.writeString(file, text);
        Profile profile = Profile.parse(text, file.toString()).withConnection(connection, home);
        ProfileVerifier.Stamp stamp = new ProfileVerifier(new ProcessRunner()).stamp(file, profile, LocalDate.of(2026, 10, 26));
        check.eq("probe cannot stamp a harness", "harness_coding_unproven", stamp.code());
        check.eq("stamp does not rewrite the profile", false, stamp.written());
        check.eq("profile text stays unchanged", text, Files.readString(file));
        check.rejects("running the probe is refused", "cannot stamp",
                () -> new ProfileVerifier(new ProcessRunner()).run(profile, home, Duration.ofSeconds(5)));
        Policy policy = Policy.parse("""
                version: 1
                roles:
                  implementer: { profiles: [opencode-implementer], strategy: first, require_independent_vendor: true }
                """, "policy");
        RoleResolver.Candidate candidate = new RoleResolver().candidates("implementer", policy,
                Map.of(profile.name(), profile), RoleResolver.Writers.NONE, ignored -> true, Set.of()).getFirst();
        check.eq("resolver keeps the harness ineligible", "harness_coding_unproven", candidate.rejected());
        Connection disabled = writeConnection(home, "local-api", "custom", "http://127.0.0.1:9/v1", "env:" + KEY, false);
        Profile boundDisabled = Profile.parse(text, file.toString()).withConnection(disabled, home);
        RoleResolver.Candidate closed = new RoleResolver().candidates("implementer", policy,
                Map.of(boundDisabled.name(), boundDisabled), RoleResolver.Writers.NONE, ignored -> true, Set.of()).getFirst();
        check.eq("a disabled harness connection is named before eligibility", "connection_unavailable", closed.rejected());
        check.that("routing selects the harness adapter",
                Executors.forProfile(profile, new ProcessRunner(), new GitRepository(home, new ProcessRunner()))
                        instanceof OpenCodeExecutor);
    }

    private static void refusesBeforeProcess(Check check, Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        List<String> marker = command("marker");
        Connection gonka = writeConnection(home, "gonka", "gonkarouter", "https://api.gonkarouter.io/v1", "env:" + KEY, true);
        Profile gonkaProfile = Profile.parse(profileYaml("implementer", "false", "").replace("connection: local-api", "connection: gonka"),
                "gonka").withConnection(gonka, home);
        RoleExecutor.Result gonkaResult = execute(gonkaProfile, project, marker, name -> SECRET, Duration.ofSeconds(5));
        check.eq("Gonka is refused before a process", "role_harness_provider_unproven", gonkaResult.code());
        check.eq("Gonka starts no vendor turn", false, gonkaResult.evidence().get("vendor_turn_started"));
        check.that("Gonka starts no process", !Files.exists(project.resolve("started.txt")));

        Connection local = writeConnection(home, "local-api", "custom", "http://127.0.0.1:9/v1", "env:" + KEY, true);
        Profile profile = Profile.parse(profileYaml("implementer", "false", ""), "local").withConnection(local, home);
        Files.writeString(home.resolve("connections/local-api.yaml"), local.withEnabled(false).toYaml());
        RoleExecutor.Result disabled = execute(profile, project, marker, name -> SECRET, Duration.ofSeconds(5));
        check.eq("a disabled file is refused from the snapshot", "role_api_connection_disabled", disabled.code());
        check.that("a disabled file starts no process", !Files.exists(project.resolve("started.txt")));

        Files.writeString(home.resolve("connections/local-api.yaml"),
                writeConnection(home, "local-api", "custom", "http://127.0.0.1:10/v1", "env:" + KEY, true).toYaml());
        RoleExecutor.Result changed = execute(profile, project, marker, name -> SECRET, Duration.ofSeconds(5));
        check.eq("a changed file is refused", "role_api_connection_changed", changed.code());
        check.that("a changed file starts no process", !Files.exists(project.resolve("started.txt")));

        writeConnection(home, "local-api", "custom", "http://127.0.0.1:9/v1", "env:" + KEY, true);
        RoleExecutor.Result missing = execute(profile, project, marker, name -> null, Duration.ofSeconds(5));
        check.eq("a missing key is refused", "role_api_key_missing", missing.code());
        check.that("a missing key starts no process", !Files.exists(project.resolve("started.txt")));
        check.that("closed-path evidence has no credential",
                !Json.write(List.of(gonkaResult.evidence(), disabled.evidence(), changed.evidence(), missing.evidence()))
                        .contains(SECRET));
    }

    private static void runDriver(Check check) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command("driver"));
        builder.environment().put(KEY, SECRET);
        builder.environment().put(OTHER, OTHER_SECRET);
        builder.redirectErrorStream(true);
        Process child = builder.start();
        boolean finished = child.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly();
            check.that("offline harness driver finishes", false);
            return;
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean clean = !output.contains(SECRET) && !output.contains(OTHER_SECRET);
        check.that("driver output has no credential", clean);
        if (!clean) return;
        check.eq("driver exits cleanly", 0, child.exitValue());
        if (child.exitValue() != 0 || output.isBlank()) return;
        Map<String, Object> report = Json.parseObject(output.strip());
        for (String assertion : List.of("success", "credential_on_run", "credential_absent_on_preflight",
                "other_absent", "config_has_no_credential", "evidence_has_no_credential",
                "no_artifact", "wrong_model", "missing_model", "read_only_blocks_edit",
                "implementer_edit_ok", "timeout_settled", "descendant_dead", "leak_redacted",
                "cost_not_billed", "coding_not_verified")) {
            check.eq(assertion.replace('_', ' '), true, report.get(assertion));
        }
    }

    private static void driver() throws Exception {
        Path scratch = Files.createTempDirectory("warden-opencode-driver-");
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            Path project = prepareRepo(scratch.resolve("repo"));
            String head = git(project, "rev-parse", "HEAD").strip();
            Connection connection = writeConnection(scratch, "local-api", "custom",
                    "http://127.0.0.1:9/v1", "env:" + KEY, true);
            Profile reader = Profile.parse(profileYaml("reviewer", "true", ""), "reader").withConnection(connection, scratch);
            Profile writer = Profile.parse(profileYaml("implementer", "false", ""), "writer").withConnection(connection, scratch);
            ProcessRunner processes = new ProcessRunner().withoutEnvironment(Set.of(KEY, OTHER));
            OpenCodeExecutor executor = new OpenCodeExecutor(processes, new GitRepository(project, processes),
                    System::getenv, command(), Duration.ofSeconds(20));
            Path run = project.resolve(".warden/runs/case");
            Files.createDirectories(run);
            RoleExecutor.Result success = execute(executor, reader, project, run, head, "success");
            String stored = storedText(run, success);
            report.put("success", "ok".equals(success.code()) && success.artifact() != null);
            report.put("credential_on_run", success.rawOutput().contains("credential_present=true"));
            report.put("credential_absent_on_preflight", preflightText(run, success).contains("credential_present=false"));
            report.put("other_absent", success.rawOutput().contains("other_credential_present=false")
                    && preflightText(run, success).contains("other_credential_present=false"));
            report.put("config_has_no_credential", success.rawOutput().contains("config_contains_credential=false")
                    && success.rawOutput().contains("config_contains_other=false"));
            report.put("evidence_has_no_credential", !stored.contains(SECRET) && !stored.contains(OTHER_SECRET));
            report.put("cost_not_billed", "unknown".equals(success.evidence().get("cost_status"))
                    && !success.evidence().containsKey("cost_usd")
                    && Boolean.FALSE.equals(success.evidence().get("billing_confirmed")));
            report.put("coding_not_verified", Boolean.FALSE.equals(success.evidence().get("coding_tools_verified"))
                    && Boolean.FALSE.equals(success.evidence().get("eligible_implementer"))
                    && Boolean.FALSE.equals(success.evidence().get("live_smoke")));

            RoleExecutor.Result missingArtifact = execute(executor, reader, project, run, head, "no-artifact");
            report.put("no_artifact", "role_harness_artifact_missing".equals(missingArtifact.code())
                    && missingArtifact.artifact() == null);
            RoleExecutor.Result wrong = execute(executor, reader, project, run, head, "wrong-model");
            report.put("wrong_model", "role_harness_identity_unproven".equals(wrong.code()) && wrong.artifact() == null);
            RoleExecutor.Result unnamed = execute(executor, reader, project, run, head, "missing-model");
            report.put("missing_model", "role_harness_identity_unproven".equals(unnamed.code()));
            RoleExecutor.Result blocked = execute(executor, reader, project, run, head, "mutate");
            report.put("read_only_blocks_edit", "role_violated_read_only".equals(blocked.code())
                    && blocked.artifact() == null && Files.exists(project.resolve("violation.txt")));
            RoleExecutor.Result edited = execute(executor, writer, project, run, head, "edit");
            report.put("implementer_edit_ok", "ok".equals(edited.code()) && Files.exists(project.resolve("touched.txt")));

            OpenCodeExecutor shortRun = new OpenCodeExecutor(processes, new GitRepository(project, processes),
                    System::getenv, command(), Duration.ofSeconds(15));
            RoleExecutor.Result timeout = execute(shortRun, reader, project, run, head, "sleep");
            boolean descendantDead = true;
            Path pidFile = run.resolve("child.pid");
            if (Files.isRegularFile(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile).strip());
                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                descendantDead = handle == null || !handle.isAlive();
                if (handle != null && handle.isAlive()) handle.destroyForcibly();
            } else descendantDead = false;
            report.put("timeout_settled", "role_timeout".equals(timeout.code())
                    && Boolean.TRUE.equals(timeout.evidence().get("process_tree_settled"))
                    && timeout.artifact() == null);
            report.put("descendant_dead", descendantDead);

            RoleExecutor.Result leak = execute(executor, reader, project, run, head, "leak");
            String leakStored = storedText(run, leak);
            report.put("leak_redacted", "ok".equals(leak.code())
                    && !leakStored.contains(SECRET) && !leak.rawOutput().contains(SECRET)
                    && !Json.write(leak.evidence()).contains(SECRET));
        } catch (RuntimeException | java.io.IOException | InterruptedException failure) {
            report.put("driver_error", failure.getClass().getSimpleName());
        } finally {
            deleteTree(scratch);
            System.out.print(Json.write(report));
        }
    }

    private static RoleExecutor.Result execute(OpenCodeExecutor executor, Profile profile, Path project, Path run,
                                               String head, String mode) throws Exception {
        Files.writeString(run.resolve("mode.txt"), mode);
        Path prompt = run.resolve("prompt.md");
        Files.writeString(prompt, "synthetic offline harness prompt");
        TaskSpec.ResolvedTask task = new TaskSpec.ResolvedTask("harness", "offline probe", List.of(), "low",
                "HEAD", List.of("."), List.of(), List.of("unused"),
                new TaskSpec.Authority(false, false, false), new TaskSpec.VisualQa(false, List.of(), null, null),
                new TaskSpec.Budget(1, 1), 1, 1);
        return executor.execute(new RoleExecutor.Request("harness", "harness", profile.role(), profile, task, head,
                project, run, prompt, null, "", "harness", List.of()));
    }

    private static RoleExecutor.Result execute(Profile profile, Path project, List<String> prefix,
                                               java.util.function.Function<String, String> environment,
                                               Duration timeout) throws Exception {
        Path run = Files.createDirectories(project.resolve(".warden/runs/case"));
        Path prompt = run.resolve("prompt.md");
        Files.writeString(prompt, "synthetic offline harness prompt");
        OpenCodeExecutor executor = new OpenCodeExecutor(new ProcessRunner(), new GitRepository(project, new ProcessRunner()),
                environment, prefix, timeout);
        TaskSpec.ResolvedTask task = new TaskSpec.ResolvedTask("harness", "offline probe", List.of(), "low",
                "HEAD", List.of("."), List.of(), List.of("unused"),
                new TaskSpec.Authority(false, false, false), new TaskSpec.VisualQa(false, List.of(), null, null),
                new TaskSpec.Budget(1, 1), 1, 1);
        return executor.execute(new RoleExecutor.Request("harness", "harness", profile.role(), profile, task,
                "0".repeat(40), project, run, prompt, null, "", "harness", List.of()));
    }

    private static void debugConfig() throws Exception {
        Map<String, Object> root = Json.parseObject(System.getenv("OPENCODE_CONFIG_CONTENT"));
        Map<String, Object> provider = nested(nested(root, "provider"), "warden");
        Map<String, Object> options = nested(provider, "options");
        System.out.println("baseURL=" + options.get("baseURL"));
        System.out.println("model=" + root.get("model"));
        reportCredential(System.getenv("OPENCODE_CONFIG_CONTENT"));
    }

    private static void runMode() throws Exception {
        String mode = Files.isRegularFile(Path.of(".warden/runs/case/mode.txt"))
                ? Files.readString(Path.of(".warden/runs/case/mode.txt")).strip() : "success";
        String config = System.getenv("OPENCODE_CONFIG_CONTENT");
        reportCredential(config);
        Map<String, Object> provider = nested(nested(Json.parseObject(config), "provider"), "warden");
        String requested = nested(provider, "models").keySet().iterator().next();
        if ("wrong-model".equals(mode)) requested = "other-vendor/other-model";
        if (!"missing-model".equals(mode)) {
            System.out.println("{\"type\":\"step_finish\",\"model\":\"" + requested + "\",\"cost\":null}");
        } else {
            System.out.println("{\"type\":\"step_finish\"}");
        }
        if ("leak".equals(mode)) System.out.println(System.getenv(KEY));
        if ("mutate".equals(mode)) Files.writeString(Path.of("violation.txt"), "changed\n");
        if ("edit".equals(mode)) Files.writeString(Path.of("touched.txt"), "changed\n");
        if ("sleep".equals(mode)) {
            Process child = new ProcessBuilder(command("sleep-child")).start();
            Files.createDirectories(Path.of(".warden/runs/case"));
            Files.writeString(Path.of(".warden/runs/case/child.pid"), Long.toString(child.pid()));
        }
        if (!"no-artifact".equals(mode)) {
            Files.writeString(Path.of(System.getenv("WARDEN_HARNESS_ARTIFACT")),
                    "{\"status\":\"completed\",\"summary\":\"offline\",\"verdict\":\"pass\",\"findings\":[]}");
        }
        if ("sleep".equals(mode)) Thread.sleep(30_000);
    }

    private static void reportCredential(String config) {
        String key = System.getenv(KEY);
        String other = System.getenv(OTHER);
        System.out.println("credential_present=" + (key != null && !key.isBlank()));
        System.out.println("other_credential_present=" + (other != null));
        System.out.println("config_contains_credential=" + (key != null && config != null && config.contains(key)));
        System.out.println("config_contains_other=" + (other != null && config != null && config.contains(other)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    private static String preflightText(Path run, RoleExecutor.Result result) throws Exception {
        return Files.readString(run.resolve(String.valueOf(result.evidence().get("raw_preflight_stdout"))));
    }

    private static String storedText(Path run, RoleExecutor.Result result) throws Exception {
        return Files.readString(run.resolve(String.valueOf(result.evidence().get("raw_stdout"))))
                + Files.readString(run.resolve(String.valueOf(result.evidence().get("raw_stderr"))))
                + preflightText(run, result)
                + Json.write(result.evidence())
                + String.valueOf(result.rawOutput());
    }

    private static Path prepareRepo(Path project) throws Exception {
        Files.createDirectories(project);
        git(project, "init", "-b", "main");
        Files.writeString(project.resolve("README"), "base\n");
        git(project, "add", "README");
        git(project, "-c", "user.email=warden-test@example.com", "-c", "user.name=warden", "commit", "-m", "base");
        return project;
    }

    private static String git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.Result result = new ProcessRunner().run(command, directory, Duration.ofSeconds(20));
        if (!result.ok()) throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        return result.stdout();
    }

    private static Connection writeConnection(Path home, String id, String provider, String baseUrl,
                                              String credential, boolean enabled) throws Exception {
        Connection connection = new Connection(id, provider, "openai_chat", baseUrl, credential, enabled);
        Path file = home.resolve("connections").resolve(id + ".yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, connection.toYaml());
        return connection;
    }

    private static String profileYaml(String role, String readOnly, String extraLimits) {
        return """
                version: 1
                profile: opencode-implementer
                role: %s
                runner: direct
                harness: opencode
                connection: local-api
                model_vendor: z-ai
                model: z-ai/glm-5.3-flash
                read_only: %s
                prompt_template: prompts/implementer.md
                json_schema: schemas/implementer.json
                limits:
                  wall_clock_minutes: 5
                %s""".formatted(role, readOnly, extraLimits);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> command(String... extra) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(executable, "-cp", classpath, OpenCodeHarnessTest.class.getName()));
        command.addAll(List.of(extra));
        return command;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }
}
