package dev.warden;

import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.LedgerReader;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LedgerTest implements Suite {
    @Override public String name() { return "ledger"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-ledger-");
        try {
            new EvidenceLedger(root, "pass").append("machine_gate", Map.of("ok", true, "code", "passed"));
            new EvidenceLedger(root, "fail").append("machine_gate", Map.of("ok", false, "code", "failed"));

            Map<String, Object> knownRole = new LinkedHashMap<>();
            knownRole.put("ok", true);
            knownRole.put("code", "ok");
            knownRole.put("role", "implementer");
            knownRole.put("profile", "codex");
            knownRole.put("vendor", "openai");
            knownRole.put("model", "gpt-test");
            knownRole.put("runner", "direct");
            knownRole.put("duration_millis", 100L);
            knownRole.put("cost_usd", 0.5);
            knownRole.put("tokens", Map.of("input", 10L, "output", 20L, "total", 30L));
            knownRole.put("vendor_attempts", List.of(
                    Map.of("vendor", "anthropic", "code", "role_quota_exhausted"),
                    Map.of("vendor", "openai", "code", "ok")));
            new EvidenceLedger(root, "pass").append("role_run", knownRole);

            Map<String, Object> unknownRole = new LinkedHashMap<>();
            unknownRole.put("ok", false);
            unknownRole.put("code", "role_command_failed");
            unknownRole.put("role", "reviewer");
            unknownRole.put("profile", "claude");
            unknownRole.put("vendor", "anthropic");
            unknownRole.put("runner", "orca");
            unknownRole.put("duration_millis", 300L);
            new EvidenceLedger(root, "fail").append("role_run", unknownRole);

            new EvidenceLedger(root, "pass").append("task_run",
                    Map.of("ok", true, "attempts_used", 2L, "next_action", "human_gate"));
            new EvidenceLedger(root, "fail").append("visual_qa",
                    Map.of("ok", false, "code", "visual_qa_failed"));
            new EvidenceLedger(root, "fail").append("role_quota_exhausted",
                    Map.of("role", "reviewer", "vendor", "anthropic"));
            new EvidenceLedger(root, "fail").append("role_orca_unavailable",
                    Map.of("role", "reviewer"));
            new EvidenceLedger(root, "fail").append("task_run",
                    Map.of("ok", false, "next_action", "human_escalation"));
            new EvidenceLedger(root, "pass").append("human_decision",
                    Map.of("decision", "approved", "wait_millis", 5_000L));
            new EvidenceLedger(root, "pass").append("human_wait", Map.of("reason", "operator_offline"));

            Map<String, Object> summary = new LedgerReader().summarize(root);
            check.eq("two runs counted", 2L, summary.get("run_count"));
            check.eq("pass counted", 1L, summary.get("passed"));
            check.eq("failure counted", 1L, summary.get("failed"));
            check.that("future comparison dimensions declared",
                    String.valueOf(summary.get("future_dimensions")).contains("vendor"));

            Map<String, Object> metrics = object(summary.get("metrics"));
            Map<String, Object> roleRuns = object(metrics.get("role_runs"));
            check.eq("actual role runs counted", 2L, roleRuns.get("total"));
            check.eq("role runs grouped by vendor", 1L,
                    object(roleRuns.get("by_vendor")).get("openai"));
            check.eq("missing model is an explicit dimension", 1L,
                    object(roleRuns.get("by_model")).get("<unknown>"));
            check.eq("role runs grouped by runner", 1L,
                    object(roleRuns.get("by_runner")).get("orca"));
            check.eq("role runs grouped by outcome", 1L,
                    object(roleRuns.get("by_outcome")).get("role_command_failed"));

            Map<String, Object> telemetry = object(metrics.get("telemetry"));
            Map<String, Object> costs = object(telemetry.get("cost_usd"));
            check.eq("known cost is totalled", 0.5, costs.get("total"));
            check.eq("known cost count is explicit", 1L, costs.get("known_count"));
            check.eq("missing cost is not converted to zero", 1L, costs.get("unknown_count"));
            Map<String, Object> tokenTotals = object(object(telemetry.get("tokens")).get("total"));
            check.eq("known tokens are totalled", 30L, tokenTotals.get("total"));
            check.eq("missing tokens are explicit", 1L, tokenTotals.get("unknown_count"));
            Map<String, Object> durations = object(telemetry.get("duration_millis"));
            check.eq("durations are totalled", 400L, durations.get("total"));
            check.eq("duration p50 uses nearest rank", 100L, durations.get("p50"));
            check.eq("duration p95 uses nearest rank", 300L, durations.get("p95"));

            Map<String, Object> iterations = object(metrics.get("iterations"));
            check.eq("vendor transition is one failover", 1L, iterations.get("failovers"));
            check.eq("fix rounds come from final task evidence", 2L, iterations.get("fix_rounds"));
            check.eq("task runs without old fix telemetry stay unknown", 1L,
                    iterations.get("fix_rounds_unknown_runs"));

            Map<String, Object> failures = object(metrics.get("failures"));
            check.eq("machine gate failures counted", 1L, failures.get("machine_gate"));
            check.eq("visual failures counted", 1L, failures.get("visual_qa"));
            check.eq("quota refusals counted once", 1L, failures.get("quota"));
            check.eq("configuration failures counted", 1L, failures.get("configuration"));

            Map<String, Object> human = object(metrics.get("human"));
            check.eq("human decisions counted", 1L, human.get("decisions"));
            check.eq("human decision outcome grouped", 1L,
                    object(human.get("by_decision")).get("approved"));
            check.eq("human waits counted", 2L, human.get("wait_events"));
            Map<String, Object> waits = object(human.get("wait_millis"));
            check.eq("known human wait totalled", 5_000L, waits.get("total"));
            check.eq("unknown human wait remains unknown", 1L, waits.get("unknown_count"));

            Path unknownOnly = root.resolve("unknown-project");
            new EvidenceLedger(unknownOnly, "unknown").append("role_run", Map.of(
                    "ok", true, "code", "ok", "role", "implementer", "vendor", "local"));
            Map<String, Object> unknownMetrics = object(new LedgerReader().summarize(unknownOnly).get("metrics"));
            Map<String, Object> unknownTelemetry = object(unknownMetrics.get("telemetry"));
            Map<String, Object> unknownCosts = object(unknownTelemetry.get("cost_usd"));
            check.eq("all-unknown cost total is null", null, unknownCosts.get("total"));
            check.eq("all-unknown cost is counted", 1L, unknownCosts.get("unknown_count"));
            Map<String, Object> unknownTokens = object(object(unknownTelemetry.get("tokens")).get("total"));
            check.eq("all-unknown token total is null", null, unknownTokens.get("total"));
            check.eq("all-unknown tokens are counted", 1L, unknownTokens.get("unknown_count"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
