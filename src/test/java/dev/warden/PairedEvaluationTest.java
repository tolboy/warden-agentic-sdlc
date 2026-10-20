package dev.warden;

import dev.warden.json.Json;
import dev.warden.ledger.ComparisonConditions;
import dev.warden.ledger.LedgerCompare;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PairedEvaluationTest implements Suite {
    @Override public String name() { return "paired-evaluation"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-paired-evaluation-");
        try {
            conditionChecks(check, root.resolve("conditions"));
            pairingChecks(check, root.resolve("pairs"));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private void conditionChecks(Check check, Path project) throws Exception {
        Map<String, Object> a = summary("a", "model-a", "high", "stopped");
        write(project, "a", a);
        write(project, "b", summary("b", "model-b", "high", "stopped"));
        write(project, "c", summary("c", "model-a", "low", "stopped"));
        check.eq("the same profile with another model or effort has different conditions", 3,
                rows(LedgerCompare.compare(project, null), "groups").size());
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("effort", "high");
        reordered.put("model", "model-a");
        reordered.put("profile", "writer");
        check.eq("JSON object iteration order does not change a condition",
                ComparisonConditions.hash(Map.of("profile", "writer", "model", "model-a", "effort", "high")),
                ComparisonConditions.hash(reordered));

        // Stopping before any role must not create a new arm of the experiment.
        Map<String, Object> beforeFirstCall = summary("d", "model-a", "high", "stopped");
        beforeFirstCall.put("steps", List.of());
        write(project, "d", beforeFirstCall);
        check.eq("frozen intent groups failed preflight with its intended cohort", 3,
                rows(LedgerCompare.compare(project, null), "groups").size());
        Map<String, Object> historical = summary("e", "model-a", "high", "stopped");
        historical.remove("comparison_conditions");
        historical.put("steps", List.of(Map.of("profile", "writer", "role_contract", Map.of("model", "old-a", "effort", "high"))));
        write(project, "e", historical);
        Map<String, Object> historicalOther = new LinkedHashMap<>(historical);
        historicalOther.put("run_id", "f");
        historicalOther.put("chain", chain(List.of("f")));
        historicalOther.put("steps", List.of(Map.of("profile", "writer", "role_contract", Map.of("model", "old-b", "effort", "high"))));
        write(project, "f", historicalOther);
        check.eq("historical recorded models also stay separate", 5,
                rows(LedgerCompare.compare(project, null), "groups").size());

        Map<String, Object> continued = summary("g", "model-a", "high", "stopped");
        continued.put("chain", chain(List.of("b", "g")));
        write(project, "g", continued);
        List<Map<String, Object>> groups = rows(LedgerCompare.compare(project, null), "groups");
        check.eq("a chain that changed model does not collapse into its final model cohort", 5, groups.size());
        check.that("both configurations remain visible in the continued chain",
                groups.stream().anyMatch(group -> rows(group, "requested_conditions").size() == 2));

        Map<String, Object> missing = summary("h", "model-a", "high", "stopped");
        missing.put("chain", chain(List.of("absent", "h")));
        write(project, "h", missing);
        check.that("a missing chain link makes its conditions unknown",
                rows(LedgerCompare.compare(project, null), "groups").stream().anyMatch(group ->
                        Boolean.FALSE.equals(group.get("requested_conditions_known"))
                                && rows(group, "requested_conditions").size() == 2));
        Map<String, Object> privateData = new LinkedHashMap<>();
        privateData.put("goal", "PRIVATE_FROZEN_TEXT");
        privateData.put("stages", List.of());
        Map<String, Object> unexpected = summary("private", "model-a", "high", "stopped");
        unexpected.put("comparison_conditions", ComparisonConditions.recorded(privateData));
        write(project, "private", unexpected);
        check.that("unrecognised frozen fields are hashed but never printed",
                !Json.write(LedgerCompare.compare(project, null)).contains("PRIVATE_FROZEN_TEXT"));
        Map<String, Object> invalidChain = summary("invalid", "model-a", "high", "stopped");
        invalidChain.put("chain", chain(List.of("a")));
        write(project, "invalid", invalidChain);
        check.contains("an invalid chain cannot attribute a different run's configuration",
                Json.write(LedgerCompare.compare(project, null)), "invalid_chain");
        Path builds = project.resolve("builds");
        Map<String, Object> buildA = summary("a", "model-a", "high", "stopped");
        Map<String, Object> buildB = summary("b", "model-a", "high", "stopped");
        Map<String, Object> data = new LinkedHashMap<>(object(object(buildB.get("comparison_conditions")).get("data")));
        data.put("warden_build", Map.of("sha256", "1".repeat(64)));
        buildB.put("comparison_conditions", ComparisonConditions.recorded(data));
        write(builds, "a", buildA);
        write(builds, "b", buildB);
        check.eq("identical version/model/effort with another executable build is separate", 2,
                rows(LedgerCompare.compare(builds, null), "groups").size());
        data.remove("warden_build");
        buildB.put("comparison_conditions", ComparisonConditions.recorded(data));
        write(builds, "b", buildB);
        check.that("missing build identity cannot establish experimental conditions",
                rows(LedgerCompare.compare(builds, null), "groups").stream()
                        .anyMatch(group -> Boolean.FALSE.equals(group.get("requested_conditions_known"))));
    }

    private void pairingChecks(Check check, Path project) throws Exception {
        List<Map<String, Object>> baseline = new ArrayList<>(), annotations = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String id = "run-" + i;
            Map<String, Object> run = summary(id, "model-a", "high", i == 3 ? "stopped" : "accepted");
            write(project, id, run);
            if (i != 3) decision(project, id);
            annotations.add(new LinkedHashMap<>(Map.of("run_id", id, "experiment_id", "pilot", "task_pair_id", "task-" + i,
                    "variant", "warden", "repeat", 1L, "operator_minutes", 5L)));
            Map<String, Object> row = new LinkedHashMap<>(Map.of("label", "manual-frozen-v1", "completed", true,
                    "experiment_id", "pilot", "task_pair_id", "task-" + i, "variant", "manual", "repeat", 1L,
                    "risk", "medium", "base_commit", "base", "operator_minutes", 20L));
            row.put("cost_usd", i == 2 ? null : 2.0);
            row.put("elapsed_minutes", 2L);
            row.put("calls", 3L);
            baseline.add(row);
        }
        Path input = project.resolve("experiment.json");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("experiments", List.of(Map.of("id", "pilot", "variants", List.of("manual", "warden"))));
        document.put("rows", baseline);
        document.put("runs", annotations);
        Map<String, Object> report = compare(project, input, document);
        Map<String, Object> paired = object(report.get("paired_evaluation"));
        Map<String, Object> experiment = rows(paired, "experiments").get(0);
        check.eq("three explicit complete pairs can be described", true, experiment.get("comparable"));
        check.eq("the stopped Warden task remains in the denominator", 3, experiment.get("complete_pairs"));
        Map<String, Object> estimates = object(experiment.get("estimates"));
        check.eq("a stopped task lowers acceptance relative to the manual reference", -1.0 / 3,
                rows(estimates, "accepted").get(0).get("mean_delta"));
        check.eq("unknown spend is a paired unknown, never a zero", 1L,
                rows(estimates, "cost_usd").get(0).get("unknown_pairs"));
        check.eq("two priced pairs are insufficient for a cost comparison", false,
                rows(estimates, "cost_usd").get(0).get("comparable"));
        check.eq("cost delta uses the two fully priced pairs", -1.0,
                rows(estimates, "cost_usd").get(0).get("mean_delta"));
        check.eq("operator effort is distinct from elapsed and gate wait", -15.0,
                rows(estimates, "operator_minutes").get(0).get("mean_delta"));
        check.eq("Warden groups retain operator effort", 15.0,
                object(rows(report, "groups").get(0).get("operator_minutes")).get("total"));
        check.contains("text makes the pairing denominator visible", LedgerCompare.render(report), "3/3 complete pairs");

        annotations.get(2).put("task_pair_id", "task-1");
        annotations.get(2).put("repeat", 2L);
        baseline.get(2).put("task_pair_id", "task-1");
        baseline.get(2).put("repeat", 2L);
        check.eq("repeats are paired by task and repeat together", 3,
                experiment(compare(project, input, document)).get("complete_pairs"));
        baseline.get(2).put("repeat", 1L);
        check.eq("a different repeat is never silently cross-joined", false,
                experiment(compare(project, input, document)).get("comparable"));
        baseline.get(2).put("task_pair_id", "task-3");
        annotations.get(2).put("task_pair_id", "task-3");
        annotations.get(2).put("repeat", 1L);

        Files.delete(project.resolve(".warden/runs/run-1/decision.json"));
        check.contains("a pending human outcome does not count as accepted or rejected",
                Json.write(compare(project, input, document)), "outcome pending: warden");
        decision(project, "run-1");

        baseline.add(new LinkedHashMap<>(baseline.get(0)));
        report = compare(project, input, document);
        experiment = experiment(report);
        check.eq("a duplicate member blocks comparison", false, experiment.get("comparable"));
        check.eq("the duplicate pair is retained as incomplete", 3, experiment.get("pairs"));
        check.eq("only complete pairs enter descriptive estimates", 2, experiment.get("complete_pairs"));
        baseline.remove(baseline.size() - 1);

        baseline.get(0).put("base_commit", "other");
        check.contains("a different base invalidates the pair", Json.write(compare(project, input, document)), "base_commit mismatch");
        baseline.get(0).put("base_commit", "base");
        baseline.remove(2);
        check.contains("a missing arm is visible", Json.write(compare(project, input, document)), "manual: missing");

        document.put("rows", new ArrayList<>(baseline));
        write(project, "run-2", summary("run-2", "model-b", "high", "accepted"));
        check.contains("changing a variant's configuration blocks pooled comparison",
                Json.write(compare(project, input, document)), "condition drift: warden");
        annotations.get(0).put("run_id", "missing");
        check.contains("unresolved annotations are reported rather than silently dropped",
                Json.write(compare(project, input, document)), "annotate the final run");
        annotations.get(0).put("repeat", 1.5);
        check.rejects("fractional repeats cannot create accidental pairs", "positive integer",
                () -> compare(project, input, document));
        annotations.get(0).put("repeat", 1L);
        annotations.add(new LinkedHashMap<>(annotations.get(0)));
        check.rejects("one run cannot be annotated into two samples", "duplicate runs run_id",
                () -> compare(project, input, document));
        annotations.remove(annotations.size() - 1);
        baseline.get(0).put("cost_usd", -1L);
        check.rejects("negative costs are rejected", "non-negative finite", () -> compare(project, input, document));
    }

    private static Map<String, Object> experiment(Map<String, Object> report) {
        return rows(object(report.get("paired_evaluation")), "experiments").get(0);
    }

    private static Map<String, Object> compare(Path root, Path input, Map<String, Object> document) throws Exception {
        Files.writeString(input, Json.write(document));
        return LedgerCompare.compare(root, input);
    }

    private static Map<String, Object> summary(String id, String model, String effort, String outcome) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", id);
        run.put("prepare", "off");
        run.put("risk", "medium");
        run.put("diff_base_commit", "base");
        run.put("workflow", List.of(Map.of("stage", "implement", "role", "implementer")));
        run.put("comparison_conditions", ComparisonConditions.recorded(Map.of("model", model, "effort", effort,
                "warden_version", Main.VERSION, "warden_build", Map.of("sha256", "0".repeat(64)),
                "configuration_complete", true, "workflow", run.get("workflow"),
                "stages", List.of(Map.of("stage", "implement", "candidates", List.of(Map.of("profile", "writer")))))));
        run.put("steps", List.of(Map.of("stage", "implement", "profile", "writer")));
        run.put("reason", "stopped".equals(outcome) ? "budget_exhausted" : "ready_for_human");
        run.put("chain", chain(List.of(id)));
        return run;
    }

    private static Map<String, Object> chain(List<String> ids) {
        return Map.of("runs", ids, "role_runs", 2L, "cost_usd", 1.0, "unpriced_calls", 0L,
                "elapsed_seconds", 60L, "elapsed_known", true);
    }

    private static void write(Path root, String id, Map<String, Object> summary) throws Exception {
        Path dir = root.resolve(".warden/runs").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("task-run.json"), Json.write(summary));
    }

    private static void decision(Path root, String id) throws Exception {
        Files.writeString(root.resolve(".warden/runs").resolve(id).resolve("decision.json"),
                Json.write(Map.of("state", "resolved", "decision", "accept")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> value, String key) {
        return (List<Map<String, Object>>) value.get(key);
    }
}
