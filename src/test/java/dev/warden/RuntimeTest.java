package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.gate.GateRunner;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class RuntimeTest implements Suite {
    @Override public String name() { return "runtime"; }

    @Override public void run(Check check) throws Exception {
        ProcessRunner runner = new ProcessRunner();
        ProcessRunner.Result captured = runner.run(shell("echo hello"), Path.of("."), Duration.ofSeconds(10), 1024);
        check.that("process succeeds", captured.ok());
        check.contains("stdout captured", captured.stdout(), "hello");

        ProcessRunner.Result truncated = runner.run(shell(longOutput()), Path.of("."), Duration.ofSeconds(10), 32);
        check.that("output is capped", truncated.stdoutTruncated());
        check.that("capture limit is respected", truncated.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32);
        ProcessRunner.Result timedOut = runner.run(shell(slowCommand()), Path.of("."), Duration.ofMillis(100), 1024);
        check.that("timeout settles", timedOut.timedOut());
        check.that("timed-out process is no longer successful", !timedOut.ok());

        Path repository = fixture();
        try {
            GitRepository git = new GitRepository(repository, runner);
            check.eq("merge-base resolves", 40, git.mergeBase("HEAD").length());
            String originalBase = git.mergeBase("HEAD");
            Files.writeString(repository.resolve("committed-outside.txt"), "committed outside\n");
            command(repository, "git", "add", "committed-outside.txt");
            command(repository, "git", "-c", "user.name=Warden Tests", "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "outside change");
            check.that("committed diff since merge-base is visible",
                    git.changedPaths(originalBase).contains("committed-outside.txt"));
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");
            check.eq("changed path found", Set.of("src/value.txt"), git.changedPaths());
            check.eq("scope accepts real child", List.of(), git.outsideScope(git.changedPaths(), List.of("src")));
            check.eq("scope rejects prefix collision", List.of("src/value.txt"),
                    git.outsideScope(git.changedPaths(), List.of("sr")));

            ConfigLoader.Loaded loaded = new ConfigLoader().load(repository, "smoke");
            GateRunner.Outcome baseline = new GateRunner(runner)
                    .runBaseline(loaded, "test-baseline", null, null);
            check.that("the pre-agent baseline runs through the real gate process", baseline.ok());
            check.eq("baseline reports its distinct phase", "baseline", baseline.data().get("phase"));
            check.eq("baseline runs only its project-health command", "echo baseline-ok",
                    ((java.util.Map<?, ?>) ((java.util.List<?>) baseline.data().get("commands")).get(0))
                            .get("command"));
            check.eq("baseline does not run the task command", 1,
                    ((java.util.List<?>) baseline.data().get("commands")).size());
            check.eq("baseline evidence has its own report name", "baseline-gate.json",
                    baseline.report().getFileName().toString());
            check.contains("and its own append-only ledger event",
                    Files.readString(repository.resolve(".warden/runs/test-baseline/evidence.jsonl")),
                    "\"type\":\"baseline_gate\"");

            Path projectContract = repository.resolve(".warden/project.yaml");
            Path source = repository.resolve("src/value.txt");
            String originalContract = Files.readString(projectContract);
            String originalSource = Files.readString(source);
            try {
                Files.writeString(projectContract, originalContract.replace(
                        "\"echo baseline-ok\"",
                        "\"echo baseline-mutated >> src/value.txt\""));
                GateRunner.Outcome mutatingBaseline = new GateRunner(runner).runBaseline(
                        new ConfigLoader().load(repository, "smoke"),
                        "test-mutating-baseline", null, null);
                check.eq("a baseline command may not alter even in-scope project source",
                        "baseline_mutated_source", mutatingBaseline.code());
                check.that("the mutating baseline still leaves a failure report",
                        Files.isRegularFile(mutatingBaseline.report()));
            } finally {
                Files.writeString(source, originalSource);
                Files.writeString(projectContract, originalContract);
            }
            GateRunner.Outcome passed = new GateRunner(runner).run(loaded, "test-pass");
            check.that("machine gate passes in-scope change", passed.ok());
            check.that("machine gate report written", Files.isRegularFile(passed.report()));
            check.that("ignored-path limitation disclosed", passed.data().containsKey("does_not_cover"));
            check.that("managed run evidence does not poison later blast radius",
                    git.changedPaths().stream().noneMatch(path -> path.startsWith(".warden/runs/")));
            Files.writeString(repository.resolve(".warden/tasks/extra.yaml"), "version: 1\nid: extra\ngoal: g\nscope: code\n");
            check.that("untracked Warden contract files remain observable",
                    git.changedPaths().contains(".warden/tasks/extra.yaml"));

            // A project whose `.warden` is not committed — which is every project the day
            // `warden do` created it — must not fail its own next task because a sibling task
            // contract exists on disk and is outside the task's source scope.
            java.util.Map<String, String> pinned =
                    dev.warden.config.WardenTree.snapshot(repository);
            GateRunner.Outcome withSibling = new GateRunner(runner)
                    .run(loaded, "test-sibling-contract", pinned, null);
            check.that("an unchanged sibling contract is not a blast-radius violation",
                    withSibling.ok());

            // Changed, though, and it is the terms of the run moving under it.
            Files.writeString(repository.resolve(".warden/tasks/extra.yaml"),
                    "version: 1\nid: extra\ngoal: something else\nscope: code\n");
            GateRunner.Outcome mutated = new GateRunner(runner)
                    .run(loaded, "test-config-mutated", pinned, null);
            check.eq("editing any Warden config mid-run fails closed",
                    "contract_mutated", mutated.code());
            check.contains("and the report names the exact file and what happened to it",
                    String.valueOf(mutated.data().get("configuration_changed")),
                    ".warden/tasks/extra.yaml (modified)");

            Files.delete(repository.resolve(".warden/tasks/extra.yaml"));
            GateRunner.Outcome removed = new GateRunner(runner)
                    .run(loaded, "test-config-removed", pinned, null);
            check.eq("so does deleting one", "contract_mutated", removed.code());
            check.contains("and that is reported as a removal, not a modification",
                    String.valueOf(removed.data().get("configuration_changed")), "(removed)");

            // Two questions, two scopes. A read-only role writing a task file is a violation,
            // so `.warden` is in that fingerprint. A human accepting a candidate is accepting
            // a source change, so it is not in that one — a run whose own log lived under
            // `.warden` invalidated its own acceptance the moment the log was written.
            String base = git.mergeBase("HEAD");
            String beforeSource = git.sourceFingerprint(base);
            String beforeAll = git.fingerprint(base);
            Files.writeString(repository.resolve(".warden/operator-note.txt"), "written mid-run\n");
            check.eq("a change under .warden leaves the candidate fingerprint alone",
                    beforeSource, git.sourceFingerprint(base));
            check.that("but the read-only fingerprint still sees it",
                    !beforeAll.equals(git.fingerprint(base)));
            Files.writeString(repository.resolve("src/value.txt"), "changed again\n");
            check.that("and a real source edit moves the candidate fingerprint",
                    !beforeSource.equals(git.sourceFingerprint(base)));
            Files.delete(repository.resolve(".warden/operator-note.txt"));
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");

            // The same question for a *tracked* file under `.warden`, which is the case that
            // actually broke. The check above passed for the wrong reason: an untracked file
            // never appears in `git diff --raw` at all, so filtering the path list was enough
            // for it. A tracked one does appear, the raw shape was not filtered, and so adding
            // the `land:` block that `warden land` itself asks for when it has nowhere to push
            // made that same command refuse the accepted run as `candidate_changed`.
            Files.writeString(repository.resolve(".warden/land-note.txt"), "committed config\n");
            command(repository, "git", "add", "--", ".warden/land-note.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "a tracked file under .warden");
            String beforeTracked = git.sourceFingerprint(base);
            String beforeTrackedAll = git.fingerprint(base);
            Files.writeString(repository.resolve(".warden/land-note.txt"),
                    "land:\n  remote: origin\n  base: main\n");
            check.eq("editing a tracked file under .warden leaves the candidate fingerprint alone",
                    beforeTracked, git.sourceFingerprint(base));
            check.that("while the read-only fingerprint still sees that edit",
                    !beforeTrackedAll.equals(git.fingerprint(base)));
            Files.writeString(repository.resolve(".warden/land-note.txt"), "committed config\n");

            // Committing the accepted change must not invalidate the acceptance. The candidate
            // is the content, not where it is sitting: measured on run torch-2, where
            // `warden land --commit` made the commit and `warden land --push` then refused it
            // as `candidate_changed`, because `git diff --raw` reports a zeroed destination
            // blob while a change is uncommitted and a real one once it is not.
            String beforeCommit = git.sourceFingerprint(base);
            command(repository, "git", "add", "--", "src/value.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "land the accepted change");
            check.eq("committing the accepted change leaves the candidate fingerprint alone",
                    beforeCommit, git.sourceFingerprint(base));

            // The raw line is still there for a reason: a deletion has no bytes to hash, so
            // only the status letter can carry it.
            Files.delete(repository.resolve("src/value.txt"));
            check.that("while deleting the file still moves it",
                    !beforeCommit.equals(git.sourceFingerprint(base)));
            // Put it back exactly as committed, so the fingerprint returns to where it was —
            // which is the other half of the property: content decides, nothing else.
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");
            check.eq("and restoring the same bytes brings it back",
                    beforeCommit, git.sourceFingerprint(base));

            ConfigLoader.Loaded visual = new ConfigLoader().load(repository, "needs-eyes");
            GateRunner.Outcome eyes = new GateRunner(runner).run(visual, "test-visual");
            check.that("machine gates no longer stand in for visual QA",
                    !"visual_qa_unavailable".equals(eyes.code()));

            Files.writeString(repository.resolve("outside.txt"), "outside\n");
            GateRunner.Outcome refused = new GateRunner(runner).run(loaded, "test-refuse");
            check.eq("preflight fails closed outside scope", "preflight_outside_scope", refused.code());
            check.that("failure report is still written", Files.isRegularFile(refused.report()));
        } finally {
            deleteTree(repository);
        }
    }

    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("warden-runtime-");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.writeString(root.resolve("src/value.txt"), "initial\n");
        Files.writeString(root.resolve(".warden/project.yaml"), """
                version: 1
                project: fixture
                base_ref: HEAD
                checks:
                  baseline: ["echo baseline-ok"]
                  fast: ["echo gate-ok"]
                scopes:
                  code: ["src"]
                defaults:
                  checks: fast
                  baseline_checks: baseline
                  risk: medium
                  max_fix_attempts: 2
                  timeout_minutes: 1
                """);
        Files.writeString(root.resolve(".warden/tasks/smoke.yaml"), """
                version: 1
                id: smoke
                goal: exercise machine gates
                non_goals: ["no external effects"]
                scope: code
                authority:
                  workspace_write: true
                  network: false
                  land: false
                visual_qa:
                  required: false
                budgets:
                  max_role_runs: 3
                  max_cost_usd: 1.0
                """);
        Files.writeString(root.resolve(".warden/tasks/needs-eyes.yaml"), """
                version: 1
                id: needs-eyes
                goal: a task that requires visual QA
                scope: code
                visual_qa:
                  required: true
                  scenarios: ["1280x720: testid=main-menu visible"]
                """);
        command(root, "git", "init", "-b", "main");
        command(root, "git", "add", ".");
        command(root, "git", "-c", "user.name=Warden Tests", "-c", "user.email=warden@example.invalid",
                "commit", "-m", "fixture");
        return root;
    }

    private static void command(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + ": " + output);
    }

    private static List<String> shell(String command) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    private static String longOutput() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "for /L %i in (1,1,100) do @echo 0123456789"
                : "yes 0123456789 | head -100";
    }

    private static String slowCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "ping -n 5 127.0.0.1 >nul"
                : "sleep 5";
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                Files.deleteIfExists(path);
            }
        }
    }
}
