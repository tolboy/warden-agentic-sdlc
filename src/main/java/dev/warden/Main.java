package dev.warden;

import dev.warden.approval.ApprovalException;
import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.execution.orca.OrcaClient;
import dev.warden.json.Json;
import dev.warden.ledger.LedgerReader;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.DoCommand;
import dev.warden.run.TaskLoop;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point. Command dispatch only — every command lives in its own class so that adding
 * one cannot change the behaviour of another.
 */
public final class Main {

    public static final String VERSION = "0.1.0-dev";
    public static void main(String[] args) {
        useUtf8ForOutput();
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            System.exit(args.length == 0 ? 2 : 0);
        }
        if (args[0].equals("--version")) {
            System.out.println("warden " + VERSION);
            return;
        }
        try {
            int exit = switch (args[0]) {
                case "validate" -> validate(args);
                case "init" -> init(args);
                case "gates" -> gates(args);
                case "visual-qa" -> visualQa(args);
                case "setup" -> setup();
                case "profiles" -> profiles(args);
                case "role" -> role(args);
                case "run" -> runLoop(args);
                case "do" -> doIntent(args);
                case "doctor" -> doctor();
                case "ledger" -> ledger();
                case "status" -> status(args);
                case "approve" -> approve(args);
                default -> {
                    System.err.println("warden: unknown command '" + args[0] + "'");
                    usage();
                    yield 2;
                }
            };
            if (exit != 0) System.exit(exit);
        } catch (Exception failure) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("code", "warden_error");
            error.put("message", String.valueOf(failure.getMessage()));
            System.err.println(Json.write(error));
            System.exit(1);
        }
    }

    /**
     * Every report Warden writes to disk is UTF-8; its console streams follow the Windows
     * ANSI codepage unless told otherwise, so a Russian goal read correctly from a file was
     * still printed as question marks. The file was right and the screen was wrong, which is
     * the harder version of the bug to notice.
     */
    private static void useUtf8ForOutput() {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int validate(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("validate requires a task id or YAML path");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("project", loaded.project().project());
        result.put("task_id", loaded.resolved().id());
        result.put("risk", loaded.resolved().risk());
        result.put("scope_paths", loaded.resolved().scopePaths());
        result.put("acceptance_commands", loaded.resolved().acceptanceCommands());
        System.out.println(Json.write(result));
        return 0;
    }

    private static int init(String[] args) throws Exception {
        String baseRef = option(args, "--base-ref", "origin/main");
        ProjectInitializer.Result initialized = new ProjectInitializer().initialize(Path.of("."), baseRef);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("detected_build", initialized.detectedBuild());
        result.put("project_file", initialized.projectFile().toString());
        result.put("example_task", initialized.exampleTask().toString());
        result.put("checks", initialized.checks());
        result.put("next", "review files, then run warden validate example");
        System.out.println(Json.write(result));
        return 0;
    }

    private static int gates(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("gates requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        GateRunner.Outcome outcome = new GateRunner(new ProcessRunner()).run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    private static int visualQa(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("visual-qa requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        VisualQaRunner.Outcome outcome = new VisualQaRunner(new ProcessRunner()).run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /** Create a starter ~/.warden so the first dry run explains itself. Never overwrites. */
    private static int setup() throws Exception {
        UserSetup.Result outcome = new UserSetup().run(UserConfig.defaultHome());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("home", outcome.home().toString());
        result.put("created", outcome.created());
        result.put("skipped_existing", outcome.skipped());
        result.put("next", "warden profiles, then warden role reviewer <task> --dry-run");
        System.out.println(Json.write(result));
        return 0;
    }

    /**
     * What the resolver can see. An unverified profile is listed with the probe that would
     * verify it, because "why did nothing run" is the question this command exists to answer.
     */
    private static int profiles(String[] args) throws Exception {
        String verify = option(args, "--verify", null);
        if (verify != null) return verifyProfile(verify, hasFlag(args, "--confirm"));

        UserConfig user = UserConfig.load();
        List<Object> rows = new ArrayList<>();
        for (Profile profile : user.profiles().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profile", profile.name());
            row.put("role", profile.role());
            row.put("vendor", profile.vendor());
            row.put("command", profile.command());
            row.put("runner", profile.runner());
            row.put("read_only", profile.readOnly());
            row.put("verified", profile.verified());
            row.put("eligible", profile.verified());
            if (!profile.verified()) {
                row.put("blocked_because", "profile is unverified; fill verification.verified_on "
                        + "only after running its probe");
                // The exact command that would settle it, next to the reason it is blocked.
                // Making an operator find it in the file is how it gets retyped wrong.
                row.put("probe", profile.verificationProbe());
                row.put("verify_with", "warden profiles --verify " + profile.name());
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", user.policyPresent() && !user.profiles().isEmpty());
        result.put("home", user.home().toString());
        result.put("policy_present", user.policyPresent());
        result.put("profiles", rows);
        result.put("problems", user.problems());
        result.put("dangling_policy_references", user.danglingProfileReferences());
        if (!user.policyPresent()) result.put("hint", "run `warden setup` to create a starter configuration");
        System.out.println(Json.write(result));
        return Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
    }

    /**
     * Run a profile's own verification probe, and stamp the date only when asked separately.
     *
     * `verified_on` is the one field in the whole configuration that records a human
     * judgement: that someone ran the probe and confirmed the points in `what_to_check` —
     * which envelope key carries the answer, whether it exits without asking for approval,
     * whether a cost figure appears at all. Stamping that automatically on a zero exit code
     * would replace the judgement with "the binary ran", and this profile is the gate in front
     * of a role that writes to the repository.
     *
     * So the command removes the retyping and the hand-edited YAML, and leaves the reading.
     */
    private static int verifyProfile(String name, boolean confirm) throws Exception {
        UserConfig user = UserConfig.load();
        Profile profile = user.profiles().get(name);
        if (profile == null) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("ok", false);
            unknown.put("code", "profile_not_found");
            unknown.put("profile", name);
            unknown.put("known_profiles", List.copyOf(user.profiles().keySet()));
            unknown.put("problems", user.problems());
            System.out.println(Json.write(unknown));
            return 1;
        }

        ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner());
        ProfileVerifier.Probe probe = verifier.run(profile, user.home(), Duration.ofMinutes(10));
        Map<String, Object> result = new LinkedHashMap<>(ProfileVerifier.report(profile, probe));
        result.put("already_verified", profile.verified());

        if (!probe.ok()) {
            result.put("ok", false);
            result.put("code", "probe_failed");
            result.put("next", "fix the profile's args or authentication and run this again; "
                    + "nothing was stamped");
            System.out.println(Json.write(result));
            return 1;
        }

        if (!confirm) {
            result.put("ok", true);
            result.put("code", "probe_passed");
            result.put("next", "read the transcript against what_to_check above. If every point "
                    + "holds, run: warden profiles --verify " + name + " --confirm");
            result.put("stamped", false);
            System.out.println(Json.write(result));
            return 0;
        }

        Path file = user.home().resolve("profiles").resolve(name + ".yaml");
        ProfileVerifier.Stamp stamp = verifier.stamp(file, profile, LocalDate.now());
        result.put("ok", stamp.written());
        result.put("code", stamp.code());
        result.put("message", stamp.message());
        result.put("profile_file", stamp.profileFile().toString());
        result.put("stamped", stamp.written());
        if (stamp.written()) result.put("verified_on", stamp.date());
        System.out.println(Json.write(result));
        return stamp.written() ? 0 : 1;
    }

    /**
     * Run one role. `--dry-run` resolves the vendor and writes the exact prompt without
     * spending anything — the cheapest way to find out whether the setup is right.
     */
    private static int role(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("role requires a role name and a task, "
                    + "for example: warden role reviewer my-task --dry-run");
        }
        String roleName = args[1];
        String taskSelector = args[2];
        boolean dryRun = hasFlag(args, "--dry-run");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        String implementerVendor = option(args, "--implementer-vendor", null);
        String contextPath = option(args, "--context", null);

        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), taskSelector);
        UserConfig user = UserConfig.load();
        RoleRunner.Outcome outcome = new RoleRunner(new ProcessRunner()).run(
                loaded, user, roleName, runId, implementerVendor,
                contextPath == null ? null : Path.of(contextPath), dryRun);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("role", outcome.role());
        result.put("profile", outcome.profile());
        result.put("vendor", outcome.vendor());
        result.put("rejected_profiles", outcome.rejected());
        result.put("run_id", runId);
        if (outcome.report() != null) result.put("report", outcome.report().toString());
        if (outcome.details() != null) result.put("details", outcome.details());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /**
     * The bounded loop. It stops at the human gate and never lands anything: the only two
     * outcomes are `human_gate` and `human_escalation`.
     */
    private static int runLoop(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("run requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        boolean dryRun = hasFlag(args, "--dry-run");
        TaskLoop.Outcome outcome;
        try {
            ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
            UserConfig user = UserConfig.load();
            outcome = new TaskLoop(new ProcessRunner()).run(loaded, user, runId, dryRun);
        } catch (EvidenceLedger.RunExistsException duplicate) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("reason", "run_id_exists");
            result.put("next_action", "choose_new_run_id");
            result.put("run_id", runId);
            result.put("message", duplicate.getMessage());
            System.out.println(Json.write(result));
            return 1;
        } catch (Exception preflight) {
            return recordPreflightFailure(args[1], runId, preflight);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("reason", outcome.reason());
        result.put("next_action", outcome.nextAction());
        result.put("run_id", runId);
        result.put("summary", String.valueOf(outcome.summary()));
        result.put("steps", outcome.summaryReport().get("steps"));
        result.put("decision_path", outcome.summaryReport().get("decision_path"));
        result.put("decision_state", outcome.summaryReport().get("decision_state"));
        result.put("decision_options", outcome.summaryReport().get("decision_options"));
        result.put("decision_updated_at", outcome.summaryReport().get("decision_updated_at"));
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /** Turn failures before TaskLoop starts into the same durable human boundary. */
    private static int recordPreflightFailure(String taskSelector, String runId, Exception failure)
            throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        EvidenceLedger ledger = new EvidenceLedger(root, runId);
        ledger.reserveWorkflowRun(taskSelector);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", taskSelector);
        summary.put("ok", false);
        summary.put("reason", "run_preflight_failed");
        summary.put("next_action", "human_escalation");
        summary.put("error", Map.of(
                "type", failure.getClass().getName(),
                "message", String.valueOf(failure.getMessage())));
        Path file = ledger.writeReport("task-run", summary);
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision decision = store.createFailure(runId, taskSelector,
                "run_preflight_failed", file, null);
        summary.put("decision_path", root.relativize(store.decisionPath(runId))
                .toString().replace('\\', '/'));
        summary.put("decision_state", decision.state().jsonValue());
        summary.put("decision_options", decision.options());
        summary.put("decision_updated_at", decision.updatedAt().toString());
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", summary.get("decision_path")));
        ledger.append("task_run", summary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("reason", "run_preflight_failed");
        result.put("next_action", "human_escalation");
        result.put("run_id", runId);
        result.put("summary", file.toString());
        result.put("decision_path", summary.get("decision_path"));
        result.put("decision_state", summary.get("decision_state"));
        result.put("decision_options", summary.get("decision_options"));
        result.put("decision_updated_at", summary.get("decision_updated_at"));
        result.put("message", String.valueOf(failure.getMessage()));
        System.out.println(Json.write(result));
        return 1;
    }

    /**
     * Goal plus project. Isolates via Orca, writes a task, runs the loop, stops at the human
     * gate. Never lands.
     */
    private static int doIntent(String[] args) throws Exception {
        DoCommand.Options options = DoCommand.parse(args);
        UserConfig user = UserConfig.load();
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(options, user);
        System.out.println(Json.write(outcome.report()));
        return outcome.ok() ? 0 : 1;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String argument : args) if (argument.equals(name)) return true;
        return false;
    }

    private static int doctor() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        ProcessRunner processes = new ProcessRunner();
        Map<String, Object> orca = new OrcaClient(processes).status(Path.of("."));
        boolean javaSupported = Runtime.version().feature() >= 21;
        boolean projectConfig = java.nio.file.Files.isRegularFile(Path.of(".warden/project.yaml"));
        boolean orcaAvailable = Boolean.TRUE.equals(orca.get("available"));
        boolean machineGatesReady = javaSupported && projectConfig;
        UserConfig user = UserConfig.load();
        List<String> eligible = user.profiles().values().stream()
                .filter(Profile::verified).map(Profile::name).toList();
        List<String> unverified = user.profiles().values().stream()
                .filter(profile -> !profile.verified()).map(Profile::name).toList();
        boolean roleExecutionReady = user.policyPresent() && user.policy() != null
                && !eligible.isEmpty() && user.danglingProfileReferences().isEmpty();
        result.put("ok", machineGatesReady);
        result.put("warden_version", VERSION);
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_feature", (long) Runtime.version().feature());
        result.put("java_21_or_newer", javaSupported);
        // Where a Russian or otherwise non-ASCII goal silently turns into question marks.
        // Reported unconditionally: an operator should learn this from `doctor`, not from a
        // task contract that came out mangled.
        Map<String, Object> encoding = new LinkedHashMap<>();
        encoding.put("sun_jnu_encoding", System.getProperty("sun.jnu.encoding"));
        encoding.put("native_encoding", System.getProperty("native.encoding"));
        encoding.put("file_encoding", System.getProperty("file.encoding"));
        boolean argsSafe = dev.warden.run.DoCommand.argumentsCanCarryNonAscii();
        encoding.put("non_ascii_arguments_safe", argsSafe);
        if (!argsSafe) {
            encoding.put("consequence", "non-ASCII text in a command-line argument reaches Warden as "
                    + "'?'. No JVM flag changes this. Use `warden do --goal-file <utf8 file>`, or "
                    + "enable the Windows setting \"Use Unicode UTF-8 for worldwide language support\".");
        }
        result.put("argument_encoding", encoding);
        result.put("project_config_found", projectConfig);
        result.put("machine_gates_ready", machineGatesReady);
        result.put("orca", orca);
        Map<String, Object> userSummary = new LinkedHashMap<>();
        userSummary.put("home", user.home().toString());
        userSummary.put("policy_present", user.policyPresent());
        userSummary.put("profiles_loaded", (long) user.profiles().size());
        userSummary.put("verified_profiles", eligible);
        userSummary.put("unverified_profiles", unverified);
        userSummary.put("problems", user.problems());
        userSummary.put("dangling_policy_references", user.danglingProfileReferences());
        result.put("user_config", userSummary);
        result.put("role_execution_ready", roleExecutionReady);
        if (!roleExecutionReady) {
            result.put("role_execution_blocked_by", !user.policyPresent()
                    ? "no ~/.warden/policy.yaml — run `warden setup`"
                    : eligible.isEmpty()
                        ? "every profile is unverified; run its probe, then set verification.verified_on"
                        : "policy references profiles that do not load: " + user.danglingProfileReferences());
        }
        result.put("direct_cli_ready", roleExecutionReady);
        result.put("orca_adapter", Map.of(
                "available", orcaAvailable,
                "orchestration_contract", Boolean.TRUE.equals(orca.get("orchestration_contract")),
                "creates_worktrees", false,
                "completion", "worker_done or dispatch-show settlement only; terminal text is not evidence"));
        result.put("note", machineGatesReady
                ? (roleExecutionReady
                    ? "Machine gates and Direct CLI role execution are ready. Orca is an optional runner "
                        + "and only attaches to an existing worktree."
                    : "Machine gates are ready; role execution waits on a verified profile in ~/.warden.")
                : "Machine gates need Java 21+ and .warden/project.yaml in the target repository");
        System.out.println(Json.write(result));
        return Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
    }

    private static int ledger() throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        System.out.println(Json.write(new LedgerReader().summarize(root)));
        return 0;
    }

    /** Pending and resolved human decisions are Warden state, not terminal prose. */
    private static int status(String[] args) throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        ApprovalStore store = new ApprovalStore(root);
        String runId = args.length > 1 && !args[1].startsWith("--") ? args[1] : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("project", root.toString());
        if (runId != null) {
            HumanDecision decision = store.read(runId);
            result.put("decision", decision.toMap());
            result.put("decision_path", store.decisionPath(runId).toString());
        } else {
            result.put("decisions", store.list().stream().map(HumanDecision::toMap).toList());
        }
        System.out.println(Json.write(result));
        return 0;
    }

    /**
     * Record a human decision. Even an accepted decision never commits, merges, pushes or
     * deploys; it only closes the durable gate for a separate operator-owned landing step.
     */
    private static int approve(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("approve requires a run id and --decision");
        }
        String runId = args[1];
        String choice = option(args, "--decision", null);
        if (choice == null) throw new IllegalArgumentException("approve requires --decision <choice>");
        String actor = option(args, "--actor", System.getProperty("user.name", "human"));
        String note = option(args, "--note", "");
        String noteFile = option(args, "--note-file", null);
        if (noteFile != null) note = java.nio.file.Files.readString(Path.of(noteFile));

        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        ApprovalStore store = new ApprovalStore(root);
        try {
            HumanDecision pending = store.read(runId);
            String expected = option(args, "--expected-updated-at", pending.updatedAt().toString());

            // Acceptance is valid only for the exact candidate the human inspected. Reject,
            // abort and retry remain available when the tree moved, because they grant no land.
            if ("accept".equals(choice) && pending.candidateFingerprint() != null) {
                Path summaryPath = Path.of(pending.summaryPath());
                if (!summaryPath.isAbsolute()) summaryPath = root.resolve(summaryPath);
                Map<String, Object> summary = Json.parseObject(java.nio.file.Files.readString(summaryPath));
                Object base = summary.get("diff_base_commit");
                if (!(base instanceof String commit)) {
                    throw new ApprovalException("candidate_unverifiable",
                            "task summary has no immutable diff_base_commit");
                }
                String current = new dev.warden.git.GitRepository(root, new ProcessRunner()).fingerprint(commit);
                if (!pending.candidateFingerprint().equals(current)) {
                    throw new ApprovalException("candidate_changed",
                            "worktree fingerprint changed after the decision was shown; run gates again");
                }
            }

            HumanDecision resolved = store.resolve(runId, expected, choice, actor, note);
            boolean summaryProjectionUpdated = updateDecisionProjection(root, resolved);
            long waitMillis = Math.max(0L,
                    Duration.between(resolved.createdAt(), resolved.updatedAt()).toMillis());
            new dev.warden.ledger.EvidenceLedger(root, runId).append("human_decision", Map.of(
                    "decision", choice, "actor", actor,
                    "created_at", resolved.createdAt().toString(),
                    "decided_at", resolved.updatedAt().toString(),
                    "wait_millis", waitMillis,
                    "updated_at", resolved.updatedAt().toString(), "lands", false));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("code", "decision_recorded");
            result.put("decision", resolved.toMap());
            result.put("summary_projection_updated", summaryProjectionUpdated);
            result.put("lands", false);
            result.put("next", "accept".equals(choice)
                    ? "inspect the exact diff and land it manually if desired"
                    : "retry".equals(choice)
                        ? "start a new run id after addressing the recorded blocker"
                        : "no changes were landed");
            System.out.println(Json.write(result));
            return 0;
        } catch (ApprovalException failure) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("code", failure.code());
            result.put("message", failure.getMessage());
            result.put("lands", false);
            System.out.println(Json.write(result));
            return 1;
        }
    }

    /** decision.json is authoritative; task-run.json is a convenient, explicitly derived view. */
    private static boolean updateDecisionProjection(Path root, HumanDecision decision) {
        try {
            Path summary = Path.of(decision.summaryPath());
            if (!summary.isAbsolute()) summary = root.resolve(summary);
            summary = summary.toAbsolutePath().normalize();
            Path runDirectory = root.resolve(".warden/runs").resolve(decision.runId())
                    .toAbsolutePath().normalize();
            if (!summary.startsWith(runDirectory) || !java.nio.file.Files.isRegularFile(summary)) return false;
            Map<String, Object> report = Json.parseObject(java.nio.file.Files.readString(summary));
            report.put("decision_state", decision.state().jsonValue());
            report.put("decision", decision.decision());
            report.put("decision_actor", decision.actor());
            report.put("decision_note", decision.note());
            report.put("decision_updated_at", decision.updatedAt().toString());
            String file = summary.getFileName().toString();
            if (!file.endsWith(".json")) return false;
            new EvidenceLedger(root, decision.runId()).writeReport(
                    file.substring(0, file.length() - ".json".length()), report);
            return true;
        } catch (Exception projectionFailure) {
            return false;
        }
    }

    private static String option(String[] args, String name, String fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) return args[index + 1];
        }
        return fallback;
    }

    private static String loadedDefaultRunId() {
        return "run-" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void usage() {
        System.out.println("""
                warden - deterministic SDLC gates for an AI worktree; never lands changes.

                  warden setup                 create a starter ~/.warden (never overwrites)
                  warden doctor                verify Java, vendor profiles and live Orca readiness
                  warden profiles              which profiles load, which are eligible, and why not
                  warden profiles --verify N   run profile N's own probe; --confirm stamps the date
                  warden init [--base-ref REF] create a conservative project starter config
                  warden validate <task>       validate and resolve the project task contract
                  warden gates <task>          preflight and machine gates only; spends nothing
                  warden visual-qa <task>      launch preview, screenshot, assert control visibility
                  warden role <role> <task>    run one role; add --dry-run to spend nothing
                  warden do \"<goal>\" [--project DIR] [--scope NAME] [--goal-file FILE]
                                           the whole workflow; stops at the human gate
                  warden run <task>            the bounded loop; stops at the human gate
                  warden ledger                aggregate local evidence and experiment dimensions
                  warden status [run-id]       show pending/resolved human decisions
                  warden approve <run-id> --decision <choice>
                                               record a decision; never lands changes

                The command to use is `warden do`. Everything else is a piece of that loop.
                Vendor configuration lives in ~/.warden/, project configuration in .warden/.
                Warden never merges. See spec/SPEC.md and docs/ARCHITECTURE.md.""");
    }
}
