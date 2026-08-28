package dev.warden.ledger;

import dev.warden.config.WardenTree;
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

        List<Map<String, Object>> stages = stages(runs, runId, summary);
        report.put("stages", stages);
        report.put("vendors", vendors(stages));
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

            Path detail = detailFile(runs, runId, label, attempt, visualRole);
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
            } else if ("gates".equals(label)) {
                stage.put("kind", "machine_gates");
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

    private static Path detailFile(Path runs, String runId, String label, long attempt,
                                   boolean visualRole) {
        String stageRunId = switch (label) {
            case "gates" -> runId + "--gates-" + attempt;
            case "visual_qa" -> runId + "--" + (visualRole ? "visual-role-" : "visual-qa-") + attempt;
            default -> runId + "--" + label + "-" + attempt;
        };
        String file = switch (label) {
            case "gates" -> "machine-gate.json";
            case "visual_qa" -> visualRole ? "role-visual_qa.json" : "visual-qa.json";
            default -> "role-" + label + ".json";
        };
        Path candidate = runs.resolve(stageRunId).resolve(file);
        return Files.isRegularFile(candidate) ? candidate : null;
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
            result.add(one);
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
     * Each attempt records its own vendor and cost, but model and token usage are written
     * once, on the role report, by the attempt that actually settled. Those are folded back
     * into the matching attempt: leaving them out made a rollup that said `grok/&lt;unknown&gt;`
     * and `0/0 tokens` next to a stage row that had both.
     */
    private static List<Map<String, Object>> callsOf(Map<String, Object> stage) {
        Object attempts = stage.get("vendor_attempts");
        if (attempts instanceof List<?> rows && !rows.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> call = new LinkedHashMap<>(cast(map));
                if (call.get("profile") != null && call.get("profile").equals(stage.get("profile"))) {
                    call.putIfAbsent("model", stage.get("model"));
                    call.putIfAbsent("tokens", stage.get("tokens"));
                }
                calls.add(call);
            }
            if (!calls.isEmpty()) return calls;
        }
        return List.of(stage);
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
                roleRuns += callsOf(stage).size();
                if (Boolean.FALSE.equals(stage.get("ok"))) failedRoleRuns++;
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
                .append("  next=").append(report.get("next_action")).append('\n');

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

        if (report.get("visual") instanceof Map<?, ?> raw) {
            Map<String, Object> visual = cast(raw);
            if (Boolean.TRUE.equals(visual.get("ran"))) {
                out.append('\n').append("browser  ")
                        .append(Boolean.TRUE.equals(visual.get("passed")) ? "passed" : "failed")
                        .append("  ").append(list(visual.get("screenshots")).size())
                        .append(" screenshot(s)\n");
                for (Object item : list(visual.get("scenarios"))) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    out.append("  ").append(Boolean.TRUE.equals(row.get("ok")) ? "ok  " : "FAIL")
                            .append("  ").append(row.get("scenario"));
                    if (row.get("why") != null) out.append("  — ").append(row.get("why"));
                    out.append('\n');
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
