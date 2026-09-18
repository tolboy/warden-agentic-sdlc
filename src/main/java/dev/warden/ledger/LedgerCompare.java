package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comparative A/B report over local task-run summaries, grouped by the conditions they
 * ran under, with an optional file of manually observed tasks done without Warden.
 *
 * Pure aggregation: no model is consulted, and goal, prompt or finding text never leaves
 * the input files. A value nobody reported stays absent rather than becoming a zero.
 *
 * A continued chain is one unit of work, attributed to its last run — the run that is not
 * listed inside another run's {@code chain.runs} except as the last element. Dry runs and
 * preview rows are ignored. A file that cannot be parsed is counted under {@code skipped}
 * and marks the report {@code incomplete}; it is never guessed.
 */
public final class LedgerCompare {

    public static final long SCHEMA_VERSION = 1L;

    static final String NOTE = "Identical conditions do not prove equal task difficulty. "
            + "A stopped task is not a success. Preparation, servicing, operator wait, "
            + "stops and unknown spend remain in the denominator.";

    private static final Set<String> BASELINE_KEYS = Set.of(
            "label", "task_kind", "risk", "completed", "operator_minutes",
            "elapsed_minutes", "cost_usd", "calls", "note");
    private static final Set<String> PREPARE_MODES = Set.of("off", "auto", "always");

    private LedgerCompare() {}

    public static Map<String, Object> compare(Path projectRoot, Path baselineFile)
            throws IOException {
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Loaded> loaded = loadRuns(projectRoot, skipped);
        List<Loaded> units = foldChains(loaded);
        Map<String, Group> warden = new LinkedHashMap<>();
        for (Loaded unit : units) {
            Group group = warden.computeIfAbsent(unit.conditionKey(), ignored -> Group.warden(unit));
            group.acceptWarden(unit, physicalRuns(unit, loaded));
        }

        List<Group> baselines = new ArrayList<>();
        if (baselineFile != null) {
            Map<String, Group> byKey = new LinkedHashMap<>();
            for (Map<String, Object> row : readBaseline(baselineFile)) {
                Group group = byKey.computeIfAbsent(baselineKey(row), ignored -> Group.baseline(row));
                group.acceptBaseline(row);
            }
            baselines.addAll(byKey.values());
        }

        List<Map<String, Object>> groups = warden.values().stream()
                .sorted(Group.WARDEN_ORDER)
                .map(Group::toMap)
                .toList();
        List<Map<String, Object>> baselineGroups = baselines.stream()
                .sorted(Group.BASELINE_ORDER)
                .map(Group::toMap)
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", SCHEMA_VERSION);
        report.put("groups", groups);
        report.put("baseline_groups", baselineGroups);
        report.put("skipped", List.copyOf(skipped));
        report.put("incomplete", !skipped.isEmpty());
        report.put("note", NOTE);
        return report;
    }

    /**
     * One row per group: conditions, units, accepted/ready/stopped, fix-round mean,
     * call mean, cost known/unknown, elapsed known/unknown, operator wait.
     */
    public static String render(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        out.append("prepare  risk    workflow      roster          units  acc/rdy/stp  "
                + "fix    calls  cost              elapsed         wait       comparable\n");
        for (Object item : list(report.get("groups"))) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> group = cast(raw);
            out.append(String.format(Locale.ROOT,
                    "%-8s %-7s %-13s %-15s %5s  %-11s  %-6s %-6s %-17s %-15s %-10s %s%n",
                    clip(text(group.get("prepare")), 8),
                    clip(text(group.get("risk")), 7),
                    clip(text(group.get("workflow_sha256")), 13),
                    clip(rosterText(group.get("roster")), 15),
                    text(group.get("units")),
                    outcomesCell(object(group.get("outcomes"))),
                    metricMean(object(group.get("fix_rounds"))),
                    metricMean(object(group.get("role_runs"))),
                    costCell(object(group.get("cost_usd"))),
                    elapsedCell(object(group.get("elapsed_seconds"))),
                    waitCell(object(group.get("human_wait_millis")), "ms"),
                    comparableCell(group)));
        }

