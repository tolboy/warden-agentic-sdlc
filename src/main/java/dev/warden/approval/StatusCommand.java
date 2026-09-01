package dev.warden.approval;

import dev.warden.config.ConfigLoader;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code warden status}: the human-gate view, including pending decisions that live in a
 * sibling worktree of this repository.
 *
 * <p>Standing in the wrong checkout used to look identical to "that run does not exist".
 * The two answers are different, and the operator has to be able to tell them apart — and
 * to find a run that is waiting without already knowing which worktree it is in.</p>
 */
public final class StatusCommand {

    public record Outcome(boolean ok, Map<String, Object> report) {}

    private final ProcessRunner processes;

    public StatusCommand(ProcessRunner processes) {
        this.processes = processes;
    }

    public Outcome run(Path cwd, String[] args) throws IOException {
        boolean acrossWorktrees = hasFlag(args, "--worktrees");
        String runId = runId(args);
        Path start = cwd.toAbsolutePath().normalize();
        Optional<Path> project = new ConfigLoader().locateProjectRoot(start);

        List<Map<String, Object>> worktrees = null;
        if (acrossWorktrees) {
            try {
                worktrees = scan(start);
            } catch (IOException | InterruptedException gitFailed) {
                if (gitFailed instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (project.isEmpty()) return new Outcome(false, notAWardenProject(start));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ok", false);
                result.put("code", "worktree_list_failed");
                result.put("message", gitFailed.getMessage());
                result.put("project", project.get().toString());
                return new Outcome(false, result);
            }
        }

        if (project.isEmpty()) {
            if (worktrees != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ok", true);
                result.put("decisions", List.of());
                result.put("worktrees", worktrees);
                return new Outcome(true, result);
            }
            return new Outcome(false, notAWardenProject(start));
        }

        Path root = project.get();
        ApprovalStore store = new ApprovalStore(root);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("project", root.toString());
        if (runId != null) {
            try {
                HumanDecision decision = store.read(runId);
                result.put("decision", decision.toMap());
                result.put("decision_path", store.decisionPath(runId).toString());
            } catch (ApprovalException failure) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("ok", false);
                error.put("code", failure.code());
                error.put("message", failure.getMessage());
                error.put("project", root.toString());
                return new Outcome(false, error);
            }
        } else if (worktrees == null) {
            // Plain status refuses rather than under-report: a listing that answers ok while
            // omitting a decision it could not read is the untruth this command exists to end.
            result.put("decisions", store.list().stream().map(HumanDecision::toMap).toList());
        } else {
            // With --worktrees the answer is per file, so one malformed decision costs only
            // itself. It used to cost every other decision in this checkout: the catch here
            // replaced the whole list with an empty one, so a single broken file made every
            // readable decision vanish from the response. Traded a silent drop for a silent
            // wipe, and an independent review caught it.
            result.put("decisions", readable(store));
        }
        if (worktrees != null) result.put("worktrees", worktrees);
        return new Outcome(true, result);
    }

    /**
     * Every decision in this checkout that can be read, for the {@code --worktrees} view.
     *
     * The unreadable ones are not dropped: the scan reports each by path with its parse
     * problem, in the {@code worktrees} rows. Between them nothing is hidden, and one broken
     * file costs only itself.
     */
    private static List<Map<String, Object>> readable(ApprovalStore store) throws IOException {
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (String runId : store.runIds()) {
            try {
                decisions.add(store.read(runId).toMap());
            } catch (ApprovalException reportedByTheScan) {
                // Named in `worktrees` with its path and the parse problem.
            }
        }
        return decisions;
    }

    /**
     * No {@code .warden/project.yaml} above {@code start}. Distinct from {@code unknown_run}:
     * the run may exist, just not in this directory.
     */
    public static Map<String, Object> notAWardenProject(Path start) {
        Path from = start.toAbsolutePath().normalize();
        ApprovalException failure = new ApprovalException("not_a_warden_project",
                "no Warden project found from " + from + "; the run may exist elsewhere");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("code", failure.code());
        result.put("directory", from.toString());
        result.put("message", failure.getMessage());
        return result;
    }

