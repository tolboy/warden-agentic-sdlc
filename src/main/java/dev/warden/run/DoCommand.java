package dev.warden.run;

import dev.warden.config.ConfigLoader;
import dev.warden.config.PlannerDraft;
import dev.warden.config.ProjectConfig;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.TaskDraft;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.execution.Isolation;
import dev.warden.execution.orca.OrcaIsolation;
import dev.warden.git.GitWorktreeIsolation;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one command an operator should have to run:
 *
 *   warden do "what you want" --project C:/path/to/repo
 *
 * It isolates, writes a task the linter accepts, runs the bounded loop, and stops at the human
 * gate. It never lands.
 *
 * Isolation is a `git worktree` unless Orca is running, in which case Orca makes it — and
 * `--isolation git|orca` settles it either way. Both give the same guarantee: the loop does not
 * write to the branch the operator is looking at. `--in-place` is the one way to say otherwise.
 *
 * Scope is not guessed. If the project has one named scope, that is used; if it has several,
 * {@code --scope} is required. A guessed blast radius is how a typo fails open.
 */
public final class DoCommand {

    public record Options(Path project, String goal, String scope, String risk, String taskId,
                          String runId, String baseRef, boolean inPlace, boolean dryRun,
                          boolean conductor, boolean autoRejectGates, boolean initRepo,
                          boolean draftOnly, String isolation, String prepare) {
        public Options {
            prepare = Preparation.parseMode(prepare);
        }

        public Options(Path project, String goal, String scope, String risk, String taskId,
                       String runId, String baseRef, boolean inPlace, boolean dryRun,
                       boolean conductor, boolean autoRejectGates, boolean initRepo,
                       boolean draftOnly, String isolation) {
            this(project, goal, scope, risk, taskId, runId, baseRef, inPlace, dryRun,
                    conductor, autoRejectGates, initRepo, draftOnly, isolation, "off");
        }

        public Options(Path project, String goal, String scope, String risk, String taskId,
                       String runId, String baseRef, boolean inPlace, boolean dryRun,
                       boolean conductor, boolean autoRejectGates, boolean initRepo) {
            this(project, goal, scope, risk, taskId, runId, baseRef, inPlace, dryRun,
                    conductor, autoRejectGates, initRepo, false, null);
        }

        public Options(Path project, String goal, String scope, String risk, String taskId,
                       String runId, String baseRef, boolean inPlace, boolean dryRun) {
            this(project, goal, scope, risk, taskId, runId, baseRef, inPlace, dryRun,
                    false, false, false);
        }

        public Options(Path project, String goal, String scope, String risk, String taskId,
                       String runId, String baseRef, boolean inPlace, boolean dryRun,
                       boolean conductor, boolean autoRejectGates) {
            this(project, goal, scope, risk, taskId, runId, baseRef, inPlace, dryRun,
                    conductor, autoRejectGates, false);
        }
    }

    public record Outcome(boolean ok, String code, Path project, Path worktree, String taskId,
                          Map<String, Object> report) {}

    private final ProcessRunner processes;
    private final Progress progress;
    private final Workspace.Source board;
    private final boolean watch;
    private final boolean orcaGate;

    public DoCommand(ProcessRunner processes) { this(processes, Progress.SILENT); }

    public DoCommand(ProcessRunner processes, Progress progress) {
        this(processes, progress, Workspace.Source.NONE, false);
    }

    public DoCommand(ProcessRunner processes, Progress progress, Workspace.Source board,
                     boolean watch) {
        this(processes, progress, board, watch, false);
    }

    public DoCommand(ProcessRunner processes, Progress progress, Workspace.Source board,
                     boolean watch, boolean orcaGate) {
        this.processes = processes;
        this.progress = progress;
        this.board = board;
        this.watch = watch;
        this.orcaGate = orcaGate;
    }

    /** Publish the run's pending decision to Orca as a gate, so it can be answered elsewhere. */
    public DoCommand withOrcaGate(boolean publish) {
        return new DoCommand(processes, progress, board, watch, publish);
    }

    /**
     * Report to the card of whichever worktree this run ends up in, and — when asked — open a
     * live view of it there. Both are resolved rather than held, because `do` creates that
     * worktree partway through and the operator's own checkout is the wrong one to write on.
     */
    public DoCommand withWorkspace(Workspace.Source source, boolean watch) {
        return new DoCommand(processes, progress, source, watch, orcaGate);
    }

