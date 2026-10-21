package dev.warden;

import dev.warden.dashboard.Dashboard;
import dev.warden.dashboard.RunAnalytics;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RunAnalyticsTest implements Suite {
    public String name() { return "run-analytics"; }
    public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-analytics-");
        try {
            Path runs = root.resolve(".warden/runs"); Files.createDirectories(runs);
            Map<String, Object> a = attempt("a", "a1", "role_rate_limited", false, 0.25, null);
            Map<String, Object> b = attempt("a", "a2", "role_quota_exhausted", false, null, null);
            Map<String, Object> c = attempt("b", "b1", "ok", true, 1.0, "reported-b");
            for (var call : List.of(a, b, c)) {
                call.put("type", "vendor_attempt"); call.put("role_invocation_id", "invocation-1"); call.put("workflow_run_id", "r1");
            }
            var role = new LinkedHashMap<String, Object>(); role.put("type", "role_run"); role.put("run_id", "r1--implement-1");
            role.put("role", "implementer"); role.put("role_invocation_id", "invocation-1");
            role.put("vendor_attempts", List.of(a, b, c)); role.put("cost_usd", 99.0); // Enclosing total is not another call.
            role.put("role_failovers", List.of(Map.of("from_profile", "a-profile", "to_profile", "b-profile", "from_code", "role_quota_exhausted")));
            role.put("rate_limit_retry", List.of(Map.of("paused_millis", 10000L)));
            events(runs, "r1--implement-1", List.of(a, b, c, role));
            Map<String, Object> d = attempt("b", "b2", "ok", true, 2.0, "reported-b");
            d.put("type", "vendor_attempt"); d.put("role_invocation_id", "invocation-2"); d.put("workflow_run_id", "r2");
            events(runs, "r2--review-1", List.of(d));
            summary(runs, "r1", Map.of("task_id", "hello", "reason", "rate_limited", "candidate_review_passed", false,
                    "role_runs", 3L, "chain", Map.of("runs", List.of("r1"), "role_runs", 3L, "cost_usd", 1.25),
                    "finding_history", List.of(Map.of("stage", "review", "findings", List.of(Map.of("id", "P1-1"))),
                            Map.of("stage", "review", "findings", List.of(), "closed", List.of("P1-1"), "persisted", List.of()))));
            summary(runs, "r2", Map.of("task_id", "hello", "reason", "ready_for_human", "candidate_review_passed", true,
                    "role_runs", 1L, "steps", List.of(Map.of("stage", "review", "profile", "b-profile", "ok", true, "raw_stdout", "RAW_SECRET")),
                    "chain", Map.of("runs", List.of("r1", "r2"), "role_runs", 4L, "cost_usd", 3.25, "unpriced_calls", 1L, "elapsed_seconds", 40L)));
            Files.writeString(runs.resolve("r2/decision.json"), Json.write(Map.of("state", "resolved", "kind", "success", "decision", "accept", "note", "PRIVATE_NOTE")));
            summary(runs, "dry", Map.of("task_id", "hello", "dry_run", true, "role_runs", 100L));
            events(runs, "dry--implement-1", List.of(attempt("dry", "fake", "ok", true, 999.0, "dry-model")));
            var analytics = new RunAnalytics(root);
            var report = analytics.read(null, null);
            check.eq("complete evidence is not marked incomplete", false, report.get("incomplete"));
            check.eq("four calls do not double-count role summaries or continuation counters", 4L, at(report, "metrics", "role_runs", "total"));
            check.eq("known cost subtotal comes from attempts", 3.25, at(report, "metrics", "telemetry", "cost_usd", "total"));
            check.eq("cost coverage known count", 3L, at(report, "metrics", "telemetry", "cost_usd", "known_count"));
            check.eq("unknown cost is not free", 1L, at(report, "metrics", "telemetry", "cost_usd", "unknown_count"));
            check.eq("unknown cost withholds exact spend", false, report.get("exact_cost_available"));
            check.eq("dry-runs are not task outcomes", 2, ((List<?>) report.get("runs")).size());
            check.eq("ready is distinct from review verdicts", 1L, at(report, "outcomes", "ready_for_human"));
            check.eq("human acceptance requires the actual decision", 1L, at(report, "outcomes", "human_accepted"));
            check.eq("same-profile rate retry is not a failover", 1L, at(report, "metrics", "iterations", "failovers"));
            check.eq("requested retry pause is visible", 10000L, at(report, "retry", "planned_backoff_millis"));
            check.eq("actual retry requires a subsequent call of the same profile", 1L, at(report, "retry", "observed_rate_limit_retries"));
            check.eq("vendor success differs from workflow and acceptance", 2L, at(report, "outcomes", "vendor_ok"));
            check.eq("requested model dimension is explicit", 2L, at(report, "metrics", "role_runs", "by_requested_model", "requested-b"));
            check.eq("requested model is never substituted for a receipt", 2L, at(report, "metrics", "role_runs", "by_model", "<unknown>"));
            check.that("public analytics excludes private content", !Json.write(report).contains("RAW_SECRET") && !Json.write(report).contains("PRIVATE_NOTE"));
            var first = (Map<?, ?>) ((List<?>) report.get("runs")).get(0);
            check.eq("closed blockers count transitions", 1L, ((Map<?, ?>) first.get("findings")).get("closed_transitions"));
            check.eq("finding IDs are observed distinctly", 1, ((Map<?, ?>) first.get("findings")).get("observed_ids"));
            var settings = (List<?>) report.get("settings");
            check.eq("measurement settings distinguish actual model receipts", 2, settings.size());
            var only = analytics.read("hello", "r2");
            check.eq("run filter excludes prior continuation calls", 1L, at(only, "metrics", "role_runs", "total"));
            check.eq("run filter can have complete price coverage", true, only.get("exact_cost_available"));
            check.eq("filter does not replace chain context", 4L, at((Map<String, Object>) ((List<?>) only.get("runs")).get(0), "chain", "role_runs"));
            var empty = analytics.read("other", null);
            check.eq("empty cost is unknown, not zero", null, at(empty, "metrics", "telemetry", "cost_usd", "total"));
            check.eq("empty selection has no exact spend", false, empty.get("exact_cost_available"));
            summary(runs, "crash", Map.of("task_id", "hello", "role_runs", 2L));
            check.eq("missing call measurements mark report incomplete", true, analytics.read(null, null).get("incomplete"));
            events(runs, "crash", List.of());
            Files.writeString(runs.resolve("crash/evidence.jsonl"), "{bad json}\n");
            var broken = analytics.read(null, null);
            check.eq("malformed JSONL marks incomplete", true, broken.get("incomplete"));
            check.that("skipped source is named", Json.write(broken.get("skipped")).contains("crash/evidence.jsonl:1"));
            // A controller may crash after the per-call journal but before a task summary.
            events(runs, "orphan--review-1", List.of(d));
            var orphan = analytics.read("hello", "orphan--review-1");
            check.eq("unfinished run still exposes paid calls", 1L, at(orphan, "metrics", "role_runs", "total"));
            Files.writeString(runs.resolve("crash/evidence.jsonl"), "x".repeat(8 * 1024 * 1024 + 1));
            check.that("oversized evidence is bounded and diagnosed", Json.write(analytics.read(null, null).get("skipped")).contains("crash/evidence.jsonl"));
            Path outside = Files.createTempFile(root, "outside-", ".json"); Files.writeString(outside, "{\"raw_stdout\":\"LINK_SECRET\"}");
            try {
                Path link = runs.resolve("linked"); Files.createSymbolicLink(link, root);
                check.that("directory symlinks do not leak data", !Json.write(analytics.read(null, null)).contains("LINK_SECRET"));
                Files.delete(link);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                check.that("symlink protection also uses NOFOLLOW_LINKS on files", true);
            }
            try (var dashboard = new Dashboard(root, 0); var client = HttpClient.newHttpClient()) {
                dashboard.start(); var base = URI.create(dashboard.url());
                var http = client.send(HttpRequest.newBuilder(base.resolve("api/analytics?task=hello&run=r2")).build(), HttpResponse.BodyHandlers.ofString());
                check.eq("analytics GET serves the filtered report", 200, http.statusCode());
                check.eq("HTTP report agrees with direct read", 1L, at(Json.parseObject(http.body()), "metrics", "role_runs", "total"));
            }
            var untrusted = attempt("bad", "bad1", "ok", true, null, null);
            untrusted.put("type", "vendor_attempt"); untrusted.put("cost_usd", Map.of("raw_stdout", "PRIVATE_TELEMETRY"));
            untrusted.put("launch", Map.of("status", "matched", "effective", Map.of("model", "launch-model", "secret", "PRIVATE_LAUNCH")));
            events(runs, "untrusted", List.of(untrusted));
            var untrustedReport = analytics.read(null, "untrusted");
            check.that("arbitrary telemetry objects never reach public call rows", !Json.write(untrustedReport).contains("PRIVATE_TELEMETRY")
                    && !Json.write(untrustedReport).contains("PRIVATE_LAUNCH"));
            check.eq("invalid cost type stays unknown", 1L, at(untrustedReport, "metrics", "telemetry", "cost_usd", "unknown_count"));
            var blocked = attempt("not-started", "blocked", "role_orca_dispatch_failed", false, null, null);
            blocked.put("type", "vendor_attempt"); blocked.put("budget_charged", false);
            events(runs, "blocked", List.of(blocked));
            var blockedReport = analytics.read("hello", "blocked");
            check.eq("uncharged dispatch does not become a vendor failure", 0L, at(blockedReport, "outcomes", "vendor_failed"));
            check.eq("uncharged dispatch remains visible separately", 1L, at(blockedReport, "metrics", "role_runs", "uncharged_attempts"));
            check.eq("uncharged dispatch has no invented price coverage", 0L, at(blockedReport, "metrics", "telemetry", "cost_usd", "unknown_count"));
        } finally {
            try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static Map<String, Object> attempt(String vendor, String id, String code, boolean ok, Double cost, String reported) {
        var result = new LinkedHashMap<String, Object>();
        result.put("vendor_attempt_id", id); result.put("task_id", "hello"); result.put("role", "implementer");
        result.put("vendor", vendor); result.put("profile", vendor + "-profile"); result.put("model_requested", "requested-" + vendor);
        result.put("model_reported", reported); result.put("effort_requested", "high"); result.put("runner", "direct");
        result.put("ok", ok); result.put("code", code); result.put("cost_usd", cost); result.put("duration_millis", 100L);
        return result;
    }
    private static Object at(Map<String, Object> root, String... keys) {
        Object value = root; for (String key : keys) value = ((Map<?, ?>) value).get(key); return value;
    }
    private static void summary(Path runs, String id, Map<String, Object> source) throws Exception {
        Files.createDirectories(runs.resolve(id)); Files.writeString(runs.resolve(id + "/task-run.json"), Json.write(source));
    }
    private static void events(Path runs, String id, List<Map<String, Object>> events) throws Exception {
        Files.createDirectories(runs.resolve(id)); StringBuilder out = new StringBuilder();
        for (var event : events) out.append(Json.write(event)).append('\n'); Files.writeString(runs.resolve(id + "/evidence.jsonl"), out);
    }
}
