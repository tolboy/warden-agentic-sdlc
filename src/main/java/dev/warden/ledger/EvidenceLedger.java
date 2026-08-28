package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable append-only evidence plus stable per-stage JSON reports. */
public final class EvidenceLedger {
    private final Path projectRoot;
    private final Path runDirectory;

    public EvidenceLedger(Path projectRoot, String runId) throws IOException {
        if (!runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IOException("unsafe run id: " + runId);
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        runDirectory = this.projectRoot.resolve(".warden/runs").resolve(runId);
        Files.createDirectories(runDirectory);
    }

    public Path runDirectory() { return runDirectory; }
    public Path projectRoot() { return projectRoot; }

    /**
     * Fence one workflow controller before any vendor can be dispatched. Stage commands use
     * their own run ids; only the outer TaskLoop reserves a workflow id.
     */
    public Path reserveWorkflowRun(String taskId) throws IOException {
        Path marker = runDirectory.resolve("run.json");
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("schema_version", 1L);
        reservation.put("run_id", runDirectory.getFileName().toString());
        reservation.put("task_id", taskId);
        reservation.put("reserved_at", Instant.now().toString());
        try {
            Files.writeString(marker, Json.writePretty(reservation) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new RunExistsException("run id already reserved: " + runDirectory.getFileName(), duplicate);
        }
        try (var entries = Files.list(runDirectory)) {
            if (entries.anyMatch(path -> !path.equals(marker))) {
                Files.deleteIfExists(marker);
                throw new RunExistsException("run directory already contains evidence: "
                        + runDirectory.getFileName(), null);
            }
        }
        return marker;
    }

    @SuppressWarnings("serial")
    public static final class RunExistsException extends IOException {
        public RunExistsException(String message, Throwable cause) { super(message, cause); }
    }

    public Path writeReport(String name, Map<String, Object> report) throws IOException {
        Path target = runDirectory.resolve(name + ".json");
        Files.writeString(target, Json.writePretty(report) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return target;
    }

    public void append(String type, Map<String, Object> evidence) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now().toString());
        event.put("type", type);
        event.putAll(evidence);
        Files.writeString(runDirectory.resolve("evidence.jsonl"), Json.write(event) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
