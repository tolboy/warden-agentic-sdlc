package dev.warden;

import dev.warden.execution.orca.OrcaRecovery;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;

/**
 * Recovery classification, against the receipt shapes Orca 1.4.196 actually returns.
 *
 * The envelopes below are trimmed copies of live output, not invented ones. The distinction
 * being tested is always the same: RESUME and SETTLED both mean "do not start another agent",
 * GONE is the only verdict that clears the way, and UNCERTAIN must never quietly become GONE.
 */
public final class OrcaRecoveryTest implements Suite {

    @Override public String name() { return "orca-recovery"; }

    @Override public void run(Check check) {
        check.eq("a dispatch Orca has never heard of is gone",
                OrcaRecovery.Verdict.GONE,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":false,"error":{"code":"dispatch_not_found",
                         "message":"Worker Dispatch ctx_1 was not found."}}
                        """), "ctx_1").verdict());

        check.eq("any other refusal is uncertain, never gone",
                OrcaRecovery.Verdict.UNCERTAIN,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":false,"error":{"code":"runtime_unreachable"}}
                        """), "ctx_1").verdict());

        check.eq("no receipt at all is uncertain",
                OrcaRecovery.Verdict.UNCERTAIN,
                OrcaRecovery.assess(java.util.Map.of(), "ctx_1").verdict());

        check.eq("a dispatched worker is resumed",
                OrcaRecovery.Verdict.RESUME,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":true,"result":{"dispatch":{"id":"ctx_1","task_id":"task_1",
                         "status":"dispatched"},"worker":{"state":"running"},
                         "terminal":{"orphaned":false}}}
                        """), "ctx_1").verdict());

        check.eq("a completed worker is settled",
                OrcaRecovery.Verdict.SETTLED,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":true,"result":{"dispatch":{"id":"ctx_1","task_id":"task_1",
                         "status":"completed"},"worker":{"state":"succeeded"}}}
                        """), "ctx_1").verdict());

        check.eq("a failed worker is settled too",
                OrcaRecovery.Verdict.SETTLED,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":true,"result":{"dispatch":{"id":"ctx_1","status":"failed"}}}
                        """), "ctx_1").verdict());

        // In flight with no terminal left to run in. Waiting is waiting on nothing, and
        // starting a replacement on the strength of it is the duplication this all guards.
        OrcaRecovery.Assessment orphan = OrcaRecovery.assess(Json.parseObject("""
                {"ok":true,"result":{"dispatch":{"id":"ctx_1","status":"dispatched"},
                 "worker":{"state":"running"},"terminal":{"orphaned":true}}}
                """), "ctx_1");
        check.eq("an orphaned live dispatch is uncertain",
                OrcaRecovery.Verdict.UNCERTAIN, orphan.verdict());
        check.eq("and says why", "worker_terminal_orphaned", orphan.reason());

        check.eq("a receipt about a different dispatch proves nothing",
                OrcaRecovery.Verdict.UNCERTAIN,
                OrcaRecovery.assess(Json.parseObject("""
                        {"ok":true,"result":{"dispatch":{"id":"ctx_2","status":"completed"}}}
                        """), "ctx_1").verdict());

        check.eq("with nothing recorded there is nothing to attach to",
                OrcaRecovery.Verdict.GONE, OrcaRecovery.assess(null, null).verdict());

        // worker-list, trimmed from a live census: one live worker, one released, one failed.
        var census = Json.parseObject("""
                {"ok":true,"result":{"workers":[
                  {"dispatchId":"ctx_live","taskId":"task_a","runId":"run_a",
                   "workerState":"running","dispatchStatus":"dispatched","terminalState":"active",
                   "resource":{"worktreeId":"repo::C:/mine"}},
                  {"dispatchId":"ctx_done","taskId":"task_b","runId":"run_a",
                   "workerState":"succeeded","dispatchStatus":"completed","terminalState":"released",
                   "resource":{"worktreeId":"repo::C:/mine"}},
                  {"dispatchId":"ctx_failed","taskId":"task_c","runId":"run_a",
                   "workerState":"failed","dispatchStatus":"failed","terminalState":"reclaimable",
                   "resource":{"worktreeId":"repo::C:/mine"}},
                  {"dispatchId":"ctx_elsewhere","taskId":"task_d","runId":"run_b",
                   "workerState":"running","dispatchStatus":"dispatched","terminalState":"active",
                   "resource":{"worktreeId":"repo::C:/theirs"}}]}}
                """);
        List<OrcaRecovery.LiveWorker> live = OrcaRecovery.liveWorkers(census);
        check.eq("only in-flight dispatches count as live", 2, live.size());
        check.eq("a worker in another worktree does not contend", 1,
                OrcaRecovery.contending(live, "repo::C:/mine", null).size());
        check.eq("and it is the live one that does", "ctx_live",
                OrcaRecovery.contending(live, "repo::C:/mine", null).get(0).dispatchId());
        check.eq("the run's own worker does not contend with itself", 0,
                OrcaRecovery.contending(live, "repo::C:/mine", "ctx_live").size());

        // A worker whose worktree Orca did not report is counted as contending. Warden cannot
        // prove the checkout is free, and an unprovable claim must not clear a start.
        List<OrcaRecovery.LiveWorker> unattributed = OrcaRecovery.liveWorkers(Json.parseObject("""
                {"ok":true,"result":{"workers":[
                  {"dispatchId":"ctx_unknown","workerState":"running",
                   "dispatchStatus":"dispatched","terminalState":"retained","resource":null}]}}
                """));
        check.eq("a live worker with no worktree is counted as contending", 1,
                OrcaRecovery.contending(unattributed, "repo::C:/mine", null).size());

        check.eq("an unreadable census lists nobody", 0,
                OrcaRecovery.liveWorkers(Json.parseObject("{\"ok\":false}")).size());
    }
}
