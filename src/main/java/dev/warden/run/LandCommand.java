package dev.warden.run;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.ProjectConfig;
import dev.warden.config.WardenTree;
import dev.warden.git.GitRepository;
import dev.warden.ledger.RunReport;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an accepted candidate into a commit, a pushed branch and a pull request.
 *
 * <h2>Why this exists, given that Warden never lands</h2>
 *
 * It still does not. A pull request is a request: it moves nothing into a protected branch,
 * and whoever merges it is a person looking at it in GitHub. What this removes is the part
 * that was neither reviewed nor recorded — three git commands typed from memory, in a
 * worktree that is easy to confuse with another, staging whatever happened to be dirty.
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li>a run with no {@code accept} decision, or one still pending — the acceptance is the
 *       authority, and without it there is nothing to act on;</li>
 *   <li>a worktree whose source fingerprint no longer matches the one that was accepted. What
 *       gets pushed must be what a human said yes to, not what is there now;</li>
 *   <li>the repository's own default branch. A landing step that can commit to `main` is a
 *       merge with extra steps;</li>
 *   <li>anything under `.warden`. Evidence is not the change;</li>
 *   <li>anything else in the index. The commit holds the accepted paths and nothing the
 *       operator had staged besides; that stays staged, and the report lists it.</li>
 * </ul>
 *
 * <h2>What it does not know</h2>
 *
 * Git, yes: merge-base, blast radius and the content fingerprint are built on it. Your forge,
 * no. `gh pr create` is GitHub's, `glab mr create` is GitLab's, `tea` is Gitea's, and plenty
 * of repositories have no forge at all. So the command that opens the request is declared in
 * `<project>/.warden/project.yaml` under `land.pull_request`, as argv rather than a shell
 * line, and a project that declares none simply cannot be asked to open one. The remote is
 * inferred only when the repository has exactly one; two and it must be named, because
 * picking one would be choosing where somebody's work goes.
 *
 * <h2>Nothing happens by default</h2>
 *
 * With no flags this prints the exact commands it would run and changes nothing. `--commit`,
 * `--push` and `--pull-request` escalate one step at a time, each implying the ones before.
 * Pushing a branch and opening a pull request are outward-facing, and a command that did them
 * because a run went green would be making that decision on the operator's behalf.
 */
public final class LandCommand {

    public record Options(String runId, boolean commit, boolean push, boolean pullRequest,
                          String title, Path bodyFile, Path messageFile, String base,
                          String remote, String type) {
        public Options {
            if (type == null || type.isBlank()) type = "feat";
        }

        /** Callers that predate {@code --type} get the conventional default. */
        public Options(String runId, boolean commit, boolean push, boolean pullRequest,
                       String title, Path bodyFile, Path messageFile, String base,
                       String remote) {
            this(runId, commit, push, pullRequest, title, bodyFile, messageFile, base, remote, "feat");
        }
    }

    public record Outcome(boolean ok, String code, Map<String, Object> report) {}

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(10);
    private static final Set<String> COMMIT_TYPES = Set.of(
            "feat", "fix", "docs", "chore", "refactor", "test", "perf");
    private static final int SUBJECT_LIMIT = 72;

    private final ProcessRunner processes;

    public LandCommand(ProcessRunner processes) { this.processes = processes; }

