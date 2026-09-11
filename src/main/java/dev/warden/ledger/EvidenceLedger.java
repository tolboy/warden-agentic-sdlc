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
import java.util.UUID;

/**
 * Durable append-only evidence plus stable per-stage JSON reports.
 *
 * One {@link #append} performs the write-then-deliver protocol: identity and local write,
 * journal, shared home-corpus write, then a best-effort delivery mark. The shared record
 * is derived from the local one. See {@code docs/ARCHITECTURE.md}.
 */
public final class EvidenceLedger {
    public static final long SCHEMA_VERSION = 1L;
    public static final String INSTANCE_FILE = "run-instance.json";

    private final Path projectRoot;
    private final Path runDirectory;
    private final Path home;
    private final String operatorRunId;
    private final String projectId;
    private final String projectName;
    private final String runInstanceId;
    private String parentRunInstanceId;

    public EvidenceLedger(Path projectRoot, String runId) throws IOException {
        this(projectRoot, runId, null);
    }

    /**
     * @param home the operator's Warden home, already resolved by the caller. Null skips
     *             the shared write. Callers must never read the environment or
     *             {@code UserConfig.defaultHome()} at a write site.
     */
    public EvidenceLedger(Path projectRoot, String runId, Path home) throws IOException {
        if (!runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IOException("unsafe run id: " + runId);
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.home = home == null ? null : home.toAbsolutePath().normalize();
        this.operatorRunId = runId;
        runDirectory = this.projectRoot.resolve(".warden/runs").resolve(runId);
        Files.createDirectories(runDirectory);
        this.projectId = ProjectIdentity.resolve(this.projectRoot);
        this.projectName = ProjectIdentity.projectName(this.projectRoot);
        this.runInstanceId = loadOrCreateRunInstance();
        if (this.home != null) HomeCorpus.recoverRun(this.home, runDirectory);
    }

    public Path runDirectory() { return runDirectory; }
    public Path projectRoot() { return projectRoot; }
    public Path home() { return home; }
    public String projectId() { return projectId; }
    public String runInstanceId() { return runInstanceId; }
    public String operatorRunId() { return operatorRunId; }

    /**
     * Record the parent run instance of a {@code --continue}, or leave lineage unknown.
     * Unknown stays unknown rather than being guessed from a path or a matching run id.
     */
    public EvidenceLedger bindParent(String parentRunInstanceId) {
        this.parentRunInstanceId = parentRunInstanceId == null || parentRunInstanceId.isBlank()
                ? null : parentRunInstanceId;
        return this;
    }

    public static String runInstanceIdOf(Path projectRoot, String runId) {
        Path file = projectRoot.resolve(".warden/runs").resolve(runId).resolve(INSTANCE_FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            Object id = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8))
                    .get("run_instance_id");
            return id instanceof String text && !text.isBlank() ? text : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public Map<String, Object> corpusStatus() {
        return HomeCorpus.status(runDirectory);
    }

    /**
     * What the CLI owes the operator after a local write: delivery state, and whether the
     * project tree is safe to delete. Settlement and a human decision still succeed locally
     * when this is not {@code ok}.
     */
    public Map<String, Object> corpusVisibility() {
        Map<String, Object> status = corpusStatus();
        String state = String.valueOf(status.getOrDefault("state", "ok"));
        // `corpus_status` is this run's own delivery; `tree_safe_to_delete` is a question
        // about the whole tree and is counted here, at the moment it is answered. Reading it
        // back from a status file cannot work in either direction: a neighbouring run that
        // failed after this file was written leaves it saying `ok`, and a neighbour that was
        // recovered afterwards leaves it saying `pending` forever. Deleting a tree on a
        // stale yes loses a measurement no import in this slice could recover.
        int pending = HomeCorpus.undeliveredCount(projectRoot);
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("corpus_status", state);
        visible.put("corpus_undelivered", (long) pending);
        if (status.get("reason") != null) visible.put("corpus_reason", status.get("reason"));
        visible.put("tree_safe_to_delete", pending == 0);
        return visible;
    }

    public void recordCorpusVisibility(Map<String, Object> into) {
        if (into == null) return;
        into.putAll(corpusVisibility());
    }

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
        reservation.put("run_instance_id", runInstanceId);
        reservation.put("project_id", projectId);
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
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("task_id", taskId);
        measurement.put("phase", "reserve");
        if (extra != null) {
            if (extra.get("prepare") != null) measurement.put("prepare", extra.get("prepare"));
            if (extra.get("budget_plan") instanceof Map<?, ?>) {
                measurement.put("budget_plan", extra.get("budget_plan"));
            }
        }
        append("run_reserved", measurement);
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
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("task_id", reservation.get("task_id"));
        measurement.put("phase", "prepared");
        if (reservation.get("prepare") != null) measurement.put("prepare", reservation.get("prepare"));
        measurement.put("role_runs", (long) Math.max(0, roleRuns));
        measurement.put("total_cost_usd", costUsd);
        measurement.put("unpriced_calls", (long) Math.max(0, unpriced));
        append("run_prepared", measurement);
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
    /**
     * Files a reservation may find already in its directory that are not evidence of a
     * prior controller. Watch files exist before {@code run.json} because {@code --watch}
     * opens a window first. Instance, journal and corpus-status files exist because the
     * ledger is constructed before the workflow fence.
     */
    private static final java.util.Set<String> NON_EVIDENCE = java.util.Set.of(
            NARRATION, "follow.ps1", "follow.sh",
            INSTANCE_FILE,
            HomeCorpus.STATUS, HomeCorpus.OUTBOX, HomeCorpus.DELIVERED, HomeCorpus.PROVENANCE,
            HomeCorpus.RUN_LOCK);

    private static boolean isNarration(java.nio.file.Path path) {
        return NON_EVIDENCE.contains(path.getFileName().toString());
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
        HomeCorpus.underRunLock(runDirectory, () -> {
            appendLocked(type, evidence);
            return null;
        });
    }

    private void appendLocked(String type, Map<String, Object> evidence) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now().toString());
        event.put("type", type);
        if (evidence != null) event.putAll(evidence);
        String eventId = suppliedId(event.get("event_id"));
        event.put("event_id", eventId);
        event.put("schema_version", SCHEMA_VERSION);
        event.put("seq", nextSeq());
        event.put("project_id", projectId);
        if (projectName != null) event.put("project_name", projectName);
        event.put("run_instance_id", runInstanceId);
        event.put("operator_run_id", operatorRunId);
        event.put("run_id", operatorRunId);
        if (parentRunInstanceId != null) event.put("parent_run_instance_id", parentRunInstanceId);

