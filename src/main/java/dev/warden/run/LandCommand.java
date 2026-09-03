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
 *   <li>anything under `.warden`. Evidence is not the change.</li>
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
                          String remote) {}

    public record Outcome(boolean ok, String code, Map<String, Object> report) {}

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(10);

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
        return new Options(args[1], commit, push, pullRequest,
                option(args, "--title", null),
                bodyFile == null ? null : Path.of(bodyFile),
                messageFile == null ? null : Path.of(messageFile),
                option(args, "--base", null),
                option(args, "--remote", null));
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
        List<String> paths = sourcePaths(report);
        if (paths.isEmpty()) {
            return refuse("nothing_to_land", root, "the accepted run changed no source file");
        }

        String message = options.messageFile() != null
                ? Files.readString(options.messageFile(), StandardCharsets.UTF_8)
                : commitMessage(report);
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

        List<String> add = new ArrayList<>(List.of("git", "add", "--"));
        add.addAll(paths);
        ProcessRunner.Result staged = processes.run(add, root, GIT_TIMEOUT);
        if (!staged.ok()) return failed(result, "git_add_failed", staged);

        ProcessRunner.Result committed = processes.run(
                List.of("git", "commit", "-m", message), root, GIT_TIMEOUT);
        if (!committed.ok()) return failed(result, "git_commit_failed", committed);
        result.put("committed", commitSha(root));

        if (!options.push()) {
            result.put("ok", true);
            result.put("code", "committed");
            result.put("next", "re-run with --push or --pull-request");
            return new Outcome(true, "committed", result);
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
     * Subject once. Every later claim is answered by {@code stages} and
     * {@code skipped_stages}: a commit message outlives the run directory, so it
     * must not name a check nobody performed.
     */
    private static String commitMessage(Map<String, Object> report) {
        String goal = String.valueOf(report.getOrDefault("goal", report.get("task_id")));
        StringBuilder message = new StringBuilder(firstLine(goal)).append("\n\n");
        String rest = afterFirstLine(goal);
        if (!rest.isEmpty()) message.append(rest).append("\n\n");
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
     * What ran and passed, and what was skipped, in the report's own names.
     * Nothing skipped produces no skip clause.
     */
    private static String checksFrom(Map<String, Object> report) {
        LinkedHashMap<String, Boolean> lastOk = new LinkedHashMap<>();
        for (Object item : list(report.get("stages"))) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String step = textOrNull(row.get("step"));
            if (step == null) continue;
            lastOk.put(step, Boolean.TRUE.equals(row.get("ok")));
        }
        List<String> passed = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : lastOk.entrySet()) {
            if (entry.getValue()) passed.add(entry.getKey());
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

    private static List<String> plan(List<String> paths, String branch, String remote, String base,
                                     ProjectConfig.Land land, String title, Options options) {
        List<String> plan = new ArrayList<>();
        plan.add("git add -- " + String.join(" ", paths));
        plan.add("git commit -m <the message above>");
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

    /** The goal after its subject line, or empty when the goal is a single line. */
    private static String afterFirstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? "" : text.substring(newline + 1).strip();
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
