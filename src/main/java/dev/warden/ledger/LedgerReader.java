package dev.warden.ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Event types the home corpus writes into the same local stream, and that this reader
     * never saw before that corpus existed: the per-call vendor journal, the refusal a
     * writing role meets when the contract grants it no authority, and the two markers a
     * prepared run leaves behind.
     *
     * Filtering them once, here, is what keeps `warden ledger` printing the same run
     * verdicts, event counts and configuration totals it printed before the corpus existed,
     * rather than for whichever tally someone remembered to exempt.
     */
    private static final Set<String> CORPUS_ONLY_EVENTS = Set.of(
            "authority_denied", "run_prepared", "run_reserved", "vendor_attempt");

    private static List<Map<String, Object>> localView(List<Map<String, Object>> events) {
        List<Map<String, Object>> visible = new ArrayList<>(events.size());
        for (Map<String, Object> event : events) {
            Object type = event.get("type");
            if (type == null || !CORPUS_ONLY_EVENTS.contains(String.valueOf(type))) {
                visible.add(event);
            }
        }
        return visible;
    }

    public Map<String, Object> summarize(Path projectRoot) throws IOException {
        Path runs = projectRoot.resolve(".warden/runs");
        List<Map<String, Object>> runRows = new ArrayList<>();
        Metrics metrics = new Metrics();
        long passed = 0;
        long failed = 0;
        List<JsonlDiagnostics.Skip> skipped = new ArrayList<>();
        if (Files.isDirectory(runs)) {
            try (var directories = Files.list(runs)) {
                for (Path run : directories.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
                    String label = ".warden/runs/" + run.getFileName() + "/evidence.jsonl";
                    JsonlDiagnostics.Read read = JsonlDiagnostics.read(run.resolve("evidence.jsonl"), label);
                    skipped.addAll(read.skipped());
                    List<Map<String, Object>> events = localView(read.rows());
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
        markIncomplete(summary, skipped);
        return summary;
    }

    /**
     * Aggregate the home corpus for one project identity. {@code projectId} blank or
     * null selects measurements whose project identity is unknown — imported legacy
     * evidence that never carried one. Never mints an identity, never constructs an
     * {@link EvidenceLedger}, never recovers a journal, and never sums a local tree
     * together with the copy of it that reached the home.
     *
     * Paid calls are counted by accounting unit: {@code vendor_attempt} is a call,
     * {@code role_run} is a summary of those calls when a journal of attempts exists.
     * A historical {@code role_run} that still carries {@code vendor_attempts} is
     * expanded attempt by attempt, skipping matching {@code vendor_attempt_id} values.
     * Run identity alone never covers all the calls in a summary. Missing identities do
     * not establish coverage, and an id on only one representation does not prove
     * two distinct calls. Nested attempts whose ids already appear in the journal
     * are not counted again. Evidence whose accounting unit cannot be established is
     * counted as unknown. A relationship that cannot be proved the same or distinct
     * is flagged as {@code ambiguous_coverage} rather than merged.
     *
     * Incomplete imports are part of the report: {@code imports.jsonl} carries the
     * source skip, and a later global read retains the file, offset, skip count and
     * {@code incomplete} marker rather than treating the surviving segment rows as
     * the whole story. A skip whose project identity could not be recovered applies
     * to every identity: failure to recover it does not establish that the loss
     * cannot affect the selected project.
     */
    public Map<String, Object> summarizeCorpus(Path home, String projectId) throws IOException {
        boolean unknownIdentity = projectId == null || projectId.isBlank();
        List<Map<String, Object>> events = new ArrayList<>();
        List<JsonlDiagnostics.Skip> skipped = new ArrayList<>();
        Path ledgerDir = home == null ? null : HomeCorpus.directory(home);
        Path segments = ledgerDir == null ? null : ledgerDir.resolve(HomeCorpus.SEGMENTS);
        if (segments != null && Files.isDirectory(segments)) {
            try (var files = Files.list(segments)) {
                List<Path> ordered = files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path file : ordered) {
                    String label = HomeCorpus.SEGMENTS + "/" + file.getFileName();
                    JsonlDiagnostics.Read read = JsonlDiagnostics.read(file, label);
                    skipped.addAll(read.skipped());
                    for (Map<String, Object> row : read.rows()) {
                        if (matchesProject(row, projectId, unknownIdentity)) events.add(row);
                    }
                }
            }
        }
        if (ledgerDir != null) {
            foldIncompleteImports(ledgerDir.resolve(CorpusImport.IMPORTS), projectId,
                    unknownIdentity, skipped);
        }
        Metrics metrics = new Metrics();
        metrics.acceptCorpus(events);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", 1L);
        summary.put("source", "corpus");
        summary.put("project_id", unknownIdentity ? null : projectId);
        summary.putAll(identityOf(events));
        summary.put("outcomes", outcomesOf(events));
        summary.put("metrics", metrics.toMap());
        if (metrics.ambiguousCoverage > 0) {
            summary.put("ambiguous_coverage", metrics.ambiguousCoverage);
            // The same reason a skipped row withholds it: some of what was paid for is not
            // attributable, so a number presented as the total would be a guess wearing a
            // decimal point.
            summary.put("incomplete", Boolean.TRUE);
            withholdExactSpend(summary);
        }
        markIncomplete(summary, skipped);
        return summary;
    }

    private static final class Metrics {
        private long roleRuns;
        private final Map<String, Long> byRole = new LinkedHashMap<>();
        private final Map<String, Long> byProfile = new LinkedHashMap<>();
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
        private long baselineFailures;
        private long machineGateFailures;
        private long visualHarnessFailures;
        private long visualRoleFailures;
        private long quotaFailures;
        private long configurationFailures;
        private long humanDecisions;
        private final Map<String, Long> decisionsByOutcome = new LinkedHashMap<>();
        private long humanWaitEvents;
        private final IntegerMetric humanWaitMillis = new IntegerMetric();
        private long ambiguousCoverage;

        void accept(List<Map<String, Object>> events) {
            acceptEvents(events, false);
        }

        /**
         * Count paid calls by {@code accounting.counts_as_new_calls}. The enclosing
         * {@code role_run} still contributes failovers, configuration and visual-role
         * failures, but not a second set of calls or dollars — unless it is historical
         * evidence whose attempts were never journaled as their own events.
         */
        void acceptCorpus(List<Map<String, Object>> events) {
            acceptEvents(events, true);
        }

        void acceptEvents(List<Map<String, Object>> events, boolean corpus) {
            long explicitQuotaEvents = events.stream()
                    .filter(event -> "role_quota_exhausted".equals(text(event.get("type"))))
                    .count();
            quotaFailures += explicitQuotaEvents;
            long fallbackQuotaFailures = 0;
            CoveredCalls covered = corpus ? CoveredCalls.of(events) : CoveredCalls.NONE;

            for (Map<String, Object> event : events) {
                if (corpus && countsAsNewCall(event)) {
                    acceptRoleAttempt(event, event, true);
                }
                String type = text(event.get("type"));
                String code = text(event.get("code"));
                if ("role_run".equals(type)) {
                    if (corpus) acceptCorpusRoleRun(event, covered);
                    else acceptRoleRun(event);
                    if ("role_quota_exhausted".equals(code)) fallbackQuotaFailures++;
                    if (isConfigurationFailure(code)) configurationFailures++;
                    if ("visual_qa".equals(text(event.get("role")))
                            && Boolean.FALSE.equals(event.get("ok"))) visualRoleFailures++;
                } else if (!"vendor_attempt".equals(type)
                        && (isConfigurationFailure(type) || isConfigurationFailure(code))) {
                    // task_run repeats the final reason already recorded by the stage that
                    // failed. Counting it again would double every configuration failure.
                    // vendor_attempt carries the same code as the enclosing role_run; the
                    // local view hides it, and the corpus must not count it twice either.
                    if (!"task_run".equals(type)) configurationFailures++;
                }

                if ("baseline_gate".equals(type) && Boolean.FALSE.equals(event.get("ok"))) {
                    baselineFailures++;
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

        /**
         * Corpus accounting for a role summary. Modern journals already counted the
         * attempts as {@code vendor_attempt} events. Historical evidence recorded the
         * same attempts only on the enclosing {@code role_run}; expand those so they
         * remain measurable, skipping nested attempts whose {@code vendor_attempt_id}
         * is already in the journal. A role_run that is neither a journaled summary
         * nor an explicit attempt list has no established accounting unit: observe it
         * as unknown rather than inventing a call.
         */
        private void acceptCorpusRoleRun(Map<String, Object> event, CoveredCalls covered) {
            if (shouldExpandHistoricalRoleRun(event)) {
                acceptExpandedRoleRun(event, covered);
                return;
            }
            acceptRoleRunMeta(event);
            if (accountingUnitUnknown(event, covered)) acceptUnknownAccounting();
        }

        @SuppressWarnings("unchecked")
        private void acceptExpandedRoleRun(Map<String, Object> event, CoveredCalls covered) {
            if (event.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
                boolean flagged = false;
                for (int index = 0; index < attempts.size(); index++) {
                    Object body = attempts.get(index);
                    Map<String, Object> attempt = body instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    if (covered.coversAttempt(attempt)) continue;
                    if (covered.ambiguousWith(attempt, event)) {
                        // Neither merged nor split. Counting this side as well would put one
                        // paid call into the exact total twice, which is what an operator
                        // reads as money spent; asserting it is the journaled call would
                        // claim what the evidence cannot say. It goes to unknown, the pair
                        // is reported, and the total stops claiming to be exact.
                        if (!flagged) {
                            ambiguousCoverage++;
                            flagged = true;
                        }
                        acceptUnknownAccounting();
                        continue;
                    }
                    acceptRoleAttempt(event, attempt, index == attempts.size() - 1);
                }
            } else {
                acceptRoleAttempt(event, event, true);
            }
            acceptRoleRunMeta(event);
        }

        private void acceptUnknownAccounting() {
            cost.accept(null);
            durationMillis.accept(null);
            inputTokens.accept(null);
            outputTokens.accept(null);
            totalTokens.accept(null);
        }

        @SuppressWarnings("unchecked")
        private void acceptRoleRun(Map<String, Object> event) {
            if (event.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
                for (int index = 0; index < attempts.size(); index++) {
                    Object body = attempts.get(index);
                    Map<String, Object> attempt = body instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    acceptRoleAttempt(event, attempt, index == attempts.size() - 1);
                }
            } else {
                // Ledgers written before vendor_attempts existed represented one actual call
                // directly on role_run. Keep reading that format indefinitely.
                acceptRoleAttempt(event, event, true);
            }
            acceptRoleRunMeta(event);
        }

        private void acceptRoleRunMeta(Map<String, Object> event) {
            long transitions = 0;
            if (event.get("vendor_attempts") instanceof List<?> attempts && attempts.size() > 1) {
                transitions = attempts.size() - 1L;
            }
            if (event.get("failed_over_from") instanceof List<?> prior) {
                transitions = Math.max(transitions, prior.size());
            }
            if (event.get("accounting") instanceof Map<?, ?> accounting
                    && accounting.get("vendor_attempt_count") instanceof Number count
                    && count.longValue() > 1) {
                transitions = Math.max(transitions, count.longValue() - 1);
            }
            if (transitions > 0) {
                failoverRoleRuns++;
                failovers += transitions;
            }
        }

        @SuppressWarnings("unchecked")
        private void acceptRoleAttempt(Map<String, Object> roleRun, Map<String, Object> attempt,
                                       boolean finalAttempt) {
            roleRuns++;
            increment(byRole, dimension(inherited(attempt, roleRun, "role", true)));
            increment(byProfile, dimension(inherited(attempt, roleRun, "profile", finalAttempt)));
            increment(byVendor, dimension(inherited(attempt, roleRun, "vendor", finalAttempt)));
            increment(byModel, dimension(model(attempt, roleRun, finalAttempt)));
            increment(byRunner, dimension(inherited(attempt, roleRun, "runner", finalAttempt)));
            Object outcome = inherited(attempt, roleRun, "code", finalAttempt);
            Object okBody = inherited(attempt, roleRun, "ok", finalAttempt);
            if (outcome == null && okBody instanceof Boolean ok) {
                outcome = ok ? "passed" : "failed";
            }
            increment(byOutcome, dimension(outcome));

            cost.accept(inherited(attempt, roleRun, "cost_usd", finalAttempt));
            Object duration = firstPresent(attempt, "duration_millis", "duration_ms");
            if (duration == null && finalAttempt) {
                duration = firstPresent(roleRun, "duration_millis", "duration_ms");
            }
            durationMillis.accept(duration);
            Object tokenBody = attempt.get("tokens");
            if (tokenBody == null && finalAttempt) tokenBody = roleRun.get("tokens");
            Map<String, Object> tokens = tokenBody instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            inputTokens.accept(firstPresent(tokens, "input", "input_tokens", "inputTokens"));
            outputTokens.accept(firstPresent(tokens, "output", "output_tokens", "outputTokens"));
            totalTokens.accept(firstPresent(tokens, "total", "total_tokens", "totalTokens"));
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
            role.put("by_profile", byProfile);
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
            failures.put("baseline", baselineFailures);
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

    private static Object inherited(Map<String, Object> attempt, Map<String, Object> roleRun,
                                    String name, boolean inheritFinal) {
        Object value = attempt.get(name);
        return value != null || !inheritFinal ? value : roleRun.get(name);
    }

    private static Object model(Map<String, Object> attempt, Map<String, Object> roleRun,
                                boolean finalAttempt) {
        Object reported = firstPresent(attempt, "model_reported");
        if (reported == null && finalAttempt) reported = firstPresent(roleRun, "model_reported");
        if (reported != null) return reported;
        Object declared = firstPresent(attempt, "model");
        return declared != null || !finalAttempt ? declared : firstPresent(roleRun, "model");
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
                || code.startsWith("configuration_")
                || code.startsWith("role_orca_");
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

    private static boolean countsAsNewCall(Map<String, Object> event) {
        if (event.get("accounting") instanceof Map<?, ?> accounting) {
            return Boolean.TRUE.equals(accounting.get("counts_as_new_calls"));
        }
        return false;
    }

    private static boolean hasRecordedAttempts(Map<String, Object> event) {
        return event.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty();
    }

    private static boolean shouldExpandHistoricalRoleRun(Map<String, Object> event) {
        if (countsAsNewCall(event)) return false;
        return hasRecordedAttempts(event);
    }

    private static boolean accountingUnitUnknown(Map<String, Object> event, CoveredCalls covered) {
        if (countsAsNewCall(event) || covered.coversAttempt(event)
                || hasRecordedAttempts(event)) {
            return false;
        }
        return true;
    }

    private static String attemptId(Map<String, Object> row) {
        return nonBlank(firstText(row, "vendor_attempt_id"));
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String text = nonBlank(value);
            if (text != null) return text;
        }
        return null;
    }

    private static boolean matchesProject(Map<String, Object> row, String projectId,
                                          boolean unknownIdentity) {
        String id = text(row.get("project_id"));
        boolean rowUnknown = id == null || id.isBlank();
        return unknownIdentity ? rowUnknown : projectId.equals(id);
    }

    /**
     * Journaled {@code vendor_attempt} events, keyed so a historical {@code role_run}
     * that recorded the same attempts only as a nested array can still be expanded
     * without counting a modern journal twice. Coverage requires matching
     * {@code vendor_attempt_id}. Distinct run identities can establish separate calls,
     * but a shared run identity cannot establish coverage. Missing identities do not
     * establish that one attempt covers another, or that the two representations
     * are distinct: an id on only one side is flagged as {@code ambiguous_coverage}
     * rather than counted as two unrelated calls.
     */
    private record CoveredCalls(Set<String> attemptIds, List<JournalCall> journal) {
        static final CoveredCalls NONE = new CoveredCalls(Set.of(), List.of());

        private record JournalCall(String instance, String operator, String attemptId) {}

        static CoveredCalls of(List<Map<String, Object>> events) {
            Set<String> attemptIds = new LinkedHashSet<>();
            List<JournalCall> journal = new ArrayList<>();
            for (Map<String, Object> event : events) {
                if (!countsAsNewCall(event)) continue;
                String instance = nonBlank(text(event.get("run_instance_id")));
                String operator = nonBlank(firstText(event, "operator_run_id", "run_id"));
                String attempt = attemptId(event);
                if (attempt != null) attemptIds.add(attempt);
                journal.add(new JournalCall(instance, operator, attempt));
            }
            return new CoveredCalls(attemptIds, List.copyOf(journal));
        }

        boolean coversAttempt(Map<String, Object> row) {
            String id = attemptId(row);
            return id != null && attemptIds.contains(id);
        }

        /**
         * True when this nested attempt is neither the same as a journaled call nor
         * provably a different one. Distinct {@code vendor_attempt_id} values still
         * count separately; an id on only one representation does not.
         */
        boolean ambiguousWith(Map<String, Object> attempt, Map<String, Object> roleRun) {
            if (journal.isEmpty()) return false;
            if (coversAttempt(attempt)) return false;
            for (JournalCall call : journal) {
                if (!distinctFrom(attempt, roleRun, call)) return true;
            }
            return false;
        }

        private static boolean distinctFrom(Map<String, Object> attempt, Map<String, Object> roleRun,
                                            JournalCall call) {
            String nestedId = attemptId(attempt);
            if (nestedId != null && call.attemptId != null) return !nestedId.equals(call.attemptId);
            String nestedInstance = firstNonBlank(text(attempt.get("run_instance_id")),
                    text(roleRun.get("run_instance_id")));
            if (nestedInstance != null && call.instance != null) {
                return !nestedInstance.equals(call.instance);
            }
            String nestedOperator = firstNonBlank(
                    firstText(attempt, "operator_run_id", "run_id"),
                    firstText(roleRun, "operator_run_id", "run_id"));
            return nestedOperator != null && call.operator != null
                    && !nestedOperator.equals(call.operator);
        }
    }

    /**
     * Incomplete imports live in {@code imports.jsonl}, not in the segment rows that
     * survived. Fold their skips into this report when the import touched the identity
     * being summarised — including when a skipped row's project identity could not be
     * recovered, because that does not establish that the loss cannot affect this
     * identity — so a later global read still withholds exact spend.
     */
    private static void foldIncompleteImports(Path importsFile, String projectId,
                                              boolean unknownIdentity,
                                              List<JsonlDiagnostics.Skip> skipped)
            throws IOException {
        JsonlDiagnostics.Read read = JsonlDiagnostics.read(importsFile, CorpusImport.IMPORTS);
        skipped.addAll(read.skipped());
        // An explicit successful re-import of exactly the same bytes reconciles a failed
        // delivery. Different source content (or an unknown fingerprint) cannot erase loss.
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        long anonymous = 0;
        for (Map<String, Object> receipt : read.rows()) {
            String fingerprint = nonBlank(text(receipt.get("source_fingerprint")));
            String transform = nonBlank(text(receipt.get("transform_version")));
            String key = fingerprint == null || transform == null
                    ? "unknown:" + anonymous++ : transform + ":" + fingerprint;
            latest.put(key, receipt);
        }
        for (Map<String, Object> receipt : latest.values()) {
            if (!incompleteReceipt(receipt)) continue;
            if (!importAppliesTo(receipt, projectId, unknownIdentity)) continue;
            int before = skipped.size();
            addReceiptSkips(skipped, receipt);
            if (skipped.size() == before) {
                String name = text(receipt.get("source_name"));
                skipped.add(new JsonlDiagnostics.Skip(
                        name == null || name.isBlank() ? CorpusImport.IMPORTS : name,
                        0L, "incomplete_import"));
            }
        }
    }

    private static boolean incompleteReceipt(Map<String, Object> receipt) {
        if (Boolean.FALSE.equals(receipt.get("complete"))) return true;
        // Older importers wrote complete:true even when delivery lost rows.
        for (String field : List.of("failed", "conflicts")) {
            if (receipt.get(field) instanceof Number count && count.longValue() > 0) return true;
        }
        Object skipped = receipt.get("skipped");
        if (skipped instanceof Map<?, ?> map && map.get("count") instanceof Number count) {
            return count.longValue() > 0;
        }
        return false;
    }

    private static boolean importAppliesTo(Map<String, Object> receipt, String projectId,
                                           boolean unknownIdentity) {
        Set<String> ids = projectIdsOf(receipt);
        long unknownRows = unknownProjectRows(receipt);
        if (unattributedSkipped(receipt, ids, unknownRows) > 0) return true;
        if (unknownIdentity) return unknownRows > 0;
        return projectId != null && ids.contains(projectId);
    }

    private static Set<String> projectIdsOf(Map<String, Object> receipt) {
        Set<String> ids = new LinkedHashSet<>();
        Object listed = receipt.get("project_ids");
        if (listed instanceof List<?> list) {
            for (Object item : list) {
                String id = text(item);
                if (id != null && !id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private static long unknownProjectRows(Map<String, Object> receipt) {
        if (receipt.get("unknown_project_id") instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /**
     * Skips whose project identity was not recovered cannot be shown not to belong
     * to the selected project. Receipts written before this field existed look the
     * same: incomplete, no surviving {@code project_ids}, no unknown-identity rows.
     */
    private static long unattributedSkipped(Map<String, Object> receipt, Set<String> ids,
                                            long unknownRows) {
        if (receipt.get("unattributed_skipped") instanceof Number number) {
            return number.longValue();
        }
        if (incompleteReceipt(receipt) && ids.isEmpty() && unknownRows == 0) return 1L;
        return 0L;
    }

    private static void addReceiptSkips(List<JsonlDiagnostics.Skip> skipped,
                                        Map<String, Object> receipt) {
        Object body = receipt.get("skipped");
        if (!(body instanceof Map<?, ?> map)) return;
        Object records = map.get("records");
        if (!(records instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) continue;
            Object file = row.get("file");
            Object offset = row.get("offset");
            Object reason = row.get("reason");
            skipped.add(new JsonlDiagnostics.Skip(
                    file == null ? "" : String.valueOf(file),
                    offset instanceof Number number ? number.longValue() : 0L,
                    reason == null ? "corrupt" : String.valueOf(reason)));
        }
    }

    @SuppressWarnings("unchecked")
    private static void markIncomplete(Map<String, Object> summary, List<JsonlDiagnostics.Skip> skipped) {
        if (skipped == null || skipped.isEmpty()) return;
        summary.put("incomplete", Boolean.TRUE);
        summary.put("skipped", JsonlDiagnostics.skippedMap(skipped));
        withholdExactSpend(summary);
    }

    @SuppressWarnings("unchecked")
    private static void withholdExactSpend(Map<String, Object> summary) {
        Object metrics = summary.get("metrics");
        if (!(metrics instanceof Map<?, ?> metricsMap)) return;
        Object telemetry = metricsMap.get("telemetry");
        if (!(telemetry instanceof Map<?, ?> telemetryMap)) return;
        Object cost = telemetryMap.get("cost_usd");
        if (cost instanceof Map<?, ?>) {
            ((Map<String, Object>) cost).put("total", null);
        }
    }

    private static Map<String, Object> identityOf(List<Map<String, Object>> events) {
        Map<String, Instance> byId = new LinkedHashMap<>();
        long unknown = 0;
        for (Map<String, Object> event : events) {
            String id = text(event.get("run_instance_id"));
            if (id == null || id.isBlank()) {
                unknown++;
                continue;
            }
            Instance instance = byId.computeIfAbsent(id, Instance::new);
            instance.events++;
            Object operator = event.get("operator_run_id");
            if (operator == null) operator = event.get("run_id");
            if (operator != null) instance.operatorRunId = String.valueOf(operator);
            Object parent = event.get("parent_run_instance_id");
            if (parent != null && !String.valueOf(parent).isBlank()) {
                instance.parent = String.valueOf(parent);
            }
        }
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (String id : byId.keySet()) {
            String root = rootOf(id, byId);
            members.computeIfAbsent(root, key -> new ArrayList<>()).add(id);
        }
        Map<String, Object> instances = new LinkedHashMap<>();
        instances.put("count", (long) byId.size());
        instances.put("unknown_count", unknown);
        instances.put("ids", List.copyOf(byId.keySet()));

        Map<String, Object> lineages = new LinkedHashMap<>();
        lineages.put("count", (long) members.size());
        lineages.put("roots", List.copyOf(members.keySet()));
        lineages.put("members", members);

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("run_instances", instances);
        identity.put("lineages", lineages);
        return identity;
    }

    private static String rootOf(String id, Map<String, Instance> byId) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String current = id;
        while (current != null && byId.containsKey(current)) {
            if (!seen.add(current)) break;
            String parent = byId.get(current).parent;
            if (parent == null || !byId.containsKey(parent)) return current;
            current = parent;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outcomesOf(List<Map<String, Object>> events) {
        long roleKnown = 0;
        long roleUnknown = 0;
        long roleTrue = 0;
        long roleFalse = 0;
        long workflowKnown = 0;
        long workflowUnknown = 0;
        Map<String, Long> workflowBy = new LinkedHashMap<>();
        long humanKnown = 0;
        long humanUnknown = 0;
        long humanTrue = 0;
        long humanFalse = 0;
        for (Map<String, Object> event : events) {
            String type = text(event.get("type"));
            Map<String, Object> outcomes = event.get("outcomes") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            if ("role_run".equals(type)) {
                Object ok = outcomes.get("role_ok");
                if (ok == null) ok = event.get("ok");
                if (ok instanceof Boolean value) {
                    roleKnown++;
                    if (value) roleTrue++;
                    else roleFalse++;
                } else {
                    roleUnknown++;
                }
            }
            if ("task_run".equals(type) || "task_dry_run".equals(type)) {
                Object workflow = outcomes.get("workflow_outcome");
                if (workflow == null) workflow = event.get("reason");
                if (workflow == null) workflow = event.get("next_action");
                if (workflow != null) {
                    workflowKnown++;
                    increment(workflowBy, String.valueOf(workflow));
                } else {
                    workflowUnknown++;
                }
            }
            if ("human_decision".equals(type)) {
                Object accept = outcomes.get("human_accept");
                if (accept instanceof Boolean value) {
                    humanKnown++;
                    if (value) humanTrue++;
                    else humanFalse++;
                } else {
                    String decision = text(event.get("decision"));
                    if (decision == null) {
                        humanUnknown++;
                    } else {
                        humanKnown++;
                        boolean yes = "accept".equals(decision) || "approved".equals(decision);
                        if (yes) humanTrue++;
                        else humanFalse++;
                    }
                }
            }
        }
        Map<String, Object> role = counts(roleKnown, roleUnknown);
        role.put("true", roleTrue);
        role.put("false", roleFalse);
        Map<String, Object> workflow = counts(workflowKnown, workflowUnknown);
        workflow.put("by_value", workflowBy);
        Map<String, Object> human = counts(humanKnown, humanUnknown);
        human.put("true", humanTrue);
        human.put("false", humanFalse);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role_ok", role);
        result.put("workflow_outcome", workflow);
        result.put("human_accept", human);
        return result;
    }

    private static final class Instance {
        final String id;
        String operatorRunId;
        String parent;
        long events;

        Instance(String id) {
            this.id = id;
        }
    }
}
