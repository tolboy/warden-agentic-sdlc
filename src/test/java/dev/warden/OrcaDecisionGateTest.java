package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.execution.orca.OrcaDecisionGate;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gate bridge, on the two facts measured against Orca 1.4.196: a resolution is free text,
 * and a resolved gate can be resolved again with a different answer.
 *
 * Both are fine for a coordination primitive and fatal for an authorization record, so what is
 * tested here is that neither reaches {@code decision.json}.
 */
public final class OrcaDecisionGateTest implements Suite {

    @Override public String name() { return "orca-decision-gate"; }

    @Override public void run(Check check) throws Exception {
        var listed = Json.parseObject("""
                {"ok":true,"result":{"runId":"run_1","gates":[
                  {"id":"gate_1","run_id":"run_1","task_id":"task_1",
                   "question":"Accept?","options":"[\\"accept\\",\\"reject\\"]",
                   "status":"pending","resolution":null}]}}
                """);
        OrcaDecisionGate.Answer pending = OrcaDecisionGate.answerFrom(listed, "gate_1");
        check.eq("a pending gate is found", "pending", pending.status());
        check.eq("and is not resolved", false, pending.resolved());
        check.eq("another gate id finds nothing", null,
                OrcaDecisionGate.answerFrom(listed, "gate_2"));

        var resolved = Json.parseObject("""
                {"ok":true,"result":{"gates":[
                  {"id":"gate_1","status":"resolved","resolution":"accept",
                   "options":"[\\"accept\\",\\"reject\\"]"}]}}
                """);
        check.eq("a resolved gate is resolved", true,
                OrcaDecisionGate.answerFrom(resolved, "gate_1").resolved());
        check.eq("an unreadable envelope answers nothing", null,
                OrcaDecisionGate.answerFrom(Json.parseObject("{\"ok\":false}"), "gate_1"));

        List<String> options = List.of("accept", "reject");
        check.eq("an exact option is taken", "accept",
                OrcaDecisionGate.choose("accept", options));
        check.eq("case and space are forgiven, because a person typed it", "reject",
                OrcaDecisionGate.choose("  Reject \n", options));
        check.eq("a sentence containing an option is not an option", null,
                OrcaDecisionGate.choose("accept if the tests pass", options));
        check.eq("an option from another kind is refused", null,
                OrcaDecisionGate.choose("retry", options));
        check.eq("an empty resolution is refused", null, OrcaDecisionGate.choose("", options));
        check.eq("a missing resolution is refused", null, OrcaDecisionGate.choose(null, options));

        // The question a person is shown names the run, the reason and the exact words that
        // will be accepted back. Orca will take anything; Warden will not.
        Path root = Files.createTempDirectory("warden-gate");
        ApprovalStore store = new ApprovalStore(root);
        Path summary = root.resolve(".warden/runs/run-1/task-run.json");
        Files.createDirectories(summary.getParent());
        Files.writeString(summary, "{}");
        HumanDecision decision = store.createSuccess("run-1", "task-1",
                "every stage that ran passed: implementer, gates", summary, "fingerprint-1");
        String question = OrcaDecisionGate.question(decision);
        check.eq("the question names the run", true, question.contains("run-1"));
        check.eq("the question names the options", true, question.contains("accept or reject"));
        check.eq("the question says nothing lands", true, question.contains("Nothing is landed"));

        // A gate answered a second time cannot flip a decision that is already recorded: the
        // store refuses the duplicate. This is the whole reason the gate is not the record.
        store.resolve("run-1", decision.updatedAt().toString(), "accept", "orca-gate:gate_1", "");
        boolean refused = false;
        try {
            store.resolve("run-1", decision.updatedAt().toString(), "reject", "orca-gate:gate_1", "");
        } catch (Exception expected) {
            refused = true;
        }
        check.eq("a second answer cannot overwrite a recorded decision", true, refused);

        waiterResolvesOnTheThirdPoll(check);
        waiterDeadlineLeavesTheDecisionUntouched(check);
        waiterStaleFailsWithoutWaiting(check);
        waiterBackoffSequence(check);
        waitForGateSkipsWhenNoneWasPublished(check);
    }

