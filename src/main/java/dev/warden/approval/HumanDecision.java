package dev.warden.approval;

import dev.warden.json.Json;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable representation of one fail-closed human decision. */
public record HumanDecision(
        long schemaVersion,
        String runId,
        String taskId,
        State state,
        Kind kind,
        String reason,
        List<String> options,
        Instant createdAt,
        Instant updatedAt,
        String summaryPath,
        String candidateFingerprint,
        String decision,
        String actor,
        String note) {

    public static final long SCHEMA_VERSION = 1L;

    public enum State {
        PENDING("pending"),
        RESOLVED("resolved");

        private final String jsonValue;

        State(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }

        static State parse(Object value) throws ApprovalException {
            for (State state : values()) {
                if (state.jsonValue.equals(value)) return state;
            }
            throw malformed("unknown state " + Json.write(value));
        }
    }

    public enum Kind {
        SUCCESS("success", List.of("accept", "reject")),
        FAILURE("failure", List.of("retry", "abort")),
        /**
         * A vendor ran out mid-run and another could take over. Distinct from FAILURE
         * because the choice is not "try again or give up" — the work so far is fine, and
         * what is being asked is whether somebody else may finish it.
         *
         * `abort` is first so that automation which auto-selects the first option declines
         * the substitution rather than granting one.
         */
        FAILOVER("failover", List.of("abort", "switch"));

        private final String jsonValue;
        private final List<String> options;

        Kind(String jsonValue, List<String> options) {
            this.jsonValue = jsonValue;
            this.options = options;
        }

        public String jsonValue() {
            return jsonValue;
        }

        public List<String> options() {
            return options;
        }

        static Kind parse(Object value) throws ApprovalException {
            for (Kind kind : values()) {
                if (kind.jsonValue.equals(value)) return kind;
            }
            throw malformed("unknown kind " + Json.write(value));
        }
    }

    public HumanDecision {
        options = List.copyOf(options);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", schemaVersion);
        value.put("run_id", runId);
        value.put("task_id", taskId);
        value.put("state", state.jsonValue());
        value.put("kind", kind.jsonValue());
        value.put("reason", reason);
        value.put("options", options);
        value.put("created_at", createdAt.toString());
        value.put("updated_at", updatedAt.toString());
        value.put("summary_path", summaryPath);
        value.put("candidate_fingerprint", candidateFingerprint);
        value.put("decision", decision);
        value.put("actor", actor);
        value.put("note", note);
        return value;
    }

    public static HumanDecision fromMap(Map<String, Object> value) throws ApprovalException {
        long schemaVersion = integer(value, "schema_version");
        if (schemaVersion != SCHEMA_VERSION) {
            throw malformed("unsupported schema_version " + schemaVersion);
        }
        String runId = requiredString(value, "run_id");
        String taskId = requiredString(value, "task_id");
        State state = State.parse(value.get("state"));
        Kind kind = Kind.parse(value.get("kind"));
        String reason = requiredString(value, "reason");
        List<String> options = stringList(value, "options");
        if (!options.equals(kind.options())) {
            throw malformed("options do not match kind " + kind.jsonValue());
        }
        Instant createdAt = instant(value, "created_at");
        Instant updatedAt = instant(value, "updated_at");
        if (updatedAt.isBefore(createdAt)) {
            throw malformed("updated_at precedes created_at");
        }
        String summaryPath = requiredString(value, "summary_path");
        String candidateFingerprint = nullableString(value, "candidate_fingerprint");
        String decision = nullableString(value, "decision");
        String actor = nullableString(value, "actor");
        String note = nullableString(value, "note");

        if (state == State.PENDING && (decision != null || actor != null || note != null)) {
            throw malformed("pending decision contains resolution fields");
        }
        if (kind == Kind.SUCCESS
                && (candidateFingerprint == null || candidateFingerprint.isBlank())) {
            throw malformed("success decision has no candidate_fingerprint");
        }
        if (state == State.RESOLVED) {
            if (decision == null || !options.contains(decision)) {
                throw malformed("resolved decision is not one of its options");
            }
            if (actor == null || actor.isBlank()) {
                throw malformed("resolved decision has no actor");
            }
        }
        return new HumanDecision(schemaVersion, runId, taskId, state, kind, reason, options,
                createdAt, updatedAt, summaryPath, candidateFingerprint, decision, actor, note);
    }

    private static long integer(Map<String, Object> value, String key) throws ApprovalException {
        Object found = value.get(key);
        if (found instanceof Long number) return number;
        if (found instanceof Integer number) return number.longValue();
        throw malformed(key + " must be an integer");
    }

    private static String requiredString(Map<String, Object> value, String key) throws ApprovalException {
        String found = nullableString(value, key);
        if (found == null || found.isBlank()) throw malformed(key + " must be a non-blank string");
        return found;
    }

    private static String nullableString(Map<String, Object> value, String key) throws ApprovalException {
        Object found = value.get(key);
        if (found == null) return null;
        if (found instanceof String text) return text;
        throw malformed(key + " must be a string or null");
    }

    private static List<String> stringList(Map<String, Object> value, String key) throws ApprovalException {
        Object found = value.get(key);
        if (!(found instanceof List<?> list)) throw malformed(key + " must be an array");
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw malformed(key + " must contain only strings");
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    private static Instant instant(Map<String, Object> value, String key) throws ApprovalException {
        String text = requiredString(value, key);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException badTimestamp) {
            throw new ApprovalException("malformed_decision", key + " is not an ISO-8601 instant", badTimestamp);
        }
    }

    private static ApprovalException malformed(String message) {
        return new ApprovalException("malformed_decision", message);
    }
}
