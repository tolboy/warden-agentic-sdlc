package dev.warden.run;

import dev.warden.config.ConfigLoader;
import dev.warden.ledger.Findings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one concrete operator action a stop is asking for.
 *
 * {@code safe_next_step} is a sentence. After a live run stopped on a {@code contract_gap}
 * the operator still had to dig through JSON to learn that the acceptance had to change,
 * that every paid verdict would then be invalid, and that the next run had to be a new
 * one. This is the same diagnosis, structured: a kind, a sentence, the commands with the
 * real ids filled in, the edits, what is kept, and the findings that caused it.
 *
 * Pure, apart from an optional read of the task file through {@link ConfigLoader} when the
 * summary did not already record the acceptance commands. It does not write, and it does
 * not change {@code safeNextStep}.
 */
public final class NextStep {

    private NextStep() {}

    public static Map<String, Object> of(String reason, Map<String, Object> summary, Path root) {
        if (summary == null) summary = Map.of();
        String runId = text(summary.get("run_id"));
        String taskId = text(summary.get("task_id"));
        String kind = kindOf(reason, summary);
        List<String> acceptance = acceptanceCommands(summary, root, taskId);
        String taskFile = taskId.isBlank() ? ".warden/tasks/<task>.yaml"
                : ".warden/tasks/" + taskId + ".yaml";
        List<Map<String, Object>> findings = openBlockingFindings(summary);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("summary", sentence(kind, reason, summary, runId, taskId, taskFile, acceptance, findings));
        step.put("commands", commands(kind, summary, runId, taskId, findings));
        step.put("edits", edits(kind, reason, summary, taskFile, acceptance, findings));
        step.put("consequences", consequences(kind, summary));
        step.put("findings", "fix_contract".equals(kind) || "resolve_blockers".equals(kind)
                || "repair_or_retry".equals(kind) ? findings : List.of());
        return step;
    }

    /**
     * The same facts, for a terminal. Replaces the one-line {@code safe_next_step} when a
     * structured step exists; the caller falls back to the old line for older summaries.
     */
    public static void render(StringBuilder out, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return;
        out.append("next     ").append(text(map.get("kind"))).append('\n');
        String sentence = text(map.get("summary"));
        if (!sentence.isBlank()) out.append("         ").append(sentence).append('\n');
        List<Object> commands = list(map.get("commands"));
        for (int i = 0; i < commands.size(); i++) {
            out.append("  ").append(i + 1).append(". ").append(commands.get(i)).append('\n');
        }
        for (Object item : list(map.get("edits"))) {
            if (!(item instanceof Map<?, ?> edit)) continue;
            out.append("edit     ").append(edit.get("file"));
            if (edit.get("change") != null) out.append(" — ").append(edit.get("change"));
            out.append('\n');
        }
        for (Object item : list(map.get("consequences"))) {
            out.append("then     ").append(item).append('\n');
        }
        for (Object item : list(map.get("findings"))) {
            if (!(item instanceof Map<?, ?> finding)) continue;
            out.append("finding  ").append(finding.get("severity"))
                    .append(' ').append(finding.get("category"))
                    .append(' ').append(finding.get("id"));
            if (finding.get("path") != null) out.append("  ").append(finding.get("path"));
            if (finding.get("line") != null) out.append(':').append(finding.get("line"));
            out.append('\n');
            if (finding.get("message") != null) {
                out.append("         ").append(finding.get("message")).append('\n');
            }
            if (finding.get("suggestion") != null) {
                out.append("         suggestion: ").append(finding.get("suggestion")).append('\n');
            }
        }
    }

    static String kindOf(String reason, Map<String, Object> summary) {
        if (reason == null) return "read_report";
        return switch (reason) {
            case "ready_for_human" -> "accept_or_reject";
            case "budget_exhausted", "budget_insufficient_to_finish" -> switch (
                    String.valueOf(summary.get("budget_limit_hit"))) {
                case "max_cost_usd" -> "raise_cost_budget";
                case "max_elapsed_minutes" -> "raise_time_budget";
                default -> "raise_call_budget";
            };
            case "quota_exhausted", "rate_limited" -> "wait_or_add_vendor";
            case "failover_requires_confirmation" -> "confirm_failover";
            case "role_timed_out", "vendor_call_failed", "vendor_protocol_failed",
                    "prompt_undeliverable", "turn_ceiling_reached" -> "retry_infrastructure";
            case "reproduction_passed_before_change", "reproduction_inconclusive" -> "fix_contract";
            case "blocking_findings_remain", "quality_exhausted" -> blockingKind(summary);
            case "escalation_unavailable", "independent_review_unavailable" -> "wait_or_add_vendor";
            case "baseline_failed" -> "fix_baseline";
            case "preflight_outside_scope" -> "fix_scope";
            case "contract_mutated" -> "restore_contract";
            case "ledger_unavailable" -> "restore_ledger";
            default -> "read_report";
        };
    }

