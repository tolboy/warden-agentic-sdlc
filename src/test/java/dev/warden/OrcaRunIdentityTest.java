package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.execution.RoleExecutor;
import dev.warden.execution.orca.OrcaDecisionGate;
import dev.warden.execution.orca.OrcaExecutor;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.execution.orca.OrcaWorkspace;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;
import dev.warden.run.Workspace;
import dev.warden.testing.Check;
import dev.warden.testing.FakeOrca;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One Warden run, one Orca Run, and a tab that stops asking once it is answered.
 *
 * The narration board kept the Run it made in a field; the executor and the gate each found
 * or made one through the lifecycle file. A `--watch` run therefore put its stage rows in one
 * Run and its workers and gate in another, and a person reading either saw part of the
 * history. The watch tab's handle lived in the same object, so `warden approve` — a different
 * process — found no tab to rename, and the tab said NEEDS YOU after the answer.
 */
public final class OrcaRunIdentityTest implements Suite {

    @Override public String name() { return "orca-run-identity"; }

    private static final String PROFILE = """
            version: 1
            profile: orca-review
            role: reviewer
            vendor: claude
            command: claude
            model: opus
            effort: max
            runner: orca
            read_only: true
            """;

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-orca-run-");
        try {
            gitRepo(root);
            boardExecutorAndGateShareOneRun(check, root);
            theExecutorFirstIsTheSameRun(check, root);
            aContinuationKeepsTheChainsRun(check, root);
            anAnswerRenamesTheTabFromAnotherProcess(check, root);
            aStageTheRunAbandonsIsClosedAsFailed(check, root);
        } finally {
            deleteTree(root);
        }
    }

    private void boardExecutorAndGateShareOneRun(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root);
        Workspace board = OrcaWorkspace.attach(orca.client(), root);
        board.watch(root.resolve(".warden/runs/run-1/narration.log"), "run-1");
        board.stage("[1/3] review");

        orca.answering(args -> FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(),
                Map.of("warden_artifact", Map.of("role", "reviewer", "task_id", "task",
                        "status", "completed", "verdict", "pass", "summary", "reviewed",
                        "findings", List.of()))));
        RoleExecutor.Result reviewed = new OrcaExecutor(orca.client(), new GitRepository(root, new ProcessRunner()))
                .execute(request(root, "run-1"));
        check.eq("the worker ran", "ok", reviewed.code());
        board.stageEnded(true, "[1/3] review · ok");

        HumanDecision decision = new ApprovalStore(root).createSuccess("run-1", "task",
                "every stage that ran passed", root.resolve(".warden/runs/run-1/task-run.json"), "fingerprint");
        OrcaDecisionGate.Publication gate = new OrcaDecisionGate(orca.client())
                .publish(root, new OrcaLifecycle(root, "run-1"), decision, "review");
        check.that("the gate was published", gate.published());

        check.eq("one Run for the board, the worker and the gate", 1,
                orca.calls("orchestration run-create").size());
        check.eq("which the lifecycle records", "run-1", new OrcaLifecycle(root, "run-1").read().orcaRunId());
        for (List<String> task : orca.calls("orchestration task-create")) {
            check.eq("every task is on that Run: " + FakeOrca.option(task, "--task-title"), "run-1",
                    FakeOrca.option(task, "--run"));
        }
        check.eq("the executor bound its coordinator to it", List.of("run-1", "run-1"),
                orca.calls("orchestration run-use").stream().map(call -> FakeOrca.option(call, "--id")).toList());
        List<List<String>> settled = orca.calls("orchestration task-update");
        check.eq("the stage row is closed, once", 1, settled.size());
        check.eq("with the stage's own outcome", "completed", FakeOrca.option(settled.get(0), "--status"));
        check.eq("on the row the stage opened", "task-1", FakeOrca.option(settled.get(0), "--id"));
        check.eq("the watch tab's handle is on disk for the next process", "terminal-1",
                new OrcaLifecycle(root, "run-1").read().watchTerminal());
    }

    /** The other order: a run without `--watch` made the Run first; a board joining later uses it. */
    private void theExecutorFirstIsTheSameRun(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root);
        orca.answering(args -> FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(),
                Map.of("warden_artifact", Map.of("role", "reviewer", "task_id", "task",
                        "status", "completed", "verdict", "pass", "summary", "reviewed",
                        "findings", List.of()))));
        new OrcaExecutor(orca.client(), new GitRepository(root, new ProcessRunner())).execute(request(root, "run-2"));
        Workspace board = OrcaWorkspace.attach(orca.client(), root);
        board.watch(root.resolve(".warden/runs/run-2/narration.log"), "run-2");
        board.stage("[2/3] look");
        check.eq("a board that comes second makes no Run of its own", 1,
                orca.calls("orchestration run-create").size());
        List<String> lastTask = orca.calls("orchestration task-create").getLast();
        check.eq("and puts its row on the executor's", "run-1", FakeOrca.option(lastTask, "--run"));
    }

    /** A retry is a new Warden run id; its rows go on the Run its predecessor used. */
    private void aContinuationKeepsTheChainsRun(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root);
        new OrcaLifecycle(root, "chain-1").mutate(OrcaLifecycle.of(s -> s.withOrcaRunId("run-chain")));
        OrcaLifecycle.inheritRun(root, "chain-1", "chain-2");
        check.eq("the continuation inherits the Run", "run-chain",
                new OrcaLifecycle(root, "chain-2").read().orcaRunId());
        Workspace board = OrcaWorkspace.attach(orca.client(), root);
        board.watch(root.resolve(".warden/runs/chain-2/narration.log"), "chain-2");
        board.stage("[1/3] implement");
        check.eq("no new Run for a retry", 0, orca.calls("orchestration run-create").size());
        check.eq("its rows join the chain's", "run-chain",
                FakeOrca.option(orca.calls("orchestration task-create").getFirst(), "--run"));
        new OrcaLifecycle(root, "chain-3").mutate(OrcaLifecycle.of(s -> s.withOrcaRunId("run-own")));
        OrcaLifecycle.inheritRun(root, "chain-1", "chain-3");
        check.eq("a continuation that already has a Run keeps it", "run-own",
                new OrcaLifecycle(root, "chain-3").read().orcaRunId());
    }

    /** `warden approve` is another process: it finds the tab through the lifecycle file. */
    private void anAnswerRenamesTheTabFromAnotherProcess(Check check, Path root) throws Exception {
        FakeOrca asking = new FakeOrca(root);
        Workspace first = OrcaWorkspace.attach(asking.client(), root);
        first.watch(root.resolve(".warden/runs/run-4/narration.log"), "run-4");
        first.state(Workspace.State.WAITING_FOR_HUMAN);
        String tab = new OrcaLifecycle(root, "run-4").read().watchTerminal();

        FakeOrca answering = new FakeOrca(root);
        Workspace approve = OrcaWorkspace.attach(answering.client(), root);
        approve.answered("retry");
        check.eq("a board that has not adopted the run renames nothing", 0,
                answering.calls("terminal rename").size());
        approve.adopt("run-4");
        approve.answered("retry");
        List<List<String>> renamed = answering.calls("terminal rename");
        check.eq("an answer renames the tab the asking process opened", tab,
                FakeOrca.option(renamed.getLast(), "--terminal"));
        check.eq("to what was decided, not NEEDS YOU", "warden run-4 - retry",
                FakeOrca.option(renamed.getLast(), "--title"));
        check.that("and does not move the card to running before a next run starts",
                answering.calls("worktree set").stream().noneMatch(call -> call.contains("in-progress")));

        FakeOrca closing = new FakeOrca(root);
        Workspace accept = OrcaWorkspace.attach(closing.client(), root);
        accept.adopt("run-4");
        accept.state(Workspace.State.SETTLED);
        check.eq("an accepted run's tab says done", "warden run-4 - done",
                FakeOrca.option(closing.calls("terminal rename").getLast(), "--title"));
    }

    /** A run that stops mid-stage closes that stage's row as failed, not leave it open. */
    private void aStageTheRunAbandonsIsClosedAsFailed(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root);
        Workspace board = OrcaWorkspace.attach(orca.client(), root);
        board.watch(root.resolve(".warden/runs/run-5/narration.log"), "run-5");
        board.stage("[1/3] implement");
        board.state(Workspace.State.WAITING_FOR_HUMAN);
        List<List<String>> settled = orca.calls("orchestration task-update");
        check.eq("the open row is closed when the run stops", 1, settled.size());
        check.eq("as failed", "failed", FakeOrca.option(settled.getFirst(), "--status"));
        board.stageEnded(true, "late");
        check.eq("and is not closed twice", 1, orca.calls("orchestration task-update").size());
    }

    private static RoleExecutor.Request request(Path root, String runId) throws Exception {
        Path run = Files.createDirectories(root.resolve(".warden/runs").resolve(runId));
        Path prompt = run.resolve("prompt.md");
        Files.writeString(prompt, "Review only; do not edit.");
        var task = new TaskSpec.ResolvedTask("task", "review", List.of(), "low", "HEAD", List.of(),
                List.of(), List.of(), null, null, null, 0, 1);
        return new RoleExecutor.Request(runId, runId, "reviewer", Profile.parse(PROFILE, "test"), task,
                "HEAD", root, run, prompt, null, "", "reviewer", List.of());
    }

    private static void gitRepo(Path root) throws Exception {
        ProcessRunner processes = new ProcessRunner();
        for (List<String> command : List.of(List.of("git", "init", "-q"),
                List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-qm", "fixture"))) {
            if (!processes.run(command, root, Duration.ofSeconds(30)).ok()) {
                throw new IllegalStateException("git fixture failed");
            }
        }
        Files.writeString(root.resolve(".gitignore"), ".warden/\n");
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
