package dev.warden.ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit, one-time import of selected local runs and archives into the home corpus.
 *
 * The same allowlist projector the write path uses is the only transform. Nothing is
 * imported that the operator did not name, and a read never imports. Re-importing a
 * source, a copy of it at another path, or an archive that overlaps a source already
 * imported leaves the totals unchanged: modern rows are keyed by {@code event_id},
 * and rows that never had one use the provenance key documented on
 * {@link #provenanceKey(Map)}.
 *
 * Copy-detection for records without {@code event_id}: the provenance key is SHA-256
 * over a fixed material string of type, time, code, ok, seq, operator run id, run
 * instance and role/stage when present. Absolute paths and raw fields never enter it
 * because they never enter the projection. The same key with the same content hash is
 * a duplicate. The same key with a different content hash is an integrity conflict —
 * flagged, not merged. Ambiguous matches do not change totals.
 */
public final class CorpusImport {

    public static final String TRANSFORM_VERSION = "measurement-projector-"
            + MeasurementProjector.SCHEMA_VERSION;
    public static final String LEGACY_PREFIX = "legacy:";
    public static final String PROVENANCE_MATERIAL = "warden.legacy-provenance.v1";
    public static final String IMPORTS = "imports.jsonl";

    private static final Set<String> NOT_EVIDENCE = Set.of(
            "outbox.jsonl", "conflicts.jsonl", "index.jsonl", "provenance.jsonl",
            IMPORTS);

    private CorpusImport() {}

    public static Map<String, Object> run(Path home, Path source) throws IOException {
        return run(home, List.of(source));
    }

    public static Map<String, Object> run(Path home, List<Path> sources) throws IOException {
        if (home == null) throw new IOException("import needs a home corpus");
        List<Map<String, Object>> results = new ArrayList<>();
        for (Path source : sources) {
            if (source == null) {
                throw new IOException("import needs a path");
            }
            results.add(importOne(home, source.toAbsolutePath().normalize()));
        }
        // An operator imports an archive in order to delete it afterwards. Telling them the
        // history was saved when every row failed to deliver is the one answer this command
        // must never give, so `ok` is derived from the receipts rather than asserted.
        boolean delivered = true;
        for (Map<String, Object> receipt : results) {
            if (number(receipt.get("failed")) > 0) delivered = false;
            if (number(receipt.get("conflicts")) > 0) delivered = false;
            if (!Boolean.TRUE.equals(receipt.get("complete"))) delivered = false;
            if (number(receipt.get("delivered")) == 0 && number(receipt.get("duplicates")) == 0
                    && number(receipt.get("read")) > 0) {
                // Rows were read and none of them reached the corpus. A source that was
                // already there in full is the `duplicates` case and is a real success.
                delivered = false;
            }
            if (number(receipt.get("files")) == 0) {
                // The named source held no evidence at all, which is not an import anyone
                // should read as done before deleting the thing they named.
                delivered = false;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", delivered);
        body.put("transform_version", TRANSFORM_VERSION);
        body.put("imports", results);
        return body;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Deterministic provenance key for a record that carries no {@code event_id}.
     *
     * The key is a function of the identity fields the row already had. It is not a
     * function of the projected body, so re-projecting under a later transform yields
     * the same key and, if the hash moved, an integrity conflict rather than a second
     * measurement.
     */
    public static String provenanceKey(Map<String, Object> event) {
        String operator = firstText(event, "operator_run_id", "run_id");
        String material = PROVENANCE_MATERIAL
                + '\0' + textOrEmpty(event.get("type"))
                + '\0' + textOrEmpty(event.get("at"))
                + '\0' + textOrEmpty(event.get("code"))
                + '\0' + textOrEmpty(event.get("ok"))
                + '\0' + textOrEmpty(event.get("seq"))
                + '\0' + textOrEmpty(operator)
                + '\0' + textOrEmpty(event.get("run_instance_id"))
                + '\0' + textOrEmpty(event.get("role"))
                + '\0' + textOrEmpty(event.get("stage"));
        return ProjectIdentity.sha256(material);
    }

    private static Map<String, Object> importOne(Path home, Path source) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("import source does not exist: " + source);
        }
        List<Path> files = new ArrayList<>();
        collect(source, files);
        long read = 0;
        long delivered = 0;
        long duplicates = 0;
        long conflicts = 0;
        long failed = 0;
        long legacy = 0;
        long unknownProject = 0;
        long unattributedSkipped = 0;
        LinkedHashSet<String> projectIds = new LinkedHashSet<>();
        List<JsonlDiagnostics.Skip> skipped = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder();
        for (Path file : files) {
            fingerprint.append(file.getFileName()).append('\0');
            String label = label(source, file);
            JsonlDiagnostics.Read parsed;
            try {
                byte[] bytes = Files.readAllBytes(file);
                fingerprint.append(ProjectIdentity.sha256(new String(bytes,
                        java.nio.charset.StandardCharsets.ISO_8859_1)));
                parsed = JsonlDiagnostics.readBytes(bytes, label);
            } catch (IOException unreadable) {
                // A vanished/unreadable file is loss, not an empty successful source.
                fingerprint.append("unreadable");
                parsed = new JsonlDiagnostics.Read(List.of(),
                        List.of(new JsonlDiagnostics.Skip(label, 0L, "unreadable")));
            }
            fingerprint.append('\n');
            skipped.addAll(parsed.skipped());
            for (JsonlDiagnostics.Skip skip : parsed.skipped()) {
                if (skip.identityRecovered()) {
                    if (skip.projectId() == null) unknownProject++;
                    else projectIds.add(skip.projectId());
                } else {
                    unattributedSkipped++;
                }
            }
            for (Map<String, Object> event : parsed.rows()) {
                read++;
                boolean missingId = text(event.get("event_id")) == null;
                if (missingId) legacy++;
                String projectId = text(event.get("project_id"));
                if (projectId == null) unknownProject++;
                else projectIds.add(projectId);
                HomeCorpus.Result result = importEvent(home, event);
                if (result.delivery() == HomeCorpus.Delivery.DELIVERED) delivered++;
                else if (result.delivery() == HomeCorpus.Delivery.DUPLICATE) duplicates++;
                else if (result.delivery() == HomeCorpus.Delivery.CONFLICT) conflicts++;
                else failed++;
            }
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("at", Instant.now().toString());
        receipt.put("transform_version", TRANSFORM_VERSION);
        receipt.put("source_name", source.getFileName() == null ? "" : source.getFileName().toString());
        receipt.put("source_kind", kind(source, files));
        receipt.put("source_fingerprint", ProjectIdentity.sha256(fingerprint.toString()));
        receipt.put("complete", skipped.isEmpty() && failed == 0 && conflicts == 0);
        receipt.put("files", (long) files.size());
        receipt.put("read", read);
        receipt.put("legacy", legacy);
        receipt.put("project_ids", List.copyOf(projectIds));
        receipt.put("unknown_project_id", unknownProject);
        receipt.put("unattributed_skipped", unattributedSkipped);
        receipt.put("delivered", delivered);
        receipt.put("duplicates", duplicates);
        receipt.put("conflicts", conflicts);
        receipt.put("failed", failed);
        receipt.put("skipped", JsonlDiagnostics.skippedMap(skipped));
        HomeCorpus.appendLine(HomeCorpus.directory(home).resolve(IMPORTS),
                dev.warden.json.Json.write(receipt));
        return receipt;
    }

    private static HomeCorpus.Result importEvent(Path home, Map<String, Object> event) {
        Map<String, Object> incoming = new LinkedHashMap<>(event);
        String eventId = text(incoming.get("event_id"));
        if (eventId == null) {
            incoming.put("event_id", LEGACY_PREFIX + provenanceKey(event));
        }
        MeasurementProjector.Projection projection = MeasurementProjector.project(incoming);
        String id = text(projection.body().get("event_id"));
        return HomeCorpus.deliver(home, id, projection.contentHash(), projection.body());
    }

    private static void collect(Path source, List<Path> into) throws IOException {
        if (Files.isRegularFile(source)) {
            String name = source.getFileName().toString();
            if (name.endsWith(".jsonl") && !NOT_EVIDENCE.contains(name)) into.add(source);
            return;
        }
        if (!Files.isDirectory(source)) return;
        Path evidence = source.resolve("evidence.jsonl");
        if (Files.isRegularFile(evidence)) {
            into.add(evidence);
            return;
        }
        Path runs = source.resolve(".warden").resolve("runs");
        if (Files.isDirectory(runs)) {
            collectRuns(runs, into);
            return;
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (!Files.isRegularFile(path)) continue;
                if (!"evidence.jsonl".equals(path.getFileName().toString())) continue;
                if (isLedgerSegment(path)) continue;
                into.add(path);
            }
        }
    }

    private static void collectRuns(Path runs, List<Path> into) throws IOException {
        try (var directories = Files.list(runs)) {
            for (Path run : directories.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                Path evidence = run.resolve("evidence.jsonl");
                if (Files.isRegularFile(evidence)) into.add(evidence);
            }
        }
    }

    private static boolean isLedgerSegment(Path file) {
        Path segments = file.getParent();
        if (segments == null || segments.getFileName() == null) return false;
        if (!HomeCorpus.SEGMENTS.equals(segments.getFileName().toString())) return false;
        Path ledger = segments.getParent();
        return ledger != null && ledger.getFileName() != null
                && HomeCorpus.DIRECTORY.equals(ledger.getFileName().toString());
    }

    private static String kind(Path source, List<Path> files) {
        if (Files.isRegularFile(source)) return "file";
        if (Files.isDirectory(source.resolve(".warden").resolve("runs"))) return "project";
        if (Files.isRegularFile(source.resolve("evidence.jsonl"))) return "run";
        if (files.size() > 1) return "archive";
        return Files.isDirectory(source) ? "archive" : "file";
    }

    private static String label(Path source, Path file) {
        try {
            Path relative = source.relativize(file);
            String text = relative.toString().replace('\\', '/');
            if (!text.isBlank() && !text.startsWith("..")) return text;
        } catch (IllegalArgumentException ignored) {
            // Different roots; fall through to the file name.
        }
        return file.getFileName().toString();
    }

    private static String firstText(Map<String, Object> event, String... names) {
        for (String name : names) {
            String value = text(event.get(name));
            if (value != null) return value;
        }
        return null;
    }

    private static String textOrEmpty(Object value) {
        String text = text(value);
        return text == null ? "" : text;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
