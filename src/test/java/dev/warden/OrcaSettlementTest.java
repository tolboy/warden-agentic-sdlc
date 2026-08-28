package dev.warden;

import dev.warden.execution.orca.OrcaSettlement;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.Map;

public final class OrcaSettlementTest implements Suite {
    @Override public String name() { return "orca-settlement"; }

    @Override public void run(Check check) {
        Map<String, Object> ready = Json.parseObject("""
                {"ok":true,"result":{"ready":true,"dispatchId":"disp_1","taskId":"task_1"}}
                """);
        check.that("worker-start ready is recognised", OrcaSettlement.startReady(ready));
        check.eq("dispatch id is taken from the receipt", "disp_1", OrcaSettlement.dispatchId(ready));

        Map<String, Object> nested = Json.parseObject("""
                {"ok":true,"result":{"status":"ready","dispatch":{"id":"disp_2"},"task":{"id":"task_2"}}}
                """);
        check.that("nested dispatch id is accepted", "disp_2".equals(OrcaSettlement.dispatchId(nested)));
        check.eq("nested task id is accepted", "task_2", OrcaSettlement.taskId(nested));

        Map<String, Object> done = Json.parseObject("""
                {"ok":true,"result":{"count":1,"messages":[
                  {"type":"worker_done","taskId":"task_1","dispatchId":"disp_1",
                   "outcome":"succeeded","payload":{"summary":"ok"}}]}}
                """);
        OrcaSettlement.Outcome completed = OrcaSettlement.fromCheck(done, "task_1", "disp_1");
        check.eq("worker_done settles the dispatch", OrcaSettlement.Kind.COMPLETED, completed.kind());
        check.eq("payload is retained", "ok", completed.payload().get("summary"));

        Map<String, Object> failedDone = Json.parseObject("""
                {"ok":true,"result":{"delivery":{"id":"delivery_1","messages":[
                  {"id":"message_1","type":"worker_done","taskId":"task_1","dispatchId":"disp_1",
                   "outcome":"failed","payload":{"summary":"tests failed"}}]}}}
                """);
        OrcaSettlement.Outcome failed = OrcaSettlement.fromCheck(failedDone, "task_1", "disp_1");
        check.eq("failed worker_done is a terminal failure", OrcaSettlement.Kind.FAILED, failed.kind());
        check.eq("delivery id survives for acknowledgement", "delivery_1", failed.deliveryId());
        check.eq("message id survives for recovery", "message_1", failed.messageId());

        Map<String, Object> missingOutcome = Json.parseObject("""
                {"ok":true,"result":{"messages":[
                  {"type":"worker_done","taskId":"task_1","dispatchId":"disp_1"}]}}
                """);
        check.eq("worker_done without explicit outcome fails closed", OrcaSettlement.Kind.FAILED,
                OrcaSettlement.fromCheck(missingOutcome, "task_1", "disp_1").kind());

        Map<String, Object> other = Json.parseObject("""
                {"ok":true,"result":{"messages":[
                  {"type":"worker_done","taskId":"task_other","dispatchId":"disp_x"}]}}
                """);
        check.eq("a worker_done for a different task is not this run's completion",
                OrcaSettlement.Kind.CHECKPOINT, OrcaSettlement.fromCheck(other, "task_1", "disp_1").kind());

        Map<String, Object> escalated = Json.parseObject("""
                {"ok":true,"result":{"messages":[{"type":"escalation","taskId":"task_1","dispatchId":"disp_1"}]}}
                """);
        check.eq("escalation is a named failure, not a pass",
                OrcaSettlement.Kind.ESCALATED, OrcaSettlement.fromCheck(escalated, "task_1", "disp_1").kind());

        Map<String, Object> empty = Json.parseObject("{\"ok\":true,\"result\":{\"count\":0}}");
        check.eq("an empty check is a checkpoint, not a worker failure",
                OrcaSettlement.Kind.CHECKPOINT, OrcaSettlement.fromCheck(empty, "task_1", "disp_1").kind());

        Map<String, Object> settled = Json.parseObject("""
                {"ok":true,"result":{"status":"completed","settled":true,"dispatchId":"disp_1"}}
                """);
        check.eq("dispatch-show completed is settlement",
                OrcaSettlement.Kind.COMPLETED, OrcaSettlement.fromDispatchShow(settled).kind());

        Map<String, Object> failedAndSettled = Json.parseObject("""
                {"ok":true,"result":{"status":"failed","settled":true,"outcome":"failed",
                "dispatchId":"disp_1"}}
                """);
        check.eq("failed status wins over generic settled flag", OrcaSettlement.Kind.FAILED,
                OrcaSettlement.fromDispatchShow(failedAndSettled).kind());

        Map<String, Object> question = Json.parseObject("""
                {"ok":true,"result":{"deliveryId":"delivery_q","messages":[
                  {"id":"message_q","type":"question","taskId":"task_1","dispatchId":"disp_1",
                   "payload":{"question":"Which account?"}}]}}
                """);
        check.eq("worker question is surfaced rather than treated as completion", OrcaSettlement.Kind.QUESTION,
                OrcaSettlement.fromCheck(question, "task_1", "disp_1").kind());

        Map<String, Object> inFlight = Json.parseObject("{\"ok\":true,\"result\":{\"status\":\"dispatched\"}}");
        check.eq("dispatch-show in flight is a checkpoint",
                OrcaSettlement.Kind.CHECKPOINT, OrcaSettlement.fromDispatchShow(inFlight).kind());

        check.that("a missing envelope fails closed",
                !OrcaSettlement.startReady(null));
        check.eq("an unrecognised dispatch status is unknown, never a pass",
                OrcaSettlement.Kind.UNKNOWN,
                OrcaSettlement.fromDispatchShow(Json.parseObject("{\"ok\":true,\"result\":{\"status\":\"maybe\"}}")).kind());

        Map<String, Object> current = Json.parseObject("""
                {"ok":true,"result":{"worktree":{"id":"repo::C:/proj","path":"C:/proj","branch":"refs/heads/main"}}}
                """);
        check.eq("worktree current uses the nested worktree id from a live Orca 1.4 receipt",
                "repo::C:/proj", OrcaSettlement.worktreeSelector(current));
    }
}
