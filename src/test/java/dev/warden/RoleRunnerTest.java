package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.execution.DirectCliExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.PromptRenderer;
import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The role layer end to end, driven by stand-in vendors.
 *
 * Stand-ins rather than real CLIs on purpose: none of the properties under test are about
 * model quality, and a suite that spends money and needs a network is a suite that stops
 * being run. Every case here is a promise the tool makes to an operator.
 */
public final class RoleRunnerTest implements Suite {

    @Override public String name() { return "role runner"; }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * The stand-in runs with the project directory as its working directory, so a relative
     * classpath entry — which is what a `-cp out/classes` build produces — would not resolve.
     */
    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        String[] entries = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator));
        StringBuilder builder = new StringBuilder();
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    /** The JVM already running this suite is the most portable stand-in vendor available. */
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-role-test-");
        try {
            Path home = sandbox.resolve("home");
            Path project = sandbox.resolve("project");
            homeResolutionChecks(check);
            jsonlRecoveryChecks(check);
            setupChecks(check, home);
            scaffoldProject(project);

            // --- dry run: resolves and renders, spends nothing -------------------------
            writePolicy(home, "stub-review", "stub-impl");
            writeProfile(home, "stub-review", "reviewer", "othervendor",
                    "review", true, "reviewer",
                    "role, task_id, status, verdict, summary, findings");
            writeProfile(home, "stub-impl", "implementer", "stubvendor",
                    "impl", false, "implementer",
                    "role, task_id, status, summary, files_changed");

            RoleRunner.Outcome dry = runRole(project, home, "reviewer", "dry", null, null, true);
            check.that("dry run resolves a profile", dry.ok());
            check.eq("dry run picks the configured reviewer", "stub-review", dry.profile());
            check.that("dry run writes the exact prompt that would be sent",
                    Files.isRegularFile(project.resolve(".warden/runs/dry/prompts/reviewer.md")));
            check.that("dry run invokes no vendor",
                    !Files.exists(project.resolve(".warden/runs/dry/raw")));
            String prompt = Files.readString(project.resolve(".warden/runs/dry/prompts/reviewer.md"));
            check.contains("prompt carries the task goal", prompt, "Create src/result.txt");
            check.contains("prompt carries the declared scope", prompt, "src");
            check.that("prompt has no unresolved placeholders", !prompt.contains("{{"));

            // The reviewer template used to name `git diff` and `git ls-files` as the way to
            // see what changed. A profile whose tool grant is Read/Grep/Glob cannot run
            // either, and one measured Opus review spent 16 of its 74 tool calls being
            // refused for doing exactly as it was told, then died at its turn ceiling. The
            // paths are resolved by Warden, which can always run git, and rendered into the
            // prompt where any profile can use them.
            check.that("the reviewer is handed the changed paths rather than a command to find them",
                    prompt.contains("These are the paths, already resolved"));
            check.contains("and the git commands are offered only to a profile that has a shell",
                    prompt, "If — and only if — you are also");
            check.contains("with the read-only rule saying that no shell at all is normal here",
                    prompt, "no way to run commands at all");

            // --- implementer writes, reviewer stays read-only --------------------------
            RoleRunner.Outcome implemented = runRole(project, home, "implementer", "live", null, null, false);
            check.that("implementer run succeeds", implemented.ok());
            check.that("implementer actually wrote the file",
                    Files.isRegularFile(project.resolve("src/result.txt")));
            Map<String, Object> implReport = readJson(project.resolve(".warden/runs/live/role-implementer.json"));
            check.eq("cost is captured without an experiment", 0.012, implReport.get("cost_usd"));
            check.eq("turns are captured", 3L, implReport.get("num_turns"));
            check.eq("the vendor's own model name is recorded", "stub-impl-model", implReport.get("model_reported"));
            List<Map<String, Object>> implAttempts = objects(implReport.get("vendor_attempts"));
            check.eq("attempt evidence keeps the vendor-reported model", "stub-impl-model",
                    implAttempts.get(0).get("model"));
            check.eq("attempt evidence keeps the runner", "direct",
                    implAttempts.get(0).get("runner"));
            check.eq("attempt evidence keeps reported tokens", 12L,
                    ((Map<?, ?>) implAttempts.get(0).get("tokens")).get("total"));
            check.that("the prompt is hashed for the ledger", implReport.get("prompt_sha256") instanceof String);
            check.that("the artifact is hashed for the ledger", implReport.get("artifact_sha256") instanceof String);
            check.that("raw vendor output is kept beside the run, not under prompts/",
                    Files.isRegularFile(project.resolve(".warden/runs/live/raw/implementer.stdout.txt")));

            RoleRunner.Outcome reviewed = runRole(project, home, "reviewer", "live", null, null, false);
            check.that("reviewer run succeeds", reviewed.ok());
            Map<String, Object> reviewReport = readJson(project.resolve(".warden/runs/live/role-reviewer.json"));
            check.eq("the verdict reaches the ledger", "pass", reviewReport.get("verdict"));
            check.that("an answer buried in a text envelope is still recovered",
                    Files.isRegularFile(project.resolve(".warden/runs/live/artifacts/reviewer.json")));

            // --- independence: a reviewer may not share the implementer's vendor -------
            writePolicy(home, "stub-sneaky, stub-review", "stub-impl");
            writeProfile(home, "stub-sneaky", "reviewer", "stubvendor",
                    "sneaky", true, "reviewer",
                    "role, task_id, status, verdict, summary, findings");
            RoleRunner.Outcome independent =
                    runRole(project, home, "reviewer", "independent", "stubvendor", null, false);
            check.eq("a model never reviews its own vendor's output", "othervendor", independent.vendor());
            check.eq("and the reason is recorded", "same_vendor_as_implementer",
                    independent.rejected().get("stub-sneaky"));

            // --- read-only is verified, not trusted ------------------------------------
            writePolicy(home, "stub-sneaky", "stub-impl");
            RoleRunner.Outcome trapped = runRole(project, home, "reviewer", "trap", null, null, false);
            check.that("a reviewer that edited the tree fails", !trapped.ok());
            check.eq("and says exactly why", "role_violated_read_only", trapped.code());
            check.that("its artifact is discarded, whatever it claimed",
                    !Files.exists(project.resolve(".warden/runs/trap/artifacts/reviewer.json")));
            Map<String, Object> trapReport = readJson(project.resolve(".warden/runs/trap/role-reviewer.json"));
            Object readOnlyCheck = trapReport.get("read_only_check");
            check.that("the uncovered case is disclosed in the report, not buried in docs",
                    String.valueOf(readOnlyCheck).contains("gitignore"));
            restore(project);

            // --- an unverified profile is refused before it can be attempted -----------
            writeUnverifiedProfile(home, "unproven", "review");
            writePolicy(home, "unproven", "stub-impl");
            RoleRunner.Outcome unverified = runRole(project, home, "reviewer", "unverified", null, null, true);
            check.that("an unverified profile cannot run", !unverified.ok());
            check.contains("and the reason names verification",
                    String.valueOf(unverified.details().get("message")), "profile_unverified");

            // --- write authority must come from the task, not only the profile ---------
            writePolicy(home, "stub-review", "stub-impl");
            Files.writeString(project.resolve(".warden/tasks/locked.yaml"), """
                    version: 1
                    id: locked
                    goal: A task that forbids workspace writes
                    risk: medium
                    scope: app
                    authority:
                      workspace_write: false
                    """);
            RoleRunner.Outcome denied = runRole(project, home, "implementer", "denied", null, null, true, "locked");
            check.that("a writing role is refused when the task forbids writes", !denied.ok());
            check.eq("and the refusal is named", "authority_denied", denied.code());

            // --- a mistyped placeholder is an error, never sent to a model -------------
            check.rejects("unknown placeholder refuses to render", "unknown placeholder",
                    () -> PromptRenderer.render("hello {{tsak_id}}", Map.of("task_id", "x"), "t.md"));
            check.eq("known placeholders render", "hello x",
                    PromptRenderer.render("hello {{task_id}}", Map.of("task_id", "x"), "t.md"));

            // --- an incomplete artifact is refused --------------------------------------
            writeProfile(home, "stub-thin", "reviewer", "thinvendor",
                    "thin", true, "reviewer",
                    "role, task_id, status, verdict, summary, findings");
            writePolicy(home, "stub-thin", "stub-impl");
            RoleRunner.Outcome thin = runRole(project, home, "reviewer", "thin", null, null, false);
            check.that("an artifact missing required fields fails", !thin.ok());
            check.eq("and says which contract it broke", "role_artifact_incomplete", thin.code());
            check.that("nothing is written for a rejected artifact",
                    !Files.exists(project.resolve(".warden/runs/thin/artifacts/reviewer.json")));

            writeProfile(home, "stub-schema", "reviewer", "schemavendor",
                    "bad-schema", true, "reviewer",
                    "role, task_id, status, verdict, summary, findings");
            writePolicy(home, "stub-schema", "stub-impl");
            RoleRunner.Outcome schema = runRole(project, home, "reviewer", "schema", null, null, false);
            check.that("an artifact that fails the schema is refused", !schema.ok());
            check.eq("and names the schema, not a missing field", "role_artifact_schema_violation", schema.code());
            check.that("a schema-invalid artifact is not written",
                    !Files.exists(project.resolve(".warden/runs/schema/artifacts/reviewer.json")));

            modelPlaceholderChecks(check);
            quotaFailoverChecks(check, project, home);
            turnCeilingChecks(check, project, home);
            promptDeliveryChecks(check, project, home);
        } finally {
            deleteTree(sandbox);
        }
    }

    /**
     * `model:` used to be recorded in the ledger and never sent anywhere, so a run could
     * report a model it had not used. The value is now available to the profile's args, and a
     * vendor that reports a different one is written down rather than quietly overriding the
     * record.
     */
    private void modelPlaceholderChecks(Check check) {
        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("model_reported", "some-other-model");
        DirectCliExecutor.noteModelMismatch(profileNaming("declared-model"), evidence);
        check.that("a vendor reporting a different model is recorded",
                evidence.get("model_mismatch") != null);
        check.contains("with both names in the report",
                String.valueOf(evidence.get("model_mismatch")), "declared-model");

        java.util.Map<String, Object> agreeing = new java.util.LinkedHashMap<>();
        agreeing.put("model_reported", "declared-model");
        DirectCliExecutor.noteModelMismatch(profileNaming("declared-model"), agreeing);
        check.that("agreement is not noise in the ledger", agreeing.get("model_mismatch") == null);

        java.util.Map<String, Object> silent = new java.util.LinkedHashMap<>();
        DirectCliExecutor.noteModelMismatch(profileNaming("declared-model"), silent);
        check.that("a vendor that reports nothing is not accused of anything",
                silent.get("model_mismatch") == null);
    }

    private static dev.warden.config.Profile profileNaming(String model) {
        return dev.warden.config.Profile.parse("""
                version: 1
                profile: p
                role: reviewer
                vendor: v
                model: %s
                command: c
                verification:
                  verified_on: "2026-08-27"
                """.formatted(model), "p.yaml");
    }

    /**
     * How the prompt reaches the vendor, and what happens when it cannot.
     *
     * The refusal case is not hypothetical: it is what a real npm-installed vendor did on this
     * host. cmd.exe cut the prompt at its first newline and dropped the flags that followed,
     * and the run looked ordinary from the outside — a paid answer to a question nobody asked.
     */
    private void promptDeliveryChecks(Check check, Path project, Path home) throws Exception {
        List<String> multiLinePrompt = List.of("exec", "line one\nline two", "--json");
        Map<String, Object> deliverable = DirectCliExecutor.deliverabilityCheck(
                prefixed("C:\\tools\\vendor.exe", multiLinePrompt));
        check.eq("a real executable takes a multi-line argument intact",
                Boolean.TRUE, deliverable.get("deliverable"));

        Map<String, Object> refused = DirectCliExecutor.deliverabilityCheck(
                prefixed("C:\\tools\\vendor.cmd", multiLinePrompt));
        check.eq("the same argument through a batch shim is refused before dispatch",
                Boolean.FALSE, refused.get("deliverable"));
        check.contains("and the operator is told what to do instead",
                String.valueOf(refused.get("resolution")), "prompt_delivery: stdin");
        check.contains("while the gap the check does not close is disclosed too",
                String.valueOf(refused.get("does_not_cover")), "%NAME%");
        check.eq("a shim with only single-line arguments is fine", Boolean.TRUE,
                DirectCliExecutor.deliverabilityCheck(prefixed("vendor.cmd",
                        List.of("exec", "--prompt-file", "C:\\x\\p.md"))).get("deliverable"));

        // Not a shim problem, and no shim check would ever have seen it. On Windows the JVM
        // quotes an argument containing a space but does not escape a quote already inside
        // it, so the receiving process re-splits from there. Measured on a real run: a
        // visual-QA prompt with the harness report inlined reached claude.exe as
        // `error: unknown option '->'`.
        Map<String, Object> quoted = DirectCliExecutor.deliverabilityCheck(
                prefixed("C:\\tools\\vendor.exe",
                        List.of("-p", "report: {\"ok\": true, \"code\": \"passed\"}")));
        check.eq("a prompt carrying JSON is refused before dispatch on Windows",
                WINDOWS ? Boolean.FALSE : Boolean.TRUE, quoted.get("deliverable"));
        if (WINDOWS) {
            check.contains("and the offending argument is named",
                    String.valueOf(quoted.get("undeliverable_arguments")), "double quote");
            check.contains("with the same way out",
                    String.valueOf(quoted.get("resolution")), "prompt_delivery: stdin");
        }
        check.eq("a quote-free argument still goes through", Boolean.TRUE,
                DirectCliExecutor.deliverabilityCheck(prefixed("C:\\tools\\vendor.exe",
                        List.of("-p", "no quotes here at all"))).get("deliverable"));
        // Both halves of the rule are needed, and the second half is why. The JVM wraps an
        // argument only when it holds whitespace, so a quote in a value it never wrapped
        // survives — and this exact flag has been reaching Codex intact for months. A check
        // that refuses it is a false alarm that stops a working profile, which a first
        // version of this rule did on a live run.
        check.eq("a quoted config flag with no whitespace is left alone", Boolean.TRUE,
                DirectCliExecutor.deliverabilityCheck(prefixed("C:\\tools\\vendor.exe",
                        List.of("-c", "model_reasoning_effort=\"high\""))).get("deliverable"));

        // And the channel that survives a shim actually carries the prompt.
        Files.deleteIfExists(project.resolve("src/result.txt"));
        writeStdinProfile(home);
        writePolicy(home, "stub-review", "stub-stdin");
        RoleRunner.Outcome viaStdin = runRole(project, home, "implementer", "stdin", null, null, false);
        check.that("a profile that takes its prompt on stdin runs", viaStdin.ok());
        check.that("and the vendor really received it, not an empty stream",
                Files.isRegularFile(project.resolve("src/result.txt")));
        Map<String, Object> stdinReport =
                readJson(project.resolve(".warden/runs/stdin/role-implementer.json"));
        check.eq("the delivery channel is recorded with the run", "stdin",
                stdinReport.get("prompt_delivery"));
        restore(project);
    }

    private static List<String> prefixed(String executable, List<String> arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        return command;
    }

    /** A vendor that reads its prompt from standard input and nowhere else. */
    private void writeStdinProfile(Path home) throws IOException {
        Files.writeString(home.resolve("profiles/stub-stdin.yaml"), """
                version: 1
                profile: stub-stdin
                role: implementer
                vendor: stdinvendor
                command: %s
                read_only: false
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "stdin"]
                prompt_delivery: stdin
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/implementer.md
                json_schema: schemas/implementer.json
                artifact:
                  required_fields: [role, task_id, status, summary, files_changed]
                verification:
                  verified_on: "2026-08-26"
                """.formatted(yaml(javaExecutable()), yaml(absoluteClassPath())));
    }

    /**
     * A spent subscription is the one failure worth handing to a different vendor. These pin
     * what that costs an operator in transparency: which vendor ran out, what it said, what
     * the successor was told, and what happens when there is no successor left.
     */
    private void quotaFailoverChecks(Check check, Path project, Path home) throws Exception {
        writeProfile(home, "stub-spent", "reviewer", "spentvendor",
                "quota", true, "reviewer",
                "role, task_id, status, verdict, summary, findings");
        writePolicy(home, "stub-spent, stub-review", "stub-impl");

        RoleRunner.Outcome failedOver = runRole(project, home, "reviewer", "failover", null, null, false);
        check.that("a role whose first vendor ran out still completes", failedOver.ok());
        check.eq("because it was re-dispatched to another vendor", "stub-review", failedOver.profile());

        Map<String, Object> report = readJson(project.resolve(".warden/runs/failover/role-reviewer.json"));
        check.eq("the run says which profile it fell back from",
                List.of("stub-spent"), report.get("failed_over_from"));
        check.that("both transcripts survive; the retry does not overwrite the refusal",
                Files.isRegularFile(project.resolve(".warden/runs/failover/raw/reviewer.stdout.txt"))
                        && Files.isRegularFile(project.resolve(
                                ".warden/runs/failover/raw/reviewer.attempt-2.stdout.txt")));
        String refusal = Files.readString(
                project.resolve(".warden/runs/failover/raw/reviewer.stdout.txt"));
        check.contains("and the refusal is kept verbatim, retry window included",
                refusal, "try again at 9:21 PM");
        String evidence = Files.readString(project.resolve(".warden/runs/failover/evidence.jsonl"));
        check.contains("the exhaustion is its own ledger event", evidence, "role_quota_exhausted");
        check.contains("naming the vendor that ran out", evidence, "spentvendor");

        // Nothing left to fall back to: the run stops, and says which of the two situations
        // it is in — "nothing is configured" needs an operator, "everything is spent" needs time.
        writePolicy(home, "stub-spent", "stub-impl");
        RoleRunner.Outcome nowhere = runRole(project, home, "reviewer", "spent", null, null, false);
        check.that("a role with no vendor left fails", !nowhere.ok());
        check.eq("as an exhausted subscription, not as a broken configuration",
                "role_quota_exhausted", nowhere.code());
        check.contains("and tells the operator what would unblock it",
                String.valueOf(nowhere.details().get("resolution")), "another vendor");

        // A writing role can be cut off after it has already edited the tree. The vendor that
        // inherits the work is told so, rather than being handed a clean-looking task.
        Files.deleteIfExists(project.resolve("src/result.txt"));
        writeProfile(home, "stub-spent-impl", "implementer", "spentvendor",
                "quota-after-edit", false, "implementer",
                "role, task_id, status, summary, files_changed");
        writePolicy(home, "stub-review", "stub-spent-impl, stub-impl");
        RoleRunner.Outcome handover = runRole(project, home, "implementer", "handover", null, null, false);
        check.that("the successor finishes the work", handover.ok());
        check.eq("and it is a different profile", "stub-impl", handover.profile());
        String secondPrompt = Files.readString(
                project.resolve(".warden/runs/handover/prompts/implementer.attempt-2.md"));
        check.contains("the successor is told it is continuing an interrupted run",
                secondPrompt, "continuing an interrupted run");
        check.contains("and that the tree already holds unreviewed edits",
                secondPrompt, "unreviewed");
        Map<String, Object> handoverReport =
                readJson(project.resolve(".warden/runs/handover/role-implementer.json"));
        check.eq("the inheritance is recorded, not smoothed over",
                Boolean.TRUE, handoverReport.get("inherited_unfinished_work"));
        restore(project);
    }

    // ------------------------------------------------------------------ helpers

    private void jsonlRecoveryChecks(Check check) {
        String jsonl = """
                {"type":"thread.started","thread_id":"t1"}
                {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"{\\"role\\":\\"reviewer\\",\\"verdict\\":\\"fail\\"}"}}
                {"type":"turn.completed","usage":{"input_tokens":3,"output_tokens":2}}
                """;
        Map<String, Object> recovered = DirectCliExecutor.extractJsonlAgentMessage(jsonl);
        check.eq("Codex JSONL agent_message JSON is recovered, not the trailing turn.completed",
                "fail", recovered == null ? null : recovered.get("verdict"));
        check.eq("plain-text agent_message is not mistaken for an artifact",
                null, DirectCliExecutor.extractJsonlAgentMessage("""
                        {"type":"item.completed","item":{"type":"agent_message","text":"ok"}}
                        {"type":"turn.completed","usage":{}}
                        """));
    }

    private void homeResolutionChecks(Check check) {
        // The launcher exports WARDEN_HOME for the install directory. Reading the same name
        // for the config directory once put a user's starter configuration inside the
        // repository being gated.
        check.eq("the config home variable is distinct from the launcher's",
                "WARDEN_CONFIG_HOME", UserConfig.HOME_ENVIRONMENT_VARIABLE);
        check.that("an unset override falls back to the user's home, never the working directory",
                System.getenv(UserConfig.HOME_ENVIRONMENT_VARIABLE) != null
                        || UserConfig.defaultHome().startsWith(Path.of(System.getProperty("user.home"))));
    }

    private void setupChecks(Check check, Path home) throws IOException {
        UserSetup.Result first = new UserSetup().run(home);
        check.that("setup creates a policy", first.created().contains("policy.yaml"));
        check.that("setup ships a verified Grok profile",
                first.created().contains("profiles/grok-review.yaml"));
        UserConfig loaded = UserConfig.load(home);
        check.that("the shipped Grok profile is marked verified",
                loaded.profiles().get("grok-review").verified());
        check.that("the shipped Claude profile is not, because its flags were never run",
                !loaded.profiles().get("claude-review").verified());
        check.that("the shipped Codex profile writes and is therefore left unverified",
                !loaded.profiles().get("codex-implement").verified()
                        && !loaded.profiles().get("codex-implement").readOnly());
        check.that("policy references resolve", loaded.danglingProfileReferences().isEmpty());

        UserSetup.Result second = new UserSetup().run(home);
        check.that("a second setup overwrites nothing", second.created().isEmpty());
        check.that("and reports what it left alone", second.skipped().contains("policy.yaml"));
    }

    private void scaffoldProject(Path project) throws Exception {
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        // Acceptance commands go through a shell, and the two platforms do not share one.
        String existsCheck = WINDOWS
                ? "if exist src\\\\result.txt (exit /b 0) else (exit /b 1)"
                : "test -f src/result.txt";
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: demo
                base_ref: HEAD
                checks:
                  fast: ["%s"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """.formatted(existsCheck));
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt containing the word ok
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                """);
        Files.writeString(project.resolve("README.md"), "seed\n");
        ProcessRunner runner = new ProcessRunner();
        runner.run(List.of("git", "init", "-q", "-b", "main", "."), project, Duration.ofSeconds(30));
        runner.run(List.of("git", "config", "user.email", "test@example.invalid"), project, Duration.ofSeconds(30));
        runner.run(List.of("git", "config", "user.name", "test"), project, Duration.ofSeconds(30));
        runner.run(List.of("git", "add", "-A"), project, Duration.ofSeconds(30));
        runner.run(List.of("git", "commit", "-qm", "base"), project, Duration.ofSeconds(30));
    }

    private void restore(Path project) throws Exception {
        Files.writeString(project.resolve("src/result.txt"), "ok");
    }

    /**
     * A profile whose command is this JVM and whose first argument selects a stand-in mode.
     * The role executor passes arguments as an argv array, so no shell quoting is involved
     * and the same profile text works on both platforms.
     */
    /**
     * A vendor stopped by its own turn ceiling, which is neither a verdict on the work nor a
     * spent subscription — and which cost money on the way to saying nothing.
     *
     * Both halves were measured on live runs and both were wrong. The failure arrived as a
     * bare `role_command_failed`, the same code as a broken flag, so the one knob worth
     * turning was named nowhere; the classifier that exists for exactly this was only
     * reachable on the exit-0 path, and both vendors that hit a ceiling exit 1. And the
     * envelope's `total_cost_usd` was read only after a valid artifact, so $1.33 of grok and
     * $4.88 of Opus were recorded as costing nothing at all — a ceiling that measures only
     * the calls that worked is not a ceiling.
     */
    private void turnCeilingChecks(Check check, Path project, Path home) throws Exception {
        writeProfile(home, "stub-ceiling", "reviewer", "ceilingvendor",
                "turn-ceiling", true, "reviewer",
                "role, task_id, status, verdict, summary, findings");
        writePolicy(home, "stub-ceiling", "stub-impl");

        RoleRunner.Outcome stopped = runRole(project, home, "reviewer", "ceiling", null, null, false);
        check.that("a role stopped at its turn ceiling fails", !stopped.ok());
        check.eq("named as a ceiling, not as an ordinary command failure",
                "role_turns_exhausted", stopped.code());
        check.contains("and the report names the knob that would move it",
                String.valueOf(stopped.details().get("resolution")), "--max-turns");
        check.contains("saying which profile carries it",
                String.valueOf(stopped.details().get("resolution")), "stub-ceiling");
        check.eq("the cost the vendor reported is recorded even though the call failed",
                1.33483082, stopped.details().get("cost_usd"));

        Map<String, Object> report = readJson(project.resolve(".warden/runs/ceiling/role-reviewer.json"));
        check.eq("and it reaches the run's own evidence file", 1.33483082, report.get("cost_usd"));
        check.eq("with the turn count the vendor volunteered", 40L, report.get("num_turns"));

        // Not quota: a different vendor would meet the same ceiling on the same diff, so
        // failing over would buy an identical failure at twice the price.
        check.that("no failover is attempted for a ceiling",
                report.get("failed_over_from") == null);
    }

    private void writeProfile(Path home, String name, String role, String vendor, String stubMode,
                              boolean readOnly, String promptAndSchema, String requiredFields)
            throws IOException {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: %s
                read_only: %s
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "%s", "--prompt-file", "{{prompt_file}}"]
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/%s.md
                json_schema: schemas/%s.json
                artifact:
                  required_fields: [%s]
                verification:
                  verified_on: "2026-08-26"
                """.formatted(name, role, vendor, yaml(javaExecutable()), readOnly,
                yaml(absoluteClassPath()), stubMode,
                promptAndSchema, promptAndSchema, requiredFields));
    }

    private void writeUnverifiedProfile(Path home, String name, String stubMode) throws IOException {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: reviewer
                vendor: unprovenvendor
                command: %s
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "%s"]
                prompt_template: prompts/reviewer.md
                json_schema: schemas/reviewer.json
                artifact:
                  required_fields: [role, task_id, status, verdict, summary, findings]
                verification:
                  probe: 'run it once before trusting it'
                """.formatted(name, yaml(javaExecutable()), yaml(absoluteClassPath()),
                stubMode));
    }

    /** Windows paths contain backslashes; a double-quoted YAML scalar keeps them intact. */
    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void writePolicy(Path home, String reviewers, String implementers) throws IOException {
        policy(home, reviewers, implementers, "auto");
    }

    /**
     * @param failover the policy's answer to a spent subscription. These scenarios were
     *                 written when routing around one was the only behaviour, so they say
     *                 `auto` outright; the shipped default is `confirm`, and has its own
     *                 checks below.
     */
    private void policy(Path home, String reviewers, String implementers, String failover)
            throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  reviewer: { profiles: [%s], strategy: first, require_independent_vendor: true }
                  implementer: { profiles: [%s], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                failover: { on_quota_exhausted: %s }
                """.formatted(reviewers, implementers, failover));
    }

    private RoleRunner.Outcome runRole(Path project, Path home, String role, String runId,
                                       String implementerVendor, Path context, boolean dryRun)
            throws Exception {
        return runRole(project, home, role, runId, implementerVendor, context, dryRun, "hello");
    }

    private RoleRunner.Outcome runRole(Path project, Path home, String role, String runId,
                                       String implementerVendor, Path context, boolean dryRun,
                                       String task) throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, task);
        UserConfig user = UserConfig.load(home);
        return new RoleRunner(new ProcessRunner())
                .run(loaded, user, role, runId, implementerVendor, context, dryRun);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path file) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objects(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
