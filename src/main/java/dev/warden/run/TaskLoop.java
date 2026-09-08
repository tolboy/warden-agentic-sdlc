package dev.warden.run;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.WardenTree;
import dev.warden.config.Workflow;
import dev.warden.execution.orca.OrcaDecisionGate;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.Findings;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleContract;
import dev.warden.role.RoleRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded loop. By default:
 *
 *   implement → machine gates → [fix ≤ N] → independent review → [fix ≤ N]
 *             → browser harness → [fix ≤ N] → visual QA role → [fix ≤ N] → stop for a human
 *
 * That chain is the built-in {@link Workflow}, not a hard-coded one: the order of stages and
 * the conditions under which each runs are declared beside the roles they dispatch, in
 * `~/.warden/policy.yaml`. This class executes whatever chain it is handed and owns the parts
 * that must not be configurable — the fix bound, the budget, and what a failure is called.
 *
 * Three properties are deliberate.
 *
 * 1. It never lands anything. The final act is a summary whose `next_action` can only be
 *    `human_gate` or `human_escalation`. Approval is the one step that treats agent output as
 *    trusted, and it stays a human act.
 * 2. Routing is code. Whether to fix again, whether to review at all, and which vendor fills a
 *    role are decided here by counters, risk and exit codes. No model is consulted.
 * 3. Every attempt leaves the exact failure text that was handed back, so a loop that went
 *    wrong is diagnosable afterwards instead of being re-run to be understood.
 *
 * Budgets are enforced before a vendor is dispatched, never after: an overrun should not be
 * discovered by paying for it. That includes the dispatches a failover adds, which is why the
 * loop hands its budget to the role runner as a gate instead of counting role invocations.
 *
 * A spent subscription is reported as its own stop reason. `implementer_failed` and
 * `quota_exhausted` demand different things from whoever reads the summary — one is a defect
 * to look at, the other resolves itself when the quota window rolls over — and collapsing them
 * into one reason sends an operator to read a transcript that has nothing wrong in it.
 */
public final class TaskLoop {

    public record Outcome(boolean ok, String reason, String nextAction, Path summary,
                          Map<String, Object> summaryReport) {}

    /**
     * The browser step, as a seam. It exists so the loop's routing — when a failing
     * screenshot goes back to the implementer, when the visual role is paid to look — can be
     * tested without a browser. The routing is the part that breaks; the CDP driver has its
     * own live proof in docs/SMOKE.md.
     */
    @FunctionalInterface
    public interface VisualCheck {
        VisualQaRunner.Outcome run(ConfigLoader.Loaded loaded, String runId) throws Exception;
    }

    private final ProcessRunner processes;
    private final VisualCheck visualCheck;
    private final Progress progress;
    private final Workspace workspace;
    private final boolean orcaGate;

    public TaskLoop(ProcessRunner processes) {
        this(processes, (loaded, runId) -> new VisualQaRunner(processes).run(loaded, runId));
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck) {
        this(processes, visualCheck, Progress.SILENT);
    }

    /**
     * The same loop, narrating itself. Not an overloaded constructor on purpose: `Progress`
     * and `VisualCheck` are both single-method interfaces, so `new TaskLoop(runner, x::y)`
     * would be ambiguous at every call site and a lambda could silently pick the wrong one.
     */
    public TaskLoop withProgress(Progress narration) {
        return new TaskLoop(processes, visualCheck, narration, workspace, orcaGate);
    }

    /**
     * The same loop, reporting to the board it is running on. Separate from the terminal
     * narration because the two carry different things: every line goes to the terminal, and
     * only what a person would act on goes to the card.
     */
    public TaskLoop withWorkspace(Workspace board) {
        return new TaskLoop(processes, visualCheck, progress, Workspace.guarded(board), orcaGate);
    }

    /**
     * Whether a pending decision is also published to Orca as a decision gate.
     *
     * Off unless a caller asks, and `warden run` and `warden do` ask, for the same reason they
     * write the card: a run that stops for a person stops wherever that person is not, and
     * inside an Orca worktree there is already a surface that reaches them. Outside one,
     * publishing finds nothing and records that it did not. The gate never decides anything —
     * see {@link dev.warden.execution.orca.OrcaDecisionGate}.
     */
    public TaskLoop withOrcaGate(boolean publish) {
        return new TaskLoop(processes, visualCheck, progress, workspace, publish);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress) {
        this(processes, visualCheck, progress, Workspace.NONE, false);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace) {
        this(processes, visualCheck, progress, workspace, false);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate) {
        this.processes = processes;
        this.visualCheck = visualCheck;
        this.progress = progress;
        this.workspace = workspace;
        this.orcaGate = orcaGate;
    }

    /**
     * What a previous run's recorded decision carries into this one.
     *
     * A rejection used to be a full stop. The reason a person typed went into `decision.json`
     * and was read by nobody: the next run started from an empty context and the implementer
     * was left to rediscover, or not, what a human had already said was wrong with its work.
     * That is the one piece of feedback in the whole loop that cost a person's attention
     * rather than a vendor call, and it was the only one thrown away.
     *
     * @param fromRunId    the run whose decision this is, named in the evidence
     * @param rejectionNote what the person said when they rejected that candidate, or null
     */
    public record Continuation(String fromRunId, String rejectionNote, boolean reuseJudgements) {
        public static final Continuation NONE = new Continuation(null, null, false);

        public Continuation(String fromRunId, String rejectionNote) {
            this(fromRunId, rejectionNote, false);
        }

        public boolean carriesRejection() {
            return rejectionNote != null && !rejectionNote.isBlank();
        }

        public boolean continuesRun() {
            return fromRunId != null && !fromRunId.isBlank();
        }
    }