    /**
     * What remaining blockers ask of a person, by category.
     *
     * A product defect is the implementer's, so any one of them makes this a repair. Of the
     * rest, only a contract gap says the acceptance is wrong. An unavailable provider, spent
     * quota, missing access, tooling failure or a disagreement between readers does not, and
     * routing them to an acceptance edit told the operator to replace `npm test` with "Restart
     * the test provider" — found by the review of this change.
     */
    private static String blockingKind(Map<String, Object> summary) {
        List<Map<String, Object>> open = openBlockingFindings(summary);
        if (open.isEmpty()) {
            // An older summary names the ids but not their categories.
            return summary.get("nonactionable_blocking_ids") instanceof List<?> leftover
                    && !leftover.isEmpty() ? "resolve_blockers" : "repair_or_retry";
        }
        boolean contractGap = false;
        for (Map<String, Object> finding : open) {
            String category = categoryOf(finding);
            if ("product_defect".equals(category)) return "repair_or_retry";
            if ("contract_gap".equals(category)) contractGap = true;
        }
        return contractGap ? "fix_contract" : "resolve_blockers";
    }

    /**
     * The category a finding routes by. Matches {@link Findings.Finding#repairable()}: an
     * absent or unrecognised category is the default, {@code product_defect}.
     */
    private static String categoryOf(Map<String, Object> finding) {
        Object raw = finding.get("category");
        String category = raw == null ? "" : String.valueOf(raw);
        return category.isBlank() || !Findings.CATEGORIES.contains(category)
                ? Findings.DEFAULT_CATEGORY : category;
    }

    private static String sentence(String kind, String reason, Map<String, Object> summary,
                                   String runId, String taskId, String taskFile,
                                   List<String> acceptance, List<Map<String, Object>> findings) {
        return switch (kind) {
            case "accept_or_reject" ->
                    "Accept or reject run " + runId + "; nothing is landed either way.";
            case "raise_call_budget" -> {
                String floor = callFloor(summary);
                yield "Raise budgets.max_role_runs in " + taskFile
                        + (floor.isBlank() ? "" : " to at least " + floor)
                        + ", then retry this run so the verdicts already paid for are kept.";
            }
            case "raise_cost_budget" ->
                    "Raise budgets.max_cost_usd in " + taskFile
                            + ", then retry this run so the verdicts already paid for are kept.";
            case "raise_time_budget" ->
                    "Raise budgets.max_elapsed_minutes in " + taskFile + " above the "
                            + elapsedMinutes(summary) + " minute(s) this chain has used, then "
                            + "retry this run so the verdicts already paid for are kept.";
            case "wait_or_add_vendor" -> "rate_limited".equals(reason)
                    ? "The vendor asked to slow down; wait a few minutes, then retry run "
                            + runId + ". Its subscription is not known to be spent."
                    : "escalation_unavailable".equals(reason)
                    ? "The escalation ladder's next rung names a profile that cannot be "
                            + "dispatched now; verify or replace it in policy.yaml, then retry run "
                            + runId + " so the verdicts already reached are kept."
                    : "independent_review_unavailable".equals(reason)
                    ? "No reader on the roster differs from every vendor that wrote this "
                            + "candidate. Add a reader from another vendor, or set "
                            + "review_assurance: same_vendor_peer on the task if that weaker "
                            + "check is acceptable, then retry run " + runId + "."
                    : "Wait for the quota window named in the vendor message, or add a profile "
                            + "from another vendor, then retry run " + runId + ".";
            case "confirm_failover" ->
                    "Confirm the vendor substitution for run " + runId
                            + ", then continue it so the work already done is kept.";
            case "retry_infrastructure" ->
                    "Retry the infrastructure failure on run " + runId
                            + "; the verdicts already reached on this tree are kept.";
            case "fix_contract" -> contractSentence(reason, taskFile, acceptance, summary, findings);
            case "resolve_blockers" ->
                    "The open P1s are not the implementer's to close ("
                            + String.join(", ", categoriesOf(findings)) + "): grant the access, "
                            + "restore the provider or tooling, or settle the disagreement each "
                            + "names, then start a new run. The acceptance is not in question.";
            case "fix_baseline" ->
                    "The project's own checks were already failing; fix that breakage, or change "
                            + "the baseline contract deliberately, then start a new run.";
            case "fix_scope" ->
                    "Bring the paths named in preexisting_violations into scope, then start a new run.";
            case "restore_contract" ->
                    "A file under .warden changed while the run was in flight; restore the "
                            + "contract and start a new run.";
            case "restore_ledger" ->
                    "Restore write access to the home corpus, then retry run " + runId
                            + "; the verdicts this run reached are kept.";
            case "repair_or_retry" ->
                    "Address the open product defect named in the report and start a new run, "
                            + "or retry run " + runId + " if the same tree can still finish.";
            default ->
                    "Read `warden report " + runId + " --text`, then either address what it names "
                            + "and start a new run, or retry this one.";
        };
    }

