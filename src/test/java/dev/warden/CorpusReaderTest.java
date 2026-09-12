package dev.warden;

import dev.warden.json.Json;
import dev.warden.ledger.CorpusImport;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.HomeCorpus;
import dev.warden.ledger.LedgerReader;
import dev.warden.ledger.ProjectIdentity;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Read-path acceptance for the home measurement corpus: group 1 in full form (global
 * reader after the tree is gone), group 6 (legacy import), group 9 (corruption) and
 * group 10 as a regression (plain {@code warden ledger}, temporary homes, CLI flags).
 */
public final class CorpusReaderTest implements Suite {
    @Override public String name() { return "corpus-reader"; }

    private static final String SECRET = "SECRET_MARKER_7f3c9e2a1b";

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-corpus-reader-");
        try {
            group1FullForm(check, sandbox);
            group6LegacyImport(check, sandbox);
            group9Corruption(check, sandbox);
            group10Regression(check, sandbox);
        } finally {
            try {
                deleteTree(sandbox);
            } catch (IOException ignored) {
                // Windows can keep a mapped file after the child exits.
            }
        }
    }

    private void group1FullForm(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("g1-home");
        Path project = sandbox.resolve("g1-project");
        Files.createDirectories(project);
        EvidenceLedger first = new EvidenceLedger(project, "alpha", home);

        Map<String, Object> attemptFail = attempt("claude", "anthropic", "claude-test",
                false, "role_quota_exhausted", 50L, 0.25, 3L);
        Map<String, Object> attemptOk = attempt("codex", "openai", "gpt-test",
                true, "ok", 100L, 0.5, 30L);
        first.append("vendor_attempt", attemptFail);
        first.append("vendor_attempt", attemptOk);
        Map<String, Object> role = new LinkedHashMap<>(attemptOk);
        role.put("vendor_attempts", List.of(attemptFail, attemptOk));
        first.append("role_run", role);
        first.append("task_run", Map.of("ok", true, "attempts_used", 1L, "next_action", "human_gate"));
        first.append("human_decision", Map.of("decision", "accept", "wait_millis", 10L));
        String projectId = first.projectId();
        String instanceA = first.runInstanceId();
        check.that("the write path recorded a project identity", projectId != null && !projectId.isBlank());

        EvidenceLedger continued = new EvidenceLedger(project, "beta", home).bindParent(instanceA);
        continued.append("vendor_attempt", attemptOk);
        Map<String, Object> continuedRole = new LinkedHashMap<>(attemptOk);
        continuedRole.put("vendor_attempts", List.of(attemptOk));
        continued.append("role_run", continuedRole);
        String instanceB = continued.runInstanceId();
        check.that("a continuation is a different physical run instance",
                !instanceA.equals(instanceB));

        Map<String, Object> local = new LedgerReader().summarize(project);
        Map<String, Object> localMetrics = object(local.get("metrics"));
        long localCalls = number(object(localMetrics.get("role_runs")).get("total"));
        Double localCost = (Double) object(object(localMetrics.get("telemetry")).get("cost_usd")).get("total");
        check.eq("local reader counts every vendor attempt once", 3L, localCalls);
        check.eq("local reader totals the priced attempts", 1.25, localCost);

        double naive = 0;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if (!projectId.equals(row.get("project_id"))) continue;
            if (row.get("cost_usd") instanceof Number priced) naive += priced.doubleValue();
        }
        check.that("summing every corpus record would double-count spend", naive > localCost);

        Path identityBefore = project.resolve(".warden/runs/.project-identity");
        check.that("the write path recorded identity on disk", Files.isRegularFile(identityBefore));
        deleteTree(project);
        check.that("the project tree is gone", !Files.exists(project.resolve(".warden")));

        Map<String, Object> global = new LedgerReader().summarizeCorpus(home, projectId);
        check.eq("global source is the corpus", "corpus", global.get("source"));
        check.eq("global filter is the recorded project identity", projectId, global.get("project_id"));
        check.that("global mode is not incomplete for a well-formed corpus",
                !Boolean.TRUE.equals(global.get("incomplete")));
        Map<String, Object> globalMetrics = object(global.get("metrics"));
        check.eq("global reader returns the same calls after the tree is deleted",
                localCalls, number(object(globalMetrics.get("role_runs")).get("total")));
        check.eq("global reader returns the same cost after the tree is deleted",
                localCost, object(object(globalMetrics.get("telemetry")).get("cost_usd")).get("total"));

        Map<String, Object> outcomes = object(global.get("outcomes"));
        Map<String, Object> roleOk = object(outcomes.get("role_ok"));
        check.eq("role_ok is known for both role summaries", 2L, number(roleOk.get("known_count")));
        check.eq("both role summaries succeeded", 2L, number(roleOk.get("true")));
        Map<String, Object> workflow = object(outcomes.get("workflow_outcome"));
        check.eq("workflow outcome is known", 1L, number(workflow.get("known_count")));
        check.eq("workflow outcome stayed human_gate", 1L,
                number(object(workflow.get("by_value")).get("human_gate")));
        Map<String, Object> human = object(outcomes.get("human_accept"));
        check.eq("human accept is known", 1L, number(human.get("known_count")));
        check.eq("human accept is true", 1L, number(human.get("true")));

        Map<String, Object> instances = object(global.get("run_instances"));
        Map<String, Object> lineages = object(global.get("lineages"));
        check.eq("physical run instances are not merged", 2L, number(instances.get("count")));
        check.eq("continuations collapse to one lineage", 1L, number(lineages.get("count")));
        check.that("the parent instance is the lineage root",
                object(lineages.get("members")).containsKey(instanceA));

        Path copyHome = sandbox.resolve("g1-copy");
        copyTree(home.resolve("ledger"), copyHome.resolve("ledger"));
        Map<String, Object> copied = new LedgerReader().summarizeCorpus(copyHome, projectId);
        check.eq("a copied finished corpus returns the same calls from another path",
                localCalls, number(object(object(copied.get("metrics")).get("role_runs")).get("total")));
        check.eq("and the same cost",
                localCost, object(object(object(copied.get("metrics")).get("telemetry")).get("cost_usd")).get("total"));
        check.eq("and the same role_ok count",
                number(roleOk.get("true")),
                number(object(object(copied.get("outcomes")).get("role_ok")).get("true")));

        long bytesBefore = directorySize(home.resolve("ledger"));
        new LedgerReader().summarizeCorpus(home, projectId);
        check.eq("reading the corpus does not append to it",
                bytesBefore, directorySize(home.resolve("ledger")));
        check.that("a global read does not mint a project identity",
                !Files.exists(sandbox.resolve(".warden/runs/.project-identity")));

        Path outside = sandbox.resolve("g1-outside");
        Files.createDirectories(outside);
        Map<String, Object> cli = cliJson(outside, home, "ledger", "--global", "--project-id", projectId);
        check.eq("CLI --global outside a repository returns the same calls",
                localCalls, number(object(object(cli.get("metrics")).get("role_runs")).get("total")));
        check.eq("CLI --global outside a repository returns the same cost",
                localCost, object(object(object(cli.get("metrics")).get("telemetry")).get("cost_usd")).get("total"));
    }

    private void group6LegacyImport(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("imp-home");
        Path sample = Path.of("examples/demo/sample-evidence").toAbsolutePath().normalize();
        check.that("the committed demo evidence is present", Files.isDirectory(sample));

        Map<String, Object> first = CorpusImport.run(home, sample);
        Map<String, Object> firstRow = firstImport(first);
        check.eq("import names the projector version", CorpusImport.TRANSFORM_VERSION,
                firstRow.get("transform_version"));
        check.that("import records when it ran", firstRow.get("at") instanceof String);
        check.eq("the demo archive imported completely", true, firstRow.get("complete"));
        check.eq("two legacy rows were read", 2L, number(firstRow.get("read")));
        check.eq("both lacked an event id", 2L, number(firstRow.get("legacy")));
        check.eq("both were delivered", 2L, number(firstRow.get("delivered")));
        List<Map<String, Object>> afterFirst = HomeCorpus.records(home);
        check.eq("the corpus holds the two imported rows", 2, afterFirst.size());

        Map<String, Object> second = CorpusImport.run(home, sample);
        Map<String, Object> secondRow = firstImport(second);
        check.eq("re-importing the same source delivers nothing new", 0L, number(secondRow.get("delivered")));
        check.eq("re-importing the same source is duplicate", 2L, number(secondRow.get("duplicates")));
        check.eq("totals are unchanged after a second import", 2, HomeCorpus.records(home).size());

        Path copy = sandbox.resolve("sample-copy");
        copyTree(sample, copy);
        Map<String, Object> copied = CorpusImport.run(home, copy);
        Map<String, Object> copiedRow = firstImport(copied);
        check.eq("a copy at another path delivers nothing new", 0L, number(copiedRow.get("delivered")));
        check.eq("a copy at another path is duplicate", 2L, number(copiedRow.get("duplicates")));
        check.eq("totals are unchanged after importing a copy", 2, HomeCorpus.records(home).size());

        Path overlap = sandbox.resolve("overlap-archive");
        Files.createDirectories(overlap.resolve("demo-red"));
        Files.copy(sample.resolve("demo-red").resolve("evidence.jsonl"),
                overlap.resolve("demo-red").resolve("evidence.jsonl"));
        Files.createDirectories(overlap.resolve("extra"));
        Files.writeString(overlap.resolve("extra").resolve("evidence.jsonl"),
                "{\"at\":\"2026-09-01T00:00:00Z\",\"type\":\"machine_gate\",\"code\":\"passed\",\"ok\":true}\n",
                StandardCharsets.UTF_8);
        Map<String, Object> overlapped = CorpusImport.run(home, overlap);
        Map<String, Object> overlapRow = firstImport(overlapped);
        check.eq("an overlapping archive delivers only the new row", 1L, number(overlapRow.get("delivered")));
        check.that("and reports the overlap as duplicate", number(overlapRow.get("duplicates")) >= 1);
        check.eq("totals grew by the one new row", 3, HomeCorpus.records(home).size());
        CorpusImport.run(home, overlap);
        check.eq("re-importing the overlapping archive leaves totals unchanged",
                3, HomeCorpus.records(home).size());

        for (Map<String, Object> row : HomeCorpus.records(home)) {
            check.that("legacy import does not invent a contract hash", row.get("contract_sha256") == null);
            check.that("legacy import does not invent cost", row.get("cost_usd") == null);
            check.that("legacy import does not invent a run instance", row.get("run_instance_id") == null);
            check.that("legacy import does not invent lineage", row.get("lineage") == null);
            check.that("legacy import does not invent measurement context",
                    row.get("measurement_context") == null);
            check.that("legacy import does not carry the raw report path", row.get("report") == null);
            String body = Json.write(row);
            check.that("imported rows do not carry the demo absolute path",
                    !body.contains("C:\\\\Temp\\\\warden-demo") && !body.contains("C:/Temp/warden-demo"));
        }

        Path secretProject = sandbox.resolve("secret-src");
        Files.createDirectories(secretProject);
        String secretLine = Json.write(Map.of(
                "at", "2026-09-01T12:00:00Z",
                "type", "role_run",
                "ok", true,
                "code", "ok",
                "goal", SECRET,
                "prompt", SECRET,
                "report", "C:\\\\Temp\\\\warden-fixture\\\\.warden\\\\" + SECRET + ".json",
                "error", SECRET,
                "brand_new_field", SECRET));
        Files.writeString(secretProject.resolve("evidence.jsonl"), secretLine + "\n", StandardCharsets.UTF_8);
        CorpusImport.run(home, secretProject);
        String corpus = stringify(HomeCorpus.records(home));
        check.that("import does not admit goal, prompt, error or an unknown field",
                !corpus.contains(SECRET));

        Path ambiguousHome = sandbox.resolve("amb-home");
        Path amb = sandbox.resolve("amb-src");
        Files.createDirectories(amb);
        String shared = "{\"at\":\"2026-09-02T00:00:00Z\",\"type\":\"role_run\",\"code\":\"ok\",\"ok\":true";
        Files.writeString(amb.resolve("evidence.jsonl"),
                shared + ",\"duration_millis\":10}\n"
                        + shared + ",\"duration_millis\":99}\n",
                StandardCharsets.UTF_8);
        Map<String, Object> ambReceipt = firstImport(CorpusImport.run(ambiguousHome, amb));
        check.eq("an ambiguous legacy match delivers one row", 1L, number(ambReceipt.get("delivered")));
        check.eq("and flags the other rather than merging", 1L, number(ambReceipt.get("conflicts")));
        check.eq("ambiguous totals stay at one measurement", 1, HomeCorpus.records(ambiguousHome).size());
        check.that("the conflict is recorded", !HomeCorpus.conflicts(ambiguousHome).isEmpty());

        Path modern = sandbox.resolve("modern-src");
        Path modernHome = sandbox.resolve("modern-home");
        Files.createDirectories(modern);
        EvidenceLedger live = new EvidenceLedger(modern, "kept", modernHome);
        Map<String, Object> modernEvent = new LinkedHashMap<>();
        modernEvent.put("ok", true);
        modernEvent.put("code", "ok");
        modernEvent.put("role", "implementer");
        modernEvent.put("cost_usd", 2.0);
        live.append("role_run", modernEvent);
        int modernCount = HomeCorpus.records(modernHome).size();
        Path runDir = modern.resolve(".warden/runs/kept");
        CorpusImport.run(modernHome, runDir);
        CorpusImport.run(modernHome, runDir);
        Path elsewhere = sandbox.resolve("modern-copy-run");
        copyTree(runDir, elsewhere);
        CorpusImport.run(modernHome, elsewhere);
        check.eq("modern event_id import is idempotent across copies",
                modernCount, HomeCorpus.records(modernHome).size());

        Path cliHome = sandbox.resolve("imp-cli-home");
        Files.createDirectories(cliHome);
        Path cwd = sandbox.resolve("imp-cli-cwd");
        Files.createDirectories(cwd);
        Map<String, Object> cli = cliJson(cwd, cliHome, "ledger", "--import", sample.toString());
        check.eq("CLI --import reports the projector version",
                CorpusImport.TRANSFORM_VERSION, cli.get("transform_version"));
        check.eq("CLI --import delivers the two demo rows", 2L,
                number(firstImport(cli).get("delivered")));

        ProcessRunnerResult missing = cli(cwd, cliHome, "ledger", "--import",
                sandbox.resolve("no-such-import").toString());
        check.that("import of a missing path is rejected", missing.exit != 0);

        // P6AR-001: imported legacy rows have no project_id. They remain reportable
        // as the unknown-identity slice, not by inventing an id or attaching them to
        // a named project.
        Path unknownHome = sandbox.resolve("unknown-id-home");
        CorpusImport.run(unknownHome, sample);
        Map<String, Object> unknown = new LedgerReader().summarizeCorpus(unknownHome, null);
        check.eq("unknown-identity corpus names no project", null, unknown.get("project_id"));
        check.eq("unknown-identity source is the corpus", "corpus", unknown.get("source"));
        check.eq("legacy demo machine_gate failure remains measurable", 1L,
                number(object(object(unknown.get("metrics")).get("failures")).get("machine_gate")));
        check.eq("legacy demo rows have unknown run instances", 2L,
                number(object(unknown.get("run_instances")).get("unknown_count")));
        check.eq("legacy demo rows invent no priced calls", 0L,
                number(object(object(unknown.get("metrics")).get("role_runs")).get("total")));
        check.eq("legacy demo rows invent no spend", null,
                object(object(object(unknown.get("metrics")).get("telemetry")).get("cost_usd")).get("total"));
        Map<String, Object> named = new LedgerReader().summarizeCorpus(unknownHome, "no-such-project");
        check.eq("a named project does not absorb unknown-identity rows", 0L,
                number(object(object(named.get("metrics")).get("failures")).get("machine_gate")));
        check.eq("a named project does not inherit unknown run instances", 0L,
                number(object(named.get("run_instances")).get("unknown_count")));
        Path unknownCwd = sandbox.resolve("unknown-id-cwd");
        Files.createDirectories(unknownCwd);
        Map<String, Object> unknownCli = cliJson(unknownCwd, unknownHome, "ledger", "--global");
        check.eq("CLI --global without --project-id reports unknown identity",
                null, unknownCli.get("project_id"));
        check.eq("CLI --global without --project-id still sees the demo failure", 1L,
                number(object(object(unknownCli.get("metrics")).get("failures")).get("machine_gate")));

        // P6AR-003: historical role_run with nested attempts and no vendor_attempt
        // journal still counts those attempts once; a modern journal for the same
        // project is not expanded on top of them.
        Path histHome = sandbox.resolve("hist-home");
        Path histSrc = sandbox.resolve("hist-src");
        Files.createDirectories(histSrc);
        Map<String, Object> histFail = attempt("claude", "anthropic", "claude-test",
                false, "role_quota_exhausted", 50L, 0.25, 3L);
        Map<String, Object> histOk = attempt("codex", "openai", "gpt-test",
                true, "ok", 100L, 0.5, 30L);
        Map<String, Object> histRole = new LinkedHashMap<>();
        histRole.put("at", "2026-09-03T00:00:00Z");
        histRole.put("type", "role_run");
        histRole.put("ok", true);
        histRole.put("code", "ok");
        histRole.put("role", "implementer");
        histRole.put("project_id", "hist-P");
        histRole.put("vendor_attempts", List.of(histFail, histOk));
        Files.writeString(histSrc.resolve("evidence.jsonl"), Json.write(histRole) + "\n",
                StandardCharsets.UTF_8);
        Path histProject = sandbox.resolve("hist-project");
        Path histRun = histProject.resolve(".warden/runs/old");
        Files.createDirectories(histRun);
        Files.copy(histSrc.resolve("evidence.jsonl"), histRun.resolve("evidence.jsonl"));
        Map<String, Object> histLocal = new LedgerReader().summarize(histProject);
        long histCalls = number(object(object(histLocal.get("metrics")).get("role_runs")).get("total"));
        Object histCost = object(object(object(histLocal.get("metrics")).get("telemetry"))
                .get("cost_usd")).get("total");
        check.eq("local expansion of historical attempts is two calls", 2L, histCalls);
        CorpusImport.run(histHome, histSrc);
        Map<String, Object> histGlobal = new LedgerReader().summarizeCorpus(histHome, "hist-P");
        check.eq("global historical attempts match the local expansion",
                histCalls, number(object(object(histGlobal.get("metrics")).get("role_runs")).get("total")));
        check.eq("global historical cost matches the local expansion",
                histCost, object(object(object(histGlobal.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));

        Path modernSrc = sandbox.resolve("hist-modern-src");
        Files.createDirectories(modernSrc);
        Map<String, Object> modernFail = new LinkedHashMap<>(histFail);
        modernFail.put("at", "2026-09-03T01:00:00Z");
        modernFail.put("type", "vendor_attempt");
        modernFail.put("event_id", "modern-attempt-1");
        modernFail.put("project_id", "hist-P");
        modernFail.put("run_instance_id", "inst-modern");
        Map<String, Object> modernOk = new LinkedHashMap<>(histOk);
        modernOk.put("at", "2026-09-03T01:00:01Z");
        modernOk.put("type", "vendor_attempt");
        modernOk.put("event_id", "modern-attempt-2");
        modernOk.put("project_id", "hist-P");
        modernOk.put("run_instance_id", "inst-modern");
        Map<String, Object> modernRole = new LinkedHashMap<>();
        modernRole.put("at", "2026-09-03T01:00:02Z");
        modernRole.put("type", "role_run");
        modernRole.put("event_id", "modern-role-1");
        modernRole.put("ok", true);
        modernRole.put("code", "ok");
        modernRole.put("role", "implementer");
        modernRole.put("project_id", "hist-P");
        modernRole.put("run_instance_id", "inst-modern");
        modernRole.put("vendor_attempts", List.of(histFail, histOk));
        Files.writeString(modernSrc.resolve("evidence.jsonl"),
                Json.write(modernFail) + "\n" + Json.write(modernOk) + "\n"
                        + Json.write(modernRole) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(histHome, modernSrc);
        Map<String, Object> mixed = new LedgerReader().summarizeCorpus(histHome, "hist-P");
        check.eq("historical expansion plus a modern journal does not double-count",
                histCalls + 2L, number(object(object(mixed.get("metrics")).get("role_runs")).get("total")));

        // P6AR-003: distinct vendor_attempt_id values stay measurable even when
        // every record lacks run identity. An anonymous journaled attempt must
        // not suppress unrelated historical nested attempts.
        Path anonHome = sandbox.resolve("p6ar003-home");
        Path histAnon = sandbox.resolve("p6ar003-hist");
        Files.createDirectories(histAnon);
        Map<String, Object> nestedA = new LinkedHashMap<>(histFail);
        nestedA.put("vendor_attempt_id", "A");
        Map<String, Object> nestedB = new LinkedHashMap<>(histOk);
        nestedB.put("vendor_attempt_id", "B");
        Map<String, Object> histAnonRole = new LinkedHashMap<>();
        histAnonRole.put("at", "2026-09-06T00:00:00Z");
        histAnonRole.put("type", "role_run");
        histAnonRole.put("ok", true);
        histAnonRole.put("code", "ok");
        histAnonRole.put("role", "implementer");
        histAnonRole.put("project_id", "P");
        histAnonRole.put("vendor_attempts", List.of(nestedA, nestedB));
        Files.writeString(histAnon.resolve("evidence.jsonl"), Json.write(histAnonRole) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(anonHome, histAnon);
        Map<String, Object> beforeC = new LedgerReader().summarizeCorpus(anonHome, "P");
        check.eq("anonymous historical nested attempts are two calls",
                2L, number(object(object(beforeC.get("metrics")).get("role_runs")).get("total")));
        check.eq("anonymous historical nested cost is 0.75",
                0.75, object(object(object(beforeC.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));

        Path journalC = sandbox.resolve("p6ar003-c");
        Files.createDirectories(journalC);
        Map<String, Object> attemptC = new LinkedHashMap<>();
        attemptC.put("at", "2026-09-06T00:00:01Z");
        attemptC.put("type", "vendor_attempt");
        attemptC.put("event_id", "attempt-C");
        attemptC.put("vendor_attempt_id", "C");
        attemptC.put("project_id", "P");
        attemptC.put("ok", true);
        attemptC.put("code", "ok");
        attemptC.put("role", "implementer");
        attemptC.put("cost_usd", 1.0);
        Files.writeString(journalC.resolve("evidence.jsonl"), Json.write(attemptC) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(anonHome, journalC);
        Map<String, Object> afterC = new LedgerReader().summarizeCorpus(anonHome, "P");
        check.eq("distinct anonymous attempts A, B and C remain three calls",
                3L, number(object(object(afterC.get("metrics")).get("role_runs")).get("total")));
        check.eq("distinct anonymous attempt costs sum rather than drop A and B",
                1.75, object(object(object(afterC.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));
        check.that("distinct attempt ids are not an ambiguous merge",
                !afterC.containsKey("ambiguous_coverage"));

        Path matchHome = sandbox.resolve("p6ar003-match-home");
        CorpusImport.run(matchHome, histAnon);
        Path journalA = sandbox.resolve("p6ar003-a");
        Files.createDirectories(journalA);
        Map<String, Object> attemptA = new LinkedHashMap<>(attemptC);
        attemptA.put("event_id", "attempt-A");
        attemptA.put("vendor_attempt_id", "A");
        attemptA.put("cost_usd", 0.25);
        Files.writeString(journalA.resolve("evidence.jsonl"), Json.write(attemptA) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(matchHome, journalA);
        Map<String, Object> matched = new LedgerReader().summarizeCorpus(matchHome, "P");
        check.eq("a journaled attempt id covers only that nested attempt",
                2L, number(object(object(matched.get("metrics")).get("role_runs")).get("total")));

        Path ambCovHome = sandbox.resolve("p6ar003-amb-home");
        Path ambHist = sandbox.resolve("p6ar003-amb-hist");
        Files.createDirectories(ambHist);
        Map<String, Object> ambRole = new LinkedHashMap<>(histAnonRole);
        ambRole.put("vendor_attempts", List.of(histFail, histOk));
        Files.writeString(ambHist.resolve("evidence.jsonl"), Json.write(ambRole) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(ambCovHome, ambHist);
        Path ambJournal = sandbox.resolve("p6ar003-amb-journal");
        Files.createDirectories(ambJournal);
        Map<String, Object> unlinked = new LinkedHashMap<>(attemptC);
        unlinked.remove("vendor_attempt_id");
        unlinked.put("event_id", "unlinked-attempt");
        Files.writeString(ambJournal.resolve("evidence.jsonl"), Json.write(unlinked) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(ambCovHome, ambJournal);
        Map<String, Object> ambCov = new LedgerReader().summarizeCorpus(ambCovHome, "P");
        check.eq("unlinked historical attempts plus an unlinked journal stay measurable",
                3L, number(object(object(ambCov.get("metrics")).get("role_runs")).get("total")));
        check.that("an unlinked relationship is flagged rather than merged",
                number(ambCov.get("ambiguous_coverage")) >= 1);

        // P6AR-005: an id on only one representation does not prove two distinct calls.
        Path asymHome = sandbox.resolve("p6ar005-home");
        Path nestedNoId = sandbox.resolve("p6ar005-nested");
        Files.createDirectories(nestedNoId);
        Map<String, Object> nestedAnon = attempt("codex", "openai", "gpt-test",
                true, "ok", 100L, 1.0, 30L);
        Map<String, Object> nestedAnonRole = new LinkedHashMap<>();
        nestedAnonRole.put("at", "2026-09-07T00:00:00Z");
        nestedAnonRole.put("type", "role_run");
        nestedAnonRole.put("ok", true);
        nestedAnonRole.put("code", "ok");
        nestedAnonRole.put("role", "implementer");
        nestedAnonRole.put("project_id", "P");
        nestedAnonRole.put("vendor_attempts", List.of(nestedAnon));
        Files.writeString(nestedNoId.resolve("evidence.jsonl"), Json.write(nestedAnonRole) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(asymHome, nestedNoId);
        Path journalIdentified = sandbox.resolve("p6ar005-journal-c");
        Files.createDirectories(journalIdentified);
        Map<String, Object> journalCOnly = new LinkedHashMap<>();
        journalCOnly.put("at", "2026-09-07T00:00:01Z");
        journalCOnly.put("type", "vendor_attempt");
        journalCOnly.put("event_id", "attempt-C-asym");
        journalCOnly.put("vendor_attempt_id", "C");
        journalCOnly.put("project_id", "P");
        journalCOnly.put("ok", true);
        journalCOnly.put("code", "ok");
        journalCOnly.put("role", "implementer");
        journalCOnly.put("cost_usd", 1.0);
        Files.writeString(journalIdentified.resolve("evidence.jsonl"), Json.write(journalCOnly) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(asymHome, journalIdentified);
        Map<String, Object> asym = new LedgerReader().summarizeCorpus(asymHome, "P");
        check.eq("asymmetric nested-unidentified plus journal C stays measurable",
                2L, number(object(object(asym.get("metrics")).get("role_runs")).get("total")));
        check.that("an id on the journal only is flagged rather than treated as two calls",
                number(asym.get("ambiguous_coverage")) >= 1);

        Path reverseHome = sandbox.resolve("p6ar005-reverse-home");
        Path nestedIdentified = sandbox.resolve("p6ar005-nested-a");
        Files.createDirectories(nestedIdentified);
        Map<String, Object> nestedAOnly = new LinkedHashMap<>(nestedAnon);
        nestedAOnly.put("vendor_attempt_id", "A");
        Map<String, Object> nestedARole = new LinkedHashMap<>(nestedAnonRole);
        nestedARole.put("vendor_attempts", List.of(nestedAOnly));
        Files.writeString(nestedIdentified.resolve("evidence.jsonl"), Json.write(nestedARole) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(reverseHome, nestedIdentified);
        Path journalUnlinkedAsym = sandbox.resolve("p6ar005-journal-unlinked");
        Files.createDirectories(journalUnlinkedAsym);
        Map<String, Object> journalNoId = new LinkedHashMap<>(journalCOnly);
        journalNoId.remove("vendor_attempt_id");
        journalNoId.put("event_id", "attempt-unlinked-asym");
        Files.writeString(journalUnlinkedAsym.resolve("evidence.jsonl"), Json.write(journalNoId) + "\n",
                StandardCharsets.UTF_8);
        CorpusImport.run(reverseHome, journalUnlinkedAsym);
        Map<String, Object> reverse = new LedgerReader().summarizeCorpus(reverseHome, "P");
        check.eq("asymmetric nested A plus unlinked journal stays measurable",
                2L, number(object(object(reverse.get("metrics")).get("role_runs")).get("total")));
        check.that("an id on the nested attempt only is flagged rather than treated as two calls",
                number(reverse.get("ambiguous_coverage")) >= 1);

        Path unitHome = sandbox.resolve("unit-unknown-home");
        Path unitSrc = sandbox.resolve("unit-unknown-src");
        Files.createDirectories(unitSrc);
        Files.writeString(unitSrc.resolve("evidence.jsonl"), Json.write(Map.of(
                "at", "2026-09-03T02:00:00Z",
                "type", "role_run",
                "ok", true,
                "code", "ok",
                "role", "implementer",
                "project_id", "unit-P",
                "cost_usd", 9.0)) + "\n", StandardCharsets.UTF_8);
        CorpusImport.run(unitHome, unitSrc);
        Map<String, Object> unitGlobal = new LedgerReader().summarizeCorpus(unitHome, "unit-P");
        check.eq("a role_run whose accounting unit cannot be established is not a call",
                0L, number(object(object(unitGlobal.get("metrics")).get("role_runs")).get("total")));
        check.that("and its spend is unknown rather than an exact total",
                number(object(object(object(unitGlobal.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("unknown_count")) >= 1);
        check.eq("and is not added as known spend",
                null, object(object(object(unitGlobal.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));
    }

    private void group9Corruption(Check check, Path sandbox) throws Exception {
        Path project = sandbox.resolve("corr-local");
        EvidenceLedger ledger = new EvidenceLedger(project, "ok");
        Map<String, Object> priced = new LinkedHashMap<>();
        priced.put("ok", true);
        priced.put("code", "ok");
        priced.put("role", "implementer");
        priced.put("cost_usd", 1.5);
        ledger.append("machine_gate", Map.of("ok", true, "code", "passed"));
        ledger.append("role_run", priced);
        Path evidence = project.resolve(".warden/runs/ok/evidence.jsonl");
        List<String> lines = Files.readAllLines(evidence, StandardCharsets.UTF_8);
        check.that("the well-formed local file has two events", lines.size() >= 2);
        StringBuilder damaged = new StringBuilder();
        damaged.append(lines.get(0)).append('\n');
        damaged.append("NOT JSON\n");
        damaged.append("{\"type\":\"machine_gate\",\"ok\":false,\"code\":\"failed\",\"schema_version\":99}\n");
        damaged.append(lines.get(1)).append('\n');
        damaged.append("{\"truncated\":true");
        Files.writeString(evidence, damaged.toString(), StandardCharsets.UTF_8);

        Map<String, Object> local = new LedgerReader().summarize(project);
        check.eq("a damaged local tree is marked incomplete", true, local.get("incomplete"));
        Map<String, Object> skipped = object(local.get("skipped"));
        check.that("skipped count is at least the three bad lines", number(skipped.get("count")) >= 3);
        List<?> records = (List<?>) skipped.get("records");
        check.that("each skip names the file", records.stream().allMatch(item ->
                item instanceof Map<?, ?> map && String.valueOf(map.get("file")).contains("evidence.jsonl")));
        check.that("each skip names a byte offset", records.stream().allMatch(item ->
                item instanceof Map<?, ?> map && map.get("offset") instanceof Number));
        check.that("a middle corrupt line is reported", records.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && "corrupt".equals(map.get("reason"))));
        check.that("an unsupported schema version is reported", records.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && "unsupported_schema_version".equals(map.get("reason"))));
        check.that("a truncated last line is reported", records.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && "truncated".equals(map.get("reason"))));
        check.eq("an incomplete local report withholds exact spend",
                null, object(object(object(local.get("metrics")).get("telemetry")).get("cost_usd")).get("total"));
        check.that("an incomplete local report does not print a success rate",
                !local.containsKey("success_rate"));
        check.that("readable local events still produce a report", local.get("run_count") instanceof Number);

        Path home = sandbox.resolve("corr-home");
        Path corpusProject = sandbox.resolve("corr-corpus-project");
        Files.createDirectories(corpusProject);
        EvidenceLedger shared = new EvidenceLedger(corpusProject, "keep", home);
        shared.append("role_run", priced);
        String projectId = shared.projectId();
        Path segments = HomeCorpus.directory(home).resolve(HomeCorpus.SEGMENTS);
        Path segment;
        try (var files = Files.list(segments)) {
            segment = files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst()
                    .orElseThrow();
        }
        String good = Files.readString(segment, StandardCharsets.UTF_8);
        Files.writeString(segment, good + "NOT JSON\n{\"schema_version\":2,\"type\":\"role_run\",\"ok\":true}\n{\"trunc",
                StandardCharsets.UTF_8);
        Map<String, Object> global = new LedgerReader().summarizeCorpus(home, projectId);
        check.eq("a damaged corpus is marked incomplete", true, global.get("incomplete"));
        check.eq("an incomplete corpus withholds exact spend",
                null, object(object(object(global.get("metrics")).get("telemetry")).get("cost_usd")).get("total"));
        check.that("an incomplete corpus does not print a success rate",
                !global.containsKey("success_rate"));
        Map<String, Object> globalSkipped = object(global.get("skipped"));
        check.that("corpus skips name the segment file",
                String.valueOf(globalSkipped.get("records")).contains("segments/"));
        check.that("readable corpus events remain",
                number(object(object(global.get("metrics")).get("role_runs")).get("total")) >= 0);

        // P6AR-002: an incomplete import records skips on the receipt. The subsequent
        // global report must retain that incompleteness even though segments only
        // contain the rows that parsed.
        Path incHome = sandbox.resolve("inc-import-home");
        Path incSrc = sandbox.resolve("inc-import-src");
        Files.createDirectories(incSrc);
        Map<String, Object> pricedAttempt = new LinkedHashMap<>();
        pricedAttempt.put("at", "2026-09-04T00:00:00Z");
        pricedAttempt.put("type", "vendor_attempt");
        pricedAttempt.put("event_id", "inc-attempt");
        pricedAttempt.put("project_id", "inc-P");
        pricedAttempt.put("run_instance_id", "inc-inst");
        pricedAttempt.put("ok", true);
        pricedAttempt.put("code", "ok");
        pricedAttempt.put("role", "implementer");
        pricedAttempt.put("cost_usd", 1.25);
        Map<String, Object> otherEvent = new LinkedHashMap<>();
        otherEvent.put("at", "2026-09-04T00:00:01Z");
        otherEvent.put("type", "machine_gate");
        otherEvent.put("event_id", "inc-gate");
        otherEvent.put("project_id", "inc-P");
        otherEvent.put("ok", true);
        otherEvent.put("code", "passed");
        Files.writeString(incSrc.resolve("evidence.jsonl"),
                Json.write(pricedAttempt) + "\nNOT JSON\n" + Json.write(otherEvent) + "\n",
                StandardCharsets.UTF_8);
        Map<String, Object> incReceipt = firstImport(CorpusImport.run(incHome, incSrc));
        check.eq("incomplete import is recorded on the receipt", false, incReceipt.get("complete"));
        check.that("import skipped the corrupt source line",
                number(object(incReceipt.get("skipped")).get("count")) >= 1);
        Map<String, Object> incGlobal = new LedgerReader().summarizeCorpus(incHome, "inc-P");
        check.eq("global report retains the import's incomplete status", true, incGlobal.get("incomplete"));
        check.eq("incomplete import withholds exact total spend",
                null, object(object(object(incGlobal.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));
        Map<String, Object> incSkipped = object(incGlobal.get("skipped"));
        check.that("import skip count survives into the report", number(incSkipped.get("count")) >= 1);
        List<?> incRecords = (List<?>) incSkipped.get("records");
        check.that("import skip names the source file", incRecords.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && String.valueOf(map.get("file")).contains("evidence.jsonl")));
        check.that("import skip names a byte offset", incRecords.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && map.get("offset") instanceof Number));
        check.that("surviving priced attempt is still a call",
                number(object(object(incGlobal.get("metrics")).get("role_runs")).get("total")) >= 1);
        check.that("an incomplete import does not print a success rate",
                !incGlobal.containsKey("success_rate"));

        // P6AR-002: importing a second file that contains only a project-P row
        // with an unsupported schema must still mark the named-P report
        // incomplete. The skipped row never reaches the segment, so identity
        // has to be recovered from the skip itself; failing to recover a
        // later corrupt line must not establish that the loss cannot affect P.
        Path skipHome = sandbox.resolve("p6ar002-home");
        Path pricedSrc = sandbox.resolve("p6ar002-priced");
        Files.createDirectories(pricedSrc);
        Map<String, Object> surviving = new LinkedHashMap<>();
        surviving.put("at", "2026-09-05T00:00:00Z");
        surviving.put("type", "vendor_attempt");
        surviving.put("event_id", "p6ar002-ok");
        surviving.put("project_id", "P");
        surviving.put("run_instance_id", "p6ar002-inst");
        surviving.put("ok", true);
        surviving.put("code", "ok");
        surviving.put("role", "implementer");
        surviving.put("cost_usd", 1.0);
        Files.writeString(pricedSrc.resolve("evidence.jsonl"),
                Json.write(surviving) + "\n", StandardCharsets.UTF_8);
        CorpusImport.run(skipHome, pricedSrc);
        Map<String, Object> pricedOnly = new LedgerReader().summarizeCorpus(skipHome, "P");
        check.that("a complete priced import is not incomplete",
                !Boolean.TRUE.equals(pricedOnly.get("incomplete")));
        check.eq("the surviving priced attempt is one dollar before the skip",
                1.0, object(object(object(pricedOnly.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));

        Path badSrc = sandbox.resolve("p6ar002-unsupported");
        Files.createDirectories(badSrc);
        Map<String, Object> unsupported = new LinkedHashMap<>();
        unsupported.put("at", "2026-09-05T00:00:01Z");
        unsupported.put("type", "vendor_attempt");
        unsupported.put("event_id", "p6ar002-bad");
        unsupported.put("project_id", "P");
        unsupported.put("schema_version", 99L);
        unsupported.put("ok", true);
        unsupported.put("code", "ok");
        unsupported.put("role", "implementer");
        unsupported.put("cost_usd", 4.0);
        Files.writeString(badSrc.resolve("evidence.jsonl"),
                Json.write(unsupported) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> badReceipt = firstImport(CorpusImport.run(skipHome, badSrc));
        check.eq("unsupported-schema import is incomplete", false, badReceipt.get("complete"));
        check.that("unsupported-schema import recovered project P from the skipped row",
                ((List<?>) badReceipt.get("project_ids")).contains("P"));
        Map<String, Object> namedP = new LedgerReader().summarizeCorpus(skipHome, "P");
        check.eq("named P is incomplete after an unreadable P row", true, namedP.get("incomplete"));
        check.eq("named P withholds exact spend after the skipped P row",
                null, object(object(object(namedP.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));
        check.eq("the surviving priced attempt is still a call",
                1L, number(object(object(namedP.get("metrics")).get("role_runs")).get("total")));
        Map<String, Object> namedSkipped = object(namedP.get("skipped"));
        check.that("the skip names unsupported_schema_version",
                String.valueOf(namedSkipped.get("records")).contains("unsupported_schema_version"));
        check.that("the skip names a byte offset",
                ((List<?>) namedSkipped.get("records")).stream().anyMatch(item ->
                        item instanceof Map<?, ?> map && map.get("offset") instanceof Number));
        Map<String, Object> namedQ = new LedgerReader().summarizeCorpus(skipHome, "Q");
        check.that("a skip recovered as P does not mark unrelated Q incomplete",
                !Boolean.TRUE.equals(namedQ.get("incomplete")));

        Path corruptSrc = sandbox.resolve("p6ar002-corrupt");
        Files.createDirectories(corruptSrc);
        Files.writeString(corruptSrc.resolve("evidence.jsonl"), "NOT JSON\n", StandardCharsets.UTF_8);
        Map<String, Object> corruptReceipt = firstImport(CorpusImport.run(skipHome, corruptSrc));
        check.eq("corrupt-only import is incomplete", false, corruptReceipt.get("complete"));
        check.that("corrupt-only import did not recover a project id",
                number(corruptReceipt.get("unattributed_skipped")) >= 1);
        Map<String, Object> stillP = new LedgerReader().summarizeCorpus(skipHome, "P");
        check.eq("named P stays incomplete when a skip's identity was not recovered",
                true, stillP.get("incomplete"));
        Map<String, Object> stillQ = new LedgerReader().summarizeCorpus(skipHome, "Q");
        check.eq("an unrecovered skip also marks another named project incomplete",
                true, stillQ.get("incomplete"));
        check.eq("an unrecovered skip withholds exact spend on that other project too",
                null, object(object(object(stillQ.get("metrics")).get("telemetry"))
                        .get("cost_usd")).get("total"));
    }

    private void group10Regression(Check check, Path sandbox) throws Exception {
        Path root = sandbox.resolve("local-well-formed");
        new EvidenceLedger(root, "pass").append("machine_gate", Map.of("ok", true, "code", "passed"));
        new EvidenceLedger(root, "fail").append("machine_gate", Map.of("ok", false, "code", "failed"));
        Map<String, Object> summary = new LedgerReader().summarize(root);
        check.eq("plain ledger still reports schema 1", 1L, summary.get("schema_version"));
        check.that("plain ledger still has run_count, passed, failed, runs, metrics",
                summary.containsKey("run_count") && summary.containsKey("passed")
                        && summary.containsKey("failed") && summary.containsKey("runs")
                        && summary.containsKey("metrics"));
        check.eq("two local runs are still counted", 2L, summary.get("run_count"));
        check.eq("one passed", 1L, summary.get("passed"));
        check.eq("one failed", 1L, summary.get("failed"));
        check.that("well-formed local output is not marked incomplete",
                !summary.containsKey("incomplete") && !summary.containsKey("skipped"));
        check.that("no global mode leaked into the default summary",
                !summary.containsKey("global") && !summary.containsKey("source")
                        && !String.valueOf(summary).contains("--global"));

        Path demo = sandbox.resolve("demo-project");
        Files.createDirectories(demo.resolve(".warden"));
        Files.writeString(demo.resolve(".warden/project.yaml"), """
                version: 1
                project: demo-ledger
                base_ref: HEAD
                checks:
                  fast: ["true"]
                scopes:
                  app: ["."]
                defaults:
                  checks: fast
                  risk: medium
                """, StandardCharsets.UTF_8);
        Path sample = Path.of("examples/demo/sample-evidence");
        copyTree(sample.resolve("demo-red"), demo.resolve(".warden/runs/demo-red"));
        copyTree(sample.resolve("demo-green"), demo.resolve(".warden/runs/demo-green"));
        Path demoHome = sandbox.resolve("demo-home");
        Files.createDirectories(demoHome);
        Map<String, Object> demoLedger = cliJson(demo, demoHome, "ledger");
        check.eq("demo-style no-argument ledger counts both runs", 2L, demoLedger.get("run_count"));
        check.eq("demo-style no-argument ledger counts the green run", 1L, demoLedger.get("passed"));
        check.eq("demo-style no-argument ledger counts the red run", 1L, demoLedger.get("failed"));
        check.eq("demo-style vendor fields stay empty", 0L,
                number(object(object(demoLedger.get("metrics")).get("role_runs")).get("total")));
        check.eq("demo-style machine_gate failure is counted", 1L,
                number(object(object(demoLedger.get("metrics")).get("failures")).get("machine_gate")));
        check.that("demo-style no-argument ledger is not global",
                !demoLedger.containsKey("source") && !demoLedger.containsKey("project_id"));

        Path helpCwd = sandbox.resolve("help-cwd");
        Files.createDirectories(helpCwd);
        ProcessRunnerResult help = cli(helpCwd, demoHome, "--help");
        check.eq("help exits 0", 0, help.exit);
        check.that("help documents --global", help.stdout.contains("--global"));
        check.that("help documents --import", help.stdout.contains("--import"));
        check.that("help documents --project-id", help.stdout.contains("--project-id"));

        Path empty = sandbox.resolve("empty-cwd");
        Files.createDirectories(empty);
        ProcessRunnerResult unknown = cli(empty, demoHome, "ledger", "--not-a-flag");
        check.that("unknown ledger arguments are rejected", unknown.exit != 0);

        Path noIdentity = sandbox.resolve("no-identity");
        Files.createDirectories(noIdentity);
        check.eq("recorded identity does not mint one", null, ProjectIdentity.recorded(noIdentity));
        check.that("and does not write .project-identity",
                !Files.exists(noIdentity.resolve(".warden/runs/.project-identity")));

        Path pendingProject = sandbox.resolve("pending-project");
        Path pendingHome = sandbox.resolve("pending-home");
        Files.createDirectories(pendingProject);
        EvidenceLedger pending = new EvidenceLedger(pendingProject, "pend", null);
        pending.append("role_run", Map.of("ok", true, "code", "ok", "role", "implementer"));
        Path outbox = pendingProject.resolve(".warden/runs/pend/outbox.jsonl");
        check.that("a no-home write left a journal", Files.isRegularFile(outbox));
        long outboxSize = Files.size(outbox);
        Files.createDirectories(pendingHome);
        new LedgerReader().summarizeCorpus(pendingHome, "no-such-project");
        check.eq("a global read does not recover a journal", outboxSize, Files.size(outbox));
        check.eq("and does not deliver it", 0, HomeCorpus.records(pendingHome).size());
    }

    private static Map<String, Object> attempt(String profile, String vendor, String model,
                                               boolean ok, String code, long duration,
                                               double cost, long tokens) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ok", ok);
        row.put("code", code);
        row.put("role", "implementer");
        row.put("profile", profile);
        row.put("vendor", vendor);
        row.put("model", model);
        row.put("runner", "direct");
        row.put("duration_millis", duration);
        row.put("cost_usd", cost);
        row.put("tokens", Map.of("input", 1L, "output", 2L, "total", tokens));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstImport(Map<String, Object> body) {
        Object imports = body.get("imports");
        if (imports instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) return 0L;
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
        }
        return total;
    }

    private record ProcessRunnerResult(int exit, String stdout, String stderr) {}

    private static Map<String, Object> cliJson(Path cwd, Path home, String... args) throws Exception {
        ProcessRunnerResult result = cli(cwd, home, args);
        if (result.exit != 0) {
            throw new IllegalStateException("cli exited " + result.exit
                    + " stdout=" + result.stdout + " stderr=" + result.stderr);
        }
        String stdout = result.stdout == null ? "" : result.stdout.strip();
        if (stdout.isEmpty()) {
            throw new IllegalStateException("empty stdout: " + result.stderr);
        }
        return Json.parseObject(stdout);
    }

    private static ProcessRunnerResult cli(Path cwd, Path home, String... args) throws Exception {
        Files.createDirectories(cwd);
        Files.createDirectories(home);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add("dev.warden.Main");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.environment().put("WARDEN_CONFIG_HOME", home.toAbsolutePath().normalize().toString());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("cli timed out");
        }
        return new ProcessRunnerResult(process.exitValue(), stdout, stderr);
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        StringBuilder builder = new StringBuilder();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            last = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        path.toFile().setWritable(true);
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException retry) {
                            last = retry;
                        }
                    }
                }
            } catch (IOException failure) {
                last = failure;
            }
            if (last == null || !Files.exists(root)) return;
            try {
                Thread.sleep(50L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null) throw last;
    }
}
