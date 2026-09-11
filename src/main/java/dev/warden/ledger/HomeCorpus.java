package dev.warden.ledger;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The durable home measurement corpus under {@code <UserConfig.home>/ledger/}.
 *
 * Local evidence is written first. The shared record is derived from the local one by
 * {@link MeasurementProjector}, never the reverse. The durable acknowledgement is the
 * {@code force} of this writer's segment: before it nothing may report the measurement as
 * delivered, and after it the record survives deletion of the project tree.
 *
 * Recovery replays delivery, not execution. The same {@code event_id} with the same content
 * hash is a no-op; the same id with a different payload is an integrity conflict.
 */
public final class HomeCorpus {

    public static final String DIRECTORY = "ledger";
    public static final String SEGMENTS = "segments";
    public static final String OUTBOX = "outbox.jsonl";
    public static final String DELIVERED = "outbox-delivered.json";
    public static final String PROVENANCE = "provenance.jsonl";
    public static final String STATUS = "corpus_status.json";
    public static final String CONFLICTS = "conflicts.jsonl";
    public static final String INDEX = "index.json";
    public static final String INDEX_LOG = "index.jsonl";

    /**
     * How large the append-only index log may grow before it is folded back into the
     * snapshot. Compaction is the only write whose cost scales with the corpus, so it has
     * to be rare; every ordinary delivery appends one line and nothing else.
     */
    private static final long COMPACT_ABOVE_BYTES = 1_000_000L;

    /**
     * The event-id index as this process last saw it, per home.
     *
     * Delivery used to rebuild this on every append: read the snapshot, then list the
     * segments and parse every line of every one of them, then rewrite the whole map. The
     * cost of appending one measurement therefore grew with the corpus it was appended to,
     * on the one store the design says accumulates across every project forever. The full
     * reconciliation is a recovery step - it exists to catch a crash between a segment
     * append and the index write - so it runs when this process has no view of a home yet,
     * and after that the log's tail carries whatever another process added.
     */
    private static final Map<Path, Snapshot> INDEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The index as of a set of file sizes. Both are load-bearing. A log that has not grown
     * is not proof that nothing was delivered: a write that forced its segment and then
     * failed to append its index entry leaves exactly that shape, and trusting the cache
     * there delivers the same measurement twice. The segments are the readable record, so
     * their total size is what says whether this view is still the whole story.
     */
    private record Snapshot(Map<String, String> index, long logSize, Map<String, Long> segments) {}
    /** Exclusive lock sibling of the run's evidence stream. Never deleted. */
    public static final String RUN_LOCK = "evidence.lock";
    public static final long SCHEMA_VERSION = 1L;

    public enum Delivery {
        DELIVERED, DUPLICATE, CONFLICT, FAILED
    }

    /**
     * The home corpus cannot accept a new paid dispatch. Settlement of a worker already
     * running and recording a human decision are never this exception.
     */
    @SuppressWarnings("serial")
    public static final class UnavailableException extends RuntimeException {
        private final String code;

        public UnavailableException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    public record Result(Delivery delivery, String reason) {
        public boolean confirmed() {
            return delivery == Delivery.DELIVERED || delivery == Delivery.DUPLICATE
                    || delivery == Delivery.CONFLICT;
        }
    }

    private HomeCorpus() {}

    public static Path directory(Path home) {
        return home.resolve(DIRECTORY);
    }

    /**
     * Refuse a new paid vendor dispatch when the corpus cannot be written. Recovers any
     * undelivered journal rows for this project first; pending means the tree is not yet
     * safe to delete.
     */
    public static void requireDispatch(Path home, Path projectRoot) {
        if (home == null) return;
        recoverProject(home, projectRoot);
        probeDeliverable(home);
        int pending = undeliveredCount(projectRoot);
        if (pending > 0) {
            throw new UnavailableException("ledger_unavailable",
                    "ledger_unavailable: home corpus has " + pending
                            + " undelivered measurement(s); the project tree is not safe to delete "
                            + "and no new paid dispatch will start until delivery is replayed");
        }
    }