    /**
     * The JVM decodes the native command line with {@code sun.jnu.encoding}, which on a
     * Windows host with a non-UTF-8 ANSI codepage is Cp1252. Every Cyrillic character in an
     * argument becomes {@code ?} before {@code main} is entered — measured here, and not
     * fixable from inside: {@code -Dsun.jnu.encoding=UTF-8}, {@code JDK_JAVA_OPTIONS} and
     * {@code chcp 65001} all leave it at Cp1252.
     *
     * So a goal that arrived damaged is refused rather than written into a contract, and
     * {@code --goal-file} exists as the channel that always works.
     */
    public static boolean argumentsCanCarryNonAscii() {
        String jnu = System.getProperty("sun.jnu.encoding", "");
        return jnu.toLowerCase(Locale.ROOT).replace("-", "").contains("utf8");
    }

    static boolean looksMangled(String goal) {
        return !argumentsCanCarryNonAscii() && goal.contains("??");
    }

    public static Options parse(String[] args) {
        String goalFile = option(args, "--goal-file", null);
        if (goalFile != null) {
            try {
                String fromFile = Files.readString(Path.of(goalFile), StandardCharsets.UTF_8).strip();
                if (fromFile.isEmpty()) {
                    throw new IllegalArgumentException("--goal-file " + goalFile + " is empty");
                }
                return withGoal(args, fromFile);
            } catch (java.io.IOException unreadable) {
                throw new IllegalArgumentException("--goal-file " + goalFile + " could not be read: "
                        + unreadable.getMessage());
            }
        }
        String goal = option(args, "--goal", null);
        List<String> positional = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            if (arg.startsWith("--")) {
                if (!arg.equals("--in-place") && !arg.equals("--no-worktree")
                        && !arg.equals("--dry-run") && !arg.equals("--conductor")
                        && !arg.equals("--auto-reject-gates") && !arg.equals("--init-repo")
                        && !arg.equals("--draft-only") && !arg.equals("--quiet")) {
                    index++;
                }
                continue;
            }
            positional.add(arg);
        }
        if (goal == null && !positional.isEmpty()) goal = String.join(" ", positional);
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("do requires a goal, for example: "
                    + "warden do --project C:/repo --scope code \"Add a Settings button\"");
        }
        return withGoal(args, goal.strip());
    }

    private static Options withGoal(String[] args, String goal) {
        Path project = Path.of(option(args, "--project", "."));
        return new Options(project.toAbsolutePath().normalize(), goal,
                option(args, "--scope", null), option(args, "--risk", null),
                option(args, "--task-id", null), option(args, "--run-id", null),
                option(args, "--base-ref", "HEAD"),
                hasFlag(args, "--in-place") || hasFlag(args, "--no-worktree"),
                hasFlag(args, "--dry-run"), hasFlag(args, "--conductor"),
                hasFlag(args, "--auto-reject-gates"), hasFlag(args, "--init-repo"),
                hasFlag(args, "--draft-only"), option(args, "--isolation", null),
                option(args, "--prepare", "off"));
    }

    public Outcome run(Options options, UserConfig user) throws Exception {
        Path requested = options.project();
        if (looksMangled(options.goal())) {
            return fail("goal_mangled_by_console_encoding", requested, requested, null,
                    "the goal arrived as \"" + options.goal() + "\". This JVM decodes command-line "
                            + "arguments with sun.jnu.encoding=" + System.getProperty("sun.jnu.encoding")
                            + ", which cannot represent the characters you typed, and no JVM flag "
                            + "changes that. Write the goal to a UTF-8 file and pass "
                            + "--goal-file <path>, or enable the Windows setting \"Use Unicode UTF-8 "
                            + "for worldwide language support\". Refusing rather than writing a "
                            + "contract full of question marks.");
        }
        if (!Files.isDirectory(requested)) {
            return fail("project_not_found", requested, requested, null, "not a directory: " + requested);
        }
        if (!Files.exists(requested.resolve(".git"))) {
            if (!options.initRepo()) {
                return fail("not_a_git_repository", requested, requested, null,
                        "warden do requires a Git repository so isolation and blast-radius have a "
                                + "merge-base. For a new project, pass --init-repo: warden will run "
                                + "git init and commit whatever is already here as the baseline");
            }
            try {
                initializeRepository(requested);
            } catch (Exception cannotInitialize) {
                return fail("git_init_failed", requested, requested, null,
                        "could not create a repository in " + requested + ": "
                                + cannotInitialize.getMessage());
            }
        }

        String taskId = options.taskId() != null ? options.taskId() : TaskDraft.slug(options.goal());
        String runId = options.runId() != null ? options.runId()
                : "do-" + taskId + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);

        Isolation.Placement placement;
        String isolateFrom = options.baseRef();
        // Recorded, because with an automatic default "which program made this worktree" stops
        // being something the operator can infer from the command they typed.
        String backend = options.inPlace() ? "none" : "unknown";
        if (options.inPlace()) {
            placement = Isolation.Placement.inPlace(requested);
        } else {
            try {
                isolateFrom = branchToIsolateFrom(requested, options.baseRef());
                Isolation isolation = isolationFor(options, requested);
                backend = isolation instanceof GitWorktreeIsolation ? "git" : "orca";
                placement = isolation.isolate(requested, "w-" + taskId, isolateFrom);
            } catch (Isolation.IsolationException isolation) {
                return fail(isolation.code(), requested, requested, taskId, isolation.getMessage());
            }
        }
        Path root = placement.path();

        if (!Files.isRegularFile(root.resolve(".warden/project.yaml"))) {
            try {
                new ProjectInitializer().initialize(root, options.baseRef());
            } catch (Exception cannotInfer) {
                // `init` refuses to guess checks for a build system it does not recognise.
                // That refusal is right; escaping as an unstructured warden_error was not.
                return fail("project_not_initialized", requested, root, taskId,
                        cannotInfer.getMessage() + " — create .warden/project.yaml with this "
                                + "project's real check commands, then run warden do again");
            }
        }

        ConfigLoader loader = new ConfigLoader();
        Path projectFile = root.resolve(".warden/project.yaml");
        var project = dev.warden.config.ProjectConfig.parse(Files.readString(projectFile), projectFile.toString());
        String scope = options.scope();
        if (scope == null) {
            if (project.scopes().size() == 1) scope = project.scopes().keySet().iterator().next();
            else {
                return fail("scope_required", requested, root, taskId,
                        "project has multiple scopes " + project.scopes().keySet()
                                + "; pass --scope <name> rather than guessing a blast radius");
            }
        }
        if (!project.scopes().containsKey(scope)) {
            return fail("scope_unknown", requested, root, taskId,
                    "scope '" + scope + "' is not defined. Available: " + project.scopes().keySet());
        }
        String risk = options.risk() != null ? options.risk() : project.defaultRisk();
        if (!ProjectConfig.RISK_LEVELS.contains(risk)) {
            return fail("risk_unknown", requested, root, taskId, "risk must be one of " + ProjectConfig.RISK_LEVELS);
        }

        // A project with no check command has only its browser scenarios to define done.
        List<String> defaultCommands = project.defaultChecks() == null ? List.of()
                : project.checks().getOrDefault(project.defaultChecks(), List.of());
        Path taskFile = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
        boolean contractExists = Files.isRegularFile(taskFile);
        boolean dispatchPlanner = Preparation.shouldDispatch(options.prepare(), contractExists);

        TaskDraft.Written drafted;
        // The mode that was in force, even when auto skipped the planner. TaskLoop writes
        // this into the run summary; defaulting to NONE (off) made --prepare auto look like
        // off after the fact.
        TaskLoop.Preparation preparation = new TaskLoop.Preparation(
                options.prepare(), false, 0, 0, 0, false);
        CallPlan preparationPlan = null;
        long preparationCap = 1;
        if (dispatchPlanner) {
            // Setup first so the planner reads the tree a later implementer would, not an
            // empty worktree whose checks cannot run. The --prepare off path below is left
            // in the historical order so TaskDraft's bytes do not move.
            if ("git".equals(backend) && "created".equals(placement.reason())) {
                String failedSetup = runSetup(project.setup(), root);
                if (failedSetup != null) {
                    return fail("setup_failed", requested, root, taskId, failedSetup);
                }
            }
            if (user.policy() != null) {
                // Reserved before the vendor is paid, not reconstructed afterwards. One
                // coherent plan: --draft-only is the bootstrap (cap of one, the planner
                // alone), because no workflow stage will run. A run that continues into
                // the loop uses the same skip predicate and ceiling TaskLoop will write
                // a moment later. Mixing the bootstrap's numerator of one with the
                // undeclared chain as the denominator is how run.json used to claim
                // sufficient_for_success: false on a run that then finished.
                if (options.draftOnly()) {
                    preparationPlan = new CallPlan(user.policy().workflow(),
                            stage -> true, List.of("planner"));
                    preparationCap = 1;
                } else {
                    TaskSpec.ResolvedTask measured = measureForReservation(root, project,
                            taskId, options.goal(), scope, risk, contractExists);
                    preparationPlan = TaskLoop.planFor(user.policy().workflow(), user,
                            measured, List.of("planner"));
                    preparationCap = measured.budget().maxRoleRuns();
                }
            }
            try {
                Map<String, Object> reservation = new LinkedHashMap<>();
                reservation.put("prepare", options.prepare());
                if (preparationPlan != null) {
                    reservation.put("budget_plan", preparationPlan.toMap(
                            preparationCap, user.policy().repairReserve()));
                }
                new EvidenceLedger(root, runId, user.home())
                        .reserveWorkflowRun(taskId, reservation);
            } catch (EvidenceLedger.RunExistsException duplicate) {
                return fail("run_id_exists", requested, root, taskId, duplicate.getMessage());
            }
            Preparation.Outcome prepared;
            prepared = new Preparation(processes, progress).run(root, project, user, taskId,
                    runId, options.goal(), scope, risk, PlannerDraft.Access.DO_DEFAULT,
                    options.dryRun());
            if (!prepared.ok()) {
                Map<String, Object> report = fail(prepared.code(), requested, root, taskId,
                        prepared.message() == null ? prepared.code() : prepared.message()).report();
                report.put("prepare", options.prepare());
                report.put("run_id", runId);
                attachCorpusVisibility(report, root, runId, user.home());
                return new Outcome(false, prepared.code(), requested, root, taskId, report);
            }
            if (options.dryRun() && prepared.written() == null && !contractExists) {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("ok", true);
                report.put("code", "dry_run");
                report.put("prepare", options.prepare());
                report.put("goal", options.goal());
                report.put("task_id", taskId);
                report.put("run_id", runId);
                report.put("project", String.valueOf(requested));
                report.put("worktree", String.valueOf(root));
                report.put("isolated", placement.isolated());
                report.put("scope", scope);
                report.put("risk", risk);
                report.put("lands", false);
                attachCorpusVisibility(report, root, runId, user.home());
                return new Outcome(true, "dry_run", requested, root, taskId, report);
            }
            if (prepared.written() == null && contractExists) {
                drafted = new TaskDraft.Written(taskFile, taskId, true);
            } else if (prepared.written() == null) {
                Map<String, Object> report = fail("planner_draft_invalid", requested, root, taskId,
                        "planner produced no contract").report();
                report.put("run_id", runId);
                attachCorpusVisibility(report, root, runId, user.home());
                return new Outcome(false, "planner_draft_invalid", requested, root, taskId, report);
            } else {
                drafted = prepared.written();
            }
            preparation = new TaskLoop.Preparation(options.prepare(), true, prepared.roleRuns(),
                    prepared.costUsd(), prepared.unpriced(), true);
            // So a later `warden run` or the Conductor inner process can join this reservation
            // instead of refusing it as a duplicate. The planner wrote evidence here; the loop
            // has not started yet.
            new EvidenceLedger(root, runId, user.home())
                    .markPrepared(prepared.roleRuns(), prepared.costUsd(), prepared.unpriced());
        } else if ("auto".equals(options.prepare()) && contractExists) {
            // A ready contract is not rewritten, but it is still this invocation's contract.
            // TaskDraft.write compares the operator's goal for equality, which would refuse
            // the file a planner itself just wrote (it may append after that goal). Skip the
            // planner, refuse task_conflict when the existing file does not preserve the
            // supplied goal, scope or risk, and leave planner-added text in place.
            try {
                drafted = TaskDraft.requireSameIntent(taskFile, taskId, options.goal(), scope, risk);
            } catch (TaskDraft.TaskConflict conflict) {
                return fail("task_conflict", requested, root, taskId, conflict.getMessage());
            }
        } else {
            try {
                drafted = new TaskDraft().write(root, taskId, options.goal(), scope, risk,
                        defaultCommands.isEmpty());
            } catch (TaskDraft.TaskConflict conflict) {
                return fail("task_conflict", requested, root, taskId, conflict.getMessage());
            }
        }
        ConfigLoader.Loaded loaded = loader.load(root, taskId);

        // A compiled contract is the most consequential thing a run produces before it spends
        // anything, and until this was here the only trace of it on the board was a path in a
        // terminal nobody was necessarily watching. A planner turns one sentence into the
        // document every later verdict is measured against; the operator gets to see it.
        Workspace card = this.board.at(root);
        if (dispatchPlanner) {
            card.note("planner compiled " + taskId + " to .warden/tasks/" + taskId
                    + ".yaml; no writer has been dispatched");
            card.show(drafted.file(), "warden " + runId + " contract");
        }

        // A worktree Warden made is empty of everything the project needs to check itself. Orca
        // runs a repo's setup when Orca made the checkout; when git made it, this is the only
        // thing that will. Only on a fresh one: joining a worktree that already exists means
        // the setup already ran there, and running `npm install` again is minutes for nothing.
        if (!dispatchPlanner && "git".equals(backend) && "created".equals(placement.reason())) {
            String failedSetup = runSetup(loaded.project().setup(), root);
            if (failedSetup != null) {
                return fail("setup_failed", requested, root, taskId, failedSetup);
            }
        }

        if (options.draftOnly()) {
            // Nothing is running and the next move is a person's, which is the one question
            // this board answers.
            if (dispatchPlanner) card.state(Workspace.State.WAITING_FOR_HUMAN);
            // The contract is where a run's whole meaning lives, and the drafter writes it
            // from one sentence. Every real task so far needed its browser scenarios written
            // by hand before it was worth paying anybody to satisfy them — and there was no
            // way to stop here and do that: `do` went straight on to dispatch, and the only
            // way to get the draft without spending was --dry-run, which resolves the whole
            // chain and reads like a rehearsal rather than a checkpoint.
            //
            // `--prepare always --draft-only` still spends the planner call, then stops.
            // No implementer, no gates, no reviewer.
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("ok", true);
            report.put("code", "drafted");
            report.put("next_action", "review_the_contract");
            report.put("prepare", options.prepare());
            report.put("goal", options.goal());
            report.put("task_id", taskId);
            report.put("task_existed", drafted.existed());
            report.put("task_file", drafted.file().toString());
            if (dispatchPlanner) report.put("run_id", runId);
            report.put("project", String.valueOf(requested));
            report.put("worktree", String.valueOf(root));
            report.put("isolated", placement.isolated());
            report.put("scope", scope);
            report.put("risk", risk);
            report.put("lands", false);
            if (preparationPlan != null) {
                report.put("budget_plan", preparationPlan.toMap(
                        preparationCap, user.policy().repairReserve()));
            }
            if (dispatchPlanner) {
                attachCorpusVisibility(report, root, runId, user.home());
            }
            progress.blank();
            progress.line("draft " + drafted.file());
            progress.line("      read it, write the browser scenarios this task actually "
                    + "promises, then:");
            progress.line("      cd " + root);
            // A planner call lives on the reservation at this run id. Printing the
            // placeholder <id> — correct when --prepare off reserved nothing — sent
            // the operator into a new loop that never joined it, so the planner was
            // not counted and the paid reservation was orphaned.
            progress.line("      warden run " + taskId + " --run-id "
                    + (dispatchPlanner ? runId : "<id>"));
            progress.blank();
            return new Outcome(true, "drafted", requested, root, taskId, report);
        }
        if (!dispatchPlanner && "auto".equals(options.prepare())) {
            // The planner did not run, so nothing reserved the run id. Conductor's inner
            // `warden run` still has to record prepare=auto; without this marker it joins
            // nothing and writes Preparation.NONE's off.
            try {
                preparation = Preparation.recordSkipped(root, runId, taskId, options.prepare(),
                        user.home());
            } catch (EvidenceLedger.RunExistsException duplicate) {
                return fail("run_id_exists", requested, root, taskId, duplicate.getMessage());
            }
        }
        if (options.conductor()) {
            return runWithConductor(options, requested, root, placement, backend, drafted, loaded, taskId,
                    runId, scope, risk, isolateFrom);
        }
        Path narration = root.resolve(".warden/runs").resolve(runId).resolve("narration.log");
        if (watch && !options.dryRun()) card.watch(narration, runId);
        TaskLoop.Outcome loop;
        try {
            loop = new TaskLoop(processes)
                    .withProgress(Progress.tee(progress, Progress.toFile(narration)))
                    .withWorkspace(card)
                    .withOrcaGate(orcaGate && !options.dryRun())
                    .withPreparation(preparation)
                    .run(loaded, user, runId, options.dryRun());
        } catch (EvidenceLedger.RunExistsException duplicate) {
            return fail("run_id_exists", requested, root, taskId, duplicate.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", loop.ok());
        report.put("code", loop.reason());
        report.put("next_action", loop.nextAction());
        report.put("prepare", options.prepare());
        report.put("goal", options.goal());
        report.put("task_id", taskId);
        report.put("task_existed", drafted.existed());
        report.put("run_id", runId);
        report.put("project", String.valueOf(requested));
        report.put("worktree", String.valueOf(root));
        report.put("isolated", placement.isolated());
        report.put("isolation", placement.reason());
        report.put("isolation_backend", backend);
        report.put("worktree_start_ref", isolateFrom);
        report.put("diff_base_ref", loaded.resolved().baseRef());
        report.put("base_refs_differ", !options.baseRef().equals(loaded.resolved().baseRef()));
        report.put("scope", scope);
        report.put("risk", risk);
        report.put("dry_run", options.dryRun());
        report.put("summary", String.valueOf(loop.summary()));
        report.put("steps", loop.summaryReport().get("steps"));
        // Only when there is something to say. A dry run does not stop for these, so if the
        // preview does not name them the operator reads "ok" and then watches the real run
        // refuse to dispatch.
        for (String carried : List.of("preexisting_violations", "resolution", "would_stop")) {
            Object value = loop.summaryReport().get(carried);
            if (value != null) report.put(carried, value);
        }
        report.put("decision_path", loop.summaryReport().get("decision_path"));
        report.put("decision_state", loop.summaryReport().get("decision_state"));
        report.put("decision_options", loop.summaryReport().get("decision_options"));
        report.put("approve_with", loop.summaryReport().get("approve_with"));
        for (String key : List.of("corpus_status", "corpus_undelivered", "corpus_reason",
                "tree_safe_to_delete")) {
            Object value = loop.summaryReport().get(key);
            if (value != null) report.put(key, value);
        }
        report.put("lands", false);
        return new Outcome(loop.ok(), loop.reason(),
                requested, root, taskId, report);
    }

    private Outcome runWithConductor(Options options, Path requested, Path root,
                                     Isolation.Placement placement, String backend,
                                     TaskDraft.Written drafted,
                                     ConfigLoader.Loaded loaded, String taskId, String runId,
                                     String scope, String risk, String isolateFrom) throws Exception {
        if (options.dryRun()) {
            return fail("conductor_dry_run_unsupported", requested, root, taskId,
                    "use ordinary `warden do --dry-run`; Conductor exists to present a real human gate");
        }
        // Agent execution has its own task budget. This outer bound is the separate human-gate
        // TTL; Conductor checkpoints the workflow if the operator does not answer within a day.
        ConductorBridge.Outcome conductor = new ConductorBridge().run(root, taskId, runId,
                options.autoRejectGates(), Duration.ofHours(24), options.prepare());
        Path summaryFile = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        Map<String, Object> summary = Files.isRegularFile(summaryFile)
                ? dev.warden.json.Json.parseObject(Files.readString(summaryFile)) : Map.of();
        ApprovalStore decisions = new ApprovalStore(root);
        HumanDecision decision = decisions.find(runId).orElse(null);
        String choice = decision == null ? null : decision.decision();
        String code = switch (choice == null ? "" : choice) {
            case "accept" -> "human_accepted";
            case "reject" -> "human_rejected";
            case "abort" -> "human_aborted";
            case "retry" -> "retry_authorized";
            default -> conductor.timedOut() ? "human_gate_timeout" : "conductor_stopped";
        };
        boolean accepted = "accept".equals(choice) && conductor.ok();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", accepted);
        report.put("code", code);
        report.put("prepare", options.prepare());
        report.put("goal", options.goal());
        report.put("task_id", taskId);
        report.put("task_existed", drafted.existed());
        report.put("run_id", runId);
        report.put("project", String.valueOf(requested));
        report.put("worktree", String.valueOf(root));
        report.put("isolated", placement.isolated());
        report.put("isolation", placement.reason());
        report.put("isolation_backend", backend);
        report.put("worktree_start_ref", isolateFrom);
        report.put("diff_base_ref", loaded.resolved().baseRef());
        report.put("base_refs_differ", !options.baseRef().equals(loaded.resolved().baseRef()));
        report.put("scope", scope);
        report.put("risk", risk);
        report.put("conductor", true);
        report.put("conductor_workflow", String.valueOf(conductor.workflow()));
        report.put("conductor_exit_code", (long) conductor.exitCode());
        report.put("conductor_timed_out", conductor.timedOut());
        report.put("task_summary", summary);
        if (decision != null) report.put("decision", decision.toMap());
        report.put("decision_path", root.resolve(".warden/runs").resolve(runId)
                .resolve(dev.warden.approval.ApprovalStore.FILE_NAME).toString());
        report.put("lands", false);
        return new Outcome(accepted, code, requested, root, taskId, report);
    }

    /**
     * The branch a new worktree is cut from.
     *
     * `HEAD` is the default because it means "where I am", but it is a symbolic name, and a
     * worktree has to be created from a concrete branch. An earlier version translated HEAD
     * to `main` unconditionally, which silently cut from the wrong branch for anyone working
     * on master, develop or a feature branch — the isolation looked like it worked and the
     * agent started from a tree the operator had never seen.
     *
     * A detached HEAD is refused rather than guessed at: there is no branch to name, and
     * picking one would be inventing the baseline.
     */
    private String branchToIsolateFrom(Path project, String baseRef)
            throws Isolation.IsolationException {
        if (!"HEAD".equals(baseRef)) return baseRef;
        try {
            ProcessRunner.Result current = processes.run(
                    List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), project,
                    java.time.Duration.ofSeconds(30));
            String branch = current.ok() ? current.stdout().strip() : "";
            if (branch.isEmpty() || branch.equals("HEAD")) {
                throw new Isolation.IsolationException("detached_head",
                        "HEAD is detached in " + project + ", so there is no branch to cut a "
                                + "worktree from. Check out a branch, or pass --base-ref <branch>.");
            }
            return branch;
        } catch (Isolation.IsolationException already) {
            throw already;
        } catch (Exception notAskable) {
            throw new Isolation.IsolationException("detached_head",
                    "could not read the current branch of " + project + ": " + notAskable.getMessage());
        }
    }

    /**
     * The task facts a reservation can know before the planner is paid.
     *
     * An existing contract is measured as the loop will measure it. Without one, risk
     * comes from the invocation and visual_qa is treated as not required: naming a
     * stage the loop will skip is the lie this reservation used to tell. A stage the
     * planner may later request is added when the loop writes its own plan, once a
     * contract exists to measure. Omitted {@code budgets:} default to the same
     * numbers {@link TaskSpec} uses, which is what a planner-compiled contract gets.
     */
    private static TaskSpec.ResolvedTask measureForReservation(Path root, ProjectConfig project,
            String taskId, String goal, String scope, String risk, boolean contractExists) {
        if (contractExists) {
            try {
                return new ConfigLoader().load(root, taskId).resolved();
            } catch (Exception unreadable) {
                // Fall through: a file we cannot measure still has to reserve one
                // coherent plan, not a mix of the bootstrap cap and the full chain.
            }
        }
        return new TaskSpec.ResolvedTask(
                taskId,
                goal,
                List.of(),
                risk,
                project.baseRef(),
                List.copyOf(project.scopes().getOrDefault(scope, List.of())),
                List.of(),
                List.of(),
                new TaskSpec.Authority(true, false, false),
                new TaskSpec.VisualQa(false, List.of(), null, null),
                new TaskSpec.Budget(6, 20.0),
                project.defaultMaxFixAttempts(),
                project.defaultTimeoutMinutes());
    }

    /**
     * The project's own setup, in the checkout Warden just made.
     *
     * Narrated rather than silent: `npm install` is minutes, and a command that says nothing
     * for four of them looks hung. Failure stops the run before a vendor is paid — a checkout
     * whose setup failed will fail every gate afterwards, and for a reason no implementer put
     * there.
     *
     * @return the message to stop with, or null when every command succeeded
     */
    private String runSetup(List<String> commands, Path root) throws Exception {
        if (commands.isEmpty()) return null;
        progress.line("setup " + String.join(", ", commands));
        for (String command : commands) {
            ProcessRunner.Result result = processes.run(shell(command), root, Duration.ofMinutes(20));
            progress.line("      " + (result.ok() ? "ok" : "FAILED") + "  "
                    + Progress.elapsed(result.durationMillis()) + "  " + command);
            if (!result.ok()) {
                String tail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                return "setup command `" + command + "` failed in " + root + " with exit "
                        + result.exitCode() + ". Its output ended with: "
                        + (tail.length() <= 600 ? tail.strip()
                            : tail.substring(tail.length() - 600).strip());
            }
        }
        return null;
    }

    private static List<String> shell(String command) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    /**
     * Which backend makes the room, and why the default is neither of them by name.
     *
     * `auto` uses Orca when Orca is running and Git otherwise. Not a fallback in the apologetic
     * sense: both give the same guarantee, which is that the loop does not write to the branch
     * the operator is looking at, and the difference is what the operator gets to watch. An
     * operator with Orca open wants the worktree in their sidebar; one without it wants the
     * command to work, which before this it did not — the only route left was `--in-place`,
     * the one path this tool elsewhere calls unsafe.
     *
     * `--isolation orca` still fails closed when Orca is down. Asking for a specific backend is
     * a statement about what you want, and silently substituting the other one would answer a
     * question nobody asked.
     */
    private Isolation isolationFor(Options options, Path project) {
        String asked = options.isolation() == null ? "" : options.isolation().toLowerCase(Locale.ROOT);
        if (asked.equals("git")) return new GitWorktreeIsolation(processes);
        if (asked.equals("orca")) return new OrcaIsolation(processes);
        if (!asked.isEmpty()) {
            throw new IllegalArgumentException("--isolation must be git, orca or omitted for "
                    + "automatic; got '" + options.isolation() + "'");
        }
        boolean orcaUp = Boolean.TRUE.equals(
                new dev.warden.execution.orca.OrcaClient(processes).status(project).get("available"));
        return orcaUp ? new OrcaIsolation(processes) : new GitWorktreeIsolation(processes);
    }

    /**
     * A repository for a project that did not have one. Whatever is already in the directory
     * becomes the first commit, on purpose: without a baseline every pre-existing file would
     * read as something an agent produced, and the blast-radius check would be measuring the
     * operator's own work. `--init-repo` is opt-in for the same reason — creating a commit in
     * somebody's directory is not something to do because a path happened to lack `.git`.
     */
    private void initializeRepository(Path project) throws Exception {
        java.time.Duration limit = java.time.Duration.ofSeconds(60);
        for (List<String> command : List.of(
                List.of("git", "init", "-q", "-b", "main", "."),
                List.of("git", "add", "-A"))) {
            ProcessRunner.Result result = processes.run(command, project, limit);
            if (!result.ok()) throw new java.io.IOException(String.join(" ", command)
                    + " failed: " + result.stderr().strip());
        }
        // --allow-empty so a genuinely empty directory still gets the root commit that every
        // later merge-base, fingerprint and diff is taken against.
        ProcessRunner.Result committed = processes.run(List.of("git", "commit", "-q",
                "--allow-empty", "-m", "warden: baseline before automated work"), project, limit);
        if (!committed.ok()) {
            throw new java.io.IOException("git commit failed: " + committed.stderr().strip()
                    + " (set user.name and user.email, or run git init yourself)");
        }
    }

    /**
     * Planning-only completion still writes through the shared protocol. The local draft
     * can be ok while delivery is pending or error; the CLI report must say so.
     */
    private static void attachCorpusVisibility(Map<String, Object> report, Path root, String runId,
                                               Path home) {
        if (report == null || root == null || runId == null) return;
        try {
            new EvidenceLedger(root, runId, home).recordCorpusVisibility(report);
        } catch (Exception ignored) {
            report.putIfAbsent("corpus_status", "error");
            report.put("tree_safe_to_delete", false);
        }
    }

    private Outcome fail(String code, Path project, Path worktree, String taskId, String message) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("code", code);
        report.put("message", message);
        report.put("next_action", "human_escalation");
        report.put("lands", false);
        if (taskId != null) report.put("task_id", taskId);
        return new Outcome(false, code, project, worktree, taskId, report);
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String argument : args) if (argument.equals(name)) return true;
        return false;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) return args[index + 1];
        }
        return fallback;
    }
}
