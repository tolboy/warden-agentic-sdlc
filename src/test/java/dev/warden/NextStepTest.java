package dev.warden;

import dev.warden.ledger.RunReport;
import dev.warden.run.NextStep;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stop becomes one concrete operator action: kind, sentence, commands with real ids,
 * edits, consequences, and the findings that caused it.
 */
public final class NextStepTest implements Suite {

    @Override public String name() { return "next-step"; }

    @Override public void run(Check check) throws Exception {
        everyKindFromHandBuiltSummary(check);
        contractGapNamesTheAcceptance(check);
        contractGapReadsAcceptanceFromTheTaskFile(check);
        repairOrRetryForAProductDefect(check);
        blockersThatAreNotTheContract(check);
        reportRendersTheBlockAndFallsBack(check);
    }

    private void everyKindFromHandBuiltSummary(Check check) {
        check.eq("ready_for_human", "accept_or_reject",
                kind("ready_for_human", summary("run-1", "hello")));
        check.eq("call ceiling", "raise_call_budget",
                kind("budget_exhausted", summary("run-1", "hello")));
        Map<String, Object> money = summary("run-1", "hello");
        money.put("budget_limit_hit", "max_cost_usd");
        check.eq("money ceiling", "raise_cost_budget",
                kind("budget_exhausted", money));
        check.eq("insufficient is the same two ceilings", "raise_call_budget",
                kind("budget_insufficient_to_finish", summary("run-1", "hello")));
        Map<String, Object> late = summary("run-1", "hello");
        late.put("budget_limit_hit", "max_elapsed_minutes");
        late.put("chain", Map.of("elapsed_seconds", 125L, "role_runs", 3L));
        check.eq("the execution deadline is its own ceiling", "raise_time_budget",
                kind("budget_exhausted", late));
        Map<String, Object> lateStep = NextStep.of("budget_exhausted", late, null);
        check.contains("and names the key and the minutes the chain used",
                String.valueOf(lateStep.get("edits")), "max_elapsed_minutes above 3");
        check.contains("then continues the same chain", String.valueOf(lateStep.get("commands")),
                "warden run hello --continue run-1");
        Map<String, Object> capped = summary("run-1", "hello");
        capped.put("role_runs", 1L);
        capped.put("chain", Map.of("role_runs", 3L));
        capped.put("budget_reserve", Map.of("calls_needed_to_repair_and_finish", 2L));
        check.contains("a call floor counts the chain's calls, not the run's",
                String.valueOf(NextStep.of("budget_insufficient_to_finish", capped, null).get("edits")),
                "at least 5");
        check.contains("a timeout names the profile's wall clock, not the gate timeout",
                String.valueOf(NextStep.of("role_timed_out", summary("run-1", "hello"), null)
                        .get("edits")), "limits.wall_clock_minutes");
        check.eq("quota", "wait_or_add_vendor",
                kind("quota_exhausted", summary("run-1", "hello")));
        check.eq("rate limit", "wait_or_add_vendor",
                kind("rate_limited", summary("run-1", "hello")));
        check.eq("failover", "confirm_failover",
                kind("failover_requires_confirmation", summary("run-1", "hello")));
        for (String reason : List.of("role_timed_out", "vendor_call_failed",
                "vendor_protocol_failed", "prompt_undeliverable", "turn_ceiling_reached")) {
            check.eq(reason, "retry_infrastructure", kind(reason, summary("run-1", "hello")));
        }
        check.eq("reproduction passed", "fix_contract",
                kind("reproduction_passed_before_change", summary("run-1", "hello")));
        check.eq("reproduction inconclusive", "fix_contract",
                kind("reproduction_inconclusive", summary("run-1", "hello")));
        check.eq("baseline", "fix_baseline", kind("baseline_failed", summary("run-1", "hello")));
        check.eq("scope", "fix_scope", kind("preflight_outside_scope", summary("run-1", "hello")));
        check.eq("mutated contract", "restore_contract",
                kind("contract_mutated", summary("run-1", "hello")));
        check.eq("ledger", "restore_ledger", kind("ledger_unavailable", summary("run-1", "hello")));
        check.eq("anything else", "read_report",
                kind("repair_made_no_progress", summary("run-1", "hello")));

        Map<String, Object> accept = NextStep.of("ready_for_human", summary("run-1", "hello"), null);
        check.contains("accept command uses the real run id",
                String.valueOf(accept.get("commands")), "warden approve run-1 --decision accept");
        Map<String, Object> calls = summary("run-1", "hello");
        calls.put("role_runs", 4L);
        calls.put("budget_reserve", Map.of("calls_needed_to_repair_and_finish", 2L));
        Map<String, Object> raise = NextStep.of("budget_exhausted", calls, null);
        check.contains("call-budget edit names the floor",
                String.valueOf(raise.get("edits")), "to at least 6");
        check.contains("and keeps the verdicts",
                String.valueOf(raise.get("consequences")), "are kept");
        Map<String, Object> failover = NextStep.of("failover_requires_confirmation",
                summary("run-1", "hello"), null);
        check.contains("failover continues the same run",
                String.valueOf(failover.get("commands")),
                "warden run hello --continue run-1");
    }

