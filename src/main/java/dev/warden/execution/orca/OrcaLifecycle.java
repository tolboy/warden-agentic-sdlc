package dev.warden.execution.orca;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What Warden knows about the Orca objects a run owns, written down where a later process can
 * read it.
 *
 * The identities used to live in a static map, which is exactly as durable as the JVM holding
 * it. A Warden process killed while an Orca worker was running therefore forgot the worker
 * existed, and the next invocation started a second agent in the same worktree — two writers,
 * one tree, and no way to tell whose change is whose. This file is the fix: the Run, task,
 * dispatch and coordinator identities reach disk before the worker is started, so a restart
 * can attach to the worker that is already there instead of duplicating it.
 *
 * It lives at {@code .warden/runs/<run-id>/orca.json}, beside the run's other evidence, and
 * therefore inside the one directory that both the read-only fingerprint and the contract
 * snapshot deliberately exclude. Writing it during a run changes no judgement.
 *
 * Reads fail closed. An unreadable or unknown-shaped record is an error, never an empty one:
 * "Warden cannot tell whether a worker is live" and "no worker is live" must not be the same
 * answer, because only one of them is safe to start an agent on.
 */
public final class OrcaLifecycle {

    public static final String FILE_NAME = "orca.json";
    public static final long SCHEMA_VERSION = 1L;

    /** Where a recorded worker stands, as far as this Warden process was able to observe. */
    public enum State {
        /** Started, and not yet proven finished or fenced. Blocks a second start. */
        ACTIVE("active"),
        /** Settled and released: the dispatch is finished and its terminal accounted for. */
        SETTLED("settled"),
        /** Fenced by Warden. No agent is running under this dispatch. */
        STOPPED("stopped");

        private final String jsonValue;

        State(String jsonValue) { this.jsonValue = jsonValue; }

        public String jsonValue() { return jsonValue; }

        static State parse(Object value) throws IOException {
            for (State state : values()) if (state.jsonValue.equals(value)) return state;
            throw malformed("unknown worker state " + Json.write(value));
        }
    }

