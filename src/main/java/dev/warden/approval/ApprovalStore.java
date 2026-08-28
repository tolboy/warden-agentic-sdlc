package dev.warden.approval;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable, fail-closed storage for the only action Warden delegates to a human.
 *
 * <p>The store records a decision but deliberately has no Git or landing operation. An
 * acceptance is evidence for a separate operator-controlled action, never permission for
 * this class to mutate a branch.</p>
 */
public final class ApprovalStore {
    public static final String FILE_NAME = "decision.json";
    /** File locks protect processes; these monitors prevent overlapping-lock errors in one JVM. */
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path projectRoot;
    private final Path runsDirectory;
    private final Clock clock;

    public ApprovalStore(Path projectRoot) {
        this(projectRoot, Clock.systemUTC());
    }

    public ApprovalStore(Path projectRoot, Clock clock) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.runsDirectory = this.projectRoot.resolve(".warden/runs");
        this.clock = clock;
    }

    public HumanDecision createSuccess(
            String runId,
            String taskId,
            String reason,
            Path summaryPath,
            String candidateFingerprint) throws IOException {
        return createPending(runId, taskId, HumanDecision.Kind.SUCCESS, reason,
                summaryPath, candidateFingerprint);
    }

    public HumanDecision createFailure(
            String runId,
            String taskId,
            String reason,
            Path summaryPath,
            String candidateFingerprint) throws IOException {
        return createPending(runId, taskId, HumanDecision.Kind.FAILURE, reason,
                summaryPath, candidateFingerprint);
    }

    public synchronized HumanDecision createPending(
            String runId,
            String taskId,
            HumanDecision.Kind kind,
            String reason,
            Path summaryPath,
            String candidateFingerprint) throws IOException {
        validateRunId(runId);
        requireNonBlank("task_id", taskId);
        requireNonBlank("reason", reason);
        if (kind == null) throw new ApprovalException("invalid_kind", "kind is required");
        if (summaryPath == null) {
            throw new ApprovalException("invalid_summary_path", "summary path is required");
        }
        if (kind == HumanDecision.Kind.SUCCESS
                && (candidateFingerprint == null || candidateFingerprint.isBlank())) {
            throw new ApprovalException("invalid_candidate_fingerprint",
                    "a successful run requires the exact candidate fingerprint shown to the human");
        }

        Path target = decisionPath(runId);
        return underExclusiveLock(target, () -> {
            if (Files.exists(target)) {
                throw new ApprovalException("decision_exists",
                        "a decision already exists for run " + runId);
            }
            Instant now = clock.instant();
            HumanDecision pending = new HumanDecision(
                    HumanDecision.SCHEMA_VERSION,
                    runId,
                    taskId,
                    HumanDecision.State.PENDING,
                    kind,
                    reason,
                    kind.options(),
                    now,
                    now,
                    portablePath(summaryPath),
                    candidateFingerprint,
                    null,
                    null,
                    null);
            writeAtomically(target, pending.toMap());
            return pending;
        });
    }

    /**
     * Resolve a pending decision using {@code expectedUpdatedAt} as an optimistic-lock token.
     */
    public synchronized HumanDecision resolve(
            String runId,
            String expectedUpdatedAt,
            String decision,
            String actor,
            String note) throws IOException {
        validateRunId(runId);
        Path target = decisionPath(runId);
        return underExclusiveLock(target, () -> {
            HumanDecision current = read(runId);
            if (current.state() != HumanDecision.State.PENDING) {
                throw new ApprovalException("duplicate_decision",
                        "run " + runId + " is already resolved");
            }
            if (!current.updatedAt().toString().equals(expectedUpdatedAt)) {
                throw new ApprovalException("stale_decision", "updated_at no longer matches run " + runId);
            }
            if (decision == null || !current.options().contains(decision)) {
                throw new ApprovalException("unknown_decision",
                        "decision must be one of " + current.options() + " for run " + runId);
            }
            requireNonBlank("actor", actor);

            HumanDecision resolved = new HumanDecision(
                    current.schemaVersion(),
                    current.runId(),
                    current.taskId(),
                    HumanDecision.State.RESOLVED,
                    current.kind(),
                    current.reason(),
                    current.options(),
                    current.createdAt(),
                    clock.instant(),
                    current.summaryPath(),
                    current.candidateFingerprint(),
                    decision,
                    actor,
                    note == null ? "" : note);
            writeAtomically(target, resolved.toMap());
            return resolved;
        });
    }

    public HumanDecision read(String runId) throws IOException {
        validateRunId(runId);
        Path target = decisionPath(runId);
        if (!Files.isRegularFile(target)) {
            throw new ApprovalException("unknown_run", "no decision exists for run " + runId);
        }
        try {
            HumanDecision decision = HumanDecision.fromMap(
                    Json.parseObject(Files.readString(target, StandardCharsets.UTF_8)));
            if (!runId.equals(decision.runId())) {
                throw new ApprovalException("malformed_decision",
                        "decision run_id '" + decision.runId() + "' does not match directory '" + runId + "'");
            }
            return decision;
        } catch (Json.JsonException malformedJson) {
            throw new ApprovalException("malformed_decision",
                    "cannot read " + target + ": " + malformedJson.getMessage(), malformedJson);
        }
    }

    public Optional<HumanDecision> find(String runId) throws IOException {
        validateRunId(runId);
        if (!Files.isRegularFile(decisionPath(runId))) return Optional.empty();
        return Optional.of(read(runId));
    }

    public List<HumanDecision> list() throws IOException {
        if (!Files.isDirectory(runsDirectory)) return List.of();
        List<HumanDecision> decisions = new ArrayList<>();
        try (var paths = Files.list(runsDirectory)) {
            for (Path runDirectory : paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                Path target = runDirectory.resolve(FILE_NAME);
                if (Files.isRegularFile(target)) {
                    decisions.add(read(runDirectory.getFileName().toString()));
                }
            }
        }
        return List.copyOf(decisions);
    }

    public Path decisionPath(String runId) throws ApprovalException {
        validateRunId(runId);
        return runsDirectory.resolve(runId).resolve(FILE_NAME);
    }

    private String portablePath(Path summaryPath) throws ApprovalException {
        Path absolute = summaryPath.isAbsolute()
                ? summaryPath.toAbsolutePath().normalize()
                : projectRoot.resolve(summaryPath).normalize();
        if (!absolute.startsWith(projectRoot)) {
            throw new ApprovalException("invalid_summary_path",
                    "summary path must stay inside project root " + projectRoot);
        }
        return projectRoot.relativize(absolute).toString().replace('\\', '/');
    }

    private void writeAtomically(Path target, Map<String, Object> value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".decision-", ".tmp");
        try {
            Files.writeString(temporary, Json.writePretty(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new ApprovalException("atomic_write_unsupported",
                        "filesystem cannot atomically replace " + target, unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Compare-and-swap decisions across both threads and independent Warden processes.
     * The lock file is deliberately retained: deleting and recreating it can split waiters
     * across two different filesystem objects and make the critical section illusory.
     */
    private <T> T underExclusiveLock(Path target, IoOperation<T> operation) throws IOException {
        Files.createDirectories(target.getParent());
        Path lockPath = target.resolveSibling(FILE_NAME + ".lock").toAbsolutePath().normalize();
        Object monitor = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    private static void validateRunId(String runId) throws ApprovalException {
        if (runId == null || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new ApprovalException("unsafe_run_id", "unsafe run id: " + runId);
        }
    }

    private static void requireNonBlank(String name, String value) throws ApprovalException {
        if (value == null || value.isBlank()) {
            throw new ApprovalException("invalid_" + name, name + " must be non-blank");
        }
    }
}