        HomeCorpus.checkpoint("start");
        HomeCorpus.appendLine(runDirectory.resolve("evidence.jsonl"), Json.write(event));
        HomeCorpus.checkpoint("after_local");

        MeasurementProjector.Projection projection = MeasurementProjector.project(event);
        HomeCorpus.writeProvenance(runDirectory, projection.provenance());
        HomeCorpus.writeJournal(runDirectory, eventId, projection.contentHash(), projection.body());
        try {
            HomeCorpus.refreshStatus(runDirectory, "pending", "delivery in flight");
        } catch (IOException ignored) {
            // Status is derived; the journal is the record of undelivered work.
        }
        HomeCorpus.checkpoint("after_journal");

        if (home == null) {
            HomeCorpus.refreshStatus(runDirectory, "pending", "no home corpus configured");
            HomeCorpus.checkpoint("after_shared");
            HomeCorpus.checkpoint("after_ack");
            return;
        }

        HomeCorpus.Result result = HomeCorpus.deliver(home, eventId, projection.contentHash(),
                projection.body());
        HomeCorpus.checkpoint("after_shared");
        if (result.confirmed()) {
            try {
                HomeCorpus.markDelivered(runDirectory, eventId);
            } catch (IOException ignored) {
                // Delivery mark is best effort. A crash here leaves the event undelivered
                // in the journal; recovery re-appends and the corpus treats it as a no-op.
            }
            try {
                HomeCorpus.refreshStatusFromJournal(runDirectory,
                        result.delivery() == HomeCorpus.Delivery.CONFLICT
                                ? result.reason() : null);
            } catch (IOException ignored) {
                // Status is derived, not the acknowledgement.
            }
        } else {
            try {
                HomeCorpus.refreshStatus(runDirectory, "error", result.reason());
            } catch (IOException ignored) {
                // Local evidence and the journal remain the record of what happened.
            }
        }
        HomeCorpus.checkpoint("after_ack");
    }

    private String loadOrCreateRunInstance() throws IOException {
        Path file = runDirectory.resolve(INSTANCE_FILE);
        if (Files.isRegularFile(file)) {
            try {
                Object id = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8))
                        .get("run_instance_id");
                if (id instanceof String text && !text.isBlank()) return text;
            } catch (RuntimeException ignored) {
                // Replace a broken instance file rather than inventing lineage.
            }
        }
        Path marker = runDirectory.resolve("run.json");
        if (Files.isRegularFile(marker)) {
            try {
                Object id = Json.parseObject(Files.readString(marker, StandardCharsets.UTF_8))
                        .get("run_instance_id");
                if (id instanceof String text && !text.isBlank()) {
                    return writeInstance(file, text);
                }
            } catch (RuntimeException ignored) {
                // Fall through and mint a new instance id.
            }
        }
        String id = UUID.randomUUID().toString();
        return writeInstance(file, id);
    }

    private String writeInstance(Path file, String id) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", SCHEMA_VERSION);
        body.put("run_instance_id", id);
        body.put("operator_run_id", operatorRunId);
        body.put("project_id", projectId);
        try {
            Files.writeString(file, Json.writePretty(body) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return id;
        } catch (java.nio.file.FileAlreadyExistsException race) {
            Object existing = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8))
                    .get("run_instance_id");
            if (existing instanceof String text && !text.isBlank()) return text;
            return id;
        }
    }

    private long nextSeq() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        if (!Files.isRegularFile(file)) return 1L;
        long count = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) count++;
        }
        return count + 1L;
    }

    private static String suppliedId(Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        return UUID.randomUUID().toString();
    }
}