    /**
     * Append the projected body to this writer's segment, under the exclusive lock that
     * also serialises the event-id index.
     */
    public static Result deliver(Path home, String eventId, String contentHash,
                                 Map<String, Object> projected) {
        if (home == null) {
            return new Result(Delivery.FAILED, "no home");
        }
        try {
            probeWritable(home);
            WriterIdentity writer = WriterIdentity.current();
            Path dir = directory(home);
            Files.createDirectories(dir.resolve(SEGMENTS));
            Path index = dir.resolve(INDEX);
            return JsonFile.underExclusiveLock(index, "index.lock", () ->
                    deliverLocked(home, writer, eventId, contentHash, projected));
        } catch (UnavailableException unavailable) {
            return new Result(Delivery.FAILED, unavailable.getMessage());
        } catch (Exception failure) {
            return new Result(Delivery.FAILED, String.valueOf(failure.getMessage()));
        }
    }

    public static void recoverProject(Path home, Path projectRoot) {
        if (home == null || projectRoot == null) return;
        Path runs = projectRoot.resolve(".warden").resolve("runs");
        if (!Files.isDirectory(runs)) return;
        try (var directories = Files.list(runs)) {
            for (Path run : directories.filter(Files::isDirectory).toList()) {
                recoverRun(home, run);
            }
        } catch (IOException ignored) {
            // Recovery is best-effort per run; requireDispatch probes writability after.
        }
    }

    public static void recoverRun(Path home, Path runDirectory) {
        if (home == null || runDirectory == null) return;
        checkpoint("recover_start");
        try {
            underRunLock(runDirectory, () -> {
                recoverRunLocked(home, runDirectory);
                return null;
            });
        } catch (Exception ignored) {
            try {
                refreshStatus(runDirectory, "error", "recovery failed");
            } catch (IOException ignoredWrite) {
                // Status is best effort.
            }
        }
    }

    /**
     * Serialise sequence allocation, local append, journal and delivery-mark updates for one
     * run. Two processes must not assign the same {@code seq}, splice outbox lines, or lose
     * a delivery mark by read-modify-write.
     */
    public static <T> T underRunLock(Path runDirectory, JsonFile.IoOperation<T> operation)
            throws IOException {
        return JsonFile.underExclusiveLock(runDirectory.resolve("evidence.jsonl"), RUN_LOCK, operation);
    }

    public static List<Map<String, Object>> records(Path home) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        if (home == null) return events;
        Path segments = directory(home).resolve(SEGMENTS);
        if (!Files.isDirectory(segments)) return events;
        try (var files = Files.list(segments)) {
            List<Path> ordered = files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            for (Path file : ordered) events.addAll(readJsonl(file));
        }
        return events;
    }

    public static List<Map<String, Object>> exportPortable(Path home) throws IOException {
        List<Map<String, Object>> exported = new ArrayList<>();
        for (Map<String, Object> record : records(home)) {
            exported.add(MeasurementProjector.stripPortable(record));
        }
        return exported;
    }

    public static List<Map<String, Object>> conflicts(Path home) throws IOException {
        if (home == null) return List.of();
        return readJsonl(directory(home).resolve(CONFLICTS));
    }

    public static Map<String, Object> status(Path runDirectory) {
        Path file = runDirectory.resolve(STATUS);
        if (!Files.isRegularFile(file)) {
            // An absent status file is not evidence of delivery. A run whose status write
            // failed still has a journal, and the journal is the record of what it owes.
            // A run that predates the corpus has no journal and owes nothing.
            int pending = undeliveredIn(runDirectory);
            if (pending == 0) return Map.of("state", "ok");
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("state", "pending");
            derived.put("undelivered", (long) pending);
            derived.put("reason", "no status file; the journal still holds undelivered rows");
            return derived;
        }
        try {
            return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return Map.of("state", "error", "reason", "status_unreadable");
        }
    }