    /**
     * Three pending/resolved reads, one import, same recorded decision as a plain
     * {@code --from-orca} that already had the answer.
     */
    private void waiterResolvesOnTheThirdPoll(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-wait-third-");
        Path home = root.resolve("config-home");
        Files.createDirectories(home);
        try {
            Fixture pending = fixture(root, "wait-third");
            ScriptedGate script = new ScriptedGate(
                    pendingAnswer(pending.gateId),
                    pendingAnswer(pending.gateId),
                    resolvedAnswer(pending.gateId, "abort"));
            FakeTime time = new FakeTime();
            Main.ApproveEnv waitEnv = new Main.ApproveEnv(time, time, script, home);
            Main.ApproveOutcome waited = Main.approveDecision(root, new String[] {
                    "approve", "wait-third", "--from-orca", "--wait-minutes", "1",
                    "--no-workspace-status"
            }, waitEnv);
            check.eq("third-poll wait records the decision", true, waited.ok());
            check.eq("with the same code as a plain import", "decision_recorded",
                    waited.report().get("code"));
            check.eq("and imported abort", "abort",
                    ((Map<?, ?>) waited.report().get("decision")).get("decision"));
            check.eq("the gate was read three times", 3, script.reads);
            check.eq("sleeps were 5s then 7.5s", List.of(5_000L, 7_500L), time.sleeps);

            Path other = Files.createTempDirectory("warden-wait-plain-");
            Path otherHome = other.resolve("config-home");
            Files.createDirectories(otherHome);
            try {
                Fixture already = fixture(other, "wait-plain");
                ScriptedGate immediate = new ScriptedGate(resolvedAnswer(already.gateId, "abort"));
                FakeTime plainTime = new FakeTime();
                Main.ApproveOutcome plain = Main.approveDecision(other, new String[] {
                        "approve", "wait-plain", "--from-orca", "--no-workspace-status"
                }, new Main.ApproveEnv(plainTime, plainTime, immediate, otherHome));
                check.eq("plain --from-orca also records the decision", true, plain.ok());
                check.eq("with the same code", waited.report().get("code"), plain.report().get("code"));
                check.eq("and the same choice",
                        ((Map<?, ?>) waited.report().get("decision")).get("decision"),
                        ((Map<?, ?>) plain.report().get("decision")).get("decision"));
                check.eq("plain import does not sleep", List.of(), plainTime.sleeps);
                check.eq("and reads once", 1, immediate.reads);
            } finally {
                deleteTree(other);
            }
        } finally {
            deleteTree(root);
        }
    }

    private void waiterDeadlineLeavesTheDecisionUntouched(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-wait-deadline-");
        Path home = root.resolve("config-home");
        Files.createDirectories(home);
        try {
            Fixture pending = fixture(root, "wait-deadline");
            Path decisionFile = new ApprovalStore(root).decisionPath("wait-deadline");
            byte[] before = Files.readAllBytes(decisionFile);
            ScriptedGate alwaysPending = new ScriptedGate(pendingAnswer(pending.gateId));
            FakeTime time = new FakeTime();
            time.jumpOnSleepTo = 60_000L;
            Main.ApproveOutcome timedOut = Main.approveDecision(root, new String[] {
                    "approve", "wait-deadline", "--from-orca", "--wait-minutes", "1",
                    "--no-workspace-status"
            }, new Main.ApproveEnv(time, time, alwaysPending, home));
            check.eq("a deadline is not ok", false, timedOut.ok());
            check.eq("and names gate_pending", "gate_pending", timedOut.report().get("code"));
            check.that("records how long it waited", timedOut.report().get("waited_seconds") instanceof Number);
            check.that("records the poll count", timedOut.report().get("polls") instanceof Number);
            check.that("records the last error", timedOut.report().get("last_error") instanceof String);
            check.that("names a next step", timedOut.report().get("next_step") != null);
            check.eq("and leaves the decision file byte-identical", true,
                    java.util.Arrays.equals(before, Files.readAllBytes(decisionFile)));
            HumanDecision still = new ApprovalStore(root).read("wait-deadline");
            check.eq("still pending", "pending", still.state().jsonValue());
        } finally {
            deleteTree(root);
        }
    }

    private void waiterStaleFailsWithoutWaiting(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-wait-stale-");
        Path home = root.resolve("config-home");
        Files.createDirectories(home);
        try {
            Fixture pending = fixture(root, "wait-stale");
            // Rewrite the published token so it no longer matches the pending decision.
            OrcaLifecycle.Gate stale = new OrcaLifecycle.Gate(
                    pending.gateId, "task_gate", "orca_run", "term_1",
                    "failure", List.of("retry", "abort"),
                    "2000-01-01T00:00:00Z", null, Instant.parse("2026-09-04T09:00:00Z"));
            new OrcaLifecycle(root, "wait-stale").mutate(OrcaLifecycle.of(snapshot -> snapshot.withGate(stale)));
            ScriptedGate reader = new ScriptedGate(resolvedAnswer(pending.gateId, "abort"));
            FakeTime time = new FakeTime();
            Main.ApproveOutcome outcome = Main.approveDecision(root, new String[] {
                    "approve", "wait-stale", "--from-orca", "--wait-minutes", "1",
                    "--no-workspace-status"
            }, new Main.ApproveEnv(time, time, reader, home));
            check.eq("stale_decision is not ok", false, outcome.ok());
            check.eq("and fails on the first poll", "stale_decision", outcome.report().get("code"));
            check.eq("without sleeping", List.of(), time.sleeps);
            check.eq("and without reading Orca", 0, reader.reads);
        } finally {
            deleteTree(root);
        }
    }

