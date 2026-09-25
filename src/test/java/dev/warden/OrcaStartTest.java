package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.execution.RoleExecutor;
import dev.warden.execution.orca.OrcaExecutor;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.FakeOrca;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A worker Orca could not see start is not a worker that failed to start.
 *
 * Measured 2026-09-24 on Orca 1.4.209: worker-start typed the task, watched 30 s for the
 * agent's turn, and answered `outcome_unknown` / `turn_unobserved`, "unverifiable, not proof
 * the worker is dead". Warden fenced the worker on that receipt: a Codex reviewer once and a
 * Claude look twice, 47-57 s in, on screens with nothing on them to answer, and each fenced
 * start was charged against `max_role_runs`. The only start screens worth fencing on are the
 * first-run questions Warden can name, and a worker fenced on one never reached a model.
 */
public final class OrcaStartTest implements Suite {

    @Override public String name() { return "orca-start"; }

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
            limits: { wall_clock_minutes: 1 }
            """;

    /** Claude Code's once-per-repository question, as Orca 1.4.207 previewed it. */
    private static final String TRUST_SCREEN = "one you trust? (Like your own code, a well-known "
            + "open source\nproject, or work from your team).\nSecurity guide\n1. Yes, proceed\n2. No, exit";
    /** Codex 0.156's update screen, which a worker cannot get past on its own. */
    private static final String UPDATE_SCREEN = "Update available! 0.154.0 -> 0.156.1\n"
            + "1. Update now (runs `npm install -g @openai/codex`)\n2. Skip\n3. Skip until next version";
    private static final String QUIET_SCREEN = "Claude Code v2.1.280\n=== TASK ===\n> ";

    private static final Map<String, Object> REVIEW = Map.of("warden_artifact", Map.of(
            "role", "reviewer", "task_id", "task", "status", "completed", "verdict", "pass",
            "summary", "reviewed", "findings", List.of()));

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-orca-start-");
        try {
            gitRepo(root);
            aTurnOrcaCouldNotSeeIsWaitedFor(check, root);
            aWorkerThatOnlyReportsHasStillStarted(check, root);
            aFirstRunQuestionAtStartIsFencedAtOnce(check, root);
            aFirstRunQuestionWhileWaitingIsFenced(check, root);
            aWorkingAgentsScreenIsItsWork(check, root);
            aTurnObservedOnTheFirstScreenIsKept(check, root);
            aQuestionProvesTheTurnBeforeTheNextPoll(check, root);
            aStartThatFailedOutrightIsStillFenced(check, root);
            theBudgetGivesBackOnlyAProvenNonStart(check, root.resolve("budget"));
        } finally {
            deleteTree(root);
        }
    }

    private void aTurnOrcaCouldNotSeeIsWaitedFor(Check check, Path root) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED);
        orca.showing(() -> checks.get() < 2
                ? Map.of("state", "start_unknown", "screen", QUIET_SCREEN)
                : Map.of("state", "start_unknown", "screen", "Reading index.html",
                        "heartbeat", "2026-09-24T15:46:40Z"));
        orca.answering(args -> checks.incrementAndGet() < 3 ? null
                : FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), REVIEW));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "unseen-1"));

        check.eq("a start Orca could not see is waited on, and its worker_done accepted",
                "ok", result.code());
        check.eq("nothing was fenced", 0, orca.calls("orchestration worker-stop").size());
        check.eq("Orca's receipt is kept", "outcome_unknown", result.evidence().get("worker_start_state"));
        check.eq("the turn is recorded as seen once it was", "observed",
                result.evidence().get("worker_turn"));
        check.eq("by the agent's own heartbeat", "heartbeat",
                result.evidence().get("worker_turn_observed_by"));
        check.that("and the call is charged like any other",
                !result.evidence().containsKey("vendor_turn_started"));
        check.eq("the worker is released as settled", 1,
                orca.calls("orchestration worker-release").size());
        check.eq("which the lifecycle records", OrcaLifecycle.State.SETTLED, new OrcaLifecycle(root, "unseen-1")
                .read().worker(OrcaLifecycle.workerKey("reviewer", "unseen-1")).orElseThrow().state());
    }

    /** No heartbeat, no status: the worker_done is itself the proof that the turn ran. */
    private void aWorkerThatOnlyReportsHasStillStarted(Check check, Path root) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED)
                .showing(() -> Map.of("state", "start_unknown", "screen", QUIET_SCREEN));
        orca.answering(args -> checks.incrementAndGet() < 2 ? null
                : FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), REVIEW));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "unseen-2"));

        check.eq("a worker Orca never saw working still settles by reporting", "ok", result.code());
        check.eq("and its report is what proves the turn", "worker_done",
                result.evidence().get("worker_turn_observed_by"));
        check.eq("nothing was fenced", 0, orca.calls("orchestration worker-stop").size());
    }

    private void aFirstRunQuestionAtStartIsFencedAtOnce(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED)
                .showing(() -> Map.of("state", "start_unknown", "screen", TRUST_SCREEN));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "trust-1"));

        check.eq("a trust question on the start screen is still a failed start",
                "role_orca_start_failed", result.code());
        check.eq("named for the operator", "folder_trust", result.evidence().get("agent_blocked_on"));
        check.eq("fenced at once", 1, orca.calls("orchestration worker-stop").size());
        check.eq("without waiting for a report", 0, orca.calls("orchestration check").size());
        check.eq("and not charged, because no model was asked anything", false,
                result.evidence().get("vendor_turn_started"));
        check.eq("the lifecycle records the fence", OrcaLifecycle.State.STOPPED, new OrcaLifecycle(root, "trust-1")
                .read().worker(OrcaLifecycle.workerKey("reviewer", "trust-1")).orElseThrow().state());
    }

    /** The update screen can come up after the 30 s Orca watched; the wait keeps reading it. */
    private void aFirstRunQuestionWhileWaitingIsFenced(Check check, Path root) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED);
        orca.showing(() -> Map.of("state", "start_unknown",
                "screen", checks.get() < 2 ? QUIET_SCREEN : UPDATE_SCREEN));
        orca.answering(args -> {
            checks.incrementAndGet();
            return null;
        });
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "update-1"));

        check.eq("an update prompt that shows up while waiting fails the start",
                "role_orca_start_failed", result.code());
        check.eq("named for the operator", "cli_update_prompt", result.evidence().get("agent_blocked_on"));
        check.eq("on the poll that first saw it", 2, orca.calls("orchestration check").size());
        check.eq("fenced once", 1, orca.calls("orchestration worker-stop").size());
        check.eq("not released as if it had settled", 0, orca.calls("orchestration worker-release").size());
        check.eq("not charged", false, result.evidence().get("vendor_turn_started"));
        check.contains("with the screen that says why", String.valueOf(result.evidence().get("agent_screen_tail")),
                "Skip until next version");
        check.eq("the lifecycle records the fence", OrcaLifecycle.State.STOPPED, new OrcaLifecycle(root, "update-1")
                .read().worker(OrcaLifecycle.workerKey("reviewer", "update-1")).orElseThrow().state());
    }

    /** Once the agent is seen working, what its screen shows is the work, not a prompt. */
    private void aWorkingAgentsScreenIsItsWork(Check check, Path root) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED);
        orca.showing(() -> Map.of("state", "start_unknown", "activity", "working",
                "screen", checks.get() < 1 ? QUIET_SCREEN : "CHANGELOG.md: Update available! Update now"));
        orca.answering(args -> checks.incrementAndGet() < 3 ? null
                : FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), REVIEW));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "working-1"));

        check.eq("a working agent reading about an update is not fenced", "ok", result.code());
        check.eq("seen through Orca's own status", "agent_working",
                result.evidence().get("worker_turn_observed_by"));
        check.eq("no stop was sent", 0, orca.calls("orchestration worker-stop").size());
        check.that("and nothing was named as blocking it",
                !result.evidence().containsKey("agent_blocked_on"));
    }

    private void aStartThatFailedOutrightIsStillFenced(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.FAILED)
                .showing(() -> Map.of("state", "failed", "screen", QUIET_SCREEN));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "failed-1"));

        check.eq("a start Orca gave up on still fails", "role_orca_start_failed", result.code());
        check.eq("and is fenced at once", 1, orca.calls("orchestration worker-stop").size());
        check.that("no wait on the unobserved-start path",
                !result.evidence().containsKey("worker_turn"));
        check.that("and stays charged: nothing proves the agent never took the task",
                !result.evidence().containsKey("vendor_turn_started"));
    }

    private void aTurnObservedOnTheFirstScreenIsKept(Check check, Path root) throws Exception {
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED)
                .showing(() -> Map.of("state", "start_unknown", "activity", "working",
                        "screen", "CHANGELOG.md: Update available! Update now"));
        orca.answering(args -> FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), REVIEW));
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "working-first"));
        check.eq("the initial observation of a working agent outranks screen text", "ok", result.code());
        check.eq("the initial observation records the turn", "agent_working",
                result.evidence().get("worker_turn_observed_by"));
        check.eq("a working agent is not stopped at the first screen", 0,
                orca.calls("orchestration worker-stop").size());
        check.that("its vendor call is not refunded", !Boolean.FALSE.equals(
                result.evidence().get("vendor_turn_started")));
    }

    private void aQuestionProvesTheTurnBeforeTheNextPoll(Check check, Path root) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        FakeOrca orca = new FakeOrca(root).starting(FakeOrca.Start.TURN_UNOBSERVED);
        orca.showing(() -> Map.of("state", "start_unknown", "screen", checks.get() == 0
                ? QUIET_SCREEN : "CHANGELOG.md: Update available! Update now"));
        orca.answering(args -> switch (checks.incrementAndGet()) {
            case 1 -> Map.of("delivery", Map.of("id", "question-delivery", "messages", List.of(Map.of(
                    "id", "question-message", "type", "question", "taskId", orca.lastTask(),
                    "dispatchId", orca.lastDispatch(), "payload", Map.of("question", "Which file?")))));
            case 2 -> null;
            default -> FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), REVIEW);
        });
        RoleExecutor.Result result = executor(orca, root).execute(request(root, "question-first"));
        check.eq("a question proves the turn while the settlement wait is still running", "ok", result.code());
        check.eq("the question is kept as the first proof", "question",
                result.evidence().get("worker_turn_observed_by"));
        check.eq("later screen text cannot fence an agent that asked a question", 0,
                orca.calls("orchestration worker-stop").size());
    }

    /**
     * The loop's side: {@code max_role_runs} counts vendor calls, and a worker fenced on a
     * first-run question was not one. A start that failed for a reason Warden cannot see into
     * still is, because it may have been served.
     */
    private void theBudgetGivesBackOnlyAProvenNonStart(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("home");
        Path project = sandbox.resolve("project");
        new UserSetup().run(home);
        Files.writeString(home.resolve("profiles/orca-review.yaml"), """
                version: 1
                profile: orca-review
                role: reviewer
                vendor: claude
                command: claude
                model: opus
                effort: max
                runner: orca
                prompt_delivery: workspace_file
                read_only: true
                limits: { wall_clock_minutes: 1 }
                prompt_template: prompts/reviewer.md
                json_schema: schemas/reviewer.json
                artifact:
                  required_fields: [role, task_id, status, verdict, summary, findings]
                verification:
                  verified_on: "2026-09-24"
                """);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  reviewer: { profiles: [orca-review], strategy: first, require_independent_vendor: false }
                  implementer: { profiles: [codex-implement], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                """);
        scaffoldProject(project);

        FakeOrca blocked = new FakeOrca(project).starting(FakeOrca.Start.TURN_UNOBSERVED)
                .showing(() -> Map.of("state", "start_unknown", "screen", TRUST_SCREEN));
        CountingGate given = new CountingGate();
        RoleRunner.Outcome fenced = runner(given, blocked).run(new ConfigLoader().load(project, "hello"),
                UserConfig.load(home), "reviewer", "budget-1", null, null, false);
        check.eq("the role still fails as a start that did not happen", "role_orca_start_failed", fenced.code());
        check.eq("the call was admitted", 1, given.admitted);
        check.eq("and given back", 1, given.released);
        check.eq("never settled as a paid call", 0, given.settled);
        Map<String, Object> report = fenced.details();
        check.eq("the report says it was not charged", false, report.get("budget_charged"));
        List<?> attempts = (List<?>) report.get("vendor_attempts");
        check.eq("the attempt is still recorded", 1, attempts.size());
        check.eq("marked uncharged", false, ((Map<?, ?>) attempts.get(0)).get("budget_charged"));

        FakeOrca stalled = new FakeOrca(project).starting(FakeOrca.Start.FAILED)
                .showing(() -> Map.of("state", "failed", "screen", QUIET_SCREEN));
        CountingGate kept = new CountingGate();
        RoleRunner.Outcome failed = runner(kept, stalled).run(new ConfigLoader().load(project, "hello"),
                UserConfig.load(home), "reviewer", "budget-2", null, null, false);
        check.eq("a start that failed for no visible reason fails the same way",
                "role_orca_start_failed", failed.code());
        check.eq("but stays charged", 1, kept.settled);
        check.eq("with nothing given back", 0, kept.released);
    }

    private static RoleRunner runner(RoleRunner.DispatchGate gate, FakeOrca orca) {
        return new RoleRunner(new ProcessRunner(), gate)
                .availability(profile -> true)
                .withExecutors((profile, processes, git) -> new OrcaExecutor(orca.client(), git));
    }

    private static final class CountingGate implements RoleRunner.DispatchGate {
        int admitted;
        int settled;
        int released;

        @Override public void requireDispatch() { admitted++; }

        @Override public void settleAttempt(Object costUsd, Double declaredBound) { settled++; }

        @Override public void release() { released++; }
    }

    private static OrcaExecutor executor(FakeOrca orca, Path root) {
        return new OrcaExecutor(orca.client(), new GitRepository(root, new ProcessRunner()));
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

    private static void scaffoldProject(Path project) throws Exception {
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: demo
                base_ref: HEAD
                checks:
                  fast: ["git --version"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """);
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt containing the word ok
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                """);
        Files.writeString(project.resolve("README.md"), "seed\n");
        gitRepo(project, ".warden/runs/\n");
    }

    private static void gitRepo(Path root) throws Exception {
        gitRepo(root, ".warden/\nbudget/\n");
    }

    private static void gitRepo(Path root, String ignored) throws Exception {
        ProcessRunner processes = new ProcessRunner();
        Files.writeString(root.resolve(".gitignore"), ignored);
        for (List<String> command : List.of(List.of("git", "init", "-q"),
                List.of("git", "add", "-A"),
                List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-qm", "fixture"))) {
            if (!processes.run(command, root, Duration.ofSeconds(30)).ok()) {
                throw new IllegalStateException("git fixture failed: " + command);
            }
        }
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
