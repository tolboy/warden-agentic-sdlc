package dev.warden.run;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.WardenTree;
import dev.warden.config.Workflow;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;
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
        return new TaskLoop(processes, visualCheck, narration);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress) {
        this.processes = processes;
        this.visualCheck = visualCheck;
        this.progress = progress;
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
            "visual_qa_unavailable", "visual_qa_port_occupied", "preflight_outside_scope");

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

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", task.id());
        summary.put("diff_base_commit", diffBaseCommit);
        summary.put("contract_sha256", contractHash);
        summary.put("risk", task.risk());
        summary.put("dry_run", dryRun);
        summary.put("max_fix_attempts", task.maxFixAttempts());
        summary.put("budget_max_role_runs", task.budget().maxRoleRuns());
        summary.put("budget_max_cost_usd", task.budget().maxCostUsd());
        summary.put("review_required", reviewRequired);
        summary.put("visual_qa_required", task.visualQa().required());
        summary.put("visual_qa_role", visualRoleConfigured);
        summary.put("workflow_declared", user.policy() != null && user.policy().workflowDeclared());
        summary.put("failover_mode", user.policy() == null ? "confirm" : user.policy().failoverMode());
        if (!authorizedFailover.isEmpty()) summary.put("authorized_failover", authorizedFailover);
        summary.put("workflow", workflow.toList());
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

        if (carried.carriesRejection()) {
            summary.put("continued_from", carried.fromRunId());
            summary.put("carries_human_rejection", true);
        }
        Map<String, Map<String, Object>> reusable = carried.reuseJudgements()
                ? reusableJudgements(root, carried.fromRunId(), contractHash,
                        git.sourceFingerprint(diffBaseCommit), summary)
                : Map.of();
        header(task, runId, workflow, dryRun);
        if (carried.carriesRejection()) {
            progress.line("note  starting from the rejection recorded on " + carried.fromRunId()
                    + "; the implementer is given its reason verbatim");
            progress.blank();
        }
        Engine engine = new Engine(loaded, user, roles, gates, ledger, runId, dryRun,
                configSnapshot, diffBaseCommit, steps, summary, budget, task, workflow, reviewByRisk,
                progress, carried, reusable);
        try {
            engine.run();
        } catch (StopException stopped) {
            return stop(ledger, summary, stopped.reason(), steps, engine.attempt(), budget);
        } catch (Budget.ExceededException exceeded) {
            summary.put("budget_stop", exceeded.getMessage());
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
        // The person accepting this is entitled to know which of those verdicts were reached
        // in this run and which were carried over. They are about the same bytes — that is
        // what let them be carried — but "passed" and "passed, earlier, elsewhere" are
        // different sentences and the gate should not blur them.
        if (summary.get("reused_judgements") instanceof Map<?, ?> reused) {
            verdict += ". " + reused.get("roles") + " were not re-run: they passed this exact "
                    + "tree on run " + reused.get("from") + " and neither the source nor the "
                    + "contract has changed since";
        }
        if (!stale.isEmpty()) {
            verdict += ". WARNING: " + String.join(", ", stale) + " last judged an earlier tree; "
                    + "code changed after that and was not judged again";
        }
        HumanDecision decision = new ApprovalStore(root).createSuccess(runId, task.id(),
                verdict, file, git.sourceFingerprint(diffBaseCommit));
        addDecision(summary, root, decision);
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(runId)).toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        footer(runId, "ready_for_human", "human_gate", budget, summary);
        return new Outcome(true, "ready_for_human", "human_gate", file, summary);
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
        private boolean rejectionDelivered;

        /** The vendor that filled each role, so a later role can be required to differ. */
        private final Map<String, String> vendors = new LinkedHashMap<>();
        /** The pixels each harness stage produced, for the role stage that looks at them. */
        private final Map<String, VisualQaRunner.Outcome> harness = new LinkedHashMap<>();
        private final List<Map<String, Object>> skipped = new ArrayList<>();
        private int attempt;

        Engine(ConfigLoader.Loaded loaded, UserConfig user, RoleRunner roles, GateRunner gates,
               EvidenceLedger ledger, String runId, boolean dryRun,
               Map<String, String> contractSnapshot,
               String diffBaseCommit, List<Map<String, Object>> steps, Map<String, Object> summary,
               Budget budget, TaskSpec.ResolvedTask task, Workflow workflow, boolean reviewByRisk,
               Progress progress, Continuation carried,
               Map<String, Map<String, Object>> reusable) {
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
            if (outcome == null) return;

            while (failed(outcome) && "fix".equals(stage.onFail()) && fixable(outcome)
                    && attempt < task.maxFixAttempts()) {
                fixRound(stage, index, failureContext(stage, outcome));
                outcome = execute(stage);
            }
            if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
            if (stage.kind() != Workflow.Kind.ROLE || stage.onFindings() == null) return;

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
            }
            if (blocking > 0) throw new StopException(stage.findingsReason());
        }

        /**
         * One fix round: hand the exact failure back, then re-establish every earlier stage
         * that asked to be rechecked. Without the recheck a fix could satisfy the stage that
         * complained by breaking one that had already passed.
         */
        private void fixRound(Workflow.Stage stage, int index, String context) throws Exception {
            attempt++;
            progress.line("      -> fix round " + attempt + " of " + task.maxFixAttempts()
                    + ": " + stage.name() + " sends the work back to " + stage.fixWith());
            Path file = writeContext(ledger, attempt, stage.contextKind(), context);
            String role = stage.fixWith();
            RoleRunner.Outcome fix = roles.run(loaded, user, role,
                    stepRunId(runId, role, attempt), null, file, false);
            record(steps, budget, role, attempt, fix);
            vendors.putIfAbsent(role, fix.vendor());
            if (!fix.ok()) throw new StopException(reasonFor(fix, "fix_attempt_failed"));
            requireUnchangedContract();
            for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
                if (skipReason(earlier) != null) continue;
                Object rechecked = execute(earlier);
                if (!failed(rechecked)) continue;
                throw new StopException(earlier.kind() == Workflow.Kind.MACHINE_GATES
                        ? "gates_not_satisfied_after_fix" : terminalReason(earlier, rechecked));
            }
        }

        private Object execute(Workflow.Stage stage) throws Exception {
            announce(stage);
            long started = System.nanoTime();
            Object outcome = dispatch(stage);
            narrate(outcome, (System.nanoTime() - started) / 1_000_000L);
            return outcome;
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
            String role = stage.role();
            Map<String, Object> standing = attempt == 0 ? reusable.remove(role) : null;
            if (standing != null) {
                // Not a shortcut and not a cache: the fingerprint check that let this entry
                // through says these are the same bytes the earlier vendor read. A fix round
                // makes it stale immediately, which is why this only applies at attempt 0.
                Map<String, Object> entry = new LinkedHashMap<>(standing);
                entry.put("reused_from", carried.fromRunId());
                entry.put("attempt", (long) attempt);
                steps.add(entry);
                vendors.putIfAbsent(role, String.valueOf(standing.get("vendor")));
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
                outcome = roles.run(loaded, user, role, stepRunId(runId, role, attempt),
                        avoid, rejectionContextFor(role), dryRun);
                record(steps, budget, role, attempt, outcome);
            }
            if (dryRun || outcome == null) return outcome;
            vendors.putIfAbsent(role, outcome.vendor());
            // A read-only role that edited the contract has already invalidated the very
            // thing it is about to be believed for.
            requireUnchangedContract();
            return outcome;
        }

        private void requireUnchangedContract() {
            if (!contractMatches(loaded, contractSnapshot)) throw new StopException("contract_mutated");
        }

        /** Why this stage is not running at all, or null when it is. */
        private String skipReason(Workflow.Stage stage) {
            if (stage.kind() == Workflow.Kind.ROLE) {
                boolean configured = user.policy() != null
                        && user.policy().roles().containsKey(stage.role());
                if (!configured) return "role_not_configured";
            }
            for (String condition : stage.when()) {
                if (!holds(condition)) return "condition_not_met:" + condition;
            }
            return null;
        }

        private boolean holds(String condition) {
            return switch (condition) {
                case "review_required" -> reviewByRisk;
                case "visual_qa_required" -> task.visualQa().required();
                case "risk_low" -> "low".equals(task.risk());
                case "risk_medium" -> "medium".equals(task.risk());
                case "risk_high" -> "high".equals(task.risk());
                default -> true;
            };
        }

        private long recordFindings(Workflow.Stage stage, RoleRunner.Outcome outcome) throws Exception {
            long blocking = blockingFindings(loaded.root(), outcome);
            summary.put(findingsKey(stage), blocking);
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
         * evict another project's dev server, resolve a base ref, or revert a file outside
         * the blast radius it was given — and asking one to try spends a role run on the
         * operator's configuration and then fails the same way.
         */
        private boolean fixable(Object outcome) {
            if (outcome instanceof VisualQaRunner.Outcome visual) {
                return "visual_qa_failed".equals(visual.code());
            }
            if (outcome instanceof GateRunner.Outcome gate) {
                return !OPERATOR_MUST_RESOLVE.contains(gate.code());
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
        static final class ExceededException extends RuntimeException {
            ExceededException(String message) { super(message); }
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

        void requireRoleRun() {
            if (maxRuns > 0 && runs >= maxRuns) {
                throw new ExceededException("max_role_runs of " + maxRuns + " reached");
            }
            if (maxCost > 0 && spent >= maxCost) {
                throw new ExceededException("max_cost_usd of " + maxCost + " reached, spent " + spent);
            }
            runs++;
        }

        int unpriced() { return unpriced; }

        void account(Object cost) {
            if (cost instanceof Number number) spent += number.doubleValue();
            else unpriced++;
        }
    }

    /**
     * Role verdicts from an earlier run that are still true of this tree.
     *
     * A verdict is about a tree, not about a run. If the source fingerprint and the whole
     * `.warden` contract are byte-for-byte what they were when that role passed, then paying
     * a second vendor to reach the same conclusion about the same bytes buys nothing.
     *
     * Every condition here is a way to say no, and any doubt at all returns an empty map and
     * runs the full chain:
     *
     *  - the earlier run must have stopped for something that was never about the work;
     *  - the contract must be identical, or the role was judged against different terms;
     *  - the source must be identical, or it judged a different candidate;
     *  - the role's own last attempt must have passed and filed no blocking findings, because
     *    a pass with a P1 is not a pass, it is a fix round that had not happened yet.
     */
    private Map<String, Map<String, Object>> reusableJudgements(
            Path root, String priorRunId, String contractHash, String fingerprint,
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
            return declineReuse(summary, "the contract changed since " + priorRunId
                    + ", so its roles were judged against different terms");
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
        Map<String, Map<String, Object>> reusable = new LinkedHashMap<>();
        Object steps = prior.get("steps");
        if (steps instanceof List<?> rows) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String label = String.valueOf(row.get("step"));
                if (row.get("profile") == null) continue;
                if (!Boolean.TRUE.equals(row.get("ok"))) { reusable.remove(label); continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) row;
                reusable.put(label, entry);
            }
        }
        for (String role : List.copyOf(reusable.keySet())) {
            Object blocking = prior.get("reviewer".equals(role) ? "blocking_findings"
                    : "visual_qa".equals(role) ? "visual_blocking_findings"
                    : role + "_blocking_findings");
            if (blocking instanceof Number number && number.longValue() > 0) reusable.remove(role);
        }
        if (!reusable.isEmpty()) {
            summary.put("reused_judgements", Map.of("from", priorRunId,
                    "roles", List.copyOf(reusable.keySet())));
        }
        return reusable;
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
    private void header(TaskSpec.ResolvedTask task, String runId, Workflow workflow, boolean dryRun) {
        progress.blank();
        progress.line("run   " + runId + (dryRun ? "   (dry run: nothing is dispatched)" : ""));
        progress.line("task  " + task.id() + "   risk=" + task.risk()
                + "   scope=" + String.join(", ", task.scopePaths()));
        List<String> names = new ArrayList<>();
        for (Workflow.Stage stage : workflow.stages()) names.add(stage.name());
        progress.line("plan  " + String.join(" -> ", names));
        progress.line("bound " + task.budget().maxRoleRuns() + " vendor call(s), "
                + Progress.money(task.budget().maxCostUsd()) + " ceiling, fix rounds <= "
                + task.maxFixAttempts());
        progress.blank();
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
        progress.line("      warden report " + runId + " --text");
        Object options = summary.get("decision_options");
        if (options instanceof List<?> list && !list.isEmpty()) {
            progress.line("      warden approve " + runId + " --decision <"
                    + list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"))
                    + ">");
        }
        progress.blank();
    }

    private void record(List<Map<String, Object>> steps, Budget budget, String role, int attempt,
                        RoleRunner.Outcome step) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", role);
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
