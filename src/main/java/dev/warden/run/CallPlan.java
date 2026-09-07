package dev.warden.run;

import dev.warden.config.Workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How many vendor calls the declared chain still owes before a person can be handed a
 * finished candidate.
 *
 * The budget used to be a ceiling and nothing else: spend until the counter refuses, then
 * report `budget_exhausted`. That is fine when the ceiling is generous and wrong in the one
 * case that costs real money. Measured on a live run with `max_role_runs: 6` — an implementer,
 * two repair rounds and three reviews used all six, and the two stages that would have
 * finished the workflow were never reached. Every call was paid for, the candidate was good,
 * and the run still could not say so, because the last of the budget went into a repair after
 * which nothing could be concluded.
 *
 * What was missing is arithmetic that was always available. The chain is declared before the
 * first dispatch, the conditions that skip a stage are known before the first dispatch, and
 * only {@link Workflow.Kind#ROLE} stages cost a vendor call — machine gates and the browser
 * harness cost time, not money. So the loop can say, before it spends anything, how many calls
 * a clean pass needs, and what each repair branch would cost from there.
 *
 * How much of that a repair must be able to pay for is a policy choice and not this class's:
 * `budget.repair_reserve` is `full` by default, meaning the repair and everything still owed,
 * and `partial` reserves the repair plus whatever will read its result. Both numbers are
 * computed and both are published, so a reader can see which one was binding.
 *
 * Three properties are deliberate.
 *
 * 1. It never raises a limit. A plan that does not fit is reported, narrated and stopped on —
 *    the ceiling is the operator's number, and an orchestrator that quietly widened it would
 *    make every budget advisory.
 * 2. It counts calls, not dollars. What a call costs is whatever its vendor chose to report,
 *    and some report nothing at all; a money reserve computed from an unknown price would be
 *    a guess wearing a number. The cost ceiling stays a ceiling and says so.
 * 3. It decides nothing. Every method answers an arithmetic question; which answer binds is
 *    settled in the loop against the declared policy.
 */
public final class CallPlan {

    private final List<Workflow.Stage> stages;
    private final Workflow workflow;
    private final Predicate<Workflow.Stage> skipped;

    /**
     * @param skipped whether a stage will not run at all — the same predicate the loop uses,
     *                so the plan and the execution cannot disagree about which stages count
     */
    public CallPlan(Workflow workflow, Predicate<Workflow.Stage> skipped) {
        this.workflow = workflow;
        this.stages = workflow.stages();
        this.skipped = skipped;
    }

    /** Vendor calls a run that never repairs anything needs, from the top of the chain. */
    public int minimumToFinish() {
        return callsFrom(0);
    }

    /** Vendor calls still owed by the stages strictly after {@code index}. */
    public int callsAfter(int index) {
        return callsFrom(index + 1);
    }

    /**
     * What one repair round at {@code index} costs before the chain can carry on: the fix
     * dispatch itself, every role stage the fix invalidates and has to re-establish, and
     * re-running the stage that objected when that stage is a role.
     */
    public int repairCost(int index) {
        int cost = 1;
        for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
            if (earlier.kind() == Workflow.Kind.ROLE && !skipped.test(earlier)) cost++;
        }
        if (index < stages.size() && stages.get(index).kind() == Workflow.Kind.ROLE) cost++;
        return cost;
    }

    /**
     * Everything a repair at {@code index} commits the run to: the repair, and finishing.
     *
     * The floor under `repair_reserve: full`, which is the default. An allowance that covers
     * a fix but not the stages after it buys progress the run cannot conclude, and whether
     * that is worth paying for is the operator's judgement rather than this class's.
     */
    public int reserveForRepair(int index) {
        return repairCost(index) + callsAfter(index);
    }

    /**
     * The repair, plus reaching whichever stage will actually read what it produces.
     *
     * The floor under `repair_reserve: partial`, and the reason that mode is not simply
     * {@link #repairCost}. A machine gate failing before the first review costs exactly one
     * call to repair: the gate re-runs for free and no judgement has been given yet to
     * re-establish. Reserving one call there would let a run pay an implementer and stop
     * with a changed tree that nothing ever looked at, which is the outcome the reserve
     * exists to prevent — so the next judging stage is counted in even though it sits past
     * the stage that objected.
     *
     * A chain that declares no judging stage at all gets {@link #repairCost} back, because
     * there is then nothing to wait for.
     */
    public int repairAndJudgeCost(int index) {
        int cost = repairCost(index);
        if (repairIncludesAJudge(index)) return cost;
        for (int position = index + 1; position < stages.size(); position++) {
            Workflow.Stage stage = stages.get(position);
            if (skipped.test(stage)) continue;
            if (stage.kind() == Workflow.Kind.ROLE) cost++;
            if (judges(stage)) return cost;
        }
        return cost;
    }

    /** A role stage that can object to what it reads, rather than only succeed or fail. */
    private boolean judges(Workflow.Stage stage) {
        return stage.kind() == Workflow.Kind.ROLE && stage.onFindings() != null;
    }

    /** Whether the repair itself already pays for something that will judge the result. */
    private boolean repairIncludesAJudge(int index) {
        if (index < stages.size() && judges(stages.get(index))) return true;
        for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
            if (judges(earlier) && !skipped.test(earlier)) return true;
        }
        return false;
    }

    /** The stages that would be dispatched, named, for a preview that spends nothing. */
    public List<String> payingStages() {
        List<String> names = new ArrayList<>();
        for (Workflow.Stage stage : stages) {
            if (stage.kind() == Workflow.Kind.ROLE && !skipped.test(stage)) names.add(stage.name());
        }
        return names;
    }

    /**
     * The plan as the run summary carries it.
     *
     * @param cap {@code budgets.max_role_runs} as the task declared it; 0 or less means the
     *            operator set no ceiling, and the plan says so rather than inventing one
     */
    /**
     * @param mode `full` or `partial`, from `budget.repair_reserve`. Recorded rather than
     *             applied here: the plan states the arithmetic and the loop enforces it, so
     *             a reader can see both numbers and which one is binding.
     */
    public Map<String, Object> toMap(long cap, String mode) {
        int minimum = minimumToFinish();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("requested_cap", cap);
        plan.put("minimum_success_calls", (long) minimum);
        plan.put("paying_stages", payingStages());
        plan.put("sufficient_for_success", cap <= 0 || cap >= minimum);
        plan.put("repair_reserve", mode);
        List<Map<String, Object>> recovery = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            Workflow.Stage stage = stages.get(index);
            if (skipped.test(stage)) continue;
            if (!"fix".equals(stage.onFail()) && !"fix".equals(stage.onFindings())) continue;
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("stage", stage.name());
            // The bare mechanical cost is published beside the two floors so the gap is
            // visible: a gate repair before any review costs one call and would be read by
            // nothing, which is why neither mode's floor is this number.
            branch.put("calls_needed_to_repair", (long) repairCost(index));
            branch.put("calls_to_repair_and_be_judged", (long) repairAndJudgeCost(index));
            branch.put("calls_to_repair_and_finish", (long) reserveForRepair(index));
            branch.put("calls_required_here", (long) requiredFor(index, mode));
            // The suffix is already included in the full reserve. Count only the calls
            // spent reaching this branch before adding either reserve, not the whole chain.
            int spentToReach = minimum - callsAfter(index);
            branch.put("reachable_under_cap",
                    cap <= 0 || cap >= spentToReach + reserveForRepair(index));
            branch.put("repair_allowed_under_cap",
                    cap <= 0 || cap >= spentToReach + requiredFor(index, mode));
            recovery.add(branch);
        }
        plan.put("recovery_branches", recovery);
        // Said out loud rather than left to be inferred from its absence. A reader who sees a
        // call reserve and no money reserve is entitled to know the second one was considered.
        plan.put("cost_reserve", "not computed: a vendor's price is only known after its call, "
                + "and some vendors report none, so max_cost_usd stays a ceiling and is never "
                + "reserved against");
        // Named for the same reason as the cost. A reader who finds a call reserve and nothing
        // about elapsed time should be told that time was considered and where it stopped,
        // rather than left to assume a deadline is being enforced somewhere.
        plan.put("time_reserve", "not computed: timeout_minutes bounds one gate run, and no "
                + "whole-workflow deadline is declared anywhere in the contract, so there is "
                + "no elapsed-time budget to reserve against");
        return plan;
    }

    /** The floor a repair at {@code index} has to clear under {@code mode}. */
    public int requiredFor(int index, String mode) {
        return "partial".equals(mode) ? repairAndJudgeCost(index) : reserveForRepair(index);
    }

    private int callsFrom(int index) {
        int calls = 0;
        for (int position = Math.max(0, index); position < stages.size(); position++) {
            Workflow.Stage stage = stages.get(position);
            if (stage.kind() == Workflow.Kind.ROLE && !skipped.test(stage)) calls++;
        }
        return calls;
    }
}
