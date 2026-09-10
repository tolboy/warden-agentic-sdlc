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
        return reserveWorkflowRun(taskId, Map.of());
    }

    /**
     * @param extra fields recorded on the reservation itself, so a finished run still says
     *              how it was prepared. Unknown keys are the caller's; the schema version
     *              and identity fields stay Warden's.
     */
    public Path reserveWorkflowRun(String taskId, Map<String, Object> extra) throws IOException {
        Path marker = runDirectory.resolve("run.json");
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("schema_version", 1L);
        reservation.put("run_id", runDirectory.getFileName().toString());
        reservation.put("task_id", taskId);
        reservation.put("reserved_at", Instant.now().toString());
        if (extra != null) extra.forEach(reservation::put);
        try {
            Files.writeString(marker, Json.writePretty(reservation) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new RunExistsException("run id already reserved: " + runDirectory.getFileName(), duplicate);
        }
        // The narration is not evidence, and this is the check that has to know it. `--watch`
        // opens a window following `narration.log` before the loop starts, which means the file
        // exists before the reservation does — and every watched run then failed its first
        // attempt with `run_id_exists`. Found by running Warden against Warden, on the first
        // dogfood run, which is the only place it could have been found: no test exercises the
        // flag through `main`, and the file is created by the flag rather than by the loop.
        try (var entries = Files.list(runDirectory)) {
            if (entries.anyMatch(path -> !path.equals(marker) && !isNarration(path))) {
                Files.deleteIfExists(marker);
                throw new RunExistsException("run directory already contains evidence: "
                        + runDirectory.getFileName(), null);
            }
        }
        return marker;
    }

    /**
     * Record that preparation finished and spent these vendor calls, so a later controller
     * — {@code warden run} in this process, or Conductor's inner {@code warden run} — can
     * join the reservation instead of refusing it as a duplicate.
     */
    public void markPrepared(int roleRuns, double costUsd, int unpriced) throws IOException {
        Path marker = runDirectory.resolve("run.json");
        if (!Files.isRegularFile(marker)) {
            throw new IOException("cannot mark preparation: run id is not reserved");
        }
        Map<String, Object> reservation = Json.parseObject(Files.readString(marker, StandardCharsets.UTF_8));
        reservation.put("prepared", true);
        reservation.put("preparation_role_runs", (long) Math.max(0, roleRuns));
        reservation.put("preparation_cost_usd", costUsd);
        reservation.put("preparation_unpriced", (long) Math.max(0, unpriced));
        Files.writeString(marker, Json.writePretty(reservation) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Take exclusive ownership of a run the planner already reserved. CREATE_NEW on
     * {@code loop.json} is the same fence {@link #reserveWorkflowRun} uses on {@code run.json}:
     * a second controller is refused before it can overwrite evidence.
     *
     * @return the reservation body, including the preparation spend the loop must count
     */
    public Map<String, Object> claimPreparedLoop(String taskId) throws IOException {
        Path marker = runDirectory.resolve("run.json");
        if (!Files.isRegularFile(marker)) {
            throw new RunExistsException("run id already reserved: " + runDirectory.getFileName(), null);
        }
        Map<String, Object> reservation = Json.parseObject(Files.readString(marker, StandardCharsets.UTF_8));
        if (!String.valueOf(reservation.get("task_id")).equals(taskId)
                || !Boolean.TRUE.equals(reservation.get("prepared"))) {
            throw new RunExistsException("run id already reserved: " + runDirectory.getFileName(), null);
        }
        Path claim = runDirectory.resolve("loop.json");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema_version", 1L);
            body.put("run_id", runDirectory.getFileName().toString());
            body.put("task_id", taskId);
            body.put("claimed_at", Instant.now().toString());
            Files.writeString(claim, Json.writePretty(body) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new RunExistsException("run id already reserved: " + runDirectory.getFileName(), duplicate);
        }
        return reservation;
    }

    /**
     * The one file in a run directory that a run may find already there.
     *
     * It holds the terminal narration, which every comment in this codebase describes as not
     * being evidence: each line restates something the ledger already has. A reservation that
     * counted it would make the two statements contradict each other, and it did.
     */
    public static final String NARRATION = "narration.log";

    /**
     * The files a run may find already in its own directory, all of them belonging to `--watch`.
     *
     * The narration and the tiny script a terminal follows it with are written before the loop
     * starts, because the window has to exist before there is anything to show in it. None of
     * them is evidence, so none of them may make a fresh run look like a used one.
     */
    private static final java.util.Set<String> WATCH_FILES =
            java.util.Set.of(NARRATION, "follow.ps1", "follow.sh");

    private static boolean isNarration(java.nio.file.Path path) {
        return WATCH_FILES.contains(path.getFileName().toString());
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