    private static String contractSentence(String reason, String taskFile, List<String> acceptance,
                                           Map<String, Object> summary,
                                           List<Map<String, Object>> findings) {
        StringBuilder sentence = new StringBuilder();
        sentence.append("Change the acceptance in ").append(taskFile);
        if (!acceptance.isEmpty()) {
            sentence.append(" (currently: ").append(String.join("; ", acceptance)).append(')');
        }
        String suggestion = contractSuggestion(findings);
        if (!suggestion.isBlank()) {
            sentence.append("; the reviewer suggested ").append(suggestion);
        } else if ("reproduction_passed_before_change".equals(reason)) {
            sentence.append("; a reproduce command passed on the unchanged tree, so the "
                    + "acceptance cannot detect the defect");
        } else if ("reproduction_inconclusive".equals(reason)) {
            sentence.append("; a reproduce command could not give a verdict");
        }
        Object hash = summary.get("acceptance_sha256");
        if (hash != null) {
            sentence.append("; editing it moves acceptance_sha256 (currently ").append(hash)
                    .append(')');
        }
        sentence.append('.');
        return sentence.toString();
    }

    private static List<String> commands(String kind, Map<String, Object> summary,
                                         String runId, String taskId,
                                         List<Map<String, Object>> findings) {
        String newId = newRunId(taskId, runId);
        return switch (kind) {
            case "accept_or_reject" -> List.of(
                    "warden approve " + runId + " --decision accept",
                    "warden approve " + runId + " --decision reject");
            case "raise_call_budget", "raise_cost_budget", "raise_time_budget",
                    "wait_or_add_vendor", "retry_infrastructure", "restore_ledger" ->
                    carryOn(runId, taskId);
            case "confirm_failover" -> List.of(
                    "warden approve " + runId + " --decision switch",
                    "warden run " + taskId + " --continue " + runId);
            case "fix_contract", "resolve_blockers", "fix_baseline", "fix_scope",
                    "restore_contract" -> {
                List<String> commands = new ArrayList<>();
                commands.add("warden approve " + runId + " --decision advance --note \""
                        + note(kind, findings) + "\"");
                commands.add("warden approve " + runId + " --decision " + closeOption(summary)
                        + " --note \"" + note(kind, findings) + "\"");
                if (!taskId.isBlank()) {
                    commands.add("warden validate " + taskId);
                    // A preview reserves the id it runs under, so it takes a generated one
                    // and leaves the named id to the live run.
                    commands.add("warden run " + taskId + " --dry-run");
                    commands.add("warden run " + taskId + " --run-id " + newId);
                }
                yield List.copyOf(commands);
            }
            case "repair_or_retry" -> {
                List<String> commands = new ArrayList<>();
                commands.add("warden report " + runId + " --text");
                commands.addAll(carryOn(runId, taskId));
                yield List.copyOf(commands);
            }
            default -> List.of("warden report " + runId + " --text");
        };
    }

