package dev.warden.ledger;

import dev.warden.config.WardenTree;
import dev.warden.config.Workflow;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One run, assembled: what was asked, which vendor answered, what it cost, what the machine
 * measured, what a human decided.
 *
 * The evidence for a run is spread across a directory per stage on purpose — each stage
 * writes its own report and never edits another's, so nothing that happened can be quietly
 * revised later. That is right for durability and useless for answering "how did this run
 * go". This class does the joining, reads only, and derives nothing it cannot point at: a
 * number the vendor never reported stays absent instead of becoming a zero.
 *
 * `unknown_count` is carried beside every total for exactly that reason. A cost of $0.00 over
 * four vendor calls means something very different from "four calls, none of which reported a
 * cost", and a report that renders both the same way is one that will be believed.
 */
public final class RunReport {

    public static final long SCHEMA_VERSION = 1L;

    private final ProcessRunner processes;

    public RunReport() { this(new ProcessRunner()); }

    public RunReport(ProcessRunner processes) { this.processes = processes; }

    public Map<String, Object> of(Path projectRoot, String runId) throws IOException {
        Path runs = projectRoot.resolve(".warden/runs");
        Path directory = runs.resolve(runId);
        if (!Files.isDirectory(directory)) {
            throw new IOException("no run '" + runId + "' under " + runs);
        }
        Map<String, Object> summary = readJson(directory.resolve("task-run.json"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", SCHEMA_VERSION);
        report.put("run_id", runId);
        report.put("project", projectRoot.toString());
        report.put("task_id", summary.get("task_id"));
        report.put("risk", summary.get("risk"));
        report.put("ok", summary.get("ok"));
        report.put("reason", summary.get("reason"));
        report.put("next_action", summary.get("next_action"));
        report.put("dry_run", summary.get("dry_run"));
        report.put("diff_base_commit", summary.get("diff_base_commit"));
        report.put("contract_sha256", summary.get("contract_sha256"));
        report.put("goal", goalOf(projectRoot, summary.get("task_id")));
        report.put("workflow", summary.get("workflow"));
        report.put("skipped_stages", summary.get("skipped_stages"));
        // Whether the candidate was judged, whether the chain finished, and what to do next
        // are three answers, and the joined report used to carry only `reason` for all of
        // them. They are copied rather than recomputed: the loop is the only thing that knows
        // which stage completed, and a reader that re-derived it could disagree with the run.
        report.put("candidate_review_passed", summary.get("candidate_review_passed"));
        report.put("workflow_incomplete", summary.get("workflow_incomplete"));
        report.put("pending_stages", summary.get("pending_stages"));
        report.put("open_blocking_findings", summary.get("open_blocking_findings"));
        report.put("review_coverage", summary.get("review_coverage"));
        // What each judging stage objected to, round by round, and what moved between them.
        // Two reports of one defect used to be two unrelated blobs of text.
        report.put("finding_history", summary.get("finding_history"));
        for (String key : List.of("finding_protocol_failure", "last_repair_receipt",
                "severity_downgraded_without_change", "nonactionable_blocking_ids"))
            report.put(key, summary.get(key));
        report.put("repair_made_no_progress", summary.get("repair_made_no_progress"));
        report.put("budget_plan", summary.get("budget_plan"));
        report.put("budget_reserve", summary.get("budget_reserve"));
        report.put("budget_limit_hit", summary.get("budget_limit_hit"));
        report.put("safe_next_step", summary.get("safe_next_step"));
        // What a continuation refused to carry over, and why. A run that paid for a stage it
        // could have inherited should be able to say which term moved.
        report.put("reused_judgements", summary.get("reused_judgements"));
        report.put("reuse_declined", summary.get("reuse_declined"));
        report.put("reuse_declined_by_stage", summary.get("reuse_declined_by_stage"));
        report.put("contract_change_budget_only", summary.get("contract_change_budget_only"));
        report.put("baseline", baseline(summary));

        List<Map<String, Object>> stages = stages(runs, runId, summary);
        report.put("stages", stages);
        report.put("vendors", vendors(stages));
        report.put("failovers", failovers(stages));
        report.put("visual", visual(stages));
        // Warden's own contract files are listed apart. They are a real difference in the
        // worktree and stay visible, but reporting "4 files changed" for a one-file fix sends
        // somebody looking for three changes no agent made.
        List<String> changed = changedFiles(projectRoot, summary.get("diff_base_commit"));
        report.put("changed_files", List.copyOf(WardenTree.sourcePaths(
                new java.util.LinkedHashSet<>(changed))));
        report.put("contract_files", changed.stream()
                .filter(path -> path.equals(WardenTree.DIRECTORY)
                        || path.startsWith(WardenTree.DIRECTORY + "/"))
                .toList());
        report.put("decision", readOptionalJson(directory.resolve("decision.json")));
        report.put("totals", totals(summary, stages));
        return report;
    }

    // -------------------------------------------------------------------------- stages

    /**
     * The summary's step list is the order things happened; the per-stage directory holds
     * what they cost. Joining on the deterministic stage run id keeps this a read: nothing
     * here re-derives an outcome that a stage already wrote down.
     */
    private List<Map<String, Object>> stages(Path runs, String runId, Map<String, Object> summary)
            throws IOException {
        List<Map<String, Object>> stages = new ArrayList<>();
        Object steps = summary.get("steps");
        if (!(steps instanceof List<?> rows)) return stages;

        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> step = cast(raw);
            String label = text(step.get("step"));
            long attempt = number(step.get("attempt"));
            boolean visualRole = "visual_qa".equals(label) && step.get("profile") != null;

            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("step", label);
            stage.put("attempt", attempt);
            stage.put("ok", step.get("ok"));
            stage.put("code", step.get("code"));
            if (Boolean.TRUE.equals(step.get("dry_run"))) stage.put("dry_run", true);

            Path detail = detailFile(runs, runId, label, text(step.get("stage")), attempt, visualRole);
            if (detail == null && step.get("report") != null) {
                Path referenced = Path.of(String.valueOf(step.get("report")));
                if (Files.isRegularFile(referenced)) detail = referenced;
            }
            Map<String, Object> body = detail == null ? Map.of() : readOptionalJson(detail);
            if (body == null) body = Map.of();

            if (step.get("profile") != null || body.get("vendor") != null) {
                stage.put("kind", "role");
                stage.put("role", body.getOrDefault("role", visualRole ? "visual_qa" : label));
                stage.put("profile", step.getOrDefault("profile", body.get("profile")));
                stage.put("vendor", step.getOrDefault("vendor", body.get("vendor")));
                stage.put("model", body.get("model"));
                stage.put("model_reported", body.get("model_reported"));
                stage.put("runner", body.get("runner"));
                stage.put("read_only", body.get("read_only"));
                stage.put("duration_millis", body.get("duration_millis"));
                stage.put("cost_usd", first(step.get("cost_usd"), body.get("cost_usd")));
                stage.put("num_turns", body.get("num_turns"));
                stage.put("tokens", body.get("tokens"));
                stage.put("vendor_attempts", step.getOrDefault("vendor_attempts",
                        body.get("vendor_attempts")));
                stage.put("failed_over_from", step.get("failed_over_from"));
                stage.put("artifact_path", step.getOrDefault("artifact_path", body.get("artifact_path")));
                stage.put("rejected_profiles", step.get("rejected_profiles"));
            } else if ("gates".equals(label) || "baseline".equals(label)) {
                stage.put("kind", "machine_gates");
                stage.put("phase", body.getOrDefault("phase",
                        "baseline".equals(label) ? "baseline" : "acceptance"));
                if (step.get("reused") != null) stage.put("reused", step.get("reused"));
                if (step.get("reused_from") != null) {
                    stage.put("reused_from", step.get("reused_from"));
                }
                stage.put("commands", body.get("commands"));
                stage.put("changed_paths", body.get("changed_paths"));
                stage.put("violations", body.get("violations"));
                stage.put("report", step.get("report"));
            } else {
                stage.put("kind", "visual_harness");
                stage.put("url", body.get("url"));
                stage.put("scenarios", scenarioSummaries(body));
                stage.put("screenshots", body.get("image_evidence"));
                stage.put("report", step.get("report"));
            }
            stages.add(stage);
        }
        return stages;
    }

    /**
     * @param stageName the workflow stage that produced this step, when the summary recorded
     *                  one. Tried first, because a role dispatched by two stages writes its
     *                  evidence under the stage rather than the role — otherwise the second
     *                  stage's file is what a reader of the first stage's row gets, and the
     *                  report then prints one vendor's row with another vendor's model,
     *                  duration and token counts.
     */
    private static Path detailFile(Path runs, String runId, String label, String stageName,
                                   long attempt, boolean visualRole) {
        String file = switch (label) {
            case "gates" -> "machine-gate.json";
            case "baseline" -> "baseline-gate.json";
            case "visual_qa" -> visualRole ? "role-visual_qa.json" : "visual-qa.json";
            default -> "role-" + label + ".json";
        };
        List<String> directories = new ArrayList<>();
        if (stageName != null && !stageName.isBlank()) {
            directories.add(runId + "--" + Workflow.slug(stageName) + "-" + attempt);
        }
        directories.add(switch (label) {
            case "gates" -> runId + "--gates-" + attempt;
            case "baseline" -> runId + "--baseline-" + attempt;
            case "visual_qa" -> runId + "--" + (visualRole ? "visual-role-" : "visual-qa-") + attempt;
            default -> runId + "--" + label + "-" + attempt;
        });
        for (String directory : directories) {
            Path candidate = runs.resolve(directory).resolve(file);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Map<String, Object> baseline(Map<String, Object> summary) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("configured", summary.get("baseline_required"));
        baseline.put("commands", summary.get("baseline_commands"));
        baseline.put("passed", summary.get("baseline_ok"));
        baseline.put("code", summary.get("baseline_code"));
        baseline.put("report", summary.get("baseline_report"));
        baseline.put("report_sha256", summary.get("baseline_report_sha256"));
        baseline.put("reused_from", summary.get("baseline_reused_from"));
        baseline.put("reuse_declined", summary.get("baseline_reuse_declined"));
        return baseline;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scenarioSummaries(Map<String, Object> body) {
        Object adapter = body.get("adapter");
        if (!(adapter instanceof Map<?, ?> map)) return List.of();
        Object scenarios = ((Map<String, Object>) map).get("scenarios");
        if (!(scenarios instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> scenario)) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("scenario", scenario.get("raw"));
            one.put("ok", scenario.get("ok"));
            one.put("why", scenario.get("why"));
            one.put("screenshot", scenario.get("screenshot"));
            one.put("steps", stepEvidence(scenario.get("steps")));
            one.put("a11y", a11yNames(scenario.get("a11y")));
            result.add(one);
        }
        return result;
    }

    private static List<Map<String, Object>> stepEvidence(Object raw) {
        if (!(raw instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> step)) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("matcher", step.get("matcher"));
            one.put("assertion", step.get("assertion"));
            one.put("ok", step.get("ok"));
            if (step.get("screenshot_after") != null) {
                one.put("screenshot_after", step.get("screenshot_after"));
            }
            if (step.get("why") != null) one.put("why", step.get("why"));
            result.add(one);
        }
        return result;
    }

    /**
     * Role, name, box, focused, ignored. A blank name is kept: an interactive
     * control with no accessible name is a finding, not noise. {@code ignored}
     * is carried rather than dropped, so a row cannot look like an ordinary
     * control the way it did when the flag was recorded and then discarded.
     */
    private static List<Map<String, Object>> a11yNames(Object raw) {
        if (!(raw instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> node)) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("role", node.get("role"));
            Object name = node.get("name");
            one.put("name", name instanceof String text ? text : "");
            if (Boolean.TRUE.equals(node.get("focused"))) one.put("focused", true);
            if (Boolean.TRUE.equals(node.get("ignored"))) one.put("ignored", true);
            if (node.get("box") instanceof Map<?, ?>) one.put("box", node.get("box"));
            result.add(one);
            if (result.size() >= 24) break;
        }
        return result;
    }

