package dev.warden.execution.orca;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies Orca orchestration receipts. Completion is a named settlement, never inferred
 * from terminal chatter: heartbeats and visible activity mean the worker is alive, not done.
 *
 * Field names vary across Orca versions. This parser accepts the documented shapes and fails
 * closed on anything else — an unknown receipt is {@link Kind#UNKNOWN}, not a pass.
 */
public final class OrcaSettlement {

    public enum Kind { COMPLETED, ESCALATED, QUESTION, FAILED, READY, CHECKPOINT, UNKNOWN }

    public record Outcome(Kind kind, String taskId, String dispatchId, String type,
                          Map<String, Object> payload, String reason,
                          String deliveryId, String messageId) {
        public Outcome(Kind kind, String taskId, String dispatchId, String type,
                       Map<String, Object> payload, String reason) {
            this(kind, taskId, dispatchId, type, payload, reason, null, null);
        }
    }

    private OrcaSettlement() {}

    public static boolean envelopeOk(Map<String, Object> envelope) {
        if (envelope == null) return false;
        Object ok = envelope.get("ok");
        return Boolean.TRUE.equals(ok);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resultOf(Map<String, Object> envelope) {
        if (envelope == null) return Map.of();
        Object result = envelope.get("result");
        return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static boolean startReady(Map<String, Object> envelope) {
        if (!envelopeOk(envelope)) return false;
        Map<String, Object> result = resultOf(envelope);
        if (Boolean.TRUE.equals(result.get("ready"))) return true;
        Object status = first(result, "status", "state");
        return "ready".equals(status);
    }

    public static String dispatchId(Map<String, Object> envelope) {
        Map<String, Object> result = resultOf(envelope);
        Object nested = result.get("dispatch");
        if (nested instanceof Map<?, ?> map && map.get("id") != null) return String.valueOf(map.get("id"));
        Object id = first(result, "dispatchId", "dispatch_id", "id");
        return id == null ? null : String.valueOf(id);
    }

    public static String taskId(Map<String, Object> envelope) {
        Map<String, Object> result = resultOf(envelope);
        Object nested = result.get("task");
        if (nested instanceof Map<?, ?> map && map.get("id") != null) return String.valueOf(map.get("id"));
        Object id = first(result, "taskId", "task_id");
        return id == null ? null : String.valueOf(id);
    }

    public static Outcome fromDispatchShow(Map<String, Object> envelope) {
        if (envelope == null) return new Outcome(Kind.UNKNOWN, null, null, null, Map.of(), "missing_envelope");
        Map<String, Object> result = resultOf(envelope);
        String status = String.valueOf(first(result, "status", "state"));
        Object nested = result.get("dispatch");
        String outcome = string(first(result, "outcome", "workerOutcome", "worker_outcome"));
        if (nested instanceof Map<?, ?> map) {
            if (map.get("status") != null) status = String.valueOf(map.get("status"));
            Object nestedOutcome = first(cast(map), "outcome", "workerOutcome", "worker_outcome");
            if (nestedOutcome != null) outcome = String.valueOf(nestedOutcome);
        }
        String task = taskId(envelope);
        String dispatch = dispatchId(envelope);
        // A failed dispatch is still settled. Failure must win over the generic settled bit.
        if (failureOutcome(outcome) || "failed".equals(status) || "blocked".equals(status)
                || "cancelled".equals(status) || "canceled".equals(status)
                || "abandoned".equals(status) || "stopped".equals(status)) {
            return new Outcome(Kind.FAILED, task, dispatch, status, result, "dispatch_" + status);
        }
        if (successOutcome(outcome) || "completed".equals(status)) {
            return new Outcome(Kind.COMPLETED, task, dispatch, status, result, "dispatch_succeeded");
        }
        if (Boolean.TRUE.equals(result.get("settled")) || "settled".equals(status)) {
            return new Outcome(Kind.FAILED, task, dispatch, status, result,
                    "dispatch_settled_without_success_outcome");
        }
        if ("dispatched".equals(status) || "ready".equals(status) || "pending".equals(status)) {
            return new Outcome(Kind.CHECKPOINT, task, dispatch, status, result, "dispatch_in_flight");
        }
        return new Outcome(Kind.UNKNOWN, task, dispatch, status, result, "unrecognised_dispatch_status");
    }

    /**
     * One {@code orchestration check} Delivery. An empty or timed-out check is a checkpoint,
     * not a worker failure: long tasks routinely run past a single wait window.
     */
    public static Outcome fromCheck(Map<String, Object> envelope, String expectedTaskId, String expectedDispatchId) {
        if (envelope == null) return new Outcome(Kind.UNKNOWN, expectedTaskId, expectedDispatchId, null, Map.of(),
                "missing_envelope");
        Map<String, Object> result = resultOf(envelope);
        String deliveryId = deliveryId(result);
        Object count = result.get("count");
        if (count instanceof Number number && number.longValue() == 0) {
            return new Outcome(Kind.CHECKPOINT, expectedTaskId, expectedDispatchId, null, result, "empty_delivery");
        }
        for (Map<String, Object> message : messagesOf(result)) {
            String type = String.valueOf(first(message, "type", "kind"));
            String task = string(first(message, "taskId", "task_id", "task"));
            String dispatch = string(first(message, "dispatchId", "dispatch_id", "dispatch"));
            if (expectedTaskId != null && task != null && !expectedTaskId.equals(task)) continue;
            if (expectedDispatchId != null && dispatch != null && !expectedDispatchId.equals(dispatch)) continue;
            Map<String, Object> payload = payloadOf(message);
            if ("worker_done".equals(type) || "worker-done".equals(type)) {
                String outcome = string(first(message, "outcome", "workerOutcome", "worker_outcome"));
                if (outcome == null) {
                    outcome = string(first(payload, "outcome", "workerOutcome", "worker_outcome"));
                }
                Kind kind = successOutcome(outcome) ? Kind.COMPLETED : Kind.FAILED;
                String reason = successOutcome(outcome) ? "worker_done_succeeded"
                        : failureOutcome(outcome) ? "worker_done_failed"
                        : "worker_done_missing_or_unknown_outcome";
                return new Outcome(kind, task != null ? task : expectedTaskId,
                        dispatch != null ? dispatch : expectedDispatchId, type, payload, reason,
                        deliveryId, messageId(message));
            }
            if ("escalation".equals(type)) {
                return new Outcome(Kind.ESCALATED, task != null ? task : expectedTaskId,
                        dispatch != null ? dispatch : expectedDispatchId, type, payload, "escalation",
                        deliveryId, messageId(message));
            }
            if ("question".equals(type)) {
                return new Outcome(Kind.QUESTION, task != null ? task : expectedTaskId,
                        dispatch != null ? dispatch : expectedDispatchId, type, payload, "question",
                        deliveryId, messageId(message));
            }
        }
        if (Boolean.FALSE.equals(envelope.get("ok"))) {
            return new Outcome(Kind.CHECKPOINT, expectedTaskId, expectedDispatchId, null, result,
                    "check_not_ok");
        }
        return new Outcome(Kind.CHECKPOINT, expectedTaskId, expectedDispatchId, null, result, "no_terminal_message");
    }

    public static String worktreeSelector(Map<String, Object> envelope) {
        Map<String, Object> result = resultOf(envelope);
        Object nested = result.get("worktree");
        if (nested instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id != null) return String.valueOf(id);
            Object path = map.get("path");
            if (path != null) return "path:" + path;
        }
        Object selector = first(result, "selector", "worktreeSelector");
        if (selector != null) return String.valueOf(selector);
        Object path = first(result, "path", "worktreePath");
        return path == null ? null : "path:" + path;
    }

    public static Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> messagesOf(Map<String, Object> result) {
        List<Map<String, Object>> messages = new ArrayList<>();
        collect(messages, result.get("messages"));
        Object delivery = result.get("delivery");
        if (delivery instanceof Map<?, ?> map) collect(messages, map.get("messages"));
        collect(messages, result.get("items"));
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static void collect(List<Map<String, Object>> into, Object raw) {
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) into.add((Map<String, Object>) map);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payloadOf(Map<String, Object> message) {
        Object payload = first(message, "payload", "body", "result");
        if (payload instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        return new LinkedHashMap<>(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String deliveryId(Map<String, Object> result) {
        Object direct = first(result, "deliveryId", "delivery_id");
        if (direct != null) return String.valueOf(direct);
        Object delivery = result.get("delivery");
        if (delivery instanceof Map<?, ?> map) {
            Object id = first(cast(map), "id", "deliveryId", "delivery_id");
            if (id != null) return String.valueOf(id);
        }
        return null;
    }

    private static String messageId(Map<String, Object> message) {
        return string(first(message, "id", "messageId", "message_id"));
    }

    private static boolean successOutcome(String outcome) {
        if (outcome == null) return false;
        return switch (outcome.toLowerCase()) {
            case "succeeded", "success", "completed", "complete", "passed", "pass" -> true;
            default -> false;
        };
    }

    private static boolean failureOutcome(String outcome) {
        if (outcome == null) return false;
        return switch (outcome.toLowerCase()) {
            case "failed", "failure", "blocked", "cancelled", "canceled", "abandoned", "stopped" -> true;
            default -> false;
        };
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