    private void waiterBackoffSequence(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-wait-backoff-");
        Path home = root.resolve("config-home");
        Files.createDirectories(home);
        try {
            Fixture pending = fixture(root, "wait-backoff");
            ScriptedGate alwaysPending = new ScriptedGate(pendingAnswer(pending.gateId));
            FakeTime time = new FakeTime();
            Main.pollGate(root, "wait-backoff", new ApprovalStore(root),
                    new Main.ApproveEnv(time, time, alwaysPending, home), 1);
            check.eq("backoff is 5, 7.5, 10, 10 … seconds",
                    List.of(5_000L, 7_500L, 10_000L, 10_000L),
                    time.sleeps.subList(0, 4));
        } finally {
            deleteTree(root);
        }
    }

    private void waitForGateSkipsWhenNoneWasPublished(Check check) throws Exception {
        Map<String, Object> none = Main.waitForPublishedGate(Path.of("."), "run-x",
                Map.of("reason", "ready_for_human"), 5, Main.ApproveEnv.realtime());
        @SuppressWarnings("unchecked")
        Map<String, Object> skip = (Map<String, Object>) none.get("gate_wait");
        check.eq("a missing gate skips the wait", true, skip.get("skipped"));
        check.eq("naming why", "no_orca_gate", skip.get("reason"));

        Map<String, Object> disabled = Main.waitForPublishedGate(Path.of("."), "run-x",
                Map.of("orca_gate", Map.of("published", false, "reason", "disabled")),
                5, Main.ApproveEnv.realtime());
        @SuppressWarnings("unchecked")
        Map<String, Object> skippedDisabled = (Map<String, Object>) disabled.get("gate_wait");
        check.eq("an unpublished gate skips too", true, skippedDisabled.get("skipped"));
        check.eq("with the publish reason", "disabled", skippedDisabled.get("reason"));
    }

    private static Fixture fixture(Path root, String runId) throws Exception {
        Files.createDirectories(root.resolve(".warden"));
        Files.writeString(root.resolve(".warden/project.yaml"),
                "version: 1\nproject: fixture\n", StandardCharsets.UTF_8);
        Path summary = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        Files.createDirectories(summary.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("task_id", "hello");
        body.put("reason", "blocking_findings_remain");
        body.put("ok", false);
        Files.writeString(summary, Json.write(body), StandardCharsets.UTF_8);
        HumanDecision pending = new ApprovalStore(root).createFailure(
                runId, "hello", "blocking_findings_remain", summary, null);
        String gateId = "gate_" + runId;
        OrcaLifecycle.Gate gate = new OrcaLifecycle.Gate(
                gateId, "task_gate", "orca_run", "term_1",
                pending.kind().jsonValue(), pending.options(),
                pending.updatedAt().toString(), pending.candidateFingerprint(),
                pending.createdAt());
        new OrcaLifecycle(root, runId).mutate(OrcaLifecycle.of(snapshot ->
                snapshot.withOrcaRunId("orca_run").withGate(gate)));
        return new Fixture(gateId);
    }

    private record Fixture(String gateId) {}

    private static OrcaDecisionGate.Answer pendingAnswer(String gateId) {
        return new OrcaDecisionGate.Answer(gateId, "pending", null, "Accept?");
    }

    private static OrcaDecisionGate.Answer resolvedAnswer(String gateId, String resolution) {
        return new OrcaDecisionGate.Answer(gateId, "resolved", resolution, "Accept?");
    }

    private static final class ScriptedGate implements Main.ApproveEnv.Gates {
        private final List<OrcaDecisionGate.Answer> script;
        int reads;

        ScriptedGate(OrcaDecisionGate.Answer... answers) {
            this.script = List.of(answers);
        }

        @Override
        public OrcaDecisionGate.Answer read(Path root, OrcaLifecycle.Gate gate) {
            int index = Math.min(reads, script.size() - 1);
            reads++;
            return script.get(index);
        }
    }

    private static final class FakeTime implements Main.ApproveEnv.Clock, Main.ApproveEnv.Sleeper {
        long now;
        Long jumpOnSleepTo;
        final List<Long> sleeps = new ArrayList<>();

        @Override public long nowMillis() { return now; }

        @Override public void sleep(long millis) {
            sleeps.add(millis);
            if (jumpOnSleepTo != null) now = jumpOnSleepTo;
            else now += millis;
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
