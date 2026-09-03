package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates append-only run evidence without mutating or trusting model output. */
public final class LedgerReader {
    private static final Set<String> CONFIGURATION_FAILURES = Set.of(
            "authority_denied",
            "configuration_changed",
            "role_local_api_key_missing",
            "role_local_endpoint_unreachable",
            "role_orca_no_coordinator",
            "role_orca_no_worktree",
            "role_orca_unavailable",
            "role_prompt_undeliverable",
            "role_runner_unimplemented",
            "role_unresolved");

    public Map<String, Object> summarize(Path projectRoot) throws IOException {
        Path runs = projectRoot.resolve(".warden/runs");
        List<Map<String, Object>> runRows = new ArrayList<>();
        Metrics metrics = new Metrics();
        long passed = 0;
        long failed = 0;
        if (Files.isDirectory(runs)) {
            try (var directories = Files.list(runs)) {
                for (Path run : directories.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
                    List<Map<String, Object>> events = readEvents(run.resolve("evidence.jsonl"));
                    String verdict = "no_evidence";
                    for (Map<String, Object> event : events) {
                        if (event.get("ok") instanceof Boolean ok) verdict = ok ? "passed" : "failed";
                    }
                    if (verdict.equals("passed")) passed++;
                    else if (verdict.equals("failed")) failed++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("run_id", run.getFileName().toString());
                    row.put("verdict", verdict);
                    row.put("events", (long) events.size());
                    runRows.add(row);
                    metrics.accept(events);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        // Keep the original contract intact. Metrics are additive, so existing consumers of
        // schema version 1 and the legacy fields continue to work unchanged.
        summary.put("schema_version", 1L);
        summary.put("run_count", (long) runRows.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("runs", runRows);
        summary.put("metrics", metrics.toMap());
        summary.put("future_dimensions", List.of("vendor", "model", "duration_ms", "cost_usd",
                "fix_attempts", "review_findings", "human_decision"));
        return summary;
    }

    private List<Map<String, Object>> readEvents(Path path) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        if (!Files.isRegularFile(path)) return events;
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) events.add(Json.parseObject(line));
        }
        return events;
    }

    private static final class Metrics {
        private long roleRuns;
        private final Map<String, Long> byRole = new LinkedHashMap<>();
        private final Map<String, Long> byVendor = new LinkedHashMap<>();
        private final Map<String, Long> byModel = new LinkedHashMap<>();
        private final Map<String, Long> byRunner = new LinkedHashMap<>();
        private final Map<String, Long> byOutcome = new LinkedHashMap<>();
        private final DecimalMetric cost = new DecimalMetric();
        private final IntegerMetric inputTokens = new IntegerMetric();
        private final IntegerMetric outputTokens = new IntegerMetric();
        private final IntegerMetric totalTokens = new IntegerMetric();
        private final IntegerMetric durationMillis = new IntegerMetric();
        private long failovers;
        private long failoverRoleRuns;
        private long fixRounds;
        private long fixRoundsUnknown;
        private long machineGateFailures;
        private long visualHarnessFailures;
        private long visualRoleFailures;
        private long quotaFailures;
        private long configurationFailures;
        private long humanDecisions;
        private final Map<String, Long> decisionsByOutcome = new LinkedHashMap<>();
        private long humanWaitEvents;
        private final IntegerMetric humanWaitMillis = new IntegerMetric();

        void accept(List<Map<String, Object>> events) {
            long explicitQuotaEvents = events.stream()
                    .filter(event -> "role_quota_exhausted".equals(text(event.get("type"))))
                    .count();
            quotaFailures += explicitQuotaEvents;
            long fallbackQuotaFailures = 0;

            for (Map<String, Object> event : events) {
                String type = text(event.get("type"));
                String code = text(event.get("code"));
                if ("role_run".equals(type)) {
                    acceptRoleRun(event);
                    if ("role_quota_exhausted".equals(code)) fallbackQuotaFailures++;
                    if (isConfigurationFailure(code)) configurationFailures++;
                    if ("visual_qa".equals(text(event.get("role")))
                            && Boolean.FALSE.equals(event.get("ok"))) visualRoleFailures++;
                } else if (isConfigurationFailure(type) || isConfigurationFailure(code)) {
                    // task_run repeats the final reason already recorded by the stage that
                    // failed. Counting it again would double every configuration failure.
                    if (!"task_run".equals(type)) configurationFailures++;
                }

                if ("machine_gate".equals(type) && Boolean.FALSE.equals(event.get("ok"))) {
                    machineGateFailures++;
                }
                if ("visual_qa".equals(type) && Boolean.FALSE.equals(event.get("ok"))) {
                    visualHarnessFailures++;
                }
                if ("task_run".equals(type)) acceptTaskRun(event);
                acceptHumanEvent(event, type);
            }
            // New ledgers have a dedicated event per refusal. This fallback keeps metrics
            // useful for older ledgers that only recorded the final role_run code.
            if (explicitQuotaEvents == 0) quotaFailures += fallbackQuotaFailures;
        }

        @SuppressWarnings("unchecked")
        private void acceptRoleRun(Map<String, Object> event) {
            roleRuns++;
            increment(byRole, dimension(event.get("role")));
            increment(byVendor, dimension(event.get("vendor")));
            increment(byModel, dimension(event.get("model")));
            increment(byRunner, dimension(event.get("runner")));
            Object outcome = event.get("code");
            if (outcome == null && event.get("ok") instanceof Boolean ok) {
                outcome = ok ? "passed" : "failed";
            }
            increment(byOutcome, dimension(outcome));

            cost.accept(event.get("cost_usd"));
            durationMillis.accept(firstPresent(event, "duration_millis", "duration_ms"));
            Object tokenBody = event.get("tokens");
            Map<String, Object> tokens = tokenBody instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            inputTokens.accept(firstPresent(tokens, "input", "input_tokens", "inputTokens"));
            outputTokens.accept(firstPresent(tokens, "output", "output_tokens", "outputTokens"));
            totalTokens.accept(firstPresent(tokens, "total", "total_tokens", "totalTokens"));

            long transitions = 0;
            if (event.get("vendor_attempts") instanceof List<?> attempts && attempts.size() > 1) {
                transitions = attempts.size() - 1L;
            }
            if (event.get("failed_over_from") instanceof List<?> prior) {
                transitions = Math.max(transitions, prior.size());
            }
            if (transitions > 0) {
                failoverRoleRuns++;
                failovers += transitions;
            }
        }

        private void acceptTaskRun(Map<String, Object> event) {
            Object attempts = event.get("attempts_used");
            if (attempts instanceof Number number && number.longValue() >= 0) {
                fixRounds += number.longValue();
            } else {
                fixRoundsUnknown++;
            }
        }

        private void acceptHumanEvent(Map<String, Object> event, String type) {
            boolean humanType = type != null
                    && (type.startsWith("human_") || type.startsWith("approval_"));
            String decision = firstText(event, "human_decision", "decision", "approval");
            if (decision == null && humanType) decision = firstText(event, "outcome");
            boolean decisionEvent = "human_decision".equals(type)
                    || "approval_decision".equals(type)
                    || event.get("human_decision") != null;
            if (decisionEvent) {
                humanDecisions++;
                increment(decisionsByOutcome, dimension(decision));
            }

            Object wait = firstPresent(event, "human_wait_millis", "wait_millis", "wait_ms");
            if (wait == null) wait = elapsedBetween(event);
            boolean waitEvent = event.get("human_wait_millis") != null
                    || (humanType && (type.contains("wait") || (decisionEvent && wait != null)));
            if (waitEvent) {
                humanWaitEvents++;
                humanWaitMillis.accept(wait);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("total", roleRuns);
            role.put("by_role", byRole);
            role.put("by_vendor", byVendor);
            role.put("by_model", byModel);
            role.put("by_runner", byRunner);
            role.put("by_outcome", byOutcome);

            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("input", inputTokens.toMap());
            tokens.put("output", outputTokens.toMap());
            tokens.put("total", totalTokens.toMap());

            Map<String, Object> telemetry = new LinkedHashMap<>();
            telemetry.put("cost_usd", cost.toMap());
            telemetry.put("tokens", tokens);
            telemetry.put("duration_millis", durationMillis.toMapWithPercentiles());

            Map<String, Object> iterations = new LinkedHashMap<>();
            iterations.put("failovers", failovers);
            iterations.put("failover_role_runs", failoverRoleRuns);
            iterations.put("fix_rounds", fixRounds);
            iterations.put("fix_rounds_unknown_runs", fixRoundsUnknown);

            Map<String, Object> failures = new LinkedHashMap<>();
            failures.put("machine_gate", machineGateFailures);
            failures.put("visual_qa", visualHarnessFailures + visualRoleFailures);
            failures.put("visual_harness", visualHarnessFailures);
            failures.put("visual_role", visualRoleFailures);
            failures.put("quota", quotaFailures);
            failures.put("configuration", configurationFailures);

            Map<String, Object> human = new LinkedHashMap<>();
            human.put("decisions", humanDecisions);
            human.put("by_decision", decisionsByOutcome);
            human.put("wait_events", humanWaitEvents);
            human.put("wait_millis", humanWaitMillis.toMapWithPercentiles());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema_version", 1L);
            result.put("role_runs", role);
            result.put("telemetry", telemetry);
            result.put("iterations", iterations);
            result.put("failures", failures);
            result.put("human", human);
            return result;
        }
    }

    private static final class DecimalMetric {
        private long known;
        private long unknown;
        private double total;

        void accept(Object value) {
            if (value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0) {
                known++;
                total += number.doubleValue();
            } else {
                unknown++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = counts(known, unknown);
            result.put("total", known == 0 ? null : total);
            return result;
        }
    }

    private static final class IntegerMetric {
        private long known;
        private long unknown;
        private long total;
        private final List<Long> samples = new ArrayList<>();

        void accept(Object value) {
            if (value instanceof Number number && number.longValue() >= 0) {
                known++;
                total += number.longValue();
                samples.add(number.longValue());
            } else {
                unknown++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = counts(known, unknown);
            result.put("total", known == 0 ? null : total);
            return result;
        }

        Map<String, Object> toMapWithPercentiles() {
            Map<String, Object> result = toMap();
            result.put("p50", percentile(50));
            result.put("p95", percentile(95));
            return result;
        }

        private Long percentile(int percentage) {
            if (samples.isEmpty()) return null;
            List<Long> sorted = samples.stream().sorted().toList();
            int rank = Math.max(1, (int) Math.ceil(percentage / 100.0 * sorted.size()));
            return sorted.get(rank - 1);
        }
    }

    private static Map<String, Object> counts(long known, long unknown) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("known_count", known);
        result.put("unknown_count", unknown);
        return result;
    }

    private static Object firstPresent(Map<String, Object> map, String... names) {
        for (String name : names) {
            if (map.get(name) != null) return map.get(name);
        }
        return null;
    }

    private static String firstText(Map<String, Object> map, String... names) {
        Object value = firstPresent(map, names);
        return value == null ? null : String.valueOf(value);
    }

    private static Long elapsedBetween(Map<String, Object> event) {
        String start = firstText(event, "requested_at", "started_at");
        String end = firstText(event, "decided_at", "completed_at");
        if (start == null || end == null) return null;
        try {
            long millis = Duration.between(Instant.parse(start), Instant.parse(end)).toMillis();
            return millis < 0 ? null : millis;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isConfigurationFailure(String code) {
        if (code == null) return false;
        return CONFIGURATION_FAILURES.contains(code)
                || code.startsWith("config_")
                || code.startsWith("configuration_");
    }

    private static String dimension(Object value) {
        String text = text(value);
        return text == null || text.isBlank() ? "<unknown>" : text;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Long::sum);
    }
}
