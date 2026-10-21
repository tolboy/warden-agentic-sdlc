package dev.warden.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit task/repeat pairing. Never matches tasks by title, outcome, or cost. */
final class PairedEvaluation {
    private static final Set<String> META = Set.of("experiment_id", "task_pair_id", "variant", "repeat");
    private static final Set<String> ANNOTATION_KEYS = Set.of("run_id", "experiment_id", "task_pair_id",
            "variant", "repeat", "operator_minutes");
    private PairedEvaluation() {}

    static void validateMetadata(Map<String, Object> row, String location) {
        if (META.stream().noneMatch(row::containsKey)) return;
        for (String key : List.of("experiment_id", "task_pair_id", "variant")) {
            if (!(row.get(key) instanceof String value) || value.isBlank())
                throw invalid(location + " " + key + " must be a non-blank string for a paired row");
        }
        if (!(row.get("repeat") instanceof Number value) || !Double.isFinite(value.doubleValue())
                || value.doubleValue() < 1 || value.doubleValue() != value.longValue())
            throw invalid(location + " repeat must be a positive integer");
        if (row.containsKey("base_commit") && (!(row.get("base_commit") instanceof String base) || base.isBlank()))
            throw invalid(location + " base_commit must be a non-blank string");
    }

    static Map<String, Map<String, Object>> annotations(Map<String, Object> input) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : objects(input, "runs")) {
            for (String key : row.keySet()) if (!ANNOTATION_KEYS.contains(key))
                throw invalid("runs refuses unknown key '" + key + "'");
            if (!(row.get("run_id") instanceof String id) || id.isBlank())
                throw invalid("runs run_id must be a non-blank string");
            validateMetadata(row, "runs " + id);
            Object minutes = row.get("operator_minutes");
            if (minutes != null && (!(minutes instanceof Number number)
                    || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0))
                throw invalid("runs operator_minutes must be a non-negative finite number or null");
            if (result.putIfAbsent(id, row) != null) throw invalid("duplicate runs run_id '" + id + "'");
        }
        return result;
    }

    static Map<String, Object> report(Map<String, Object> input, List<Map<String, Object>> units,
            List<Map<String, Object>> baseline, Map<String, Map<String, Object>> annotations, boolean sourceIncomplete) {
        Map<String, List<String>> variants = new LinkedHashMap<>();
        for (Map<String, Object> experiment : objects(input, "experiments")) {
            if (!Set.of("id", "variants").containsAll(experiment.keySet())) throw invalid("experiments refuses unknown keys");
            if (!(experiment.get("id") instanceof String id) || id.isBlank()) throw invalid("experiments id must be a non-blank string");
            if (!(experiment.get("variants") instanceof List<?> names) || names.size() < 2
                    || names.stream().anyMatch(name -> !(name instanceof String text) || text.isBlank())
                    || new LinkedHashSet<>(names).size() != names.size())
                throw invalid("experiments variants must contain at least two distinct non-blank strings");
            if (variants.putIfAbsent(id, names.stream().map(String::valueOf).toList()) != null)
                throw invalid("duplicate experiment '" + id + "'");
        }
        List<String> issues = new ArrayList<>();
        if (sourceIncomplete) issues.add("unreadable run summaries; source report incomplete");
        List<Map<String, Object>> members = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        long unpairedWarden = 0, unpairedBaseline = 0;
        for (Map<String, Object> unit : units) {
            String id = String.valueOf(unit.get("run_id"));
            present.add(id);
            Map<String, Object> annotation = annotations.getOrDefault(id, Map.of());
            if (!annotation.containsKey("experiment_id")) { unpairedWarden++; continue; }
            Map<String, Object> member = new LinkedHashMap<>(unit);
            member.putAll(annotation);
            members.add(member);
        }
        for (String id : annotations.keySet()) if (!present.contains(id))
            issues.add("run '" + id + "' is missing, a preview, or an absorbed chain link; annotate the final run");
        for (Map<String, Object> row : baseline) {
            if (!row.containsKey("experiment_id")) { unpairedBaseline++; continue; }
            Map<String, Object> member = new LinkedHashMap<>();
            for (String key : List.of("experiment_id", "task_pair_id", "variant", "repeat", "base_commit",
                    "risk", "operator_minutes", "cost_usd", "calls")) member.put(key, row.get(key));
            member.put("source", "manual_baseline");
            member.put("outcome", Boolean.TRUE.equals(row.get("completed")) ? "accepted" : "stopped");
            member.put("elapsed_seconds", row.get("elapsed_minutes") instanceof Number n ? n.doubleValue() * 60 : null);
            // The operator's frozen baseline condition label; never inferred from success.
            member.put("condition_id", "manual:" + row.get("label"));
            member.put("requested_conditions_known", true);
            members.add(member);
        }
        Map<String, List<Map<String, Object>>> byPair = new TreeMap<>();
        for (Map<String, Object> member : members) {
            String experiment = String.valueOf(member.get("experiment_id"));
            if (!variants.containsKey(experiment)) {
                issues.add("undeclared experiment '" + experiment + "'");
                continue;
            }
            String key = dev.warden.json.Json.write(List.of(experiment, member.get("task_pair_id"),
                    ((Number) member.get("repeat")).longValue()));
            byPair.computeIfAbsent(key, ignored -> new ArrayList<>()).add(member);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        Map<String, Map<String, Set<String>>> conditions = new LinkedHashMap<>();
        for (List<Map<String, Object>> rows : byPair.values()) {
            Map<String, Object> first = rows.get(0);
            String experiment = String.valueOf(first.get("experiment_id"));
            List<String> expected = variants.get(experiment);
            List<String> problems = new ArrayList<>();
            for (String variant : expected) {
                long count = rows.stream().filter(row -> variant.equals(row.get("variant"))).count();
                if (count != 1) problems.add(variant + ": " + (count == 0 ? "missing" : "duplicate"));
            }
            Set<String> bases = new LinkedHashSet<>(), risks = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String variant = String.valueOf(row.get("variant"));
                if (!expected.contains(variant)) problems.add("undeclared variant: " + variant);
                Object base = row.get("base_commit");
                if (!(base instanceof String text) || text.isBlank()) problems.add("base_commit unknown: " + variant);
                else bases.add((String) base);
                Object risk = row.get("risk");
                if (!(risk instanceof String text) || text.isBlank() || "unknown".equals(text))
                    problems.add("risk unknown: " + variant);
                else risks.add((String) risk);
                if (!Boolean.TRUE.equals(row.get("requested_conditions_known"))) problems.add("requested conditions unknown: " + variant);
                if ("ready_for_human".equals(row.get("outcome"))) problems.add("outcome pending: " + variant);
                conditions.computeIfAbsent(experiment, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(variant, ignored -> new LinkedHashSet<>())
                        .add(String.valueOf(row.get("condition_id")));
            }
            if (bases.size() > 1) problems.add("base_commit mismatch");
            if (risks.size() > 1) problems.add("risk mismatch");
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("experiment_id", experiment);
            pair.put("task_pair_id", first.get("task_pair_id"));
            pair.put("repeat", first.get("repeat"));
            pair.put("complete", problems.isEmpty());
            pair.put("problems", problems);
            pair.put("members", rows);
            pairs.add(pair);
        }
        List<Map<String, Object>> experiments = new ArrayList<>();
        for (var experiment : variants.entrySet()) {
            String id = experiment.getKey();
            List<Map<String, Object>> own = pairs.stream().filter(pair -> id.equals(pair.get("experiment_id"))).toList();
            List<Map<String, Object>> complete = own.stream().filter(pair -> Boolean.TRUE.equals(pair.get("complete"))).toList();
            List<String> problems = new ArrayList<>();
            if (complete.size() < 3) problems.add("fewer than 3 complete pairs");
            if (own.size() != complete.size()) problems.add("incomplete pairs");
            if (!issues.isEmpty()) problems.add("unresolved input issues");
            Map<String, Set<String>> observed = conditions.getOrDefault(id, Map.of());
            for (String variant : experiment.getValue()) {
                if (observed.getOrDefault(variant, Set.of()).size() > 1) problems.add("condition drift: " + variant);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("experiment_id", id);
            result.put("variants", experiment.getValue());
            result.put("pairs", own.size());
            result.put("complete_pairs", complete.size());
            result.put("comparable", problems.isEmpty());
            result.put("problems", problems);
            // Retain every pair in the report, and state the denominator used by estimates.
            result.put("estimates", estimates(complete, experiment.getValue(), problems.isEmpty()));
            experiments.add(result);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("experiments", experiments);
        report.put("pairs", pairs);
        report.put("issues", issues);
        report.put("unpaired_warden_units", unpairedWarden);
        report.put("unpaired_baseline_rows", unpairedBaseline);
        report.put("incomplete", !issues.isEmpty() || pairs.stream().anyMatch(pair -> !Boolean.TRUE.equals(pair.get("complete"))));
        return report;
    }

    private static Map<String, Object> estimates(List<Map<String, Object>> pairs, List<String> variants, boolean eligible) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String metric : List.of("cost_usd", "elapsed_seconds", "operator_minutes", "calls", "accepted")) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (String variant : variants.subList(1, variants.size())) {
                long known = 0, unknown = 0;
                double total = 0;
                for (Map<String, Object> pair : pairs) {
                    List<Map<String, Object>> rows = objects(pair, "members");
                    Map<String, Object> left = rows.stream().filter(row -> variants.get(0).equals(row.get("variant"))).findFirst().orElseThrow();
                    Map<String, Object> right = rows.stream().filter(row -> variant.equals(row.get("variant"))).findFirst().orElseThrow();
                    Double a = metric(left, metric), b = metric(right, metric);
                    if (a == null || b == null) unknown++;
                    else { known++; total += b - a; }
                }
                Map<String, Object> estimate = new LinkedHashMap<>();
                estimate.put("variant", variant);
                estimate.put("reference", variants.get(0));
                estimate.put("known_pairs", known);
                estimate.put("unknown_pairs", unknown);
                estimate.put("comparable", eligible && known >= 3);
                estimate.put("mean_delta", known == 0 ? null : total / known);
                values.add(estimate);
            }
            result.put(metric, values);
        }
        return result;
    }

    private static Double metric(Map<String, Object> row, String key) {
        if ("accepted".equals(key)) return "accepted".equals(row.get("outcome")) ? 1.0 : 0.0;
        Object value = row.get(key);
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 ? number.doubleValue() : null;
    }

    static String render(Map<String, Object> report) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> experiment : objects(report, "experiments")) {
            text.append("\npaired   ").append(experiment.get("experiment_id")).append("  ")
                    .append(experiment.get("complete_pairs")).append('/').append(experiment.get("pairs"))
                    .append(" complete pairs; comparable=").append(experiment.get("comparable"))
                    .append("  ").append(experiment.get("problems")).append('\n');
        }
        for (Object issue : list(report.get("issues"))) text.append("eval     ").append(issue).append('\n');
        return text.toString();
    }

    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> input, String key) {
        Object value = input.getOrDefault(key, List.of());
        if (!(value instanceof List<?> rows)) throw invalid(key + " must be an array");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) throw invalid(key + " must contain objects");
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("ledger --baseline-file " + message);
    }
}