    private static List<Map<String, Object>> edits(String kind, String reason,
                                                   Map<String, Object> summary, String taskFile,
                                                   List<String> acceptance,
                                                   List<Map<String, Object>> findings) {
        return switch (kind) {
            case "raise_call_budget" -> List.of(edit(taskFile,
                    "raise budgets.max_role_runs"
                            + (callFloor(summary).isBlank() ? "" : " to at least " + callFloor(summary))));
            case "raise_cost_budget" -> List.of(edit(taskFile,
                    "raise budgets.max_cost_usd"
                            + (summary.get("budget_stop") == null ? ""
                            : " — " + summary.get("budget_stop"))));
            case "raise_time_budget" -> List.of(edit(taskFile,
                    "raise budgets.max_elapsed_minutes above " + elapsedMinutes(summary)
                            + (summary.get("budget_stop") == null ? ""
                            : " — " + summary.get("budget_stop"))));
            case "retry_infrastructure" -> profileEdits(reason);
            case "fix_contract" -> {
                String suggestion = contractSuggestion(findings);
                String current = acceptance.isEmpty() ? "the acceptance commands currently in force"
                        : "current acceptance: " + String.join("; ", acceptance);
                String change = suggestion.isBlank()
                        ? "update the acceptance so it can fail; " + current
                        : "replace " + current + " with: " + suggestion;
                yield List.of(edit(taskFile, change));
            }
            case "fix_baseline" -> List.of(edit(taskFile,
                    "fix the project's own checks, or change the baseline contract deliberately"));
            case "fix_scope" -> List.of(edit(taskFile,
                    "revert, commit, or bring into scope the paths named in preexisting_violations"));
            case "restore_contract" -> List.of(edit(".warden",
                    "restore the contract files that changed while the run was in flight"));
            default -> List.of();
        };
    }

    private static List<Map<String, Object>> profileEdits(String reason) {
        String change = switch (reason) {
            case "role_timed_out" ->
                    "raise limits.wall_clock_minutes on the profile (the wall clock that bound "
                            + "this call), or narrow the task";
            case "turn_ceiling_reached" ->
                    "raise the profile's turn / max-turns limit";
            default ->
                    "inspect the profile that ran this role, then retry; no contract file changed";
        };
        return List.of(edit("the vendor profile that ran this role", change));
    }

    private static List<String> consequences(String kind, Map<String, Object> summary) {
        return switch (kind) {
            case "accept_or_reject" -> List.of(
                    "Nothing is landed either way; landing is a separate operator step.");
            case "raise_call_budget", "raise_cost_budget", "raise_time_budget" -> {
                List<String> rows = new ArrayList<>();
                rows.add("No budget is part of what the work is judged by, so the verdicts "
                        + "this run already reached on this tree are kept rather than paid for "
                        + "a second time.");
                if ("raise_cost_budget".equals(kind) && summary.get("unpriced_calls") != null) {
                    rows.add("The money ceiling only measures calls whose vendor reported a price: "
                            + summary.get("unpriced_calls")
                            + " of this run's calls reported none and were not counted against it.");
                }
                yield List.copyOf(rows);
            }
            case "wait_or_add_vendor", "confirm_failover", "retry_infrastructure",
                    "restore_ledger" -> List.of(
                    "The verdicts this run already reached on this tree are kept: this stop was "
                            + "not a judgement on the work.");
            case "fix_contract" -> {
                String hash = text(summary.get("acceptance_sha256"));
                yield List.of("Editing the acceptance moves acceptance_sha256"
                        + (hash.isBlank() ? "" : " (currently " + hash + ")")
                        + ", so no verdict of this run can be reused and the next run must be a "
                        + "new run, not a --continue that expects reuse.");
            }
            case "fix_baseline", "fix_scope", "restore_contract" -> List.of(
                    "What the work is judged by changes, so no verdict of this run can be reused; "
                            + "start a new run rather than --continue.");
            case "resolve_blockers" -> List.of(
                    "The findings stay on this run's record. A new run reads the tree again, "
                            + "because a blocker is a verdict and a continuation reuses nothing "
                            + "after one.");
            case "repair_or_retry" -> List.of(
                    "A repair that changes the candidate invalidates judging stages that read the "
                            + "previous tree; a retry of the same tree keeps the verdicts already paid for.",
                    "A retry continues this run's chain: the calls and fix rounds it has used "
                            + "still count against the task's limits.");
            default -> List.of();
        };
    }

    private static List<String> carryOn(String runId, String taskId) {
        return List.of(
                "warden approve " + runId + " --decision retry --note \"<why>\"",
                "warden run " + taskId + " --continue " + runId);
    }

    /**
     * {@code abort} on a failure, {@code reject} on a success — whichever
     * {@code decision_options} actually offers. The decision has often not been written yet
     * when this runs, so a missing list is inferred from the reason.
     */
    private static String closeOption(Map<String, Object> summary) {
        if (summary.get("decision_options") instanceof List<?> options) {
            for (Object option : options) if ("abort".equals(option)) return "abort";
            for (Object option : options) if ("reject".equals(option)) return "reject";
        }
        return "ready_for_human".equals(summary.get("reason")) ? "reject" : "abort";
    }

