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
            new EvidenceLedger(root, "fail").append("baseline_gate",
                    Map.of("ok", false, "code", "command_failed"));

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
                    Map.of("profile", "claude", "vendor", "anthropic", "model", "claude-test",
                            "runner", "direct", "code", "role_quota_exhausted", "ok", false,
                            "duration_millis", 50L, "cost_usd", 0.25,
                            "tokens", Map.of("input", 1L, "output", 2L, "total", 3L)),
                    Map.of("profile", "codex", "vendor", "openai", "model", "declared-model",
                            "model_reported", "gpt-test", "runner", "direct", "code", "ok", "ok", true,
                            "duration_millis", 100L, "cost_usd", 0.5,
                            "tokens", Map.of("input", 10L, "output", 20L, "total", 30L))));
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
            check.eq("every actual vendor attempt is counted", 3L, roleRuns.get("total"));
            check.eq("role attempts grouped by profile", 2L,
                    object(roleRuns.get("by_profile")).get("claude"));
            check.eq("role runs grouped by vendor", 1L,
                    object(roleRuns.get("by_vendor")).get("openai"));
            check.eq("legacy role run without a model stays explicit", 1L,
                    object(roleRuns.get("by_model")).get("<unknown>"));
            check.eq("reported model wins over the declared model", 1L,
                    object(roleRuns.get("by_model")).get("gpt-test"));
            check.eq("role runs grouped by runner", 1L,
                    object(roleRuns.get("by_runner")).get("orca"));
            check.eq("failed failover attempt keeps its own outcome", 1L,
                    object(roleRuns.get("by_outcome")).get("role_quota_exhausted"));
            check.eq("role runs grouped by outcome", 1L,
                    object(roleRuns.get("by_outcome")).get("role_command_failed"));

            Map<String, Object> telemetry = object(metrics.get("telemetry"));
            Map<String, Object> costs = object(telemetry.get("cost_usd"));
            check.eq("cost from every priced attempt is totalled", 0.75, costs.get("total"));
            check.eq("known cost count is per attempt", 2L, costs.get("known_count"));
            check.eq("missing cost is not converted to zero", 1L, costs.get("unknown_count"));
            Map<String, Object> tokenTotals = object(object(telemetry.get("tokens")).get("total"));
            check.eq("tokens from every reporting attempt are totalled", 33L, tokenTotals.get("total"));
            check.eq("missing tokens are explicit", 1L, tokenTotals.get("unknown_count"));
            Map<String, Object> durations = object(telemetry.get("duration_millis"));
            check.eq("attempt durations are totalled without the role total twice", 450L, durations.get("total"));
            check.eq("duration p50 uses nearest rank", 100L, durations.get("p50"));
            check.eq("duration p95 uses nearest rank", 300L, durations.get("p95"));

            Map<String, Object> iterations = object(metrics.get("iterations"));
            check.eq("vendor transition is one failover", 1L, iterations.get("failovers"));
            check.eq("fix rounds come from final task evidence", 2L, iterations.get("fix_rounds"));
            check.eq("task runs without old fix telemetry stay unknown", 1L,
                    iterations.get("fix_rounds_unknown_runs"));

            Map<String, Object> failures = object(metrics.get("failures"));
            check.eq("pre-existing baseline failures counted separately", 1L,
                    failures.get("baseline"));
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

            Path oldFailover = root.resolve("old-failover-project");
            Map<String, Object> oldEvent = new LinkedHashMap<>();
            oldEvent.put("ok", true);
            oldEvent.put("code", "ok");
            oldEvent.put("role", "reviewer");
            oldEvent.put("profile", "codex");
            oldEvent.put("vendor", "openai");
            oldEvent.put("model", "gpt-old");
            oldEvent.put("runner", "direct");
            oldEvent.put("duration_millis", 90L);
            oldEvent.put("cost_usd", 0.4);
            oldEvent.put("tokens", Map.of("total", 9L));
            oldEvent.put("vendor_attempts", List.of(
                    Map.of("profile", "claude", "vendor", "anthropic",
                            "code", "role_quota_exhausted", "duration_millis", 10L),
                    Map.of("profile", "codex", "vendor", "openai", "code", "ok")));
            new EvidenceLedger(oldFailover, "old").append("role_run", oldEvent);
            Map<String, Object> oldMetrics = object(new LedgerReader().summarize(oldFailover).get("metrics"));
            Map<String, Object> oldRoleRuns = object(oldMetrics.get("role_runs"));
            check.eq("sparse historical attempt arrays still count both calls", 2L,
                    oldRoleRuns.get("total"));
            check.eq("the historical final attempt inherits its top-level model", 1L,
                    object(oldRoleRuns.get("by_model")).get("gpt-old"));
            Map<String, Object> oldTelemetry = object(oldMetrics.get("telemetry"));
            check.eq("historical final telemetry is inherited exactly once", 0.4,
                    object(oldTelemetry.get("cost_usd")).get("total"));
            check.eq("historical earlier missing cost remains unknown", 1L,
                    object(oldTelemetry.get("cost_usd")).get("unknown_count"));
            check.eq("historical durations combine nested and final top-level values", 100L,
                    object(oldTelemetry.get("duration_millis")).get("total"));

            Path orcaTimeout = root.resolve("orca-timeout-project");
            new EvidenceLedger(orcaTimeout, "timeout").append("role_run", Map.of(
                    "ok", false, "code", "role_orca_timeout", "role", "reviewer", "runner", "orca"));
            Map<String, Object> timeoutFailures = object(object(
                    new LedgerReader().summarize(orcaTimeout).get("metrics")).get("failures"));
            check.eq("an Orca wall-clock stop is configuration, not a machine gate", 1L,
                    timeoutFailures.get("configuration"));
            check.eq("and is not counted as a baseline or acceptance failure", 0L,
                    timeoutFailures.get("baseline"));
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
