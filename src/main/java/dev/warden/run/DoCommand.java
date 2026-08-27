package dev.warden.run;

import dev.warden.config.ConfigLoader;
import dev.warden.config.ProjectConfig;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.TaskDraft;
import dev.warden.config.UserConfig;
import dev.warden.execution.orca.OrcaIsolation;
import dev.warden.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                          String runId, String baseRef, boolean inPlace, boolean dryRun) {}

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
                if (!arg.equals("--in-place") && !arg.equals("--no-worktree") && !arg.equals("--dry-run")) {
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
                hasFlag(args, "--dry-run"));
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
            return fail("not_a_git_repository", requested, requested, null,
                    "warden do requires a Git repository so isolation and blast-radius have a merge-base");
        }

        String taskId = options.taskId() != null ? options.taskId() : TaskDraft.slug(options.goal());
        String runId = options.runId() != null ? options.runId() : "do-" + taskId;

        OrcaIsolation.Placement placement;
        if (options.inPlace()) {
            placement = OrcaIsolation.Placement.inPlace(requested);
        } else {
            try {
                placement = new OrcaIsolation(processes).isolate(requested, "w-" + taskId,
                        "HEAD".equals(options.baseRef()) ? "main" : options.baseRef());
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

        TaskDraft.Written drafted = new TaskDraft().write(root, taskId, options.goal(), scope, risk);
        ConfigLoader.Loaded loaded = loader.load(root, taskId);
        TaskLoop.Outcome loop = new TaskLoop(processes).run(loaded, user, runId, options.dryRun());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", loop.ok());
        report.put("code", loop.ok() ? "ready_for_human" : loop.reason());
        report.put("next_action", loop.nextAction());
        report.put("goal", options.goal());
        report.put("task_id", taskId);
        report.put("task_existed", drafted.existed());
        report.put("run_id", runId);
        report.put("project", String.valueOf(requested));
        report.put("worktree", String.valueOf(root));
        report.put("isolated", placement.isolated());
        report.put("isolation", placement.reason());
        report.put("scope", scope);
        report.put("risk", risk);
        report.put("dry_run", options.dryRun());
        report.put("summary", String.valueOf(loop.summary()));
        report.put("steps", loop.summaryReport().get("steps"));
        report.put("lands", false);
        return new Outcome(loop.ok(), loop.ok() ? "ready_for_human" : loop.reason(),
                requested, root, taskId, report);
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
