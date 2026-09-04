package dev.warden.execution.orca;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two questions a restarted Warden has to answer before it is allowed to start an agent.
 *
 * The first is about a worker this run already recorded: is it still running, has it already
 * finished, or is it gone? The second is about the worktree: does anybody else's supervised
 * worker have it right now? Both are read out of Orca's own receipts and neither is inferred
 * from terminal text.
 *
 * Every classification here is three-valued in the way that matters. {@link Verdict#UNCERTAIN}
 * is a real answer, not a tidy default — it means Orca could not say, and the caller must
 * refuse to start rather than guess. The failure this whole class exists to prevent is two
 * agents writing one worktree, and that failure is reached by treating "cannot tell" as "no".
 */
public final class OrcaRecovery {

    public enum Verdict {
        /** The recorded dispatch is still in flight: attach to it, never start another. */
        RESUME,
        /** The recorded dispatch already settled: adopt its outcome and finish the accounting. */
        SETTLED,
        /** Orca has no such dispatch. Nothing can be running under it; a fresh start is safe. */
        GONE,
        /** Orca could not say. Fail closed: no attach, and above all no second worker. */
        UNCERTAIN
    }

    public record Assessment(Verdict verdict, String reason, OrcaSettlement.Outcome settlement) {
        public Assessment(Verdict verdict, String reason) { this(verdict, reason, null); }
    }

    /** One supervised worker as {@code worker-list} reports it. */
    public record LiveWorker(String dispatchId, String taskId, String orcaRunId, String worktreeId,
                             String dispatchStatus, String workerState, String terminalState) {

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("dispatch_id", dispatchId);
            value.put("task_id", taskId);
            value.put("orca_run_id", orcaRunId);
            value.put("worktree_id", worktreeId);
            value.put("dispatch_status", dispatchStatus);
            value.put("worker_state", workerState);
            value.put("terminal_state", terminalState);
            return value;
        }
    }

    /** Orca's own name for a dispatch it has never heard of, which is the one safe negative. */
    public static final String DISPATCH_NOT_FOUND = "dispatch_not_found";

    private OrcaRecovery() {}

    /**
     * Classify {@code orchestration worker-show} for the exact dispatch this run recorded.
     *
     * @param envelope the whole CLI envelope, so both {@code ok} and {@code error.code} are read
     * @param expectedDispatchId the dispatch Warden wrote down before starting the worker
     */
    public static Assessment assess(Map<String, Object> envelope, String expectedDispatchId) {
        if (expectedDispatchId == null || expectedDispatchId.isBlank()) {
            return new Assessment(Verdict.GONE, "no_recorded_dispatch");
        }
        if (envelope == null || envelope.isEmpty()) {
            return new Assessment(Verdict.UNCERTAIN, "worker_show_unavailable");
        }
        if (!OrcaSettlement.envelopeOk(envelope)) {
            String code = OrcaSettlement.errorCode(envelope);
            if (DISPATCH_NOT_FOUND.equals(code)) {
                return new Assessment(Verdict.GONE, DISPATCH_NOT_FOUND);
            }
            return new Assessment(Verdict.UNCERTAIN,
                    "worker_show_failed" + (code == null ? "" : "_" + code));
        }
        String reported = OrcaSettlement.dispatchId(envelope);
        if (reported != null && !expectedDispatchId.equals(reported)) {
            return new Assessment(Verdict.UNCERTAIN, "dispatch_identity_mismatch");
        }
        OrcaSettlement.Outcome outcome = OrcaSettlement.fromDispatchShow(envelope);
        return switch (outcome.kind()) {
            case COMPLETED, FAILED -> new Assessment(Verdict.SETTLED, outcome.reason(), outcome);
            case ESCALATED -> new Assessment(Verdict.SETTLED, "dispatch_escalated", outcome);
            case CHECKPOINT -> orphaned(envelope)
                    // In flight according to the dispatch row, with no terminal left to run in.
                    // Waiting on that would be waiting on nothing, and starting a second worker
                    // on the strength of it is exactly the duplication this guards against.
                    ? new Assessment(Verdict.UNCERTAIN, "worker_terminal_orphaned")
                    : new Assessment(Verdict.RESUME, outcome.reason(), outcome);
            default -> new Assessment(Verdict.UNCERTAIN, "unrecognised_worker_show");
        };
    }

    /**
     * Supervised workers that Orca still reports as in flight, from {@code worker-list}.
     *
     * A settled dispatch can still own a terminal, so terminal accounting is recorded but is
     * not what makes a worker live here: an agent is running only while its dispatch is.
     */
    public static List<LiveWorker> liveWorkers(Map<String, Object> envelope) {
        List<LiveWorker> live = new ArrayList<>();
        if (envelope == null || !OrcaSettlement.envelopeOk(envelope)) return List.of();
        Object raw = OrcaSettlement.resultOf(envelope).get("workers");
        if (!(raw instanceof List<?> list)) return List.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> worker = cast(map);
            String dispatchStatus = string(OrcaSettlement.first(worker, "dispatchStatus", "dispatch_status"));
            String workerState = string(OrcaSettlement.first(worker, "workerState", "worker_state"));
            if (!inFlight(dispatchStatus, workerState)) continue;
            live.add(new LiveWorker(
                    string(OrcaSettlement.first(worker, "dispatchId", "dispatch_id")),
                    string(OrcaSettlement.first(worker, "taskId", "task_id")),
                    string(OrcaSettlement.first(worker, "runId", "run_id")),
                    worktreeIdOf(worker),
                    dispatchStatus,
                    workerState,
                    string(OrcaSettlement.first(worker, "terminalState", "terminal_state"))));
        }
        return List.copyOf(live);
    }

    /**
     * Live workers that hold {@code worktreeId}, excluding the dispatch this run already owns.
     *
     * A worker whose worktree Orca did not report is included on purpose. It is the case where
     * Warden cannot prove the worktree is free, and an unprovable claim must not clear the way
     * for a second agent; the caller names it and stops.
     */
    public static List<LiveWorker> contending(List<LiveWorker> live, String worktreeId, String ownDispatchId) {
        List<LiveWorker> contending = new ArrayList<>();
        for (LiveWorker worker : live) {
            if (ownDispatchId != null && ownDispatchId.equals(worker.dispatchId())) continue;
            if (worker.worktreeId() == null || worker.worktreeId().equals(worktreeId)) {
                contending.add(worker);
            }
        }
        return List.copyOf(contending);
    }

    private static boolean inFlight(String dispatchStatus, String workerState) {
        if (dispatchStatus == null) return false;
        boolean settledWorker = workerState != null && switch (workerState.toLowerCase()) {
            case "succeeded", "failed", "stopped", "abandoned", "unsupervised" -> true;
            default -> false;
        };
        if (settledWorker) return false;
        return switch (dispatchStatus.toLowerCase()) {
            case "dispatched", "pending", "ready", "running", "working" -> true;
            default -> false;
        };
    }

    private static boolean orphaned(Map<String, Object> envelope) {
        Object terminal = OrcaSettlement.resultOf(envelope).get("terminal");
        return terminal instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("orphaned"));
    }

    private static String worktreeIdOf(Map<String, Object> worker) {
        Object resource = worker.get("resource");
        if (resource instanceof Map<?, ?> map) {
            Object id = OrcaSettlement.first(cast(map), "worktreeId", "worktree_id");
            if (id != null) return String.valueOf(id);
        }
        Object direct = OrcaSettlement.first(worker, "worktreeId", "worktree_id");
        return direct == null ? null : String.valueOf(direct);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
