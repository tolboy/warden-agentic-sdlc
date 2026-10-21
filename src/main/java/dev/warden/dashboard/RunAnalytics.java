package dev.warden.dashboard;

import dev.warden.json.Json;
import dev.warden.ledger.LedgerReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded local evidence projection. Chain counters are never added across continuations. */
public final class RunAnalytics {
    private static final int MAX_FILE = 8 * 1024 * 1024, MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DIRECTORIES = 2000, MAX_EVENTS = 20000;
    private final Path project;
    public RunAnalytics(Path project) { this.project = project.toAbsolutePath().normalize(); }

    public Map<String, Object> read(String taskFilter, String runFilter) throws IOException {
        for (String filter : new String[] { taskFilter, runFilter }) if (filter != null && filter.length() > 160)
            throw new IllegalArgumentException("Analytics filter is too long");
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        Map<String, Map<String, Object>> decisions = new LinkedHashMap<>();
        List<Map<String, Object>> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Path runs = project.resolve(".warden/runs");
        long[] remaining = { MAX_BYTES };
        if (Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS) && runs.toRealPath().startsWith(project.toRealPath())) {
            Path boundary = runs.toRealPath();
            List<Path> directories;
            try (var listing = Files.list(runs)) {
                directories = listing.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).limit(MAX_DIRECTORIES + 1L).toList();
            }
            if (directories.size() > MAX_DIRECTORIES) skipped.add("directory_limit");
            for (Path directory : directories.stream().limit(MAX_DIRECTORIES).toList()) {
                String id = directory.getFileName().toString();
                Map<String, Object> summary = json(directory.resolve("task-run.json"), boundary, remaining, skipped);
                if (summary != null) summaries.put(id, summary);
                Map<String, Object> decision = json(directory.resolve("decision.json"), boundary, remaining, skipped);
                if (decision != null) decisions.put(id, decision);
            }
            for (Path directory : directories.stream().limit(MAX_DIRECTORIES).toList()) {
                String id = directory.getFileName().toString();
                String owner = summaries.containsKey(id) ? id : summaries.keySet().stream()
                        .filter(key -> id.startsWith(key + "--")).max(Comparator.comparingInt(String::length)).orElse(null);
                if (owner != null && Boolean.TRUE.equals(summaries.get(owner).get("dry_run"))) continue;
                String body = text(directory.resolve("evidence.jsonl"), boundary, remaining, skipped);
                if (body == null) continue;
                int lineNumber = 0;
                for (String line : body.split("\n")) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    if (events.size() >= MAX_EVENTS) { skipped.add("event_limit"); break; }
                    try {
                        Map<String, Object> event = Json.parseObject(line);
                        if (Boolean.TRUE.equals(event.get("dry_run"))) continue;
                        event.put("_owner", owner == null ? id : owner);
                        events.add(event);
                    } catch (RuntimeException invalid) { skipped.add(id + "/evidence.jsonl:" + lineNumber); }
                }
            }
        } else if (Files.exists(runs, LinkOption.NOFOLLOW_LINKS)) skipped.add("runs_directory_not_confined");
        List<String> selected = summaries.entrySet().stream().filter(e -> !Boolean.TRUE.equals(e.getValue().get("dry_run")))
                .filter(e -> empty(taskFilter) || taskFilter.equals(e.getValue().get("task_id")))
                .filter(e -> empty(runFilter) || runFilter.equals(e.getKey())).map(Map.Entry::getKey).toList();
        List<Map<String, Object>> selectedEvents = events.stream().filter(e -> {
            String owner = String.valueOf(e.get("_owner"));
            if (summaries.containsKey(owner)) return selected.contains(owner);
            // A crash before task-run.json must not hide already paid calls.
            return (empty(taskFilter) || taskFilter.equals(e.get("task_id")))
                    && (empty(runFilter) || runFilter.equals(e.get("workflow_run_id")) || runFilter.equals(owner));
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>(LedgerReader.summarizeRecords(selectedEvents));
        List<Map<String, Object>> rows = new ArrayList<>();
        long ready = 0, reviewPassed = 0, accepted = 0;
        for (String id : selected) {
            var source = summaries.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", id);
            for (String key : List.of("task_id", "risk", "ok", "reason", "candidate_review_passed", "workflow_incomplete",
                    "open_blocking_findings", "review_assurance", "budget_limit_hit")) scalar(row, source, key);
            if ("ready_for_human".equals(source.get("reason")) && !Boolean.TRUE.equals(source.get("workflow_incomplete"))) ready++;
            if (Boolean.TRUE.equals(source.get("candidate_review_passed"))) reviewPassed++;
            var decision = decisions.getOrDefault(id, Map.of());
            Map<String, Object> human = new LinkedHashMap<>();
            for (String key : List.of("state", "kind", "decision", "updated_at")) scalar(human, decision, key);
            row.put("decision", human);
            if ("resolved".equals(decision.get("state")) && "accept".equals(decision.get("decision"))
                    && "success".equals(decision.get("kind"))) accepted++;
            List<Map<String, Object>> runEvents = selectedEvents.stream().filter(e -> id.equals(e.get("_owner"))).toList();
            row.putAll(LedgerReader.summarizeRecords(runEvents));
            var measured = (Map<?, ?>) ((Map<?, ?>) row.get("metrics")).get("role_runs");
            if (source.get("role_runs") instanceof Number declared && measured.get("total") instanceof Number counted
                    && declared.longValue() > counted.longValue()) skipped.add(id + "/missing_call_measurements");
            row.put("findings", findingCounts(source.get("finding_history")));
            row.put("retry", retryCounts(runEvents, calls(row)));
            row.put("timeline", timeline(source.get("steps")));
            Map<String, Object> chain = new LinkedHashMap<>();
            if (source.get("chain") instanceof Map<?, ?> recorded) {
                for (String key : List.of("role_runs", "cost_usd", "unpriced_calls", "fix_attempts", "elapsed_seconds", "elapsed_known", "calls_remaining"))
                    scalar(chain, recorded, key);
                if (recorded.get("runs") instanceof List<?> ids) chain.put("runs", ids.stream().filter(String.class::isInstance).toList());
            }
            row.put("chain", chain);
            rows.add(row);
        }
        result.put("runs", rows);
        List<Map<String, Object>> measuredCalls = calls(result);
        result.put("outcomes", Map.of("runs", selected.size(), "ready_for_human", ready, "review_passed", reviewPassed, "human_accepted", accepted,
                "vendor_ok", measuredCalls.stream().filter(c -> Boolean.TRUE.equals(c.get("ok"))).count(),
                "vendor_failed", measuredCalls.stream().filter(c -> Boolean.FALSE.equals(c.get("ok"))).count()));
        result.put("retry", retryCounts(selectedEvents, measuredCalls));
        result.put("observed_at", Instant.now().toString());
        result.put("source", "local_evidence");
        result.put("incomplete", !skipped.isEmpty() || Boolean.TRUE.equals(result.get("incomplete")));
        result.put("skipped", skipped.stream().distinct().limit(50).toList());
        // Complete totals cannot be established after a bounded read skipped evidence.
        result.put("exact_cost_available", !Boolean.TRUE.equals(result.get("incomplete")) && costComplete(result));
        return result;
    }

    private static boolean costComplete(Map<String, Object> result) {
        var metrics = (Map<?, ?>) result.get("metrics");
        var telemetry = (Map<?, ?>) metrics.get("telemetry");
        var cost = (Map<?, ?>) telemetry.get("cost_usd");
        return cost.get("known_count") instanceof Number known && known.longValue() > 0
                && cost.get("unknown_count") instanceof Number unknown && unknown.longValue() == 0;
    }

    private static Map<String, Object> findingCounts(Object raw) {
        long rounds = 0, closed = 0, persisted = 0, newFindings = 0;
        Set<String> observed = new HashSet<>();
        if (raw instanceof List<?> history) for (Object item : history) if (item instanceof Map<?, ?> round) {
            rounds++;
            closed += size(round.get("closed")); persisted += size(round.get("persisted")); newFindings += size(round.get("new_findings"));
            if (round.get("findings") instanceof List<?> findings) for (Object f : findings)
                if (f instanceof Map<?, ?> finding && finding.get("id") instanceof String id) observed.add(id);
        }
        return Map.of("rounds", rounds, "observed_ids", observed.size(), "closed_transitions", closed,
                "persisted_transitions", persisted, "new_blocking_transitions", newFindings);
    }
    private static Map<String, Object> retryCounts(List<Map<String, Object>> events, List<Map<String, Object>> calls) {
        long pauses = 0, millis = 0;
        Set<String> seen = new HashSet<>();
        for (var event : events) if ("role_run".equals(event.get("type")) && event.get("rate_limit_retry") instanceof List<?> retries) {
            String key = String.valueOf(event.getOrDefault("role_invocation_id", event.get("run_id"))) + "/" + event.get("role");
            if (!seen.add(key)) continue;
            for (Object item : retries) if (item instanceof Map<?, ?> retry && retry.get("paused_millis") instanceof Number n && n.longValue() >= 0) {
                pauses++; millis += n.longValue();
            }
        }
        long observedRetries = 0;
        Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
        for (var call : calls) if (call.get("role_invocation_id") instanceof String invocation) {
            var prior = previous.put(invocation, call);
            if (prior != null && "role_rate_limited".equals(prior.get("code"))
                    && prior.get("profile") instanceof String profile && profile.equals(call.get("profile"))) observedRetries++;
        }
        return Map.of("planned_pauses", pauses, "planned_backoff_millis", millis, "observed_rate_limit_retries", observedRetries);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> calls(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("calls");
    }
    private static List<Map<String, Object>> timeline(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof List<?> steps) for (Object item : steps) if (item instanceof Map<?, ?> step) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : List.of("stage", "step", "attempt", "ok", "code", "profile", "vendor", "cost_usd", "fix_for", "reused_from")) scalar(row, step, key);
            rows.add(row);
        }
        return rows;
    }
    private static int size(Object raw) { return raw instanceof List<?> list ? list.size() : 0; }
    private static boolean empty(String value) { return value == null || value.isBlank(); }
    private static void scalar(Map<String, Object> dest, Map<?, ?> source, String key) {
        Object v = source.get(key);
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) dest.put(key, v);
    }
    private static Map<String, Object> json(Path file, Path boundary, long[] remaining, List<String> skipped) {
        String content = text(file, boundary, remaining, skipped);
        if (content == null) return null;
        try { return Json.parseObject(content); }
        catch (RuntimeException invalid) { skipped.add(file.getParent().getFileName() + "/" + file.getFileName()); return null; }
    }
    private static String text(Path file, Path boundary, long[] remaining, List<String> skipped) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        String label = file.getParent().getFileName() + "/" + file.getFileName();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(boundary)
                    || Files.size(file) > MAX_FILE || Files.size(file) > remaining[0]) { skipped.add(label); return null; }
            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes((int) Math.min(MAX_FILE, remaining[0]) + 1);
                if (bytes.length > MAX_FILE || bytes.length > remaining[0]) { skipped.add(label); return null; }
                remaining[0] -= bytes.length;
                return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException invalid) { skipped.add(label); return null; }
    }
}