    private static String note(String kind, List<Map<String, Object>> findings) {
        String suggestion = "fix_contract".equals(kind) ? contractSuggestion(findings) : "";
        if (!suggestion.isBlank()) return sanitizeNote(suggestion);
        if (!findings.isEmpty() && findings.get(0).get("message") != null) {
            return sanitizeNote(String.valueOf(findings.get(0).get("message")));
        }
        return "fix_contract".equals(kind)
                ? "acceptance cannot detect the defect"
                : "start a new run";
    }

    private static String sanitizeNote(String text) {
        String stripped = text.replace('"', '\'').replace('\r', ' ').replace('\n', ' ').strip();
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 117) + "...";
    }

    /** Named after the stopped run, so the pair reads together in `warden ledger`. */
    private static String newRunId(String taskId, String runId) {
        if (runId.isBlank()) return taskId.isBlank() ? "next" : taskId + "-next";
        return runId + "-next";
    }

    private static String callFloor(Map<String, Object> summary) {
        Object reserve = summary.get("budget_reserve");
        Object spentByChain = summary.get("chain") instanceof Map<?, ?> chain
                ? chain.get("role_runs") : summary.get("role_runs");
        if (reserve instanceof Map<?, ?> map
                && map.get("calls_needed_to_repair_and_finish") instanceof Number needed
                && spentByChain instanceof Number spent) {
            return String.valueOf(spent.longValue() + needed.longValue());
        }
        return "";
    }

    /** Whole minutes of execution the chain has used, rounded up. */
    private static long elapsedMinutes(Map<String, Object> summary) {
        if (summary.get("chain") instanceof Map<?, ?> chain
                && chain.get("elapsed_seconds") instanceof Number seconds) {
            return (seconds.longValue() + 59) / 60;
        }
        return 0L;
    }

    private static List<String> acceptanceCommands(Map<String, Object> summary, Path root,
                                                   String taskId) {
        Object recorded = summary.get("acceptance_commands");
        if (recorded instanceof List<?> list) return strings(list);
        if (root == null || taskId == null || taskId.isBlank()) return List.of();
        try {
            return List.copyOf(new ConfigLoader().load(root, taskId).resolved().acceptanceCommands());
        } catch (Exception unreadable) {
            // Optional: a structured step that cannot name the commands is still a step.
            return List.of();
        }
    }

    /**
     * Open P1s as {@code warden report} already has them: the last {@code finding_history}
     * round, not a re-derivation from stage artifacts.
     */
    private static List<Map<String, Object>> openBlockingFindings(Map<String, Object> summary) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(summary.get("finding_history") instanceof List<?> rounds) || rounds.isEmpty()) {
            return result;
        }
        Map<?, ?> last = null;
        for (Object row : rounds) if (row instanceof Map<?, ?> map) last = map;
        if (last == null || !(last.get("findings") instanceof List<?> findings)) return result;
        for (Object item : findings) {
            if (!(item instanceof Map<?, ?> finding)) continue;
            if (!"P1".equals(String.valueOf(finding.get("severity")))) continue;
            if ("closed".equals(String.valueOf(finding.get("status")))) continue;
            result.add(projectFinding(finding));
        }
        return result;
    }

    private static Map<String, Object> projectFinding(Map<?, ?> finding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", finding.get("id"));
        row.put("severity", finding.get("severity"));
        row.put("category", finding.get("category"));
        row.put("path", finding.get("path"));
        row.put("line", finding.get("line"));
        row.put("message", finding.get("message"));
        row.put("suggestion", finding.get("suggestion"));
        row.put("proposed_acceptance", finding.get("proposed_acceptance"));
        return row;
    }

    /** The first suggestion a contract-gap finding makes; nobody else's is an acceptance edit. */
    private static String contractSuggestion(List<Map<String, Object>> findings) {
        for (Map<String, Object> finding : findings) {
            if (!"contract_gap".equals(categoryOf(finding))) continue;
            Object suggestion = finding.get("suggestion");
            if (suggestion instanceof String text && !text.isBlank()) return text;
        }
        return "";
    }

    private static List<String> categoriesOf(List<Map<String, Object>> findings) {
        List<String> categories = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            String category = categoryOf(finding);
            if (!categories.contains(category)) categories.add(category);
        }
        return categories.isEmpty() ? List.of("category not recorded") : categories;
    }

    private static Map<String, Object> edit(String file, String change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file", file);
        row.put("change", change);
        return row;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) return List.copyOf(list);
        return List.of();
    }

    private static List<String> strings(List<?> values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) if (value != null) result.add(String.valueOf(value));
        return result;
    }
}
