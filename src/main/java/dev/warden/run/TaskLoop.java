package dev.warden.run;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
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
 * The bounded loop:
 *
 *   implement → machine gates → [fix ≤ N] → independent review → [fix ≤ N]
 *             → browser harness → [fix ≤ N] → visual QA role → [fix ≤ N] → stop for a human
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

    public TaskLoop(ProcessRunner processes) {
        this(processes, (loaded, runId) -> new VisualQaRunner(processes).run(loaded, runId));
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck) {
        this.processes = processes;
        this.visualCheck = visualCheck;
    }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun)
            throws Exception {
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
        String contractHash = GitRepository.sha256(loaded.projectFile(), loaded.taskFile());
        Budget budget = new Budget(task.budget().maxRoleRuns(), task.budget().maxCostUsd());
        // One runner for the whole loop: a vendor that ran out at implement time must not be
        // dispatched again at review time. The gate makes the budget count vendor calls, not
        // role invocations, so a failover cannot spend more than the task allowed.
        RoleRunner roles = new RoleRunner(processes, budget::requireRoleRun, diffBaseCommit, runId);
        GateRunner gates = new GateRunner(processes);

        boolean reviewRequired = user.policy() != null
                && user.policy().reviewRequired(task.risk())
                && user.policy().roles().containsKey("reviewer");
        boolean implementConfigured = user.policy() != null
                && user.policy().roles().containsKey("implementer");
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
        summary.put("steps", steps);

        int attempt = 0;
        String implementerVendor = null;
        Path contextFile = null;

        try {
            if (implementConfigured) {
                RoleRunner.Outcome step = roles.run(loaded, user, "implementer",
                        stepRunId(runId, "implementer", attempt), null, contextFile, dryRun);
                record(steps, budget, "implementer", attempt, step);
                implementerVendor = step.vendor();
                if (!dryRun && !step.ok()) return stop(ledger, summary, reasonFor(step, "implementer_failed"), steps, attempt, budget);
            }

            GateRunner.Outcome gate = runGates(gates, loaded, runId, attempt, dryRun, steps,
                    contractHash, diffBaseCommit);
            while (!dryRun && !gate.ok() && attempt < task.maxFixAttempts()) {
                attempt++;
                contextFile = writeContext(ledger, attempt, "gates", gateContext(gate));
                RoleRunner.Outcome fix = roles.run(loaded, user, "implementer",
                        stepRunId(runId, "implementer", attempt), null, contextFile, false);
                record(steps, budget, "implementer", attempt, fix);
                if (!fix.ok()) return stop(ledger, summary, reasonFor(fix, "fix_attempt_failed"), steps, attempt, budget);
                gate = runGates(gates, loaded, runId, attempt, false, steps,
                        contractHash, diffBaseCommit);
            }
            if (!dryRun && !gate.ok()) return stop(ledger, summary, "gates_not_satisfied", steps, attempt, budget);

            if (reviewRequired) {
                RoleRunner.Outcome review = roles.run(loaded, user, "reviewer",
                        stepRunId(runId, "reviewer", attempt), implementerVendor, null, dryRun);
                record(steps, budget, "reviewer", attempt, review);
                if (!dryRun) {
                    if (!contractMatches(loaded, contractHash)) {
                        return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
                    }
                    if (!review.ok()) return stop(ledger, summary, reasonFor(review, "reviewer_failed"), steps, attempt, budget);
                    long blocking = blockingFindings(root, review);
                    summary.put("blocking_findings", blocking);
                    while (blocking > 0 && attempt < task.maxFixAttempts()) {
                        attempt++;
                        contextFile = writeContext(ledger, attempt, "review", reviewContext(root, review));
                        RoleRunner.Outcome fix = roles.run(loaded, user, "implementer",
                                stepRunId(runId, "implementer", attempt), null, contextFile, false);
                        record(steps, budget, "implementer", attempt, fix);
                        if (!fix.ok()) return stop(ledger, summary, reasonFor(fix, "fix_attempt_failed"), steps, attempt, budget);
                        gate = runGates(gates, loaded, runId, attempt, false, steps,
                                contractHash, diffBaseCommit);
                        if (!gate.ok()) return stop(ledger, summary, "gates_not_satisfied_after_fix", steps, attempt, budget);
                        review = roles.run(loaded, user, "reviewer",
                                stepRunId(runId, "reviewer", attempt), implementerVendor, null, false);
                        record(steps, budget, "reviewer", attempt, review);
                        if (!contractMatches(loaded, contractHash)) {
                            return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
                        }
                        if (!review.ok()) return stop(ledger, summary, reasonFor(review, "reviewer_failed"), steps, attempt, budget);
                        blocking = blockingFindings(root, review);
                        summary.put("blocking_findings", blocking);
                    }
                    if (blocking > 0) return stop(ledger, summary, "blocking_findings_remain", steps, attempt, budget);
                }
            }

            if (task.visualQa().required()) {
                VisualQaRunner.Outcome visual = runVisual(loaded, runId, attempt, dryRun, steps);

                // A failing screenshot is a failing check, and every other failing check in
                // this loop gets handed back with its evidence. Leaving visual QA terminal
                // made the one role with eyes the only one whose finding nobody had to act on.
                // `visual_qa_unavailable` is excluded on purpose: no implementer can fix a
                // missing browser, and asking one to try burns a role run on the operator's
                // configuration.
                while (!dryRun && visual != null && "visual_qa_failed".equals(visual.code())
                        && attempt < task.maxFixAttempts()) {
                    attempt++;
                    contextFile = writeContext(ledger, attempt, "visual", visualContext(visual));
                    RoleRunner.Outcome fix = roles.run(loaded, user, "implementer",
                            stepRunId(runId, "implementer", attempt), null, contextFile, false);
                    record(steps, budget, "implementer", attempt, fix);
                    if (!fix.ok()) return stop(ledger, summary, reasonFor(fix, "fix_attempt_failed"), steps, attempt, budget);
                    gate = runGates(gates, loaded, runId, attempt, false, steps,
                            contractHash, diffBaseCommit);
                    if (!gate.ok()) return stop(ledger, summary, "gates_not_satisfied_after_fix", steps, attempt, budget);
                    visual = runVisual(loaded, runId, attempt, false, steps);
                }
                if (!dryRun && visual != null && !visual.ok()) {
                    return stop(ledger, summary, visual.code(), steps, attempt, budget);
                }

                // Only now, on a page the machine has already accepted, is it worth paying a
                // model to look at it. It answers what the harness cannot measure: whether the
                // result reads correctly to a person.
                if (visualRoleConfigured) {
                    RoleRunner.Outcome seen = runVisualRole(loaded, user, roles, runId, attempt,
                            implementerVendor, visual, dryRun, steps, budget);
                    if (!dryRun && seen != null) {
                        if (!contractMatches(loaded, contractHash)) {
                            return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
                        }
                        if (!seen.ok()) return stop(ledger, summary, reasonFor(seen, "visual_qa_role_failed"), steps, attempt, budget);
                        long blocking = blockingFindings(root, seen);
                        summary.put("visual_blocking_findings", blocking);
                        while (blocking > 0 && attempt < task.maxFixAttempts()) {
                            attempt++;
                            contextFile = writeContext(ledger, attempt, "visual-review",
                                    reviewContext(root, seen));
                            RoleRunner.Outcome fix = roles.run(loaded, user, "implementer",
                                    stepRunId(runId, "implementer", attempt), null, contextFile, false);
                            record(steps, budget, "implementer", attempt, fix);
                            if (!fix.ok()) return stop(ledger, summary, reasonFor(fix, "fix_attempt_failed"), steps, attempt, budget);
                            gate = runGates(gates, loaded, runId, attempt, false, steps,
                                    contractHash, diffBaseCommit);
                            if (!gate.ok()) return stop(ledger, summary, "gates_not_satisfied_after_fix", steps, attempt, budget);
                            visual = runVisual(loaded, runId, attempt, false, steps);
                            if (visual != null && !visual.ok()) {
                                return stop(ledger, summary, visual.code(), steps, attempt, budget);
                            }
                            seen = runVisualRole(loaded, user, roles, runId, attempt, implementerVendor,
                                    visual, false, steps, budget);
                            if (!contractMatches(loaded, contractHash)) {
                                return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
                            }
                            if (seen == null || !seen.ok()) {
                                return stop(ledger, summary,
                                        seen == null ? "visual_qa_role_failed" : reasonFor(seen, "visual_qa_role_failed"),
                                        steps, attempt, budget);
                            }
                            blocking = blockingFindings(root, seen);
                            summary.put("visual_blocking_findings", blocking);
                        }
                        if (blocking > 0) {
                            return stop(ledger, summary, "visual_findings_remain", steps, attempt, budget);
                        }
                    }
                }
            }
        } catch (Budget.ExceededException exceeded) {
            summary.put("budget_stop", exceeded.getMessage());
            return stop(ledger, summary, "budget_exhausted", steps, attempt, budget);
        } catch (Exception unexpected) {
            summary.put("unexpected_error", Map.of(
                    "type", unexpected.getClass().getName(),
                    "message", String.valueOf(unexpected.getMessage())));
            return stop(ledger, summary, "unexpected_error", steps, attempt, budget);
        }

        if (!dryRun && !contractMatches(loaded, contractHash)) {
            return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
        }

        summary.put("attempts_used", (long) attempt);
        summary.put("total_cost_usd", budget.spent());
        summary.put("role_runs", (long) budget.runs());
        summary.put("ok", true);
        if (dryRun) {
            // A preview has not produced a candidate a human can accept. Persisting a real
            // pending decision here makes status noisy and, worse, makes a dry run look like
            // an authorization boundary was actually reached.
            summary.put("next_action", "none");
            Path file = ledger.writeReport("task-run", summary);
            ledger.append("task_dry_run", summary);
            return new Outcome(true, "dry_run", "none", file, summary);
        }

        summary.put("next_action", "human_gate");
        Path file = ledger.writeReport("task-run", summary);
        HumanDecision decision = new ApprovalStore(root).createSuccess(runId, task.id(),
                "all configured machine, review and visual gates passed", file,
                git.fingerprint(diffBaseCommit));
        addDecision(summary, root, decision);
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(runId)).toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        return new Outcome(true, "ready_for_human", "human_gate", file, summary);
    }

    private static final class Budget {
        static final class ExceededException extends RuntimeException {
            ExceededException(String message) { super(message); }
        }

        private final long maxRuns;
        private final double maxCost;
        private int runs;
        private double spent;

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

        void account(Object cost) {
            if (cost instanceof Number number) spent += number.doubleValue();
        }
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
        return "role_quota_exhausted".equals(step.code()) ? "quota_exhausted" : genericReason;
    }

    private GateRunner.Outcome runGates(GateRunner gates, ConfigLoader.Loaded loaded, String runId,
                                        int attempt, boolean dryRun, List<Map<String, Object>> steps,
                                        String contractHash, String diffBaseCommit)
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
                contractHash, diffBaseCommit);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", "gates");
        entry.put("attempt", (long) attempt);
        entry.put("ok", outcome.ok());
        entry.put("code", outcome.code());
        entry.put("report", String.valueOf(outcome.report()));
        steps.add(entry);
        return outcome;
    }

    private static boolean contractMatches(ConfigLoader.Loaded loaded, String expected) {
        try {
            return expected.equals(GitRepository.sha256(loaded.projectFile(), loaded.taskFile()));
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
        summary.put("steps", steps);
        Path file = ledger.writeReport("task-run", summary);
        Path root = ledger.projectRoot();
        Object base = summary.get("diff_base_commit");
        String fingerprint = base instanceof String commit
                ? new GitRepository(root, processes).fingerprint(commit) : null;
        HumanDecision decision = new ApprovalStore(root).createFailure(
                String.valueOf(summary.get("run_id")), String.valueOf(summary.get("task_id")),
                reason, file, fingerprint);
        addDecision(summary, root, decision);
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", decision.runId(), "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(decision.runId()))
                        .toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        return new Outcome(false, reason, "human_escalation", file, summary);
    }

    private static void addDecision(Map<String, Object> summary, Path root, HumanDecision decision)
            throws Exception {
        ApprovalStore store = new ApprovalStore(root);
        summary.put("decision_path", root.relativize(store.decisionPath(decision.runId()))
                .toString().replace('\\', '/'));
        summary.put("decision_state", decision.state().jsonValue());
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