    // -------------------------------------------------------------------------- rollups

    /**
     * Per vendor and model, because that is the comparison the operator is actually making:
     * two vendors filled the same role in the same run, and one of them cost four times what
     * the other did.
     */
    private static List<Map<String, Object>> vendors(List<Map<String, Object>> stages) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> stage : stages) {
            if (!"role".equals(stage.get("kind"))) continue;
            for (Map<String, Object> call : callsOf(stage)) {
                String vendor = text(call.getOrDefault("vendor", "<unknown>"));
                String model = text(call.getOrDefault("model", "<unknown>"));
                Map<String, Object> row = byKey.computeIfAbsent(vendor + "\u0000" + model, ignored -> {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    fresh.put("vendor", vendor);
                    fresh.put("model", model);
                    fresh.put("calls", 0L);
                    fresh.put("failed_calls", 0L);
                    fresh.put("cost_usd", 0.0d);
                    fresh.put("cost_unknown_calls", 0L);
                    fresh.put("duration_millis", 0L);
                    fresh.put("duration_unknown_calls", 0L);
                    fresh.put("input_tokens", 0L);
                    fresh.put("output_tokens", 0L);
                    fresh.put("total_tokens", 0L);
                    fresh.put("tokens_unknown_calls", 0L);
                    return fresh;
                });
                row.put("calls", number(row.get("calls")) + 1);
                if (Boolean.FALSE.equals(call.get("ok"))) {
                    row.put("failed_calls", number(row.get("failed_calls")) + 1);
                }
                accumulate(row, "cost_usd", "cost_unknown_calls", call.get("cost_usd"));
                accumulateLong(row, "duration_millis", "duration_unknown_calls",
                        call.get("duration_millis"));
                Map<String, Object> tokens = call.get("tokens") instanceof Map<?, ?> map
                        ? cast(map) : Map.of();
                boolean any = false;
                any |= accumulateLong(row, "input_tokens", null, tokens.get("input"));
                any |= accumulateLong(row, "output_tokens", null, tokens.get("output"));
                any |= accumulateLong(row, "total_tokens", null, tokens.get("total"));
                if (!any) row.put("tokens_unknown_calls", number(row.get("tokens_unknown_calls")) + 1);
            }
        }
        // A total nobody contributed to is not zero. `$0.0000 across one call` and "the vendor
        // reported no cost" are different facts, and only the first one is believable on
        // sight, so the unreported one is left absent and renders as `?`.
        for (Map<String, Object> row : byKey.values()) {
            long calls = number(row.get("calls"));
            if (number(row.get("cost_unknown_calls")) == calls) row.put("cost_usd", null);
            if (number(row.get("duration_unknown_calls")) == calls) row.put("duration_millis", null);
            if (number(row.get("tokens_unknown_calls")) == calls) {
                row.put("input_tokens", null);
                row.put("output_tokens", null);
                row.put("total_tokens", null);
            }
        }
        return List.copyOf(byKey.values());
    }

    /**
     * A role that failed over dispatched more than one vendor. Charging only the survivor
     * would under-report exactly the run most worth costing.
     *
     * New evidence records every dimension on every attempt. Historical sparse arrays wrote
     * some settlement fields only on the enclosing role report; inherit those onto the final
     * attempt only. Earlier attempts remain explicitly unknown rather than acquiring the
     * successful vendor's telemetry.
     */
    private static List<Map<String, Object>> callsOf(Map<String, Object> stage) {
        Object attempts = stage.get("vendor_attempts");
        if (attempts instanceof List<?> rows && !rows.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> map)) continue;
                calls.add(new LinkedHashMap<>(cast(map)));
            }
            for (int index = 0; index < calls.size(); index++) {
                Map<String, Object> call = calls.get(index);
                boolean finalAttempt = index == calls.size() - 1;
                if (finalAttempt) {
                    inherit(call, stage, "profile");
                    inherit(call, stage, "vendor");
                    inherit(call, stage, "runner");
                    inherit(call, stage, "code");
                    inherit(call, stage, "ok");
                    inherit(call, stage, "cost_usd");
                    inherit(call, stage, "duration_millis");
                    inherit(call, stage, "tokens");
                }
                Object reported = first(call.get("model_reported"),
                        finalAttempt ? stage.get("model_reported") : null);
                Object model = first(reported, first(call.get("model"),
                        finalAttempt ? stage.get("model") : null));
                if (model != null) call.put("model", model);
            }
            if (!calls.isEmpty()) return calls;
        }
        return List.of(stage);
    }

    private static void inherit(Map<String, Object> target, Map<String, Object> source,
                                String field) {
        if (target.get(field) == null && source.get(field) != null) {
            target.put(field, source.get(field));
        }
    }

    /**
     * Every point in the run where the vendor doing the work changed. It is recorded per
     * stage, where it happened; it is reported here, because "who wrote this" is a question
     * about the run and not about a directory.
     */
    private static List<Map<String, Object>> failovers(List<Map<String, Object>> stages) {
        List<Map<String, Object>> switches = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            List<Map<String, Object>> calls = callsOf(stage);
            if (calls.size() < 2) continue;
            for (int index = 1; index < calls.size(); index++) {
                Map<String, Object> before = calls.get(index - 1);
                Map<String, Object> after = calls.get(index);
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("step", stage.get("step"));
                one.put("attempt", stage.get("attempt"));
                one.put("from_profile", before.get("profile"));
                one.put("from_vendor", before.get("vendor"));
                one.put("from_code", before.get("code"));
                one.put("to_profile", after.get("profile"));
                one.put("to_vendor", after.get("vendor"));
                switches.add(one);
            }
        }
        return List.copyOf(switches);
    }

    private static Map<String, Object> visual(List<Map<String, Object>> stages) {
        Map<String, Object> visual = new LinkedHashMap<>();
        List<Map<String, Object>> scenarios = new ArrayList<>();
        // Several scenarios share one viewport screenshot, so the harness legitimately names
        // the same file more than once. Listing it three times reads as three pieces of
        // evidence; it is one.
        Map<String, Object> screenshotsByPath = new LinkedHashMap<>();
        boolean ran = false;
        Boolean last = null;
        for (Map<String, Object> stage : stages) {
            if (!"visual_harness".equals(stage.get("kind"))) continue;
            ran = true;
            last = stage.get("ok") instanceof Boolean value ? value : null;
            if (stage.get("scenarios") instanceof List<?> rows) {
                for (Object item : rows) if (item instanceof Map<?, ?> map) scenarios.add(cast(map));
            }
            if (stage.get("screenshots") instanceof List<?> rows) {
                for (Object item : rows) {
                    if (item instanceof Map<?, ?> map) {
                        screenshotsByPath.putIfAbsent(text(map.get("path")), cast(map));
                    }
                }
            }
        }
        List<Object> screenshots = new ArrayList<>(screenshotsByPath.values());
        visual.put("ran", ran);
        visual.put("passed", last);
        visual.put("scenarios", scenarios);
        visual.put("screenshots", screenshots);
        return visual;
    }

    private static Map<String, Object> totals(Map<String, Object> summary,
                                              List<Map<String, Object>> stages) {
        long roleRuns = 0;
        long failedRoleRuns = 0;
        long gateRuns = 0;
        long failedGateRuns = 0;
        for (Map<String, Object> stage : stages) {
            if ("role".equals(stage.get("kind"))) {
                List<Map<String, Object>> calls = callsOf(stage);
                roleRuns += calls.size();
                failedRoleRuns += calls.stream()
                        .filter(call -> Boolean.FALSE.equals(call.get("ok"))).count();
            } else {
                gateRuns++;
                if (Boolean.FALSE.equals(stage.get("ok"))) failedGateRuns++;
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("role_runs", roleRuns);
        totals.put("failed_role_runs", failedRoleRuns);
        totals.put("machine_stage_runs", gateRuns);
        totals.put("failed_machine_stage_runs", failedGateRuns);
        totals.put("fix_rounds", summary.get("attempts_used"));
        // The loop's own accounting, kept beside the join so a disagreement is visible rather
        // than resolved silently in favour of whichever number was computed last.
        totals.put("cost_usd_reported_by_loop", summary.get("total_cost_usd"));
        totals.put("role_runs_reported_by_loop", summary.get("role_runs"));
        totals.put("budget_max_role_runs", summary.get("budget_max_role_runs"));
        totals.put("budget_max_cost_usd", summary.get("budget_max_cost_usd"));
        return totals;
    }

    // ---------------------------------------------------------------------------- text

    /** The same facts, for a terminal. Anything the vendor did not report reads as `?`. */
    public static String render(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        out.append("run      ").append(report.get("run_id")).append('\n');
        out.append("task     ").append(report.get("task_id"))
                .append("  risk=").append(report.get("risk")).append('\n');
        Object goal = report.get("goal");
        if (goal != null) out.append("goal     ").append(goal).append('\n');
        out.append("outcome  ").append(Boolean.TRUE.equals(report.get("ok")) ? "ok" : "STOPPED")
                .append("  ").append(report.get("reason"))
                .append("  next=").append(report.get("next_action"));
        // `budget_exhausted` covers two ceilings that are raised in different places, so the
        // reason alone sends half the readers to the wrong line of the task file.
        if (report.get("budget_limit_hit") != null) {
            out.append("  limit=").append(report.get("budget_limit_hit"));
        }
        out.append('\n');
        // Printed next to the outcome and not buried below the tables, because the whole point
        // of separating them is that a reader who stops at the outcome line gets it wrong.
        if (report.get("candidate_review_passed") != null) {
            out.append("review   ").append(Boolean.TRUE.equals(report.get("candidate_review_passed"))
                            ? "the candidate passed every review that ran"
                            : "the candidate has not passed review")
                    .append("  open blockers=").append(text(report.get("open_blocking_findings")))
                    .append('\n');
        }
        List<Object> pending = list(report.get("pending_stages"));
        if (!pending.isEmpty()) {
            List<String> owed = new java.util.ArrayList<>();
            for (Object item : pending) {
                if (item instanceof Map<?, ?> row) {
                    owed.add(row.get("stage") + " (" + row.get("reason") + ")");
                }
            }
            out.append("owed     the workflow did not finish: ")
                    .append(String.join(", ", owed)).append('\n');
        }
        if (report.get("safe_next_step") instanceof String step) {
            out.append("do next  ").append(step).append('\n');
        }

        Object decision = report.get("decision");
        if (decision instanceof Map<?, ?> map) {
            out.append("human    ").append(map.get("state"))
                    .append(map.get("decision") == null ? "" : " -> " + map.get("decision"))
                    .append("  options=").append(map.get("options")).append('\n');
        }

        out.append('\n').append("stage           attempt  ok  vendor/model                    "
                + "cost      tokens        ms\n");
        for (Object item : list(report.get("stages"))) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> stage = cast(raw);
            String who = "role".equals(stage.get("kind"))
                    ? text(stage.getOrDefault("vendor", "?")) + "/" + text(stage.getOrDefault("model", "?"))
                    : "-";
            Map<String, Object> tokens = stage.get("tokens") instanceof Map<?, ?> map
                    ? cast(map) : Map.of();
            out.append(String.format(Locale.ROOT, "%-15s %7s  %-3s %-30s  %-8s  %-12s %8s%n",
                    clip(text(stage.get("step")), 15),
                    text(stage.get("attempt")),
                    Boolean.TRUE.equals(stage.get("ok")) ? "ok"
                            : Boolean.FALSE.equals(stage.get("ok")) ? "NO" : "-",
                    clip(who, 30),
                    money(stage.get("cost_usd")),
                    text(tokens.get("input")) + "/" + text(tokens.get("output")),
                    text(stage.get("duration_millis"))));
        }

        List<Object> vendors = list(report.get("vendors"));
        if (!vendors.isEmpty()) {
            out.append('\n').append("vendor/model                     calls  fail  "
                    + "cost      in/out tokens        ms\n");
            for (Object item : vendors) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> row = cast(raw);
                out.append(String.format(Locale.ROOT, "%-32s %5s %5s  %-8s  %-18s %8s%n",
                        clip(text(row.get("vendor")) + "/" + text(row.get("model")), 32),
                        text(row.get("calls")), text(row.get("failed_calls")),
                        money(row.get("cost_usd")),
                        text(row.get("input_tokens")) + "/" + text(row.get("output_tokens")),
                        text(row.get("duration_millis"))));
                long unknownCost = number(row.get("cost_unknown_calls"));
                long unknownTokens = number(row.get("tokens_unknown_calls"));
                if (unknownCost > 0 || unknownTokens > 0) {
                    out.append("    not reported by this vendor: cost on ").append(unknownCost)
                            .append(" call(s), tokens on ").append(unknownTokens).append(" call(s)\n");
                }
            }
        }

        List<Object> switches = list(report.get("failovers"));
        if (!switches.isEmpty()) {
            out.append('\n').append("failover\n");
            for (Object item : switches) {
                if (!(item instanceof Map<?, ?> row)) continue;
                out.append("  ").append(row.get("step")).append(": ")
                        .append(row.get("from_profile")).append(" (").append(row.get("from_vendor"))
                        .append(") ").append(row.get("from_code")).append(" -> ")
                        .append(row.get("to_profile")).append(" (").append(row.get("to_vendor"))
                        .append(")\n");
            }
        }

        if (report.get("visual") instanceof Map<?, ?> raw) {
            Map<String, Object> visual = cast(raw);
            if (Boolean.TRUE.equals(visual.get("ran"))) {
                out.append('\n').append("browser  ")
                        .append(Boolean.TRUE.equals(visual.get("passed")) ? "passed" : "failed")
                        .append("  ").append(list(visual.get("screenshots")).size())
                        .append(" screenshot(s)\n");
                boolean sharedA11y = true;
                String sharedLine = null;
                for (Object item : list(visual.get("scenarios"))) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    String line = a11yLine(list(row.get("a11y")));
                    if (sharedLine == null) sharedLine = line;
                    else if (!sharedLine.equals(line)) sharedA11y = false;
                }
                if (sharedLine == null || sharedLine.isEmpty()) sharedA11y = false;

                for (Object item : list(visual.get("scenarios"))) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    out.append("  ").append(Boolean.TRUE.equals(row.get("ok")) ? "ok  " : "FAIL")
                            .append("  ").append(row.get("scenario"));
                    if (row.get("why") != null) out.append("  — ").append(row.get("why"));
                    out.append('\n');
                    for (Object step : list(row.get("steps"))) {
                        if (!(step instanceof Map<?, ?> one)) continue;
                        if (one.get("screenshot_after") == null) continue;
                        out.append("        after ").append(one.get("matcher"))
                                .append("  ").append(one.get("screenshot_after")).append('\n');
                    }
                    if (!sharedA11y) {
                        String line = a11yLine(list(row.get("a11y")));
                        if (!line.isEmpty()) {
                            out.append("        a11y  ").append(line).append('\n');
                        }
                    }
                }
                if (sharedA11y) {
                    out.append("        a11y  ").append(sharedLine).append('\n');
                }
                for (Object item : list(visual.get("screenshots"))) {
                    if (item instanceof Map<?, ?> row) out.append("        ").append(row.get("path")).append('\n');
                }
            }
        }

        List<Object> changed = list(report.get("changed_files"));
        out.append('\n').append("changed  ").append(changed.size()).append(" source file(s)");
        if (report.get("diff_base_commit") != null) {
            out.append(" since ").append(clip(text(report.get("diff_base_commit")), 12));
        }
        out.append('\n');
        for (Object path : changed) out.append("  ").append(path).append('\n');
        List<Object> contract = list(report.get("contract_files"));
        if (!contract.isEmpty()) {
            out.append("         plus ").append(contract.size())
                    .append(" Warden contract file(s), unchanged since the run snapshot\n");
        }

        if (report.get("totals") instanceof Map<?, ?> raw) {
            Map<String, Object> totals = cast(raw);
            out.append('\n').append("totals   ").append(totals.get("role_runs"))
                    .append(" vendor call(s), ")
                    .append(number(totals.get("fix_rounds"))).append(" fix round(s)");
            if (totals.get("cost_usd_reported_by_loop") instanceof Number spent) {
                out.append(", $").append(money(spent));
                if (totals.get("budget_max_cost_usd") instanceof Number limit
                        && limit.doubleValue() > 0) {
                    out.append(" of $").append(money(limit)).append(" budget");
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    // --------------------------------------------------------------------------- utility

    private String goalOf(Path projectRoot, Object taskId) {
        if (!(taskId instanceof String id)) return null;
        Path file = projectRoot.resolve(".warden/tasks").resolve(id + ".yaml");
        if (!Files.isRegularFile(file)) return null;
        try {
            Object parsed = dev.warden.yaml.Yaml.parse(Files.readString(file, StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> map && map.get("goal") instanceof String goal ? goal : null;
        } catch (RuntimeException | IOException unreadable) {
            return null;
        }
    }

    /** The actual result of the run: what is different in the worktree, straight from git. */
    private List<String> changedFiles(Path projectRoot, Object base) {
        if (!(base instanceof String commit) || commit.isBlank()) return List.of();
        try {
            return List.copyOf(new GitRepository(projectRoot, processes).changedPaths(commit));
        } catch (Exception notARepositoryAnyMore) {
            return List.of();
        }
    }

    private static void accumulate(Map<String, Object> row, String total, String unknown, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            row.put(total, ((Number) row.get(total)).doubleValue() + number.doubleValue());
        } else if (unknown != null) {
            row.put(unknown, number(row.get(unknown)) + 1);
        }
    }

    private static boolean accumulateLong(Map<String, Object> row, String total, String unknown,
                                          Object value) {
        if (value instanceof Number number) {
            row.put(total, number(row.get(total)) + number.longValue());
            return true;
        }
        if (unknown != null) row.put(unknown, number(row.get(unknown)) + 1);
        return false;
    }

    private static Object first(Object preferred, Object fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static long number(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    private static String money(Object value) {
        return value instanceof Number number
                ? String.format(Locale.ROOT, "%.4f", number.doubleValue()) : "?";
    }

    private static String clip(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }

    /**
     * One human-readable a11y row. Ignored nodes are marked unreachable so they
     * cannot be read as ordinary controls. A blank name is spelled out: that is
     * the finding.
     */
    private static String a11yLine(List<Object> names) {
        StringBuilder line = new StringBuilder();
        int shown = 0;
        for (Object node : names) {
            if (!(node instanceof Map<?, ?> raw)) continue;
            if (shown > 0) line.append(" | ");
            line.append(a11yLabel(cast(raw)));
            shown++;
            if (shown >= 8) break;
        }
        return line.toString();
    }

    private static String a11yLabel(Map<String, Object> node) {
        String role = text(node.get("role"));
        Object name = node.get("name");
        String label = name instanceof String named && !named.isBlank()
                ? named : "(no accessible name)";
        if (Boolean.TRUE.equals(node.get("ignored"))) {
            return role + ":" + label + " [unreachable]";
        }
        return role + ":" + label;
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> rows ? List.copyOf(rows) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("no task-run.json at " + file
                    + "; this run never reached a summary");
        }
        return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> readOptionalJson(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
