package dev.warden;

import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.LedgerReader;
import dev.warden.ledger.MeasurementProjector;
import dev.warden.ledger.ProjectIdentity;
import dev.warden.ledger.RunReport;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cost durability, transition semantics and measured-versus-requested telemetry. */
public final class MeasurementAccountingTest implements Suite {
    @Override public String name() { return "measurement-accounting"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-measurement-accounting-");
        try {
            crashAccounting(check, root);
            transitions(check, root);
            tokenCoverage(check, root);
            models(check, root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void crashAccounting(Check check, Path root) throws Exception {
        Path project = root.resolve("crash"), home = root.resolve("home");
        EvidenceLedger ledger = new EvidenceLedger(project, "run", home);
        Map<String, Object> a = call("A", "alpha", "one", 0.25);
        ledger.append("vendor_attempt", a);
        Map<String, Object> before = new LedgerReader().summarize(project);
        check.eq("crash before role_run retains the call", 1L, calls(before));
        check.eq("crash before role_run retains spend", 0.25, spend(before));
        check.eq("an orphan call does not invent a run verdict", "no_evidence",
                map(((List<?>) before.get("runs")).get(0)).get("verdict"));
        check.eq("orphan presentation retains the old visible event count", 0L,
                map(((List<?>) before.get("runs")).get(0)).get("events"));
        Map<String, Object> global = new LedgerReader().summarizeCorpus(home, ProjectIdentity.resolve(project));
        check.eq("local/global crash call count agrees", calls(global), calls(before));
        check.eq("local/global crash spend agrees", spend(global), spend(before));
        Map<String, Object> b = call("B", "beta", "two", 0.5);
        Map<String, Object> role = role(List.of(a, b));
        ledger.append("role_run", role);
        Map<String, Object> partial = new LedgerReader().summarize(project);
        check.eq("partial journal counts uncovered nested B", 2L, calls(partial));
        check.eq("partial journal charges A only once", 0.75, spend(partial));
        ledger.append("vendor_attempt", b);
        check.eq("late journal delivery does not charge B twice", 0.75,
                spend(new LedgerReader().summarize(project)));
        ledger.append("vendor_attempt", a);
        check.eq("repeated attempt identity is not another paid call", 2L,
                calls(new LedgerReader().summarize(project)));
        global = new LedgerReader().summarizeCorpus(home, ProjectIdentity.resolve(project));
        check.eq("global also deduplicates the repeated attempt identity", 2L, calls(global));
        check.eq("global spend agrees after both summaries and journal", 0.75, spend(global));

        Path ambiguous = root.resolve("ambiguous");
        EvidenceLedger mixed = new EvidenceLedger(ambiguous, "run");
        mixed.append("vendor_attempt", a);
        Map<String, Object> anonymous = new LinkedHashMap<>(a);
        anonymous.remove("vendor_attempt_id");
        mixed.append("role_run", role(List.of(anonymous)));
        Map<String, Object> unknown = new LedgerReader().summarize(ambiguous);
        check.eq("unprovable coverage is incomplete locally too", true, unknown.get("incomplete"));
        check.eq("unprovable coverage does not claim exact spend", null, spend(unknown));
        check.eq("unprovable coverage does not invent a second call", 1L, calls(unknown));
    }

    private void transitions(Check check, Path root) throws Exception {
        Map<String, Object> a = call("t1", "alpha", "one", 0.1);
        Map<String, Object> retry = call("t2", "alpha", "one", 0.2);
        Map<String, Object> b = call("t3", "beta", "two", 0.3);
        for (List<Map<String, Object>> sequence : List.of(List.of(a, retry), List.of(a, b), List.of(a, retry, b))) {
            String id = "sequence-" + sequence.size() + "-" + sequence.get(1).get("profile");
            Path project = root.resolve(id);
            Map<String, Object> body = role(sequence);
            new EvidenceLedger(project, "run").append("role_run", body);
            long expected = sequence.get(sequence.size() - 1) == retry ? 0L : 1L;
            check.eq(id + " ledger counts actual switches", expected,
                    iterations(new LedgerReader().summarize(project)).get("failovers"));
            check.eq(id + " report counts actual switches", (int) expected,
                    ((List<?>) report(project, body).get("failovers")).size());
        }
        Path project = root.resolve("confirmed");
        EvidenceLedger ledger = new EvidenceLedger(project, "run", root.resolve("switch-home"));
        Map<String, Object> confirmed = switchEvent("alpha", "beta", "vendor_call_failed");
        Map<String, Object> automatic = switchEvent("beta", "gamma", "role_quota_exhausted");
        Map<String, Object> role = role(List.of(b));
        role.put("role_invocation_id", "invocation");
        role.put("role_failovers", List.of(confirmed, automatic));
        ledger.append("role_failover", confirmed);
        check.eq("a switch survives a crash before summary", 1L,
                iterations(new LedgerReader().summarize(project)).get("failovers"));
        ledger.append("role_run", role);
        Map<String, Object> partial = iterations(new LedgerReader().summarize(project));
        check.eq("a partial switch journal does not hide the summary's second switch", 2L,
                partial.get("failovers"));
        check.eq("partial switch coverage still belongs to one invocation", 1L,
                partial.get("failover_role_runs"));
        check.eq("partial switch coverage retains failure classification once", 1L,
                partial.get("vendor_failure_failovers"));
        Map<String, Object> partialGlobal = iterations(new LedgerReader().summarizeCorpus(
                root.resolve("switch-home"), ProjectIdentity.resolve(project)));
        check.eq("partial switch recovery agrees in the corpus", 2L, partialGlobal.get("failovers"));
        ledger.append("role_failover", automatic);
        Map<String, Object> local = iterations(new LedgerReader().summarize(project));
        check.eq("confirmed and automatic switches count once each", 2L, local.get("failovers"));
        check.eq("one invocation with two switches is one role run", 1L, local.get("failover_role_runs"));
        check.eq("vendor failure stays apart from quota", 1L, local.get("vendor_failure_failovers"));
        Map<String, Object> global = iterations(new LedgerReader().summarizeCorpus(
                root.resolve("switch-home"), ProjectIdentity.resolve(project)));
        check.eq("switch accounting agrees locally and globally", local.get("failovers"), global.get("failovers"));
        check.eq("confirmed chain is visible in joined report", 2,
                ((List<?>) report(project, role).get("failovers")).size());
        role.put("role_failovers", List.of(confirmed, automatic, confirmed));
        check.eq("repeated recorded from/to pairs are separate transitions", 3,
                ((List<?>) report(project, role).get("failovers")).size());
    }

    private void tokenCoverage(Check check, Path root) throws Exception {
        Path project = root.resolve("tokens");
        Map<String, Object> a = call("in-only", "alpha", "one", 0.0);
        a.put("tokens", Map.of("input", 0L));
        Map<String, Object> b = call("out-only", "alpha", "one", 0.0);
        b.put("tokens", Map.of("output_tokens", 9L));
        Map<String, Object> body = role(List.of(a, b));
        Map<String, Object> joined = report(project, body);
        Map<String, Object> vendor = map(((List<?>) joined.get("vendors")).get(0));
        check.eq("reported zero input remains known", 0L, vendor.get("input_tokens"));
        check.eq("output alias is measured", 9L, vendor.get("output_tokens"));
        check.eq("unreported total is not zero or an inferred sum", null, vendor.get("total_tokens"));
        check.eq("missing input coverage is per field", 1L, vendor.get("input_tokens_unknown_calls"));
        check.eq("missing output coverage is per field", 1L, vendor.get("output_tokens_unknown_calls"));
        check.eq("missing total coverage is per field", 2L, vendor.get("total_tokens_unknown_calls"));
        check.eq("partial token calls remain incomplete", 2L, vendor.get("tokens_unknown_calls"));
        check.contains("terminal prints unknown token coverage", RunReport.render(joined), "some token fields on 2 call(s)");
        new EvidenceLedger(project, "run").append("role_run", body);
        Map<String, Object> telemetry = map(map(new LedgerReader().summarize(project).get("metrics")).get("telemetry"));
        check.eq("ledger and report retain the same output alias", 9L,
                map(map(telemetry.get("tokens")).get("output")).get("total"));
        check.eq("ledger and report retain unknown total coverage", 2L,
                map(map(telemetry.get("tokens")).get("total")).get("unknown_count"));
    }

    private void models(Check check, Path root) throws Exception {
        Path project = root.resolve("models");
        Map<String, Object> requested = call("requested", "alpha", "one", 0.0);
        requested.put("model_requested", "asked-model");
        requested.put("model", "asked-model");
        Map<String, Object> reported = call("reported", "alpha", "one", 0.0);
        reported.put("model_requested", "asked-model");
        reported.put("model_reported", "served-model");
        Map<String, Object> legacy = call("legacy", "alpha", "one", 0.0);
        legacy.put("model", "legacy-name");
        Map<String, Object> body = role(List.of(requested, reported, legacy));
        new EvidenceLedger(project, "run").append("role_run", body);
        Map<String, Object> roles = map(map(new LedgerReader().summarize(project).get("metrics")).get("role_runs"));
        check.eq("only a receipt establishes the reported model", 1L,
                map(roles.get("by_model")).get("served-model"));
        check.eq("requested and unproven legacy models stay unknown", 2L,
                map(roles.get("by_model")).get("<unknown>"));
        check.eq("requested model stays separately inspectable", 2L,
                map(roles.get("by_requested_model")).get("asked-model"));
        List<?> vendors = (List<?>) report(project, body).get("vendors");
        check.eq("report groups requested-only and legacy models as unknown", 2L,
                vendors.stream().map(MeasurementAccountingTest::map)
                        .filter(row -> "<unknown>".equals(row.get("model"))).findFirst().orElseThrow().get("calls"));
        Map<String, Object> requestedOnly = report(project, role(List.of(requested)));
        check.eq("stage table does not present the requested model as served", null,
                map(((List<?>) requestedOnly.get("stages")).get(0)).get("model"));
        check.eq("stage retains the separate requested setting", "asked-model",
                map(((List<?>) requestedOnly.get("stages")).get(0)).get("model_requested"));
        check.that("terminal model column cannot attribute a requested-only model",
                !RunReport.render(requestedOnly).contains("asked-model"));
        Map<String, Object> projected = MeasurementProjector.project(Map.of(
                "type", "vendor_attempt", "model_requested", "asked-model",
                "model_reported", "served-model", "vendor_attempts", List.of(reported))).body();
        check.eq("corpus preserves the requested model", "asked-model", projected.get("model_requested"));
        check.eq("corpus preserves nested model provenance", "asked-model",
                map(((List<?>) projected.get("vendor_attempts")).get(0)).get("model_requested"));
    }

    private static Map<String, Object> call(String id, String profile, String vendor, double cost) {
        return new LinkedHashMap<>(Map.of("vendor_attempt_id", id, "profile", profile,
                "vendor", vendor, "role", "reviewer", "code", "ok", "ok", true,
                "cost_usd", cost, "duration_millis", 1L));
    }

    private static Map<String, Object> role(List<Map<String, Object>> calls) {
        Map<String, Object> role = new LinkedHashMap<>(calls.get(calls.size() - 1));
        role.remove("vendor_attempt_id");
        role.put("vendor_attempts", calls);
        return role;
    }

    private static Map<String, Object> switchEvent(String from, String to, String cause) {
        return Map.of("from_profile", from, "to_profile", to, "cause", cause,
                "role", "reviewer", "role_invocation_id", "invocation", "authorized_by", "operator");
    }

    private static Map<String, Object> report(Path project, Map<String, Object> role) throws Exception {
        new EvidenceLedger(project, "run--reviewer-0").writeReport("role-reviewer", role);
        new EvidenceLedger(project, "run").writeReport("task-run", Map.of("steps", List.of(
                Map.of("step", "reviewer", "attempt", 0L, "profile", "alpha", "ok", true))));
        return new RunReport().of(project, "run");
    }

    private static long calls(Map<String, Object> summary) {
        return ((Number) map(map(summary.get("metrics")).get("role_runs")).get("total")).longValue();
    }

    private static Object spend(Map<String, Object> summary) {
        return map(map(map(summary.get("metrics")).get("telemetry")).get("cost_usd")).get("total");
    }

    private static Map<String, Object> iterations(Map<String, Object> summary) {
        return map(map(summary.get("metrics")).get("iterations"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