    /**
     * Failures that were never about the work.
     *
     * These are the codes {@code docs/ARCHITECTURE.md} already routes away from the fix loop:
     * no implementer can install a browser or evict another project's dev server. A run that
     * stopped for one of them threw away an implementer and an independent review that had
     * both passed, and the only way forward was to pay for both again — measured at $0.34 and
     * twenty minutes for a defect that was in Warden itself.
     */
    private static final java.util.Set<String> NOT_ABOUT_THE_WORK = java.util.Set.of(
            "visual_qa_unavailable", "visual_qa_port_occupied", "preflight_outside_scope",
            // A malformed scenario usually gets fixed in the contract, which moves the
            // fingerprint and declines reuse on its own. It is listed anyway for the case
            // where the fix is in the harness rather than the contract — which is how this
            // code came to exist.
            "visual_qa_contract_invalid",
            // A vendor that stopped at its own `--max-turns` said nothing about the work. It
            // is the operator's ceiling, the fix is a number in a profile, and the next run
            // re-reads a diff no vendor has objected to. Measured: a run whose implementer
            // and machine gates had both passed lost both because its reviewer ran out of
            // turns, and the retry paid for the implementer again to reach the same tree.
            "turn_ceiling_reached",
            // Running out of room is a fact about the allowance, not about the diff. A review
            // that read this exact tree and passed it read the same bytes it would read again,
            // and the only thing that changed between the runs is a number the operator chose.
            // Nothing here is believed on that basis alone: the source fingerprint and the
            // acceptance surface are still checked, so an implementer that got halfway through
            // a repair before the budget refused it has moved the tree and declines reuse.
            "budget_exhausted",
            "budget_insufficient_to_finish");

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun)
            throws Exception {
        return run(loaded, user, runId, dryRun, Map.of());
    }

    /**
     * @param authorizedFailover role to profile substitutions a human already agreed to on an
     *                           earlier run. Empty for an ordinary run, which is why the
     *                           default policy stops and asks instead of switching.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun,
                       Map<String, String> authorizedFailover) throws Exception {
        return run(loaded, user, runId, dryRun, authorizedFailover, Continuation.NONE);
    }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun,
                       Map<String, String> authorizedFailover, Continuation carried) throws Exception {
        Path root = loaded.root();
        TaskSpec.ResolvedTask task = loaded.resolved();
        EvidenceLedger ledger = new EvidenceLedger(root, runId);
        // Reservation is the first mutation. A duplicate controller is refused before it can
        // overwrite evidence, spend a token, or start a second Orca worker in the same tree.
        ledger.reserveWorkflowRun(task.id());
        // Both values are selected before any vendor can write. HEAD and the contract files
        // are mutable names/bytes; resolving them inside each later role or gate lets a worker
        // move the baseline or rewrite its own acceptance criteria.
        GitRepository git = new GitRepository(root, processes);
        String diffBaseCommit = git.mergeBase(task.baseRef());
        // The whole `.warden` tree, not two files: a second task contract, a rewritten policy
        // or a deleted scenario file are all terms the run would then be judged by.
        Map<String, String> configSnapshot = WardenTree.snapshot(root);
        String contractHash = WardenTree.digest(configSnapshot);
        Budget budget = new Budget(task.budget().maxRoleRuns(), task.budget().maxCostUsd());
        // One runner for the whole loop: a vendor that ran out at implement time must not be
        // dispatched again at review time. The gate makes the budget count vendor calls, not
        // role invocations, so a failover cannot spend more than the task allowed.
        RoleRunner roles = new RoleRunner(processes, budget::requireRoleRun, diffBaseCommit, runId,
                authorizedFailover, progress);
        GateRunner gates = new GateRunner(processes);

        Workflow workflow = user.policy() != null ? user.policy().workflow() : Workflow.builtIn();
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        boolean reviewRequired = reviewByRisk
                && user.policy().roles().containsKey("reviewer");
        // Opt-in, and only meaningful for a task that asked for visual QA at all.
        boolean visualRoleConfigured = user.policy() != null
                && user.policy().roles().containsKey("visual_qa");
        // Built here, from the same predicate the engine routes by, and consulted before the
        // first dispatch as well as before every repair.
        CallPlan callPlan = new CallPlan(workflow,
                stage -> skipReason(stage, user, task, reviewByRisk) != null);

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", task.id());
        summary.put("diff_base_commit", diffBaseCommit);
        summary.put("contract_sha256", contractHash);
        // The narrower hash a later run compares against when deciding whether a verdict it
        // already paid for still describes this tree. See TaskSpec.ResolvedTask.
        summary.put("acceptance_sha256", WardenTree.acceptanceDigest(configSnapshot,
                root.relativize(loaded.taskFile()).toString().replace('\\', '/'),
                task.acceptanceFingerprint()));
        summary.put("risk", task.risk());
        summary.put("dry_run", dryRun);
        summary.put("max_fix_attempts", task.maxFixAttempts());
        summary.put("budget_max_role_runs", task.budget().maxRoleRuns());
        summary.put("budget_max_cost_usd", task.budget().maxCostUsd());
        summary.put("review_required", reviewRequired);
        summary.put("visual_qa_required", task.visualQa().required());
        summary.put("visual_qa_role", visualRoleConfigured);
        summary.put("baseline_required", !task.baselineCommands().isEmpty());
        summary.put("baseline_commands", task.baselineCommands());
        summary.put("workflow_declared", user.policy() != null && user.policy().workflowDeclared());
        summary.put("failover_mode", user.policy() == null ? "confirm" : user.policy().failoverMode());
        if (!authorizedFailover.isEmpty()) summary.put("authorized_failover", authorizedFailover);
        summary.put("workflow", workflow.toList());
        summary.put("budget_plan", callPlan.toMap(task.budget().maxRoleRuns(),
                user.policy() == null ? "full" : user.policy().repairReserve()));
        // Which stages this task excludes, decided before the walk rather than during it.
        //
        // `skipped_stages` is a record of what the engine reached and passed over, so a run
        // that stops early records none of the exclusions after the stop — and every one of
        // them then reads as a stage that never got its turn. On a medium-risk task with no
        // visual contract that turned two stages nobody ever intended to run into two
        // outstanding obligations in the final report.
        List<Map<String, Object>> inapplicable = new ArrayList<>();
        for (Workflow.Stage stage : workflow.stages()) {
            String why = skipReason(stage, user, task, reviewByRisk);
            if (why != null) inapplicable.add(Map.of("stage", stage.name(), "reason", why));
        }
        summary.put("inapplicable_stages", inapplicable);
        summary.put("steps", steps);

        // Asked before the first dispatch, not after it. A worktree that was already dirty
        // outside the task's scope — a setup step that touched a lockfile, a half-finished
        // edit from yesterday — is the operator's to resolve, and no implementer can resolve
        // it: those paths are outside the blast radius it is allowed to touch. Discovering
        // that at the gates stage means having paid a vendor to find out.
        List<String> alreadyOutside = git.outsideScope(
                WardenTree.sourcePaths(git.changedPaths(diffBaseCommit)), task.scopePaths());
        if (!alreadyOutside.isEmpty()) {
            summary.put("preexisting_violations", alreadyOutside);
            summary.put("resolution", "these paths were already changed before this run started "
                    + "and are outside the task's scope " + task.scopePaths() + ". Revert them, "
                    + "commit them, or widen the scope — an implementer cannot touch them.");
            if (!dryRun) return stop(ledger, summary, "preflight_outside_scope", steps, 0, budget);
            // A dry run has no candidate to protect, so this does not stop it — but it is
            // exactly what the preview is for. Measured: a fresh Orca worktree of an npm
            // project arrives with `npm install` having rewritten the lockfile, --dry-run
            // reported the whole chain resolving, and the real run refused before dispatching
            // anything. A preview that stays silent about the one thing that will stop the
            // run is worse than no preview.
            summary.put("would_stop", "preflight_outside_scope");
        }

        // The other thing that is the operator's to fix and nobody else's. A scenario that
        // states no assertion is a contract fault, and it used to be found by the browser
        // stage — after the implementer and the reviewer had been paid to produce and read a
        // candidate that could then not be looked at. Asked of the adapter itself, so the
        // grammar has one definition.
        String scenarioProblem = new VisualQaRunner(processes).contractProblem(loaded);
        if (scenarioProblem != null) {
            summary.put("resolution", scenarioProblem);
            if (!dryRun) return stop(ledger, summary, "visual_qa_contract_invalid", steps, 0, budget);
            summary.put("would_stop", "visual_qa_contract_invalid");
        }

        if (carried.continuesRun()) {
            summary.put("continued_from", carried.fromRunId());
        }
        if (carried.carriesRejection()) {
            summary.put("carries_human_rejection", true);
        }
        String candidateFingerprint = git.sourceFingerprint(diffBaseCommit);
        summary.put("candidate_fingerprint", candidateFingerprint);
        Map<String, Map<String, Object>> reusable = carried.reuseJudgements()
                ? reusableJudgements(root, carried.fromRunId(), contractHash,
                        String.valueOf(summary.get("acceptance_sha256")), candidateFingerprint,
                        workflow, user, summary)
                : Map.of();
        header(task, runId, workflow, dryRun, callPlan);
        if (carried.carriesRejection()) {
            progress.line("note  starting from the rejection recorded on " + carried.fromRunId()
                    + "; the implementer is given its reason verbatim");
            progress.blank();
        }
        BaselineReceipt baselineReceipt = !dryRun && !task.baselineCommands().isEmpty()
                && carried.continuesRun()
                ? reusableBaseline(root, carried.fromRunId(), task.id(), contractHash,
                        diffBaseCommit, candidateFingerprint, summary)
                : null;
        if (!task.baselineCommands().isEmpty()) {
            if (dryRun) {
                steps.add(Map.of("step", "baseline", "attempt", 0L, "dry_run", true));
            } else if (baselineReceipt != null) {
                summary.put("baseline_ok", true);
                summary.put("baseline_code", "reused");
                summary.put("baseline_reused_from", baselineReceipt.runId());
                summary.put("baseline_receipt_run", baselineReceipt.runId());
                summary.put("baseline_report", baselineReceipt.report().toString());
                summary.put("baseline_report_sha256", baselineReceipt.sha256());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("step", "baseline");
                entry.put("attempt", 0L);
                entry.put("ok", true);
                entry.put("code", "reused");
                entry.put("report", baselineReceipt.report().toString());
                entry.put("reused", true);
                entry.put("reused_from", baselineReceipt.runId());
                steps.add(entry);
                progress.line("base  reused the green project baseline from "
                        + baselineReceipt.runId());
                workspace.note("baseline · green on " + baselineReceipt.runId() + " · reused");
                progress.blank();
            } else {
                progress.line("base  project tests before the first vendor: "
                        + String.join(", ", task.baselineCommands()));
                workspace.note("baseline · project tests · running");
                long started = System.nanoTime();
                GateRunner.Outcome baseline;
                try (Heartbeat alive = Heartbeat.over("baseline gates", progress, workspace)) {
                    baseline = gates.runBaseline(loaded, stepRunId(runId, "baseline", 0),
                            configSnapshot, diffBaseCommit);
                }
                long millis = (System.nanoTime() - started) / 1_000_000L;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("step", "baseline");
                entry.put("attempt", 0L);
                entry.put("ok", baseline.ok());
                entry.put("code", baseline.code());
                entry.put("report", baseline.report().toString());
                steps.add(entry);
                summary.put("baseline_ok", baseline.ok());
                summary.put("baseline_code", baseline.code());
                summary.put("baseline_report", baseline.report().toString());
                summary.put("baseline_report_sha256",
                        GitRepository.contentSha256(baseline.report()));
                if (baseline.ok()) {
                    summary.put("baseline_receipt_run", runId);
                    progress.line("      ok    " + Progress.elapsed(millis));
                    workspace.note("baseline · project tests · ok · " + Progress.elapsed(millis));
                    progress.blank();
                } else {
                    progress.line("      FAILED  " + Progress.elapsed(millis) + "   "
                            + baseline.code());
                    workspace.note("baseline · project tests · failed " + baseline.code()
                            + " · no vendor dispatched");
                    summary.put("resolution", "Project checks failed before the first vendor "
                            + "dispatch. Fix the pre-existing failure or deliberately change "
                            + "the project baseline contract, then retry; no agent was asked "
                            + "to repair an unknown earlier breakage.");
                    return stop(ledger, summary, "baseline_failed", steps, 0, budget);
                }
            }
        }
        Engine engine = new Engine(loaded, user, roles, gates, ledger, runId, dryRun,
                configSnapshot, diffBaseCommit, steps, summary, budget, task, workflow, reviewByRisk,
                progress, carried, reusable, callPlan);
        try {
            engine.run();
        } catch (StopException stopped) {
            return stop(ledger, summary, stopped.reason(), steps, engine.attempt(), budget);
        } catch (Budget.ExceededException exceeded) {
            summary.put("budget_stop", exceeded.getMessage());
            summary.put("budget_limit_hit", exceeded.limit());
            return stop(ledger, summary, "budget_exhausted", steps, engine.attempt(), budget);
        } catch (Exception unexpected) {
            summary.put("unexpected_error", Map.of(
                    "type", unexpected.getClass().getName(),
                    "message", String.valueOf(unexpected.getMessage())));
            return stop(ledger, summary, "unexpected_error", steps, engine.attempt(), budget);
        }
        int attempt = engine.attempt();
        summary.putIfAbsent("skipped_stages", engine.skipped());

        if (!dryRun && !contractMatches(loaded, configSnapshot)) {
            return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
        }

        summary.put("attempts_used", (long) attempt);
        summary.put("total_cost_usd", budget.spent());
        summary.put("role_runs", (long) budget.runs());
        summary.put("unpriced_calls", (long) budget.unpriced());
        summary.put("cost_ceiling_binding", budget.unpriced() == 0);
        // Which judging stages did not see the tree as it finally stands. A workflow may
        // legitimately choose not to pay for a second review, but "every stage passed" must
        // not be allowed to mean "every stage passed something, at some point".
        List<String> stale = staleJudgements(steps);
        if (!stale.isEmpty()) summary.put("stale_judgements", stale);
        // A preview completes nothing, so it has no completion to describe. Reporting every
        // stage as outstanding would be true and useless, and would read as a warning.
        if (!dryRun) describeCompletion(summary);
        summary.put("ok", true);
        if (dryRun) {
            // A preview has not produced a candidate a human can accept. Persisting a real
            // pending decision here makes status noisy and, worse, makes a dry run look like
            // an authorization boundary was actually reached.
            summary.put("reason", "dry_run");
            summary.put("next_action", "none");
            Path file = ledger.writeReport("task-run", summary);
            ledger.append("task_dry_run", summary);
            progress.blank();
            progress.line("done  dry_run   nothing was dispatched and nothing was spent");
            // The preview's whole job is to answer "will this work" before it costs anything,
            // and the cheapest way for it to be wrong is arithmetic. A cap that cannot reach
            // the end of the declared chain is knowable here, for free, and used to be
            // discovered an hour and several vendor calls later.
            long cap = task.budget().maxRoleRuns();
            progress.line("      requested cap " + (cap > 0 ? cap + " vendor call(s)" : "none")
                    + "; a clean pass needs " + callPlan.minimumToFinish()
                    + " (" + String.join(", ", callPlan.payingStages()) + ")");
            if (cap > 0 && cap < callPlan.minimumToFinish()) {
                progress.line("      that cap cannot finish this chain even with no repairs");
            }
            for (Map<String, Object> branch : recoveryBranches(summary)) {
                progress.line("      if " + branch.get("stage") + " sends the work back: "
                        + branch.get("calls_to_repair_and_finish") + " call(s) to repair and "
                        + "finish — " + (Boolean.TRUE.equals(branch.get("reachable_under_cap"))
                            ? "affordable" : "NOT affordable under this cap"));
            }
            if (summary.get("would_stop") != null) {
                progress.line("      a real run would stop here: " + summary.get("would_stop"));
                progress.line("      " + summary.get("resolution"));
            }
            progress.blank();
            return new Outcome(true, "dry_run", "none", file, summary);
        }

        summary.put("reason", "ready_for_human");
        summary.put("next_action", "human_gate");
        Path file = ledger.writeReport("task-run", summary);
        // Named, not asserted. The chain is declared now, so "all machine, review and visual
        // gates passed" was a sentence that could describe a run in which review never ran.
        // What the human is being asked to accept is what actually executed.
        String verdict = "every stage that ran passed: " + String.join(", ", executedStages(steps));
        if (summary.get("baseline_reused_from") != null) {
            verdict += "; project baseline reused from " + summary.get("baseline_reused_from");
        }
        // The person accepting this is entitled to know which of those verdicts were reached
        // in this run and which were carried over. They are about the same bytes — that is
        // what let them be carried — but "passed" and "passed, earlier, elsewhere" are
        // different sentences and the gate should not blur them.
        if (summary.get("reused_judgements") instanceof Map<?, ?> reused) {
            verdict += ". " + reused.get("stages") + " were not re-run: they passed this exact "
                    + "tree on run " + reused.get("from") + " and neither the source nor the "
                    + "contract has changed since";
            if (summary.get("contract_change_budget_only") instanceof Map<?, ?> budgetOnly) {
                verdict += " — except its call ceiling, raised from "
                        + budgetOnly.get("prior_max_role_runs") + " to "
                        + budgetOnly.get("now_max_role_runs") + ", which is not part of what "
                        + "the work is judged by";
            }
        }
        if (!stale.isEmpty()) {
            verdict += ". WARNING: " + String.join(", ", stale) + " last judged an earlier tree; "
                    + "code changed after that and was not judged again";
        }
        // A chain can reach the human gate with stages still owed — a conditional stage that
        // ran out of room, say. The person being asked to accept is entitled to that sentence
        // rather than to a list of what happened to pass.
        if (summary.get("pending_stages") instanceof List<?> owed && !owed.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object row : owed) {
                if (row instanceof Map<?, ?> stage) names.add(String.valueOf(stage.get("stage")));
            }
            verdict += ". The declared workflow did not finish: " + String.join(", ", names)
                    + " never completed";
        }
        HumanDecision decision = new ApprovalStore(root).createSuccess(runId, task.id(),
                verdict, file, git.sourceFingerprint(diffBaseCommit));
        addDecision(summary, root, decision);
        publishGate(summary, root, decision, task.goal());
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(runId)).toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        footer(runId, "ready_for_human", "human_gate", budget, summary);
        return new Outcome(true, "ready_for_human", "human_gate", file, summary);
    }

    /**
     * Why a stage will not run at all, or null when it will.
     *
     * Static, and asked in two places on purpose. The loop asks it to decide what to execute;
     * {@link CallPlan} asks it to decide what to count. A budget plan built from a different
     * notion of which stages run than the loop uses would reserve calls for a reviewer that
     * risk had already excluded, or fail to reserve one that risk had brought back.
     */
    private static String skipReason(Workflow.Stage stage, UserConfig user,
                                     TaskSpec.ResolvedTask task, boolean reviewByRisk) {
        if (stage.kind() == Workflow.Kind.ROLE) {
            boolean configured = user.policy() != null
                    && user.policy().roles().containsKey(stage.role());
            if (!configured) return "role_not_configured";
        }
        for (String condition : stage.when()) {
            if (!holds(condition, task, reviewByRisk)) return "condition_not_met:" + condition;
        }
        return null;
    }

    private static boolean holds(String condition, TaskSpec.ResolvedTask task, boolean reviewByRisk) {
        return switch (condition) {
            case "review_required" -> reviewByRisk;
            case "visual_qa_required" -> task.visualQa().required();
            case "risk_low" -> "low".equals(task.risk());
            case "risk_medium" -> "medium".equals(task.risk());
            case "risk_high" -> "high".equals(task.risk());
            default -> true;
        };
    }

    /**
     * Gate failures an implementer is structurally unable to fix. Each names something
     * outside the work: a tree that was dirty before the run, a base ref that does not
     * resolve, configuration that moved while the run was in flight, or the gate runner
     * itself falling over.
     */
    private static final java.util.Set<String> OPERATOR_MUST_RESOLVE = java.util.Set.of(
            "preflight_outside_scope",
            "base_ref_unresolvable",
            "contract_mutated",
            "gate_interrupted",
            "gate_internal_error");

    /**
     * Role failures about the endpoint rather than about the work. A model that is not
     * serving, a bearer variable nobody exported, a 500 from the server, an envelope that is
     * not a chat completion: no implementer can fix any of them from inside the diff, and
     * handing one back spends a role run and then fails in exactly the same place. Same
     * reason {@code visual_qa_unavailable} and the {@code role_orca_*} codes are terminal.
     */
    private static final java.util.Set<String> ROLE_OPERATOR_MUST_RESOLVE = java.util.Set.of(
            "role_local_endpoint_unreachable",
            "role_local_api_key_missing",
            "role_local_http_error",
            "role_local_response_unreadable",
            "role_human_input_required");

    /** True when another implementer call cannot safely repair the role runner itself. */
    public static boolean roleFailureRequiresOperator(String code) {
        return code != null && (ROLE_OPERATOR_MUST_RESOLVE.contains(code)
                || code.startsWith("role_orca_"));
    }

    /**
     * Raised by a stage that has run out of options. It carries the reason an operator greps
     * for, and the only thing the caller does with it is stop at the human boundary —
     * threading a "should I stop now" flag through every nested loop is how one of the
     * branches quietly forgets to check it.
     */
    @SuppressWarnings("serial")
    private static final class StopException extends RuntimeException {
        private final String reason;

        StopException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() { return reason; }
    }

    /**
     * Walks the declared chain. Every routing decision here is a counter, an exit code or a
     * declared condition; nothing asks a model what to do next, and nothing lands anything.
     */
    private final class Engine {
        private final ConfigLoader.Loaded loaded;
        private final UserConfig user;
        private final RoleRunner roles;
        private final GateRunner gates;
        private final EvidenceLedger ledger;
        private final String runId;
        private final boolean dryRun;
        private final Map<String, String> contractSnapshot;
        private final String diffBaseCommit;
        private final List<Map<String, Object>> steps;
        private final Map<String, Object> summary;
        private final Budget budget;
        private final TaskSpec.ResolvedTask task;
        private final Workflow workflow;
        private final boolean reviewByRisk;
        private final Progress progress;
        private final Continuation carried;
        private final Map<String, Map<String, Object>> reusable;
        private final CallPlan callPlan;
        private boolean rejectionDelivered;

        /** The vendor that filled each role, so a later role can be required to differ. */
        private final Map<String, String> vendors = new LinkedHashMap<>();
        /** The pixels each harness stage produced, for the role stage that looks at them. */
        private final Map<String, VisualQaRunner.Outcome> harness = new LinkedHashMap<>();
        private final List<Map<String, Object>> skipped = new ArrayList<>();
        /**
         * Stages that finished, by name.
         *
         * "Every stage that ran passed" was a sentence assembled from the step rows, and step
         * rows are labelled by role: two review stages wrote the same label, and a chain that
         * reached only the first of them read afterwards as if review were done. Names are
         * unique in a workflow, so this is the one identity that cannot collapse.
         */
        private final java.util.Set<String> completed = new java.util.LinkedHashSet<>();
        /**
         * Judging stages that had passed and whose candidate a later repair then changed.
         *
         * Kept apart from "never ran": both are outstanding, and they send a reader to
         * different places. One has a verdict on disk about a tree that no longer exists.
         */
        private final java.util.Set<String> staleAfterFix = new java.util.LinkedHashSet<>();
        /**
         * Stages whose earlier verdict this run actually consumed, in the order it did.
         *
         * Recorded here rather than at pool build, because a stage that looked reusable when
         * the pool was assembled can be invalidated before its turn — an ordinary implementer
         * dispatch earlier in this same resume moves the tree out from under a later review.
         * `reused_judgements` names what was consumed, so it is written as consumption happens.
         */
        private final java.util.Set<String> reusedStages = new java.util.LinkedHashSet<>();
        /** Every judging round this run performed, per stage, with what it objected to. */
        private final List<Map<String, Object>> findingHistory = new ArrayList<>();
        /** Per judging stage: whether it ran, whether it passed, and what it still objects to. */
        private final Map<String, Map<String, Object>> coverage = new LinkedHashMap<>();
        private int attempt;
        /** The beat wrapping the dispatch that is running now; {@link Heartbeat#none()} otherwise. */
        private Heartbeat heartbeat = Heartbeat.none();
        /**
         * How the card should say what is running, once the profile behind it is known.
         *
         * A builder rather than the stage itself, because the two things that dispatch a
         * vendor say different sentences: a stage says where it is in the plan, a fix round
         * says which stage sent the work back. Null when nothing is dispatching.
         */
        private java.util.function.BinaryOperator<String> heartbeatNote;

        Engine(ConfigLoader.Loaded loaded, UserConfig user, RoleRunner roles, GateRunner gates,
               EvidenceLedger ledger, String runId, boolean dryRun,
               Map<String, String> contractSnapshot,
               String diffBaseCommit, List<Map<String, Object>> steps, Map<String, Object> summary,
               Budget budget, TaskSpec.ResolvedTask task, Workflow workflow, boolean reviewByRisk,
               Progress progress, Continuation carried,
               Map<String, Map<String, Object>> reusable, CallPlan callPlan) {
            this.callPlan = callPlan;
            this.loaded = loaded;
            this.user = user;
            this.roles = roles;
            this.gates = gates;
            this.ledger = ledger;
            this.runId = runId;
            this.dryRun = dryRun;
            this.contractSnapshot = contractSnapshot;
            this.diffBaseCommit = diffBaseCommit;
            this.steps = steps;
            this.summary = summary;
            this.budget = budget;
            this.task = task;
            this.workflow = workflow;
            this.reviewByRisk = reviewByRisk;
            this.progress = progress;
            this.carried = carried;
            this.reusable = new LinkedHashMap<>(reusable);
            roles.occupying(this::named);
        }

        int attempt() { return attempt; }

        List<Map<String, Object>> skipped() { return List.copyOf(skipped); }

        void run() throws Exception {
            List<Workflow.Stage> stages = workflow.stages();
            for (int index = 0; index < stages.size(); index++) {
                Workflow.Stage stage = stages.get(index);
                String reason = skipReason(stage);
                if (reason != null) {
                    progress.line("[" + (index + 1) + "/" + stages.size() + "] "
                            + pad(stage.name(), 10) + "skipped: " + reason);
                    skipped.add(Map.of("stage", stage.name(), "reason", reason));
                    // Written as we go: a run that stops early still owes an answer to
                    // "why did the reviewer never run", and that is the run that needs it.
                    summary.put("skipped_stages", List.copyOf(skipped));
                    continue;
                }
                runStage(stage, index);
            }
        }

        private void runStage(Workflow.Stage stage, int index) throws Exception {
            Object outcome = execute(stage);
            if (dryRun) return;
            // A role whose verdict was carried over from an earlier run on this exact tree
            // dispatched nobody, so there is no outcome to route and nothing new to object.
            if (outcome == null) { finished(stage); return; }

            while (failed(outcome) && "fix".equals(stage.onFail()) && fixable(outcome)
                    && attempt < task.maxFixAttempts()) {
                fixRound(stage, index, failureContext(stage, outcome));
                outcome = execute(stage);
            }
            if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
            if (stage.kind() != Workflow.Kind.ROLE || stage.onFindings() == null) {
                finished(stage);
                return;
            }

            // A role that succeeded can still object. Findings are the reason to run a second
            // vendor at all, so they route like any other failing check instead of being
            // printed for somebody to notice.
            RoleRunner.Outcome reviewed = (RoleRunner.Outcome) outcome;
            long blocking = recordFindings(stage, reviewed);
            while (blocking > 0 && "fix".equals(stage.onFindings()) && attempt < task.maxFixAttempts()) {
                fixRound(stage, index, reviewContext(loaded.root(), reviewed));
                outcome = execute(stage);
                if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
                reviewed = (RoleRunner.Outcome) outcome;
                blocking = recordFindings(stage, reviewed);
                // The stage has now re-read the repaired candidate, so its answer is the only
                // thing that can say whether the round achieved anything.
                requireFindingsProgress(stage);
            }
            if (blocking > 0) throw new StopException(stage.findingsReason());
            finished(stage);
        }

        /** A stage that reached the end of its own routing with nothing left outstanding. */
        private void finished(Workflow.Stage stage) {
            completed.add(stage.name());
            staleAfterFix.remove(stage.name());
            summary.put("completed_stages", List.copyOf(completed));
            summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
        }

        /**
         * A repair is about to change the candidate, so every verdict about the old one stops
         * counting as finished business.
         *
         * Marking them complete through a fix round was wrong in the direction that matters.
         * A reviewer that passed, was re-dispatched after a browser repair and came back with
         * a P1 correctly stopped the run — and the stage it had just failed was still listed
         * in `completed_stages`, absent from `pending_stages`, and therefore reported as a
         * stage that had passed. Reproduced on run `rk1`.
         *
         * The rechecked stages earn their place back by passing again. The ones the workflow
         * did not ask to recheck stay here, which is the honest answer: they judged a tree
         * that no longer exists, and `stale_judgements` has always said so separately without
         * anything acting on it.
         */
        private void candidateAboutToChange() {
            for (Workflow.Stage stage : workflow.stages()) {
                if (stage.kind() != Workflow.Kind.ROLE || stage.onFindings() == null) continue;
                if (!completed.remove(stage.name())) continue;
                staleAfterFix.add(stage.name());
                coverage.remove(stage.name());
            }
            summary.put("completed_stages", List.copyOf(completed));
            summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
            summary.put("review_coverage", List.copyOf(coverage.values()));
        }

        /**
         * One fix round: hand the exact failure back, then re-establish every earlier stage
         * that asked to be rechecked. Without the recheck a fix could satisfy the stage that
         * complained by breaking one that had already passed.
         */
        private void fixRound(Workflow.Stage stage, int index, String context) throws Exception {
            // Both halves of what a repair commits to, asked in cost order: the calls it needs
            // are arithmetic, the readers it needs cost a probe apiece.
            requireReserve(stage, index);
            requireJudgesRemain(stage, index);
            attempt++;
            // Before the dispatch, not after it. A fix that fails part way through has still
            // touched the tree, and the verdicts about the tree it started from are no more
            // current than if it had succeeded.
            candidateAboutToChange();
            progress.line("      -> fix round " + attempt + " of " + task.maxFixAttempts()
                    + ": " + stage.name() + " sends the work back to " + stage.fixWith());
            workspace.note(fixNote(stage, null, null));
            Path file = writeContext(ledger, attempt, stage.contextKind(), context);
            String role = stage.fixWith();
            // A fix round is the same vendor, the same silence and the same twenty minutes as
            // the stage that provoked it. It went without a beat in the first version of this,
            // so the one place a run is most likely to be waited on — a second implementer
            // call, after the operator has already spent half an hour — was the one place that
            // said nothing at all.
            RoleRunner.Outcome fix;
            try (Heartbeat alive = beating(role)) {
                heartbeat = alive;
                heartbeatNote = (profile, vendor) -> fixNote(stage, profile, vendor);
                fix = roles.atStage(stage.name() + "/fix").run(loaded, user, role,
                        stepRunId(runId, role, attempt), null, file, false);
            } finally {
                heartbeat = Heartbeat.none();
                heartbeatNote = null;
            }
            record(steps, budget, role, attempt, fix);
            // Same stamp as an ordinary dispatch: the tree this repair produced is the tree a
            // later resume must match before it may reuse this implementer's work.
            stampFingerprint();
            vendors.putIfAbsent(role, fix.vendor());
            if (!fix.ok()) throw new StopException(reasonFor(fix, "fix_attempt_failed"));
            requireUnchangedContract();
            for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
                if (skipReason(earlier) != null) continue;
                Object rechecked = execute(earlier);
                if (failed(rechecked)) {
                    throw new StopException(earlier.kind() == Workflow.Kind.MACHINE_GATES
                            ? "gates_not_satisfied_after_fix" : terminalReason(earlier, rechecked));
                }
                // A recheck asks whether the fix broke a verdict that had already been given,
                // and until now it only ever heard the half of that answer a process exit code
                // can carry. A reviewer that read the new diff, objected to it in a P1 and
                // returned cleanly was recorded as a passing recheck — the run paid for the
                // objection and then discarded it, and the human gate went on to say every
                // stage that ran had passed. Findings are the whole reason this stage is
                // re-dispatched; not reading them made the recheck a paid formality.
                if (earlier.kind() == Workflow.Kind.ROLE && earlier.onFindings() != null
                        && rechecked instanceof RoleRunner.Outcome verdict
                        && recordFindings(earlier, verdict) > 0) {
                    // Deliberately a stop rather than another repair. This objection was found
                    // from inside a repair round already under way for a different stage, and
                    // repairing from inside a repair is how a bounded loop stops being one.
                    // The findings are on disk and the stop names them.
                    progress.line("      the fix broke a verdict " + earlier.name()
                            + " had already given; stopping rather than repairing recursively");
                    throw new StopException(earlier.findingsReason());
                }
                // It has re-established itself against the changed candidate, so it earns back
                // the completion the fix took away. Without this, a recheck that passed left
                // its stage listed as outstanding for the rest of the run — the mirror of the
                // bug that let a failed recheck stay listed as passed.
                finished(earlier);
            }
        }

        /**
         * Stop a repair loop that is not repairing anything.
         *
         * The condition is the plan's, and both halves of it matter: the same confirmed
         * blockers came back, *and* the evidence they are about did not move. Either alone is
         * a bad signal. A repair that changes the tree and still leaves the finding open may
         * have closed half of it, and stopping there would throw away real progress; a
         * different finding on an unchanged tree means the reviewer looked somewhere new.
         * Together they mean the round bought nothing, and the next one would buy the same.
         *
         * Measured live on 2026-09-07: a repair cost $0.031 and changed no bytes, and the
         * round then spent $1.46 for one reviewer to reach the same pass and another the same
         * fail. Nothing compared the tree before the repair with the tree after it, so the
         * loop would have gone on doing that until `max_fix_attempts` ran out.
         *
         * Checked after the re-read rather than straight after the fix. Stopping the moment a
         * repair writes nothing would be cheaper and is wrong: the stage that objected has not
         * spoken yet, and it is the only thing that can say whether the objection still
         * stands.
         */
        private void requireFindingsProgress(Workflow.Stage stage) {
            Map<String, Object> now = lastFindingRound(stage.name());
            if (now == null) return;
            if (!(now.get("blocking_ids") instanceof List<?> blockers) || blockers.isEmpty()) return;
            // `candidate_moved` is absent on a stage's first round, and a first round cannot
            // be a repeat of anything.
            if (!Boolean.FALSE.equals(now.get("candidate_moved"))) return;
            boolean closedNothing = now.get("closed") instanceof List<?> closed && closed.isEmpty();
            boolean foundNothingNew = now.get("new_findings") instanceof List<?> fresh && fresh.isEmpty();
            if (!closedNothing || !foundNothingNew) return;

            Map<String, Object> stalled = new LinkedHashMap<>();
            stalled.put("at_stage", stage.name());
            stalled.put("fix_attempt", (long) attempt);
            stalled.put("candidate_fingerprint", now.get("candidate_fingerprint"));
            stalled.put("open_blocking_ids", blockers.stream().map(String::valueOf).toList());
            summary.put("repair_made_no_progress", stalled);
            summary.put("resolution", "The repair for '" + stage.name() + "' left the candidate "
                    + "byte-for-byte unchanged and the same blocking finding came back, so "
                    + "another round would re-read the same bytes for the same verdict. Read "
                    + "what the finding actually asks for: one an implementer cannot act on — a "
                    + "weak acceptance command, a missing access grant, a disagreement about the "
                    + "contract — is resolved by a person changing the task, not by another fix.");
            progress.line("      the repair changed nothing and the same finding came back; "
                    + "stopping rather than buying the same verdict again");
            throw new StopException("repair_made_no_progress");
        }

        /**
         * Refuse a repair the remaining allowance cannot pay for, on the terms the policy set.
         *
         * The budget already refused a call it could not afford. That check fires one call too
         * late to be useful: it stops the repair the run cannot pay for, having already spent
         * the allowance on repairs whose consequences it then could not judge. Measured on a
         * live run — six calls bought an implementer, two repairs and three reviews, and the
         * two stages that would have finished the workflow were never reached.
         *
         * How much has to fit is `budget.repair_reserve`, and the default is `full`: the
         * repair, every judgement it invalidates, and every stage still owed. A run either
         * completes or does not start the attempt.
         *
         * `partial` is the opt-in, and it is a real trade rather than a strictly better one.
         * In its favour: the unspent calls are not saved by declining to spend them, since
         * only raising the ceiling unlocks the rest of the chain, and a review that then
         * passes is a verdict a continuation reuses for nothing. Against it: a repair can
         * introduce a regression rather than remove one, and a person may decide not to
         * continue the task at all, in which case the money bought nothing. That judgement is
         * the operator's, so it is declared rather than inferred here.
         */
        private void requireReserve(Workflow.Stage stage, int index) {
            String mode = user.policy() == null ? "full" : user.policy().repairReserve();
            int required = callPlan.requiredFor(index, mode);
            int toFinish = callPlan.reserveForRepair(index);
            int remaining = budget.remaining();
            Map<String, Object> reserve = new LinkedHashMap<>();
            reserve.put("at_stage", stage.name());
            reserve.put("repair_reserve", mode);
            reserve.put("calls_required_here", (long) required);
            reserve.put("calls_needed_to_repair", (long) callPlan.repairCost(index));
            reserve.put("calls_needed_to_repair_and_be_judged",
                    (long) callPlan.repairAndJudgeCost(index));
            reserve.put("calls_needed_to_repair_and_finish", (long) toFinish);
            reserve.put("calls_remaining", (long) remaining);
            reserve.put("calls_after_this_stage", (long) callPlan.callsAfter(index));
            reserve.put("cap", budget.maxRuns());
            if (remaining >= required) {
                // Allowed, but not silently. Under `partial` this is the case where the run
                // knows now that it will stop short, and saying so at the start beats
                // discovering it in the summary.
                if (remaining < toFinish) {
                    reserve.put("will_finish", false);
                    summary.put("budget_reserve", reserve);
                    progress.line("      repair_reserve=" + mode + ": the remaining " + remaining
                            + " call(s) cover this repair and a judgement of it, but not the "
                            + callPlan.callsAfter(index) + " stage(s) after it; this run will "
                            + "stop with the chain unfinished");
                }
                return;
            }
            reserve.put("will_finish", false);
            summary.put("budget_reserve", reserve);
            summary.put("resolution", "The remaining " + remaining + " vendor call(s) do not "
                    + "meet the repair reserve this policy requires (" + mode + ", needing "
                    + required + "). Raise budgets.max_role_runs to at least "
                    + (budget.runs() + toFinish) + " and continue this run — the verdicts "
                    + "already reached on this tree are kept, because a change to the call "
                    + "ceiling is not a change to what the work is judged by."
                    + ("full".equals(mode)
                        ? " Or set budget.repair_reserve: partial in policy.yaml to allow paid "
                          + "progress that this run cannot finish."
                        : ""));
            progress.line("      repair_reserve=" + mode + ": the remaining " + remaining
                    + " call(s) do not meet the reserve of " + required + " this repair needs");
            throw new StopException("budget_insufficient_to_finish");
        }

        /**
         * Refuse a repair that nothing left on the roster could judge.
         *
         * The money is only half of what a repair commits the run to; the other half is a
         * reader for what it produces. Both used to be discovered late and in the wrong order:
         * the repair ran, moved the tree, and the review stage after it then failed to resolve
         * because the one remaining vendor was the one that had just written the code. The
         * run ended with an unjudged candidate and had paid for the privilege.
         *
         * Independence is the constraint that actually runs out. A two-vendor roster minus one
         * spent subscription is one vendor, and a reviewer required to differ from the
         * implementer has nobody — which is a fact about the roster, not about the diff, and
         * says so in its own stop reason.
         */
        private void requireJudgesRemain(Workflow.Stage stage, int index) {
            String writer = vendors.get("implementer");
            List<Workflow.Stage> needed = new ArrayList<>();
            for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
                if (earlier.kind() == Workflow.Kind.ROLE && skipReason(earlier) == null) {
                    needed.add(earlier);
                }
            }
            if (stage.kind() == Workflow.Kind.ROLE) needed.add(stage);
            for (int position = index + 1; position < workflow.stages().size(); position++) {
                Workflow.Stage later = workflow.stages().get(position);
                if (later.kind() == Workflow.Kind.ROLE && skipReason(later) == null) needed.add(later);
            }
            // The repair itself first: a fix role nobody can fill makes the rest moot.
            if (!roles.canFill(user, stage.fixWith(), null, RoleRunner.UNSTAGED)) {
                throw unfillable(stage.fixWith(), stage.name(), "the repair itself", null);
            }
            for (Workflow.Stage judge : needed) {
                String avoid = "implementer".equals(judge.role()) ? null : writer;
                if (roles.canFill(user, judge.role(), avoid, workflow.rotationPositionOf(judge))) {
                    continue;
                }
                throw unfillable(judge.role(), stage.name(), judge.name(), avoid);
            }
        }

        private StopException unfillable(String role, String at, String forStage, String avoid) {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("role", role);
            gap.put("needed_for", forStage);
            gap.put("blocked_at", at);
            gap.put("must_differ_from_vendor", avoid);
            gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
            summary.put("unavailable_role", gap);
            summary.put("resolution", "Repairing here would produce a candidate that '" + forStage
                    + "' could not then judge: no profile can fill role '" + role + "'"
                    + (avoid == null ? "" : " that differs from the vendor which wrote this code ("
                        + avoid + ")")
                    + ". Add a profile from another vendor, or wait for the quota window named "
                    + "in the vendor message, then continue this run.");
            progress.line("      nothing left can fill " + role + " for " + forStage
                    + "; stopping before the repair rather than after it");
            return new StopException("independent_review_unavailable");
        }

        private Object execute(Workflow.Stage stage) throws Exception {
            announce(stage);
            long started = System.nanoTime();
            Object outcome;
            // The only place a stage is dispatched, so the only place that has to say it is
            // still going. Inside the try, because a stage that throws is exactly the stage
            // whose beats must stop. The beat starts knowing only the role; the profile
            // arrives from the resolution that actually dispatched, via named().
            try (Heartbeat alive = beating(who(stage))) {
                heartbeat = alive;
                heartbeatNote = (profile, vendor) -> runningNote(stage, profile, vendor);
                outcome = dispatch(stage);
            } finally {
                heartbeat = Heartbeat.none();
                heartbeatNote = null;
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            stamp(stage);
            narrate(outcome, millis);
            post(stage, outcome, millis);
            return outcome;
        }

        /**
         * The stage name on the row the dispatch just wrote, when the writer did not know it.
         *
         * Role dispatches carry it already. Machine gates and the browser harness are labelled
         * by what they are, so a chain running two of either produced rows nobody could tell
         * apart — and "which stages has this run still not finished" is a question asked of
         * exactly these rows.
         */
        private void stamp(Workflow.Stage stage) {
            if (steps.isEmpty()) return;
            steps.get(steps.size() - 1).putIfAbsent("stage", stage.name());
        }

        /**
         * A beat per minute while this stage runs, naming whoever is holding the loop.
         *
         * A dry run gets none: it dispatches nobody, returns in milliseconds, and a card that
         * announced a working role during a preview would be claiming work that never started.
         */
        private Heartbeat beating(String who) {
            return dryRun ? Heartbeat.none() : Heartbeat.over(who, progress, workspace);
        }

        /**
         * The name a person would use for whoever is busy. A role answers with its own name;
         * the two stages that call no vendor answer with what they are, because "machine_gates
         * still working" is the loop's vocabulary and not the operator's.
         *
         * The profile is not resolved here. Resolution lives in {@link RoleRunner} and
         * advances rotation state; asking twice could name a profile that was not dispatched.
         * The pair arrives via {@link RoleRunner.Occupied} from the resolution that happened.
         */
        private static String who(Workflow.Stage stage) {
            return switch (stage.kind()) {
                case MACHINE_GATES -> "gates";
                case VISUAL_HARNESS -> "browser harness";
                default -> stage.role();
            };
        }

        /**
         * The profile RoleRunner just chose. The beat and the card learn it here because
         * asking the resolver again would rotate twice.
         *
         * A carried-over verdict runs inside a beat but never calls RoleRunner, so nobody
         * publishes a name and the card stays the bare role. Anything dispatching outside a
         * beat leaves the builder null and this is a no-op rather than a sentence attached
         * to a stage that has already ended.
         */
        private void named(String profile, String vendor) {
            heartbeat.filledBy(profile, vendor);
            java.util.function.BinaryOperator<String> note = heartbeatNote;
            if (dryRun || note == null) return;
            workspace.note(note.apply(profile, vendor));
        }

        /**
         * The card, after a stage. One line, replacing the last one — a board says where the
         * work is, and a person who wants the history has the terminal and the ledger.
         */
        private void post(Workflow.Stage stage, Object outcome, long millis) {
            if (dryRun || outcome == null) return;
            StringBuilder note = new StringBuilder(position(stage))
                    .append(' ').append(stage.name()).append(" · ");
            if (outcome instanceof RoleRunner.Outcome role) {
                note.append(role.ok() ? "ok" : "failed " + role.code());
                Map<String, Object> details = role.details() == null ? Map.of() : role.details();
                if (details.get("vendor") != null) note.append(" · ").append(details.get("vendor"));
                if (details.get("cost_usd") instanceof Number) {
                    note.append(" · ").append(Progress.money(details.get("cost_usd")));
                }
                if (details.get("verdict") != null) note.append(" · ").append(details.get("verdict"));
            } else if (outcome instanceof GateRunner.Outcome gate) {
                note.append(gate.ok() ? "ok" : "failed " + gate.code());
            } else if (outcome instanceof VisualQaRunner.Outcome visual) {
                note.append(visual.ok() ? "ok" : "failed " + visual.code());
            } else {
                return;
            }
            workspace.note(note.append(" · ").append(Progress.elapsed(millis)).toString());
        }

        private String position(Workflow.Stage stage) {
            return "[" + (1 + workflow.stages().indexOf(stage)) + "/" + workflow.stages().size() + "]";
        }

        /** `[3/5] review   role=reviewer` — where the loop is, in the plan it printed. */
        private void announce(Workflow.Stage stage) {
            int position = 1 + workflow.stages().indexOf(stage);
            String what = switch (stage.kind()) {
                case MACHINE_GATES -> "machine gates: " + String.join(", ", task.acceptanceCommands());
                case VISUAL_HARNESS -> "browser harness: " + task.visualQa().scenarios().size()
                        + " scenario(s) at " + task.visualQa().url();
                default -> "role " + stage.role();
            };
            progress.line("[" + position + "/" + workflow.stages().size() + "] "
                    + pad(stage.name(), 10)
                    + (attempt > 0 ? "(after fix " + attempt + ")  " : "") + what);
            // The card is told before the stage as well as after it, because the stage that
            // most needs a card is the one that takes sixteen minutes to answer. The profile
            // is not known yet — resolution happens inside the dispatch — so this line names
            // the role, and named() rewrites it with the pair as soon as it is.
            if (!dryRun) {
                workspace.note(runningNote(stage, null, null));
            }
        }

        /**
         * `[1/5] implement · implementer (grok-implement / grok) · running`.
         *
         * The role is always there; the bracketed pair is only there when a profile was
         * actually resolved. `gates` and `browser harness` pass nulls and stay bare.
         */
        /**
         * `fix 1/2 · review sent the work back to implementer (grok-implement / grok)`.
         *
         * The same shape the card already used, with the bracketed pair once the resolution
         * that answers the fix has chosen one. Written twice on purpose: the first call
         * happens before the dispatch, when nobody knows who will take it.
         */
        private String fixNote(Workflow.Stage stage, String profile, String vendor) {
            return "fix " + attempt + "/" + task.maxFixAttempts() + " · " + stage.name()
                    + " sent the work back to "
                    + Heartbeat.spoken(stage.fixWith(), profile, vendor);
        }

        private String runningNote(Workflow.Stage stage, String profile, String vendor) {
            return position(stage) + " " + stage.name() + " · "
                    + Heartbeat.spoken(who(stage), profile, vendor)
                    + " · running"
                    + (attempt > 0 ? " (after fix " + attempt + ")" : "");
        }

        /** How it went, in the same two-line shape for every kind of stage. */
        private void narrate(Object outcome, long millis) {
            if (outcome == null) return;
            StringBuilder line = new StringBuilder("      ");
            if (outcome instanceof RoleRunner.Outcome role) {
                line.append(role.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
                Map<String, Object> details = role.details() == null ? Map.of() : role.details();
                if (details.get("tokens") instanceof Map<?, ?> tokens) {
                    line.append("   tokens ").append(tokens.get("input")).append("/").append(tokens.get("output"));
                }
                // `cost_usd` first, and `?` when it is absent. `attempts_cost_usd` is a sum,
                // and a sum of nothing is 0.0 — printing that would tell the operator a call
                // was free when what happened is that nobody priced it, which is the exact
                // confusion the report's `?` exists to prevent. The sum is only better when
                // it is actually carrying something: a role that failed over paid twice.
                Object cost = details.get("cost_usd");
                if (cost == null && details.get("attempts_cost_usd") instanceof Number summed
                        && summed.doubleValue() > 0) {
                    cost = summed;
                }
                line.append("   cost ").append(Progress.money(cost));
                if (details.get("verdict") != null) line.append("   verdict ").append(details.get("verdict"));
                if (!role.ok()) line.append("   ").append(role.code());
            } else if (outcome instanceof GateRunner.Outcome gate) {
                line.append(gate.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
                if (!gate.ok()) line.append("   ").append(gate.code());
            } else if (outcome instanceof VisualQaRunner.Outcome visual) {
                line.append(visual.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
                Object images = visual.data() == null ? null : visual.data().get("image_evidence");
                if (images instanceof List<?> shots) line.append("   ").append(shots.size()).append(" screenshot(s)");
                if (!visual.ok()) {
                    line.append("   ").append(visual.code());
                    Object message = visual.data() == null ? null : visual.data().get("message");
                    if (message != null) line.append("\n      ").append(message);
                }
            } else {
                return;
            }
            progress.line(line.toString());
        }

        private Object dispatch(Workflow.Stage stage) throws Exception {
            switch (stage.kind()) {
                case MACHINE_GATES -> {
                    return runGates(gates, loaded, runId, attempt, dryRun, steps,
                            contractSnapshot, diffBaseCommit);
                }
                case VISUAL_HARNESS -> {
                    VisualQaRunner.Outcome outcome = runVisual(loaded, runId, attempt, dryRun, steps);
                    harness.put(stage.name(), outcome);
                    return outcome;
                }
                default -> {
                    return runRole(stage);
                }
            }
        }

        /**
         * A human rejection, handed to the implementer as the context of its first attempt.
         *
         * Delivered once, to the implementer, and only at the start: after that the ordinary
         * fix-round context is what a stage failure has to say, and stacking a stale rejection
         * on top of a specific machine failure would bury the specific one.
         */
        private Path rejectionContextFor(String role) throws Exception {
            if (rejectionDelivered || !"implementer".equals(role) || !carried.carriesRejection()) {
                return null;
            }
            rejectionDelivered = true;
            String text = """
                    # A person rejected the previous candidate

                    Run `%s` reached the human gate and was **rejected**. This is not a failed
                    check and not a reviewer's finding: it is the reason a person gave for not
                    accepting the work, and it is the only feedback in this loop that cost
                    somebody's attention rather than a vendor call.

                    > %s

                    The worktree still holds that candidate. Read what is there before writing
                    anything, address this specific objection, and do not restart the task from
                    scratch. If you believe the objection is wrong, say so in your summary with
                    evidence rather than leaving it unaddressed.
                    """.formatted(carried.fromRunId(),
                            carried.rejectionNote().replace("\n", "\n> "));
            return writeContext(ledger, 0, "rejection", text);
        }

        private RoleRunner.Outcome runRole(Workflow.Stage stage) throws Exception {
            roles.atStage(stage.name());
            String role = stage.role();
            // By stage first. The role name is accepted only as the legacy spelling of a
            // stage, and only when this workflow gives that role exactly one — otherwise the
            // first review stage would be free to spend the verdict the second one earned.
            Map<String, Object> standing = null;
            if (attempt == 0) {
                standing = reusable.remove(stage.name());
                if (standing == null && workflow.stagesFor(role).size() == 1) {
                    standing = reusable.remove(role);
                }
            }
            // Currency is checked here, against the tree as it stands at this moment, not only
            // when the pool was built. A stage earlier in this same resume can have dispatched
            // for real and moved the tree — an implementer whose contract changed, say — and a
            // verdict about the tree before that dispatch is not a verdict about this
            // candidate. The pool build proved the prior run's final tree; only this proves the
            // tree the verdict will actually be spent on.
            if (standing != null) {
                Object judged = standing.get("candidate_fingerprint");
                String now = currentFingerprint();
                if (!(judged instanceof String stamped) || now == null || !stamped.equals(now)) {
                    reuseDeclinedAtConsumption(stage,
                            judged instanceof String was ? was : null, now);
                    standing = null;
                }
            }
            if (standing != null) {
                // Not a shortcut and not a cache: the fingerprint check just above says these
                // are the same bytes the earlier vendor read, as the tree stands right now. A
                // fix round moves the tree and a re-dispatched earlier stage can too, which is
                // why the check is here and not only at attempt 0's pool build.
                Map<String, Object> entry = new LinkedHashMap<>(standing);
                entry.put("reused_from", carried.fromRunId());
                entry.put("attempt", (long) attempt);
                steps.add(entry);
                vendors.putIfAbsent(role, String.valueOf(standing.get("vendor")));
                reusedStages.add(stage.name());
                summary.put("reused_judgements", Map.of("from", carried.fromRunId(),
                        "stages", List.copyOf(reusedStages)));
                // The verdict itself, not just the row. Without this a run whose every stage
                // was carried over reported `candidate_review_passed: false` and no coverage
                // at all — so `warden report --text` told the operator the candidate had not
                // passed review, about a candidate a reviewer had passed and whose reuse the
                // fingerprint had just been checked to justify. Reproduced on run `ru2`.
                carryCoverage(stage, standing);
                progress.line("      reused from " + carried.fromRunId() + ": "
                        + standing.get("profile") + " already passed this exact tree");
                return null;
            }
            // Independence is only meaningful against whoever wrote the code.
            String avoid = "implementer".equals(role) ? null : vendors.get("implementer");
            RoleRunner.Outcome outcome;
            if ("visual_qa".equals(role)) {
                outcome = runVisualRole(loaded, user, roles, runId, attempt, avoid,
                        harness.get(stage.sees()), dryRun, steps, budget);
            } else {
                outcome = roles.run(loaded, user, role, stageRunId(runId, workflow, stage, attempt),
                        avoid, rejectionContextFor(role), dryRun, workflow.rotationPositionOf(stage));
                record(steps, budget, role, attempt, outcome, stage.name());
                // The role runner knows the profile and the roster; only the loop knows how
                // the stage that dispatched it routes. Both halves belong to the same claim
                // about the terms this verdict was reached under.
                stampRouting(stage);
            }
            if (dryRun || outcome == null) return outcome;
            vendors.putIfAbsent(role, outcome.vendor());
            // The tree this role just judged, on the row itself, so a later run can prove the
            // verdict is about the candidate it is being spent on rather than about whatever
            // this run's final tree happened to be. Stamped for every role: an implementer's
            // fingerprint is the tree after it wrote, a reviewer's is the tree it read.
            stampFingerprint();
            // A read-only role that edited the contract has already invalidated the very
            // thing it is about to be believed for.
            requireUnchangedContract();
            return outcome;
        }

        /** Record, on the last step row, the fingerprint of the tree the role just judged. */
        private void stampFingerprint() {
            if (steps.isEmpty()) return;
            steps.get(steps.size() - 1).put("candidate_fingerprint", currentFingerprint());
        }

        private void requireUnchangedContract() {
            if (!contractMatches(loaded, contractSnapshot)) throw new StopException("contract_mutated");
        }

        /** Fold this stage's routing into the contract the row already carries. */
        @SuppressWarnings("unchecked")
        private void stampRouting(Workflow.Stage stage) {
            if (steps.isEmpty()) return;
            Map<String, Object> row = steps.get(steps.size() - 1);
            if (!(row.get("role_contract") instanceof Map<?, ?> recorded)) return;
            Map<String, Object> contract = new LinkedHashMap<>((Map<String, Object>) recorded);
            contract.putAll(RoleContract.routingOf(stage));
            row.put("role_contract", contract);
        }

        /**
         * A verdict carried from an earlier run, restored as the judgement it was.
         *
         * Marked `reused` and not `executed`, and carrying the run it came from. Those are
         * different claims about the same conclusion, and a card or a report that showed a
         * carried verdict as a fresh dispatch would be inventing a vendor call that never
         * happened — the opposite mistake to the one this method fixes.
         */
        private void carryCoverage(Workflow.Stage stage, Map<String, Object> standing) {
            if (stage.onFindings() == null) return;
            Object blocking = standing.get("blocking_findings");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            row.put("source", "reused");
            row.put("reused_from", carried.fromRunId());
            row.put("ok", true);
            // Zero by construction: a row with blocking findings was excluded from reuse
            // before it got here. Copied rather than assumed, so the number in the report is
            // the number the earlier run actually recorded.
            row.put("blocking_findings", blocking instanceof Number number ? number.longValue() : 0L);
            row.put("profile", standing.get("profile"));
            row.put("vendor", standing.get("vendor"));
            // The fingerprint the earlier verdict actually judged, preserved — not overwritten
            // with this run's. It is equal to this run's tree by the time we are here, because
            // consumption declined reuse otherwise, but the coverage records what the reviewer
            // saw, and stamping the current run's fingerprint would only be right by accident.
            row.put("candidate_fingerprint", standing.get("candidate_fingerprint"));
            coverage.put(stage.name(), row);
            summary.put("review_coverage", List.copyOf(coverage.values()));
        }

        /**
         * A reusable verdict abandoned at the moment it would have been spent, because the tree
         * moved since it was reached. It falls through to a real dispatch; recording why keeps
         * a resume that quietly re-ran a stage from looking like one that skipped it.
         */
        private void reuseDeclinedAtConsumption(Workflow.Stage stage, String judged, String now) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("judged_fingerprint", judged);
            row.put("current_fingerprint", now);
            row.put("reason", judged == null
                    ? "the carried verdict recorded no candidate fingerprint"
                    : "the tree changed after the pool was built, so this verdict is about "
                            + "another candidate");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> declines = summary.get("reuse_declined_at_consumption")
                    instanceof List<?> existing
                    ? new ArrayList<>((List<Map<String, Object>>) (List<?>) existing)
                    : new ArrayList<>();
            declines.add(row);
            summary.put("reuse_declined_at_consumption", declines);
            progress.line("note  the verdict carried for stage '" + stage.name() + "' judged a "
                    + "tree this resume has since changed; re-running it rather than believing it");
        }

        /**
         * What this stage objected to this round, against what it objected to last round.
         *
         * Kept per stage, because two reviewers reading the same candidate reach their own
         * conclusions and a shared list would let one of them appear to have closed the
         * other's finding. The comparison is what makes "the repair achieved nothing" a fact
         * the run can state rather than a pattern a person has to notice across two reports.
         */
        private void recordFindingRound(Workflow.Stage stage, RoleRunner.Outcome outcome)
                throws Exception {
            List<Findings.Finding> found = Findings.of(readArtifact(loaded.root(), outcome));
            Map<String, Object> previous = lastFindingRound(stage.name());
            Map<String, Object> row = Findings.round(stage.name(), attempt, currentFingerprint(),
                    found, previous);
            findingHistory.add(row);
            summary.put("finding_history", List.copyOf(findingHistory));
        }

        /** The most recent recorded round for this stage, or null when it has had none. */
        private Map<String, Object> lastFindingRound(String stage) {
            for (int index = findingHistory.size() - 1; index >= 0; index--) {
                if (stage.equals(findingHistory.get(index).get("stage"))) {
                    return findingHistory.get(index);
                }
            }
            return null;
        }

        /** The source tree as it stands now, or null when git cannot be asked. */
        private String currentFingerprint() {
            try {
                return new GitRepository(loaded.root(), processes).sourceFingerprint(diffBaseCommit);
            } catch (Exception unreadable) {
                // A run is not failed over a label. The verdict is still recorded; what it
                // loses is the ability to prove afterwards which tree it was about.
                return null;
            }
        }

        /** Why this stage is not running at all, or null when it is. */
        private String skipReason(Workflow.Stage stage) {
            return TaskLoop.skipReason(stage, user, task, reviewByRisk);
        }

        private long recordFindings(Workflow.Stage stage, RoleRunner.Outcome outcome) throws Exception {
            long blocking = blockingFindings(loaded.root(), outcome);
            recordFindingRound(stage, outcome);
            // Kept: an operator's greps, the report reader and the reuse rules for a
            // single-stage role all read this. It is keyed by role, so a chain with two review
            // stages has always had the second silently overwrite the first.
            summary.put(findingsKey(stage), blocking);
            // The same number, under the one identity that is unique in a workflow. Without it
            // "did review-second object" is a question the summary cannot answer, and a resume
            // deciding which verdict it may keep is answering it from the wrong stage.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            row.put("source", "executed");
            row.put("ok", outcome.ok());
            row.put("blocking_findings", blocking);
            row.put("profile", outcome.profile());
            row.put("vendor", outcome.vendor());
            // Which tree this verdict is about. A judgement without one is a claim that
            // cannot be checked against the candidate a person is later asked to accept.
            row.put("candidate_fingerprint", currentFingerprint());
            coverage.put(stage.name(), row);
            // It has now judged the candidate as it currently stands, whatever it concluded.
            // Leaving it marked as judging an earlier tree would report a stage that ran and
            // objected as one that never looked, which sends a reader past the finding.
            staleAfterFix.remove(stage.name());
            summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
            summary.put("review_coverage", List.copyOf(coverage.values()));
            // And on the step row itself, which is what a later run reads back when it asks
            // whether this exact judgement can stand without being paid for twice.
            for (int index = steps.size() - 1; index >= 0; index--) {
                Map<String, Object> step = steps.get(index);
                if (!stage.name().equals(step.get("stage"))) continue;
                step.put("blocking_findings", blocking);
                break;
            }
            if (blocking > 0) {
                // A role that passed its own run and still objects is the whole reason a
                // second vendor is paid, so it says so out loud rather than only in a file.
                progress.line("      " + blocking + " blocking finding(s) from " + stage.role()
                        + " — see warden report for what it saw");
            }
            return blocking;
        }

        private String findingsKey(Workflow.Stage stage) {
            return switch (stage.role()) {
                case "reviewer" -> "blocking_findings";
                case "visual_qa" -> "visual_blocking_findings";
                default -> stage.role() + "_blocking_findings";
            };
        }

        private boolean failed(Object outcome) {
            if (outcome instanceof GateRunner.Outcome gate) return !gate.ok();
            if (outcome instanceof VisualQaRunner.Outcome visual) return !visual.ok();
            if (outcome instanceof RoleRunner.Outcome role) return !role.ok();
            return false;
        }

        /**
         * Which failures may be handed back to an implementer at all.
         *
         * The excluded ones are not about the work. No implementer can install a browser,
         * evict another project's dev server, resolve a base ref, start a local model that
         * is not serving, or revert a file outside the blast radius it was given — and
         * asking one to try spends a role run on the operator's configuration and then
         * fails the same way.
         */
        private boolean fixable(Object outcome) {
            if (outcome instanceof VisualQaRunner.Outcome visual) {
                return "visual_qa_failed".equals(visual.code());
            }
            if (outcome instanceof GateRunner.Outcome gate) {
                return !OPERATOR_MUST_RESOLVE.contains(gate.code());
            }
            if (outcome instanceof RoleRunner.Outcome role) {
                return !roleFailureRequiresOperator(role.code());
            }
            return true;
        }

        private String terminalReason(Workflow.Stage stage, Object outcome) {
            if (outcome instanceof VisualQaRunner.Outcome visual) return visual.code();
            if (outcome instanceof RoleRunner.Outcome role) return reasonFor(role, stage.failureReason());
            return stage.failureReason();
        }

        private String failureContext(Workflow.Stage stage, Object outcome) throws Exception {
            if (outcome instanceof GateRunner.Outcome gate) return gateContext(gate);
            if (outcome instanceof VisualQaRunner.Outcome visual) return visualContext(visual);
            if (outcome instanceof RoleRunner.Outcome role) return reviewContext(loaded.root(), role);
            return "# " + stage.name() + " failed\n";
        }
    }

    private static final class Budget {
        /**
         * Which of the two ceilings refused the call, carried rather than only spelled into a
         * message.
         *
         * They are hit for different reasons and fixed in different places, and the run used
         * to tell an operator whose money ceiling was reached to raise the call ceiling — a
         * next step that would not have unblocked anything. Parsing the sentence back out to
         * work out which limit it was is the kind of thing that is right until somebody
         * rewords it.
         */
        static final class ExceededException extends RuntimeException {
            private final String limit;

            ExceededException(String limit, String message) {
                super(message);
                this.limit = limit;
            }

            String limit() { return limit; }
        }

        private final long maxRuns;
        private final double maxCost;
        private int runs;
        private double spent;
        /**
         * Vendor calls that came back with no price at all.
         *
         * Every vendor here is driven through its own CLI on the operator's own
         * subscription, and what a call costs is whatever that CLI chose to report: Grok and
         * Claude print a figure, Codex prints none. So `max_cost_usd` is a ceiling on the
         * part of the run that priced itself, and a run where nothing did could spend its
         * whole allowance of calls under a $40 limit and charge $0.00 against it. Counting
         * the silent calls is the difference between a budget and the appearance of one.
         */
        private int unpriced;

        Budget(long maxRuns, double maxCost) { this.maxRuns = maxRuns; this.maxCost = maxCost; }

        int runs() { return runs; }
        double spent() { return spent; }

        /**
         * Vendor calls the ceiling still allows, or {@link Integer#MAX_VALUE} when the task
         * declared none.
         *
         * A reservation is taken at {@link #requireRoleRun} and never given back, including
         * for a dispatch that failed or whose outcome is unknown. That is the conservative
         * direction: a call whose receipt never arrived may still have been served and billed,
         * and treating it as free would let a restart pay for it twice.
         */
        int remaining() {
            if (maxRuns <= 0) return Integer.MAX_VALUE;
            return (int) Math.max(0, maxRuns - runs);
        }

        long maxRuns() { return maxRuns; }

        void requireRoleRun() {
            if (maxRuns > 0 && runs >= maxRuns) {
                throw new ExceededException("max_role_runs",
                        "max_role_runs of " + maxRuns + " reached");
            }
            if (maxCost > 0 && spent >= maxCost) {
                throw new ExceededException("max_cost_usd",
                        "max_cost_usd of " + maxCost + " reached, spent " + spent);
            }
            runs++;
        }

        int unpriced() { return unpriced; }

        void account(Object cost) {
            if (cost instanceof Number number) spent += number.doubleValue();
            else unpriced++;
        }
    }

    /** A prior run's independently persisted proof that project checks started green. */
    private record BaselineReceipt(String runId, Path report, String sha256) {}

    /**
     * Reuse a baseline only when this is demonstrably the same continuation.
     *
     * <p>Running the baseline again unconditionally would mislabel a partial change from the
     * previous agent as a pre-existing project failure. Blind reuse is worse: a person could
     * edit the tree between runs and make somebody else's new failure the next agent's
     * problem. The prior decision's candidate fingerprint closes that gap. Any missing field
     * or unreadable receipt declines reuse and executes the baseline now.</p>
     */
    private BaselineReceipt reusableBaseline(Path root, String priorRunId, String taskId,
                                              String contractHash, String diffBaseCommit,
                                              String currentFingerprint,
                                              Map<String, Object> summary) {
        try {
            Path priorSummary = root.resolve(".warden/runs").resolve(priorRunId)
                    .resolve("task-run.json");
            if (!Files.isRegularFile(priorSummary)) {
                return declineBaselineReuse(summary, "no summary for " + priorRunId);
            }
            Map<String, Object> prior = Json.parseObject(Files.readString(priorSummary));
            if (!taskId.equals(prior.get("task_id"))) {
                return declineBaselineReuse(summary, priorRunId + " belongs to another task");
            }
            if (!Boolean.TRUE.equals(prior.get("baseline_ok"))) {
                return declineBaselineReuse(summary, priorRunId
                        + " has no successful baseline receipt");
            }
            if (!contractHash.equals(prior.get("contract_sha256"))) {
                return declineBaselineReuse(summary, "the contract changed since " + priorRunId);
            }
            if (!diffBaseCommit.equals(prior.get("diff_base_commit"))) {
                return declineBaselineReuse(summary, "the immutable diff base changed since "
                        + priorRunId);
            }
            HumanDecision decision = new ApprovalStore(root).read(priorRunId);
            if (decision.candidateFingerprint() == null
                    || !decision.candidateFingerprint().equals(currentFingerprint)) {
                return declineBaselineReuse(summary, "the worktree changed since " + priorRunId);
            }
            Object reportText = prior.get("baseline_report");
            if (!(reportText instanceof String text) || text.isBlank()) {
                return declineBaselineReuse(summary, priorRunId
                        + " names no baseline report");
            }
            Object receiptRunBody = prior.get("baseline_receipt_run");
            String receiptRun = receiptRunBody instanceof String value && !value.isBlank()
                    ? value : priorRunId;
            Path report = Path.of(text);
            if (!report.isAbsolute()) report = root.resolve(report);
            report = report.toAbsolutePath().normalize();
            Path evidenceRoot = root.resolve(".warden/runs").toAbsolutePath().normalize();
            Path expectedReport = evidenceRoot.resolve(receiptRun + "--baseline-0")
                    .resolve("baseline-gate.json").normalize();
            if (!report.equals(expectedReport) || !Files.isRegularFile(report)) {
                return declineBaselineReuse(summary, "the baseline report from " + priorRunId
                        + " is missing or is not the named baseline receipt");
            }
            Object expectedHash = prior.get("baseline_report_sha256");
            String actualHash = GitRepository.contentSha256(report);
            if (!(expectedHash instanceof String hash) || hash.isBlank()
                    || !hash.equals(actualHash)) {
                return declineBaselineReuse(summary, "the baseline report checksum from "
                        + receiptRun + " does not match");
            }
            Map<String, Object> receipt = Json.parseObject(Files.readString(report));
            if (!"baseline".equals(receipt.get("phase"))
                    || !Boolean.TRUE.equals(receipt.get("ok"))
                    || !"passed".equals(receipt.get("code"))
                    || !taskId.equals(receipt.get("task_id"))
                    || !contractHash.equals(receipt.get("contract_sha256"))
                    || !diffBaseCommit.equals(receipt.get("merge_base"))) {
                return declineBaselineReuse(summary, "the baseline receipt from " + receiptRun
                        + " does not prove this task, contract and diff base green");
            }
            return new BaselineReceipt(receiptRun, report, actualHash);
        } catch (Exception unreadable) {
            return declineBaselineReuse(summary, "the baseline receipt from " + priorRunId
                    + " is not verifiable: " + unreadable.getClass().getSimpleName());
        }
    }

    private BaselineReceipt declineBaselineReuse(Map<String, Object> summary, String why) {
        summary.put("baseline_reuse_declined", why);
        progress.line("base  not reusing an earlier baseline: " + why);
        return null;
    }

    /**
     * Role verdicts from an earlier run that are still true of this tree.
     *
     * A verdict is about a tree judged under terms, not about a run. Paying a second vendor
     * to reach the same conclusion about the same bytes under the same rules buys nothing;
     * accepting a verdict when either half has moved reports a review that never happened.
     *
     * Every condition here is a way to say no, and any doubt at all declines:
     *
     *  - the earlier run must have stopped for something that was never about the work;
     *  - the contract must be identical, or the acceptance surface must be, which forgives a
     *    raised call ceiling and nothing else;
     *  - the source must be identical, or it judged a different candidate;
     *  - the stage's own last attempt must have passed and filed no blocking findings, because
     *    a pass with a P1 is not a pass, it is a fix round that had not happened yet;
     *  - the stage's judging contract must be unchanged — see {@link RoleContract}.
     *
     * The first two are checked for the whole run and decline everything. The last two are
     * checked per stage, because a swapped reviewer says nothing about the implementer.
     *
     * The project baseline receipt is deliberately stricter and still requires a byte-exact
     * contract: re-running it costs time and no vendor call, so there is nothing to save by
     * forgiving anything there.
     */
    private Map<String, Map<String, Object>> reusableJudgements(
            Path root, String priorRunId, String contractHash, String acceptanceHash,
            String fingerprint, Workflow workflow, UserConfig user,
            Map<String, Object> summary) throws Exception {
        Path priorSummary = root.resolve(".warden/runs").resolve(priorRunId).resolve("task-run.json");
        if (!Files.isRegularFile(priorSummary)) return declineReuse(summary, "no summary for " + priorRunId);
        Map<String, Object> prior = Json.parseObject(Files.readString(priorSummary));
        String priorReason = String.valueOf(prior.get("reason"));
        if (!NOT_ABOUT_THE_WORK.contains(priorReason)) {
            return declineReuse(summary, priorRunId + " stopped with `" + priorReason
                    + "`, which is a verdict on the work; only a failure that was never about "
                    + "the work leaves an earlier judgement standing");
        }
        if (!contractHash.equals(prior.get("contract_sha256"))) {
            // The contract moved. That is usually the end of it — but there is exactly one
            // way it moves that no reviewer would care about, and it is the way an operator is
            // forced to move it in order to continue at all. A run stopped by its call ceiling
            // can only go on if the ceiling goes up, and the ceiling lives in the same file as
            // the goal. Refusing on the whole-file hash would mean the act of continuing was
            // itself the reason to throw away what the continuation was meant to save.
            Object priorAcceptance = prior.get("acceptance_sha256");
            if (!(priorAcceptance instanceof String recorded) || !recorded.equals(acceptanceHash)) {
                return declineReuse(summary, "the contract changed since " + priorRunId
                        + (priorAcceptance == null
                            ? ", and that run predates the acceptance hash, so there is no way "
                              + "to show the change was only to its budget"
                            : ", and the change reaches what the work is judged by")
                        + ", so its roles were judged against different terms");
            }
            Map<String, Object> allowed = new LinkedHashMap<>();
            allowed.put("from_run", priorRunId);
            allowed.put("prior_max_role_runs", prior.get("budget_max_role_runs"));
            allowed.put("now_max_role_runs", summary.get("budget_max_role_runs"));
            allowed.put("prior_max_cost_usd", prior.get("budget_max_cost_usd"));
            allowed.put("now_max_cost_usd", summary.get("budget_max_cost_usd"));
            allowed.put("acceptance_sha256", acceptanceHash);
            // Recorded, not merely permitted. Whoever accepts this candidate is told that a
            // verdict was carried across an edited contract, and exactly which numbers moved.
            summary.put("contract_change_budget_only", allowed);
            progress.line("note  the contract changed since " + priorRunId + ", but only its "
                    + "budget: what the work is judged by is byte-identical, so the verdicts "
                    + "already reached still stand");
        }
        String priorFingerprint;
        try {
            priorFingerprint = new ApprovalStore(root).read(priorRunId).candidateFingerprint();
        } catch (Exception noDecision) {
            // The fingerprint is the whole basis for believing an earlier verdict. Without a
            // decision file there is nothing to check it against, and an unverifiable claim
            // that the tree has not moved is worth less than re-running the roles.
            return declineReuse(summary, "no recorded decision for " + priorRunId
                    + ", so there is no fingerprint to prove the tree has not moved");
        }
        if (priorFingerprint == null || !priorFingerprint.equals(fingerprint)) {
            return declineReuse(summary, "the worktree changed since " + priorRunId
                    + ", so its roles judged a different candidate");
        }
        // Keyed by stage, because a verdict belongs to a stage and not to a role.
        //
        // It used to be keyed by the step label, which is the role name. A chain that reviews
        // twice writes `reviewer` on both rows, so the second overwrote the first and the
        // survivor was then handed to whichever review stage asked first. The failure is
        // silent and in the worst direction: `review-second` exists precisely to be a second
        // pair of eyes, and a run could satisfy it with the first pair's verdict and report
        // two independent reviews. Legacy summaries carry no stage on their rows; those are
        // admitted only for a role the current workflow dispatches exactly once, where the
        // role name is an unambiguous name for the stage.
        Map<String, Map<String, Object>> reusable = new LinkedHashMap<>();
        Map<String, String> keyToRole = new LinkedHashMap<>();
        Object steps = prior.get("steps");
        // A summary this code wrote carries a `stage` on every stage row. When it does, only
        // stage rows are reuse candidates; a role row with no stage is a fix-round dispatch, an
        // intermediate step in some other stage's repair rather than a stage verdict of its
        // own, and reusing it keyed by its role would let an implementer's mid-repair state
        // stand in for a stage that never independently passed. The role-name fallback is kept
        // only for a legacy summary, which predates stage keying and has no stage on any row.
        boolean stageKeyed = false;
        if (steps instanceof List<?> scan) {
            for (Object item : scan) {
                if (item instanceof Map<?, ?> row && row.get("profile") != null
                        && row.get("stage") != null) {
                    stageKeyed = true;
                    break;
                }
            }
        }
        if (steps instanceof List<?> rows) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) continue;
                if (row.get("profile") == null) continue;
                String role = String.valueOf(row.get("step"));
                String key;
                if (row.get("stage") != null) {
                    key = String.valueOf(row.get("stage"));
                } else if (!stageKeyed && workflow.stagesFor(role).size() == 1) {
                    key = role;
                } else {
                    continue;
                }
                if (!Boolean.TRUE.equals(row.get("ok"))) { reusable.remove(key); continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) row;
                reusable.put(key, entry);
                keyToRole.put(key, role);
            }
        }
        // A pass carrying a P1 is not a pass; it is a fix round that had not happened yet. The
        // count is read from the row when the row has one, because the summary's own
        // `blocking_findings` is keyed by role and so cannot answer for a chain that reviews
        // more than once.
        for (String key : List.copyOf(reusable.keySet())) {
            Map<String, Object> row = reusable.get(key);
            Object blocking = row.get("blocking_findings");
            if (blocking == null) {
                String role = keyToRole.get(key);
                blocking = prior.get("reviewer".equals(role) ? "blocking_findings"
                        : "visual_qa".equals(role) ? "visual_blocking_findings"
                        : role + "_blocking_findings");
            }
            if (blocking instanceof Number number && number.longValue() > 0) reusable.remove(key);
        }
        Map<String, String> declined = new LinkedHashMap<>();
        // The prior run may already have marked a verdict stale — a review that passed and was
        // then overtaken by a repair the workflow did not recheck. Its step row is still
        // `ok=true`, so nothing above drops it, but the run that produced it recorded that it
        // no longer described the candidate. Believing it now would resurrect exactly the
        // judgement that run had already retired.
        java.util.Set<String> priorStale = new java.util.LinkedHashSet<>();
        if (prior.get("stages_judging_an_earlier_candidate") instanceof List<?> staleRows) {
            for (Object staleRow : staleRows) priorStale.add(String.valueOf(staleRow));
        }
        for (String key : List.copyOf(reusable.keySet())) {
            if (priorStale.contains(key)) {
                reusable.remove(key);
                declined.put(key, "that run had already retired this verdict: a later repair "
                        + "changed the candidate and the stage was not rechecked");
            }
        }
        // The bytes each verdict actually judged, per stage, not the run as a whole. The
        // run-level fingerprint above proves the prior run's *final* tree matches; it cannot
        // prove that an individual stage judged that final tree, because a stage may have
        // passed an earlier revision inside a run that later changed the tree. So a stage whose
        // recorded candidate does not match the tree we are resuming on is dropped here, before
        // it can be listed as reusable at all.
        for (String key : List.copyOf(reusable.keySet())) {
            Object judged = reusable.get(key).get("candidate_fingerprint");
            if (!(judged instanceof String stamped) || stamped.isBlank()) {
                reusable.remove(key);
                declined.put(key, "its evidence records no candidate fingerprint, so there is "
                        + "no way to show it judged the tree being resumed");
            } else if (!stamped.equals(fingerprint)) {
                reusable.remove(key);
                declined.put(key, "it judged a different revision inside " + priorRunId
                        + " than the tree being resumed");
            }
        }
        // Whether the same judge, under the same rules, would be asked again.
        //
        // The hashes above prove the bytes have not moved. They cannot prove this: they cover
        // the project's `.warden`, and who may fill a role, whether it must differ from the
        // implementer, its prompt and its schema all live in the operator's own home. Without
        // this an operator could swap the reviewer roster to another vendor, continue, and
        // have the new reviewer's stage satisfied by the old reviewer's verdict.
        //
        // Declined per stage, never globally. A changed reviewer says nothing about whether
        // the implementer's own stage still stands, and invalidating it too would charge for a
        // dispatch nothing had called into question.
        for (String key : List.copyOf(reusable.keySet())) {
            String difference = RoleContract.differenceFrom(
                    reusable.get(key).get("role_contract"),
                    currentStageFor(workflow, key, keyToRole.get(key)), user);
            if (difference == null) continue;
            reusable.remove(key);
            declined.put(key, difference);
        }
        if (!declined.isEmpty()) {
            summary.put("reuse_declined_by_stage", declined);
            for (Map.Entry<String, String> entry : declined.entrySet()) {
                progress.line("note  not reusing the verdict for stage '" + entry.getKey()
                        + "': " + entry.getValue());
            }
        }
        // `reused_judgements` is not set here. What is carried over is decided at the moment
        // each verdict is consumed, because a stage that looks reusable now can be invalidated
        // before its turn by an ordinary dispatch earlier in this same resume. The engine
        // records the stages it actually reused as it consumes them.
        return reusable;
    }

    /**
     * The stage a reusable verdict would be reused for, in the workflow as it stands now, or
     * null when this workflow no longer has such a stage.
     *
     * The key is a stage name for anything this code wrote, and a role name only for a
     * summary old enough to predate stage-keyed rows — which is admitted at all only when the
     * role has exactly one stage, so the lookup is unambiguous either way. The whole stage is
     * returned, not just its routing, because its current role is the thing the recorded
     * contract has to match: a stage that kept its name but changed its role is a different
     * job, and returning only routing hid exactly that change.
     */
    private static Workflow.Stage currentStageFor(Workflow workflow, String key, String role) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.name().equals(key)) return stage;
        }
        List<Workflow.Stage> sharing = workflow.stagesFor(role);
        return sharing.size() == 1 ? sharing.get(0) : null;
    }

    private Map<String, Map<String, Object>> declineReuse(Map<String, Object> summary, String why) {
        summary.put("reuse_declined", why);
        progress.line("note  not reusing any earlier verdict: " + why);
        return Map.of();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    /** What is about to happen, before the first vendor is paid to do any of it. */
    private void header(TaskSpec.ResolvedTask task, String runId, Workflow workflow, boolean dryRun,
                        CallPlan plan) {
        progress.blank();
        progress.line("run   " + runId + (dryRun ? "   (dry run: nothing is dispatched)" : ""));
        progress.line("task  " + task.id() + "   risk=" + task.risk()
                + "   scope=" + String.join(", ", task.scopePaths()));
        List<String> names = new ArrayList<>();
        if (!task.baselineCommands().isEmpty()) names.add("baseline");
        for (Workflow.Stage stage : workflow.stages()) names.add(stage.name());
        progress.line("plan  " + String.join(" -> ", names));
        progress.line("bound " + task.budget().maxRoleRuns() + " vendor call(s), "
                + Progress.money(task.budget().maxCostUsd()) + " ceiling, fix rounds <= "
                + task.maxFixAttempts());
        // The arithmetic, before anything is spent rather than after. An operator who is going
        // to be told at the end that the chain never finished is entitled to be told at the
        // start that it could not have.
        long cap = task.budget().maxRoleRuns();
        int minimum = plan.minimumToFinish();
        progress.line("cost  " + minimum + " vendor call(s) if nothing has to be repaired: "
                + String.join(", ", plan.payingStages()));
        if (cap > 0 && cap < minimum) {
            progress.line("      WARNING: the cap of " + cap + " cannot reach the end of this "
                    + "chain even with no repairs; the run will stop with stages outstanding");
        } else if (cap > 0) {
            progress.line("      the cap of " + cap + " leaves " + (cap - minimum)
                    + " call(s) for repairs; each repair round costs its fix plus every "
                    + "judgement it invalidates");
        }
        progress.blank();
        if (!dryRun) {
            workspace.state(Workspace.State.RUNNING);
            workspace.note(task.id() + " · " + String.join(" -> ", names) + " · starting");
        }
    }

    /** Where the run ended and what the person watching is now expected to do about it. */
    private void footer(String runId, String reason, String nextAction, Budget budget,
                        Map<String, Object> summary) {
        progress.blank();
        progress.line("done  " + reason + "   " + budget.runs() + " vendor call(s), "
                + Progress.money(budget.spent()) + " charged");
        Object unpriced = summary.get("unpriced_calls");
        if (unpriced instanceof Number number && number.longValue() > 0) {
            progress.line("      " + number + " of those reported no price at all, so the "
                    + "$ ceiling did not measure them");
        }
        if ("human_gate".equals(nextAction)) {
            progress.line("      the candidate is ready and nothing has been landed");
        } else if ("human_escalation".equals(nextAction)) {
            progress.line("      stopped for a person; nothing has been landed");
        }
        // Said separately, because they are separate facts and a run can be a yes on one and
        // a no on the other. A stop that buried a clean review under one word is the reason
        // this exists: the reviewer's verdict was the most expensive thing the run produced.
        if (Boolean.TRUE.equals(summary.get("candidate_review_passed"))) {
            progress.line("      the candidate passed every review that ran");
        }
        if (summary.get("pending_stages") instanceof List<?> pending && !pending.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object row : pending) {
                if (row instanceof Map<?, ?> stage) {
                    names.add(stage.get("stage") + " (" + stage.get("reason") + ")");
                }
            }
            progress.line("      the workflow did not finish; outstanding: "
                    + String.join(", ", names));
        }
        if (summary.get("safe_next_step") instanceof String step) {
            progress.line("      next: " + step);
        }
        progress.line("      warden report " + runId + " --text");
        Object options = summary.get("decision_options");
        if (options instanceof List<?> list && !list.isEmpty()) {
            progress.line("      warden approve " + runId + " --decision <"
                    + list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"))
                    + ">");
        }
        // An Orca gate renders nowhere in Orca's own UI — checked in 1.4.196 — so the way to
        // answer one has to be told, not discovered. From any Orca terminal, including a
        // phone's: bind the Run, then resolve the gate.
        String gate = publishedGate(summary, "gate_id");
        if (gate != null) {
            progress.line("      warden approve " + runId + " --from-orca      (Orca gate " + gate + ")");
            progress.line("      to answer that gate from any Orca terminal, phone included:");
            progress.line("        orca orchestration run-use --id " + publishedGate(summary, "orca_run_id"));
            progress.line("        orca orchestration gate-resolve --id " + gate + " --resolution <"
                    + (options instanceof List<?> chosen && !chosen.isEmpty()
                        ? chosen.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining("|"))
                        : "choice") + ">");
        }
        progress.blank();

        // The card the whole channel exists for. A run that ends waiting for a person is the
        // one worth seeing from another room, so it carries what it cost and the command that
        // answers it — a phone can read the first and paste the second.
        workspace.state(Workspace.State.WAITING_FOR_HUMAN);
        StringBuilder note = new StringBuilder(
                "human_gate".equals(nextAction) ? "ready for you" : "stopped: " + reason);
        note.append(" · ").append(budget.runs()).append(" call(s) · ")
                .append(Progress.money(budget.spent()));
        if (options instanceof List<?> list && !list.isEmpty()) {
            note.append(" · warden approve ").append(runId).append(" --decision ")
                    .append(list.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining("|")));
        }
        if (gate != null) note.append(" · or orca gate ").append(gate);
        workspace.note(note.toString());
    }

    private void record(List<Map<String, Object>> steps, Budget budget, String role, int attempt,
                        RoleRunner.Outcome step) {
        record(steps, budget, role, attempt, step, null);
    }

    /**
     * @param stage the workflow stage that dispatched this role, when there was one. It is
     *              recorded so a reader of the summary can tell two stages of the same role
     *              apart — and so `warden report` looks for the evidence where the stage
     *              actually wrote it rather than where the role alone would suggest.
     */
    private void record(List<Map<String, Object>> steps, Budget budget, String role, int attempt,
                        RoleRunner.Outcome step, String stage) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", role);
        if (stage != null) entry.put("stage", stage);
        entry.put("attempt", (long) attempt);
        entry.put("ok", step.ok());
        entry.put("code", step.code());
        entry.put("profile", step.profile());
        entry.put("vendor", step.vendor());
        entry.put("rejected_profiles", step.rejected());
        if (step.details() != null) {
            // A role may have dispatched more than one vendor. Charging only the last one
            // would under-report a run that failed over, which is the run most worth costing.
            Object attempts = step.details().get("vendor_attempts");
            if (attempts instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) budget.account(map.get("cost_usd"));
                }
                entry.put("vendor_attempts", attempts);
            } else {
                budget.account(step.details().get("cost_usd"));
            }
            if (step.details().get("failover_pending") != null) {
                entry.put("failover_pending", step.details().get("failover_pending"));
            }
            // Carried onto the row rather than left in the role report, because the row is
            // what a continuation reads when deciding whether this verdict may stand.
            if (step.details().get("role_contract") != null) {
                entry.put("role_contract", step.details().get("role_contract"));
            }
            entry.put("cost_usd", step.details().get("cost_usd"));
            entry.put("attempts_cost_usd", step.details().get("attempts_cost_usd"));
            entry.put("failed_over_from", step.details().get("failed_over_from"));
            entry.put("artifact_path", step.details().get("artifact_path"));
            if ("role_quota_exhausted".equals(step.code())) {
                entry.put("quota", step.details().get("quota"));
                entry.put("exhausted_profiles", step.details().get("exhausted_profiles"));
            }
        }
        steps.add(entry);
    }

    /**
     * A step that stopped because its vendor ran out is not the same event as a step that
     * failed, and the summary says which. The distinction survives to `next_action` unchanged:
     * both still stop for a human, because Warden does not decide to wait out a quota window.
     */
    private static String reasonFor(RoleRunner.Outcome step, String genericReason) {
        if ("role_quota_exhausted".equals(step.code())) return "quota_exhausted";
        if ("role_failover_requires_confirmation".equals(step.code())) {
            return "failover_requires_confirmation";
        }
        // `reviewer_failed` reads as "the reviewer objected"; a spent turn ceiling is the
        // opposite of that, and the difference decides both what an operator goes and reads
        // and whether the stages that already passed survive the retry.
        if ("role_turns_exhausted".equals(step.code())) return "turn_ceiling_reached";
        return genericReason;
    }

    private GateRunner.Outcome runGates(GateRunner gates, ConfigLoader.Loaded loaded, String runId,
                                        int attempt, boolean dryRun, List<Map<String, Object>> steps,
                                        Map<String, String> pinnedConfig, String diffBaseCommit)
            throws Exception {
        if (dryRun) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", "gates");
            entry.put("attempt", (long) attempt);
            entry.put("dry_run", true);
            steps.add(entry);
            return null;
        }
        GateRunner.Outcome outcome = gates.run(loaded, stepRunId(runId, "gates", attempt),
                pinnedConfig, diffBaseCommit);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", "gates");
        entry.put("attempt", (long) attempt);
        entry.put("ok", outcome.ok());
        entry.put("code", outcome.code());
        entry.put("report", String.valueOf(outcome.report()));
        steps.add(entry);
        return outcome;
    }

    private static boolean contractMatches(ConfigLoader.Loaded loaded, Map<String, String> pinned) {
        try {
            return WardenTree.changedSince(loaded.root(), pinned).isEmpty();
        } catch (Exception missingOrUnreadable) {
            return false;
        }
    }

    private VisualQaRunner.Outcome runVisual(ConfigLoader.Loaded loaded, String runId, int attempt,
                                             boolean dryRun, List<Map<String, Object>> steps)
            throws Exception {
        if (dryRun) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", "visual_qa");
            entry.put("attempt", (long) attempt);
            entry.put("dry_run", true);
            steps.add(entry);
            return null;
        }
        VisualQaRunner.Outcome outcome = visualCheck.run(loaded, stepRunId(runId, "visual-qa", attempt));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", "visual_qa");
        entry.put("attempt", (long) attempt);
        entry.put("ok", outcome.ok());
        entry.put("code", outcome.code());
        entry.put("report", String.valueOf(outcome.report()));
        steps.add(entry);
        return outcome;
    }

    /**
     * The role that looks at the screenshots. It is dispatched only when the policy names a
     * `visual_qa` role: the machine harness is the floor, and paying a model to look on top of
     * it is a decision an operator makes once, in policy, not one this loop makes for them.
     *
     * It receives every screenshot the harness produced, as attachments where the vendor
     * supports them, plus the harness report as context so it does not re-argue measurements.
     */
    private RoleRunner.Outcome runVisualRole(ConfigLoader.Loaded loaded, UserConfig user,
                                             RoleRunner roles, String runId, int attempt,
                                             String implementerVendor, VisualQaRunner.Outcome visual,
                                             boolean dryRun, List<Map<String, Object>> steps,
                                             Budget budget) throws Exception {
        List<Path> screenshots = visual == null ? List.of() : screenshotsOf(visual);
        Path context = null;
        if (visual != null) {
            EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId);
            Path directory = ledger.runDirectory().resolve("context");
            Files.createDirectories(directory);
            context = directory.resolve("visual-" + attempt + "-harness.json");
            Files.writeString(context, Json.writePretty(visual.data()) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        RoleRunner.Outcome outcome = roles.run(loaded, user, "visual_qa",
                stepRunId(runId, "visual-role", attempt), implementerVendor, context, screenshots, dryRun);
        record(steps, budget, "visual_qa", attempt, outcome);
        if (dryRun) return null;
        return outcome;
    }

    /** The screenshot files the harness actually wrote, in the order it wrote them. */
    @SuppressWarnings("unchecked")
    private static List<Path> screenshotsOf(VisualQaRunner.Outcome visual) {
        Object adapter = visual.data().get("adapter");
        if (!(adapter instanceof Map<?, ?> body)) return List.of();
        Object scenarios = ((Map<String, Object>) body).get("scenarios");
        if (!(scenarios instanceof List<?> rows)) return List.of();
        List<Path> files = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> scenario)) continue;
            addIfFile(files, scenario.get("screenshot"));
            if (scenario.get("steps") instanceof List<?> stepRows) {
                for (Object step : stepRows) {
                    if (step instanceof Map<?, ?> one) addIfFile(files, one.get("screenshot_after"));
                }
            }
        }
        return files;
    }

    private static void addIfFile(List<Path> into, Object candidate) {
        if (!(candidate instanceof String text) || text.isBlank()) return;
        Path path = Path.of(text);
        if (Files.isRegularFile(path) && !into.contains(path)) into.add(path);
    }

    /**
     * A run that gave up owes the same accounting as one that finished. Without it the
     * summary of a failure said what went wrong but not what it cost to find out, which is
     * the number an operator needs before deciding to run it again.
     */
    private Outcome stop(EvidenceLedger ledger, Map<String, Object> summary, String reason,
                         List<Map<String, Object>> steps, int attempt, Budget budget) throws Exception {
        summary.put("ok", false);
        summary.put("reason", reason);
        summary.put("next_action", "human_escalation");
        summary.put("attempts_used", (long) attempt);
        summary.put("total_cost_usd", budget.spent());
        summary.put("role_runs", (long) budget.runs());
        summary.put("unpriced_calls", (long) budget.unpriced());
        summary.put("cost_ceiling_binding", budget.unpriced() == 0);
        summary.put("steps", steps);
        // A stop is where the three questions are most often confused, so it is where they are
        // most worth separating: a candidate that passed review can sit inside a run that
        // stopped, and saying only that the run stopped throws that away.
        describeCompletion(summary);
        summary.put("safe_next_step", safeNextStep(reason, summary));
        Path file = ledger.writeReport("task-run", summary);
        Path root = ledger.projectRoot();
        Object base = summary.get("diff_base_commit");
        String fingerprint = base instanceof String commit
                ? new GitRepository(root, processes).sourceFingerprint(commit) : null;
        // A spent subscription with a named successor is a different question from a failure:
        // nothing is wrong with the work, and what is being asked is who may finish it.
        Map<String, Object> pending = pendingFailover(steps);
        ApprovalStore store = new ApprovalStore(root);
        String runIdentity = String.valueOf(summary.get("run_id"));
        String taskIdentity = String.valueOf(summary.get("task_id"));
        HumanDecision decision;
        if (pending != null) {
            summary.put("failover_pending", pending);
            decision = store.createFailover(runIdentity, taskIdentity,
                    failoverQuestion(pending), file, fingerprint);
        } else {
            decision = store.createFailure(runIdentity, taskIdentity, reason, file, fingerprint);
        }
        addDecision(summary, root, decision);
        publishGate(summary, root, decision, String.valueOf(summary.get("task_id")));
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", decision.runId(), "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(decision.runId()))
                        .toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        footer(runIdentity, reason, "human_escalation", budget, summary);
        return new Outcome(false, reason, "human_escalation", file, summary);
    }

    /**
     * Three different questions the summary used to answer with one word.
     *
     * `ok: false` meant, indiscriminately, that the investigation found nothing, that the
     * candidate was rejected by a reviewer, and that the chain ran out of room before it could
     * finish — and an operator reading it could not tell which. Measured on a live run: three
     * reviews, the last of them a clean pass with no blocking finding, a good candidate in the
     * worktree, and a summary that said `budget_exhausted` and nothing else. The work was
     * sound and the report gave nobody a reason to believe it.
     *
     * So they are separated. Whether the candidate has been judged and survived is
     * `candidate_review_passed`. Whether the declared chain got to the end is
     * `workflow_incomplete` plus the stages it still owes. Neither implies the other, and a
     * run can legitimately be a pass on the first and a no on the second.
     */
    @SuppressWarnings("unchecked")
    private static void describeCompletion(Map<String, Object> summary) {
        List<String> declared = new ArrayList<>();
        if (summary.get("workflow") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> stage && stage.get("stage") != null) {
                    declared.add(String.valueOf(stage.get("stage")));
                }
            }
        }
        java.util.Set<String> completed = new java.util.LinkedHashSet<>();
        if (summary.get("completed_stages") instanceof List<?> rows) {
            for (Object row : rows) completed.add(String.valueOf(row));
        }
        java.util.Set<String> skipped = new java.util.LinkedHashSet<>();
        for (String key : List.of("skipped_stages", "inapplicable_stages")) {
            if (!(summary.get(key) instanceof List<?> rows)) continue;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> entry) skipped.add(String.valueOf(entry.get("stage")));
            }
        }
        java.util.Set<String> attempted = new java.util.LinkedHashSet<>();
        if (summary.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> step && step.get("stage") != null) {
                    attempted.add(String.valueOf(step.get("stage")));
                }
            }
        }
        java.util.Set<String> stale = new java.util.LinkedHashSet<>();
        if (summary.get("stages_judging_an_earlier_candidate") instanceof List<?> rows) {
            for (Object row : rows) stale.add(String.valueOf(row));
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String stage : declared) {
            if (completed.contains(stage) || skipped.contains(stage)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            // Three outstanding states, not one. A stage that passed and was then overtaken by
            // a repair has a verdict on disk about a tree that no longer exists; a stage that
            // ran and objected has evidence to read; a stage that never got a turn has a
            // ceiling to raise. Collapsing them sends an operator to the wrong one of three.
            row.put("reason", stale.contains(stage) ? "judged_an_earlier_candidate"
                    : attempted.contains(stage) ? "did_not_pass" : "not_reached");
            pending.add(row);
        }
        summary.put("pending_stages", pending);
        summary.put("workflow_incomplete", !pending.isEmpty());

        long blockers = 0;
        boolean anyJudged = false;
        boolean allClean = true;
        if (summary.get("review_coverage") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> entry)) continue;
                anyJudged = true;
                long found = entry.get("blocking_findings") instanceof Number number
                        ? number.longValue() : 0;
                blockers += found;
                if (found > 0 || !Boolean.TRUE.equals(entry.get("ok"))) allClean = false;
            }
        }
        summary.put("open_blocking_findings", blockers);
        // Never true by default. A chain whose judging stages were all skipped or never
        // reached has not passed review; it has not had one.
        summary.put("candidate_review_passed", anyJudged && allClean);
    }

    /**
     * The one command that moves this run forward without losing what it has.
     *
     * A stop reason is a diagnosis, not an instruction, and the gap between them is where
     * runs get re-run from scratch. Each branch here names the change and then the
     * continuation that keeps the verdicts already paid for.
     */
    private static String safeNextStep(String reason, Map<String, Object> summary) {
        String runId = String.valueOf(summary.get("run_id"));
        String taskId = String.valueOf(summary.get("task_id"));
        String carryOn = "warden approve " + runId + " --decision retry --note \"<why>\""
                + "  then  warden run " + taskId + " --continue " + runId;
        return switch (reason) {
            // Two ceilings, two different edits. Telling somebody whose money ran out to
            // raise the call limit names a number that was never binding, and the run would
            // stop in exactly the same place.
            case "budget_insufficient_to_finish", "budget_exhausted" -> {
                String kept = ". Neither ceiling is part of what the work is judged by, so the "
                        + "verdicts this run already reached on this tree are kept rather than "
                        + "paid for a second time.";
                if ("max_cost_usd".equals(summary.get("budget_limit_hit"))) {
                    yield "raise budgets.max_cost_usd in .warden/tasks/" + taskId + ".yaml — "
                            + summary.get("budget_stop") + " — then: " + carryOn
                            + ". Raising max_role_runs would not change where this run stopped. "
                            + "Note that the ceiling only measures calls whose vendor reported "
                            + "a price: " + summary.get("unpriced_calls") + " of this run's "
                            + "calls reported none and were not counted against it" + kept;
                }
                Object reserve = summary.get("budget_reserve");
                long floor = reserve instanceof Map<?, ?> map
                        && map.get("calls_needed_to_repair_and_finish") instanceof Number needed
                        && summary.get("role_runs") instanceof Number spent
                        ? spent.longValue() + needed.longValue()
                        : 0;
                yield "raise budgets.max_role_runs in .warden/tasks/" + taskId + ".yaml"
                        + (floor > 0 ? " to at least " + floor : "") + ", then: " + carryOn + kept;
            }
            case "quota_exhausted" -> "wait for the quota window named in the vendor message, "
                    + "or add a profile from another vendor, then: " + carryOn;
            case "failover_requires_confirmation" -> "warden approve " + runId
                    + " --decision switch  then  warden run " + taskId + " --continue " + runId;
            case "repair_made_no_progress" -> "the last repair left the candidate byte-for-byte "
                    + "unchanged and the same finding came back, so another round would re-read "
                    + "the same bytes. Read the open findings in `warden report " + runId
                    + " --text`: one an implementer cannot act on is a task for a person, not "
                    + "for another fix round. Change the contract or the finding's premise, "
                    + "then start a new run.";
            case "baseline_failed" -> "the project's own checks were already failing before any "
                    + "vendor ran. Fix that breakage, or change the baseline contract "
                    + "deliberately, then start a new run.";
            case "preflight_outside_scope" -> "revert, commit, or bring into scope the paths "
                    + "named in preexisting_violations, then start a new run.";
            case "contract_mutated" -> "a file under .warden changed while the run was in "
                    + "flight, so nothing this run produced can be trusted against the terms it "
                    + "started with. Restore the contract and start a new run.";
            default -> "read `warden report " + runId + " --text`, then either address what it "
                    + "names and start a new run, or " + carryOn;
        };
    }

    /**
     * Judging stages whose last run predates the last change to the code.
     *
     * A role that reads a diff and passes it has judged one tree, not the task. If an
     * implementer runs afterwards — a browser fix round, a visual finding — the candidate a
     * human is then asked to accept contains a diff that role never saw. Declaring
     * `recheck_after_fix: true` closes it; this reports the gap for a workflow that chooses
     * not to, so the human is told rather than left to notice.
     */
    private static List<String> staleJudgements(List<Map<String, Object>> steps) {
        long lastWrite = -1;
        Map<String, Long> lastJudged = new LinkedHashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            String label = String.valueOf(steps.get(index).get("step"));
            if ("implementer".equals(label)) lastWrite = index;
            else if (!"gates".equals(label) && steps.get(index).get("profile") != null) {
                lastJudged.put(label, (long) index);
            }
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastJudged.entrySet()) {
            if (entry.getValue() < lastWrite) stale.add(entry.getKey());
        }
        return stale;
    }

    /** The stage labels that ran, in order, each named once however often it repeated. */
    private static List<String> executedStages(List<Map<String, Object>> steps) {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (Boolean.TRUE.equals(step.get("reused"))) continue;
            String label = String.valueOf(step.get("step"));
            if (!labels.contains(label)) labels.add(label);
        }
        return labels.isEmpty() ? List.of("no stage") : labels;
    }

    /** The failover awaiting a human, if the run stopped for one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingFailover(List<Map<String, Object>> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            Object pending = steps.get(index).get("failover_pending");
            if (pending instanceof Map<?, ?> map) return (Map<String, Object>) map;
        }
        return null;
    }

    /** What the human is actually being asked, in one line, with both vendors named. */
    private static String failoverQuestion(Map<String, Object> pending) {
        return "role " + pending.get("role") + ": " + pending.get("from_profile")
                + " (" + pending.get("from_vendor") + ") reported a spent subscription. "
                + "Switch to " + pending.get("to_profile") + " (" + pending.get("to_vendor")
                + ")? " + pending.get("independence_after_switch");
    }

    /** The repair branches the plan costed, for a preview that has to print them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recoveryBranches(Map<String, Object> summary) {
        if (!(summary.get("budget_plan") instanceof Map<?, ?> plan)) return List.of();
        if (!(plan.get("recovery_branches") instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> branches = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> branch) branches.add((Map<String, Object>) branch);
        }
        return branches;
    }

    /** One field of the gate this run published, or null if it published none. */
    private static String publishedGate(Map<String, Object> summary, String field) {
        Object published = summary.get("orca_gate");
        if (!(published instanceof Map<?, ?> map)) return null;
        if (!Boolean.TRUE.equals(map.get("published"))) return null;
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Mirror the pending decision onto Orca so it can be answered from somewhere else.
     *
     * Recorded either way, and never fatal. The decision is already durable on disk by the
     * time this runs; a second surface that could not be reached is worth saying out loud and
     * is not worth failing a finished run over.
     */
    private void publishGate(Map<String, Object> summary, Path root, HumanDecision decision,
                             String objective) {
        if (!orcaGate) {
            summary.put("orca_gate", Map.of("published", false, "reason", "disabled"));
            return;
        }
        OrcaDecisionGate.Publication published = new OrcaDecisionGate(processes)
                .publish(root, new OrcaLifecycle(root, decision.runId()), decision, objective);
        // Not narrated here: the footer says it once, with the commands that answer it.
        summary.put("orca_gate", published.toMap());
    }

    private static void addDecision(Map<String, Object> summary, Path root, HumanDecision decision)
            throws Exception {
        ApprovalStore store = new ApprovalStore(root);
        summary.put("decision_path", root.relativize(store.decisionPath(decision.runId()))
                .toString().replace('\\', '/'));
        summary.put("decision_state", decision.state().jsonValue());
        summary.put("decision_kind", decision.kind().jsonValue());
        summary.put("decision_reason", decision.reason());
        summary.put("decision_options", decision.options());
        summary.put("decision_updated_at", decision.updatedAt().toString());
        summary.put("approve_with", "warden approve " + decision.runId()
                + " --decision " + decision.options().get(0)
                + " --expected-updated-at " + decision.updatedAt());
    }

    private static String stepRunId(String runId, String role, int attempt) {
        return runId + "--" + role + "-" + attempt;
    }

    /**
     * Where one stage's evidence goes.
     *
     * Named after the role while a role has one stage, and after the stage as soon as it has
     * more than one. The second half is not a preference. `review` and `review-second` are
     * the same role at the same attempt, so both resolved to `<run>--reviewer-1` and the one
     * that finished second overwrote the first's prompt, raw output and artifact. Measured on
     * a live run: an Opus review that passed left nothing on disk but a ledger line, and that
     * line's `artifact_path` then pointed at the other vendor's file — worse than losing the
     * evidence, because following the pointer reads as if it were Opus's.
     *
     * A role with a single stage keeps the old path, so nothing an operator has already
     * collected changes name.
     */
    private static String stageRunId(String runId, Workflow workflow, Workflow.Stage stage,
                                     int attempt) {
        if (stage.kind() != Workflow.Kind.ROLE) return stepRunId(runId, stage.label(), attempt);
        boolean shared = workflow.stagesFor(stage.role()).size() > 1;
        return stepRunId(runId, shared ? Workflow.slug(stage.name()) : stage.label(), attempt);
    }

    private Path writeContext(EvidenceLedger ledger, int attempt, String kind, String body)
            throws Exception {
        Path directory = ledger.runDirectory().resolve("context");
        Files.createDirectories(directory);
        Path file = directory.resolve("fix-" + attempt + "-" + kind + ".md");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * What the implementer is handed when the browser disagreed with the contract. The
     * harness already wrote down which assertion failed and why; repeating that here beats
     * a pointer to a file the vendor may not open.
     */
    private String visualContext(VisualQaRunner.Outcome visual) {
        StringBuilder builder = new StringBuilder("# The browser check failed\n\n");
        Object adapter = visual.data().get("adapter");
        if (adapter instanceof Map<?, ?> body && body.get("scenarios") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> scenario) || Boolean.TRUE.equals(scenario.get("ok"))) continue;
                builder.append("## `").append(scenario.get("raw")).append("`\n\n");
                if (scenario.get("why") != null) {
                    builder.append("- why: ").append(scenario.get("why")).append('\n');
                }
                if (scenario.get("viewport_effective") instanceof Map<?, ?> effective) {
                    builder.append("- rendered at: ").append(effective.get("width")).append('x')
                            .append(effective.get("height")).append('\n');
                }
                if (scenario.get("console_errors") instanceof List<?> errors && !errors.isEmpty()) {
                    builder.append("- console errors: ").append(errors).append('\n');
                }
                if (scenario.get("screenshot") != null) {
                    builder.append("- screenshot: `").append(scenario.get("screenshot")).append("`\n");
                }
                builder.append('\n');
            }
        } else {
            builder.append("Report: `").append(visual.report()).append("`\n\n");
        }
        builder.append("""
                Fix the page, not the scenario. The visual contract lives in the task file, which
                is hashed — editing it to make the check agree aborts the run.

                If the scenario is genuinely wrong about the page — it names a control that was
                renamed, or asserts a viewport the design does not support — say so in your
                summary with what the correct assertion would be, and change nothing else.
                """);
        return builder.toString();
    }

    private String gateContext(GateRunner.Outcome gate) {
        StringBuilder body = new StringBuilder("""
                # Machine gate failed

                Failure: `%s`
                Full report: `%s`

                """.formatted(gate.code(), gate.report()));
        Object violations = gate.data() == null ? null : gate.data().get("violations");
        if (violations instanceof List<?> list && !list.isEmpty()) {
            body.append("Paths changed outside the declared scope:\n\n");
            for (Object path : list) body.append("- `").append(path).append("`\n");
            body.append('\n');
        }
        Object commands = gate.data() == null ? null : gate.data().get("commands");
        if (commands instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> command && command.get("exit_code") instanceof Number n
                        && n.longValue() != 0) {
                    body.append("Failing command: `").append(command.get("command")).append("`\n");
                    body.append("Exit code: ").append(command.get("exit_code")).append("\n\n");
                }
            }
        }
        body.append("""
                Fix the cause, not the check. Do not edit the task contract, the declared scope or
                the acceptance commands to make this pass — the configuration is hashed and a
                change to it aborts the run.
                """);
        return body.toString();
    }

    private long blockingFindings(Path root, RoleRunner.Outcome review) throws Exception {
        Map<String, Object> artifact = readArtifact(root, review);
        if (artifact == null) return 0;
        if (!(artifact.get("findings") instanceof List<?> list)) return 0;
        return list.stream()
                .filter(item -> item instanceof Map<?, ?> map && "P1".equals(map.get("severity")))
                .count();
    }

    private String reviewContext(Path root, RoleRunner.Outcome review) throws Exception {
        Map<String, Object> artifact = readArtifact(root, review);
        StringBuilder builder = new StringBuilder("# Independent review returned a failing verdict\n\n");
        if (artifact == null) return builder.append("(the reviewer artifact could not be read)\n").toString();
        builder.append("Reviewer summary: ").append(artifact.get("summary")).append("\n\n");
        if (artifact.get("findings") instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> finding) || !"P1".equals(finding.get("severity"))) continue;
                index++;
                builder.append("## ").append(index).append(". ").append(finding.get("path"));
                if (finding.get("line") != null) builder.append(':').append(finding.get("line"));
                builder.append("\n\n")
                        .append("- expected: ").append(finding.get("expected")).append('\n')
                        .append("- actual: ").append(finding.get("actual")).append('\n');
                if (finding.get("scenario") != null) {
                    builder.append("- reproduce: ").append(finding.get("scenario")).append('\n');
                }
                builder.append('\n');
            }
        }
        builder.append("""
                Each finding states an expected and an actual. Address the difference, or, if the
                reviewer is wrong, say so in your summary with the evidence that shows it — do not
                silently leave a finding unaddressed.

                If a finding is not something a change to this diff can fix — the acceptance
                command is too weak, an access grant is missing, the contract itself is wrong —
                say that in your summary and change nothing on its account. A repair that leaves
                the candidate byte-for-byte unchanged ends the run rather than buying another
                reading of the same bytes, so an honest "this one is not mine to fix" is worth
                more here than a token edit.
                """);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArtifact(Path root, RoleRunner.Outcome review) throws Exception {
        Object path = review.details() == null ? null : review.details().get("artifact_path");
        if (!(path instanceof String relative)) return null;
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) return null;
        Object parsed = Json.parse(Files.readString(file));
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }
}
