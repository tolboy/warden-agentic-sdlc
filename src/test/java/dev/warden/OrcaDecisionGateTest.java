package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.execution.orca.OrcaDecisionGate;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    }
}