    public static Options parse(String[] args) {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("land requires a run id: warden land <run-id>");
        }
        boolean pullRequest = has(args, "--pull-request") || has(args, "--pr");
        boolean push = pullRequest || has(args, "--push");
        boolean commit = push || has(args, "--commit");
        String bodyFile = option(args, "--body-file", null);
        String messageFile = option(args, "--message-file", null);
        String type = option(args, "--type", "feat");
        if (!COMMIT_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "--type must be feat, fix, docs, chore, refactor, test or perf, not '"
                            + type + "'");
        }
        return new Options(args[1], commit, push, pullRequest,
                option(args, "--title", null),
                bodyFile == null ? null : Path.of(bodyFile),
                messageFile == null ? null : Path.of(messageFile),
                option(args, "--base", null),
                option(args, "--remote", null),
                type);
    }

    public Outcome run(Options options) throws Exception {
        return run(options, new ConfigLoader().findProjectRoot(Path.of(".")));
    }

    /** @param root the project the accepted run belongs to. */
    public Outcome run(Options options, Path root) throws Exception {
        GitRepository git = new GitRepository(root, processes);
        ApprovalStore decisions = new ApprovalStore(root);

        HumanDecision decision;
        try {
            decision = decisions.read(options.runId());
        } catch (IOException unknown) {
            return refuse("unknown_run", root, "no decision exists for run " + options.runId()
                    + "; land acts on an acceptance, and there is none");
        }
        if (decision.state() != HumanDecision.State.RESOLVED || !"accept".equals(decision.decision())) {
            return refuse("not_accepted", root, "run " + options.runId() + " is "
                    + decision.state().jsonValue()
                    + (decision.decision() == null ? "" : " as '" + decision.decision() + "'")
                    + ". Land acts only on an accepted candidate: record one with "
                    + "`warden approve " + options.runId() + " --decision accept`.");
        }

        Map<String, Object> report = new RunReport(processes).of(root, options.runId());
        Object base = report.get("diff_base_commit");
        if (!(base instanceof String diffBase)) {
            return refuse("candidate_unverifiable", root,
                    "the run summary has no diff_base_commit, so what was accepted cannot be identified");
        }
        String now = git.sourceFingerprint(diffBase);
        if (!now.equals(decision.candidateFingerprint())) {
            return refuse("candidate_changed", root, "the worktree no longer matches the candidate "
                    + "that was accepted. Landing it would push something nobody said yes to; "
                    + "run the task again and accept the result.");
        }

        String branch = currentBranch(root);
        if (branch == null) {
            return refuse("detached_head", root, "HEAD is detached, so there is no branch to push");
        }
        ProjectConfig.Land land = new ConfigLoader().load(root, taskOf(report)).project().land();

        String remote = options.remote() != null ? options.remote() : land.remote();
        if (remote == null && (options.push() || options.pullRequest())) {
            List<String> remotes = remotes(root);
            if (remotes.size() == 1) {
                remote = remotes.get(0);
            } else if (remotes.isEmpty()) {
                return refuse("no_remote", root, "this repository has no remote, so there is "
                        + "nowhere to push. The commit step still works: re-run with --commit.");
            } else {
                return refuse("remote_ambiguous", root, "this repository has several remotes "
                        + remotes + ". Name one with --remote, or set land.remote in "
                        + ".warden/project.yaml — picking one would be choosing where your work goes.");
            }
        }
        String target = options.base() != null ? options.base()
                : land.base() != null ? land.base() : defaultBranch(root, remote);
        if (branch.equals(target)) {
            return refuse("refuses_default_branch", root, "this worktree is on '" + branch
                    + "', which is the branch a pull request would target. Landing here would be a "
                    + "merge with extra steps; work on a branch of your own.");
        }

        // Exactly what the report calls the change, and nothing else. Warden's own evidence
        // is not part of it, and neither is whatever else the worktree happens to be holding.
        List<String> accepted = sourcePaths(report);
        if (accepted.isEmpty()) {
            return refuse("nothing_to_land", root, "the accepted run changed no source file");
        }
        // What the commit will hold, computed before anything is written, so the preview and
        // the commit are one list. An accepted path that no longer differs from HEAD is
        // already committed (by an earlier `land --commit`, or by hand) and is not committed
        // again. Anything else staged is left staged and out of the commit: the fingerprint
        // guard above cannot see a `.warden/` file or a staged-only edit whose working copy
        // matches HEAD, and a plain `git commit` used to carry both.
        Set<String> uncommitted = git.changedPaths();
        List<String> paths = accepted.stream().filter(uncommitted::contains).toList();
        List<String> alreadyCommitted = accepted.stream().filter(path -> !uncommitted.contains(path)).toList();
        List<String> leftStaged = stagedPaths(git).stream().filter(path -> !accepted.contains(path)).toList();

        String message = options.messageFile() != null
                ? Files.readString(options.messageFile(), StandardCharsets.UTF_8)
                : commitMessage(report, options.type());
        String title = options.title() != null ? options.title() : firstLine(message);
        String body = options.bodyFile() != null
                ? Files.readString(options.bodyFile(), StandardCharsets.UTF_8)
                : pullRequestBody(root, report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", options.runId());
        result.put("project", root.toString());
        result.put("branch", branch);
        result.put("remote", remote);
        result.put("base", target);
        result.put("accepted_by", decision.actor());
        result.put("accepted_at", decision.updatedAt().toString());
        result.put("paths", paths);
        if (!alreadyCommitted.isEmpty()) result.put("already_committed", alreadyCommitted);
        if (!leftStaged.isEmpty()) result.put("left_staged", leftStaged);
        result.put("commit_message", message);
        result.put("pull_request_title", title);
        result.put("would_run", plan(paths, branch, remote, target, land, title, options));
        result.put("lands", false);
        result.put("note", "a pull request is a request; nothing is merged by this command");

        if (!options.commit()) {
            result.put("ok", true);
            result.put("code", "planned");
            result.put("next", "re-run with --commit, --push or --pull-request to carry it out");
            return new Outcome(true, "planned", result);
        }

        if (paths.isEmpty()) {
            // Every accepted path is already at HEAD. `git commit` would fail with "nothing to
            // commit", which is how `land --commit` followed by `land --push` used to stop.
            result.put("committed", null);
        } else {
            ProcessRunner.Result staged = processes.run(addCommand(paths), root, GIT_TIMEOUT);
            if (!staged.ok()) return failed(result, "git_add_failed", staged);

            Path messageOnDisk = Files.createTempFile("warden-commit-", ".txt");
            ProcessRunner.Result committed;
            try {
                Files.writeString(messageOnDisk, message, StandardCharsets.UTF_8);
                committed = processes.run(commitCommand(messageOnDisk.toString(), paths), root, GIT_TIMEOUT);
            } finally {
                Files.deleteIfExists(messageOnDisk);
            }
            if (!committed.ok()) return failed(result, "git_commit_failed", committed);
            String sha = commitSha(root);
            result.put("committed", sha);
            // `--only` leaves the rest of the index out by construction; a commit hook that
            // stages more is the one way left for the commit to differ from the preview.
            List<String> landed = committedPaths(git);
            if (!new java.util.HashSet<>(landed).equals(new java.util.HashSet<>(paths))) {
                result.put("committed_paths", landed);
                result.put("ok", false);
                result.put("code", "commit_differs_from_plan");
                result.put("message", "commit " + sha + " holds " + landed + ", not the accepted "
                        + paths + "; a commit hook probably staged more. Nothing was pushed. "
                        + "Inspect it, and undo it with `git reset --soft HEAD~1` if it is wrong.");
                return new Outcome(false, "commit_differs_from_plan", result);
            }
        }

        if (!options.push()) {
            String code = paths.isEmpty() ? "already_committed" : "committed";
            result.put("ok", true);
            result.put("code", code);
            result.put("next", "re-run with --push or --pull-request");
            return new Outcome(true, code, result);
        }

        ProcessRunner.Result pushed = processes.run(
                List.of("git", "push", "-u", remote, branch), root, PUSH_TIMEOUT);
        if (!pushed.ok()) return failed(result, "git_push_failed", pushed);
        result.put("pushed", true);

        if (!options.pullRequest()) {
            result.put("ok", true);
            result.put("code", "pushed");
            result.put("next", "open the pull request yourself, or re-run with --pull-request");
            return new Outcome(true, "pushed", result);
        }

        if (!land.opensRequests()) {
            result.put("ok", false);
            result.put("code", "no_pull_request_command");
            result.put("message", "the branch is pushed, but this project declares no way to "
                    + "open a request. Warden knows git, not your forge. Add land.pull_request "
                    + "to .warden/project.yaml as argv, for example: "
                    + suggestion(root, remote));
            return new Outcome(false, "no_pull_request_command", result);
        }
        Path bodyOnDisk = Files.createTempFile("warden-pr-", ".md");
        Files.writeString(bodyOnDisk, body, StandardCharsets.UTF_8);
        try {
            List<String> command = render(land.pullRequest(), remote, branch, target, title,
                    bodyOnDisk.toString());
            result.put("pull_request_command", command);
            ProcessRunner.Result opened = processes.run(command, root, PUSH_TIMEOUT);
            if (!opened.ok()) return failed(result, "pull_request_failed", opened);
            result.put("pull_request", opened.stdout().strip());
        } finally {
            Files.deleteIfExists(bodyOnDisk);
        }
        result.put("ok", true);
        result.put("code", "pull_request_opened");
        result.put("next", "review and merge it yourself; Warden merges nothing");
        return new Outcome(true, "pull_request_opened", result);
    }

    // ------------------------------------------------------------------ what to write

    /** The paths the report calls the change, with Warden's own tree removed. */
    private static List<String> sourcePaths(Map<String, Object> report) {
        Object changed = report.get("changed_files");
        if (!(changed instanceof List<?> rows)) return List.of();
        List<String> paths = new ArrayList<>();
        for (Object item : rows) {
            String path = String.valueOf(item);
            if (path.equals(WardenTree.DIRECTORY) || path.startsWith(WardenTree.DIRECTORY + "/")) continue;
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    /**
     * {@code type(task-id): claim}, then the full goal, then what ran. The subject is
     * what a pull request title becomes by default, so it is cut to 72 characters at a
     * word boundary rather than taken from the goal verbatim.
     */
    public static String commitMessage(Map<String, Object> report, String type) {
        String goal = String.valueOf(report.getOrDefault("goal", report.get("task_id")));
        String taskId = String.valueOf(report.get("task_id"));
        StringBuilder message = new StringBuilder(subjectFor(type, taskId, goal)).append("\n\n");
        message.append(goal);
        if (!goal.endsWith("\n")) message.append('\n');
        message.append('\n');
        String checks = checksFrom(report);
        if (!checks.isEmpty()) message.append(checks);
        message.append("the evidence is in .warden/runs/").append(report.get("run_id")).append(".\n");
        for (Object item : list(report.get("vendors"))) {
            if (!(item instanceof Map<?, ?> row)) continue;
            message.append("\nRan-by: ").append(row.get("vendor")).append('/').append(row.get("model"));
        }
        return message.toString().strip() + "\n";
    }

    /**
     * {@code <type>(<task-id>): <claim>} at most 72 characters, cut at a word boundary
     * with no ellipsis. {@code <claim>} is the first line of the goal, first letter
     * lower-cased, trailing period dropped.
     */
    public static String subjectFor(String type, String taskId, String goal) {
        String prefix = type + "(" + taskId + "): ";
        String claim = claimFrom(goal);
        String raw = prefix + claim;
        if (raw.length() <= SUBJECT_LIMIT) return raw;
        int budget = SUBJECT_LIMIT - prefix.length();
        if (budget <= 0) return raw.substring(0, SUBJECT_LIMIT);
        return prefix + cutAtWordBoundary(claim, budget);
    }

    private static String claimFrom(String goal) {
        String line = firstLine(goal);
        if (line.endsWith(".")) line = line.substring(0, line.length() - 1);
        if (line.isEmpty()) return line;
        char first = line.charAt(0);
        if (Character.isUpperCase(first)) {
            return Character.toLowerCase(first) + line.substring(1);
        }
        return line;
    }

    /** Plain cut: last space inside {@code max}, or a hard cut when there is none. */
    private static String cutAtWordBoundary(String text, int max) {
        if (text.length() <= max) return text;
        String head = text.substring(0, max);
        int space = head.lastIndexOf(' ');
        if (space <= 0) return head;
        return head.substring(0, space);
    }

    /**
     * What ran and passed, what ran and did not, and what was skipped, in the report's own
     * names. Nothing skipped produces no skip clause, and nothing failed produces no fail one.
     *
     * Public for the same reason {@code OrcaClient.summarize} is: it is a pure function of the
     * report, and the run shapes worth checking here are ones the loop cannot easily be made
     * to produce on demand.
     */
    public static String checksFrom(Map<String, Object> report) {
        LinkedHashMap<String, Boolean> lastOk = new LinkedHashMap<>();
        for (Object item : list(report.get("stages"))) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String step = textOrNull(row.get("step"));
            if (step == null) continue;
            lastOk.put(step, Boolean.TRUE.equals(row.get("ok")));
        }
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : lastOk.entrySet()) {
            (entry.getValue() ? passed : failed).add(entry.getKey());
        }

        LinkedHashMap<String, List<String>> skippedByReason = new LinkedHashMap<>();
        for (Object item : list(report.get("skipped_stages"))) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String stage = textOrNull(row.get("stage"));
            if (stage == null) continue;
            String reason = textOrNull(row.get("reason"));
            List<String> names = skippedByReason.computeIfAbsent(
                    reason == null ? "" : reason, ignored -> new ArrayList<>());
            if (!names.contains(stage)) names.add(stage);
        }

        List<String> clauses = new ArrayList<>();
        // Named first, and named at all. A stage that ran and did not pass used to be dropped
        // from this sentence entirely: not among the passed, not among the skipped, absent. On
        // the path that exists today it cannot happen, because landing requires an acceptance
        // and an acceptance requires a green run — but the silence was in the message builder
        // rather than in that rule, so the first path to `land` that did not come through a
        // green run would have inherited it. A failure is also the one thing a reader of this
        // line must not have to look for.
        if (!failed.isEmpty()) clauses.add(joinEnglish(failed) + " failed");
        if (!passed.isEmpty()) clauses.add(joinEnglish(passed) + " passed");
        for (Map.Entry<String, List<String>> entry : skippedByReason.entrySet()) {
            List<String> names = entry.getValue();
            String clause = joinEnglish(names) + (names.size() == 1 ? " was skipped" : " were skipped");
            if (!entry.getKey().isEmpty()) clause += " (" + entry.getKey() + ")";
            clauses.add(clause);
        }
        if (clauses.isEmpty()) return "";
        return String.join(". ", clauses) + ";\n";
    }

    /**
     * The pull-request body is assembled from the run's own evidence rather than written
     * fresh, so what a reviewer reads on GitHub is what the machine and the models actually
     * said, quoted, and not a summary of a summary.
     */
    private String pullRequestBody(Path root, Map<String, Object> report) {
        StringBuilder body = new StringBuilder();
        body.append("## What this changes\n\n").append(report.get("goal")).append("\n\n");

        body.append("## How it was checked\n\n");
        body.append("| stage | vendor / model | outcome | cost |\n|---|---|---|---|\n");
        for (Object item : list(report.get("stages"))) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> stage = cast(raw);
            String who = "role".equals(stage.get("kind"))
                    ? stage.get("vendor") + " / " + stage.get("model") : "—";
            body.append("| ").append(stage.get("step")).append(" | ").append(who)
                    .append(" | ").append(Boolean.TRUE.equals(stage.get("ok")) ? "passed" : "failed")
                    .append(" | ").append(stage.get("cost_usd") == null ? "—" : stage.get("cost_usd"))
                    .append(" |\n");
        }

        if (report.get("visual") instanceof Map<?, ?> raw) {
            Map<String, Object> visual = cast(raw);
            List<Object> scenarios = list(visual.get("scenarios"));
            if (!scenarios.isEmpty()) {
                body.append("\nBrowser scenarios, at the viewports named:\n\n");
                for (Object item : scenarios) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    body.append("- ").append(Boolean.TRUE.equals(row.get("ok")) ? "✔" : "✘")
                            .append(" `").append(row.get("scenario")).append("`\n");
                }
            }
        }

        appendVerdict(body, root, report, "reviewer", "An independent review read the diff");
        appendVerdict(body, root, report, "visual_qa", "A second model read the screenshots");

        List<Object> changed = list(report.get("changed_files"));
        body.append("\n## Files\n\n");
        for (Object path : changed) body.append("- `").append(path).append("`\n");

        body.append("\n---\n\nAssembled by Warden from run `").append(report.get("run_id"))
                .append("`. Warden merges nothing; this is a request.\n");
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendVerdict(StringBuilder body, Path root, Map<String, Object> report,
                               String role, String lead) {
        for (Object item : list(report.get("stages"))) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> stage = cast(raw);
            if (!role.equals(stage.get("role")) || !(stage.get("artifact_path") instanceof String at)) continue;
            Path file = root.resolve(at);
            if (!Files.isRegularFile(file)) continue;
            try {
                Map<String, Object> artifact = dev.warden.json.Json.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8));
                body.append("\n").append(lead).append(" (")
                        .append(stage.get("vendor")).append(", verdict `")
                        .append(artifact.get("verdict")).append("`):\n\n> ")
                        .append(String.valueOf(artifact.get("summary")).replace("\n", "\n> "))
                        .append('\n');
                List<Object> findings = list(artifact.get("findings"));
                if (!findings.isEmpty()) {
                    body.append("\nIt also noted, without blocking:\n\n");
                    for (Object one : findings) {
                        if (!(one instanceof Map<?, ?> finding)) continue;
                        body.append("- **").append(finding.get("severity")).append("** ")
                                .append(finding.get("message")).append('\n');
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // A missing artifact is not a reason to refuse to open a pull request; the
                // stage table above already says the stage passed.
            }
            return;
        }
    }

    // ---------------------------------------------------------------------- mechanics

    /** The commands the plan prints, in the form they are run. */
    private static List<String> plan(List<String> paths, String branch, String remote, String base,
                                     ProjectConfig.Land land, String title, Options options) {
        List<String> plan = new ArrayList<>();
        if (paths.isEmpty()) {
            plan.add("(nothing to commit: every accepted path is already at HEAD)");
        } else {
            plan.add(String.join(" ", addCommand(paths)));
            plan.add(String.join(" ", commitCommand("<message file>", paths)));
        }
        if (options.push() || options.pullRequest()) {
            plan.add("git push -u " + (remote == null ? "<remote>" : remote) + " " + branch);
        }
        if (options.pullRequest()) {
            plan.add(land.opensRequests()
                    ? String.join(" ", render(land.pullRequest(), remote, branch, base, title,
                            "<the body above, on disk>"))
                    : "(this project declares no land.pull_request command)");
        }
        return plan;
    }

    /**
     * Literal pathspecs: a file named {@code *.txt} or {@code :x} is that file, not a glob or
     * pathspec magic that would pull its neighbours into the commit.
     */
    private static List<String> addCommand(List<String> paths) {
        List<String> command = new ArrayList<>(List.of("git", "--literal-pathspecs", "add", "--"));
        command.addAll(paths);
        return command;
    }

    /**
     * {@code --only} commits HEAD plus the working-tree content of exactly these paths, and
     * leaves whatever else is staged staged and out of the commit.
     */
    private static List<String> commitCommand(String messageFile, List<String> paths) {
        List<String> command = new ArrayList<>(List.of(
                "git", "--literal-pathspecs", "commit", "--only", "-F", messageFile, "--"));
        command.addAll(paths);
        return command;
    }

    private static List<String> stagedPaths(GitRepository git) throws IOException, InterruptedException {
        return nulSeparated(git.git(List.of("diff", "--cached", "--name-only", "-z", "--no-renames", "HEAD", "--")));
    }

    private static List<String> committedPaths(GitRepository git) throws IOException, InterruptedException {
        return nulSeparated(git.git(List.of("diff-tree", "-r", "--no-commit-id", "--name-only", "-z",
                "--no-renames", "HEAD")));
    }

    private static List<String> nulSeparated(ProcessRunner.Result result) {
        List<String> paths = new ArrayList<>();
        for (String path : result.stdout().split("\0", -1)) if (!path.isBlank()) paths.add(path);
        return List.copyOf(paths);
    }

    /** Argv substitution only: no shell, so a title with a quote in it stays one argument. */
    private static List<String> render(List<String> template, String remote, String branch,
                                       String base, String title, String bodyFile) {
        List<String> command = new ArrayList<>();
        for (String part : template) {
            command.add(part
                    .replace("{{remote}}", remote == null ? "" : remote)
                    .replace("{{branch}}", branch)
                    .replace("{{base}}", base)
                    .replace("{{title}}", title)
                    .replace("{{body_file}}", bodyFile));
        }
        return command;
    }

    private List<String> remotes(Path root) throws IOException, InterruptedException {
        ProcessRunner.Result result = processes.run(List.of("git", "remote"), root, GIT_TIMEOUT);
        if (!result.ok()) return List.of();
        List<String> names = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty()) names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * A worked example for the forge this remote appears to belong to. Offered in a refusal,
     * never acted on: recognising `github.com` in a URL is a good hint and a bad decision.
     */
    private String suggestion(Path root, String remote) {
        String url = "";
        try {
            ProcessRunner.Result result = processes.run(
                    List.of("git", "remote", "get-url", remote), root, GIT_TIMEOUT);
            if (result.ok()) url = result.stdout().strip().toLowerCase();
        } catch (IOException | InterruptedException noRemote) {
            if (noRemote instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        String tool = url.contains("gitlab") ? "glab\", \"mr\", \"create"
                : url.contains("gitea") || url.contains("codeberg") ? "tea\", \"pr\", \"create"
                : "gh\", \"pr\", \"create";
        return "land:\n  pull_request: [\"" + tool + "\", \"--base\", \"{{base}}\", "
                + "\"--head\", \"{{branch}}\", \"--title\", \"{{title}}\", "
                + "\"--body-file\", \"{{body_file}}\"]";
    }

    private String currentBranch(Path root) throws IOException, InterruptedException {
        ProcessRunner.Result result = processes.run(
                List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), root, GIT_TIMEOUT);
        String branch = result.ok() ? result.stdout().strip() : "";
        return branch.isEmpty() || branch.equals("HEAD") ? null : branch;
    }

    private String commitSha(Path root) throws IOException, InterruptedException {
        ProcessRunner.Result result = processes.run(
                List.of("git", "rev-parse", "HEAD"), root, GIT_TIMEOUT);
        return result.ok() ? result.stdout().strip() : null;
    }

    /**
     * The branch a pull request would target. Asked of the remote rather than assumed:
     * guessing `main` is how a landing step opens a request against a branch that does not
     * exist, or worse, one that does and is not the trunk.
     */
    private String defaultBranch(Path root, String remote) throws IOException, InterruptedException {
        if (remote == null) return "main";
        ProcessRunner.Result head = processes.run(
                List.of("git", "symbolic-ref", "--short", "refs/remotes/" + remote + "/HEAD"),
                root, GIT_TIMEOUT);
        if (head.ok() && !head.stdout().isBlank()) {
            String name = head.stdout().strip();
            int slash = name.lastIndexOf('/');
            return slash < 0 ? name : name.substring(slash + 1);
        }
        return "main";
    }

    private Outcome refuse(String code, Path root, String message) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("code", code);
        report.put("project", root.toString());
        report.put("message", message);
        report.put("lands", false);
        return new Outcome(false, code, report);
    }

    private Outcome failed(Map<String, Object> report, String code, ProcessRunner.Result result) {
        report.put("ok", false);
        report.put("code", code);
        report.put("command", result.command());
        report.put("exit_code", (long) result.exitCode());
        report.put("stderr", result.stderr().strip());
        report.put("stdout", result.stdout().strip());
        return new Outcome(false, code, report);
    }

    private static String taskOf(Map<String, Object> report) {
        return String.valueOf(report.get("task_id"));
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.strip();
    }

    private static String textOrNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static String joinEnglish(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) out.append(index == items.size() - 1 ? " and " : ", ");
            out.append(items.get(index));
        }
        return out.toString();
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> rows ? List.copyOf(rows) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static boolean has(String[] args, String name) {
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
