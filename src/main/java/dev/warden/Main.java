package dev.warden;

import dev.warden.approval.ApprovalException;
import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.approval.StatusCommand;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.RosterCommand;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.execution.orca.OrcaClient;
import dev.warden.execution.orca.OrcaDecisionGate;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.json.Json;
import dev.warden.ledger.CorpusImport;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.LedgerCompare;
import dev.warden.ledger.LedgerReader;
import dev.warden.ledger.ProjectIdentity;
import dev.warden.ledger.RunReport;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.DoCommand;
import dev.warden.run.NextStep;
import dev.warden.run.PilotPrepareCommand;
import dev.warden.run.Preparation;
import dev.warden.run.TaskLoop;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point. Command dispatch only — every command lives in its own class so that adding
 * one cannot change the behaviour of another.
 */
public final class Main {

    public static final String VERSION = "0.2.0";
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
                case "roster" -> roster(args);
                case "role" -> role(args);
                case "run" -> runLoop(args);
                case "do" -> doIntent(args);
                case "doctor" -> doctor();
                case "dashboard" -> dashboard(args);
                case "ledger" -> ledger(args);
                case "report" -> report(args);
                case "status" -> status(args);
                case "approve" -> approve(args);
                case "decide" -> decide(args);
                case "land" -> land(args);
                case "pilot" -> pilot(args);
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
        result.put("baseline_commands", loaded.resolved().baselineCommands());
        result.put("acceptance_commands", loaded.resolved().acceptanceCommands());
        System.out.println(Json.write(result));
        return 0;
    }

    private static int dashboard(String[] args) throws Exception {
        Path project = Path.of(option(args, "--project", ".")).toAbsolutePath().normalize();
        int port = Integer.parseInt(option(args, "--port", "0"));
        if (port < 0 || port > 65535) throw new IllegalArgumentException("dashboard port must be 0..65535");
        try (var dashboard = new dev.warden.dashboard.Dashboard(project, port)) {
            dashboard.start();
            System.out.println(Json.write(Map.of("ok", true, "url", dashboard.url(),
                    "project", project.toString(), "read_only", true)));
            if (hasFlag(args, "--open-orca")) {
                var opened = new OrcaClient(new ProcessRunner()).invoke(project, Duration.ofSeconds(20),
                        List.of("tab", "create", "--worktree", "path:" + project, "--url", dashboard.url()));
                if (!opened.ok()) System.err.println("Dashboard is available at " + dashboard.url()
                        + "; Orca did not open the tab: " + opened.stderr());
            }
            new java.util.concurrent.CountDownLatch(1).await();
        }
        return 0;
    }

    /**
     * Offline external-pilot preparation. Writes a reviewable bundle; never runs the target,
     * never calls a vendor, never edits the target checkout or the global home.
     */
    private static int pilot(String[] args) {
        if (args.length < 2 || args[1].equals("--help") || args[1].equals("-h")) {
            usage();
            return args.length < 2 ? 2 : 0;
        }
        PilotPrepareCommand.Outcome outcome = new PilotPrepareCommand().run(args);
        System.out.println(Json.write(outcome.report()));
        return outcome.exitCode();
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
        UserConfig user = UserConfig.load();
        GateRunner.Outcome outcome = new GateRunner(new ProcessRunner()).withHome(user.home())
                .run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        result.putAll(new EvidenceLedger(loaded.root(), runId, user.home()).corpusVisibility());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    private static int visualQa(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("visual-qa requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        UserConfig user = UserConfig.load();
        VisualQaRunner.Outcome outcome = new VisualQaRunner(new ProcessRunner()).withHome(user.home())
                .run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        result.putAll(new EvidenceLedger(loaded.root(), runId, user.home()).corpusVisibility());
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
            row.put("model", profile.model());
            row.put("effort", profile.effort());
            // Null for `runner: local`, which starts no process. The endpoint is what an
            // operator would have looked at the command for, so it is listed beside it.
            row.put("command", profile.command());
            row.put("runner", profile.runner());
            if ("local".equals(profile.runner())) row.put("endpoint", profile.endpoint());
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
     * See and change which profile fills which role, and a profile's model/effort, without
     * a YAML round-trip. Line edits only; comments and the rest of the file stay.
     */
    private static int roster(String[] args) throws Exception {
        RosterCommand.Outcome outcome = new RosterCommand().run(UserConfig.defaultHome(), args);
        if (outcome.text() != null) System.out.print(outcome.text());
        else System.out.println(Json.write(outcome.report()));
        return outcome.exitCode();
    }

    /**
     * Run a profile's own verification probe, and stamp the date when it passes.
     *
     * `verified_on` used to be described as a human judgement — that somebody read the
     * transcript against `what_to_check` before stamping. Nothing enforced that and nothing
     * could: `--verify --confirm` was one command, and whoever ran it was trusted to have
     * read. A field whose stated meaning the tool cannot hold is worse than a plain fact,
     * because it invites everyone to rely on a guarantee that is not there.
     *
     * So it now records the fact it can: this profile's probe ran on this date and passed.
     * That is what the resolver actually needs — no profile is dispatched whose flags have
     * never been executed — and changing which vendor fills a role is a config edit and one
     * command, not a ceremony.
     *
     * The reading is still worth doing, so `what_to_check` and the transcript path are still
     * printed. What is gone is the pretence that the date proves somebody did it. The
     * enforcement that does hold is elsewhere and is mechanical: a `read_only` role that
     * writes gets `role_violated_read_only` and its artifact discarded, whatever any flag or
     * date claimed.
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
            result.put("next", ProfileVerifier.nextStep(probe));
            System.out.println(Json.write(result));
            return 1;
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
        RoleRunner.Outcome outcome;
        try {
            outcome = new RoleRunner(new ProcessRunner()).run(
                    loaded, user, roleName, runId, implementerVendor,
                    contextPath == null ? null : Path.of(contextPath), dryRun);
        } catch (dev.warden.ledger.HomeCorpus.UnavailableException unavailable) {
            // The loop names this refusal; a single role used to let it fall through to
            // main's catch-all and reach the operator as `warden_error` on stderr. Nothing
            // was spent either way - the gate is crossed before dispatch - but a refusal
            // whose name changes with the command it was met on is not a refusal anyone can
            // handle in a script.
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("ok", false);
            refused.put("code", "ledger_unavailable");
            refused.put("role", roleName);
            refused.put("run_id", runId);
            refused.put("message", unavailable.getMessage());
            refused.put("next", "restore write access to the home corpus, then run this again");
            System.out.println(Json.write(refused));
            return 1;
        }

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
        result.putAll(new EvidenceLedger(loaded.root(), runId, user.home()).corpusVisibility());
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
        String continueFrom = option(args, "--continue", null);
        String prepare = option(args, "--prepare", null);
        Integer waitForGate = optionalMinutes(args, "--wait-for-gate");
        TaskLoop.Outcome outcome;
        Path root = null;
        try {
            ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
            root = loaded.root();
            UserConfig user = UserConfig.load();
            Carried carried = continuation(loaded.root(), continueFrom);
            dev.warden.run.Workspace card = board(args).at(loaded.root());
            java.nio.file.Path narration = narrationFile(loaded.root(), runId);
            // Not for a preview. A dry run answers in seconds and dispatches nobody; opening a
            // window on the operator's board for it is the same overreach as moving its card.
            if (hasFlag(args, "--watch") && !dryRun) card.watch(narration, runId);
            TaskLoop loop = new TaskLoop(new ProcessRunner())
                    .withProgress(dev.warden.run.Progress.tee(narration(args),
                            dev.warden.run.Progress.toFile(narration)))
                    .withWorkspace(card)
                    .withOrcaGate(!dryRun && !hasFlag(args, "--no-orca-gate"))
                    .withOverride(dev.warden.config.RunOverride.fromArgs(args));
            // Conductor's inner `warden run` is a new process and cannot see DoCommand's
            // in-memory Preparation. The reservation carries the mode when a planner ran
            // (or when auto skipped and still recorded it); --prepare is the same fact
            // arriving on the command line so a skipped auto is not recorded as off.
            if (prepare != null) {
                loop = loop.withPreparation(new TaskLoop.Preparation(
                        Preparation.parseMode(prepare), false, 0, 0, 0, false));
            }
            outcome = loop.run(loaded, user, runId, dryRun, carried.failover(), carried.continuation());
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
            return recordPreflightFailure(args[1], runId, continueFrom, preflight);
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
        result.put("decision_kind", outcome.summaryReport().get("decision_kind"));
        result.put("decision_reason", outcome.summaryReport().get("decision_reason"));
        result.put("decision_options", outcome.summaryReport().get("decision_options"));
        result.put("decision_updated_at", outcome.summaryReport().get("decision_updated_at"));
        result.put("failover_pending", outcome.summaryReport().get("failover_pending"));
        for (String key : List.of("corpus_status", "corpus_undelivered", "corpus_reason",
                "tree_safe_to_delete")) {
            Object value = outcome.summaryReport().get(key);
            if (value != null) result.put(key, value);
        }
        result.put("report_path", writeRunReport(Path.of("."), runId));
        if (!dryRun && root != null && !hasFlag(args, "--no-wait-for-gate")) {
            int minutes = waitForGate == null ? 60 : waitForGate;
            ApproveEnv env = ApproveEnv.realtime();
            result.putAll(superviseGates(root, runId, outcome.summaryReport(), args, env,
                    Main::startAdvance, decisionPage(args, minutes, env)));
        } else if (waitForGate != null) {
            // Import only. The wait never starts, retries or continues a run; a missing
            // gate is said, not treated as an answer.
            if (dryRun) {
                result.put("gate_wait", skippedGateWait("dry_run"));
            } else if (root != null) {
                result.putAll(waitForPublishedGate(root, runId, outcome.summaryReport(),
                        waitForGate, ApproveEnv.realtime()));
            }
        }
        System.out.println(Json.write(result));
        int exit = outcome.ok() ? 0 : 1;
        if (result.get("continued_ok") instanceof Boolean ok) exit = ok ? 0 : 1;
        if (result.get("gate_import") instanceof Map<?, ?> imported
                && Boolean.FALSE.equals(imported.get("ok"))) {
            exit = 1;
        }
        return exit;
    }

    /**
     * The joined view, written next to the evidence it joins. It is derived, so it is
     * regenerated rather than appended to: `warden report` recomputes it from the same files
     * at any time, and a stale copy can never outlive what it summarises.
     */
    private static String writeRunReport(Path from, String runId) {
        try {
            Path root = new ConfigLoader().findProjectRoot(from);
            Map<String, Object> report = new RunReport().of(root, runId);
            Path file = new EvidenceLedger(root, runId).writeReport("report", report);
            return file.toString();
        } catch (Exception noSummary) {
            // A run that never wrote a summary has nothing to join. The stage evidence is
            // still on disk, and saying so beats failing the command that produced it.
            return null;
        }
    }

    /** Everything one run did: stages, vendors, cost, tokens, pixels, decision. */
    private static int report(String[] args) throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        String runId = args.length > 1 && !args[1].startsWith("--") ? args[1] : null;
        if (runId == null) throw new IllegalArgumentException("report requires a run id");
        Map<String, Object> report = new RunReport().of(root, runId);
        if (hasFlag(args, "--text")) System.out.print(RunReport.render(report));
        else System.out.println(Json.write(report));
        return 0;
    }

    /**
     * {@code advance}: close this run and start the next id of the same task, carrying
     * writers. Verdicts are not reused — the point of advance is that the operator changed
     * the contract, the scope, or the tree. {@code --no-start} records the decision and
     * prints the command, for a script that wants to launch the run itself.
     */
    static Map<String, Object> startAdvance(Path root, HumanDecision resolved, String[] args,
                                            ApproveEnv env) {
        String nextId = nextRunId(root, resolved.runId());
        String command = "warden run " + resolved.taskId() + " --run-id " + nextId
                + " --continue " + resolved.runId() + " --prepare off";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("command", command);
        report.put("run_id", nextId);
        report.put("continued_from", resolved.runId());
        if (hasFlag(args, "--no-start")) {
            report.put("started", false);
            return report;
        }
        String unchanged = "advance".equals(resolved.decision()) ? advanceWouldRepeat(root, resolved) : null;
        if (unchanged != null) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_would_repeat");
            report.put("message", unchanged);
            return report;
        }
        try {
            ConfigLoader.Loaded loaded = new ConfigLoader().load(root, resolved.taskId());
            UserConfig user = env.user();
            Carried carried = continuation(root, resolved.runId());
            dev.warden.run.Workspace card = dev.warden.run.Workspace.guarded(board(args).at(root));
            java.nio.file.Path narration = narrationFile(root, nextId);
            if (hasFlag(args, "--watch")) card.watch(narration, nextId);
            TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner())
                    .withProgress(dev.warden.run.Progress.tee(narration(args),
                            dev.warden.run.Progress.toFile(narration)))
                    .withWorkspace(card)
                    .withOrcaGate(!hasFlag(args, "--no-orca-gate"))
                    .withOverride(dev.warden.config.RunOverride.fromArgs(args))
                    .run(loaded, user, nextId, false, carried.failover(), carried.continuation());
            report.put("started", true);
            report.put("ok", outcome.ok());
            report.put("reason", outcome.reason());
            report.put("next_action", outcome.nextAction());
            report.put("summary_report", outcome.summaryReport());
            report.put("report_path", writeRunReport(root, nextId));
        } catch (Exception failed) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_start_failed");
            report.put("message", String.valueOf(failed.getMessage()));
        }
        return report;
    }

    /**
     * Why starting the next run now would stop at the same wall, or null when it might not.
     *
     * {@code advance} means "I dealt with the blocker, go again". The blockers it is offered
     * for — a contract that does not permit the change, a scope that excludes the file, a
     * baseline that was already failing — are all things outside the loop, and none of them
     * is cleared by running the loop a second time. Measured: an advance answered on a
     * {@code preflight_outside_scope} stop started a second run that stopped on
     * {@code preflight_outside_scope}, with the contract byte-identical and no writer called.
     *
     * So the two things that would have to have changed are compared before a run id is
     * spent: the `.warden` tree the contract is digested from, and the working tree the stop
     * was about. If neither moved, the answer is the refusal below rather than a second run
     * folder proving it. A tree Warden cannot fingerprint is not an accusation — the run
     * starts, and the loop's own preflight has the last word as it always did.
     */
    static String advanceWouldRepeat(Path root, HumanDecision resolved) {
        Map<String, Object> prior;
        try {
            Path file = root.resolve(".warden/runs").resolve(resolved.runId()).resolve("task-run.json");
            if (!java.nio.file.Files.isRegularFile(file)) return null;
            prior = dev.warden.json.Json.parseObject(java.nio.file.Files.readString(file));
        } catch (Exception unreadable) {
            return null;
        }
        Object reason = prior.get("reason");
        if (!(reason instanceof String stopped) || !REPEATS_WITHOUT_AN_EDIT.contains(stopped)) {
            return null;
        }
        String contractNow;
        String treeNow;
        try {
            contractNow = dev.warden.config.WardenTree.digest(
                    dev.warden.config.WardenTree.snapshot(root));
            Object base = prior.get("diff_base_commit");
            if (!(base instanceof String commit)) return null;
            treeNow = new dev.warden.git.GitRepository(root, new ProcessRunner())
                    .sourceFingerprint(commit);
        } catch (Exception cannotTell) {
            return null;
        }
        if (!contractNow.equals(prior.get("contract_sha256"))) return null;
        if (resolved.candidateFingerprint() != null
                && !resolved.candidateFingerprint().equals(treeNow)) {
            return null;
        }
        return "advance would start a run that stops on the same thing: " + stopped
                + ". Neither the contract under .warden nor the working tree has changed since "
                + resolved.runId() + " stopped, and that stop is not about anything the loop can "
                + "do differently on a second pass. Edit the contract (or the tree), then answer "
                + "advance again — `warden report " + resolved.runId() + " --text` names what the "
                + "preflight objected to. To start one anyway, use the printed `warden run` "
                + "command.";
    }

    /**
     * Stops whose cause is outside the loop, so a fresh run over an unchanged contract and
     * an unchanged tree reaches the same line of code.
     */
    private static final java.util.Set<String> REPEATS_WITHOUT_AN_EDIT = java.util.Set.of(
            "preflight_outside_scope", "preflight_dirty_tree", "contract_invalid",
            "policy_invalid", "baseline_failed", "run_override_invalid",
            "independent_review_unavailable", "visual_qa_unavailable", "mcp_config_missing");

    /** bakery-3 → bakery-4; a name without a trailing number gets {@code -2}. */
    static String nextRunId(Path root, String runId) {
        String candidate = bumpRunId(runId);
        Path runs = root.resolve(".warden/runs");
        for (int i = 0; i < 50; i++) {
            if (!java.nio.file.Files.isDirectory(runs.resolve(candidate))) return candidate;
            candidate = bumpRunId(candidate);
        }
        return runId + "-" + Long.toHexString(System.currentTimeMillis());
    }

    static String bumpRunId(String runId) {
        java.util.regex.Matcher trailing = java.util.regex.Pattern.compile("^(.*-)(\\d+)$")
                .matcher(runId);
        if (trailing.matches()) {
            return trailing.group(1) + (Long.parseLong(trailing.group(2)) + 1);
        }
        return runId + "-2";
    }

    /**
     * The substitutions a human authorised on an earlier run, read back from that run's own
     * decision record.
     *
     * The authorisation is the resolved decision, not a flag: a flag would let anyone grant
     * the switch, and a persisted "codex is out" would go stale the moment the quota window
     * rolled over. Naming a prior run ties the permission to one recorded human choice, for
     * one role, to one named profile.
     */
    /** Both things a recorded decision can carry forward: an authorised swap, or a rejection. */
    /** Package-visible so the loop suite can drive a recorded decision into a run. */
    record Carried(Map<String, String> failover, TaskLoop.Continuation continuation) {}

    static Carried continuation(Path root, String priorRunId) throws Exception {
        if (priorRunId == null) return new Carried(Map.of(), TaskLoop.Continuation.NONE);
        HumanDecision decision = new ApprovalStore(root).read(priorRunId);
        Map<String, String> priorSubstitutions = new LinkedHashMap<>();
        Path priorFile = root.resolve(decision.summaryPath());
        if (java.nio.file.Files.isRegularFile(priorFile)) {
            Object previous = Json.parseObject(java.nio.file.Files.readString(priorFile)).get("authorized_failover");
            if (previous instanceof Map<?, ?> map) map.forEach((key, value) ->
                    priorSubstitutions.put(String.valueOf(key), String.valueOf(value)));
        }
        // A rejection is feedback, not an authorisation: it grants nothing, it only tells the
        // next implementer what a person already objected to. Refusing to carry it meant the
        // one piece of human judgement in the loop was the one piece nobody downstream saw.
        if (decision.kind() == HumanDecision.Kind.SUCCESS && "reject".equals(decision.decision())) {
            if (decision.note() == null || decision.note().isBlank()) {
                throw new IllegalArgumentException("--continue " + priorRunId + " was rejected "
                        + "without a note, so there is nothing to carry. Record why with "
                        + "`warden approve " + priorRunId + " --decision reject --note \"...\"`, "
                        + "or run without --continue");
            }
            return new Carried(Map.of(),
                    new TaskLoop.Continuation(priorRunId, decision.note()));
        }
        // A failure a person answered with `retry` is a request to run the same task again.
        // When that failure was never about the work, the roles that already passed judged
        // the very tree that is still sitting there, and TaskLoop re-checks that before it
        // believes any of it.
        if (decision.kind() == HumanDecision.Kind.FAILURE && "retry".equals(decision.decision())) {
            return new Carried(priorSubstitutions,
                    new TaskLoop.Continuation(priorRunId, null, true));
        }
        // The operator (or whoever answered the gate) dealt with the blocker and wants the
        // next run of this task now. Writers carry; verdicts do not — a contract or scope
        // edit is why advance exists, and those change what a reading is about.
        if ((decision.kind() == HumanDecision.Kind.FAILURE && "advance".equals(decision.decision()))
                || (decision.kind() == HumanDecision.Kind.CONTRACT_CHANGE && "apply".equals(decision.decision()))) {
            return new Carried(priorSubstitutions,
                    new TaskLoop.Continuation(priorRunId, decision.note(), false));
        }
        if (decision.kind() != HumanDecision.Kind.FAILOVER) {
            throw new IllegalArgumentException("--continue " + priorRunId + " names a "
                    + decision.kind().jsonValue() + " decision that is neither a rejection, a "
                    + "retry, nor an advance; only a failover decision authorises a vendor substitution");
        }
        // A failover answered `retry` is the person waiting out the quota window with the
        // same roster: no substitution is authorised, and the spent stop was never about the
        // work, so the verdicts already reached are kept.
        if (decision.state() == HumanDecision.State.RESOLVED && "retry".equals(decision.decision())) {
            return new Carried(priorSubstitutions, new TaskLoop.Continuation(priorRunId, null, true));
        }
        if (decision.state() != HumanDecision.State.RESOLVED || !"switch".equals(decision.decision())) {
            throw new IllegalArgumentException("--continue " + priorRunId + " has not been "
                    + "resolved as 'switch' or 'retry' (state=" + decision.state().jsonValue()
                    + ", decision=" + decision.decision() + "); record the choice first with "
                    + "`warden approve " + priorRunId + " --decision switch|retry`");
        }
        Path summary = root.resolve(decision.summaryPath());
        Map<String, Object> priorSummary = Json.parseObject(java.nio.file.Files.readString(summary));
        Object pending = priorSummary.get("failover_pending");
        if (!(pending instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("--continue " + priorRunId + ": that run's summary "
                    + "names no pending failover, so there is nothing to authorise");
        }
        Object role = map.get("role");
        Object profile = map.get("to_profile");
        if (role == null || profile == null) {
            throw new IllegalArgumentException("--continue " + priorRunId
                    + ": the recorded failover names no role and profile");
        }
        // A spent subscription is not a verdict, so the switch keeps what was already judged.
        // It used to carry nothing: run p2-planner-3 paid its writer thirty minutes to redo a
        // candidate it had already written, and both readers again, because the only thing
        // that changed was who would read next. TaskLoop still re-checks the tree, the
        // acceptance and each stage's roster; the stage whose vendor ran out has no passing
        // row and is dispatched to the profile the person just chose.
        Map<String, String> substitutions = new LinkedHashMap<>();
        if (priorSummary.get("authorized_failover") instanceof Map<?, ?> authorized) {
            authorized.forEach((key, value) -> substitutions.put(String.valueOf(key), String.valueOf(value)));
        }
        Object stage = map.get("stage");
        substitutions.put(stage instanceof String text ? text : String.valueOf(role), String.valueOf(profile));
        return new Carried(substitutions,
                new TaskLoop.Continuation(priorRunId, null, true));
    }

    /** Turn failures before TaskLoop starts into the same durable human boundary. */
    private static int recordPreflightFailure(String taskSelector, String runId,
                                              String continueFrom, Exception failure)
            throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        Map<String, Object> result = recordPreflightFailure(root, UserConfig.load(), taskSelector,
                runId, continueFrom, failure);
        System.out.println(Json.write(result));
        return 1;
    }

    /** Package-visible so the loop suite can fail a continuation before it starts. */
    static Map<String, Object> recordPreflightFailure(Path root, UserConfig user,
                                                      String taskSelector, String runId,
                                                      String continueFrom, Exception failure)
            throws Exception {
        EvidenceLedger ledger = new EvidenceLedger(root, runId, user.home());
        if (continueFrom != null) {
            try {
                ledger.bindParent(EvidenceLedger.runInstanceIdOf(root, continueFrom));
            } catch (Exception unknownParent) {
                // Lineage stays unknown; the chain below still carries the spend.
            }
        }
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
        if (continueFrom != null) {
            // The run never started, but it was a continuation, and the next one reads its
            // chain from here. When the recorded decision cannot even be read, the earlier
            // spend is counted rather than dropped.
            summary.put("continued_from", continueFrom);
            boolean inherits = true;
            try {
                inherits = continuation(root, continueFrom).continuation().reuseJudgements();
            } catch (Exception unreadable) {
                inherits = true;
            }
            summary.put("chain", TaskLoop.chainForPreflightFailure(root, continueFrom, inherits, runId));
        }
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
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);

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
        result.putAll(ledger.corpusVisibility());
        result.put("message", String.valueOf(failure.getMessage()));
        return result;
    }

    /**
     * Goal plus project. Isolates via Orca, writes a task, runs the loop, stops at the human
     * gate. Never lands.
     */
    private static int doIntent(String[] args) throws Exception {
        DoCommand.Options options = DoCommand.parse(args);
        UserConfig user = UserConfig.load();
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner(), narration(args))
                .withWorkspace(board(args), hasFlag(args, "--watch"))
                .withOrcaGate(!hasFlag(args, "--no-orca-gate"))
                .withOverride(dev.warden.config.RunOverride.fromArgs(args))
                .run(options, user);
        Map<String, Object> report = new LinkedHashMap<>(outcome.report());
        Object runId = report.get("run_id");
        if (runId instanceof String id && outcome.worktree() != null) {
            report.put("report_path", writeRunReport(outcome.worktree(), id));
            Path summary = outcome.worktree().resolve(".warden/runs").resolve(id).resolve("task-run.json");
            if (!options.dryRun() && !options.conductor() && !hasFlag(args, "--no-wait-for-gate")
                    && java.nio.file.Files.isRegularFile(summary)) {
                Integer minutes = optionalMinutes(args, "--wait-for-gate");
                ApproveEnv env = ApproveEnv.realtime();
                report.putAll(superviseGates(outcome.worktree(), id,
                        Json.parseObject(java.nio.file.Files.readString(summary)), args, env,
                        Main::startAdvance, decisionPage(args, minutes == null ? 60 : minutes, env)));
            }
        }
        System.out.println(Json.write(report));
        return Boolean.TRUE.equals(report.get("continued_ok")) || outcome.ok() ? 0 : 1;
    }

    /**
     * The live account of a long run, on stderr.
     *
     * stdout is one JSON object and stays that way — Conductor and every script read it —
     * so the narration goes to the other stream, where redirecting one does not disturb the
     * other. `--quiet` turns it off for anyone who wants neither.
     */
    private static dev.warden.run.Progress narration(String[] args) {
        return hasFlag(args, "--quiet") ? dev.warden.run.Progress.SILENT
                : dev.warden.run.Progress.toStderr();
    }

    /**
     * The same run, on the board it is running on.
     *
     * Not tied to `--quiet`: that silences one terminal, and a run piped to a file is exactly
     * the run whose progress you want on a card instead. Outside an Orca worktree the attach
     * probe finds no board and every call is a no-op, so this needs no condition of its own —
     * `--no-workspace-status` exists for the operator who has a board and does not want Warden
     * writing to it.
     */
    /**
     * Where a run's own account of itself is kept.
     *
     * Beside the evidence, not in it. `warden report` never reads this: the ledger holds every
     * fact, and this holds the order a person would have watched them arrive in. It exists so
     * a run started from a script, a scheduler or a closed terminal still has one, and so
     * `--watch` has something to follow.
     */
    private static java.nio.file.Path narrationFile(Path root, String runId) {
        return root.resolve(".warden/runs").resolve(runId).resolve("narration.log");
    }

    private static dev.warden.run.Workspace.Source board(String[] args) {
        if (hasFlag(args, "--no-workspace-status")) return dev.warden.run.Workspace.Source.NONE;
        return worktree -> dev.warden.execution.orca.OrcaWorkspace.attach(new ProcessRunner(), worktree);
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
        // The chain that will actually run, so an operator can read it back before paying
        // for a run rather than discovering the order from a summary afterwards.
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("declared", user.policy() != null && user.policy().workflowDeclared());
        chain.put("stages", (user.policy() != null ? user.policy().workflow()
                : dev.warden.config.Workflow.builtIn()).toList());
        result.put("workflow", chain);
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

    private static int ledger(String[] args) throws Exception {
        boolean global = false;
        boolean compare = false;
        boolean text = false;
        String projectId = null;
        Path baselineFile = null;
        List<Path> imports = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--global" -> global = true;
                case "--compare" -> compare = true;
                case "--text" -> text = true;
                case "--project-id" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("ledger --project-id needs a value");
                    }
                    projectId = args[++index];
                }
                case "--baseline-file" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("ledger --baseline-file needs a path");
                    }
                    baselineFile = Path.of(args[++index]);
                }
                case "--import" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("ledger --import needs a path");
                    }
                    imports.add(Path.of(args[++index]));
                }
                default -> throw new IllegalArgumentException("ledger: unknown argument '" + arg + "'");
            }
        }
        if (compare && !imports.isEmpty()) {
            // Name the flag the operator actually typed, matching --import vs --global.
            throw new IllegalArgumentException("ledger --compare cannot be combined with --import");
        }
        if (!imports.isEmpty() && (global || projectId != null)) {
            // Name the flag the operator actually typed. A message about `--global` sends
            // someone who passed `--project-id` looking for an argument that is not there.
            throw new IllegalArgumentException("ledger --import cannot be combined with "
                    + (global ? "--global" : "--project-id"));
        }
        if (projectId != null && !global) {
            throw new IllegalArgumentException("ledger --project-id is only valid with --global");
        }
        if (compare && (global || projectId != null)) {
            throw new IllegalArgumentException("ledger --compare cannot be combined with "
                    + (global ? "--global" : "--project-id"));
        }
        if (text && !compare) {
            throw new IllegalArgumentException("ledger --text is only valid with --compare");
        }
        if (baselineFile != null && !compare) {
            throw new IllegalArgumentException("ledger --baseline-file is only valid with --compare");
        }
        if (!imports.isEmpty()) {
            Map<String, Object> result = CorpusImport.run(UserConfig.defaultHome(), imports);
            System.out.println(Json.write(result));
            return Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
        }
        if (global) {
            if (projectId == null || projectId.isBlank()) {
                Optional<Path> found = new ConfigLoader().locateProjectRoot(Path.of("."));
                if (found.isPresent()) {
                    String recorded = ProjectIdentity.recorded(found.get());
                    if (recorded != null && !recorded.isBlank()) projectId = recorded;
                }
            }
            // Blank identity is a filter, not an error: it selects imported measurements
            // that never carried a project_id. Inside a project the recorded identity
            // still wins when the operator omitted --project-id.
            System.out.println(Json.write(
                    new LedgerReader().summarizeCorpus(UserConfig.defaultHome(), projectId)));
            return 0;
        }
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        if (compare) {
            Map<String, Object> report = LedgerCompare.compare(root, baselineFile);
            if (text) System.out.print(LedgerCompare.render(report));
            else System.out.println(Json.write(report));
            return 0;
        }
        System.out.println(Json.write(new LedgerReader().summarize(root)));
        return 0;
    }

    /** Pending and resolved human decisions are Warden state, not terminal prose. */
    private static int status(String[] args) throws Exception {
        StatusCommand.Outcome outcome = new StatusCommand(new ProcessRunner()).run(Path.of("."), args);
        System.out.println(Json.write(outcome.report()));
        return outcome.ok() ? 0 : 1;
    }

    /**
     * Record a human decision. Even an accepted decision never commits, merges, pushes or
     * deploys; it only closes the durable gate for a separate operator-owned landing step.
     */
    /**
     * The decision page for a run that is already waiting: the `do` that stopped at it has
     * exited, timed out, or ran outside the supervisor. Opens "Warden · решение" in this
     * worktree's Orca browser and waits for the answer exactly as `do` does, continuing the
     * loop on retry, advance, switch or apply.
     */
    private static int decide(String[] args) throws Exception {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("decide requires a run id: warden decide <run-id>");
        }
        String runId = args[1];
        Optional<Path> found = new ConfigLoader().locateProjectRoot(Path.of("."));
        if (found.isEmpty()) {
            System.out.println(Json.write(StatusCommand.notAWardenProject(Path.of("."))));
            return 1;
        }
        Path root = found.get();
        Path summaryFile = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        if (!java.nio.file.Files.isRegularFile(summaryFile)) {
            System.out.println(Json.write(Map.of("ok", false, "code", "run_not_found",
                    "message", "no summary for " + runId + " under " + root.resolve(".warden/runs"))));
            return 1;
        }
        Integer minutes = optionalMinutes(args, "--wait-minutes");
        ApproveEnv env = ApproveEnv.realtime();
        Map<String, Object> summary = Json.parseObject(java.nio.file.Files.readString(summaryFile));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.putAll(superviseGates(root, runId, summary, args, env, Main::startAdvance,
                (path, id, shown) -> awaitDecision(path, id, args, minutes == null ? 60 : minutes, env,
                        url -> !hasFlag(args, "--no-open") && new OrcaClient(new ProcessRunner())
                                .invoke(path, Duration.ofSeconds(20),
                                        List.of("tab", "create", "--worktree", "path:" + path, "--url", url))
                                .ok())));
        HumanDecision after = new ApprovalStore(root).read(runId);
        report.put("ok", after.state() == HumanDecision.State.RESOLVED);
        report.put("decision", after.toMap());
        System.out.println(Json.write(report));
        return after.state() == HumanDecision.State.RESOLVED ? 0 : 1;
    }

    private static int approve(String[] args) throws Exception {
        ApproveOutcome outcome = approveDecision(Path.of("."), args, ApproveEnv.realtime());
        System.out.println(Json.write(outcome.report()));
        return outcome.ok() ? 0 : 1;
    }

    /**
     * Clock, sleep and gate reader for {@code --from-orca --wait-minutes}. Production uses
     * the wall clock and a real Orca read; tests inject all three so a wait is a sequence
     * of polls, not a sleep.
     */
    static final class ApproveEnv {
        @FunctionalInterface
        interface Clock { long nowMillis(); }

        @FunctionalInterface
        interface Sleeper { void sleep(long millis) throws InterruptedException; }

        @FunctionalInterface
        interface Gates { OrcaDecisionGate.Answer read(Path root, OrcaLifecycle.Gate gate) throws Exception; }

        final Clock clock;
        final Sleeper sleeper;
        final Gates gates;
        /** Null means {@link UserConfig#load()}; tests pass an isolated home. */
        final Path configHome;

        ApproveEnv(Clock clock, Sleeper sleeper, Gates gates) {
            this(clock, sleeper, gates, null);
        }

        ApproveEnv(Clock clock, Sleeper sleeper, Gates gates, Path configHome) {
            this.clock = clock;
            this.sleeper = sleeper;
            this.gates = gates;
            this.configHome = configHome;
        }

        UserConfig user() throws java.io.IOException {
            return configHome == null ? UserConfig.load() : UserConfig.load(configHome);
        }

        static ApproveEnv realtime() {
            return new ApproveEnv(
                    System::currentTimeMillis,
                    millis -> Thread.sleep(millis),
                    (root, gate) -> new OrcaDecisionGate(new ProcessRunner()).read(root, gate),
                    null);
        }
    }

    record ApproveOutcome(boolean ok, Map<String, Object> report) {}

    static ApproveOutcome approveDecision(Path start, String[] args, ApproveEnv env) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "approve requires a run id and either --decision or --from-orca");
        }
        String runId = args[1];
        boolean fromOrca = hasFlag(args, "--from-orca");
        String choice = option(args, "--decision", null);
        Integer waitMinutes = optionalMinutes(args, "--wait-minutes");
        if (choice == null && !fromOrca) {
            throw new IllegalArgumentException("approve requires --decision <choice> or --from-orca");
        }
        if (choice != null && fromOrca) {
            throw new IllegalArgumentException("approve takes --decision or --from-orca, not both");
        }
        if (waitMinutes != null && !fromOrca) {
            throw new IllegalArgumentException("--wait-minutes is only valid with --from-orca");
        }
        String actor = option(args, "--actor", fromOrca ? null
                : System.getProperty("user.name", "human"));
        String note = option(args, "--note", "");
        String noteFile = option(args, "--note-file", null);
        if (noteFile != null) note = java.nio.file.Files.readString(Path.of(noteFile));

        Optional<Path> found = new ConfigLoader().locateProjectRoot(start);
        if (found.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>(StatusCommand.notAWardenProject(start));
            result.put("lands", false);
            return new ApproveOutcome(false, result);
        }
        Path root = found.get();
        ApprovalStore store = new ApprovalStore(root);
        try {
            HumanDecision pending = store.read(runId);
            Map<String, Object> gateReport = null;
            if (fromOrca) {
                if (pending.state() != HumanDecision.State.PENDING) {
                    throw new ApprovalException("duplicate_decision",
                            "run " + runId + " is already resolved");
                }
                GatePoll poll = pollGate(root, runId, store, env, waitMinutes);
                if (poll.timeout() != null) return new ApproveOutcome(false, poll.timeout());
                MappedGate mapped = poll.mapped();
                choice = mapped.choice();
                pending = mapped.pending();
                if (actor == null) actor = "orca-gate:" + mapped.gate().gateId();
                gateReport = mapped.gateReport();
            }
            String expected = option(args, "--expected-updated-at", pending.updatedAt().toString());

            // A gate past its declared life can still be closed, but not accepted on the
            // strength of evidence that was current when it was published: a live page, a
            // deployment, a network observation may all have moved since. Reject, abort and
            // retry remain available, and an explicit acknowledgement re-opens accept.
            if ("accept".equals(choice) && pending.expiredAt(java.time.Instant.ofEpochMilli(env.clock.nowMillis()))
                    && !hasFlag(args, "--acknowledge-expired")) {
                throw new ApprovalException("gate_expired", "run " + runId + " asked its question "
                        + "on " + pending.createdAt() + " and its gate expired on "
                        + pending.expiresAt() + " (budgets.gate_ttl_hours). Re-check anything "
                        + "that can go stale, then accept with --acknowledge-expired, or answer "
                        + "reject/retry/abort; no answer is never an acceptance.");
            }
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
                String current = new dev.warden.git.GitRepository(root, new ProcessRunner())
                        .sourceFingerprint(commit);
                if (!pending.candidateFingerprint().equals(current)) {
                    throw new ApprovalException("candidate_changed",
                            "worktree fingerprint changed after the decision was shown; run gates again");
                }
            }

            // Checked before the decision is recorded, whoever starts the next run. With
            // `--no-start` the caller starts it — the decision page's supervisor does — and a
            // refusal found after the gate was closed left nothing to answer and no run.
            if ("advance".equals(choice)) {
                String repeat = advanceWouldRepeat(root, pending);
                if (repeat != null) throw new ApprovalException("advance_would_repeat", repeat);
            }
            final boolean applyProposal = "apply".equals(choice);
            HumanDecision resolved = store.resolve(runId, expected, choice, actor, note, current -> {
                if (applyProposal) dev.warden.run.ContractProposal.apply(root, current);
            });
            boolean summaryProjectionUpdated = updateDecisionProjection(root, resolved);
            long waitMillis = Math.max(0L,
                    Duration.between(resolved.createdAt(), resolved.updatedAt()).toMillis());
            Map<String, Object> recorded = new LinkedHashMap<>();
            recorded.put("decision", choice);
            recorded.put("actor", actor);
            recorded.put("source", fromOrca ? "orca_gate" : "cli");
            if (gateReport != null) recorded.put("orca_gate", gateReport);
            recorded.put("created_at", resolved.createdAt().toString());
            recorded.put("decided_at", resolved.updatedAt().toString());
            recorded.put("wait_millis", waitMillis);
            recorded.put("updated_at", resolved.updatedAt().toString());
            recorded.put("lands", false);
            UserConfig user = env.user();
            EvidenceLedger decisionLedger = new EvidenceLedger(root, runId, user.home());
            decisionLedger.append("human_decision", recorded);
            // The card stops asking. `accept`, `abort` and a rejection with nothing to carry
            // close the run; every other decision expects another one, so it goes back to the
            // running column rather than to a column that reads as done to whoever glances at
            // the board next.
            boolean closes = "accept".equals(choice) || "abort".equals(choice)
                    || ("reject".equals(choice) && !rejectedWithNote(resolved.kind(), choice, note));
            dev.warden.run.Workspace card = dev.warden.run.Workspace.guarded(board(args).at(root));
            card.state(closes ? dev.warden.run.Workspace.State.SETTLED
                    : dev.warden.run.Workspace.State.RUNNING);
            card.note(runId + " · " + choice + " by " + actor
                    + (note.isBlank() ? "" : " · " + note) + " · nothing was landed");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("code", "decision_recorded");
            result.put("decision", resolved.toMap());
            if (gateReport != null) result.put("orca_gate", gateReport);
            result.put("summary_projection_updated", summaryProjectionUpdated);
            result.putAll(decisionLedger.corpusVisibility());
            result.put("lands", false);
            if ("advance".equals(choice) || "apply".equals(choice)) {
                Map<String, Object> started = startAdvance(root, resolved, args, env);
                result.put("next_run", started);
                result.put("next", started.get("command"));
            } else {
                result.put("next", "accept".equals(choice)
                        ? "inspect the exact diff and land it manually if desired"
                        : "retry".equals(choice)
                            ? "start a new run id after addressing the recorded blocker"
                            : "no changes were landed");
            }
            return new ApproveOutcome(true, result);
        } catch (ApprovalException failure) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("code", failure.code());
            result.put("message", failure.getMessage());
            result.put("project", root.toString());
            result.put("lands", false);
            return new ApproveOutcome(false, result);
        }
    }

    record MappedGate(HumanDecision pending, OrcaLifecycle.Gate gate,
                      OrcaDecisionGate.Answer answer, String choice,
                      Map<String, Object> gateReport) {}

    record GatePoll(MappedGate mapped, Map<String, Object> timeout) {}

    /**
     * Read the published gate and map its free-text resolution onto one of Warden's
     * options. {@code gate_pending} and {@code gate_unreadable} wait, with 5 s then
     * ×1.5 capped at 10 s, until {@code waitMinutes}; everything else fails on the
     * first poll, as a plain {@code --from-orca} already did.
     */
    static GatePoll pollGate(Path root, String runId, ApprovalStore store, ApproveEnv env,
                             Integer waitMinutes) throws Exception {
        long start = env.clock.nowMillis();
        long deadline = waitMinutes == null ? start : start + waitMinutes.longValue() * 60_000L;
        // The wait never outlives the gate's own life: polling past it would only find an
        // answer the store then refuses. The deadline is cut, the reason is said.
        try {
            HumanDecision pending = store.read(runId);
            if (pending.expiresAt() != null) {
                deadline = Math.min(deadline, pending.expiresAt().toEpochMilli());
            }
        } catch (Exception unreadable) {
            // The poll below reports an unreadable decision on its own terms.
        }
        long delayMs = 5_000L;
        int polls = 0;
        ApprovalException last = null;
        while (true) {
            polls++;
            try {
                return new GatePoll(readMappedGate(root, runId, store, env), null);
            } catch (ApprovalException failure) {
                if (!"gate_pending".equals(failure.code()) && !"gate_unreadable".equals(failure.code())) {
                    throw failure;
                }
                last = failure;
            }
            long now = env.clock.nowMillis();
            if (waitMinutes == null) throw last;
            if (now >= deadline) {
                return new GatePoll(null, gateTimeout(root, runId, store, start, now, polls, last));
            }
            long remaining = deadline - now;
            long sleep = Math.min(delayMs, remaining);
            if (sleep <= 0) {
                return new GatePoll(null, gateTimeout(root, runId, store, start, now, polls, last));
            }
            try {
                env.sleeper.sleep(sleep);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new GatePoll(null, gateTimeout(root, runId, store, start,
                        env.clock.nowMillis(), polls, last));
            }
            delayMs = Math.min(Math.round(delayMs * 1.5d), 10_000L);
        }
    }

    /**
     * One attempt: lifecycle, version token, Orca read, option mapping. The candidate
     * fingerprint and the store's duplicate protection still run on the answer that is
     * finally imported, not here.
     */
    static MappedGate readMappedGate(Path root, String runId, ApprovalStore store, ApproveEnv env)
            throws Exception {
        HumanDecision pending = store.read(runId);
        if (pending.state() != HumanDecision.State.PENDING) {
            throw new ApprovalException("duplicate_decision",
                    "run " + runId + " is already resolved");
        }
        OrcaLifecycle.Gate gate;
        try {
            gate = new OrcaLifecycle(root, runId).read().gate();
        } catch (java.io.IOException unreadable) {
            // A record Warden cannot read is not a record that says no gate exists.
            throw new ApprovalException("orca_lifecycle_unreadable",
                    "cannot read the Orca record for run " + runId + ": "
                            + unreadable.getMessage());
        }
        if (gate == null) {
            throw new ApprovalException("no_orca_gate",
                    "run " + runId + " never published a decision gate to Orca");
        }
        // The version token. A gate answers the question it was published about; if the
        // pending decision has moved since, the answer is about something else.
        if (!gate.decisionUpdatedAt().equals(pending.updatedAt().toString())) {
            throw new ApprovalException("stale_decision",
                    "gate " + gate.gateId() + " was published for an older version of run "
                            + runId);
        }
        OrcaDecisionGate.Answer answer = env.gates.read(root, gate);
        if (answer == null) {
            throw new ApprovalException("gate_unreadable",
                    "Orca did not return gate " + gate.gateId());
        }
        if (!answer.resolved()) {
            throw new ApprovalException("gate_pending",
                    "gate " + gate.gateId() + " has not been answered yet");
        }
        String choice = OrcaDecisionGate.choose(answer.resolution(), pending.options());
        if (choice == null) {
            // Orca accepts free text as a resolution. Warden accepts one of its own
            // options and nothing else, because guessing what a sentence meant is the
            // judgement being delegated in the first place.
            throw new ApprovalException("gate_resolution_unmapped",
                    "gate " + gate.gateId() + " was resolved as "
                            + Json.write(answer.resolution()) + ", which is not one of "
                            + pending.options());
        }
        Map<String, Object> gateReport = new LinkedHashMap<>();
        gateReport.put("gate_id", gate.gateId());
        gateReport.put("task_id", gate.taskId());
        gateReport.put("orca_run_id", gate.orcaRunId());
        gateReport.put("resolution", answer.resolution());
        return new MappedGate(pending, gate, answer, choice, gateReport);
    }

    private static Map<String, Object> gateTimeout(Path root, String runId, ApprovalStore store,
                                                   long startMillis, long nowMillis, int polls,
                                                   ApprovalException last) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("code", "gate_pending");
        try {
            HumanDecision pending = store.read(runId);
            if (pending.expiresAt() != null) {
                result.put("gate_expires_at", pending.expiresAt().toString());
                result.put("gate_expired", pending.expiredAt(java.time.Instant.ofEpochMilli(nowMillis)));
            }
        } catch (Exception unreadable) {
            // The timeout report stands without it.
        }
        result.put("waited_seconds", Math.max(0L, (nowMillis - startMillis) / 1000L));
        result.put("polls", (long) polls);
        result.put("last_error", last == null ? "gate_pending" : last.getMessage());
        result.put("next_step", timeoutNextStep(root, runId, store));
        result.put("project", root.toString());
        result.put("lands", false);
        result.put("message", "no answer is never accept or reject; the decision was not changed");
        return result;
    }

    private static Object timeoutNextStep(Path root, String runId, ApprovalStore store) {
        try {
            HumanDecision pending = store.read(runId);
            Path summary = Path.of(pending.summaryPath());
            if (!summary.isAbsolute()) summary = root.resolve(summary);
            if (java.nio.file.Files.isRegularFile(summary)) {
                Map<String, Object> body = Json.parseObject(java.nio.file.Files.readString(summary));
                if (body.get("next_step") != null) return body.get("next_step");
                Object reason = body.get("reason");
                if (reason instanceof String text) return NextStep.of(text, body, root);
            }
        } catch (Exception ignored) {
            // A timeout still has to name a next step even if the summary cannot be read.
        }
        return "warden approve " + runId + " --from-orca --wait-minutes <N>";
    }

    /**
     * Thin wrapper around the same waiter {@code warden run --wait-for-gate} uses. Never
     * starts a run: it imports one published gate or says why the wait was skipped.
     */
    static Map<String, Object> waitForPublishedGate(Path root, String runId,
                                                    Map<String, Object> summary, int waitMinutes,
                                                    ApproveEnv env) throws Exception {
        Object published = summary == null ? null : summary.get("orca_gate");
        if (!(published instanceof Map<?, ?> gate) || !Boolean.TRUE.equals(gate.get("published"))) {
            String why;
            if (!(published instanceof Map<?, ?> map)) why = "no_orca_gate";
            else if (map.get("reason") != null) why = String.valueOf(map.get("reason"));
            else why = "gate_not_published";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gate_wait", skippedGateWait(why));
            return result;
        }
        ApproveOutcome imported = approveDecision(root,
                new String[] {"approve", runId, "--from-orca", "--wait-minutes",
                        Integer.toString(waitMinutes), "--no-start"},
                env);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate_import", imported.report());
        return result;
    }

    @FunctionalInterface
    interface ContinuationStarter {
        Map<String, Object> start(Path root, HumanDecision decision, String[] args, ApproveEnv env);
    }

    /** How the supervisor waits for one pending decision to be answered. */
    @FunctionalInterface
    interface DecisionWaiter {
        Map<String, Object> await(Path root, String runId, Map<String, Object> summary) throws Exception;
    }

    /** Decisions whose answer is "go on": the supervisor starts the continuation itself. */
    static final List<String> CONTINUING_DECISIONS = List.of("switch", "advance", "apply", "retry");

    /**
     * Whether an answer asks the supervisor to start the next run: a continuing decision, or
     * a rejection that says why. The note is what the next implementer is handed; a rejection
     * without one has nothing to carry and closes the work, as `--continue` always required.
     */
    static boolean continues(HumanDecision resolved) {
        if (CONTINUING_DECISIONS.contains(resolved.decision())) return true;
        return rejectedWithNote(resolved.kind(), resolved.decision(), resolved.note());
    }

    static boolean rejectedWithNote(HumanDecision.Kind kind, String decision, String note) {
        return kind == HumanDecision.Kind.SUCCESS && "reject".equals(decision)
                && note != null && !note.isBlank();
    }

    /** Own the operator wait outside TaskLoop's paid execution budget. Each restart is durable. */
    static Map<String, Object> superviseGates(Path root, String runId, Map<String, Object> summary,
                                             String[] args, int minutes, ApproveEnv env,
                                             ContinuationStarter starter) throws Exception {
        return superviseGates(root, runId, summary, args, env, starter, gateOnly(minutes, env));
    }

    /**
     * The wait this supervisor had before the decision page: an Orca gate answer for a stop
     * that is not an acceptance. Kept for callers outside an Orca worktree, where there is no
     * gate and no page to open.
     */
    static DecisionWaiter gateOnly(int minutes, ApproveEnv env) {
        return (root, runId, summary) -> {
            String kind = String.valueOf(summary.get("decision_kind"));
            if (!"failover".equals(kind) && !"failure".equals(kind) && !"contract_change".equals(kind)) {
                return Map.of("gate_wait", skippedGateWait("success_is_a_separate_acceptance"));
            }
            return waitForPublishedGate(root, runId, summary, minutes, env);
        };
    }

    /**
     * The decision as a page in Orca's browser, with a button per option.
     *
     * Only where Warden already published an Orca gate — inside an Orca worktree with Orca
     * running — so a `warden run` in a plain checkout keeps its old behaviour and never blocks
     * on a page nobody can see. Everywhere else, and with `--no-decision-page`, the wait is
     * {@link #gateOnly}. The answer is taken from whichever comes first: the page, `warden
     * approve` typed anywhere, or the Orca gate; each one goes through {@link #approveDecision}.
     */
    static DecisionWaiter decisionPage(String[] args, int minutes, ApproveEnv env) {
        DecisionWaiter fallback = gateOnly(minutes, env);
        return (root, runId, summary) -> {
            boolean gatePublished = summary.get("orca_gate") instanceof Map<?, ?> gate
                    && Boolean.TRUE.equals(gate.get("published"));
            if (hasFlag(args, "--no-decision-page") || !gatePublished) {
                return fallback.await(root, runId, summary);
            }
            return awaitDecision(root, runId, args, minutes, env,
                    url -> new OrcaClient(new ProcessRunner()).invoke(root, Duration.ofSeconds(20),
                            List.of("tab", "create", "--worktree", "path:" + root, "--url", url)).ok());
        };
    }

    /**
     * Hand a passing run's contract gaps to the planner, and continue on the amended terms.
     *
     * The planner is given the contract and the readers' `contract_gap` findings and may only
     * add acceptance; the plan reviewer reads the amendment and may send it back once. When it
     * is written, the pending decision is answered `retry` by Warden itself, naming the gaps,
     * and the supervisor starts the continuation: the writer's product is kept and every
     * reading is taken again under the amended contract. When it is not, the decision stays
     * pending and the page shows the gaps to a person.
     */
    static Map<String, Object> amendContract(Path root, String runId, Map<String, Object> summary,
                                             String[] args, ApproveEnv env) {
        Map<String, Object> report = new LinkedHashMap<>();
        dev.warden.run.Progress narration = dev.warden.run.Progress.tee(narration(args),
                dev.warden.run.Progress.toFile(narrationFile(root, runId)));
        try {
            HumanDecision pending = new ApprovalStore(root).read(runId);
            if (pending.state() != HumanDecision.State.PENDING) {
                report.put("contract_amendment", skippedGateWait("already_decided"));
                return report;
            }
            String taskId = String.valueOf(summary.get("task_id"));
            Path taskFile = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
            String before = java.nio.file.Files.readString(taskFile);
            Path projectFile = root.resolve(".warden/project.yaml");
            var project = dev.warden.config.ProjectConfig.parse(
                    java.nio.file.Files.readString(projectFile), projectFile.toString());
            var spec = dev.warden.config.TaskSpec.parse(before, taskFile.toString());
            List<String> scopes = spec.scope().entries();
            List<Map<String, Object>> gaps = new java.util.ArrayList<>();
            List<Object> ids = new java.util.ArrayList<>();
            for (Map<String, Object> finding : dev.warden.dashboard.DecisionPage.openFindings(summary)) {
                if (!"contract_gap".equals(finding.get("category"))) continue;
                gaps.add(finding);
                ids.add(finding.get("id"));
            }
            narration.blank();
            narration.line("prep  the readers found the acceptance too weak (" + ids
                    + "); the planner amends it, the plan reviewer reads the amendment");
            // The chain's ceilings bound every call the amendment makes, not only the decision
            // to route there: read before the reservation below, which it would count as spent.
            RoleRunner.DispatchGate chainGate = TaskLoop.amendmentGate(root, runId,
                    spec.resolve(project, taskFile.toString()).budget(),
                    () -> env.clock.nowMillis() * 1_000_000L);
            // The chain pays for this, so the chain is told before the first call: the receipt
            // reserves the most an amendment can spend, and a crash leaves that reservation
            // standing rather than an amendment nobody counted.
            String amendRunId = runId + "-amend";
            Path receipt = root.resolve(".warden/runs").resolve(runId).resolve(TaskLoop.AMENDMENT_RECEIPT);
            long started = env.clock.nowMillis();
            Map<String, Object> reserved = new LinkedHashMap<>();
            reserved.put("run_id", amendRunId);
            reserved.put("state", "reserved");
            reserved.put("role_runs", (long) Preparation.mostAmendmentCalls(env.user()));
            reserved.put("cost_usd", 0.0);
            reserved.put("unpriced_calls", (long) Preparation.mostAmendmentCalls(env.user()));
            reserved.put("gaps", ids);
            java.nio.file.Files.writeString(receipt, Json.write(reserved));
            Preparation.Outcome amended = new Preparation(new ProcessRunner(), narration)
                    .amending(new Preparation.Amendment(before, gaps))
                    .within(chainGate)
                    .run(root, project, env.user(), taskId, amendRunId, spec.goal(),
                            scopes.size() == 1 ? scopes.get(0) : String.valueOf(scopes),
                            spec.risk(), dev.warden.config.PlannerDraft.Access.DO_DEFAULT, false);
            Map<String, Object> settled = new LinkedHashMap<>();
            settled.put("run_id", amendRunId);
            settled.put("state", "settled");
            settled.put("ok", amended.ok());
            settled.put("code", amended.code());
            settled.put("role_runs", (long) amended.roleRuns());
            settled.put("cost_usd", amended.costUsd());
            settled.put("unpriced_calls", (long) amended.unpriced());
            settled.put("elapsed_seconds", Math.max(0L, (env.clock.nowMillis() - started) / 1000L));
            settled.put("gaps", ids);
            java.nio.file.Files.writeString(receipt, Json.write(settled));
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("ok", amended.ok());
            outcome.put("code", amended.code());
            if (amended.message() != null) outcome.put("message", amended.message());
            outcome.put("gaps", ids);
            outcome.put("role_runs", (long) amended.roleRuns());
            outcome.put("cost_usd", amended.costUsd());
            report.put("contract_amendment", outcome);
            if (!amended.ok()) {
                narration.line("      the amendment did not go through (" + amended.code()
                        + "); the gaps go to a person with the contract as it was");
                return report;
            }
            List<String> approve = new java.util.ArrayList<>(List.of("approve", runId,
                    "--decision", "retry", "--note", "acceptance amended by the planner for " + ids,
                    "--actor", "warden:contract-amendment", "--no-start"));
            if (hasFlag(args, "--no-workspace-status")) approve.add("--no-workspace-status");
            ApproveOutcome retried = approveDecision(root, approve.toArray(String[]::new), env);
            outcome.put("decision", retried.report());
            narration.line(retried.ok()
                    ? "      contract amended; continuing with the writer's product and every reading taken again"
                    : "      contract amended, but the continuation could not be recorded: "
                            + retried.report().get("message"));
        } catch (Exception failed) {
            report.put("contract_amendment", Map.of("ok", false, "code", "amendment_failed",
                    "message", String.valueOf(failed.getMessage())));
            narration.line("      the amendment failed (" + failed.getMessage() + "); the gaps go to a person");
        }
        return report;
    }

    /** Opens a URL where the operator is; true when it was shown. */
    @FunctionalInterface
    interface PageOpener {
        boolean open(String url) throws Exception;
    }

    static Map<String, Object> awaitDecision(Path root, String runId, String[] args, int minutes,
                                             ApproveEnv env, PageOpener opener) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision pending;
        try {
            pending = store.read(runId);
        } catch (Exception none) {
            result.put("decision_page", skippedGateWait("no_pending_decision"));
            return result;
        }
        if (pending.state() != HumanDecision.State.PENDING) {
            result.put("decision_page", skippedGateWait("already_decided"));
            return result;
        }
        List<String> passThrough = new java.util.ArrayList<>();
        if (hasFlag(args, "--no-workspace-status")) passThrough.add("--no-workspace-status");
        dev.warden.run.Progress narration = dev.warden.run.Progress.tee(narration(args),
                dev.warden.run.Progress.toFile(narrationFile(root, runId)));
        try (var preview = new dev.warden.gate.CandidatePreview(root, pending.taskId(),
                narrationFile(root, runId).resolveSibling("decision-preview.log"), opener::open);
             var page = new dev.warden.dashboard.DecisionPage(root, runId, (choice, note, expected) -> {
            List<String> approve = new java.util.ArrayList<>(List.of("approve", runId,
                    "--decision", choice, "--note", note, "--expected-updated-at", expected,
                    "--actor", "orca-decision-page", "--no-start"));
            approve.addAll(passThrough);
            return approveDecision(root, approve.toArray(String[]::new), env).report();
        }, preview::open)) {
            page.start();
            boolean shown;
            try {
                shown = opener.open(page.url());
            } catch (Exception failed) {
                shown = false;
            }
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("url", page.url());
            described.put("orca_tab_opened", shown);
            result.put("decision_page", described);
            narration.line(shown
                    ? "      decide in Orca: the \"Warden · решение\" tab in this worktree has a button "
                            + "for each option (or open " + page.url() + ")"
                    : "      decide here: " + page.url());

            long deadline = env.clock.nowMillis() + Math.max(1, minutes) * 60_000L;
            // The first read is at once. A "last read" sentinel of Long.MIN_VALUE overflowed
            // `clock - last` to a negative number, so the gate was never read at all.
            long nextGateRead = env.clock.nowMillis();
            while (true) {
                HumanDecision now = store.read(runId);
                if (now.state() != HumanDecision.State.PENDING) {
                    described.put("decided", now.decision());
                    described.put("actor", now.actor());
                    break;
                }
                long clock = env.clock.nowMillis();
                // The Orca gate is still an answer, from a phone for instance. Read once per
                // ten seconds; a pending or unreadable gate is simply not an answer yet.
                if (clock >= nextGateRead) {
                    nextGateRead = clock + 10_000L;
                    List<String> fromOrca = new java.util.ArrayList<>(
                            List.of("approve", runId, "--from-orca", "--no-start"));
                    fromOrca.addAll(passThrough);
                    ApproveOutcome imported = approveDecision(root, fromOrca.toArray(String[]::new), env);
                    if (imported.ok()) {
                        result.put("gate_import", imported.report());
                        continue;
                    }
                }
                if (clock >= deadline) {
                    described.put("timed_out", true);
                    narration.line("      the decision page closed after " + minutes + " min without an "
                            + "answer; answer with warden approve " + runId + " --decision <option>");
                    break;
                }
                env.sleeper.sleep(2_000L);
            }
            // The page's own POST is answered after the store records it; give that response a
            // moment to reach the tab before the server goes away.
            env.sleeper.sleep(1_500L);
        }
        return result;
    }

    static Map<String, Object> superviseGates(Path root, String runId, Map<String, Object> summary,
                                             String[] args, ApproveEnv env,
                                             ContinuationStarter starter, DecisionWaiter waiter)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> chain = new java.util.ArrayList<>();
        for (int count = 0; count < 100; count++) {
            if (summary.get("decision_kind") == null) break;
            // A passing run whose readers found the acceptance too weak is the planner's to
            // answer first. When the amendment goes through it records a retry, and the wait
            // below finds the decision already made; when it does not, a person decides.
            if (TaskLoop.CONTRACT_AMENDMENT.equals(summary.get("reason"))) {
                chain.add(amendContract(root, runId, summary, args, env));
            }
            Map<String, Object> waited = waiter.await(root, runId, summary);
            chain.add(waited);
            HumanDecision resolved;
            try {
                resolved = new ApprovalStore(root).read(runId);
            } catch (Exception unreadable) {
                break;
            }
            if (resolved.state() != HumanDecision.State.RESOLVED) break;
            // A retry answered on the page is the operator saying "go again": leaving it to a
            // command typed at the machine made the answer from Orca half an answer.
            if (!continues(resolved)) break;
            Map<String, Object> next = starter.start(root, resolved, args, env);
            chain.add(next);
            if (!Boolean.TRUE.equals(next.get("started"))) break;
            result.put("continued_run_id", next.get("run_id"));
            result.put("continued_ok", next.get("ok"));
            if (!(next.get("summary_report") instanceof Map<?, ?> raw)) break;
            summary = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) summary.put(String.valueOf(entry.getKey()), entry.getValue());
            runId = String.valueOf(next.get("run_id"));
        }
        if (!chain.isEmpty()) result.put("gate_chain", chain);
        return result;
    }

    private static Map<String, Object> skippedGateWait(String reason) {
        Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("skipped", true);
        skip.put("reason", reason);
        skip.put("detail", "the wait imports one Orca gate answer and never starts a run");
        return skip;
    }

    /**
     * Carry an accepted candidate to a commit, a branch and a request. Merges nothing: the
     * request is a request, and whoever merges it is a person looking at it.
     */
    private static int land(String[] args) throws Exception {
        dev.warden.run.LandCommand.Outcome outcome =
                new dev.warden.run.LandCommand(new ProcessRunner())
                        .run(dev.warden.run.LandCommand.parse(args));
        System.out.println(Json.write(outcome.report()));
        return outcome.ok() ? 0 : 1;
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

    /** {@code 1..1440} minutes, or null when the flag is absent. */
    static Integer optionalMinutes(String[] args, String flag) {
        String raw = option(args, flag, null);
        if (raw == null) return null;
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(flag + " must be an integer 1..1440");
        }
        if (minutes < 1 || minutes > 1440) {
            throw new IllegalArgumentException(flag + " must be 1..1440 minutes");
        }
        return minutes;
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
                  warden dashboard [--project DIR] [--port N] [--open-orca]
                                               serve a local read-only role/settings view;
                                               stays running until Ctrl+C, launches no agents
                  warden profiles              which profiles load, which are eligible, and why not
                  warden profiles --verify N   run profile N's own probe; stamps verified_on
                                           when it passes, so swapping a vendor is an edit and
                                           one command. Read the transcript it keeps anyway
                  warden roster [--text]       which profile fills which role, the workflow,
                                               and any dangling policy references
                  warden roster set <role> --profiles a,b[,c] [--strategy first|rotate]
                                           rewrite that role's profiles (and strategy) in
                                           policy.yaml; comments and every other byte stay.
                                           Copies the file to *.before-roster-<timestamp>
                                           first. --allow-missing permits a name with no
                                           profiles/<name>.yaml yet
                  warden roster model <name> --model X [--effort Y] [--keep-verified]
                                           rewrite model/effort in profiles/<name>.yaml the
                                           same way. Drops verified_on (the stamp was for
                                           the old model) unless --keep-verified; prints
                                           verify_with so the probe is re-run
                  warden init [--base-ref REF] create a conservative project starter config
                  warden pilot prepare --spec FILE --output DIR
                                           write a self-contained offline pilot bundle from
                                           an explicit JSON spec: target project/task YAML,
                                           isolated config home with two selected profiles,
                                           observation sheet and operator runbook. Never
                                           overwrites DIR, never edits the target checkout
                                           or global home, never runs checks or vendors.
                                           Does not close all of P2 or implement P3/P4.
                  warden validate <task>       validate and resolve the project task contract
                  warden gates <task>          preflight and machine gates only; spends nothing
                  warden visual-qa <task>      launch preview, screenshot, assert control visibility
                  warden role <role> <task>    run one role; add --dry-run to spend nothing
                  warden do \"<goal>\" [--project DIR] [--scope NAME] [--goal-file FILE]
                                           the whole workflow; stops at the human gate
                                           --isolation git|orca picks what makes the worktree;
                                           without it, Orca when Orca is running and git
                                           otherwise. --in-place is the one way to say "edit
                                           the tree I am standing in"
                                           --init-repo for a directory that is not a repo yet
                                           --draft-only stops after writing the contract, so
                                           its browser scenarios can be written before any
                                           vendor is paid to satisfy them
                                           --prepare off|auto|always (default auto) dispatches
                                           a read-only planner to draft the contract; Warden
                                           validates what it returns. auto only when the task
                                           has no contract yet and the policy names a planner
                                           (a plan_reviewer then argues it once). always
                                           --draft-only spends the planner and never the
                                           implementer
                  warden run <task>            the bounded loop; stops at the human gate
                                               --use <stage>=<profile>  pin a profile for one
                                               stage this run (review-second=claude-review).
                                               Filtered by the roster's independence rule
                                               like any other choice
                                               --effort <stage>=<level>  effort for that
                                               stage, refused when the runner cannot deliver
                                               it rather than only recorded
                                               --host <stage>=orca  run that stage on the
                                               runner: orca profile you declared for that
                                               role and vendor, so Agent Dashboard shows
                                               WORKING. It never rewrites a direct profile
                                               into an Orca one: worker-start forwards agent,
                                               model and effort only, so grants would be lost
                                               and the direct stamp would be carried onto a
                                               channel it never probed
                                               None of these touch ~/.warden. Task YAML
                                               `use:` is the same overlay; flags win, and
                                               every one is checked against the roster before
                                               the first paid call. --dry-run prints the
                                               resulting cast and spends nothing.
                                               --no-orca-gate does not mirror the pending
                                               decision into Orca as a decision gate
                                               --wait-for-gate N  after the loop, wait up to
                                               N minutes (1..1440, default 60) for the
                                               answer. Inside an Orca worktree the decision
                                               opens as a "Warden · решение" browser tab
                                               with a button per option; the page, warden
                                               approve and the Orca gate all count, and a
                                               retry, advance, switch or apply answered
                                               there continues the loop. Outside Orca it
                                               only imports a published gate
                                               --no-decision-page waits on the Orca gate only
                                               --prepare off|auto|always records the mode
                                               `warden do` had in force, so a skipped auto
                                               is not written as off. Does not dispatch a
                                               planner; joining a prepared reservation still
                                               counts that spend.
                                               --continue <run-id> carries a recorded decision
                                               from that run into this one: an authorised
                                               `switch`, a rejection's reason, or a `retry`
                                               of a failure that was never about the work,
                                               which keeps the verdicts already reached on
                                               this exact tree
                  warden ledger                aggregate local evidence and experiment dimensions
                                               --global reads the home corpus from outside a
                                               repository, filtered by recorded project identity
                                               (--project-id ID, or the identity already recorded
                                               for the current project). Omitting both, from
                                               outside a project, reports measurements whose
                                               project identity is unknown (legacy imports). A
                                               corrupt line, unsupported schema version or
                                               incomplete import marks the report incomplete
                                               rather than failing it.
                                               --import PATH copies selected local runs or
                                               archives through the same allowlist; re-importing
                                               a copy does not change totals. Legacy rows without
                                               event_id use a provenance key; an ambiguous match
                                               is flagged, not merged. Contracts, units, lineage
                                               and cost the source never had stay unknown.
                                               --compare groups local task-run summaries by
                                               prepare, risk, workflow and roster, folding a
                                               continued chain into one unit attributed to its
                                               last run. --baseline-file PATH sits manually
                                               observed (non-Warden) tasks beside them.
                                               --text prints a compact table. Refused with
                                               --import. A stopped task is not a success.
                  warden report <run-id> [--text]
                                               one run joined: stages, vendors, cost, tokens,
                                               screenshots, changed files, human decision
                  warden status [run-id]       show pending/resolved human decisions
                                               --worktrees lists pending decisions across
                                               every worktree of this repository
                  warden approve <run-id> --decision <choice>
                                               record a decision; never lands changes.
                                               Must be run from the worktree the run lives in.
                                               Failure options: retry, abort, advance.
                                               advance closes this run and starts the next
                                               id of the same task (writers carry; verdicts
                                               do not). --no-start records it and prints the
                                               command instead.
                  warden decide <run-id>       open the decision as a "Warden · решение" tab in this
                               worktree's Orca browser, with a button per option, and
                               wait for the answer (--wait-minutes N, default 60;
                               --no-open prints the URL instead). The same checks as
                               approve; retry/advance/switch/apply continue the loop
  warden approve <run-id> --from-orca
                                               take the decision from the Orca gate the run
                                               published, so it can be answered from another
                                               machine or a phone. The gate is a doorbell:
                                               the answer must be one of Warden's own options,
                                               the pending decision must not have moved, and an
                                               acceptance still needs the same fingerprint.
                                               --wait-minutes N  poll up to N minutes (1..1440)
                                               while the gate is pending or unreadable, with
                                               5s then x1.5 capped at 10s backoff. Imports
                                               one answer and exits; never starts a run. No
                                               answer is never accept or reject
                  warden land <run-id>         plan the commit, branch and request for an
                                               accepted run; --commit/--push/--pull-request
                                               carry it out. Merges nothing

                `do` and `run` narrate the stages on stderr while they work — which role is
                dispatched, to which vendor, what it cost and where it went next. stdout stays
                one JSON object. `--quiet` turns the narration off.

                Every run also writes that account to .warden/runs/<run-id>/narration.log, so a
                run started from a script, a scheduler or a terminal you have since closed
                still has one. It is not evidence; the ledger is.

                Inside an Orca worktree the same run writes its state to that worktree's card —
                stage, cost, and the approve command when it stops for a person — so a run
                waiting on you is visible from the board, including on a phone.
                `--no-workspace-status` turns that off.

                `--watch` additionally asks the board to open a window following that log, so
                the run can be watched from the board while still being launched from anywhere.
                Warden is not run *by* the board: closing the window does not touch the loop.

                The command to use is `warden do`. Everything else is a piece of that loop.
                Vendor configuration lives in ~/.warden/, project configuration in .warden/.
                Warden never merges. See spec/SPEC.md and docs/ARCHITECTURE.md.""");
    }
}
