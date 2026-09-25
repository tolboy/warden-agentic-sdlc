package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.execution.orca.OrcaWorkspace;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.DoCommand;
import dev.warden.testing.Check;
import dev.warden.testing.FakeOrca;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A continuation, and a fresh watched run, started through `Main` under Orca.
 *
 * Measured 2026-09-24 on Orca 1.4.209: in an Orca worktree every `warden run --continue` and
 * every fresh `warden run --watch` stopped at once with `run_id_exists`, and a Retry answered
 * on the decision page with `advance_start_failed`. The loop wrote `orca.json` into the new
 * run's directory — the chain's inherited Run, the watch tab's handle — before it reserved
 * that directory, and the reservation found the file and refused. The suites that covered
 * each half called the lifecycle and the board directly, where there is no reservation to
 * come second; this one starts the run the way the operator does.
 *
 * Every run here stops at the first preflight, on a file changed outside the task's scope,
 * so nothing is dispatched: reaching that stop is what proves the reservation was passed.
 */
public final class RunStartUnderOrcaTest implements Suite {

    @Override public String name() { return "run-start-under-orca"; }

    private static final String STOP = "preflight_outside_scope";

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-run-start-");
        try {
            Path home = Files.createDirectories(sandbox.resolve("home"));
            Path root = project(sandbox.resolve("project"));
            stoppedRun(root, "chain-1", "orca-run-chain");
            aContinuationStarts(check, sandbox, root, home);
            aFreshWatchedRunStarts(check, sandbox, root, home);
            aRefusedDuplicateOpensNoTab(check, sandbox, root, home);
            stoppedRun(root, "page-1", "orca-run-page");
            aRetryAnsweredOnThePageStarts(check, root, home);
            aWatchedDoWithoutPreparationStarts(check, root, home);
        } finally {
            deleteTree(sandbox);
        }
    }

    /** `warden run --continue`, with and without a live view: the chain keeps its Run. */
    private void aContinuationStarts(Check check, Path sandbox, Path root, Path home) throws Exception {
        Cli plain = warden(sandbox, root, home, "run", "hello", "--run-id", "chain-2",
                "--continue", "chain-1", "--prepare", "off",
                "--no-wait-for-gate", "--no-orca-gate", "--quiet");
        check.eq("a continuation is not refused as a duplicate of itself: " + plain, STOP,
                plain.result().get("reason"));
        check.that("it reserved its own directory", reserved(root, "chain-2"));
        check.eq("and joined the Run of the run it continues", "orca-run-chain",
                lifecycle(root, "chain-2").orcaRunId());

        Cli watched = warden(sandbox, root, home, "run", "hello", "--run-id", "chain-3",
                "--continue", "chain-1", "--prepare", "off", "--watch",
                "--no-wait-for-gate", "--no-orca-gate", "--quiet");
        check.eq("a watched continuation starts too: " + watched, STOP, watched.result().get("reason"));
        check.eq("on the chain's Run", "orca-run-chain", lifecycle(root, "chain-3").orcaRunId());
        check.eq("with its tab on record for the process that answers it", "terminal-1",
                lifecycle(root, "chain-3").watchTerminal());
        check.eq("and one tab opened", 1, watched.calls("terminal create").size());
    }

    /** A first run with `--watch` records its tab and is not refused for having one. */
    private void aFreshWatchedRunStarts(Check check, Path sandbox, Path root, Path home) throws Exception {
        Cli fresh = warden(sandbox, root, home, "run", "hello", "--run-id", "fresh-1",
                "--prepare", "off", "--watch", "--no-wait-for-gate", "--no-orca-gate", "--quiet");
        check.eq("a fresh watched run starts: " + fresh, STOP, fresh.result().get("reason"));
        check.that("it reserved its directory", reserved(root, "fresh-1"));
        check.eq("and recorded the tab it opened", "terminal-1", lifecycle(root, "fresh-1").watchTerminal());
    }

    /**
     * The same run id again. It is refused, as it was before; what changed is that the tab
     * now opens after the reservation, so the refused process neither opens one nor replaces
     * the handle of the run it duplicated.
     */
    private void aRefusedDuplicateOpensNoTab(Check check, Path sandbox, Path root, Path home) throws Exception {
        Path record = root.resolve(".warden/runs/fresh-1").resolve(OrcaLifecycle.FILE_NAME);
        String before = Files.readString(record);
        Cli again = warden(sandbox, root, home, "run", "hello", "--run-id", "fresh-1",
                "--prepare", "off", "--watch", "--no-wait-for-gate", "--no-orca-gate", "--quiet");
        check.eq("a second controller for one run id is still refused: " + again, "run_id_exists",
                again.result().get("reason"));
        check.eq("without opening a tab", 0, again.calls("terminal create").size());
        check.eq("or touching the first run's record", before, Files.readString(record));
    }

    /**
     * Retry pressed on the decision page: `startAdvance` is what the supervisor calls with the
     * answer, and it was stubbed in every suite that exercised the page.
     */
    private void aRetryAnsweredOnThePageStarts(Check check, Path root, Path home) throws Exception {
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision pending = store.read("page-1");
        HumanDecision retry = store.resolve("page-1", pending.updatedAt().toString(), "retry",
                "orca-decision-page", "");
        FakeOrca orca = new FakeOrca(root);
        var before = Main.boards;
        Main.boards = worktree -> OrcaWorkspace.attach(orca.client(), worktree);
        Map<String, Object> started;
        try {
            Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis, millis -> { },
                    (path, gate) -> { throw new IllegalStateException("no gate is read here"); }, home);
            started = Main.startAdvance(root, retry,
                    new String[] {"--watch", "--no-orca-gate", "--quiet"}, env);
        } finally {
            Main.boards = before;
        }
        check.eq("the answer starts the next run: " + started, true, started.get("started"));
        check.eq("which is not refused as a duplicate of itself", null, started.get("code"));
        check.eq("and runs to its first stop", STOP, started.get("reason"));
        check.eq("under the next run id", "page-2", started.get("run_id"));
        check.eq("on the chain's Run", "orca-run-page", lifecycle(root, "page-2").orcaRunId());
        check.eq("with the tab it opened on record", "terminal-1", lifecycle(root, "page-2").watchTerminal());
        check.eq("and one tab opened", 1, orca.calls("terminal create").size());
    }

    /**
     * `warden do` survived the live run only because a planner reserved the run id before the
     * tab opened. With `--prepare off` nothing reserves first, and it met the same refusal.
     */
    private void aWatchedDoWithoutPreparationStarts(Check check, Path root, Path home) throws Exception {
        FakeOrca orca = new FakeOrca(root);
        DoCommand.Outcome done = new DoCommand(new ProcessRunner(), dev.warden.run.Progress.SILENT,
                worktree -> OrcaWorkspace.attach(orca.client(), worktree), true, false)
                .run(new DoCommand.Options(root, "Toggle the greeting", "code", "low", "do-hello",
                        "do-1", "HEAD", true, false, false, false, false, false, null),
                        dev.warden.config.UserConfig.load(home));
        check.eq("a watched `do` that prepares nothing starts: " + done.report(), STOP, done.code());
        check.that("it reserved its directory", reserved(root, "do-1"));
        check.eq("and recorded the tab it opened", "terminal-1", lifecycle(root, "do-1").watchTerminal());
    }

    /** A run that stopped for a reason a Retry is offered for, on an Orca Run of its own. */
    private static void stoppedRun(Path root, String runId, String orcaRunId) throws Exception {
        Path summary = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        Files.createDirectories(summary.getParent());
        Files.writeString(summary, Json.write(Map.of("run_id", runId, "task_id", "hello",
                "reason", "orca_worker_not_started", "decision_kind", "failure",
                "steps", List.of(Map.of("step", "implementer", "stage", "implement", "ok", false,
                        "code", "orca_worker_not_started")))));
        new OrcaLifecycle(root, runId).mutate(OrcaLifecycle.of(s -> s.withOrcaRunId(orcaRunId)));
        new ApprovalStore(root).createFailure(runId, "hello", "orca_worker_not_started", summary, null);
        if (runId.equals("chain-1")) {
            HumanDecision pending = new ApprovalStore(root).read(runId);
            new ApprovalStore(root).resolve(runId, pending.updatedAt().toString(), "retry", "tester", "");
        }
    }

    private static Path project(Path root) throws Exception {
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".warden/project.yaml"), """
                version: 1
                project: run-start
                base_ref: HEAD
                checks: { fast: [check] }
                scopes: { code: [src] }
                """);
        Files.writeString(root.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Toggle the greeting
                risk: low
                scope: [src]
                acceptance: [check]
                """);
        Files.writeString(root.resolve("src/greeting.txt"), "hello\n");
        ProcessRunner processes = new ProcessRunner();
        for (List<String> command : List.of(List.of("git", "init", "-q", "-b", "main"),
                List.of("git", "add", "-A"),
                List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "-qm", "fixture"))) {
            if (!processes.run(command, root, Duration.ofSeconds(60)).ok()) {
                throw new IllegalStateException("git fixture failed: " + command);
            }
        }
        // Outside `scope: [src]`, so every run stops before its first dispatch.
        Files.writeString(root.resolve("stray.txt"), "left over\n");
        return root;
    }

    private static boolean reserved(Path root, String runId) {
        return Files.isRegularFile(root.resolve(".warden/runs").resolve(runId).resolve("run.json"));
    }

    private static OrcaLifecycle.Snapshot lifecycle(Path root, String runId) throws Exception {
        return new OrcaLifecycle(root, runId).read();
    }

    /** One `warden` process in {@code cwd}, and the calls its fake Orca was asked to make. */
    private record Cli(int exit, String stdout, String stderr, List<List<String>> orcaCalls) {
        Map<String, Object> result() {
            String json = stdout.strip();
            return json.startsWith("{") ? Json.parseObject(json) : Map.of();
        }

        List<List<String>> calls(String verb) {
            List<List<String>> matching = new ArrayList<>();
            for (List<String> call : orcaCalls) {
                if (String.join(" ", call.subList(0, Math.min(2, call.size()))).equals(verb)) matching.add(call);
            }
            return matching;
        }

        @Override public String toString() {
            return "exit=" + exit + " stdout=" + stdout.strip() + " stderr=" + stderr.strip();
        }
    }

    private static Cli warden(Path sandbox, Path cwd, Path home, String... args) throws Exception {
        Path log = Files.createTempFile(sandbox, "orca-calls-", ".json");
        Path stderr = Files.createTempFile(sandbox, "stderr-", ".txt");
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-cp", absoluteClassPath(),
                UnderFakeOrca.class.getName(), log.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectError(stderr.toFile());
        builder.environment().put("WARDEN_CONFIG_HOME", home.toAbsolutePath().normalize().toString());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("warden " + String.join(" ", args) + " timed out");
        }
        List<List<String>> calls = new ArrayList<>();
        String recorded = Files.readString(log).strip();
        if (!recorded.isEmpty() && Json.parse(recorded) instanceof List<?> made) {
            for (Object call : made) {
                calls.add(((List<?>) call).stream().map(String::valueOf).toList());
            }
        }
        return new Cli(process.exitValue(), stdout, Files.readString(stderr), calls);
    }

    /**
     * `warden` on a board that is a {@link FakeOrca}, so a run in a plain temp directory
     * takes the path an Orca worktree takes. The calls it made go to the file named first,
     * on the way out, because {@link Main#main} leaves through {@code System.exit}.
     */
    public static final class UnderFakeOrca {
        public static void main(String[] args) {
            Path log = Path.of(args[0]);
            FakeOrca orca = new FakeOrca(Path.of("").toAbsolutePath());
            Main.boards = worktree -> OrcaWorkspace.attach(orca.client(), worktree);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(log, Json.write(orca.calls()));
                } catch (Exception lost) {
                    // The suite reads an empty log as no calls, and says so in its checks.
                }
            }));
            Main.main(Arrays.copyOfRange(args, 1, args.length));
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static String absoluteClassPath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            }
        }
    }
}