    /**
     * One supervised worker Warden started, named by every identity needed to find it again.
     *
     * {@code awaiting} records why a worker was still ACTIVE when this process stopped looking
     * at it — {@code human} for a proven agent prompt — so a later run can say what it is
     * attaching to instead of reporting a stall. {@code awaitingMessage} names the Orca message
     * that has to be answered, and is written before that delivery is acknowledged so the
     * question outlives the acknowledgement.
     */
    public record Worker(String key, String role, String taskId, String dispatchId,
                         String coordinatorHandle, String worktreeSelector, String worktreeId,
                         String agent, String model, String readOnlyFingerprint,
                         State state, String awaiting, String awaitingMessage,
                         Instant createdAt, Instant updatedAt,
                         Map<String, Object> launchContract, Map<String, Object> launchEvidence) {

        /** Older records remain inspectable; they cannot prove a new launch contract on resume. */
        public Worker(String key, String role, String taskId, String dispatchId,
                      String coordinatorHandle, String worktreeSelector, String worktreeId,
                      String agent, String model, String readOnlyFingerprint,
                      State state, String awaiting, String awaitingMessage,
                      Instant createdAt, Instant updatedAt) {
            this(key, role, taskId, dispatchId, coordinatorHandle, worktreeSelector, worktreeId,
                    agent, model, readOnlyFingerprint, state, awaiting, awaitingMessage,
                    createdAt, updatedAt, Map.of(), Map.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", key);
            value.put("role", role);
            value.put("task_id", taskId);
            value.put("dispatch_id", dispatchId);
            value.put("coordinator_handle", coordinatorHandle);
            value.put("worktree_selector", worktreeSelector);
            value.put("worktree_id", worktreeId);
            value.put("agent", agent);
            value.put("model", model);
            value.put("launch_contract", launchContract);
            value.put("launch", launchEvidence);
            value.put("read_only_fingerprint", readOnlyFingerprint);
            value.put("state", state.jsonValue());
            value.put("awaiting", awaiting);
            value.put("awaiting_message", awaitingMessage);
            value.put("created_at", createdAt.toString());
            value.put("updated_at", updatedAt.toString());
            return value;
        }

        static Worker fromMap(Map<String, Object> value) throws IOException {
            return new Worker(
                    required(value, "key"),
                    required(value, "role"),
                    required(value, "task_id"),
                    required(value, "dispatch_id"),
                    optional(value, "coordinator_handle"),
                    optional(value, "worktree_selector"),
                    optional(value, "worktree_id"),
                    optional(value, "agent"),
                    optional(value, "model"),
                    optional(value, "read_only_fingerprint"),
                    State.parse(value.get("state")),
                    optional(value, "awaiting"),
                    optional(value, "awaiting_message"),
                    instant(value, "created_at"),
                    instant(value, "updated_at"),
                    optionalMap(value, "launch_contract"), optionalMap(value, "launch"));
        }

        public boolean active() { return state == State.ACTIVE; }

        public Worker at(State next, String waiting, Instant now) {
            return at(next, waiting, awaitingMessage, now);
        }

        public Worker at(State next, String waiting, String message, Instant now) {
            return new Worker(key, role, taskId, dispatchId, coordinatorHandle, worktreeSelector,
                    worktreeId, agent, model, readOnlyFingerprint, next, waiting, message,
                    createdAt, now, launchContract, launchEvidence);
        }

        public Worker withCoordinator(String handle, Instant now) {
            return new Worker(key, role, taskId, dispatchId, handle, worktreeSelector,
                    worktreeId, agent, model, readOnlyFingerprint, state, awaiting, awaitingMessage,
                    createdAt, now, launchContract, launchEvidence);
        }

        public Worker withLaunch(Map<String, Object> contract, Map<String, Object> evidence) {
            return new Worker(key, role, taskId, dispatchId, coordinatorHandle, worktreeSelector,
                    worktreeId, agent, model, readOnlyFingerprint, state, awaiting, awaitingMessage,
                    createdAt, updatedAt, contract, evidence);
        }

        private static Map<String, Object> optionalMap(Map<String, Object> value, String key) throws IOException {
            Object raw = value.get(key);
            if (raw == null) return Map.of();
            if (!(raw instanceof Map<?, ?> map)) throw malformed(key + " must be an object");
            return cast(map);
        }
    }

    /**
     * The Orca decision gate standing in for this run's pending
     * {@link dev.warden.approval.HumanDecision}.
     *
     * The version token and candidate fingerprint are copied in at publish time, and they are
     * the whole point: a resolution that arrives later is applied only if the decision it was
     * asked about is still the decision on disk. Without them, answering a gate would resolve
     * whatever the run happens to be waiting on by then.
     */
    public record Gate(String gateId, String taskId, String orcaRunId, String coordinatorHandle,
                       String kind, List<String> options, String decisionUpdatedAt,
                       String candidateFingerprint, Instant createdAt) {

        public Gate {
            options = List.copyOf(options);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("gate_id", gateId);
            value.put("task_id", taskId);
            value.put("orca_run_id", orcaRunId);
            value.put("coordinator_handle", coordinatorHandle);
            value.put("kind", kind);
            value.put("options", options);
            value.put("decision_updated_at", decisionUpdatedAt);
            value.put("candidate_fingerprint", candidateFingerprint);
            value.put("created_at", createdAt.toString());
            return value;
        }

        static Gate fromMap(Map<String, Object> value) throws IOException {
            return new Gate(
                    required(value, "gate_id"),
                    required(value, "task_id"),
                    optional(value, "orca_run_id"),
                    optional(value, "coordinator_handle"),
                    required(value, "kind"),
                    strings(value, "options"),
                    required(value, "decision_updated_at"),
                    optional(value, "candidate_fingerprint"),
                    instant(value, "created_at"));
        }
    }

    /** The whole file, read or written in one step. */
    public record Snapshot(long schemaVersion, String runId, String orcaRunId,
                           Map<String, Worker> workers, Gate gate) {

        public Snapshot {
            workers = Map.copyOf(workers);
        }

        public static Snapshot empty(String runId) {
            return new Snapshot(SCHEMA_VERSION, runId, null, Map.of(), null);
        }

        public Snapshot withOrcaRunId(String id) {
            return new Snapshot(schemaVersion, runId, id, workers, gate);
        }

        public Snapshot withWorker(Worker worker) {
            Map<String, Worker> next = new LinkedHashMap<>(workers);
            next.put(worker.key(), worker);
            return new Snapshot(schemaVersion, runId, orcaRunId, next, gate);
        }

        public Snapshot withGate(Gate published) {
            return new Snapshot(schemaVersion, runId, orcaRunId, workers, published);
        }

        public Optional<Worker> worker(String key) {
            return Optional.ofNullable(workers.get(key));
        }

        /** Workers this run started and has not proven finished. */
        public List<Worker> active() {
            return workers.values().stream().filter(Worker::active).toList();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema_version", schemaVersion);
            value.put("run_id", runId);
            value.put("orca_run_id", orcaRunId);
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (Worker worker : workers.values()) encoded.add(worker.toMap());
            value.put("workers", encoded);
            value.put("gate", gate == null ? null : gate.toMap());
            return value;
        }

        static Snapshot fromMap(Map<String, Object> value) throws IOException {
            Object version = value.get("schema_version");
            long schemaVersion = version instanceof Number number ? number.longValue() : -1L;
            if (schemaVersion != SCHEMA_VERSION) {
                throw malformed("unsupported schema_version " + Json.write(version));
            }
            Map<String, Worker> workers = new LinkedHashMap<>();
            Object raw = value.get("workers");
            if (raw != null) {
                if (!(raw instanceof List<?> list)) throw malformed("workers must be an array");
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) throw malformed("workers must hold objects");
                    Worker worker = Worker.fromMap(cast(map));
                    workers.put(worker.key(), worker);
                }
            }
            Object rawGate = value.get("gate");
            if (rawGate != null && !(rawGate instanceof Map<?, ?>)) {
                throw malformed("gate must be an object");
            }
            Gate gate = rawGate == null ? null : Gate.fromMap(cast((Map<?, ?>) rawGate));
            return new Snapshot(schemaVersion, required(value, "run_id"),
                    optional(value, "orca_run_id"), workers, gate);
        }
    }

    private final Path file;
    private final String runId;

    public OrcaLifecycle(Path projectRoot, String runId) {
        if (runId == null || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("unsafe run id: " + runId);
        }
        this.runId = runId;
        this.file = projectRoot.toAbsolutePath().normalize()
                .resolve(".warden/runs").resolve(runId).resolve(FILE_NAME);
    }

    public Path file() { return file; }

    public String runId() { return runId; }

    public Snapshot read() throws IOException {
        if (!Files.isRegularFile(file)) return Snapshot.empty(runId);
        try {
            return Snapshot.fromMap(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Json.JsonException malformedJson) {
            throw malformed("cannot read " + file + ": " + malformedJson.getMessage());
        }
    }

    /** Read, transform and write back while holding the cross-process lock. */
    public Snapshot mutate(Change change) throws IOException {
        return JsonFile.underExclusiveLock(file, FILE_NAME + ".lock", () -> {
            Snapshot next = change.apply(read());
            JsonFile.writeAtomically(file, next.toMap());
            return next;
        });
    }

    /** A transformation that may itself refuse, so a caller can compare-and-swap. */
    @FunctionalInterface
    public interface Change {
        Snapshot apply(Snapshot current) throws IOException;
    }

    /** Adapts a total transformation to {@link Change}. */
    public static Change of(UnaryOperator<Snapshot> operator) {
        return operator::apply;
    }

    /** Every run directory of this project that recorded Orca identities, by directory name. */
    public static List<String> runIds(Path projectRoot) throws IOException {
        Path runs = projectRoot.toAbsolutePath().normalize().resolve(".warden/runs");
        if (!Files.isDirectory(runs)) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        try (var paths = Files.list(runs)) {
            for (Path directory : paths.filter(Files::isDirectory).sorted().toList()) {
                if (Files.isRegularFile(directory.resolve(FILE_NAME))) {
                    ids.add(directory.getFileName().toString());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** The key under which one role attempt's worker is recorded within a run. */
    public static String workerKey(String role, String stepRunId) {
        return role + "#" + stepRunId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String required(Map<String, Object> value, String key) throws IOException {
        String found = optional(value, key);
        if (found == null || found.isBlank()) throw malformed(key + " must be a non-blank string");
        return found;
    }

    private static String optional(Map<String, Object> value, String key) throws IOException {
        Object found = value.get(key);
        if (found == null) return null;
        if (found instanceof String text) return text;
        throw malformed(key + " must be a string or null");
    }

    private static List<String> strings(Map<String, Object> value, String key) throws IOException {
        Object found = value.get(key);
        if (!(found instanceof List<?> list)) throw malformed(key + " must be an array");
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw malformed(key + " must contain only strings");
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    private static Instant instant(Map<String, Object> value, String key) throws IOException {
        String text = required(value, key);
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException badTimestamp) {
            throw malformed(key + " is not an ISO-8601 instant");
        }
    }

    private static IOException malformed(String message) {
        return new IOException("malformed_orca_lifecycle: " + message);
    }
}
