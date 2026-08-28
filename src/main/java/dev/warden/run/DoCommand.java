package dev.warden.run;

import dev.warden.config.ConfigLoader;
import dev.warden.config.ProjectConfig;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.TaskDraft;
import dev.warden.config.UserConfig;
import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.execution.orca.OrcaIsolation;
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
 * It isolates (via Orca), writes a task the linter accepts, runs the bounded loop, and
 * stops at the human gate. It never lands.
 *
 * Scope is not guessed. If the project has one named scope, that is used; if it has several,
 * {@code --scope} is required. A guessed blast radius is how a typo fails open.
 */
public final class DoCommand {

    public record Options(Path project, String goal, String scope, String risk, String taskId,
                          String runId, String baseRef, boolean inPlace, boolean dryRun,
                          boolean conductor, boolean autoRejectGates, boolean initRepo) {
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

    public DoCommand(ProcessRunner processes) { this.processes = processes; }

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
                        && !arg.equals("--auto-reject-gates") && !arg.equals("--init-repo")) {
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
                hasFlag(args, "--auto-reject-gates"), hasFlag(args, "--init-repo"));
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

        OrcaIsolation.Placement placement;
        String isolateFrom = options.baseRef();
        if (options.inPlace()) {
            placement = OrcaIsolation.Placement.inPlace(requested);
        } else {
            try {
                isolateFrom = branchToIsolateFrom(requested, options.baseRef());
                placement = new OrcaIsolation(processes).isolate(requested, "w-" + taskId, isolateFrom);
            } catch (OrcaIsolation.IsolationException isolation) {
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
        TaskDraft.Written drafted;
        try {
            drafted = new TaskDraft().write(root, taskId, options.goal(), scope, risk,
                    defaultCommands.isEmpty());
        } catch (TaskDraft.TaskConflict conflict) {
            return fail("task_conflict", requested, root, taskId, conflict.getMessage());
        }
        ConfigLoader.Loaded loaded = loader.load(root, taskId);
        if (options.conductor()) {
            return runWithConductor(options, requested, root, placement, drafted, loaded, taskId,
                    runId, scope, risk, isolateFrom);
        }
        TaskLoop.Outcome loop = new TaskLoop(processes).run(loaded, user, runId, options.dryRun());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", loop.ok());
        report.put("code", loop.reason());
        report.put("next_action", loop.nextAction());
        report.put("goal", options.goal());
        report.put("task_id", taskId);
        report.put("task_existed", drafted.existed());
        report.put("run_id", runId);
        report.put("project", String.valueOf(requested));
        report.put("worktree", String.valueOf(root));
        report.put("isolated", placement.isolated());
        report.put("isolation", placement.reason());
        report.put("worktree_start_ref", isolateFrom);
        report.put("diff_base_ref", loaded.resolved().baseRef());
        report.put("base_refs_differ", !options.baseRef().equals(loaded.resolved().baseRef()));
        report.put("scope", scope);
        report.put("risk", risk);
        report.put("dry_run", options.dryRun());
        report.put("summary", String.valueOf(loop.summary()));
        report.put("steps", loop.summaryReport().get("steps"));
        report.put("decision_path", loop.summaryReport().get("decision_path"));
        report.put("decision_state", loop.summaryReport().get("decision_state"));
        report.put("decision_options", loop.summaryReport().get("decision_options"));
        report.put("approve_with", loop.summaryReport().get("approve_with"));
        report.put("lands", false);
        return new Outcome(loop.ok(), loop.reason(),
                requested, root, taskId, report);
    }

    private Outcome runWithConductor(Options options, Path requested, Path root,
                                     OrcaIsolation.Placement placement, TaskDraft.Written drafted,
                                     ConfigLoader.Loaded loaded, String taskId, String runId,
                                     String scope, String risk, String isolateFrom) throws Exception {
        if (options.dryRun()) {
            return fail("conductor_dry_run_unsupported", requested, root, taskId,
                    "use ordinary `warden do --dry-run`; Conductor exists to present a real human gate");
        }
        // Agent execution has its own task budget. This outer bound is the separate human-gate
        // TTL; Conductor checkpoints the workflow if the operator does not answer within a day.
        ConductorBridge.Outcome conductor = new ConductorBridge().run(root, taskId, runId,
                options.autoRejectGates(), Duration.ofHours(24));
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
        report.put("goal", options.goal());
        report.put("task_id", taskId);
        report.put("task_existed", drafted.existed());
        report.put("run_id", runId);
        report.put("project", String.valueOf(requested));
        report.put("worktree", String.valueOf(root));
        report.put("isolated", placement.isolated());
        report.put("isolation", placement.reason());
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
            throws OrcaIsolation.IsolationException {
        if (!"HEAD".equals(baseRef)) return baseRef;
        try {
            ProcessRunner.Result current = processes.run(
                    List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), project,
                    java.time.Duration.ofSeconds(30));
            String branch = current.ok() ? current.stdout().strip() : "";
            if (branch.isEmpty() || branch.equals("HEAD")) {
                throw new OrcaIsolation.IsolationException("detached_head",
                        "HEAD is detached in " + project + ", so there is no branch to cut a "
                                + "worktree from. Check out a branch, or pass --base-ref <branch>.");
            }
            return branch;
        } catch (OrcaIsolation.IsolationException already) {
            throw already;
        } catch (Exception notAskable) {
            throw new OrcaIsolation.IsolationException("detached_head",
                    "could not read the current branch of " + project + ": " + notAskable.getMessage());
        }
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