    public static void writeJournal(Path runDirectory, String eventId, String contentHash,
                                    Map<String, Object> projected) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("content_hash", contentHash);
        row.put("at", Instant.now().toString());
        row.put("body", projected);
        appendLine(runDirectory.resolve(OUTBOX), Json.write(row));
    }

    public static void writeProvenance(Path runDirectory, Map<String, Object> provenance)
            throws IOException {
        if (provenance == null || provenance.isEmpty()) return;
        appendLine(runDirectory.resolve(PROVENANCE), Json.write(provenance));
    }

    public static void markDelivered(Path runDirectory, String eventId) throws IOException {
        Set<String> ids = readDelivered(runDirectory);
        ids.add(eventId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", SCHEMA_VERSION);
        body.put("event_ids", List.copyOf(ids));
        JsonFile.writeAtomically(runDirectory.resolve(DELIVERED), body);
    }

    public static void refreshStatus(Path runDirectory, String state, String reason)
            throws IOException {
        refreshStatus(runDirectory, state, reason, undeliveredIn(runDirectory));
    }

    /**
     * Decide this run's delivery state from its own journal, and write it.
     *
     * The file answers one question - did what this run wrote reach the corpus - and that
     * answer does not go stale. Whether the *tree* may be deleted is a different question
     * with a different lifetime: a neighbouring run can fail to deliver a minute after this
     * file is written, and nothing would come back to correct it. So safety is counted when
     * it is reported, in {@code EvidenceLedger.corpusVisibility}, and never cached here.
     *
     * Both callers, an append after a confirmed delivery and a recovery, decide through this
     * one method: they used to count different things, and a recovery would then overwrite a
     * pending that an append had earned.
     */
    public static void refreshStatusFromJournal(Path runDirectory, String reason) throws IOException {
        int pending = undeliveredIn(runDirectory);
        refreshStatus(runDirectory, pending == 0 ? "ok" : "pending", reason, pending);
    }

    private static void refreshStatus(Path runDirectory, String state, String reason,
                                      int undelivered) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", SCHEMA_VERSION);
        body.put("state", state);
        body.put("undelivered", (long) undelivered);
        if (reason != null) body.put("reason", reason);
        body.put("updated_at", Instant.now().toString());
        Files.writeString(runDirectory.resolve(STATUS),
                Json.writePretty(body) + System.lineSeparator(), StandardCharsets.UTF_8);
    }


    public static int undeliveredCount(Path projectRoot) {
        Path runs = projectRoot.resolve(".warden").resolve("runs");
        if (!Files.isDirectory(runs)) return 0;
        int total = 0;
        try (var directories = Files.list(runs)) {
            for (Path run : directories.filter(Files::isDirectory).toList()) {
                total += undeliveredIn(run) + unjournaledIn(run);
            }
        } catch (IOException ignored) {
            return total;
        }
        return total;
    }

    static void checkpoint(String name) {
        String trace = System.getProperty("warden.ledger.trace");
        if (trace != null && !trace.isBlank()) {
            try {
                Files.writeString(Path.of(trace), name, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                long wait = Long.parseLong(System.getProperty("warden.ledger.trace.wait_ms", "50"));
                if (wait > 0) Thread.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Tracing is a test hook; it must not affect production writes.
            }
        }
        String crash = System.getProperty("warden.ledger.crash");
        if (name.equals(crash)) Runtime.getRuntime().halt(64);
    }

    static void appendLine(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        isolateIncompleteTail(file);
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static List<Map<String, Object>> readJsonl(Path path) throws IOException {
        return readJsonl(path, 0L);
    }

    /** The rows a JSONL file carries from a byte offset onwards. */
    static List<Map<String, Object>> readJsonl(Path path, long from) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isRegularFile(path)) return rows;
        List<String> lines = from <= 0 ? Files.readAllLines(path, StandardCharsets.UTF_8)
                : List.of(readFrom(path, from).split("\\R"));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            try {
                rows.add(Json.parseObject(line));
            } catch (Json.JsonException ignored) {
                if (index == lines.size() - 1) break;
            }
        }
        return rows;
    }

    private static Result deliverLocked(Path home, WriterIdentity writer, String eventId,
                                        String contentHash, Map<String, Object> projected)
            throws IOException {
        Map<String, String> index = loadIndex(home);
        String existing = index.get(eventId);
        if (existing != null) {
            if (existing.equals(contentHash)) {
                return new Result(Delivery.DUPLICATE, "same event_id and content hash");
            }
            writeConflict(home, eventId, existing, contentHash, projected);
            return new Result(Delivery.CONFLICT, "same event_id with different content");
        }
        Path dir = directory(home);
        Path segment = dir.resolve(SEGMENTS).resolve(writer.id() + ".jsonl");
        Map<String, Object> line = new LinkedHashMap<>(projected);
        line.put("content_hash", contentHash);
        line.put("writer_id", writer.id());
        long before = Files.isRegularFile(segment) ? Files.size(segment) : 0L;
        boolean completed = false;
        try {
            appendLine(segment, Json.write(line));
            // Only the bytes this write added are read back. Parsing the whole segment to
            // find one record is the same cost that made delivery grow with the corpus.
            if (!containsReadableEvent(segment, before, eventId)) {
                return new Result(Delivery.FAILED,
                        "appended measurement is not independently readable");
            }
            index.put(eventId, contentHash);
            appendIndexEntry(home, eventId, contentHash);
            compactIfNeeded(home, index);
            Map<String, Long> sizes = segmentSizes(dir);
            INDEX_CACHE.put(dir, new Snapshot(index,
                    Files.isRegularFile(dir.resolve(INDEX_LOG))
                            ? Files.size(dir.resolve(INDEX_LOG)) : 0L,
                    sizes == null ? Map.of() : sizes));
            completed = true;
        } finally {
            // A segment that was forced while the index entry was not is the one shape a
            // cached view cannot represent. Drop it; the next delivery rebuilds from the
            // segments and sees the record that is already there.
            if (!completed) INDEX_CACHE.remove(dir);
        }
        Path writerFile = directory(home).resolve("writers").resolve(writer.id() + ".json");
        if (!Files.isRegularFile(writerFile)) {
            Files.createDirectories(writerFile.getParent());
            JsonFile.writeAtomically(writerFile, writer.toMap());
        }
        return new Result(Delivery.DELIVERED, null);
    }

    private static Map<String, String> loadIndex(Path home) throws IOException {
        Path dir = directory(home);
        Path log = dir.resolve(INDEX_LOG);
        long logSize = Files.isRegularFile(log) ? Files.size(log) : 0L;
        Map<String, Long> sizes = segmentSizes(dir);
        Snapshot cached = INDEX_CACHE.get(dir);
        if (cached != null && sizes != null && logSize >= cached.logSize()
                && !shrank(cached.segments(), sizes)) {
            // The map is handed out, not copied: every caller holds the corpus lock, and
            // copying it per delivery is work proportional to everything delivered before.
            Map<String, String> index = cached.index();
            // Whatever any writer added since this view was taken, read from where it was
            // taken - the bytes that are new, not the store they were added to. A segment
            // that grew without a matching index line is the shape a delivery leaves when
            // it forces its record and then fails to index it; reading the tail finds it.
            for (Map.Entry<String, Long> entry : sizes.entrySet()) {
                long from = cached.segments().getOrDefault(entry.getKey(), 0L);
                if (entry.getValue() > from) {
                    readSegmentTail(home, dir.resolve(SEGMENTS).resolve(entry.getKey()), from, index);
                }
            }
            if (logSize > cached.logSize()) readIndexLog(log, cached.logSize(), index);
            INDEX_CACHE.put(dir, new Snapshot(index, logSize, sizes));
            return index;
        }
        Map<String, String> rebuilt = rebuildIndex(home);
        INDEX_CACHE.put(dir, new Snapshot(rebuilt, logSize,
                sizes == null ? Map.of() : sizes));
        return rebuilt;
    }

    /** Segment file names to their sizes, or null when the directory cannot be read. */
    private static Map<String, Long> segmentSizes(Path dir) {
        Path segments = dir.resolve(SEGMENTS);
        Map<String, Long> sizes = new LinkedHashMap<>();
        if (!Files.isDirectory(segments)) return sizes;
        try (var files = Files.list(segments)) {
            for (Path segment : files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .toList()) {
                sizes.put(segment.getFileName().toString(), Files.size(segment));
            }
        } catch (IOException unreadable) {
            return null;
        }
        return sizes;
    }

    /**
     * A segment that is gone or shorter than it was is not growth: compaction, a deletion or
     * a copy went past, and the cached view is no longer a prefix of the truth.
     */
    private static boolean shrank(Map<String, Long> before, Map<String, Long> now) {
        for (Map.Entry<String, Long> entry : before.entrySet()) {
            Long current = now.get(entry.getKey());
            if (current == null || current < entry.getValue()) return true;
        }
        return false;
    }

    /**
     * The whole truth about a home: the snapshot, the log written since it, and the
     * segments themselves, which outrank both because they are what a reader reads.
     */
    private static Map<String, String> rebuildIndex(Path home) throws IOException {
        Map<String, String> index = readIndexFile(home);
        readIndexLog(directory(home).resolve(INDEX_LOG), 0L, index);
        // Segments are the source of truth after a crash between append and index write.
        Path segments = directory(home).resolve(SEGMENTS);
        if (Files.isDirectory(segments)) {
            try (var files = Files.list(segments)) {
                for (Path segment : files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .toList()) {
                    readSegmentTail(home, segment, 0L, index);
                }
            }
        }
        return index;
    }

    /** Merge the records a segment carries from {@code from} onwards into the index. */
    private static void readSegmentTail(Path home, Path segment, long from,
                                        Map<String, String> index) throws IOException {
        for (Map<String, Object> row : readJsonl(segment, from)) {
            String eventId = text(row.get("event_id"));
            if (eventId == null) continue;
            String hash = text(row.get("content_hash"));
            if (hash == null) hash = ProjectIdentity.sha256(Json.write(withoutWriter(row)));
            String existing = index.get(eventId);
            if (existing == null) index.put(eventId, hash);
            else if (!existing.equals(hash)) {
                writeConflict(home, eventId, existing, hash, withoutWriter(row));
            }
        }
    }

    private static Map<String, String> readIndexFile(Path home) throws IOException {
        Map<String, String> index = new LinkedHashMap<>();
        Path file = directory(home).resolve(INDEX);
        if (!Files.isRegularFile(file)) return index;
        try {
            Map<String, Object> body = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Object events = body.get("events");
            if (events instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> row) {
                        Object hash = row.get("hash");
                        if (hash != null) index.put(String.valueOf(entry.getKey()), String.valueOf(hash));
                    } else if (entry.getValue() != null) {
                        index.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            index.clear();
        }
        return index;
    }

    private static Map<String, Object> withoutWriter(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("writer_id");
        copy.remove("content_hash");
        return copy;
    }

    private static void readIndexLog(Path log, long from, Map<String, String> into)
            throws IOException {
        if (!Files.isRegularFile(log)) return;
        String text = readFrom(log, from);
        if (text.isEmpty()) return;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> row = Json.parseObject(line);
                String eventId = text(row.get("event_id"));
                String hash = text(row.get("content_hash"));
                if (eventId != null && hash != null) into.put(eventId, hash);
            } catch (Json.JsonException ignored) {
                // A torn tail is rebuilt from the segments, which are the readable record.
            }
        }
    }

    private static void appendIndexEntry(Path home, String eventId, String contentHash)
            throws IOException {
        Path dir = directory(home);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("content_hash", contentHash);
        appendLine(dir.resolve(INDEX_LOG), Json.write(row));
    }

    /**
     * Fold the log back into the snapshot. This is the one write whose cost is the size of
     * the corpus, so it happens when the log has earned it - and once at the start, so a
     * home always carries the regular index.json that delivery refuses to work without.
     */
    private static void compactIfNeeded(Path home, Map<String, String> index) throws IOException {
        Path dir = directory(home);
        Path log = dir.resolve(INDEX_LOG);
        long logSize = Files.isRegularFile(log) ? Files.size(log) : 0L;
        boolean missingSnapshot = !Files.isRegularFile(dir.resolve(INDEX));
        if (!missingSnapshot && logSize <= COMPACT_ABOVE_BYTES) return;
        persistIndex(home, index);
        if (logSize > COMPACT_ABOVE_BYTES) Files.deleteIfExists(log);
    }

    private static void persistIndex(Path home, Map<String, String> index) throws IOException {
        Path target = directory(home).resolve(INDEX);
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IOException("index.json is not a regular file: " + target);
        }
        Map<String, Object> events = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : index.entrySet()) {
            events.put(entry.getKey(), Map.of("hash", entry.getValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", SCHEMA_VERSION);
        body.put("events", events);
        JsonFile.writeAtomically(target, body);
        if (!Files.isRegularFile(target)) {
            throw new IOException("index.json could not be persisted as a regular file: " + target);
        }
    }

    private static void writeConflict(Path home, String eventId, String existingHash,
                                      String incomingHash, Map<String, Object> incoming)
            throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("existing_hash", existingHash);
        row.put("incoming_hash", incomingHash);
        row.put("at", Instant.now().toString());
        row.put("incoming", incoming);
        appendLine(directory(home).resolve(CONFLICTS), Json.write(row));
    }

    private static void probeWritable(Path home) {
        try {
            Path dir = directory(home);
            Files.createDirectories(dir.resolve(SEGMENTS));
            Path probe = dir.resolve(".writable");
            Files.writeString(probe, Instant.now().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (Exception failure) {
            throw new UnavailableException("ledger_unavailable",
                    "ledger_unavailable: home corpus cannot be written at " + directory(home)
                            + ": " + failure.getMessage());
        }
    }

    /**
     * The dispatch gate must exercise the same files a real delivery needs: this writer's
     * segment, the index lock, the append-only index log and index persistence. Opening the
     * lock and forcing an empty segment is not enough: {@link #deliverLocked} acknowledges
     * only after its index entry is appended and {@link #persistIndex} has been able to
     * replace {@code index.json}, so a path that cannot take either must refuse new paid
     * dispatch rather than let a measurement force its segment and fail after it.
     */
    private static void probeDeliverable(Path home) {
        probeWritable(home);
        try {
            WriterIdentity writer = WriterIdentity.current();
            Path dir = directory(home);
            Path index = dir.resolve(INDEX);
            JsonFile.underExclusiveLock(index, "index.lock", () -> {
                Path segment = dir.resolve(SEGMENTS).resolve(writer.id() + ".jsonl");
                Files.createDirectories(segment.getParent());
                try (FileChannel channel = FileChannel.open(segment,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
                    channel.force(true);
                }
                try (FileChannel channel = FileChannel.open(dir.resolve(INDEX_LOG),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
                    channel.force(true);
                }
                persistIndex(home, readIndexFile(home));
                return null;
            });
        } catch (UnavailableException unavailable) {
            throw unavailable;
        } catch (Exception failure) {
            throw new UnavailableException("ledger_unavailable",
                    "ledger_unavailable: home corpus cannot accept a measurement at "
                            + directory(home) + ": " + failure.getMessage());
        }
    }

    private static void recoverRunLocked(Path home, Path runDirectory) throws IOException {
        journalMissingLocalEvents(runDirectory);
        Set<String> delivered = readDelivered(runDirectory);
        for (Map<String, Object> row : readJsonl(runDirectory.resolve(OUTBOX))) {
            String eventId = text(row.get("event_id"));
            String hash = text(row.get("content_hash"));
            if (eventId == null || delivered.contains(eventId)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> body = row.get("body") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            Result result = deliver(home, eventId, hash, body);
            checkpoint("recover_after_shared");
            if (result.confirmed()) markDelivered(runDirectory, eventId);
        }
        refreshStatusFromJournal(runDirectory, null);
    }

    /**
     * A crash can leave the last JSONL byte sequence unterminated. The next append must not
     * concatenate onto that fragment: isolate it with a newline so a later complete record
     * stays independently parseable.
     */
    private static void isolateIncompleteTail(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (size == 0) return;
            ByteBuffer buf = ByteBuffer.allocate(1);
            int read = channel.read(buf, size - 1);
            if (read == 1 && buf.get(0) != (byte) '\n') {
                channel.position(size);
                channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
                channel.force(true);
            }
        }
    }

    private static boolean containsReadableEvent(Path segment, long from, String eventId)
            throws IOException {
        for (Map<String, Object> row : readJsonl(segment, from)) {
            if (eventId.equals(text(row.get("event_id")))) return true;
        }
        return false;
    }

    /** The text a file carries from a byte offset onwards. */
    private static String readFrom(Path path, long from) throws IOException {
        long size = Files.size(path);
        if (from >= size) return "";
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(from);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size - from, Integer.MAX_VALUE));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* fill */ }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static void journalMissingLocalEvents(Path runDirectory) throws IOException {
        Set<String> journaled = new LinkedHashSet<>();
        for (Map<String, Object> row : readJsonl(runDirectory.resolve(OUTBOX))) {
            String eventId = text(row.get("event_id"));
            if (eventId != null) journaled.add(eventId);
        }
        for (Map<String, Object> event : readJsonl(runDirectory.resolve("evidence.jsonl"))) {
            String eventId = text(event.get("event_id"));
            if (eventId == null || journaled.contains(eventId)) continue;
            MeasurementProjector.Projection projection = MeasurementProjector.project(event);
            writeJournal(runDirectory, eventId, projection.contentHash(), projection.body());
            writeProvenance(runDirectory, projection.provenance());
            journaled.add(eventId);
        }
    }

    /**
     * Local events that never reached the journal, which is the one thing an unreadable or
     * missing outbox hides: {@link #undeliveredIn} counts journal rows, and a journal that
     * cannot be read counts zero of them. Recovery synthesises these rows, so this is what
     * stands between an event that was written locally and a tree deleted on the word that
     * nothing is owed. It is counted where safety is reported, not on every append.
     */
    private static int unjournaledIn(Path runDirectory) {
        try {
            Set<String> journaled = new LinkedHashSet<>();
            for (Map<String, Object> row : readJsonl(runDirectory.resolve(OUTBOX))) {
                String eventId = text(row.get("event_id"));
                if (eventId != null) journaled.add(eventId);
            }
            int count = 0;
            for (Map<String, Object> event : readJsonl(runDirectory.resolve("evidence.jsonl"))) {
                String eventId = text(event.get("event_id"));
                if (eventId != null && !journaled.contains(eventId)) count++;
            }
            return count;
        } catch (IOException unreadable) {
            // An unreadable run is not a run that owes nothing.
            return 1;
        }
    }

    private static int undeliveredIn(Path runDirectory) {
        try {
            Set<String> delivered = readDelivered(runDirectory);
            int count = 0;
            for (Map<String, Object> row : readJsonl(runDirectory.resolve(OUTBOX))) {
                String eventId = text(row.get("event_id"));
                if (eventId != null && !delivered.contains(eventId)) count++;
            }
            return count;
        } catch (IOException ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readDelivered(Path runDirectory) {
        Set<String> ids = new LinkedHashSet<>();
        Path file = runDirectory.resolve(DELIVERED);
        if (!Files.isRegularFile(file)) return ids;
        try {
            Map<String, Object> body = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (body.get("event_ids") instanceof List<?> list) {
                for (Object item : list) if (item != null) ids.add(String.valueOf(item));
            }
        } catch (Exception ignored) {
            return ids;
        }
        return ids;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