    private void contractGapNamesTheAcceptance(Check check) {
        Map<String, Object> summary = summary("gap-review-2", "hello");
        summary.put("reason", "blocking_findings_remain");
        summary.put("acceptance_sha256", "abc123sha");
        summary.put("acceptance_commands", List.of("echo water visible"));
        summary.put("nonactionable_blocking_ids", List.of("acceptance-too-weak"));
        summary.put("decision_options", List.of("retry", "abort"));
        summary.put("finding_history", List.of(Map.of(
                "stage", "review",
                "findings", List.of(finding("acceptance-too-weak", "contract_gap",
                        "require an exact match, not a substring")))));

        Map<String, Object> step = NextStep.of("blocking_findings_remain", summary, null);
        check.eq("a lone contract_gap P1 is fix_contract", "fix_contract", step.get("kind"));
        String blob = String.valueOf(step);
        check.contains("names the task file", blob, ".warden/tasks/hello.yaml");
        check.contains("names the current acceptance", blob, "echo water visible");
        check.contains("names the reviewer's suggestion", blob, "require an exact match, not a substring");
        check.contains("names the current acceptance_sha256", blob, "abc123sha");
        check.contains("aborts this run", String.valueOf(step.get("commands")),
                "warden approve gap-review-2 --decision abort");
        check.contains("validates the task", String.valueOf(step.get("commands")),
                "warden validate hello");
        @SuppressWarnings("unchecked")
        List<String> commandList = (List<String>) step.get("commands");
        check.that("previews under a generated id", commandList.contains("warden run hello --dry-run"));
        check.that("so the preview never reserves the id the live run needs",
                commandList.stream().noneMatch(command -> command.contains("--dry-run")
                        && command.contains("gap-review-2-next")));
        check.contains("starts a new run, not a continue", String.valueOf(step.get("commands")),
                "warden run hello --run-id gap-review-2-next");
        check.that("does not offer --continue, which would expect reuse",
                !String.valueOf(step.get("commands")).contains("--continue"));
        check.contains("the consequence is that verdicts cannot be reused",
                String.valueOf(step.get("consequences")),
                "no verdict of this run can be reused");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) step.get("findings");
        check.eq("the open P1 is listed", "acceptance-too-weak", findings.get(0).get("id"));
        check.eq("with its suggestion", "require an exact match, not a substring",
                findings.get(0).get("suggestion"));
    }

    private void contractGapReadsAcceptanceFromTheTaskFile(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-next-step-task-");
        try {
            Files.createDirectories(root.resolve(".warden/tasks"));
            Files.writeString(root.resolve(".warden/project.yaml"), """
                    version: 1
                    project: fixture
                    checks:
                      fast: ["echo ok"]
                    scopes:
                      src: ["src"]
                    defaults:
                      checks: fast
                      risk: medium
                    """);
            Files.writeString(root.resolve(".warden/tasks/hello.yaml"), """
                    version: 1
                    id: hello
                    goal: water should not be visible
                    scope: src
                    acceptance: ["echo water visible"]
                    """);
            Map<String, Object> summary = summary("gap-review-2", "hello");
            summary.put("acceptance_sha256", "file-hash");
            summary.put("nonactionable_blocking_ids", List.of("acceptance-too-weak"));
            summary.put("finding_history", List.of(Map.of(
                    "stage", "review",
                    "findings", List.of(finding("acceptance-too-weak", "contract_gap",
                            "fail when water is visible")))));
            Map<String, Object> step = NextStep.of("blocking_findings_remain", summary, root);
            check.contains("acceptance is read through the existing loader",
                    String.valueOf(step), "echo water visible");
        } finally {
            deleteTree(root);
        }
    }

    /**
     * Only a contract gap is an acceptance edit. Found by the review of this change: an open
     * `provider_unavailable` P1 told the operator to replace `npm test` with its suggestion.
     */
    private void blockersThatAreNotTheContract(Check check) {
        Map<String, Object> summary = summary("down-1", "hello");
        summary.put("acceptance_commands", List.of("npm test"));
        summary.put("nonactionable_blocking_ids", List.of("provider-down"));
        summary.put("decision_options", List.of("retry", "abort"));
        summary.put("finding_history", List.of(Map.of(
                "stage", "review",
                "findings", List.of(finding("provider-down", "provider_unavailable",
                        "Restart the test provider")))));
        Map<String, Object> step = NextStep.of("blocking_findings_remain", summary, null);
        check.eq("an unavailable provider is not a contract gap", "resolve_blockers", step.get("kind"));
        check.that("and proposes no acceptance edit",
                !String.valueOf(step.get("edits")).contains("npm test")
                        && !String.valueOf(step).contains("replace current acceptance"));
        check.contains("it names the category that blocks", String.valueOf(step.get("summary")),
                "provider_unavailable");
        check.contains("still lists the finding with its suggestion",
                String.valueOf(step.get("findings")), "Restart the test provider");
        check.contains("and closes the run before a new one",
                String.valueOf(step.get("commands")), "warden approve down-1 --decision abort");

        Map<String, Object> mixed = summary("mixed-1", "hello");
        mixed.put("acceptance_commands", List.of("npm test"));
        mixed.put("finding_history", List.of(Map.of(
                "stage", "review",
                "findings", List.of(
                        finding("provider-down", "provider_unavailable", "Restart the test provider"),
                        finding("weak-check", "contract_gap", "assert the exact refusal text")))));
        Map<String, Object> both = NextStep.of("blocking_findings_remain", mixed, null);
        check.eq("a contract gap beside other blockers is still fix_contract", "fix_contract",
                both.get("kind"));
        check.contains("and the acceptance edit is the contract gap's suggestion",
                String.valueOf(both.get("edits")), "assert the exact refusal text");
        check.that("never another category's",
                !String.valueOf(both.get("edits")).contains("Restart the test provider"));
    }

    private void repairOrRetryForAProductDefect(Check check) {
        Map<String, Object> summary = summary("run-fix", "hello");
        summary.put("finding_history", List.of(Map.of(
                "stage", "review",
                "findings", List.of(finding("still-broken", "product_defect",
                        "return the converted value")))));
        Map<String, Object> step = NextStep.of("blocking_findings_remain", summary, null);
        check.eq("an open product_defect P1 is repair_or_retry", "repair_or_retry", step.get("kind"));
        check.contains("points at the report", String.valueOf(step.get("commands")),
                "warden report run-fix --text");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) step.get("findings");
        check.eq("the product_defect is listed", "still-broken", findings.get(0).get("id"));
        check.eq("as a product_defect", "product_defect", findings.get(0).get("category"));
    }

    private void reportRendersTheBlockAndFallsBack(Check check) {
        Map<String, Object> summary = summary("gap-review-2", "hello");
        summary.put("acceptance_sha256", "abc123sha");
        summary.put("acceptance_commands", List.of("echo water visible"));
        summary.put("nonactionable_blocking_ids", List.of("acceptance-too-weak"));
        summary.put("finding_history", List.of(Map.of(
                "stage", "review",
                "findings", List.of(finding("acceptance-too-weak", "contract_gap",
                        "require an exact match, not a substring")))));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", "gap-review-2");
        report.put("task_id", "hello");
        report.put("ok", false);
        report.put("reason", "blocking_findings_remain");
        report.put("next_action", "human_escalation");
        report.put("next_step", NextStep.of("blocking_findings_remain", summary, null));
        String rendered = RunReport.render(report);
        check.contains("the block names the kind", rendered, "next     fix_contract");
        check.contains("and numbers the abort command", rendered,
                "warden approve gap-review-2 --decision abort");
        check.contains("and the suggestion", rendered, "require an exact match, not a substring");
        check.contains("and the current hash", rendered, "abc123sha");

        Map<String, Object> old = new LinkedHashMap<>();
        old.put("run_id", "old-1");
        old.put("task_id", "hello");
        old.put("ok", false);
        old.put("reason", "budget_exhausted");
        old.put("next_action", "human_escalation");
        old.put("safe_next_step", "warden approve old-1 --decision retry --note \"<why>\"");
        String fallback = RunReport.render(old);
        check.contains("an old summary still prints the one-line next step", fallback,
                "do next  warden approve old-1 --decision retry");
        check.that("and does not invent a structured block",
                !fallback.contains("next     "));
    }

    private static String kind(String reason, Map<String, Object> summary) {
        return String.valueOf(NextStep.of(reason, summary, null).get("kind"));
    }

    private static Map<String, Object> summary(String runId, String taskId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", taskId);
        return summary;
    }

    private static Map<String, Object> finding(String id, String category, String suggestion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("severity", "P1");
        row.put("category", category);
        row.put("path", ".warden/tasks/hello.yaml");
        row.put("line", 12L);
        row.put("message", "the acceptance command matches a substring");
        row.put("suggestion", suggestion);
        row.put("status", "open");
        return row;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
