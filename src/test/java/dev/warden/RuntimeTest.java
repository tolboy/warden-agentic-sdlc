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
                  fast: ["echo gate-ok"]
                scopes:
                  code: ["src"]
                defaults:
                  checks: fast
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
                  scenarios: ["main-menu"]
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
