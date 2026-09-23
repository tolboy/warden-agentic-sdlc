package dev.warden.run;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.RunOverride;
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
import dev.warden.ledger.HomeCorpus;
import dev.warden.ledger.Findings;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleContract;
import dev.warden.role.RoleResolver;
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

    /**
     * What {@code warden do --prepare} already did before this loop started.
     *
     * The planner is not a workflow stage: the shipped chain is unchanged. Preparation
     * still costs a vendor call, still writes a {@code role_run}, and still has to be in
     * the budget arithmetic, or a report would show it as free.
     *
     * @param mode            {@code off}, {@code auto} or {@code always}, as the invocation asked
     * @param runReserved     the caller already fenced {@code run.json} (the planner wrote
     *                        evidence into it)
     * @param roleRuns        vendor calls already spent, counted against {@code max_role_runs}
     * @param costUsd         cost already spent, counted against {@code max_cost_usd}
     * @param unpriced        of those calls, how many reported no price
     * @param includedInPlan  whether {@link CallPlan} should reserve a planner call
     */
    public record Preparation(String mode, boolean runReserved, int roleRuns, double costUsd,
                              int unpriced, boolean includedInPlan,
                              Map<String, Object> estimateAtReservation,
                              Boolean reservationMatchedContract) {
        public static final Preparation NONE = new Preparation("off", false, 0, 0, 0, false);

        public Preparation(String mode, boolean runReserved, int roleRuns, double costUsd,
                           int unpriced, boolean includedInPlan) {
            this(mode, runReserved, roleRuns, costUsd, unpriced, includedInPlan, null, null);
        }

        /**
         * Rebuild what {@code warden do --prepare} recorded on the reservation, so a second
         * process (Conductor's {@code warden run}, or an operator following --draft-only)
         * counts the planner the same way the in-process loop does.
         */
        public static Preparation fromReservation(Map<String, Object> reservation) {
            String mode = reservation.get("prepare") instanceof String value ? value : "off";
            int runs = reservation.get("preparation_role_runs") instanceof Number number
                    ? number.intValue() : 0;
            double cost = reservation.get("preparation_cost_usd") instanceof Number number
                    ? number.doubleValue() : 0;
            int unpriced = reservation.get("preparation_unpriced") instanceof Number number
                    ? number.intValue() : 0;
            boolean included = false;
            if (reservation.get("budget_plan") instanceof Map<?, ?> plan) {
                included = String.valueOf(plan.get("paying_stages")).contains("planner");
            }
            Map<String, Object> estimate = null;
            if (reservation.get("budget_plan_at_reservation") instanceof Map<?, ?> earlier) {
                Map<String, Object> copied = new LinkedHashMap<>();
                earlier.forEach((key, value) -> copied.put(String.valueOf(key), value));
                estimate = copied;
            }
            Boolean matched = reservation.get("reservation_matched_contract") instanceof Boolean flag
                    ? flag : null;
            return new Preparation(mode, true, runs, cost, unpriced, included, estimate, matched);
        }
    }

    private final ProcessRunner processes;
    private final VisualCheck visualCheck;
    private final Progress progress;
    private final Workspace workspace;
    private final boolean orcaGate;
    private final Preparation preparation;
    private final RunOverride override;
    /** Nanoseconds, as {@link System#nanoTime}; replaced only by the suite. */
    private java.util.function.LongSupplier clock = System::nanoTime;
    /** How the loop waits between rate-limit retries; the suite replaces it. */
    private java.util.function.LongConsumer sleeper;

    public TaskLoop(ProcessRunner processes) {
        this(processes, (VisualCheck) null);
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
        return keepClock(new TaskLoop(processes, visualCheck, narration, workspace, orcaGate,
                preparation, override));
    }

    /**
     * The same loop, timed by another clock. A chain deadline is measured in minutes, and the
     * suite cannot wait that long for one to pass.
     */
    public TaskLoop withClock(java.util.function.LongSupplier nanos) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.clock = nanos;
        return copy;
    }

    /**
     * The same loop, pausing between rate-limit retries through {@code next} instead of the
     * clock. The suite cannot wait out a vendor's backoff.
     */
    public TaskLoop withSleeper(java.util.function.LongConsumer next) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.sleeper = next;
        return copy;
    }

    private TaskLoop keepClock(TaskLoop copy) {
        copy.clock = clock;
        copy.sleeper = sleeper;
        return copy;
    }

    /**
     * The same loop, reporting to the board it is running on. Separate from the terminal
     * narration because the two carry different things: every line goes to the terminal, and
     * only what a person would act on goes to the card.
     */
    public TaskLoop withWorkspace(Workspace board) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, Workspace.guarded(board),
                orcaGate, preparation, override));
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
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, publish,
                preparation, override));
    }

    public TaskLoop withPreparation(Preparation preparation) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, orcaGate,
                preparation == null ? Preparation.NONE : preparation, override));
    }

    public TaskLoop withOverride(RunOverride next) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, orcaGate,
                preparation, next == null ? RunOverride.NONE : next));
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
        this(processes, visualCheck, progress, workspace, orcaGate, Preparation.NONE);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate, Preparation preparation) {
        this(processes, visualCheck, progress, workspace, orcaGate, preparation, RunOverride.NONE);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate, Preparation preparation,
                    RunOverride override) {
        this.processes = processes;
        this.visualCheck = visualCheck;
        this.progress = progress;
        this.workspace = workspace;
        this.orcaGate = orcaGate;
        this.preparation = preparation == null ? Preparation.NONE : preparation;
        this.override = override == null ? RunOverride.NONE : override;
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
            // A visual role that brought no pixels, or a vendor whose MCP configuration file
            // is missing, is the tooling failing, not a verdict on the candidate; the readings
            // before it stand.
            "visual_qa_no_evidence", "mcp_config_missing",
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
            "budget_insufficient_to_finish",
            // A corpus that cannot be written said nothing about the diff. The refusal is
            // there so a measurement is not lost, and making the operator pay for the
            // implementer and both readers again to recover it is the exact cost this slice
            // promises never to charge: recovery replays delivery, not execution. The
            // fingerprint and acceptance checks below still apply, so a run that had already
            // moved the tree before the corpus refused declines reuse on its own.
            "ledger_unavailable",
            // Every stop a vendor's endpoint caused rather than its reading of the diff. See
            // INFRASTRUCTURE_REASONS for how each is reached and why none of them is a verdict.
            // `quota_exhausted` was missing for longer than the others, and its own next step
            // told the operator to wait and continue: the continuation then declined every
            // verdict because the stop was "about the work", and paid for all of them again.
            "quota_exhausted",
            "rate_limited",
            "failover_requires_confirmation",
            // A rung of the escalation ladder that nobody can fill is a fact about the roster,
            // exactly like an independent reader that has run out.
            "escalation_unavailable",
            "independent_review_unavailable",
            "role_timed_out",
            "vendor_call_failed",
            "vendor_protocol_failed",
            "prompt_undeliverable",
            "orca_worker_not_started");

    /**
     * Role outcome codes that describe the call rather than the candidate, and the stop reason
     * each one becomes.
     *
     * They all used to become `<role>_failed`, which reads as "the reviewer objected" and is
     * treated as a verdict by every continuation. Measured on the first external trial: a
     * reviewer killed by the operator's session limit was recorded as `reviewer_failed`, the
     * retry declined the first reader's clean pass as if it had been overruled, and a second
     * paid Codex reading reached the same conclusion about the same bytes.
     *
     * None of them is a verdict, and none of them is an implementer's to repair:
     *
     *  - a wall clock that ran out bounds the call, not the diff;
     *  - a vendor process that exited non-zero with nothing Warden recognises — a crash, a
     *    refused login, a dropped connection — has said nothing about the work at all;
     *  - an answer that is not a readable, complete, schema-valid artifact is an answer Warden
     *    could not read, so whatever verdict it held is unknown rather than negative;
     *  - a prompt that could not be delivered never reached the vendor;
     *  - a rate limit, a spent subscription and a turn ceiling are allowances.
     *
     * A negative verdict is never one of these. A reviewer that returns `fail`, or a pass
     * carrying a P1, produces an `ok` role outcome and routes through its findings; a role
     * that honestly reports itself blocked keeps its generic reason. Being on this list only
     * lets a continuation <em>consider</em> earlier verdicts: every per-stage check in
     * {@link #reusableJudgements} still applies, and the stage that failed has no passing row
     * to reuse, so it is always dispatched again.
     */
    private static final Map<String, String> INFRASTRUCTURE_REASONS = Map.ofEntries(
            Map.entry("role_timeout", "role_timed_out"),
            Map.entry("role_command_failed", "vendor_call_failed"),
            Map.entry("role_artifact_unparseable", "vendor_protocol_failed"),
            Map.entry("role_artifact_incomplete", "vendor_protocol_failed"),
            Map.entry("role_artifact_schema_violation", "vendor_protocol_failed"),
            Map.entry("role_prompt_undeliverable", "prompt_undeliverable"),
            Map.entry("role_rate_limited", "rate_limited"),
            Map.entry("role_quota_exhausted", "quota_exhausted"),
            // `reviewer_failed` reads as "the reviewer objected"; a spent turn ceiling is the
            // opposite of that, and the difference decides both what an operator goes and
            // reads and whether the stages that already passed survive the retry.
            Map.entry("role_turns_exhausted", "turn_ceiling_reached"),
            // An Orca worker that never took its first turn read nothing. Measured 2026-09-22:
            // Claude Code stopped on its once-per-repository trust question in a fresh
            // worktree, Warden fenced the worker after 45 s, the stop was `reviewer_failed`,
            // and the `--continue` that followed paid the implementer and the first reader
            // again for a tree they had already passed. `role_orca_reported_failure` is not
            // here on purpose: that worker ran and said the work failed.
            Map.entry("role_orca_start_failed", "orca_worker_not_started"),
            Map.entry("role_orca_no_coordinator", "orca_worker_not_started"),
            Map.entry("role_orca_unavailable", "orca_worker_not_started"),
            Map.entry("role_orca_no_worktree", "orca_worker_not_started"),
            Map.entry("role_orca_worker_active", "orca_worker_not_started"),
            Map.entry("role_orca_timeout", "role_timed_out"));

    /** What kind of trouble each infrastructure stop was, for a reader who wants one word. */
    private static final Map<String, String> INFRASTRUCTURE_CAUSES = Map.of(
            "role_timed_out", "timeout",
            "vendor_call_failed", "call_failed",
            "vendor_protocol_failed", "protocol",
            "prompt_undeliverable", "prompt_delivery",
            "rate_limited", "rate_limit",
            "quota_exhausted", "quota",
            "turn_ceiling_reached", "turn_ceiling",
            "orca_worker_not_started", "orca_start");

    /**
     * Whether a call timed out on a wall clock the chain deadline had lowered.
     *
     * Both runners say it their own way: the direct runner as `role_timeout` after it killed
     * the process tree, Orca as `role_orca_timeout`, whose worker may still need settling. The
     * deadline is the cause either way, and naming the profile's limit instead would send the
     * operator to a number that was never binding.
     */
    public static boolean timedOutUnderDeadline(String roleCode, Map<String, Object> details) {
        if (!"role_timeout".equals(roleCode) && !"role_orca_timeout".equals(roleCode)) return false;
        return details != null && "max_elapsed_minutes".equals(details.get("wall_clock_capped_by"));
    }

    /**
     * The chain block for a run that failed before the loop could start.
     *
     * A `warden run --continue A` that fails validation is still a link in A's chain, and a
     * run that later continues it must inherit A's spend. Without this a configuration typo
     * between two continuations reset the task's call ceiling: the review of this change
     * reproduced a second implementer dispatch under `max_role_runs: 1`. The failed run spent
     * nothing, so the block is A's chain with this run appended. A continuation that would
     * not carry verdicts — a rejection — starts afresh, exactly as the loop decides.
     */
    public static Map<String, Object> chainForPreflightFailure(Path root, String priorRunId,
                                                               boolean inherits, String runId) {
        Chain earlier = priorRunId != null && inherits ? Chain.after(root, priorRunId) : Chain.NONE;
        List<String> runs = new ArrayList<>(earlier.runs());
        runs.add(runId);
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("runs", runs);
        chain.put("role_runs", earlier.roleRuns());
        chain.put("cost_usd", earlier.costUsd());
        chain.put("unpriced_calls", earlier.unpricedCalls());
        chain.put("fix_attempts", earlier.fixAttempts());
        chain.put("elapsed_seconds", earlier.elapsedSeconds());
        chain.put("elapsed_known", earlier.elapsedKnown());
        chain.put("blocking_reviews_seen", earlier.blockingReviewsSeen());
        chain.put("escalation_rung", earlier.escalationRung());
        return chain;
    }

    /** True when a role outcome code is about the call, not about the candidate. */
    public static boolean isInfrastructureFailure(String roleCode) {
        return roleCode != null && INFRASTRUCTURE_REASONS.containsKey(roleCode);
    }

    /**
     * Whether a stop reason is a judgement on the candidate.
     *
     * A continuation throws away earlier verdicts when it is, and keeps them when it is not.
     * The list above carries the argument for each entry; this is the seam where the answer
     * can be checked without paying for a run to reach every one of them.
     */
    public static boolean isAboutTheWork(String reason) {
        return !NOT_ABOUT_THE_WORK.contains(reason);
    }

    /**
     * The stop reason a continuation judges an earlier run by.
     *
     * Usually the recorded one. A summary written before a role code was classified still says
     * the generic `<role>_failed`, and its failed step still carries the code that says what
     * happened; reading that code lets a run stopped by, say, an Orca worker that never started
     * keep the verdicts it had already paid for. Only the generic reason is re-read, and only
     * when the failed step's code is one {@link #isInfrastructureFailure} names.
     */
    public static String reasonForContinuation(Map<String, Object> prior) {
        String recorded = String.valueOf(prior.get("reason"));
        if (!recorded.endsWith("_failed") || !(prior.get("steps") instanceof List<?> steps)) {
            return recorded;
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            if (!(steps.get(index) instanceof Map<?, ?> row)) continue;
            if (!Boolean.FALSE.equals(row.get("ok"))) continue;
            String mapped = INFRASTRUCTURE_REASONS.get(String.valueOf(row.get("code")));
            return mapped != null ? mapped : recorded;
        }
        return recorded;
    }

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
        EvidenceLedger ledger = new EvidenceLedger(root, runId, user.home());
        if (carried.continuesRun()) {
            ledger.bindParent(EvidenceLedger.runInstanceIdOf(root, carried.fromRunId()));
        }
        // Reservation is the first mutation. A duplicate controller is refused before it can
        // overwrite evidence, spend a token, or start a second Orca worker in the same tree.
        // Preparation already reserved when the planner ran: that call wrote evidence into
        // this directory, and reserving again would refuse the rest of the run as a duplicate.
        // A second process — Conductor's inner `warden run`, or `warden run` after
        // `--draft-only` — did not make that reservation itself, so it joins it.
        Preparation prior = preparation;
        Path reservation = ledger.runDirectory().resolve("run.json");
        if (prior.runReserved()) {
            // The in-process caller knows the spend; the reservation on disk knows what the
            // plan was measured against before the contract existed. Both are kept.
            Preparation onDisk = Preparation.fromReservation(ledger.claimPreparedLoop(task.id()));
            prior = new Preparation(prior.mode(), true, prior.roleRuns(), prior.costUsd(),
                    prior.unpriced(), prior.includedInPlan(), onDisk.estimateAtReservation(),
                    onDisk.reservationMatchedContract());
        } else if (Files.isRegularFile(reservation)) {
            prior = Preparation.fromReservation(ledger.claimPreparedLoop(task.id()));
        } else {
            ledger.reserveWorkflowRun(task.id());
        }
        // Both values are selected before any vendor can write. HEAD and the contract files
        // are mutable names/bytes; resolving them inside each later role or gate lets a worker
        // move the baseline or rewrite its own acceptance criteria.
        GitRepository git = new GitRepository(root, processes);
        String diffBaseCommit = git.mergeBase(task.baseRef());
        // The whole `.warden` tree, not two files: a second task contract, a rewritten policy
        // or a deleted scenario file are all terms the run would then be judged by.
        Map<String, String> configSnapshot = WardenTree.snapshot(root);
        String contractHash = WardenTree.digest(configSnapshot);
        Budget budget = new Budget(task.budget().maxRoleRuns(), task.budget().maxCostUsd(),
                task.budget().maxElapsedMinutes(), clock);
        budget.alreadySpent(prior.roleRuns(), prior.costUsd(), prior.unpriced());
        // Only a continuation that carries verdicts continues the chain; see Chain.
        if (carried.continuesRun() && carried.reuseJudgements()) {
            budget.inherit(Chain.after(root, carried.fromRunId()));
        }
        // One runner for the whole loop: a vendor that ran out at implement time must not be
        // dispatched again at review time. The gate makes the budget count vendor calls, not
        // role invocations, so a failover cannot spend more than the task allowed, and it
        // lowers each call's wall clock to what the chain's deadline leaves.
        RoleRunner.DispatchGate dispatchGate = new RoleRunner.DispatchGate() {
            @Override public void requireDispatch() { budget.requireRoleRun(); }

            @Override public java.time.Duration wallClockCap() { return budget.wallClockCap(); }

            // Asked before a failover dispatch. Answering no makes the role return the spent
            // call as its outcome, so the loop still charges it; throwing from the next
            // dispatch instead lost that call's cost and its unpriced count.
            @Override public boolean hasRoom() { return budget.hasRoom(); }

            @Override public void reserve(Double declaredBound) { budget.reserveSelected(declaredBound); }

            @Override public void settleAttempt(Object costUsd, Double declaredBound) {
                budget.settleAttempt(costUsd, declaredBound);
            }
        };
        RoleRunner roles = new RoleRunner(processes, dispatchGate, diffBaseCommit, runId,
                authorizedFailover, progress);
        if (sleeper != null) roles.withSleeper(sleeper);
        if (carried.continuesRun() && !authorizedFailover.isEmpty()
                && "switch".equals(new ApprovalStore(root).read(carried.fromRunId()).decision())) {
            Map<String, Object> previous = Json.parseObject(Files.readString(root.resolve(".warden/runs")
                    .resolve(carried.fromRunId()).resolve("task-run.json")));
            if (previous.get("failover_pending") instanceof Map<?, ?> pending
                    && pending.get("exhausted_profiles") instanceof List<?> names) {
                java.util.Set<String> excluded = new java.util.LinkedHashSet<>();
                names.forEach(name -> excluded.add(String.valueOf(name)));
                roles.excluding(excluded);
            }
        }
        // Contract, then whatever the outer command left for this run id, then this
        // process's own flags. Conductor's inner `warden run` has no flags of its own, so
        // the middle term is how `warden do --conductor --use ...` reaches the roster at all.
        RunOverride overlay = loaded.task().use()
                .merged(carried.reuseJudgements() ? priorOverride(root, carried.fromRunId()) : RunOverride.NONE)
                .merged(handedOver(root, runId))
                .merged(this.override);
        overlay.pin(roles);
        roles.overlay(overlay);
        GateRunner gates = new GateRunner(processes).withHome(user.home());

        Workflow workflow = user.policy() != null ? user.policy().workflow() : Workflow.builtIn();
        if (task.visualQa().agentEvidence()) workflow = workflow.forAgentEvidence();
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        boolean reviewRequired = reviewByRisk
                && user.policy().roles().containsKey("reviewer");
        // Opt-in, and only meaningful for a task that asked for visual QA at all.
        boolean visualRoleConfigured = user.policy() != null
                && user.policy().roles().containsKey("visual_qa");
        // Built here, from the same predicate the engine routes by, and consulted before the
        // first dispatch as well as before every repair. The preparation reservation uses
        // the same helper so run.json cannot describe a different chain from this summary.
        CallPlan callPlan = planFor(workflow, user, task,
                prior.includedInPlan() ? dev.warden.run.Preparation.payingStages(user) : List.of());

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", task.id());
        if (!overlay.isEmpty()) summary.put("run_override", overlay.toMap());
        summary.put("diff_base_commit", diffBaseCommit);
        summary.put("contract_sha256", contractHash);
        // The narrower hash a later run compares against when deciding whether a verdict it
        // already paid for still describes this tree. See TaskSpec.ResolvedTask.
        summary.put("acceptance_sha256", WardenTree.acceptanceDigest(configSnapshot,
                root.relativize(loaded.taskFile()).toString().replace('\\', '/'),
                task.acceptanceFingerprint()));
        summary.put("risk", task.risk());
        summary.put("dry_run", dryRun);
        summary.put("prepare", prior.mode());
        summary.put("max_fix_attempts", task.maxFixAttempts());
        summary.put("budget_max_role_runs", task.budget().maxRoleRuns());
        summary.put("budget_max_cost_usd", task.budget().maxCostUsd());
        if (task.budget().maxElapsedMinutes() != null) {
            summary.put("budget_max_elapsed_minutes", task.budget().maxElapsedMinutes());
        }
        describeChain(summary, budget, runId, task);
        summary.put("review_required", reviewRequired);
        summary.put("visual_qa_required", task.visualQa().required());
        summary.put("visual_qa_role", visualRoleConfigured);
        summary.put("baseline_required", !task.baselineCommands().isEmpty());
        summary.put("baseline_commands", task.baselineCommands());
        summary.put("acceptance_commands", task.acceptanceCommands());
        summary.put("workflow_declared", user.policy() != null && user.policy().workflowDeclared());
        summary.put("failover_mode", user.policy() == null ? "confirm" : user.policy().failoverMode());
        if (!authorizedFailover.isEmpty()) summary.put("authorized_failover", authorizedFailover);
        summary.put("workflow", workflow.toList());
        Map<String, Object> plan = new LinkedHashMap<>(callPlan.toMap(task.budget().maxRoleRuns(),
                user.policy() == null ? "full" : user.policy().repairReserve()));
        if (task.budget().maxElapsedMinutes() != null) {
            plan.put("time_reserve", "not reserved: budgets.max_elapsed_minutes ("
                    + task.budget().maxElapsedMinutes() + ") bounds the execution time of this "
                    + "run and every continuation of it. No vendor call starts with less than "
                    + "a minute left, and each call's wall clock is lowered to what is left. A "
                    + "call's duration is not predicted, so a repair is never refused in advance "
                    + "for time; gates and the baseline are bounded by timeout_minutes instead");
        }
        if (!budget.inherited().runs().isEmpty()) {
            plan.put("already_spent_by_chain", Map.of(
                    "runs", budget.inherited().runs(),
                    "role_runs", budget.inherited().roleRuns(),
                    "fix_attempts", budget.inherited().fixAttempts(),
                    "calls_remaining_under_cap", (long) budget.remaining()));
        }
        summary.put("budget_plan", plan);
        // The estimate the reservation was made on, when a planner compiled the contract
        // afterwards. It is kept beside the plan the loop measures by, with its own phase, so
        // a reader can see what was predicted before any contract existed and whether the
        // compiled contract changed the paying stages — rather than finding run.json and
        // task-run.json naming different stages with no record of why.
        if (prior.estimateAtReservation() != null) {
            summary.put("budget_plan_at_reservation", prior.estimateAtReservation());
            summary.put("reservation_matched_contract", prior.reservationMatchedContract());
        }
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
        // Every reader the chain will need, asked about before the writer it will need to
        // differ from is paid. A two-vendor roster with one hat on each head used to find
        // out at the review stage, after the implementer had already written; the arithmetic
        // was available before anything was spent, and a person is entitled to it then.
        // A strict money cap has to be provable before it is promised. Every paying profile
        // on the roster declares an upper bound per call, or the cap cannot be enforced and
        // the run says so instead of spending under a number that was never a cap.
        if (task.budget().strictCostCap()) {
            Map<String, Object> unbounded = strictCapGap(workflow, user, task, reviewByRisk,
                    overlay, authorizedFailover);
            if (unbounded != null) {
                summary.put("cost_cap", "strict");
                summary.put("cost_cap_gap", unbounded);
                summary.put("resolution", String.valueOf(unbounded.get("message")));
                if (!dryRun) return stop(ledger, summary, "cost_cap_unenforceable", steps, 0, budget);
                summary.putIfAbsent("would_stop", "cost_cap_unenforceable");
            } else {
                summary.put("cost_cap", "strict");
                budget.strictBounds(strictBounds(workflow, user, task, reviewByRisk,
                        overlay, authorizedFailover));
            }
        }
        // A policy file that exists and does not parse is not the same as no policy. Treating
        // it as none ran the built-in chain, skipped every role stage as `role_not_configured`
        // and previewed `ok` over a candidate nobody would write and nobody would read.
        // Measured on a dry run whose multi-line flow mapping the strict YAML reader refused.
        if (user.policyPresent() && user.policy() == null) {
            String problem = user.problems() == null ? null : user.problems().get("policy.yaml");
            summary.put("policy_problem", problem);
            summary.put("resolution", "policy.yaml does not parse: " + problem + ". Fix the file "
                    + "(warden doctor and warden roster both list the problem) before anything "
                    + "is dispatched; the built-in chain is not a substitute for a policy you wrote.");
            progress.line("      policy.yaml does not parse: " + problem);
            progress.line("      stopping before the first dispatch rather than running the "
                    + "built-in chain over it");
            return stop(ledger, summary, "policy_invalid", steps, 0, budget);
        }
        Map<String, Object> overlayGap = overlayGap(root, workflow, user, task, reviewByRisk, roles,
                overlay);
        if (overlayGap != null) {
            summary.put("unavailable_role", overlayGap);
            summary.put("resolution", String.valueOf(overlayGap.get("message")));
            progress.line("      " + overlayGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, "run_override_invalid", steps, 0, budget);
            summary.putIfAbsent("would_stop", "run_override_invalid");
        }
        Map<String, Object> judgeGap = judgeGap(workflow, user, task, reviewByRisk, roles);
        if (judgeGap != null) {
            summary.put("unavailable_role", judgeGap);
            summary.put("resolution", String.valueOf(judgeGap.get("message")));
            progress.line("      " + judgeGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, RoleResolver.JUDGE_NOT_READ_ONLY, steps, 0, budget);
            summary.putIfAbsent("would_stop", RoleResolver.JUDGE_NOT_READ_ONLY);
        }
        Map<String, Object> toolingGap = toolingGap(root, workflow, user, task, reviewByRisk, roles);
        if (toolingGap != null) {
            String stopReason = String.valueOf(toolingGap.get("stop_reason"));
            summary.put("unavailable_role", toolingGap);
            summary.put("resolution", String.valueOf(toolingGap.get("message")));
            progress.line("      " + toolingGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, stopReason, steps, 0, budget);
            summary.putIfAbsent("would_stop", stopReason);
        }
        Map<String, Object> readerGap = readerGap(root, workflow, user, task, reviewByRisk, roles);
        if (readerGap != null) {
            summary.put("unavailable_role", readerGap);
            summary.put("resolution", String.valueOf(readerGap.get("message")));
            progress.line("      " + readerGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, "independent_review_unavailable", steps, 0, budget);
            summary.putIfAbsent("would_stop", "independent_review_unavailable");
        }
        String candidateFingerprint = git.sourceFingerprint(diffBaseCommit);
        summary.put("candidate_fingerprint", candidateFingerprint);
        Map<String, Map<String, Object>> reusable = carried.reuseJudgements()
                ? reusableJudgements(root, carried.fromRunId(), contractHash,
                        String.valueOf(summary.get("acceptance_sha256")), candidateFingerprint,
                        workflow, user, summary, overlay, authorizedFailover)
                : Map.of();
        // After the preflights, so a stage nobody can fill has already been reported as the
        // fault it is rather than as a blank in a table, and before the header, because the
        // header is the last thing printed before money starts being spent.
        List<Map<String, Object>> cast = cast(root, workflow, user, task, reviewByRisk, roles, overlay);
        summary.put("cast", cast);
        header(task, runId, workflow, dryRun, callPlan, budget, cast);
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
        if (!task.reproduceCommands().isEmpty()) {
            summary.put("reproduction_commands", task.reproduceCommands());
            if (dryRun) {
                steps.add(Map.of("step", "reproduction", "attempt", 0L, "dry_run", true));
                progress.line("red   must fail before any vendor is dispatched: "
                        + String.join(", ", task.reproduceCommands()));
            } else if (!WardenTree.sourcePaths(git.changedPaths(diffBaseCommit)).isEmpty()) {
                // A candidate cannot demonstrate the original defect. Only a verified receipt
                // from this continuation can speak for the base; missing proof is made visible.
                ReproductionReceipt receipt = carried.continuesRun()
                        ? reusableReproduction(root, carried.fromRunId(), task.id(),
                                String.valueOf(summary.get("acceptance_sha256")), diffBaseCommit)
                        : null;
                if (receipt == null) {
                    summary.put("reproduction_status", "not_run_candidate_present");
                    steps.add(Map.of("step", "reproduction", "attempt", 0L,
                            "status", "not_run_candidate_present"));
                    progress.line("red   not_run_candidate_present: no verified base proof; "
                            + "reproduction cannot run on a candidate");
                } else {
                    summary.put("reproduction_ok", true);
                    summary.put("reproduction_status", "reused");
                    summary.put("reproduction_reused_from", receipt.runId());
                    summary.put("reproduction_receipt_run", receipt.runId());
                    summary.put("reproduction_report", receipt.report().toString());
                    summary.put("reproduction_report_sha256", receipt.sha256());
                    summary.put("reproduction_results", receipt.commands());
                    steps.add(Map.of("step", "reproduction", "attempt", 0L, "ok", true,
                            "reused", true, "reused_from", receipt.runId(),
                            "report", receipt.report().toString()));
                    progress.line("red   reused verified base proof from " + receipt.runId());
                }
            } else {
                progress.line("red   checking acceptance fails before the first vendor: "
                        + String.join(", ", task.reproduceCommands()));
                GateRunner.Outcome reproduction;
                try (Heartbeat alive = Heartbeat.over("reproduction gates", progress, workspace)) {
                    reproduction = gates.runReproduction(loaded, stepRunId(runId, "reproduction", 0),
                            configSnapshot, diffBaseCommit);
                }
                steps.add(Map.of("step", "reproduction", "attempt", 0L, "ok", reproduction.ok(),
                        "code", reproduction.code(), "report", reproduction.report().toString()));
                summary.put("reproduction_ok", reproduction.ok());
                summary.put("reproduction_code", reproduction.code());
                summary.put("reproduction_report", reproduction.report().toString());
                summary.put("reproduction_report_sha256", GitRepository.contentSha256(reproduction.report()));
                summary.put("reproduction_results", reproduction.data().getOrDefault("commands", List.of()));
                if (!reproduction.ok()) {
                    String reason = "reproduction_passed_before_change".equals(reproduction.code())
                            ? "reproduction_passed_before_change" : "reproduction_inconclusive";
                    summary.put("resolution", "reproduction_passed_before_change".equals(reason)
                            ? "Acceptance already passed on the unchanged tree: "
                                    + reproduction.data().get("passed_commands")
                            : "Reproduction did not prove the defect: " + reproduction.code());
                    progress.line("red   " + reason + "; no vendor dispatched");
                    return stop(ledger, summary, reason, steps, 0, budget);
                }
                summary.put("reproduction_receipt_run", runId);
                summary.put("reproduction_status", "proved_on_base");
                progress.line("red   every reproduction command failed on the unchanged tree");
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
        } catch (HomeCorpus.UnavailableException unavailable) {
            summary.put("corpus_status", "error");
            summary.put("corpus_error", unavailable.getMessage());
            return stop(ledger, summary, "ledger_unavailable", steps, engine.attempt(), budget);
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
        if (budget.unpricedCharge() > 0) {
            summary.put("cost_cap_unpriced_charge_usd", budget.unpricedCharge());
        }
        summary.put("cost_ceiling_binding", budget.chainUnpriced() == 0);
        describeChain(summary, budget, runId, task, attempt);
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
            ledger.recordCorpusVisibility(summary);
            ledger.writeReport("task-run", summary);
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
            // Named every time, because the number looks like a cap and is not one: a vendor
            // that reports no price spends against it nothing at all.
            progress.line("      max_cost_usd " + Progress.money(task.budget().maxCostUsd())
                    + " is a threshold on the spend vendors report, not a strict cap");
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
        summary.put("safe_next_step", "warden approve " + runId + " --decision accept|reject");
        summary.put("next_step", NextStep.of("ready_for_human", summary, root));
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
        // Said at the gate, because it is the one label a person accepting the work most
        // needs and the one a same-vendor roster is most likely to have quietly weakened.
        if (summary.get("review_assurance") instanceof String assurance) {
            verdict += ". Review assurance: " + assurance
                    + ("independent".equals(assurance) ? ""
                        : "same_vendor_peer".equals(assurance)
                            ? " (a declared same-vendor pair read it; that is not an independent review)"
                            : "unproven".equals(assurance)
                                ? " (who wrote the candidate is not recorded, so no reading can be called independent)"
                                : " (a co-author of the candidate read it)");
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
                verdict, file, git.sourceFingerprint(diffBaseCommit),
                task.budget().gateTtlHours() == null ? null
                        : java.time.Duration.ofHours(task.budget().gateTtlHours()));
        if (decision.expiresAt() != null) summary.put("gate_expires_at", decision.expiresAt().toString());
        addDecision(summary, root, decision);
        publishGate(summary, root, decision, task.goal());
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(runId)).toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);
        footer(root, runId, "ready_for_human", "human_gate", budget, summary);
        return new Outcome(true, "ready_for_human", "human_gate", file, summary);
    }

    private static void describeChain(Map<String, Object> summary, Budget budget, String runId,
                                      TaskSpec.ResolvedTask task) {
        describeChain(summary, budget, runId, task, 0);
    }

    /**
     * The spend of the whole continued chain, this run included, beside this run's own.
     *
     * The next `--continue` reads it back as its starting point, so it is written at every
     * stop and at the gate, not only once. The maxima are the ones in force now: a person may
     * have raised them since the chain began, and the run records that separately as
     * `contract_change_budget_only`.
     */
    @SuppressWarnings("unchecked")
    private static void describeChain(Map<String, Object> summary, Budget budget, String runId,
                                      TaskSpec.ResolvedTask task, int attempt) {
        Chain earlier = budget.inherited();
        Map<String, Object> chain = summary.get("chain") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
        List<String> runs = new ArrayList<>(earlier.runs());
        runs.add(runId);
        chain.put("runs", runs);
        chain.put("role_runs", budget.chainRuns());
        chain.put("cost_usd", budget.chainSpent());
        chain.put("unpriced_calls", budget.chainUnpriced());
        if (budget.chainUnpricedCharge() > 0) {
            chain.put("cost_cap_unpriced_charge_usd", budget.chainUnpricedCharge());
        }
        chain.put("fix_attempts", earlier.fixAttempts() + attempt);
        chain.put("elapsed_seconds", budget.elapsedSeconds());
        chain.put("elapsed_known", earlier.elapsedKnown());
        if (task != null) {
            chain.put("max_role_runs", task.budget().maxRoleRuns());
            chain.put("max_cost_usd", task.budget().maxCostUsd());
            chain.put("max_fix_attempts", task.maxFixAttempts());
            chain.put("max_elapsed_minutes", task.budget().maxElapsedMinutes());
        }
        chain.put("calls_remaining", budget.remaining() == Integer.MAX_VALUE
                ? null : (long) budget.remaining());
        if (chain.get("max_fix_attempts") instanceof Number max) {
            chain.put("fix_attempts_remaining",
                    Math.max(0, max.longValue() - (earlier.fixAttempts() + attempt)));
        }
        // The ladder's position is a chain fact: a continuation must not climb the same rung
        // twice, nor forget the readings that already objected.
        long seen = earlier.blockingReviewsSeen();
        long rung = earlier.escalationRung();
        if (summary.get("escalation") instanceof Map<?, ?> ladder) {
            if (ladder.get("blocking_reviews_seen") instanceof Number n) seen = n.longValue();
            if (ladder.get("rung") instanceof Number n) rung = n.longValue();
        }
        chain.put("blocking_reviews_seen", seen);
        chain.put("escalation_rung", rung);
        summary.put("chain", chain);
    }

    /**
     * The first judging stage no reader could fill against the writer the chain would
     * dispatch, or null when every one can be. Asked once, before the first paid call.
     *
     * The writer is the profile the roster would actually choose, so a `first` strategy is
     * answered exactly and a rotating one is answered for the profile whose turn it is. A
     * chain with no writing stage has nothing to differ from here; its provenance question is
     * answered by the writer set instead.
     */
    private static Map<String, Object> readerGap(Path root, Workflow workflow, UserConfig user,
                                                 TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                 RoleRunner roles) {
        Workflow.Stage writerStage = null;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() == Workflow.Kind.ROLE && "implementer".equals(stage.role())
                    && skipReason(stage, user, task, reviewByRisk) == null) {
                writerStage = stage;
                break;
            }
        }
        if (writerStage == null) return null;
        dev.warden.config.Profile writer = roles.peek(root, user, writerStage.name(), "implementer",
                RoleResolver.Writers.NONE, workflow.rotationPositionOf(writerStage));
        // A writer nobody can fill is reported by the dispatch itself, with the roster's
        // own reasons; this check is about the readers.
        if (writer == null) return null;
        RoleResolver.Writers wouldWrite = new RoleResolver.Writers(java.util.Set.of(writer.vendor()),
                java.util.Set.of(writer.name()), true,
                "same_vendor_peer".equals(task.reviewAssurance()));
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE || stage.onFindings() == null) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            Map<String, String> refused = roles.explainFill(user, stage.name(), stage.role(),
                    wouldWrite, workflow.rotationPositionOf(stage));
            if (refused == null) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("role", stage.role());
            gap.put("needed_for", stage.name());
            gap.put("blocked_at", "preflight");
            gap.put("rejected_profiles", refused);
            gap.put("must_differ_from_vendor", writer.vendor());
            gap.put("writer_profile", writer.name());
            gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
            gap.put("message", "no profile can fill role '" + stage.role() + "' for stage '"
                    + stage.name() + "' once " + writer.name() + " (" + writer.vendor()
                    + ") has written the candidate (" + refused + "). Add a profile from another vendor, "
                    + "or declare a same_vendor_peer pair in policy.yaml and set "
                    + "review_assurance: same_vendor_peer on the task if that weaker check is "
                    + "acceptable; nothing was dispatched.");
            return gap;
        }
        return null;
    }

    /**
     * The paying profile with no declared per-call bound, when the task asked for a strict
     * money cap, or null when every candidate declares one.
     *
     * Routing does not only consult {@link Policy.RoleSpec#profiles()}: a {@code --use} pin,
     * a {@code --host} twin, an escalation rung and an authorised substitution can all select
     * a verified profile from the operator's roster. A cap that ignored those spent under a
     * number it had never reserved.
     */
    private static Map<String, Object> strictCapGap(Workflow workflow, UserConfig user,
                                                    TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                    RunOverride overlay,
                                                    Map<String, String> substitutions) {
        if (user.policy() == null) return null;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            for (dev.warden.config.Profile profile : selectableProfiles(stage, user, overlay,
                    substitutions)) {
                if (profile.maxCostUsd() != null) continue;
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("stage", stage.name());
                gap.put("role", stage.role());
                gap.put("profile", profile.name());
                gap.put("message", "budgets.cost_cap: strict needs a proven upper bound for every "
                        + "call it may admit, and profile '" + profile.name() + "' (stage '"
                        + stage.name()
                        + "') declares no limits.max_cost_usd. Declare one from that vendor's "
                        + "measured worst case, or set cost_cap: threshold to keep max_cost_usd as "
                        + "a threshold on reported spend; nothing was dispatched.");
                return gap;
            }
        }
        return null;
    }

    /**
     * The worst-case price of each paying stage under a strict cap: the largest declared
     * bound among the profiles routing can actually select. Preparation stages are already
     * spent when the loop starts and do not need reserving. The dispatch gate then overwrites
     * the stage with the selected profile's own bound.
     */
    private static Map<String, Double> strictBounds(Workflow workflow, UserConfig user,
                                                    TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                    RunOverride overlay,
                                                    Map<String, String> substitutions) {
        Map<String, Double> bounds = new LinkedHashMap<>();
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            double worst = 0;
            for (dev.warden.config.Profile profile : selectableProfiles(stage, user, overlay,
                    substitutions)) {
                if (profile.maxCostUsd() != null) {
                    worst = Math.max(worst, profile.maxCostUsd());
                }
            }
            bounds.put(stage.name(), worst);
        }
        return bounds;
    }

    /**
     * Every profile this stage might dispatch: the policy list, a pin, a host twin of those,
     * an escalation rung of the same role, and a substitution a person already authorised.
     */
    private static List<dev.warden.config.Profile> selectableProfiles(Workflow.Stage stage,
            UserConfig user, RunOverride overlay, Map<String, String> substitutions) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Policy.RoleSpec spec = user.policy() == null ? null : user.policy().roles().get(stage.role());
        if (spec != null) names.addAll(spec.profiles());
        if (overlay != null) {
            String pin = overlay.pinnedProfile(stage.name());
            if (pin != null) names.add(pin);
        }
        if (substitutions != null) {
            String named = substitutions.get(stage.name());
            if (named == null) named = substitutions.get(stage.role());
            if (named != null) names.add(named);
        }
        Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
        if (ladder != null) {
            for (Policy.Escalation.Rung rung : ladder.rungs()) {
                if ("implementer".equals(stage.role())) names.add(rung.implementer());
                else if ("reviewer".equals(stage.role())) names.add(rung.reviewer());
            }
        }
        List<dev.warden.config.Profile> profiles = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String name : names) {
            dev.warden.config.Profile profile = user.profiles().get(name);
            if (profile == null) continue;
            if (overlay != null) profile = overlay.adapt(stage.name(), profile, user.profiles());
            if (profile == null || !seen.add(profile.name())) continue;
            profiles.add(profile);
        }
        return profiles;
    }

    /**
     * The budget plan this loop records, from the same skip predicate it routes by.
     *
     * The preparation reservation calls this before the planner is paid so {@code run.json}
     * and the summary written a moment later cannot disagree about which stages count or
     * whether the cap can finish them.
     */
    static CallPlan planFor(Workflow workflow, UserConfig user, TaskSpec.ResolvedTask task,
                            List<String> preparationStages) {
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        return new CallPlan(workflow,
                stage -> skipReason(stage, user, task, reviewByRisk) != null,
                preparationStages);
    }

    /**
     * Why a stage will not run at all, or null when it will.
     *
     * Static, and asked through {@link #planFor} on purpose. The loop asks it to decide what
     * to execute; the preparation reservation asks it so the plan it records cannot name a
     * reviewer that risk had already excluded, or fail to reserve one that risk had brought
     * back.
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

    /**
     * Who would fill each stage, resolved from the roster before anything is paid.
     *
     * The header used to print the chain as stage names — `implement -> review -> look` —
     * which answers what will happen and not who will do it. Everything an operator actually
     * changes lives in the second question: which vendor, which model, at what effort, on
     * which runner, and whether a `--use` they typed landed on the stage they meant. Finding
     * that out required reading the summary of a run that had already been paid for.
     *
     * A preview, and honestly labelled as one. Rotation is read without advancing, the writer
     * set is taken as empty because nobody has written yet, and a mid-run failover can still
     * put a different vendor in a chair — the row says who the roster names now, which is the
     * question being asked now.
     */
    private List<Map<String, Object>> cast(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles, RunOverride overlay) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            dev.warden.config.Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
            if (chosen == null) {
                row.put("profile", null);
                Map<String, String> why = roles.explainFill(user, stage.name(), stage.role(),
                        RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
                row.put("unresolved", why == null ? Map.of() : why);
            } else {
                row.put("profile", chosen.name());
                row.put("vendor", chosen.vendor());
                row.put("model", chosen.model());
                row.put("effort", chosen.effort());
                row.put("runner", chosen.runner());
                // Named by the overlay is not the same as changed by it. A refused overlay
                // is about to stop the run, and a row that still said "this run only" would
                // be describing a dispatch that is not going to happen.
                if (overlay.stages().contains(stage.name())) {
                    row.put("from_overlay",
                            overlay.problem(stage.name(), chosen, user.profiles()) == null);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /** The cast as a person reads it: one stage per line, aligned, with the overlay marked. */
    private void printCast(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        int width = rows.stream().mapToInt(row -> String.valueOf(row.get("stage")).length())
                .max().orElse(0);
        String label = "cast  ";
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder(label);
            label = "      ";
            line.append(String.format("%-" + width + "s  ", row.get("stage")));
            if (row.get("profile") == null) {
                line.append("nobody can fill it");
                Object why = row.get("unresolved");
                if (why instanceof Map<?, ?> reasons && !reasons.isEmpty()) {
                    line.append(" — ").append(reasons);
                }
                progress.line(line.toString());
                continue;
            }
            line.append(row.get("profile")).append("  ").append(row.get("vendor"));
            if (row.get("model") != null) line.append('/').append(row.get("model"));
            if (row.get("effort") != null) line.append(' ').append(row.get("effort"));
            if (!"direct".equals(row.get("runner"))) line.append(" · ").append(row.get("runner"));
            if (Boolean.TRUE.equals(row.get("from_overlay"))) line.append("   (this run only)");
            if (Boolean.FALSE.equals(row.get("from_overlay"))) line.append("   (overlay refused)");
            progress.line(line.toString());
        }
    }

    /**
     * The overlay the outer command left in this run's directory, or none.
     *
     * A malformed handover refuses dispatch: silently falling back could spend a different
     * subscription from the one the operator chose.
     */
    static RunOverride handedOver(Path root, String runId) {
        Path file = root.resolve(".warden/runs").resolve(runId)
                .resolve(dev.warden.ledger.EvidenceLedger.RUN_OVERRIDE);
        if (!Files.isRegularFile(file)) return RunOverride.NONE;
        try {
            return RunOverride.fromMap(Json.parseObject(Files.readString(file)));
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("cannot read run override " + file, unreadable);
        }
    }

    public static RunOverride priorOverride(Path root, String runId) throws java.io.IOException {
        Map<String, Object> summary = Json.parseObject(Files.readString(root.resolve(".warden/runs")
                .resolve(runId).resolve("task-run.json")));
        Object raw = summary.get("run_override");
        if (raw == null) return RunOverride.NONE;
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("invalid prior run override");
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return RunOverride.fromMap(value);
    }

    /** Leave an overlay for the run {@code runId} will be, before its process exists. */
    public static void handOver(Path root, String runId, RunOverride overlay)
            throws java.io.IOException {
        if (overlay == null || overlay.isEmpty()) return;
        Path directory = root.resolve(".warden/runs").resolve(runId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(dev.warden.ledger.EvidenceLedger.RUN_OVERRIDE),
                Json.writePretty(overlay.toMap()) + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The operator's overlay, checked against the roster before anyone is paid.
     *
     * Three faults are knowable from configuration alone and all three used to surface as
     * something other than themselves. A stage name nobody in this workflow answers to
     * simply did nothing, silently — the commonest way to type one of these flags wrong.
     * An effort a runner cannot deliver was written into the evidence and never sent. A
     * {@code host: orca} on a profile with tool grants dropped them and kept the stamp. So
     * this runs first among the preflights: it is the cheapest of them, and the one whose
     * mistakes are a person's typing rather than a roster that has thinned.
     */
    private Map<String, Object> overlayGap(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles, RunOverride overlay) {
        if (overlay == null || overlay.isEmpty()) return null;
        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        for (Workflow.Stage stage : workflow.stages()) named.add(stage.name());
        for (String stage : overlay.stages()) {
            if (named.contains(stage)) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage);
            gap.put("code", "run_override_unknown_stage");
            gap.put("stop_reason", "run_override_invalid");
            gap.put("known_stages", List.copyOf(named));
            gap.put("message", "no stage called '" + stage + "' in this workflow, so the overlay "
                    + "naming it would do nothing. The stages that run are " + named
                    + ". Overlays are keyed by stage, not by role: two reviewer stages share a "
                    + "role and only one of them is the one you meant.");
            return gap;
        }
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            // `peek` has already applied whatever the overlay could honour, so what comes
            // back is either the adapted profile — asked again, it reports no problem — or
            // the untouched one, which is exactly the case with something to say.
            dev.warden.config.Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
            if (chosen == null) continue;
            String problem = overlay.problem(stage.name(), chosen, user.profiles());
            if (problem == null) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("profile", chosen.name());
            gap.put("code", "run_override_undeliverable");
            gap.put("stop_reason", "run_override_invalid");
            gap.put("message", problem);
            return gap;
        }
        return null;
    }

    /**
     * The first judging stage nobody can fill because its candidates may write, or null.
     *
     * The resolver refuses such a profile outright. Checked here, before anyone is paid and
     * ahead of the reader and tooling checks, because both would describe the same roster
     * wrongly: as a missing independent vendor, or as a visual profile to verify.
     */
    private static Map<String, Object> judgeGap(Workflow workflow, UserConfig user,
                                                TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                RoleRunner roles) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE || !RoleResolver.judges(stage.role())) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            String message = dev.warden.run.Preparation.writableJudge(roles, user, stage.name(),
                    stage.role());
            if (message == null) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("code", RoleResolver.JUDGE_NOT_READ_ONLY);
            gap.put("blocked_at", "preflight");
            gap.put("message", message);
            return gap;
        }
        return null;
    }

    /**
     * The tools a stage's profile would need, checked before anyone is paid.
     *
     * Two faults are visible from the roster alone. A stage that declares `evidence: agent`
     * needs a visual profile that declared it takes its own screenshots; a profile that
     * names an MCP configuration needs the file to exist. Either one found at dispatch has
     * already spent the implementer and every reader before it — measured on the harness's
     * missing browser, where the same lesson cost an implementer and a passed review.
     */
    private Map<String, Object> toolingGap(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            dev.warden.config.Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
            // Nobody to fill a required visual role used to fall through to dispatch, so the
            // writer and both readers ran and then the look stage died unverified. Bakery
            // would have paid that bill. Surface it here, with the resolver's reasons.
            if (chosen == null) {
                if (!"visual_qa".equals(stage.role())) continue;
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("stage", stage.name());
                gap.put("role", stage.role());
                gap.put("code", "visual_qa_unavailable");
                gap.put("stop_reason", "visual_qa_unavailable");
                Map<String, String> rejected = roles.explainFill(user, stage.name(), stage.role(),
                        RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
                gap.put("rejected_profiles", rejected == null ? Map.of() : rejected);
                gap.put("message", "visual QA is required and no eligible profile can fill stage '"
                        + stage.name() + "'"
                        + (rejected == null || rejected.isEmpty() ? "." : ": " + rejected)
                        + " For an MCP camera, run the profile's probe against your server, then "
                        + "`warden profiles --verify <profile> --confirm`. Do not start the loop "
                        + "until that stamp exists.");
                return gap;
            }
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("profile", chosen.name());
            if (chosen.mcpConfig() != null && !Files.isRegularFile(user.resolve(chosen.mcpConfig()))) {
                gap.put("code", "mcp_config_missing");
                gap.put("stop_reason", "mcp_config_missing");
                gap.put("message", "profile '" + chosen.name() + "' names mcp.config "
                        + chosen.mcpConfig() + ", which does not exist under the config home. "
                        + "Write the vendor's MCP configuration there, or remove mcp.config and "
                        + "the {{mcp_config}} argument from the profile.");
                return gap;
            }
            if (stage.acquiresEvidence() && (chosen.vision() == null || !chosen.vision().acquires())) {
                gap.put("code", "visual_qa_no_acquiring_profile");
                gap.put("stop_reason", "visual_qa_unavailable");
                gap.put("message", "stage '" + stage.name() + "' declares evidence: agent, so the "
                        + "visual role has to take its own screenshots, and profile '" + chosen.name()
                        + "' does not declare capabilities.vision.acquires: true. Declare it on a "
                        + "profile whose tools can drive the application, or give the stage a "
                        + "harness with sees:.");
                return gap;
            }
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
            "role_visual_no_evidence",
            "role_mcp_config_missing",
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
        /**
         * Every vendor that may have left bytes in the candidate, and the profiles behind
         * them. A reader is resolved against the whole set, not against the last implementer:
         * a writer failed over mid-edit, a fix round by another vendor and every writer of a
         * run this one continues all wrote into the tree a reviewer is asked to judge. Nothing
         * leaves the set without proof that its edits are gone, and there is no such proof
         * short of the tree matching the diff base again.
         */
        private final java.util.Set<String> writerVendors = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> writerProfiles = new java.util.LinkedHashSet<>();
        /**
         * How far the writer set can be trusted. `recorded` when every byte in the candidate
         * was written under this chain's eyes; `derived_from_steps` when a continued run
         * predates the field and its writers were read off its step rows;
         * `unknown_preexisting_candidate` when the tree already differed from the diff base
         * before the first dispatch and nothing recorded who did that;
         * `unknown_tree_moved` when a continued tree is not the tree the prior run left. An
         * unknown set refuses no reader, and no verdict over it is called independent.
         */
        private String writerProvenance = "recorded";
        /**
         * Readings of this task's chain that objected with blocking findings, and the rung
         * of the escalation ladder the chain stands on. Both are inherited by a continuation
         * and climbed forward only: a rung is never revisited, and a reading that objected on
         * an earlier run still counts towards the next rung.
         */
        private long blockingReviewsSeen;
        private int rung;
        /** Why the inherited rung could not be re-pinned, checked before the first stage runs. */
        private String escalationProblem;
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
        /** What the most recent repair reported doing, and whether the tree agrees. */
        private Map<String, Object> lastRepair;
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
               Map<String, Map<String, Object>> reusable, CallPlan callPlan) throws Exception {
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
            restoreFindingHistory();
            restoreWriters();
            publishWriters();
            this.blockingReviewsSeen = budget.inherited().blockingReviewsSeen();
            this.rung = (int) budget.inherited().escalationRung();
            restoreEscalation();
            roles.occupying(this::named);
        }

        /**
         * Re-pin the rung a continued chain already climbed to. The pins live in the runner,
         * which is new every run, so the summary carries which stage was pinned and the chain
         * carries the rung; both have to agree with the policy in force now, or the run stops
         * before it spends anything on a rung nobody can fill.
         */
        private void restoreEscalation() {
            if (rung <= 0 || !carried.continuesRun()) return;
            Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
            if (ladder == null || rung > ladder.rungs().size()) {
                escalationProblem = "the chain stands on escalation rung " + rung
                        + " but the policy now declares "
                        + (ladder == null ? "no ladder" : ladder.rungs().size() + " rung(s)");
                return;
            }
            String atStage = null;
            try {
                Path file = loaded.root().resolve(".warden/runs").resolve(carried.fromRunId())
                        .resolve("task-run.json");
                Map<String, Object> prior = Json.parseObject(Files.readString(file));
                if (prior.get("escalation") instanceof Map<?, ?> state
                        && state.get("at_stage") instanceof String stage) {
                    atStage = stage;
                }
            } catch (Exception unreadable) {
                atStage = null;
            }
            Policy.Escalation.Rung current = ladder.rungs().get(rung - 1);
            pinRung(ladder, current, atStage, "inherited_from_chain");
        }

        /** Pin one rung's pair and record the ladder's state on the summary. */
        private void pinRung(Policy.Escalation ladder, Policy.Escalation.Rung current,
                             String atStage, String reason) {
            roles.assignRole("implementer", current.implementer());
            if (atStage != null) roles.assignStage(atStage, current.reviewer());
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("rungs_declared", (long) ladder.rungs().size());
            state.put("after_blocking_reviews", ladder.afterBlockingReviews());
            state.put("blocking_reviews_seen", blockingReviewsSeen);
            state.put("rung", (long) rung);
            state.put("quality_attempts", (long) rung);
            state.put("implementer", current.implementer());
            state.put("reviewer", current.reviewer());
            state.put("at_stage", atStage);
            state.put("fix_round", (long) attempt);
            state.put("reason", reason);
            summary.put("escalation", state);
        }

        /**
         * Climb, or refuse to. Called at the moment a reading has objected and a repair is
         * about to be paid for; nothing here dispatches.
         *
         * Below the threshold the same writer repairs, as it always did. At or above it the
         * next rung's writer takes the repair and its reader re-reads the objecting stage,
         * both pinned for the rest of the chain. A rung whose pair cannot be filled now stops
         * before the repair rather than after it. A chain that has climbed every rung and is
         * still objected to has exhausted the quality it was allowed to buy.
         */
        private void maybeEscalate(Workflow.Stage stage, int index) {
            Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
            if (ladder == null) return;
            if (rung >= ladder.rungs().size() && rung > 0) throw qualityExhausted(stage, ladder);
            if (blockingReviewsSeen < ladder.afterBlockingReviews()) return;
            rung++;
            Policy.Escalation.Rung next = ladder.rungs().get(rung - 1);
            pinRung(ladder, next, stage.name(), "blocking_reviews_reached_threshold");
            boolean writerReady = roles.canFill(user, stage.name() + "/fix", stage.fixWith(),
                    RoleResolver.Writers.NONE, RoleRunner.UNSTAGED);
            boolean readerReady = roles.canFill(user, stage.name(), stage.role(), writers(),
                    workflow.rotationPositionOf(stage));
            if (!writerReady || !readerReady) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("rung", (long) rung);
                gap.put("implementer", next.implementer());
                gap.put("reviewer", next.reviewer());
                gap.put("implementer_fillable", writerReady);
                gap.put("reviewer_fillable", readerReady);
                gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
                summary.put("unavailable_role", gap);
                summary.put("resolution", "The escalation ladder's rung " + rung + " names "
                        + next.label() + ", and " + (writerReady ? "its reviewer" : "its implementer")
                        + " cannot be dispatched now (missing, unverified, not on the path, or "
                        + "spent). Verify or replace that profile, then continue this run: the "
                        + "verdicts already reached on this tree are kept.");
                progress.line("      escalation rung " + rung + " (" + next.label()
                        + ") cannot be filled; stopping before the repair");
                throw new StopException("escalation_unavailable");
            }
            progress.line("      -> escalation rung " + rung + " of " + ladder.rungs().size()
                    + " after " + blockingReviewsSeen + " blocking reading(s): "
                    + next.implementer() + " repairs, " + next.reviewer() + " re-reads "
                    + stage.name() + " as a co-author");
            workspace.note("escalation " + rung + "/" + ladder.rungs().size() + " · "
                    + next.implementer() + " writes, " + next.reviewer() + " reads " + stage.name());
        }

        private StopException qualityExhausted(Workflow.Stage stage, Policy.Escalation ladder) {
            summary.put("resolution", "Every rung of the escalation ladder (" + ladder.rungs().size()
                    + ") has written and been read, and '" + stage.name() + "' still objects. "
                    + "No further writer is declared, so the open findings go to a person: read "
                    + "them, change the task or the finding's premise, and start a new run.");
            noteNonactionableStop(stage);
            progress.line("      the last rung of the ladder still objects; nothing further is "
                    + "allowed to write");
            return new StopException("quality_exhausted");
        }

        private boolean ladderExhausted() {
            Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
            return ladder != null && rung > 0 && rung >= ladder.rungs().size();
        }

        /**
         * The writer set as the resolver needs it. The task decides whether a declared
         * same-vendor peer may stand in for an independent reader; the policy alone cannot.
         */
        private RoleResolver.Writers writers() {
            return new RoleResolver.Writers(writerVendors, writerProfiles,
                    "recorded".equals(writerProvenance) || "derived_from_steps".equals(writerProvenance),
                    "same_vendor_peer".equals(task.reviewAssurance()));
        }

        /**
         * Add whoever this dispatch let write. A writing role's every vendor attempt counts,
         * including one that was failed over mid-edit; a read-only role counts only when the
         * fingerprint says it wrote anyway, at which point the run stops, but the set stays
         * honest for the continuation that inherits the tree.
         */
        private void noteWriters(RoleRunner.Outcome outcome) {
            if (outcome == null || outcome.details() == null) return;
            Map<String, Object> details = outcome.details();
            boolean wrote = Boolean.FALSE.equals(details.get("read_only"));
            if (details.get("read_only_check") instanceof Map<?, ?> check
                    && Boolean.FALSE.equals(check.get("matched"))) {
                wrote = true;
            }
            if (!wrote) return;
            if (details.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
                for (Object item : attempts) {
                    if (!(item instanceof Map<?, ?> attempt)) continue;
                    if (attempt.get("vendor") instanceof String vendor) writerVendors.add(vendor);
                    if (attempt.get("profile") instanceof String profile) writerProfiles.add(profile);
                }
            } else {
                if (outcome.vendor() != null) writerVendors.add(outcome.vendor());
                if (outcome.profile() != null) writerProfiles.add(outcome.profile());
            }
            // Deliberately no promotion here. A writer dispatched now accounts for the bytes
            // it just wrote and for nothing that was in the tree before it. Treating a known
            // new author as proof of the whole candidate is how an unexplained file becomes
            // an `independent` verdict over work nobody can name.
            publishWriters();
        }

        private void publishWriters() {
            summary.put("writer_vendors", List.copyOf(new java.util.TreeSet<>(writerVendors)));
            summary.put("writer_profiles", List.copyOf(new java.util.TreeSet<>(writerProfiles)));
            summary.put("writer_provenance", writerProvenance);
        }

        /**
         * What the tree already carries before this run writes anything.
         *
         * A continued run inherits the writers of the run it continues, and of every run that
         * one inherited: a rejection keeps the candidate in the tree, a retry keeps it, a
         * switch keeps it. A summary written before the field existed is read off its step
         * rows, which is evidence rather than a guess. A tree that differs from the diff base
         * with no run to account for it, or a continued tree that is not the one the prior
         * run left, is unknown, and stays unknown rather than being called clean.
         */
        /**
         * A new run id on a tree an earlier run of this task left, identified by fingerprint.
         *
         * Bakery-7 paid two independent vendors and was then told nobody could be called
         * independent, because the run folder had a different name. That complaint is real,
         * and the answer is not to believe whichever run was written most recently: it is to
         * find the run whose recorded closing tree IS this tree. Two facts have to agree —
         * the immutable diff base, so "changed since" means the same thing in both runs, and
         * the closing candidate fingerprint the human decision recorded, so the prior writer
         * set accounts for every byte here and not merely for some earlier state.
         *
         * Anything less and the inheritance is a guess. A prior run whose own provenance was
         * unknown proves nothing either, however confidently it listed vendors, so it is not
         * a source. When no run matches, the tree stays {@code unknown_preexisting_candidate}
         * and every reading over it is labelled {@code unproven} — which is the honest
         * answer to "who wrote this", not a failure of the loop.
         */
        private boolean inheritWritersFromLatestSameTask() {
            Path runs = loaded.root().resolve(".warden/runs");
            if (!Files.isDirectory(runs)) return false;
            Object here = summary.get("candidate_fingerprint");
            if (!(here instanceof String fingerprint) || fingerprint.isBlank()) return false;
            ApprovalStore decisions = new ApprovalStore(loaded.root());
            Map<String, Object> match = null;
            long matchTime = Long.MIN_VALUE;
            try (var stream = Files.list(runs)) {
                for (Path dir : stream.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    String priorRunId = dir.getFileName().toString();
                    if (priorRunId.equals(runId)) continue;
                    Path file = dir.resolve("task-run.json");
                    if (!Files.isRegularFile(file)) continue;
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (modified < matchTime) continue;
                    Map<String, Object> prior;
                    try {
                        prior = Json.parseObject(Files.readString(file));
                    } catch (Exception unreadable) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(prior.get("dry_run"))) continue;
                    if (!task.id().equals(prior.get("task_id"))) continue;
                    if (!diffBaseCommit.equals(prior.get("diff_base_commit"))) continue;
                    if (!(prior.get("writer_vendors") instanceof List<?> vendors) || vendors.isEmpty()) {
                        continue;
                    }
                    if (!(prior.get("writer_provenance") instanceof String provenance)
                            || provenance.startsWith("unknown")) {
                        continue;
                    }
                    String closed;
                    try {
                        closed = decisions.read(priorRunId).candidateFingerprint();
                    } catch (Exception noDecision) {
                        continue;
                    }
                    if (closed == null || !closed.equals(fingerprint)) continue;
                    match = prior;
                    matchTime = modified;
                }
            } catch (Exception listing) {
                return false;
            }
            if (match == null) return false;
            // Read into locals first: a half-applied inheritance would leave profile names in
            // the writer set with no vendor to account for them, which is a worse state than
            // the unknown one this is trying to improve on.
            java.util.Set<String> vendorsFound = new java.util.LinkedHashSet<>();
            java.util.Set<String> profilesFound = new java.util.LinkedHashSet<>();
            if (match.get("writer_vendors") instanceof List<?> vendorsListed) {
                for (Object vendor : vendorsListed) vendorsFound.add(String.valueOf(vendor));
            }
            if (match.get("writer_profiles") instanceof List<?> profilesListed) {
                for (Object profile : profilesListed) profilesFound.add(String.valueOf(profile));
            }
            if (vendorsFound.isEmpty()) return false;
            writerVendors.addAll(vendorsFound);
            writerProfiles.addAll(profilesFound);
            // The prior run's own word for how well it knew its writers, not a better one.
            writerProvenance = String.valueOf(match.get("writer_provenance"));
            if (match.get("run_id") != null) {
                summary.put("writers_inherited_from", String.valueOf(match.get("run_id")));
            }
            publishWriters();
            return true;
        }

        private void restoreWriters() {
            boolean candidatePresent;
            try {
                candidatePresent = !WardenTree.sourcePaths(
                        new GitRepository(loaded.root(), processes).changedPaths(diffBaseCommit)).isEmpty();
            } catch (Exception unreadable) {
                candidatePresent = true;
            }
            if (!carried.continuesRun()) {
                if (candidatePresent && !inheritWritersFromLatestSameTask()) {
                    writerProvenance = "unknown_preexisting_candidate";
                }
                return;
            }
            Path file = loaded.root().resolve(".warden/runs").resolve(carried.fromRunId())
                    .resolve("task-run.json");
            Map<String, Object> prior = null;
            try {
                if (Files.isRegularFile(file)) prior = Json.parseObject(Files.readString(file));
            } catch (Exception unreadable) {
                prior = null;
            }
            String priorFingerprint = null;
            try {
                priorFingerprint = new ApprovalStore(loaded.root()).read(carried.fromRunId())
                        .candidateFingerprint();
            } catch (Exception noDecision) {
                priorFingerprint = null;
            }
            if (prior == null) {
                writerProvenance = candidatePresent ? "unknown_preexisting_candidate" : "recorded";
                return;
            }
            if (prior.get("writer_vendors") instanceof List<?> vendorsListed) {
                for (Object vendor : vendorsListed) writerVendors.add(String.valueOf(vendor));
                if (prior.get("writer_profiles") instanceof List<?> profilesListed) {
                    for (Object profile : profilesListed) writerProfiles.add(String.valueOf(profile));
                }
                // An unknown prior stays unknown however many vendors it managed to name:
                // the names are of runs that wrote, not of whatever was in the tree before
                // them, and that gap is exactly what `unknown` is the word for.
                String recorded = prior.get("writer_provenance") instanceof String text ? text : "recorded";
                writerProvenance = recorded.startsWith("unknown") ? recorded : "recorded";
            } else {
                boolean anyWriter = false;
                if (prior.get("steps") instanceof List<?> rows) {
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> step)) continue;
                        if (!"implementer".equals(step.get("step")) || step.get("vendor") == null) continue;
                        anyWriter = true;
                        writerVendors.add(String.valueOf(step.get("vendor")));
                        if (step.get("profile") != null) writerProfiles.add(String.valueOf(step.get("profile")));
                        if (step.get("vendor_attempts") instanceof List<?> attempts) {
                            for (Object item : attempts) {
                                if (item instanceof Map<?, ?> attempt && attempt.get("vendor") != null) {
                                    writerVendors.add(String.valueOf(attempt.get("vendor")));
                                    if (attempt.get("profile") != null) {
                                        writerProfiles.add(String.valueOf(attempt.get("profile")));
                                    }
                                }
                            }
                        }
                    }
                }
                writerProvenance = anyWriter || !candidatePresent ? "derived_from_steps"
                        : "unknown_preexisting_candidate";
            }
            Object now = summary.get("candidate_fingerprint");
            if (candidatePresent && (priorFingerprint == null || !priorFingerprint.equals(now))) {
                writerProvenance = "unknown_tree_moved";
            }
        }

        /** Keep lifecycle memory on an authorised continuation, even when content needs re-review. */
        private void restoreFindingHistory() throws Exception {
            if (!carried.continuesRun()) return;
            Path file = loaded.root().resolve(".warden/runs").resolve(carried.fromRunId()).resolve("task-run.json");
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> prior = Json.parseObject(Files.readString(file));
            if (!task.id().equals(prior.get("task_id"))) return;
            if (!java.util.Objects.equals(summary.get("contract_sha256"), prior.get("contract_sha256"))
                    && !(summary.get("acceptance_sha256") instanceof String hash
                    && hash.equals(prior.get("acceptance_sha256")))) return;
            if (prior.get("finding_history") instanceof List<?> rows) for (Object item : rows) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("stage") instanceof String stage)) continue;
                Map<String, Object> rebuilt = Findings.roundOf(stage,
                        entry.get("attempt") instanceof Number n ? n.longValue() : 0,
                        entry.get("candidate_fingerprint") instanceof String fp ? fp : null,
                        Findings.recorded(entry.get("findings")), List.copyOf(findingHistory));
                rebuilt.put("source_run", entry.get("source_run") instanceof String source
                        ? source : carried.fromRunId());
                findingHistory.add(rebuilt);
            }
            if (!findingHistory.isEmpty()) summary.put("finding_history", List.copyOf(findingHistory));
        }

        int attempt() { return attempt; }

        /**
         * Fix rounds the whole chain has used, this run's included. `attempt` stays per run:
         * it names evidence directories and gates verdict reuse to the first pass.
         */
        long fixRoundsUsed() { return budget.inherited().fixAttempts() + attempt; }

        List<Map<String, Object>> skipped() { return List.copyOf(skipped); }

        void run() throws Exception {
            if (escalationProblem != null) {
                summary.put("resolution", escalationProblem + ". Restore the ladder in "
                        + "policy.yaml or start a new run without --continue.");
                throw new StopException("escalation_unavailable");
            }
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

            while (failed(outcome) && "fix".equals(stage.onFail()) && fixable(stage, outcome)
                    && fixRoundsUsed() < task.maxFixAttempts()) {
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
            while (repairableFindings(stage) > 0 && "fix".equals(stage.onFindings())
                    && fixRoundsUsed() < task.maxFixAttempts()) {
                // Who repairs is decided here, before the repair is costed and paid for. Off
                // the ladder this is a no-op; on it, the next rung's pair is pinned or the
                // run stops for a reason that names the rung.
                maybeEscalate(stage, index);
                fixRound(stage, index, reviewContext(loaded.root(), reviewed));
                outcome = execute(stage);
                if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
                reviewed = (RoleRunner.Outcome) outcome;
                blocking = recordFindings(stage, reviewed);
                // Laundering of a P1 is already refused inside recordFindings, including on
                // this stage's first reading. This stall detector is specifically about a
                // repair that changed nothing and brought the same blockers back.
                requireFindingsProgress(stage);
            }
            if (blocking > 0) {
                if (ladderExhausted()) {
                    throw qualityExhausted(stage, user.policy().escalation());
                }
                noteNonactionableStop(stage);
                throw new StopException(stage.findingsReason());
            }
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
            progress.line("      -> fix round " + fixRoundsUsed() + " of " + task.maxFixAttempts()
                    + ": " + stage.name() + " sends the work back to " + stage.fixWith());
            workspace.note(fixNote(stage, null, null));
            String beforeRepair = currentFingerprint();
            Path file = writeContext(ledger, attempt, stage.contextKind(),
                    repairPackage(stage, context));
            String role = stage.fixWith();
            // A fix round is the same vendor, the same silence and the same twenty minutes as
            // the stage that provoked it. It went without a beat in the first version of this,
            // so the one place a run is most likely to be waited on — a second implementer
            // call, after the operator has already spent half an hour — was the one place that
            // said nothing at all.
            RoleRunner.Outcome fix;
            Workflow.Stage writerStage = writerStageOf(role);
            budget.dispatching(writerStage == null ? stage.name() + "/fix" : writerStage.name());
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
            // Which stage sent the work back and which round this was. A fix row used to be
            // indistinguishable from a stage row except by the absence of a stage, and a
            // continuation needs to know that this row is the writer's latest product.
            steps.get(steps.size() - 1).put("fix_for", stage.name());
            steps.get(steps.size() - 1).put("fix_round", (long) attempt);
            // Same stamp as an ordinary dispatch: the tree this repair produced is the tree a
            // later resume must match before it may reuse this implementer's work.
            stampFingerprint();
            // The routing of the stage this writer owns, so a continuation can compare the
            // row against the writer stage's current terms the way it compares a stage row.
            Workflow.Stage owned = writerStageOf(role);
            if (owned != null) stampRouting(owned);
            vendors.putIfAbsent(role, fix.vendor());
            noteWriters(fix);
            if (!fix.ok()) {
                requireDeadlineNotTheCause(fix);
                throw new StopException(reasonFor(fix, "fix_attempt_failed"));
            }
            recordRepairReceipt(fix, beforeRepair);
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
         * Everything a repair needs, in the order it needs it, and nothing it has to go and
         * find.
         *
         * The failure text on its own was a fix round's whole briefing, which made every round
         * after the first strictly worse informed than the first: an implementer was handed an
         * objection with no memory of what it had already closed, no statement of the goal it
         * was still working towards, and no idea how much room was left. So it re-read
         * everything, sometimes undid a closed finding, and could not tell a request it should
         * refuse from one it should act on.
         *
         * The goal is quoted from the contract rather than summarised, because a goal restated
         * by a model is a goal that drifts. The budget is stated so a repair knows whether it
         * is the last one. What the round already closed is stated so it stays closed.
         */
        private String repairPackage(Workflow.Stage stage, String failure) {
            StringBuilder out = new StringBuilder();
            out.append("# The goal, unchanged\n\n").append(task.goal()).append("\n\n");
            if (!task.nonGoals().isEmpty()) {
                out.append("Explicitly not part of it:\n");
                for (String no : task.nonGoals()) out.append("- ").append(no).append('\n');
                out.append('\n');
            }

            Map<String, Object> last = lastFindingRound(stage.name());
            List<String> alreadyClosed = last == null ? List.of() : stringsOf(last.get("closed_ids"));
            if (!alreadyClosed.isEmpty()) {
                out.append("# Already closed by an earlier round — keep them closed\n\n");
                for (String id : alreadyClosed) out.append("- ").append(id).append('\n');
                out.append(Json.writePretty(last.get("finding_registry"))).append("\n");
                out.append("\nA repair that reopens one of these has not made progress; it has "
                        + "traded one finding for another.\n\n");
            }
            if (lastRepair != null) {
                out.append("# What the previous repair reported doing\n\n")
                        .append("- summary: ").append(lastRepair.get("summary")).append('\n');
                List<String> touched = stringsOf(lastRepair.get("files_changed"));
                if (!touched.isEmpty()) {
                    out.append("- files it said it changed: ")
                            .append(String.join(", ", touched)).append('\n');
                }
                out.append("- the candidate ")
                        .append(Boolean.TRUE.equals(lastRepair.get("moved_candidate"))
                                ? "did change as a result" : "did not change at all")
                        .append("\n\n");
            }

            out.append(failure);

            out.append("\n# What you may not do\n\n")
                    .append("- Files outside ").append(String.join(", ", task.scopePaths()))
                    .append(" are outside this task's blast radius and a change to one fails the run.\n")
                    .append("- The `.warden` contract is hashed. Editing the task, the acceptance "
                            + "commands or the scenarios to make a check agree aborts the run.\n")
                    .append("- Authority for this task: workspace_write=")
                    .append(task.authority().workspaceWrite())
                    .append(", network=").append(task.authority().network())
                    .append(", land=").append(task.authority().land()).append(".\n");

            int remaining = budget.remaining();
            out.append("\n# What is left\n\n")
                    .append("- This is fix round ").append(attempt).append(" of ")
                    .append(task.maxFixAttempts()).append(".\n");
            if (remaining != Integer.MAX_VALUE) {
                out.append("- Vendor calls remaining after this one: ")
                        .append(Math.max(0, remaining - 1)).append(".\n");
            }
            out.append("- Nothing is landed by this loop; a person decides at the end.\n");
            return out.toString();
        }

        /**
         * What a judging stage is told so it can check closure rather than start again.
         *
         * Two memories, kept distinct. A stage that has already read this candidate is asked
         * whether its own findings closed, and is allowed a narrowed reading while the tree
         * is the tree it read. A later stage's first reading is independent — its verdict is
         * its own — but it still receives the registry and the repair receipt, with the stage
         * that filed each finding preserved, so a closed requirement cannot re-enter as a
         * new one on the original evidence.
         *
         * A reviewer given no memory re-investigates from scratch every round, which costs a
         * full reading each time and produces findings that drift — the same defect described
         * differently, or a new objection to code nobody touched. It is also the reason a
         * second round could not be compared to the first.
         */
        private String reviewerPackage(Workflow.Stage stage) {
            Map<String, Object> own = lastFindingRound(stage.name());
            Map<String, Object> last = own != null ? own : lastInheritedFindingRound(stage.name());
            if (last == null) return null;
            boolean firstReadingOfThisStage = own == null;
            StringBuilder out = new StringBuilder();
            if (firstReadingOfThisStage) {
                out.append("# A previous stage has already read this candidate\n\n");
                out.append("This is your first reading of this task. Another reviewer already "
                        + "read this candidate");
                if (lastRepair != null) {
                    out.append(" and an implementer has since been given what they filed");
                }
                out.append(". Their verdict is not yours — read the code. The registry below is "
                        + "so you can reuse ids, check closures, and not raise a closed "
                        + "requirement as a new one without new evidence.\n\n");
            } else {
                out.append("# You have already read this candidate\n\n");
                out.append("This is not a fresh review. You looked at this task on an earlier round "
                        + "and filed the findings below");
                if (lastRepair != null) {
                    out.append("; an implementer has since been given them.\n\n");
                } else {
                    // A resumed run reaches this branch with its reading restored and no repair
                    // behind it: `lastRepair` belongs to this run, and a restore does not carry
                    // one. Claiming a repair briefed a paid reviewer on something untrue, on the
                    // one round where dropping a finding is refused precisely because the tree
                    // did not move.
                    out.append(". No repair has run since. This is a resumed run and the reading "
                            + "below was restored from the run before it; the candidate itself ")
                            .append(movedSince(last) ? "has changed since that reading."
                                    : "**has not changed at all** since that reading.")
                            .append("\n\n");
                }
            }

            out.append("## Cumulative finding registry (including closed findings)\n\n")
                    .append(Json.writePretty(Findings.registryOf(findingHistory))).append("\n\n")
                    .append("Reuse IDs. Link renamed findings with supersedes. Reopening or a new "
                            + "P1 after a closure requires new evidence_refs; bare verdicts stop the run.\n\n");
            out.append(firstReadingOfThisStage
                    ? "## What they filed\n\n" : "## What you filed last time\n\n");
            Object listed = firstReadingOfThisStage
                    ? Findings.registryOf(findingHistory) : last.get("findings");
            if (listed instanceof List<?> rows && !rows.isEmpty()) {
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> finding)) continue;
                    out.append("- `").append(finding.get("id")).append("` (")
                            .append(finding.get("severity")).append(", ")
                            .append(finding.get("category"));
                    if (finding.get("status") instanceof String status && !status.isBlank()) {
                        out.append(", ").append(status);
                    }
                    out.append(") ").append(finding.get("path")).append(" — ")
                            .append(finding.get("message"));
                    if (finding.get("recorded_at_stage") instanceof String filed
                            && !filed.isBlank()) {
                        out.append(" [stage ").append(filed).append(']');
                    }
                    out.append('\n');
                }
            } else {
                out.append("- (nothing)\n");
            }

            if (lastRepair != null) {
                out.append("\n## What the implementer says it did\n\n")
                        .append(lastRepair.get("summary")).append('\n');
                List<String> touched = stringsOf(lastRepair.get("files_changed"));
                if (!touched.isEmpty()) {
                    out.append("\nFiles it reports changing: ")
                            .append(String.join(", ", touched)).append('\n');
                }
                out.append("\nThe candidate itself ")
                        .append(Boolean.TRUE.equals(lastRepair.get("moved_candidate"))
                                ? "did change since " + (firstReadingOfThisStage
                                        ? "the last reading." : "your last reading.")
                                : "**did not change at all** since "
                                        + (firstReadingOfThisStage
                                                ? "the last reading." : "your last reading."))
                        .append('\n');
            }

            if (firstReadingOfThisStage) {
                out.append("""

                        ## What is being asked of you now

                        Your verdict is independent of theirs. Two extra obligations come with
                        the registry: a finding they closed stays closed unless you have new
                        evidence, and a new P1 after a closure needs new evidence too. A
                        regression is a new finding, not a footnote.

                        Reuse the finding ids above for the same defect, so the run can tell a
                        surviving objection from a fresh one. Give a new id only to something
                        genuinely new.

                        Do not lower a severity to let the run pass, and do not drop a P1 you
                        filed to let it pass either. If a P1 was wrong, say it was wrong and why;
                        a P1 that quietly becomes a P2, or vanishes, on a candidate that did not
                        change is refused by the controller, not believed.
                        """);
            } else {
                out.append("""

                        ## What is being asked of you now

                        Two questions, in this order. Is each finding above actually closed, judged
                        against the code rather than against the implementer's account of it? And did
                        the repair break something that was working — a regression is a new finding,
                        not a footnote.

                        Reuse the finding ids above for anything still open, so the run can tell a
                        surviving objection from a fresh one. Give a new id only to something genuinely
                        new.

                        Do not lower a severity to let the run pass, and do not drop a P1 you
                        filed to let it pass either. If a P1 was wrong, say it was wrong and why;
                        a P1 that quietly becomes a P2, or vanishes, on a candidate that did not
                        change is refused by the controller, not believed.

                        You do not have to repeat the whole investigation to answer those two
                        questions. You do have to repeat it if the scope, the acceptance commands or
                        the contract have changed since your last reading, or if your earlier verdict
                        rested on an observation that can go stale — a live page, a deployment, a
                        network call. Say which of those applies in your summary.
                        """);
            }
            return out.toString();
        }

        private static List<String> stringsOf(Object value) {
            List<String> items = new ArrayList<>();
            if (value instanceof List<?> rows) {
                for (Object row : rows) items.add(String.valueOf(row));
            }
            return items;
        }

        /**
         * Refuse a pass that was bought by relabelling or by dropping a finding, rather than
         * by fixing.
         *
         * A reviewer can end a run by calling its own P1 a P2, or by omitting it. On a
         * candidate that changed, that may be an honest reconsideration in the light of a
         * repair. On a candidate that did not change, nothing new was learned about the code,
         * and the only thing that moved is the report standing between the run and the human
         * gate.
         *
         * The controller does not overrule the reviewer's judgement of the defect — it refuses
         * to treat the relabelling or the retraction as a pass, even when another blocker is
         * still open in the same round, and even on a stage's first reading of a resumed run.
         * Restoring the history on `--continue` is what makes that comparison possible; a
         * first reading that then drops or relabels a P1 on an unchanged candidate is the
         * same pass-by-rewriting-the-report as one that follows a no-op repair. A throwaway
         * P1 kept alive for one reading is not a reason to let the original drop through. A
         * reviewer that believes the P1 was wrong should say so and why, which reads as a
         * `review_disagreement` a person can adjudicate.
         */
        private void requireNoSeverityLaundering(Workflow.Stage stage) {
            Map<String, Object> now = lastFindingRound(stage.name());
            if (now == null || !Boolean.FALSE.equals(now.get("candidate_moved"))) return;
            List<String> weakened = List.copyOf(stringsOf(now.get("severity_downgraded")));
            List<String> dropped = List.copyOf(stringsOf(now.get("blocking_retracted")));
            if (weakened.isEmpty() && dropped.isEmpty()) return;
            Map<String, Object> laundered = new LinkedHashMap<>();
            laundered.put("at_stage", stage.name());
            laundered.put("downgraded_ids", weakened);
            laundered.put("retracted_ids", dropped);
            laundered.put("candidate_fingerprint", now.get("candidate_fingerprint"));
            summary.put("severity_downgraded_without_change", laundered);
            String how = dropped.isEmpty() ? "came back at a lower severity"
                    : weakened.isEmpty() ? "vanished from the report"
                    : "came back at a lower severity or vanished from the report";
            summary.put("resolution", "'" + stage.name() + "' stopped blocking only because a "
                    + "finding it had called P1 " + how + ", on a candidate "
                    + "that did not change. Nothing new was learned about the code between the "
                    + "two readings. If the P1 was wrong, that is a disagreement for a person to "
                    + "settle on the evidence, not a pass.");
            progress.line("      a P1 became non-blocking on an unchanged candidate; not "
                    + "treating a dropped or relabelled finding as a fixed one");
            throw new StopException("severity_downgraded_without_change");
        }

        /**
         * P1s the implementer can be expected to close by editing the candidate. A
         * contract_gap, access grant, quota or disagreement still blocks acceptance, but
         * handing the diff back will not close it.
         */
        private long repairableFindings(Workflow.Stage stage) {
            Map<String, Object> now = lastFindingRound(stage.name());
            if (now == null || !(now.get("findings") instanceof List<?> rows)) return 0;
            long n = 0;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> finding
                        && "P1".equals(String.valueOf(finding.get("severity")))
                        && "product_defect".equals(String.valueOf(finding.get("category")))) {
                    n++;
                }
            }
            return n;
        }

        /** Name the leftover when the only remaining P1s are not the implementer's. */
        private void noteNonactionableStop(Workflow.Stage stage) {
            if (repairableFindings(stage) > 0) return;
            Map<String, Object> now = lastFindingRound(stage.name());
            if (now == null || !(now.get("findings") instanceof List<?> rows)) return;
            List<String> leftover = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> finding
                        && "P1".equals(String.valueOf(finding.get("severity")))
                        && !"product_defect".equals(String.valueOf(finding.get("category")))) {
                    leftover.add(String.valueOf(finding.get("id")));
                }
            }
            if (leftover.isEmpty()) return;
            summary.put("nonactionable_blocking_ids", leftover);
            summary.put("resolution", "The remaining P1(s) are categorised as something an "
                    + "implementer cannot close by editing the candidate. A person has to change "
                    + "the task, grant access, or settle the disagreement; another fix round "
                    + "would not.");
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
                    + (budget.chainRuns() + toFinish) + " and continue this run — the verdicts "
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
            String writer = writerVendors.isEmpty() ? null
                    : String.join(", ", new java.util.TreeSet<>(writerVendors));
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
            if (!roles.canFill(user, stage.name() + "/fix", stage.fixWith(),
                    RoleResolver.Writers.NONE, RoleRunner.UNSTAGED)) {
                throw unfillable(stage.fixWith(), stage.name(), "the repair itself", null);
            }
            for (Workflow.Stage judge : needed) {
                RoleResolver.Writers avoid = "implementer".equals(judge.role())
                        ? RoleResolver.Writers.NONE : writers();
                if (roles.canFill(user, judge.name(), judge.role(), avoid,
                        workflow.rotationPositionOf(judge))) {
                    continue;
                }
                throw unfillable(judge.role(), stage.name(), judge.name(),
                        "implementer".equals(judge.role()) ? null : writer);
            }
        }

        /** The first stage that dispatches {@code role} as a writer, or null in a chain without one. */
        private Workflow.Stage writerStageOf(String role) {
            for (Workflow.Stage stage : workflow.stages()) {
                if (stage.kind() == Workflow.Kind.ROLE && role.equals(stage.role())) return stage;
            }
            return null;
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
                    + (avoid == null ? "" : " that differs from every vendor which wrote this code ("
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
            workspace.stage(note.apply(profile, vendor));
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
                workspace.stage(position(stage) + " " + stage.name());
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
                    VisualQaRunner.Outcome outcome = runVisual(loaded, runId, attempt, dryRun, steps,
                            user.home());
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

        /**
         * What this dispatch is told beyond its own prompt, or null when it needs nothing.
         *
         * Two different memories, and never both: an implementer starting from a person's
         * rejection, and a judging stage being handed findings — its own previous round, or
         * an earlier stage's registry on its first reading. Anything else gets the plain
         * prompt, because a role with nothing to remember should not be handed a preamble
         * telling it so.
         */
        private Path contextFor(Workflow.Stage stage) throws Exception {
            Path rejection = rejectionContextFor(stage.role());
            if (rejection != null) return rejection;
            if (stage.onFindings() == null) return null;
            String pack = reviewerPackage(stage);
            if (pack == null) return null;
            // Shared roles need separate evidence for each stage at the same attempt.
            // Use the workflow position: operator-chosen names can have identical slugs.
            String kind = stage.contextKind();
            if (workflow.stagesFor(stage.role()).size() > 1) {
                kind += "-stage-" + (workflow.stages().indexOf(stage) + 1);
            }
            return writeContext(ledger, attempt, kind + "-recheck", pack);
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
                if ("implementer".equals(role) && standing.get("vendor") != null) {
                    writerVendors.add(String.valueOf(standing.get("vendor")));
                    if (standing.get("profile") != null) {
                        writerProfiles.add(String.valueOf(standing.get("profile")));
                    }
                    publishWriters();
                }
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
                        + standing.get("profile") + (standing.get("fix_for") != null
                            ? " already produced this exact tree in a fix round for "
                                + standing.get("fix_for")
                            : " already passed this exact tree"));
                return null;
            }
            // Independence is measured against everyone who wrote the code, not the last
            // implementer alone; a writer is not measured against itself.
            RoleResolver.Writers avoid = "implementer".equals(role) ? RoleResolver.Writers.NONE : writers();
            budget.dispatching(stage.name());
            RoleRunner.Outcome outcome;
            if ("visual_qa".equals(role)) {
                outcome = runVisualRole(loaded, user, roles, runId, attempt, avoid,
                        stage.acquiresEvidence() ? null : harness.get(stage.sees()), dryRun, steps,
                        budget, stage.acquiresEvidence());
                // The same claim as for every other role: how the stage routes, and here also
                // who took the pictures, are terms the verdict was reached under.
                stampRouting(stage);
            } else {
                outcome = roles.run(loaded, user, role, stageRunId(runId, workflow, stage, attempt),
                        avoid, contextFor(stage), List.of(), dryRun, workflow.rotationPositionOf(stage));
                record(steps, budget, role, attempt, outcome, stage.name());
                // The role runner knows the profile and the roster; only the loop knows how
                // the stage that dispatched it routes. Both halves belong to the same claim
                // about the terms this verdict was reached under.
                stampRouting(stage);
            }
            if (dryRun) {
                if (outcome != null && !outcome.ok()) previewStop(stage, outcome);
                return outcome;
            }
            if (outcome == null) return outcome;
            vendors.putIfAbsent(role, outcome.vendor());
            noteWriters(outcome);
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

        /**
         * A preview does not stop for a role nobody can fill, because it has no candidate to
         * protect, but it has to say so.
         *
         * Measured on the pilot template: both roles came back `role_unresolved`, one profile
         * unverified and one whose executable was not on the path, and the preview still
         * finished `ok`. The refusal was in the steps for anyone who read them, and otherwise
         * in a live run that dispatched nobody. The reason recorded is the one the real run
         * stops with, and the first one wins, as it would there.
         */
        private void previewStop(Workflow.Stage stage, RoleRunner.Outcome outcome) {
            if (summary.get("would_stop") != null) return;
            summary.put("would_stop", terminalReason(stage, outcome));
            StringBuilder why = new StringBuilder("stage " + stage.name() + ": " + outcome.code());
            if (outcome.rejected() != null && !outcome.rejected().isEmpty()) {
                why.append(", refused profiles ").append(outcome.rejected());
            }
            Object message = outcome.details() == null ? null : outcome.details().get("message");
            if (message != null) why.append(". ").append(message);
            summary.put("resolution", why.toString());
        }

        /**
         * What the repair said it did, kept for the stage that has to check whether it did.
         *
         * The implementer's own account is not evidence — `moved_candidate` beside it is, and
         * the two are handed over together precisely so a reviewer can notice when they
         * disagree.
         */
        private void recordRepairReceipt(RoleRunner.Outcome fix, String before) throws Exception {
            Map<String, Object> artifact = readArtifact(loaded.root(), fix);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("attempt", (long) attempt);
            receipt.put("profile", fix.profile());
            receipt.put("summary", artifact == null ? "(the implementer filed no artifact)"
                    : String.valueOf(artifact.get("summary")));
            if (artifact != null && artifact.get("files_changed") != null) {
                receipt.put("files_changed", artifact.get("files_changed"));
            }
            String after = currentFingerprint();
            receipt.put("moved_candidate",
                    before != null && after != null && !before.equals(after));
            receipt.put("candidate_fingerprint", after);
            lastRepair = receipt;
            summary.put("last_repair_receipt", receipt);
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
            row.put("assurance", assuranceOf(standing.get("independence"),
                    String.valueOf(standing.get("vendor"))));
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
         * Round deltas stay per stage, because two reviewers reading the same candidate reach
         * their own conclusions and a shared closed/persisted list would let one of them
         * appear to have closed the other's finding. The registry is the exception: a later
         * stage's first reading inherits it so a closed requirement cannot re-enter repair
         * on the original evidence. The comparison of one stage to itself is still what
         * makes "the repair achieved nothing" a fact the run can state.
         */
        private void recordFindingRound(Workflow.Stage stage, RoleRunner.Outcome outcome)
                throws Exception {
            List<Findings.Finding> found = Findings.of(readArtifact(loaded.root(), outcome));
            // The run's whole history, not this stage's last round. The deltas below are still
            // this stage against itself; the lifecycle the evidence rules are checked against
            // belongs to the run, so a stage rechecked after another stage closed something
            // still sees that closure.
            Map<String, Object> row = Findings.roundOf(stage.name(), attempt, currentFingerprint(),
                    found, List.copyOf(findingHistory));
            findingHistory.add(row);
            summary.put("finding_history", List.copyOf(findingHistory));
            if (!stringsOf(row.get("protocol_violations")).isEmpty()) {
                summary.put("finding_protocol_failure", row.get("protocol_violations"));
                throw new StopException("finding_protocol_failure");
            }
        }

        /**
         * Whether the tree has moved since the round a re-reading is being compared against.
         * Asked only where there is no repair receipt to say it instead.
         */
        private boolean movedSince(Map<String, Object> last) {
            Object judged = last == null ? null : last.get("candidate_fingerprint");
            String now = currentFingerprint();
            return judged instanceof String fingerprint && now != null && !fingerprint.equals(now);
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

        /**
         * The most recent round filed by any other stage. A later reviewer's first reading
         * has no previous round of its own; this is the registry and repair context it
         * still has to see.
         */
        private Map<String, Object> lastInheritedFindingRound(String stage) {
            for (int index = findingHistory.size() - 1; index >= 0; index--) {
                if (!stage.equals(findingHistory.get(index).get("stage"))) {
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
            row.put("assurance", assuranceOf(outcome.details() == null ? null
                    : outcome.details().get("independence"), outcome.vendor()));
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
                // One objecting reading, whatever stage gave it and whether it was a first
                // reading or a recheck. The ladder counts readings, not findings.
                blockingReviewsSeen++;
                if (summary.get("escalation") instanceof Map<?, ?> state) {
                    Map<String, Object> updated = new LinkedHashMap<>();
                    state.forEach((key, value) -> updated.put(String.valueOf(key), value));
                    updated.put("blocking_reviews_seen", blockingReviewsSeen);
                    summary.put("escalation", updated);
                }
            }
            // Consulted here rather than only after a repair: a stage's first reading of a
            // resumed run has a previous round restored from the prior run, and that is the
            // one round of that run that would otherwise never reach the fix-loop guards.
            requireNoSeverityLaundering(stage);
            return blocking;
        }

        /**
         * What a verdict may claim about its reader, settled here rather than trusted from
         * the row: an unknown writer set degrades every label to `unproven`, and a reader
         * whose vendor is in a known writer set can only be what the resolver admitted it as.
         */
        private String assuranceOf(Object resolved, String vendor) {
            if (!writers().known()) return RoleResolver.UNPROVEN;
            if (resolved instanceof String label && !label.isBlank()
                    && !RoleResolver.NONE.equals(label) && !RoleResolver.UNPROVEN.equals(label)) {
                return label;
            }
            if (vendor != null && writerVendors.contains(vendor)) return "coauthor";
            return RoleResolver.INDEPENDENT;
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
        private boolean fixable(Workflow.Stage stage, Object outcome) {
            if (outcome instanceof VisualQaRunner.Outcome visual) {
                return "visual_qa_failed".equals(visual.code());
            }
            if (outcome instanceof GateRunner.Outcome gate) {
                return !OPERATOR_MUST_RESOLVE.contains(gate.code());
            }
            if (outcome instanceof RoleRunner.Outcome role) {
                if (roleFailureRequiresOperator(role.code())) return false;
                if (!isInfrastructureFailure(role.code())) return true;
                // A call that timed out, was rate limited or ran out of plan has nothing in it
                // for anyone to repair, even on a stage an operator declared `on_fail: fix`:
                // the repair would be a paid call to a writer about a reviewer's endpoint. An
                // unreadable answer is the one exception, and only when the role that gave it
                // is the role that would repair it — a writer can be shown its own malformed
                // artifact and asked again.
                return "vendor_protocol_failed".equals(INFRASTRUCTURE_REASONS.get(role.code()))
                        && stage.fixWith().equals(stage.role());
            }
            return true;
        }

        /**
         * A call whose wall clock the chain deadline lowered, and which then ran out, stopped
         * because of the deadline. Naming it `role_timed_out` would send the operator to raise
         * a profile limit that was never the binding one.
         */
        private void requireDeadlineNotTheCause(RoleRunner.Outcome role) {
            if (!timedOutUnderDeadline(role.code(), role.details())) return;
            throw new Budget.ExceededException("max_elapsed_minutes", "max_elapsed_minutes of "
                    + (budget.maxElapsedSeconds() / 60) + " reached: a " + role.profile()
                    + " call was cut to the " + role.details().get("wall_clock_minutes")
                    + " minute(s) the chain had left, and used them"
                    + ("role_orca_timeout".equals(role.code())
                        ? "; its Orca worker must be settled before a continuation dispatches, "
                          + "and Orca's own lifecycle check refuses one until it is"
                        : ""));
        }

        private String terminalReason(Workflow.Stage stage, Object outcome) {
            if (outcome instanceof VisualQaRunner.Outcome visual) return visual.code();
            if (outcome instanceof RoleRunner.Outcome role) requireDeadlineNotTheCause(role);
            // A reader nobody may fill because every candidate wrote the code is a fact about
            // the writers, not a reviewer objecting; `reviewer_failed` would send the operator
            // to a transcript with no defect in it and a continuation would discard verdicts.
            if (outcome instanceof RoleRunner.Outcome role && "role_unresolved".equals(role.code())
                    && role.rejected() != null && !role.rejected().isEmpty()
                    && role.rejected().values().stream().allMatch(RoleResolver::isIndependenceReason)) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("role", stage.role());
                gap.put("needed_for", stage.name());
                gap.put("rejected_profiles", role.rejected());
                gap.put("writer_vendors", List.copyOf(new java.util.TreeSet<>(writerVendors)));
                summary.put("unavailable_role", gap);
                summary.putIfAbsent("resolution", "no profile can read this candidate as '"
                        + stage.name() + "' requires: " + role.rejected() + ". Every vendor that "
                        + "could fill the role wrote into the candidate (" + String.join(", ",
                        new java.util.TreeSet<>(writerVendors)) + "), or the task does not admit "
                        + "the same-vendor peer the policy declares. Add a reader from another "
                        + "vendor, or set review_assurance: same_vendor_peer on the task if that "
                        + "weaker check is acceptable for it.");
                return "independent_review_unavailable";
            }
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

        /**
         * What earlier runs of the same chain spent. Counted against every ceiling and never
         * reported as this run's own: `role_runs` and `total_cost_usd` stay per run, because
         * the corpus sums runs and would otherwise count a continued chain twice.
         */
        private Chain inherited = Chain.NONE;
        /** Execution time the chain may spend in all, in seconds; null when undeclared. */
        private final Long maxElapsedSeconds;
        /**
         * Worst-case price per paying stage under a strict cap, or null under a threshold.
         * A call is admitted only while the reported spend plus every bound still owed
         * fits under the ceiling; the bound of the stage being dispatched is retired when
         * its actual price is accounted for.
         */
        private Map<String, Double> strictBounds;
        private final java.util.Set<String> stagesPaid = new java.util.LinkedHashSet<>();
        private String dispatchingStage;
        /** True while this role's vendor attempts have already been charged in-flight. */
        private boolean settledInFlight;
        /**
         * Declared bounds a strict cap charged for attempts that reported no price. They count
         * against the cap and are never reported as spend: `total_cost_usd` is what vendors
         * printed, and a call that printed nothing stays in `unpriced`.
         */
        private double unpricedCharge;
        private final java.util.function.LongSupplier clock;
        private final long startedNanos;

        Budget(long maxRuns, double maxCost) { this(maxRuns, maxCost, null, System::nanoTime); }

        Budget(long maxRuns, double maxCost, Long maxElapsedMinutes,
               java.util.function.LongSupplier clock) {
            this.maxRuns = maxRuns;
            this.maxCost = maxCost;
            this.maxElapsedSeconds = maxElapsedMinutes == null ? null : maxElapsedMinutes * 60;
            this.clock = clock;
            this.startedNanos = clock.getAsLong();
        }

        int runs() { return runs; }
        double spent() { return spent; }

        void strictBounds(Map<String, Double> bounds) { this.strictBounds = new LinkedHashMap<>(bounds); }

        boolean strict() { return strictBounds != null; }

        /** The stage about to dispatch, so its own bound counts once and is retired after. */
        void dispatching(String stage) { this.dispatchingStage = stage; }

        /** Bounds still owed: every paying stage not yet paid, the dispatching one included. */
        double outstandingBounds() {
            if (strictBounds == null) return 0;
            double sum = 0;
            for (Map.Entry<String, Double> bound : strictBounds.entrySet()) {
                if (stagesPaid.contains(bound.getKey())) continue;
                sum += bound.getValue();
            }
            return sum;
        }

        void inherit(Chain chain) { this.inherited = chain; }

        Chain inherited() { return inherited; }

        long chainRuns() { return inherited.roleRuns() + runs; }

        double chainSpent() { return inherited.costUsd() + spent; }

        long chainUnpriced() { return inherited.unpricedCalls() + unpriced; }

        double unpricedCharge() { return unpricedCharge; }

        double chainUnpricedCharge() { return inherited.unpricedChargeUsd() + unpricedCharge; }

        /** Execution seconds the chain has used, this run's so far included. */
        long elapsedSeconds() {
            return inherited.elapsedSeconds() + (clock.getAsLong() - startedNanos) / 1_000_000_000L;
        }

        Long maxElapsedSeconds() { return maxElapsedSeconds; }

        /** What is left of the chain's execution time, or null when it declared none. */
        java.time.Duration wallClockCap() {
            if (maxElapsedSeconds == null) return null;
            return java.time.Duration.ofSeconds(Math.max(0, maxElapsedSeconds - elapsedSeconds()));
        }

        /** Whether {@link #requireRoleRun} would admit another call right now. */
        boolean hasRoom() {
            if (maxRuns > 0 && chainRuns() >= maxRuns) return false;
            if (maxCost > 0 && chainSpent() >= maxCost) return false;
            if (strictBounds != null && maxCost > 0
                    && chainSpent() + chainUnpricedCharge() + outstandingBounds() > maxCost) {
                return false;
            }
            return !deadlinePassed();
        }

        boolean deadlinePassed() {
            java.time.Duration left = wallClockCap();
            return left != null && left.getSeconds() < MINIMUM_CALL_SECONDS;
        }

        /**
         * Vendor calls that already happened before this loop, counted so preparation is
         * not free. A reservation is never given back: see {@link #requireRoleRun}.
         */
        void alreadySpent(int priorRuns, double priorCost, int priorUnpriced) {
            this.runs = Math.max(0, priorRuns);
            this.spent = Math.max(0, priorCost);
            this.unpriced = Math.max(0, priorUnpriced);
        }

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
            return (int) Math.max(0, maxRuns - chainRuns());
        }

        long maxRuns() { return maxRuns; }

        void requireRoleRun() {
            String earlier = inherited.runs().isEmpty() ? ""
                    : " across this run and " + String.join(", ", inherited.runs());
            if (maxRuns > 0 && chainRuns() >= maxRuns) {
                throw new ExceededException("max_role_runs",
                        "max_role_runs of " + maxRuns + " reached" + earlier);
            }
            if (maxCost > 0 && chainSpent() >= maxCost) {
                throw new ExceededException("max_cost_usd",
                        "max_cost_usd of " + maxCost + " reached, spent " + chainSpent() + earlier);
            }
            // Under a strict cap the reported spend plus the worst case of every call still
            // owed has to fit, so the last call cannot be the one that overruns. A repair is
            // a call the plan did not reserve; its stage is owed again when it re-dispatches.
            if (strictBounds != null && maxCost > 0) {
                if (dispatchingStage != null) stagesPaid.remove(dispatchingStage);
                double owed = outstandingBounds();
                double charged = chainUnpricedCharge();
                if (chainSpent() + charged + owed > maxCost) {
                    throw new ExceededException("max_cost_usd", "max_cost_usd of " + maxCost
                            + " is a strict cap: reported spend " + chainSpent()
                            + (charged > 0 ? ", " + charged + " charged at declared bounds for "
                                    + "calls that reported no price," : "")
                            + " plus the declared worst case of the calls still owed (" + owed
                            + ") would exceed it" + earlier);
                }
            }
            // A call is not started with less than a minute left. The wall clock a profile
            // declares is in whole minutes, and a call admitted with seconds to spare would be
            // given a minute it does not have.
            if (deadlinePassed()) {
                throw new ExceededException("max_elapsed_minutes",
                        "max_elapsed_minutes of " + (maxElapsedSeconds / 60) + " reached: "
                                + elapsedSeconds() + " s of execution already spent" + earlier);
            }
            runs++;
        }

        int unpriced() { return unpriced; }

        /**
         * The selected profile's declared bound for the stage about to dispatch. A pin or
         * host twin can be dearer than anyone on the policy list; reserving the list's max
         * then spent the pin under a number that had never been checked.
         */
        void reserveSelected(Double bound) {
            if (strictBounds == null || dispatchingStage == null) return;
            if (bound == null) {
                throw new ExceededException("max_cost_usd",
                        "budgets.cost_cap: strict needs a proven upper bound for the profile "
                                + "about to dispatch at stage '" + dispatchingStage
                                + "', and it declares none");
            }
            strictBounds.put(dispatchingStage, bound);
        }

        /**
         * Charge one completed vendor attempt before a retry or failover is admitted.
         * Unpriced attempts under a strict cap are charged at the profile's declared bound
         * so the next dispatch cannot treat them as free. That charge is the cap's, not the
         * vendor's: folding it into {@code spent} reported a Codex call or a spent quota as
         * dollars nobody printed, and hid it from {@code unpriced_calls}.
         */
        void settleAttempt(Object cost, Double bound) {
            if (cost instanceof Number number) {
                spent += number.doubleValue();
            } else {
                unpriced++;
                if (strictBounds != null && bound != null && bound > 0) unpricedCharge += bound;
            }
            settledInFlight = true;
        }

        /**
         * The role already charged its vendor attempts through {@link #settleAttempt}.
         * {@link #record} must not add them again; it still retires the stage bound.
         */
        boolean finishSettledRole() {
            if (!settledInFlight) return false;
            settledInFlight = false;
            if (strictBounds != null && dispatchingStage != null) {
                stagesPaid.add(dispatchingStage);
                dispatchingStage = null;
            }
            return true;
        }

        void account(Object cost) {
            if (cost instanceof Number number) spent += number.doubleValue();
            else unpriced++;
            if (strictBounds != null && dispatchingStage != null) {
                stagesPaid.add(dispatchingStage);
                dispatchingStage = null;
            }
        }
    }

    /** The shortest time a vendor call is started with. See {@link Budget#requireRoleRun}. */
    private static final long MINIMUM_CALL_SECONDS = 60;

    /**
     * What the earlier runs of a continued chain already spent.
     *
     * A continuation used to start from nothing: a fresh call ceiling, a fresh set of fix
     * rounds, a fresh clock. So a task declared as "four calls, one repair" could be run as
     * four calls and one repair per `--continue`, and the limits an operator chose described
     * one run rather than the work. The chain is the unit now. Raising a limit is still one
     * edit to the task file, which is not part of what the work is judged by, and the run
     * that follows records it as `contract_change_budget_only`.
     *
     * Only a continuation that carries verdicts — a `retry` or a `switch` — is the same
     * chain. A rejection is a person asking for different work, and starts a new one; so
     * does a run with no `--continue`, which carries nothing at all.
     *
     * @param elapsedKnown false when an earlier run predates this record, whose execution
     *                     time is then not counted rather than guessed
     */
    record Chain(List<String> runs, long roleRuns, double costUsd, long unpricedCalls,
                 long fixAttempts, long elapsedSeconds, boolean elapsedKnown,
                 long blockingReviewsSeen, long escalationRung, double unpricedChargeUsd) {
        static final Chain NONE = new Chain(List.of(), 0, 0, 0, 0, 0, true, 0, 0, 0);

        Chain(List<String> runs, long roleRuns, double costUsd, long unpricedCalls,
              long fixAttempts, long elapsedSeconds, boolean elapsedKnown) {
            this(runs, roleRuns, costUsd, unpricedCalls, fixAttempts, elapsedSeconds, elapsedKnown,
                    0, 0, 0);
        }

        /** The chain as the named run left it, or {@link #NONE} when it cannot be read. */
        static Chain after(Path root, String runId) {
            try {
                Path file = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
                if (!Files.isRegularFile(file)) return NONE;
                Map<String, Object> prior = Json.parseObject(Files.readString(file));
                if (prior.get("chain") instanceof Map<?, ?> chain) {
                    List<String> runs = new ArrayList<>();
                    if (chain.get("runs") instanceof List<?> names) {
                        for (Object name : names) runs.add(String.valueOf(name));
                    }
                    return new Chain(List.copyOf(runs), number(chain.get("role_runs")),
                            decimal(chain.get("cost_usd")), number(chain.get("unpriced_calls")),
                            number(chain.get("fix_attempts")), number(chain.get("elapsed_seconds")),
                            !Boolean.FALSE.equals(chain.get("elapsed_known")),
                            number(chain.get("blocking_reviews_seen")),
                            number(chain.get("escalation_rung")),
                            decimal(chain.get("cost_cap_unpriced_charge_usd")));
                }
                // Written before chains were recorded: the run's own totals are the chain.
                return new Chain(List.of(runId), number(prior.get("role_runs")),
                        decimal(prior.get("total_cost_usd")), number(prior.get("unpriced_calls")),
                        number(prior.get("attempts_used")), 0, false);
            } catch (Exception unreadable) {
                return NONE;
            }
        }

        private static long number(Object value) {
            return value instanceof Number number ? number.longValue() : 0L;
        }

        private static double decimal(Object value) {
            return value instanceof Number number ? number.doubleValue() : 0.0;
        }
    }

    /** A prior run's persisted proof that every reproduction command failed on the base. */
    private record ReproductionReceipt(String runId, Path report, String sha256, Object commands) {}

    /**
     * The candidate is expected to move after red proof. Bind reuse to the acceptance and
     * immutable base instead, and verify the original on-disk receipt even across multiple
     * continuations. A missing, redirected or corrupted receipt never becomes proof.
     */
    private ReproductionReceipt reusableReproduction(Path root, String priorRunId, String taskId,
                                                       String acceptanceHash, String diffBaseCommit) {
        try {
            Path evidenceRoot = root.resolve(".warden/runs").toAbsolutePath().normalize();
            Map<String, Object> prior = Json.parseObject(Files.readString(
                    evidenceRoot.resolve(priorRunId).resolve("task-run.json")));
            if (!Boolean.TRUE.equals(prior.get("reproduction_ok"))
                    || !taskId.equals(prior.get("task_id"))
                    || !acceptanceHash.equals(prior.get("acceptance_sha256"))
                    || !diffBaseCommit.equals(prior.get("diff_base_commit"))) return null;
            Object reportText = prior.get("reproduction_report");
            Object receiptRunBody = prior.get("reproduction_receipt_run");
            String receiptRun = receiptRunBody instanceof String value && !value.isBlank()
                    ? value : priorRunId;
            if (!(reportText instanceof String text) || text.isBlank()) return null;
            Path report = Path.of(text);
            if (!report.isAbsolute()) report = root.resolve(report);
            report = report.toAbsolutePath().normalize();
            Path expected = evidenceRoot.resolve(receiptRun + "--reproduction-0")
                    .resolve("reproduction-gate.json").normalize();
            if (!expected.startsWith(evidenceRoot) || !report.equals(expected)
                    || !Files.isRegularFile(report)) return null;
            String hash = GitRepository.contentSha256(report);
            if (!hash.equals(prior.get("reproduction_report_sha256"))) return null;
            Map<String, Object> receipt = Json.parseObject(Files.readString(report));
            if (!"reproduction".equals(receipt.get("phase"))
                    || !Boolean.TRUE.equals(receipt.get("ok"))
                    || !"passed".equals(receipt.get("code"))
                    || !taskId.equals(receipt.get("task_id"))
                    || !diffBaseCommit.equals(receipt.get("merge_base"))) return null;
            return new ReproductionReceipt(receiptRun, report, hash, receipt.get("commands"));
        } catch (Exception unreadable) {
            return null;
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
            Map<String, Object> summary, RunOverride overlay, Map<String, String> substitutions) throws Exception {
        Path priorSummary = root.resolve(".warden/runs").resolve(priorRunId).resolve("task-run.json");
        if (!Files.isRegularFile(priorSummary)) return declineReuse(summary, "no summary for " + priorRunId);
        Map<String, Object> prior = Json.parseObject(Files.readString(priorSummary));
        String priorReason = reasonForContinuation(prior);
        if (!priorReason.equals(String.valueOf(prior.get("reason")))) {
            summary.put("prior_reason_reclassified", Map.of(
                    "recorded", String.valueOf(prior.get("reason")), "read_as", priorReason));
        }
        if (isAboutTheWork(priorReason)) {
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
                } else if (row.get("fix_for") != null && writerStageNamed(workflow, role) != null) {
                    // A fix round is the writer's latest product, not an intermediate step of
                    // someone else's stage. When the repair is the last thing the writer did,
                    // the tree it left is the tree being resumed, and paying the writer again
                    // to reproduce it bought nothing: measured, a continuation re-paid thirty
                    // minutes of implementer to redo a candidate it had already written. The
                    // row is admitted under the writer stage's name, and the fingerprint check
                    // below still decides whether it describes this tree. A judging stage's
                    // fix row never reaches here: those rows carry no `fix_for` of their own
                    // role, and a reader's verdict is never derived from a repair.
                    key = writerStageNamed(workflow, role);
                } else if (!stageKeyed && workflow.stagesFor(role).size() == 1) {
                    key = role;
                } else {
                    continue;
                }
                if (!Boolean.TRUE.equals(row.get("ok"))) {
                    // A repair that failed before it could write — rate limited, timed out —
                    // says nothing about the writer's last product; if it did move the tree,
                    // the fingerprint check below declines the stage row on its own. A stage
                    // row that failed is the stage's own verdict and still retires the key.
                    if (row.get("fix_for") == null) reusable.remove(key);
                    continue;
                }
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
                    currentStageFor(workflow, key, keyToRole.get(key)), user, overlay, substitutions);
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
    /**
     * The stage a writing role owns, by name, or null when the chain dispatches that role
     * only in repairs. A fix row is admitted for reuse only under a stage that exists now.
     */
    private static String writerStageNamed(Workflow workflow, String role) {
        if (!"implementer".equals(role)) return null;
        List<Workflow.Stage> owned = workflow.stagesFor(role);
        return owned.size() == 1 ? owned.get(0).name() : null;
    }

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
                        CallPlan plan, Budget budget, List<Map<String, Object>> cast) {
        progress.blank();
        progress.line("run   " + runId + (dryRun ? "   (dry run: nothing is dispatched)" : ""));
        progress.line("task  " + task.id() + "   risk=" + task.risk()
                + "   scope=" + String.join(", ", task.scopePaths()));
        List<String> names = new ArrayList<>();
        if (!task.baselineCommands().isEmpty()) names.add("baseline");
        for (Workflow.Stage stage : workflow.stages()) names.add(stage.name());
        progress.line("plan  " + String.join(" -> ", names));
        printCast(cast);
        progress.line("bound " + task.budget().maxRoleRuns() + " vendor call(s), "
                + Progress.money(task.budget().maxCostUsd()) + " of reported spend, fix rounds <= "
                + task.maxFixAttempts()
                + (task.budget().maxElapsedMinutes() == null ? ""
                        : ", " + task.budget().maxElapsedMinutes() + " min of execution"));
        Chain earlier = budget.inherited();
        if (!earlier.runs().isEmpty()) {
            // The limits above are the chain's, and a continuation is told how much of them is
            // gone before it spends any more.
            progress.line("      continuing " + String.join(", ", earlier.runs()) + ": "
                    + earlier.roleRuns() + " call(s), " + Progress.money(earlier.costUsd())
                    + ", " + earlier.fixAttempts() + " fix round(s)"
                    + (earlier.elapsedKnown() ? ", " + earlier.elapsedSeconds() + " s"
                            : ", execution time not recorded")
                    + " already spent");
        }
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
            // The chain with its cast, not the chain alone. "implement -> review -> look" is
            // the same sentence on every card an operator owns; "implement(grok) ->
            // review(openai) -> look(claude)" is the one that tells them which run to stop.
            workspace.note(task.id() + " · " + String.join(" -> ", castNames(names, cast))
                    + " · starting");
        }
    }

    /** Stage names with the vendor in brackets where the roster named one. */
    private static List<String> castNames(List<String> names, List<Map<String, Object>> cast) {
        Map<String, Object> vendors = new LinkedHashMap<>();
        for (Map<String, Object> row : cast) {
            if (row.get("vendor") != null) vendors.put(String.valueOf(row.get("stage")), row.get("vendor"));
        }
        List<String> labelled = new ArrayList<>();
        for (String name : names) {
            labelled.add(vendors.containsKey(name) ? name + "(" + vendors.get(name) + ")" : name);
        }
        return labelled;
    }

    /** Where the run ended and what the person watching is now expected to do about it. */
    private void footer(Path root, String runId, String reason, String nextAction, Budget budget,
                        Map<String, Object> summary) {
        progress.blank();
        progress.line("done  " + reason + "   " + budget.runs() + " vendor call(s), "
                + Progress.money(budget.spent()) + " charged");
        Object unpriced = summary.get("unpriced_calls");
        if (unpriced instanceof Number number && number.longValue() > 0) {
            progress.line("      " + number + " of those reported no price at all, so the "
                    + "$ ceiling did not measure them"
                    + (budget.unpricedCharge() > 0 ? "; the strict cap counted them at their "
                            + "declared bounds (" + Progress.money(budget.unpricedCharge()) + ")" : ""));
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
        revealVisualEvidence(root, runId, summary);
    }

    /**
     * The pictures the visual stage was judged on, opened on the board at the gate.
     *
     * The verdict a person is about to accept or reject is a verdict about pixels, and the
     * only trace of those on the board was a digest in the ledger — so answering from a
     * phone meant answering on the role's word. The verified set comes first, because that
     * is what the verdict was measured against; a harness run that wrote its shots straight
     * into the evidence directory falls back to the directory.
     *
     * A few are opened, not all: a stage that photographed eleven states would bury the card
     * it was meant to support, and {@code warden report} has the rest. Failing to open one
     * changes nothing — the run has already stopped and already decided.
     */
    private void revealVisualEvidence(Path root, String runId, Map<String, Object> summary) {
        List<Path> pictures = new ArrayList<>();
        if (summary.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> step)) continue;
                if (!(step.get("image_evidence") instanceof List<?> images)) continue;
                for (Object item : images) {
                    if (item instanceof Map<?, ?> image && image.get("path") instanceof String path) {
                        pictures.add(Path.of(path));
                    }
                }
            }
        }
        if (pictures.isEmpty()) {
            // The harness does not write into the run's own directory: each stage gets a
            // sibling, `tally-3--visual-qa-0`, and the PNGs are under that. Found by
            // running this against a live browser stage and revealing nothing at all.
            Path runs = root.resolve(".warden/runs");
            try (var siblings = Files.list(runs)) {
                for (Path directory : siblings.sorted().toList()) {
                    String name = directory.getFileName().toString();
                    if (!Files.isDirectory(directory)) continue;
                    if (!name.equals(runId) && !name.startsWith(runId + "--")) continue;
                    try (var found = Files.walk(directory, 3)) {
                        found.filter(Files::isRegularFile)
                                .filter(file -> file.getFileName().toString().toLowerCase()
                                        .endsWith(".png"))
                                .sorted()
                                .forEach(pictures::add);
                    }
                }
            } catch (Exception nothingToShow) {
                return;
            }
        }
        pictures.stream().limit(MAX_REVEALED_IMAGES).forEach(workspace::reveal);
    }

    /** Enough to judge by, few enough that the board still reads as a board. */
    private static final int MAX_REVEALED_IMAGES = 4;

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
            if (!budget.finishSettledRole()) {
                if (attempts instanceof List<?> list && !list.isEmpty()) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) budget.account(map.get("cost_usd"));
                    }
                } else {
                    budget.account(step.details().get("cost_usd"));
                }
            }
            if (attempts instanceof List<?> list && !list.isEmpty()) {
                entry.put("vendor_attempts", attempts);
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
            // The pictures a self-acquiring visual role was verified to have taken, as digests.
            if (step.details().get("image_evidence") != null) {
                entry.put("evidence", step.details().get("evidence"));
                entry.put("image_evidence", step.details().get("image_evidence"));
            }
            if (step.details().get("screenshots_refused") != null) {
                entry.put("screenshots_refused", step.details().get("screenshots_refused"));
            }
            if (step.details().get("independence") != null) {
                entry.put("independence", step.details().get("independence"));
            }
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
        if ("role_failover_requires_confirmation".equals(step.code())) {
            return "failover_requires_confirmation";
        }
        if ("role_visual_no_evidence".equals(step.code())) return "visual_qa_no_evidence";
        if ("role_mcp_config_missing".equals(step.code())) return "mcp_config_missing";
        String infrastructure = INFRASTRUCTURE_REASONS.get(step.code());
        return infrastructure != null ? infrastructure : genericReason;
    }

    /**
     * The failed call behind an infrastructure stop, named once at the top of the summary.
     *
     * The row is already in `steps`; this saves the operator from finding it, and says in so
     * many words that the stop is not a judgement and what a continuation will keep.
     */
    private static void describeInfrastructureStop(String reason, Map<String, Object> summary,
                                                   List<Map<String, Object>> steps) {
        String cause = INFRASTRUCTURE_CAUSES.get(reason);
        if (cause == null) return;
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("cause", cause);
        for (int index = steps.size() - 1; index >= 0; index--) {
            Map<String, Object> row = steps.get(index);
            if (Boolean.FALSE.equals(row.get("ok")) && row.get("profile") != null) {
                failure.put("step", row.get("step"));
                if (row.get("stage") != null) failure.put("stage", row.get("stage"));
                failure.put("role_code", row.get("code"));
                failure.put("profile", row.get("profile"));
                failure.put("vendor", row.get("vendor"));
                break;
            }
        }
        failure.put("verdict_on_the_work", false);
        failure.put("on_continue", "stages that already passed this exact tree under the same "
                + "contract and roster are reused; the failed stage is dispatched again");
        summary.put("infrastructure_failure", failure);
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
                                             boolean dryRun, List<Map<String, Object>> steps,
                                             Path home)
            throws Exception {
        if (dryRun) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", "visual_qa");
            entry.put("attempt", (long) attempt);
            entry.put("dry_run", true);
            steps.add(entry);
            return null;
        }
        VisualQaRunner.Outcome outcome = visualCheck != null
                ? visualCheck.run(loaded, stepRunId(runId, "visual-qa", attempt))
                : new VisualQaRunner(processes).withHome(home)
                        .run(loaded, stepRunId(runId, "visual-qa", attempt));
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
                                             RoleResolver.Writers writers, VisualQaRunner.Outcome visual,
                                             boolean dryRun, List<Map<String, Object>> steps,
                                             Budget budget) throws Exception {
        return runVisualRole(loaded, user, roles, runId, attempt, writers, visual, dryRun, steps,
                budget, false);
    }

    /**
     * @param acquires the stage expects the role to take its own screenshots; a verdict that
     *                 lists none it actually wrote inside the run's evidence is refused
     */
    private RoleRunner.Outcome runVisualRole(ConfigLoader.Loaded loaded, UserConfig user,
                                             RoleRunner roles, String runId, int attempt,
                                             RoleResolver.Writers writers, VisualQaRunner.Outcome visual,
                                             boolean dryRun, List<Map<String, Object>> steps,
                                             Budget budget, boolean acquires) throws Exception {
        List<Path> screenshots = visual == null ? List.of() : screenshotsOf(visual);
        Path context = null;
        if (visual != null) {
            EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId, user.home());
            Path directory = ledger.runDirectory().resolve("context");
            Files.createDirectories(directory);
            context = directory.resolve("visual-" + attempt + "-harness.json");
            Files.writeString(context, Json.writePretty(visual.data()) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        RoleRunner.Outcome outcome = roles.run(loaded, user, "visual_qa",
                stepRunId(runId, "visual-role", attempt), writers, context, screenshots, dryRun,
                RoleRunner.UNSTAGED);
        if (!dryRun && acquires && outcome.ok()) outcome = verifyAcquiredEvidence(loaded.root(), outcome);
        record(steps, budget, "visual_qa", attempt, outcome);
        if (dryRun) return null;
        return outcome;
    }

    /**
     * The pixels a self-acquiring visual role says it looked at, checked rather than believed.
     *
     * Each path in {@code screenshots_taken} has to be an existing, non-empty file inside the
     * project's own run evidence; anything else is a claim about a file Warden cannot vouch
     * for. What survives is hashed onto the outcome as {@code image_evidence}, the same shape
     * the harness records, so a report reads both kinds alike. A verdict that lists nothing
     * that survives is not a verdict: the role answered from something other than pictures.
     */
    private RoleRunner.Outcome verifyAcquiredEvidence(Path root, RoleRunner.Outcome outcome)
            throws Exception {
        Map<String, Object> artifact = readArtifact(root, outcome);
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Path runs = outcome.report().toAbsolutePath().normalize().getParent();
        if (artifact != null && artifact.get("screenshots_taken") instanceof List<?> listed) {
            for (Object item : listed) {
                if (!(item instanceof String text) || text.isBlank()) continue;
                Path file = Path.of(text);
                if (!file.isAbsolute()) file = root.resolve(file);
                file = file.toAbsolutePath().normalize();
                if (!file.startsWith(runs) || !Files.isRegularFile(file)
                        || !file.toRealPath().startsWith(runs.toRealPath())) {
                    refused.add(text + " (not a file inside the run's evidence)");
                    continue;
                }
                if (!AgentImageEvidence.isImage(file)) {
                    refused.add(text + " (not a decodable bounded image)");
                    continue;
                }
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("path", file.toString());
                image.put("sha256", GitRepository.contentSha256(file));
                image.put("bytes", Files.size(file));
                evidence.add(image);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>(
                outcome.details() == null ? Map.of() : outcome.details());
        details.put("evidence", "agent");
        details.put("image_evidence", evidence);
        if (!refused.isEmpty()) details.put("screenshots_refused", refused);
        if (!evidence.isEmpty()) {
            return new RoleRunner.Outcome(true, outcome.code(), outcome.role(), outcome.profile(),
                    outcome.vendor(), outcome.rejected(), outcome.report(), details);
        }
        details.put("resolution", "the visual role answered without listing a screenshot it took "
                + "inside the run's evidence directory. A verdict on pictures nobody can find is "
                + "not evidence; check that the profile's MCP servers can drive the application "
                + "and that the role writes its screenshots where the prompt says.");
        return new RoleRunner.Outcome(false, "role_visual_no_evidence", outcome.role(),
                outcome.profile(), outcome.vendor(), outcome.rejected(), outcome.report(), details);
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
        if (budget.unpricedCharge() > 0) {
            summary.put("cost_cap_unpriced_charge_usd", budget.unpricedCharge());
        }
        summary.put("cost_ceiling_binding", budget.chainUnpriced() == 0);
        describeChain(summary, budget, String.valueOf(summary.get("run_id")),
                null, attempt);
        summary.put("steps", steps);
        // A stop is where the three questions are most often confused, so it is where they are
        // most worth separating: a candidate that passed review can sit inside a run that
        // stopped, and saying only that the run stopped throws that away.
        describeCompletion(summary);
        describeInfrastructureStop(reason, summary, steps);
        summary.put("safe_next_step", safeNextStep(reason, summary));
        summary.put("next_step", NextStep.of(reason, summary, ledger.projectRoot()));
        Map<String, Object> proposal = ContractProposal.prepare(ledger.projectRoot(), summary);
        if (!proposal.isEmpty()) summary.put("contract_proposal", proposal);
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
        } else if (!proposal.isEmpty()) {
            decision = store.createPending(runIdentity, taskIdentity, HumanDecision.Kind.CONTRACT_CHANGE,
                    ContractProposal.question(proposal), file, fingerprint);
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
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);
        footer(root, runIdentity, reason, "human_escalation", budget, summary);
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
        // The weakest label among the readings, because a candidate is only as independently
        // reviewed as its least independent reading. `independent` is never the default: it
        // is what every reading was, or the answer is something else.
        String weakest = null;
        if (summary.get("review_coverage") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> entry)) continue;
                String label = entry.get("assurance") instanceof String text ? text : "unproven";
                weakest = weaker(weakest, label);
            }
        }
        if (weakest != null) summary.put("review_assurance", weakest);
    }

    /** Order of assurance labels, strongest first; the weaker of two wins. */
    private static final List<String> ASSURANCE_ORDER = List.of(
            "independent", "same_vendor_peer", "peer_review", "coauthor", "unproven");

    private static String weaker(String current, String candidate) {
        if (current == null) return candidate;
        int a = ASSURANCE_ORDER.indexOf(current);
        int b = ASSURANCE_ORDER.indexOf(candidate);
        if (a < 0) return current;
        if (b < 0) return candidate;
        return b > a ? candidate : current;
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
                if ("max_elapsed_minutes".equals(summary.get("budget_limit_hit"))) {
                    long used = summary.get("chain") instanceof Map<?, ?> chain
                            && chain.get("elapsed_seconds") instanceof Number seconds
                            ? seconds.longValue() : 0L;
                    yield "raise budgets.max_elapsed_minutes in .warden/tasks/" + taskId
                            + ".yaml — " + summary.get("budget_stop") + " — to more than "
                            + ((used + 59) / 60 + 1) + " (the chain has used " + used + " s; a "
                            + "call needs at least one whole minute), then: " + carryOn
                            + ". Time a stopped run spends waiting for you is not counted" + kept;
                }
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
            case "ledger_unavailable" -> "restore write access to the home corpus at "
                    + "<WARDEN_CONFIG_HOME>/ledger (the reason is in corpus_error), then: "
                    + carryOn + ". The verdicts this run reached are kept: a corpus that "
                    + "could not be written is not a judgement on the work, and no vendor is "
                    + "re-dispatched to recover a measurement.";
            case "finding_protocol_failure" -> "inspect finding_protocol_failure and finding_history in "
                    + "warden report " + runId + " --text; resolve identity/evidence before a new run";
            case "quota_exhausted" -> "wait for the quota window named in the vendor message, "
                    + "or add a profile from another vendor, then: " + carryOn + kept(summary);
            case "rate_limited" -> "the vendor asked to slow down; its subscription is not "
                    + "known to be spent, so no other vendor was offered. Wait a few minutes, "
                    + "then: " + carryOn + kept(summary);
            case "role_timed_out" -> "the " + failedProfile(summary) + " call ran out of wall "
                    + "clock. Raise limits.wall_clock_minutes on that profile if the task "
                    + "needs longer, or narrow the task, then: " + carryOn + kept(summary);
            case "orca_worker_not_started" -> "the " + failedProfile(summary) + " Orca worker "
                    + "never took its first turn, so it read nothing. The usual cause is the agent "
                    + "CLI waiting on a first-run screen in that tab: Claude Code asks once per "
                    + "repository whether to trust it, and Codex asks to update. The role report's "
                    + "agent_screen_tail and agent_blocked_on say which. Answer it once in an Orca "
                    + "tab in this worktree, then: " + carryOn + kept(summary);
            case "vendor_call_failed" -> "the " + failedProfile(summary) + " process failed "
                    + "without a recognised cause (a login, the network, the CLI itself). Read "
                    + "stderr_tail and stdout_tail in its role report, fix what they name, "
                    + "then: " + carryOn + kept(summary);
            case "vendor_protocol_failed" -> "the " + failedProfile(summary) + " call "
                    + "answered, but not with a readable, complete, schema-valid artifact, so "
                    + "whatever it concluded is unknown. Check the profile's prompt, schema "
                    + "and output flags against its raw stdout, then: " + carryOn + kept(summary);
            case "prompt_undeliverable" -> "the prompt could not be delivered to "
                    + failedProfile(summary) + " (argument_delivery_check in its role report "
                    + "says why); switch the profile to prompt_delivery: stdin or a prompt "
                    + "file, then: " + carryOn + kept(summary);
            case "failover_requires_confirmation" -> "warden approve " + runId
                    + " --decision switch  then  warden run " + taskId + " --continue " + runId;
            case "severity_downgraded_without_change" -> "a finding that had been P1 stopped "
                    + "blocking on a candidate that did not change, so the run would have "
                    + "passed on a relabelling or a dropped finding. Read the two readings in "
                    + "`warden report " + runId + " --text`. If the P1 was wrong, settle that "
                    + "on the evidence and adjust the task or the finding deliberately; then "
                    + "start a new run.";
            case "repair_made_no_progress" -> "the last repair left the candidate byte-for-byte "
                    + "unchanged and the same finding came back, so another round would re-read "
                    + "the same bytes. Read the open findings in `warden report " + runId
                    + " --text`: one an implementer cannot act on is a task for a person, not "
                    + "for another fix round. Change the contract or the finding's premise, "
                    + "then start a new run.";
            case "quality_exhausted" -> "every rung of the escalation ladder wrote and was read, "
                    + "and the last reading still objects. Read the open findings in `warden report "
                    + runId + " --text`; nothing further is allowed to write. Change the task or "
                    + "the finding's premise, or fix by hand, then start a new run.";
            case "escalation_unavailable" -> "the escalation ladder's next rung cannot be filled "
                    + "(unavailable_role says which profile and why). Verify or replace that "
                    + "profile in policy.yaml, then: " + carryOn + kept(summary);
            case "blocking_findings_remain" -> {
                if (summary.get("nonactionable_blocking_ids") instanceof List<?> leftover
                        && !leftover.isEmpty()) {
                    yield "the remaining P1(s) are not the implementer's to close. Read them in "
                            + "`warden report " + runId + " --text`, change the task or grant "
                            + "what they ask for, then start a new run.";
                }
                yield "read `warden report " + runId + " --text`, then either address what it "
                        + "names and start a new run, or " + carryOn;
            }
            case "reproduction_passed_before_change" -> "the acceptance does not detect the defect. "
                    + "Strengthen the named check or pick an acceptance command that fails today, "
                    + "then start a new run; --continue carries no proof after the contract changes.";
            case "reproduction_inconclusive" -> "the reproduction could not establish a red base. "
                    + "Resolve the timeout or gate error and start a new run on the unchanged tree "
                    + "before paying a vendor.";
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

    /** What an infrastructure stop's continuation keeps, named rather than implied. */
    private static String kept(Map<String, Object> summary) {
        List<String> passed = new ArrayList<>();
        if (summary.get("review_coverage") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> entry && Boolean.TRUE.equals(entry.get("ok"))
                        && !(entry.get("blocking_findings") instanceof Number n && n.longValue() > 0)) {
                    passed.add(String.valueOf(entry.get("stage")));
                }
            }
        }
        return ". This stop is not a judgement on the work: "
                + (passed.isEmpty() ? "any stage that already passed this exact tree"
                        : String.join(", ", passed))
                + " is reused rather than paid for again, provided the tree, the acceptance and "
                + "the roster are unchanged; the failed stage runs again.";
    }

    /** The profile behind an infrastructure stop, for a sentence that names it. */
    private static String failedProfile(Map<String, Object> summary) {
        if (summary.get("infrastructure_failure") instanceof Map<?, ?> failure
                && failure.get("profile") != null) {
            return "'" + failure.get("profile") + "'";
        }
        return "vendor";
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
        for (Findings.Finding finding : Findings.of(artifact)) {
            if (!finding.blocking()) continue;
            Map<String, Object> raw = finding.raw();
            builder.append("## ").append(finding.id()).append(" — ").append(finding.path());
            if (raw.get("line") != null) builder.append(':').append(raw.get("line"));
            builder.append("\n\n")
                    .append("- category: ").append(finding.category()).append('\n')
                    .append("- expected: ").append(raw.get("expected")).append('\n')
                    .append("- actual: ").append(raw.get("actual")).append('\n');
            if (raw.get("scenario") != null) {
                builder.append("- reproduce: ").append(raw.get("scenario")).append('\n');
            }
            builder.append('\n');
        }
        builder.append("""
                Each finding states an expected and an actual. Address the difference, or, if the
                reviewer is wrong, say so in your summary with the evidence that shows it — do not
                silently leave a finding unaddressed.

                Its `category` says what kind of problem the reviewer thinks it is. Only
                `product_defect` is reliably yours: a `contract_gap`, an `access_required` or a
                `review_disagreement` is usually resolved by a person changing the task, not by
                editing this diff.

                If a finding is not something a change to this diff can fix — the acceptance
                command is too weak, an access grant is missing, the contract itself is wrong —
                say that in your summary and change nothing on its account. A repair that leaves
                the candidate byte-for-byte unchanged ends the run rather than buying another
                reading of the same bytes, so an honest "this one is not mine to fix" is worth
                more here than a token edit.

                Quote each finding's id in your summary and say what you did about it, so the
                next reading can check closure instead of starting over.
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
