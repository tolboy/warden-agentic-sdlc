package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.LandCommand;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code warden land} against a real repository whose index holds more than the accepted
 * change. The fingerprint guard cannot see a staged {@code .warden/} file, or a staged edit
 * whose working copy was put back, and a plain {@code git commit} carried both into the
 * accepted commit. The run is written by hand rather than by the loop, so this suite costs
 * git calls and nothing else.
 */
public final class LandCommandTest implements Suite {
    @Override public String name() { return "land"; }

    private static final ProcessRunner RUNNER = new ProcessRunner();

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-land-");
        try {
            onlyTheAcceptedPathsAreCommitted(check, sandbox);
            aSecondLandPushesWhatTheFirstCommitted(check, sandbox);
            aHookThatStagesMoreIsCaught(check, sandbox);
            aHookThatRewritesAnAcceptedFileIsCaught(check, sandbox);
            aRefusedCommitIsNotPushedByALaterLand(check, sandbox);
        } finally {
            deleteTree(sandbox);
        }
    }

    private static void onlyTheAcceptedPathsAreCommitted(Check check, Path sandbox) throws Exception {
        Path project = acceptedProject(sandbox, "land-index", true);
        // Staged before landing, and invisible to the source fingerprint: a Warden file, and
        // an edit to README.md whose working copy was put back to HEAD's.
        Files.writeString(project.resolve(".warden/operator-notes.yaml"), "note: mine\n");
        git(project, "add", "--", ".warden/operator-notes.yaml");
        Files.writeString(project.resolve("README.md"), "seed, edited and staged\n");
        git(project, "add", "--", "README.md");
        Files.writeString(project.resolve("README.md"), "seed\n");

        LandCommand land = new LandCommand(RUNNER);
        LandCommand.Outcome planned = land.run(options(false, false), project);
        check.eq("the staged extras do not move the fingerprint, so land plans", "planned", planned.code());
        List<String> accepted = List.of("src/gone.txt", "src/kept.txt", "src/result.txt");
        check.eq("the plan names the accepted paths, including a deletion", accepted,
                sorted(planned.report().get("paths")));
        check.eq("and lists what stays staged instead of committed",
                List.of(".warden/operator-notes.yaml", "README.md"), sorted(planned.report().get("left_staged")));
        String wouldRun = String.valueOf(planned.report().get("would_run"));
        check.contains("the printed add is the argv that runs", wouldRun,
                "git --literal-pathspecs add -- src/gone.txt src/kept.txt src/result.txt");
        check.contains("and the commit takes only those paths", wouldRun,
                "git --literal-pathspecs commit --only -F <message file> -- src/gone.txt src/kept.txt src/result.txt");

        LandCommand.Outcome committed = land.run(options(true, false), project);
        check.eq("commit succeeds", "committed", committed.code());
        check.eq("the commit holds exactly the planned paths", accepted,
                sorted(lines(git(project, "diff-tree", "-r", "--no-commit-id", "--name-only", "HEAD"))));
        check.eq("the deletion is in it", "",
                git(project, "ls-tree", "--name-only", "HEAD", "--", "src/gone.txt").strip());
        check.eq("what the operator staged is still staged, and nothing else is",
                List.of(".warden/operator-notes.yaml", "README.md"),
                sorted(lines(git(project, "diff", "--cached", "--name-only", "HEAD"))));
        check.eq("the staged README edit survived as staged content", "seed, edited and staged\n",
                git(project, "show", ":README.md").replace("\r\n", "\n"));
        check.eq("HEAD's README is the base one", "seed\n",
                git(project, "show", "HEAD:README.md").replace("\r\n", "\n"));
    }

    /**
     * {@code land --commit}, then {@code land --push}. Push implies commit, and the commit
     * step used to fail with "nothing to commit" instead of moving on. The candidate adds no
     * file: a new file's acceptance fingerprint does not survive its own commit yet, which is
     * a separate defect in the fingerprint, not in this step.
     */
    private static void aSecondLandPushesWhatTheFirstCommitted(Check check, Path sandbox) throws Exception {
        Path project = acceptedProject(sandbox, "land-twice", false);
        LandCommand land = new LandCommand(RUNNER);
        check.eq("the first land commits", "committed", land.run(options(true, false), project).code());
        String head = git(project, "rev-parse", "HEAD").strip();

        LandCommand.Outcome again = land.run(options(true, false), project);
        check.eq("a second --commit finds nothing left to commit", "already_committed", again.code());
        check.eq("and makes no commit", head, git(project, "rev-parse", "HEAD").strip());
        LandCommand.Outcome preview = land.run(options(false, false), project);
        check.eq("its preview commits nothing", List.of(), preview.report().get("paths"));
        check.eq("and says the change is already at HEAD", List.of("src/gone.txt", "src/kept.txt"),
                sorted(preview.report().get("already_committed")));

        Path remote = sandbox.resolve("land-twice-remote.git");
        RUNNER.run(List.of("git", "init", "-q", "--bare", remote.toString()), sandbox, Duration.ofSeconds(60));
        git(project, "remote", "add", "origin", remote.toString());
        LandCommand.Outcome pushed = land.run(options(true, true), project);
        check.eq("--push after --commit pushes", "pushed", pushed.code());
        check.eq("the pushed branch is the landed commit", head,
                RUNNER.run(List.of("git", "rev-parse", "refs/heads/work"), remote, Duration.ofSeconds(60))
                        .stdout().strip());
    }

    /**
     * {@code --only} leaves the index out by construction. A pre-commit hook that stages
     * more is the way left for the commit to differ from the preview, and it must stop the
     * push rather than ride along.
     */
    private static void aHookThatStagesMoreIsCaught(Check check, Path sandbox) throws Exception {
        Path project = acceptedProject(sandbox, "land-hook", true);
        Path hooks = project.resolve(".git/hooks");
        Files.createDirectories(hooks);
        Files.writeString(hooks.resolve("pre-commit"),
                "#!/bin/sh\necho 'added by a hook' > hook-extra.txt\ngit add hook-extra.txt\n");
        hooks.resolve("pre-commit").toFile().setExecutable(true);
        Path remote = sandbox.resolve("land-hook-remote.git");
        RUNNER.run(List.of("git", "init", "-q", "--bare", remote.toString()), sandbox, Duration.ofSeconds(60));
        git(project, "remote", "add", "origin", remote.toString());

        LandCommand.Outcome outcome = new LandCommand(RUNNER).run(options(true, true), project);
        check.eq("land stops when the commit is not the plan", "commit_differs_from_plan", outcome.code());
        check.contains("and names what the hook added", String.valueOf(outcome.report().get("committed_paths")),
                "hook-extra.txt");
        check.that("nothing was pushed", !RUNNER.run(List.of("git", "rev-parse", "--verify", "-q",
                "refs/heads/work"), remote, Duration.ofSeconds(60)).ok());
    }

    /**
     * A formatter in a pre-commit hook: it rewrites an accepted file and stages it again,
     * here in the index alone, so the working tree still matches the fingerprint and the
     * commit still holds exactly the planned paths. Only the blobs show the difference.
     */
    private static void aHookThatRewritesAnAcceptedFileIsCaught(Check check, Path sandbox) throws Exception {
        Path project = acceptedProject(sandbox, "land-format", false);
        Path hook = installHook(project, "#!/bin/sh\nprintf 'after, formatted\\n' > src/kept.txt\n"
                + "git add src/kept.txt\nprintf 'after\\n' > src/kept.txt\n");
        Path remote = bareRemote(sandbox, project, "land-format-remote.git");

        LandCommand land = new LandCommand(RUNNER);
        LandCommand.Outcome outcome = land.run(options(true, true), project);
        check.eq("land stops when a hook rewrote an accepted file", "commit_differs_from_accepted", outcome.code());
        check.eq("and names the file", List.of("src/kept.txt"), outcome.report().get("altered_paths"));
        check.eq("the commit does hold the hook's version", "after, formatted\n",
                git(project, "show", "HEAD:src/kept.txt").replace("\r\n", "\n"));
        check.that("nothing was pushed", !pushed(remote));

        Files.delete(hook);
        LandCommand.Outcome again = land.run(options(true, true), project);
        check.eq("with the hook gone, a second land still will not push that commit",
                "refused_commit_in_history", again.code());
        check.that("still nothing pushed", !pushed(remote));

        git(project, "reset", "-q", "--soft", "HEAD~1");
        LandCommand.Outcome undone = land.run(options(true, true), project);
        check.eq("once the operator undoes it, land commits the accepted content and pushes",
                "pushed", undone.code());
        check.eq("which is the accepted file", "after\n",
                git(project, "show", "HEAD:src/kept.txt").replace("\r\n", "\n"));
    }

    /**
     * A hook adds a {@code .warden/} file to land's commit. The first land refuses it and
     * leaves it for the operator; the source is then at HEAD and {@code .warden/} is outside
     * the source fingerprint, so a second {@code land --push} had nothing to commit, nothing
     * to check, and published the commit the first one refused.
     */
    private static void aRefusedCommitIsNotPushedByALaterLand(Check check, Path sandbox) throws Exception {
        Path project = acceptedProject(sandbox, "land-retry", false);
        Path hook = installHook(project, "#!/bin/sh\necho 'note: from a hook' > .warden/operator-notes.yaml\n"
                + "git add .warden/operator-notes.yaml\n");
        Path remote = bareRemote(sandbox, project, "land-retry-remote.git");

        LandCommand land = new LandCommand(RUNNER);
        LandCommand.Outcome first = land.run(options(true, false), project);
        check.eq("the first land refuses the extra path", "commit_differs_from_plan", first.code());
        String refused = String.valueOf(first.report().get("committed"));

        Files.delete(hook);
        LandCommand.Outcome preview = land.run(options(false, false), project);
        check.eq("a preview says the refused commit is still there", List.of(refused),
                preview.report().get("refused_commits_in_history"));
        LandCommand.Outcome retry = land.run(options(true, true), project);
        check.eq("a later --push refuses instead of publishing it", "refused_commit_in_history", retry.code());
        check.contains("and names it", String.valueOf(retry.report().get("message")), refused);
        check.that("nothing was pushed", !pushed(remote));
    }

    private static Path installHook(Path project, String script) throws Exception {
        Path hooks = project.resolve(".git/hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, script);
        hook.toFile().setExecutable(true);
        return hook;
    }

    private static Path bareRemote(Path sandbox, Path project, String name) throws Exception {
        Path remote = sandbox.resolve(name);
        RUNNER.run(List.of("git", "init", "-q", "--bare", remote.toString()), sandbox, Duration.ofSeconds(60));
        git(project, "remote", "add", "origin", remote.toString());
        return remote;
    }

    private static boolean pushed(Path remote) throws Exception {
        return RUNNER.run(List.of("git", "rev-parse", "--verify", "-q", "refs/heads/work"), remote,
                Duration.ofSeconds(60)).ok();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A repository on branch {@code work} with an accepted run whose candidate modifies and
     * deletes a source file, and adds one when {@code addsFile}.
     */
    private static Path acceptedProject(Path sandbox, String name, boolean addsFile) throws Exception {
        Path project = sandbox.resolve(name);
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: %s
                base_ref: HEAD
                checks:
                  fast: ["git diff --check"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """.formatted(name));
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                budgets:
                  max_role_runs: 4
                  max_cost_usd: 1.0
                max_fix_attempts: 1
                """);
        Files.writeString(project.resolve("README.md"), "seed\n");
        Files.writeString(project.resolve("src/kept.txt"), "before\n");
        Files.writeString(project.resolve("src/gone.txt"), "to be deleted\n");
        Files.writeString(project.resolve(".gitattributes"), "* -text\n");
        git(project, "init", "-q", "-b", "main");
        git(project, "config", "user.email", "test@example.invalid");
        git(project, "config", "user.name", "test");
        git(project, "config", "core.autocrlf", "false");
        git(project, "add", "-A");
        git(project, "commit", "-qm", "base");
        git(project, "checkout", "-q", "-b", "work");
        String base = git(project, "rev-parse", "HEAD").strip();

        Files.writeString(project.resolve("src/kept.txt"), "after\n");
        Files.delete(project.resolve("src/gone.txt"));
        if (addsFile) Files.writeString(project.resolve("src/result.txt"), "made by the run\n");

        Path run = project.resolve(".warden/runs/r1");
        Files.createDirectories(run);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("task_id", "hello");
        summary.put("ok", true);
        summary.put("diff_base_commit", base);
        summary.put("steps", List.of());
        Files.writeString(run.resolve("task-run.json"), Json.writePretty(summary), StandardCharsets.UTF_8);
        accept(project, base);
        return project;
    }

    private static void accept(Path project, String base) throws Exception {
        ApprovalStore store = new ApprovalStore(project);
        HumanDecision pending = store.createSuccess("r1", "hello", "all gates passed",
                project.resolve(".warden/runs/r1/task-run.json"),
                new GitRepository(project, RUNNER).sourceFingerprint(base));
        store.resolve("r1", pending.updatedAt().toString(), "accept", "tester", "");
    }

    private static LandCommand.Options options(boolean commit, boolean push) {
        return new LandCommand.Options("r1", commit, push, false, null, null, null, null, null);
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.Result result = RUNNER.run(command, cwd, Duration.ofSeconds(60));
        if (!result.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
        return result.stdout();
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) if (!line.isBlank()) lines.add(line.strip());
        return lines;
    }

    private static List<String> sorted(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) items.add(String.valueOf(item));
        items.sort(null);
        return items;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