    /**
     * Every pending {@code decision.json} across {@code git worktree list --porcelain}, plus
     * any file that cannot be read. A checkout with no {@code .warden} is skipped; this is
     * a read, so it neither creates a worktree nor talks to Orca.
     */
    List<Map<String, Object>> scan(Path from) throws IOException, InterruptedException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path worktree : listWorktrees(from)) {
            collect(worktree, rows);
        }
        return rows;
    }

    private List<Path> listWorktrees(Path from) throws IOException, InterruptedException {
        String porcelain = new GitRepository(gitRoot(from), processes)
                .git(List.of("worktree", "list", "--porcelain"))
                .stdout();
        return parseWorktreePorcelain(porcelain);
    }

    /**
     * The checkout root above {@code from}, found by walking up for {@code .git}.
     *
     * Not by asking Git. {@link GitRepository} passes its own root as `safe.directory`, and
     * Git accepts only the worktree root there — so building one at the invocation directory
     * made this command work from a repository root and fail one level down with
     * `worktree_list_failed`, on any host where the checkout is owned by another account.
     * Found by the independent review, which ran the command from `src/` instead of reasoning
     * about it.
     *
     * Asking Git for the root is not an option: `rev-parse --show-toplevel` refuses the same
     * repository for the same ownership reason. A directory walk needs nobody's permission.
     * `.git` is a directory in a normal checkout and a file in a linked worktree, so this
     * tests for existence rather than for either shape.
     */
    static Path gitRoot(Path from) throws ApprovalException {
        Path directory = from.toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.exists(directory.resolve(".git"))) return directory;
            directory = directory.getParent();
        }
        throw new ApprovalException("not_a_git_repository",
                "no Git checkout found from " + from.toAbsolutePath().normalize()
                        + "; --worktrees lists the worktrees of one repository");
    }

    public static List<Path> parseWorktreePorcelain(String stdout) {
        List<Path> worktrees = new ArrayList<>();
        String current = null;
        boolean bare = false;
        for (String line : stdout.split("\\R", -1)) {
            if (line.isEmpty()) {
                addCheckout(worktrees, current, bare);
                current = null;
                bare = false;
                continue;
            }
            if (line.startsWith("worktree ")) {
                current = line.substring("worktree ".length());
            } else if (line.equals("bare")) {
                bare = true;
            }
        }
        addCheckout(worktrees, current, bare);
        return worktrees;
    }

    private static void addCheckout(List<Path> worktrees, String current, boolean bare) {
        if (current == null || current.isBlank() || bare) return;
        worktrees.add(Path.of(current).toAbsolutePath().normalize());
    }

    private static void collect(Path worktree, List<Map<String, Object>> rows) throws IOException {
        // A `.warden` directory is not a Warden project; `project.yaml` is. A checkout that
        // has runs but no contract is something else's leftovers, and reporting its decisions
        // as this repository's would be the same class of untruth this command exists to end.
        if (!Files.isRegularFile(worktree.resolve(".warden/project.yaml"))) return;
        Path runs = worktree.resolve(".warden/runs");
        if (!Files.isDirectory(runs)) return;
        List<Path> runDirectories;
        try (var paths = Files.list(runs)) {
            runDirectories = paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path runDirectory : runDirectories) {
            Path file = runDirectory.resolve(ApprovalStore.FILE_NAME);
            if (!Files.isRegularFile(file)) continue;
            try {
                HumanDecision decision = HumanDecision.fromMap(
                        Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
                String directoryId = runDirectory.getFileName().toString();
                if (!directoryId.equals(decision.runId())) {
                    rows.add(unreadable(file, "decision run_id '" + decision.runId()
                            + "' does not match directory '" + directoryId + "'"));
                    continue;
                }
                if (decision.state() != HumanDecision.State.PENDING) continue;
                rows.add(pending(worktree, decision));
            } catch (IOException | Json.JsonException problem) {
                rows.add(unreadable(file, problem.getMessage()));
            }
        }
    }

    private static Map<String, Object> pending(Path worktree, HumanDecision decision) {
        Path directory = worktree.toAbsolutePath().normalize();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("worktree", directory.toString());
        row.put("run_id", decision.runId());
        row.put("task_id", decision.taskId());
        row.put("kind", decision.kind().jsonValue());
        row.put("options", decision.options());
        row.put("approve", approveInstructions(directory, decision));
        return row;
    }

    /**
     * One pasteable line per option, and never one chosen on the operator's behalf.
     *
     * <p>This emitted a single command using {@code options().get(0)}. For a failure that is
     * {@code retry} and looks harmless; for a successful run the options are
     * {@code [accept, reject]}, so the line a person was invited to paste said
     * {@code --decision accept}. A discovery command that pre-types the authorization is the
     * human gate answering itself, which is the one thing this whole tool is built not to do.
     * An independent review found it; the run that exercised the feature happened to be a
     * failure, so nothing in testing would have shown it.</p>
     *
     * <p>{@code <accept|reject>} is not an option either: PowerShell and cmd.exe both treat
     * {@code <}, {@code |} and {@code >} as redirection, so it would not survive a paste. Two
     * complete commands, and the choice stays where it belongs.</p>
     *
     * <p>cmd.exe needs a drive-changing builtin to enter a worktree on another volume, and
     * {@code cd /d} is not a PowerShell command — so Windows gets {@code pushd}, which changes
     * drive in cmd.exe and is Set-Location in PowerShell.</p>
     */
    public static List<Map<String, Object>> approveInstructions(Path worktree, HumanDecision decision) {
        Path directory = worktree.toAbsolutePath().normalize();
        String enter = (windows() ? "pushd " : "cd ") + "\"" + directory + "\"";
        List<Map<String, Object>> commands = new ArrayList<>();
        for (String option : decision.options()) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("decision", option);
            command.put("command",
                    enter + " && warden approve " + decision.runId() + " --decision " + option);
            commands.add(command);
        }
        return List.copyOf(commands);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Map<String, Object> unreadable(Path file, String problem) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", file.toAbsolutePath().normalize().toString());
        row.put("error", problem);
        return row;
    }

    private static String runId(String[] args) {
        for (int index = 1; index < args.length; index++) {
            if (!args[index].startsWith("-")) return args[index];
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String argument : args) if (argument.equals(name)) return true;
        return false;
    }
}