        List<Object> baselines = list(report.get("baseline_groups"));
        if (!baselines.isEmpty()) {
            out.append('\n');
            out.append("baseline label            kind     risk    units  acc/rdy/stp  "
                    + "calls  cost              elapsed         wait       comparable\n");
            for (Object item : baselines) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> group = cast(raw);
                out.append(String.format(Locale.ROOT,
                        "         %-17s %-8s %-7s %5s  %-11s  %-6s %-17s %-15s %-10s %s%n",
                        clip(text(group.get("label")), 17),
                        clip(text(group.get("task_kind")), 8),
                        clip(text(group.get("risk")), 7),
                        text(group.get("units")),
                        outcomesCell(object(group.get("outcomes"))),
                        metricMean(object(group.get("role_runs"))),
                        costCell(object(group.get("cost_usd"))),
                        elapsedCell(object(group.get("elapsed_seconds"))),
                        waitCell(object(group.get("operator_minutes")), "min"),
                        comparableCell(group)));
            }
        }

        List<Object> skipped = list(report.get("skipped"));
        if (!skipped.isEmpty() || Boolean.TRUE.equals(report.get("incomplete"))) {
            out.append('\n').append("skipped  ").append(skipped.size())
                    .append(" file(s); report incomplete\n");
            for (Object item : skipped) {
                if (item instanceof Map<?, ?> row) {
                    out.append("         ").append(row.get("run_id"))
                            .append("  ").append(row.get("path"))
                            .append("  ").append(row.get("reason")).append('\n');
                }
            }
        }
        out.append('\n').append("note     ").append(report.get("note")).append('\n');
        return out.toString();
    }

    // -------------------------------------------------------------------------- load

    private static List<Loaded> loadRuns(Path projectRoot, List<Map<String, Object>> skipped)
            throws IOException {
        Path runs = projectRoot.resolve(".warden/runs");
        List<Loaded> loaded = new ArrayList<>();
        if (!Files.isDirectory(runs)) return loaded;
        try (var directories = Files.list(runs)) {
            List<Path> ordered = directories.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path directory : ordered) {
                Path file = directory.resolve("task-run.json");
                if (!Files.isRegularFile(file)) continue;
                String runId = directory.getFileName().toString();
                String relative = ".warden/runs/" + runId + "/task-run.json";
                Map<String, Object> summary;
                try {
                    summary = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                } catch (RuntimeException | IOException unreadable) {
                    skipped.add(skip(runId, relative, "unreadable"));
                    continue;
                }
                if (isPreview(summary)) continue;
                loaded.add(new Loaded(runId, summary, readDecision(directory)));
            }
        }
        return loaded;
    }

    /**
     * A dry-run preview is not a unit of work. The flag and the reason both name it,
     * because older summaries set one and not the other.
     */
    private static boolean isPreview(Map<String, Object> summary) {
        if (Boolean.TRUE.equals(summary.get("dry_run"))) return true;
        if (Boolean.TRUE.equals(summary.get("preview"))) return true;
        return "dry_run".equals(summary.get("reason"));
    }

    private static Map<String, Object> readDecision(Path directory) {
        Path file = directory.resolve("decision.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException unreadable) {
            return null;
        }
    }

    /**
     * Keep only runs that are not listed inside another run's {@code chain.runs} except
     * as the last element. An unfinished first run that names a continuation which was
     * never written is kept: it is not listed inside a different run.
     */
    private static List<Loaded> foldChains(List<Loaded> loaded) {
        Set<String> absorbed = new HashSet<>();
        for (Loaded run : loaded) {
            List<String> chain = run.chainRuns();
            if (chain.size() <= 1) continue;
            for (int index = 0; index < chain.size() - 1; index++) {
                String id = chain.get(index);
                if (!id.equals(run.runId)) absorbed.add(id);
            }
        }
        List<Loaded> units = new ArrayList<>();
        for (Loaded run : loaded) {
            if (!absorbed.contains(run.runId)) units.add(run);
        }
        return units;
    }

    private static long physicalRuns(Loaded unit, List<Loaded> loaded) {
        Set<String> present = new HashSet<>();
        for (Loaded run : loaded) present.add(run.runId);
        long count = 0;
        for (String id : unit.chainRuns()) {
            if (present.contains(id)) count++;
        }
        return count == 0 ? 1L : count;
    }

    // ----------------------------------------------------------------------- baseline

    private static List<Map<String, Object>> readBaseline(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("ledger --baseline-file is not a file: " + file);
        }
        Map<String, Object> document;
        try {
            document = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException(
                    "ledger --baseline-file is not valid JSON: " + unreadable.getMessage(),
                    unreadable);
        }
        for (String key : document.keySet()) {
            if (!"rows".equals(key)) {
                throw new IllegalArgumentException(
                        "ledger --baseline-file refuses unknown key '" + key + "'");
            }
        }
        Object rows = document.get("rows");
        if (!(rows instanceof List<?> list)) {
            throw new IllegalArgumentException("ledger --baseline-file must have a rows array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException(
                        "ledger --baseline-file rows[" + index + "] must be an object");
            }
            result.add(validateBaselineRow(cast(raw), index));
            index++;
        }
        return result;
    }

    private static Map<String, Object> validateBaselineRow(Map<String, Object> row, int index) {
        for (String key : row.keySet()) {
            if (!BASELINE_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "ledger --baseline-file rows[" + index + "] refuses unknown key '"
                                + key + "'");
            }
        }
        if (!(row.get("completed") instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "ledger --baseline-file rows[" + index + "] completed must be a boolean");
        }
        requireStringOrNull(row, index, "label");
        requireStringOrNull(row, index, "task_kind");
        requireStringOrNull(row, index, "risk");
        requireStringOrNull(row, index, "note");
        requireNumberOrNull(row, index, "operator_minutes");
        requireNumberOrNull(row, index, "elapsed_minutes");
        requireNumberOrNull(row, index, "cost_usd");
        requireNumberOrNull(row, index, "calls");
        if (!(row.get("label") instanceof String label) || label.isBlank()) {
            throw new IllegalArgumentException(
                    "ledger --baseline-file rows[" + index + "] label must be a non-blank string");
        }
        return row;
    }

    private static void requireStringOrNull(Map<String, Object> row, int index, String key) {
        Object value = row.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("ledger --baseline-file rows[" + index + "] "
                    + key + " must be a string or null");
        }
    }

    private static void requireNumberOrNull(Map<String, Object> row, int index, String key) {
        Object value = row.get(key);
        if (value != null && !(value instanceof Number)) {
            throw new IllegalArgumentException("ledger --baseline-file rows[" + index + "] "
                    + key + " must be a number or null");
        }
    }

    private static String baselineKey(Map<String, Object> row) {
        return text(row.get("label")) + "\0" + text(row.get("task_kind")) + "\0"
                + text(row.get("risk"));
    }

    // -------------------------------------------------------------------------- group

    private static final class Group {
        private final String source;
        private final String prepare;
        private final String risk;
        private final String workflowSha;
        private final List<String> roster;
        private final String label;
        private final String taskKind;
        private long units;
        private long runs;
        private final Map<String, Long> reasons = new LinkedHashMap<>();
        private long ready;
        private long accepted;
        private long rejected;
        private long stopped;
        private final Metric fixRounds = Metric.integer();
        private final Metric roleRuns = Metric.integer();
        private final Metric cost = Metric.decimal();
        private long unpricedCalls;
        private final Metric elapsed = Metric.integer();
        private final Metric wait = Metric.integer();
        private final Metric operatorMinutes = Metric.decimal();
        private final Metric blockers = Metric.integer();
        private final Metric reviewPassed = Metric.integer();

        static final Comparator<Group> WARDEN_ORDER = Comparator
                .comparing((Group group) -> group.prepare)
                .thenComparing(group -> group.risk)
                .thenComparing(group -> group.workflowSha)
                .thenComparing(group -> String.join(",", group.roster));

        static final Comparator<Group> BASELINE_ORDER = Comparator
                .comparing((Group group) -> group.label)
                .thenComparing(group -> text(group.taskKind))
                .thenComparing(group -> group.risk);

        private Group(String source, String prepare, String risk, String workflowSha,
                      List<String> roster, String label, String taskKind) {
            this.source = source;
            this.prepare = prepare;
            this.risk = risk;
            this.workflowSha = workflowSha;
            this.roster = List.copyOf(roster);
            this.label = label;
            this.taskKind = taskKind;
        }

        static Group warden(Loaded unit) {
            return new Group("warden", unit.prepare(), unit.risk(), unit.workflowSha(),
                    unit.roster(), null, null);
        }

        static Group baseline(Map<String, Object> row) {
            return new Group("manual_baseline", null,
                    row.get("risk") instanceof String text ? text : "unknown",
                    null, List.of(),
                    String.valueOf(row.get("label")),
                    row.get("task_kind") instanceof String text ? text : null);
        }

        void acceptWarden(Loaded unit, long physicalRuns) {
            units++;
            runs += physicalRuns;
            String reason = unit.reason();
            reasons.merge(reason, 1L, Long::sum);
            String bucket = unit.outcomeBucket();
            switch (bucket) {
                case "accepted" -> accepted++;
                case "rejected" -> rejected++;
                case "ready_for_human" -> ready++;
                default -> stopped++;
            }
            fixRounds.accept(unit.fixRounds());
            roleRuns.accept(unit.roleRuns());
            Object unpriced = unit.unpricedCalls();
            if (unpriced instanceof Number number && number.longValue() > 0) {
                cost.addUnknown();
                unpricedCalls += number.longValue();
            } else {
                cost.accept(unit.costUsd());
            }
            elapsed.accept(unit.elapsedSeconds());
            wait.accept(unit.waitMillis());
            blockers.accept(unit.openBlockingFindings());
            Object passed = unit.candidateReviewPassed();
            if (passed instanceof Boolean value) reviewPassed.accept(value ? 1L : 0L);
            else reviewPassed.addUnknown();
        }

        void acceptBaseline(Map<String, Object> row) {
            units++;
            runs++;
            if (Boolean.TRUE.equals(row.get("completed"))) {
                accepted++;
                reasons.merge("completed", 1L, Long::sum);
            } else {
                stopped++;
                reasons.merge("not_completed", 1L, Long::sum);
            }
            operatorMinutes.accept(row.get("operator_minutes"));
            Object minutes = row.get("elapsed_minutes");
            if (minutes instanceof Number number && Double.isFinite(number.doubleValue())) {
                elapsed.accept(Math.round(number.doubleValue() * 60.0d));
            } else {
                elapsed.addUnknown();
            }
            cost.accept(row.get("cost_usd"));
            roleRuns.accept(row.get("calls"));
        }

        Map<String, Object> toMap() {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("source", source);
            if ("manual_baseline".equals(source)) {
                group.put("label", label);
                group.put("task_kind", taskKind);
                group.put("risk", risk);
            } else {
                group.put("prepare", prepare);
                group.put("risk", risk);
                group.put("workflow_sha256", workflowSha);
                group.put("roster", roster);
            }
            group.put("units", units);
            group.put("runs", runs);
            group.put("sample_size", units);
            boolean comparable = units >= 3;
            group.put("comparable", comparable);
            if (!comparable) group.put("comparable_reason", "fewer than 3 units");
            Map<String, Object> outcomes = new LinkedHashMap<>();
            outcomes.put("reasons", new LinkedHashMap<>(reasons));
            outcomes.put("ready_for_human", ready);
            outcomes.put("accepted", accepted);
            outcomes.put("rejected", rejected);
            outcomes.put("stopped", stopped);
            group.put("outcomes", outcomes);
            if ("manual_baseline".equals(source)) {
                group.put("role_runs", roleRuns.toMap());
                Map<String, Object> costMap = cost.toMap();
                costMap.put("unpriced_calls", unpricedCalls);
                group.put("cost_usd", costMap);
                group.put("elapsed_seconds", elapsed.toMap());
                group.put("operator_minutes", operatorMinutes.toMap());
            } else {
                group.put("fix_rounds", fixRounds.toMap());
                group.put("role_runs", roleRuns.toMap());
                Map<String, Object> costMap = cost.toMap();
                costMap.put("unpriced_calls", unpricedCalls);
                group.put("cost_usd", costMap);
                group.put("elapsed_seconds", elapsed.toMap());
                group.put("human_wait_millis", wait.toMap());
                group.put("open_blocking_findings", blockers.toMap());
                group.put("candidate_review_passed", reviewPassed.toMap());
            }
            return group;
        }
    }

    private static final class Metric {
        private final boolean decimal;
        private long known;
        private long unknown;
        private double total;

        private Metric(boolean decimal) { this.decimal = decimal; }

        static Metric integer() { return new Metric(false); }
        static Metric decimal() { return new Metric(true); }

        void addUnknown() { unknown++; }

        void accept(Object value) {
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                known++;
                total += number.doubleValue();
            } else {
                unknown++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("known_count", known);
            result.put("unknown_count", unknown);
            if (known == 0) {
                result.put("total", null);
                result.put("mean", null);
            } else if (decimal) {
                result.put("total", total);
                result.put("mean", total / known);
            } else {
                result.put("total", Math.round(total));
                result.put("mean", total / known);
            }
            return result;
        }
    }

    // --------------------------------------------------------------------------- run

    private static final class Loaded {
        final String runId;
        final Map<String, Object> summary;
        final Map<String, Object> decision;

        Loaded(String runId, Map<String, Object> summary, Map<String, Object> decision) {
            this.runId = runId;
            this.summary = summary;
            this.decision = decision;
        }

        String conditionKey() {
            return prepare() + "\0" + risk() + "\0" + workflowSha() + "\0"
                    + String.join(",", roster());
        }

        String prepare() {
            Object value = summary.get("prepare");
            if (value instanceof String text && PREPARE_MODES.contains(text)) return text;
            return "unknown";
        }

        String risk() {
            return summary.get("risk") instanceof String text && !text.isBlank() ? text : "unknown";
        }

        String workflowSha() {
            Object workflow = summary.get("workflow");
            if (!(workflow instanceof List<?>)) return "unknown";
            return sha256(Json.write(workflow));
        }

        List<String> roster() {
            Object steps = summary.get("steps");
            if (!(steps instanceof List<?> rows)) return List.of();
            TreeSet<String> names = new TreeSet<>();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) continue;
                if (Boolean.TRUE.equals(row.get("dry_run"))) continue;
                Object profile = row.get("profile");
                if (profile instanceof String text && !text.isBlank()) names.add(text);
            }
            return List.copyOf(names);
        }

        List<String> chainRuns() {
            Object chain = summary.get("chain");
            if (chain instanceof Map<?, ?> map && map.get("runs") instanceof List<?> rows) {
                List<String> ids = new ArrayList<>();
                for (Object item : rows) {
                    if (item instanceof String text && !text.isBlank()) ids.add(text);
                }
                if (!ids.isEmpty()) return ids;
            }
            return List.of(runId);
        }

        Map<String, Object> chain() {
            return summary.get("chain") instanceof Map<?, ?> map ? cast(map) : Map.of();
        }

        String reason() {
            Object reason = summary.get("reason");
            return reason instanceof String text && !text.isBlank() ? text : "unknown";
        }

        /**
         * Mutually exclusive buckets so accepted/ready/stopped sum to units. A resolved
         * accept is a success even though the summary reason is still {@code ready_for_human}.
         * Everything else that did not reach a pending human gate is stopped — including
         * a resolved reject. A stopped chain is not a success.
         */
        String outcomeBucket() {
            if (resolvedDecision("accept")) return "accepted";
            if (resolvedDecision("reject")) return "rejected";
            if ("ready_for_human".equals(reason())) return "ready_for_human";
            return "stopped";
        }

        boolean resolvedDecision(String expected) {
            if (decision == null) return false;
            if (!"resolved".equals(decision.get("state"))) return false;
            return expected.equals(decision.get("decision"));
        }

        Object fixRounds() {
            Object chainValue = chain().get("fix_attempts");
            if (chainValue instanceof Number) return chainValue;
            Object used = summary.get("attempts_used");
            return used instanceof Number ? used : null;
        }

        Object roleRuns() {
            Object value = chain().get("role_runs");
            return value instanceof Number ? value : null;
        }

        Object costUsd() {
            Object value = chain().get("cost_usd");
            return value instanceof Number ? value : null;
        }

        Object unpricedCalls() {
            return chain().get("unpriced_calls");
        }

        Object elapsedSeconds() {
            Map<String, Object> chain = chain();
            if (Boolean.FALSE.equals(chain.get("elapsed_known"))) return null;
            Object value = chain.get("elapsed_seconds");
            return value instanceof Number ? value : null;
        }

        Object waitMillis() {
            if (decision == null) return null;
            if (!"resolved".equals(decision.get("state"))) return null;
            if (!(decision.get("created_at") instanceof String created)) return null;
            if (!(decision.get("updated_at") instanceof String updated)) return null;
            try {
                long millis = Duration.between(Instant.parse(created), Instant.parse(updated))
                        .toMillis();
                return millis < 0 ? null : millis;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        Object openBlockingFindings() {
            Object value = summary.get("open_blocking_findings");
            return value instanceof Number ? value : null;
        }

        Object candidateReviewPassed() {
            return summary.get("candidate_review_passed") instanceof Boolean value ? value : null;
        }
    }

    // --------------------------------------------------------------------------- text

    private static String outcomesCell(Map<String, Object> outcomes) {
        if (outcomes.isEmpty()) return "?/?/?";
        return text(outcomes.get("accepted")) + "/" + text(outcomes.get("ready_for_human"))
                + "/" + text(outcomes.get("stopped"));
    }

    private static String metricMean(Map<String, Object> metric) {
        if (metric.isEmpty() || !(metric.get("known_count") instanceof Number known)
                || known.longValue() == 0) {
            return "?";
        }
        return compact(metric.get("mean"));
    }

    private static String costCell(Map<String, Object> metric) {
        if (metric.isEmpty()) return "?";
        long unknown = number(metric.get("unknown_count"));
        String known = metric.get("total") instanceof Number
                ? String.format(Locale.ROOT, "%.4f", ((Number) metric.get("total")).doubleValue())
                : "?";
        return known + " (" + unknown + " unk)";
    }

    private static String elapsedCell(Map<String, Object> metric) {
        if (metric.isEmpty() || !(metric.get("known_count") instanceof Number known)
                || known.longValue() == 0) {
            return "? (" + number(metric.get("unknown_count")) + " unk)";
        }
        return compact(metric.get("mean")) + "s (" + number(metric.get("unknown_count")) + " unk)";
    }

    private static String waitCell(Map<String, Object> metric, String unit) {
        if (metric.isEmpty() || !(metric.get("known_count") instanceof Number known)
                || known.longValue() == 0) {
            return "?";
        }
        return compact(metric.get("mean")) + unit;
    }

    private static String comparableCell(Map<String, Object> group) {
        if (Boolean.TRUE.equals(group.get("comparable"))) return "yes";
        Object reason = group.get("comparable_reason");
        return reason == null ? "no" : "no (" + reason + ")";
    }

    private static String rosterText(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty()) return "-";
        List<String> names = new ArrayList<>();
        for (Object item : rows) names.add(String.valueOf(item));
        return String.join(",", names);
    }

    private static String compact(Object value) {
        if (!(value instanceof Number number)) return "?";
        double d = number.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String clip(String value, int width) {
        if (value == null) return "-";
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }

    private static Map<String, Object> skip(String runId, String path, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("path", path);
        row.put("reason", reason);
        return row;
    }

    private static String sha256(String material) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static long number(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> rows ? List.copyOf(rows) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
