package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic JSONL read for the measurement reader.
 *
 * Delivery still uses {@link HomeCorpus#readJsonl}: a torn tail must not fail an append, and
 * the index rebuild must not start reporting. This sibling never throws on a corrupt line
 * or an unsupported schema version. It yields the file, the byte offset, a skip count and
 * enough to mark the report {@code incomplete}.
 */
public final class JsonlDiagnostics {

    public static final long SUPPORTED_SCHEMA_VERSION = HomeCorpus.SCHEMA_VERSION;

    /**
     * @param projectId recovered from a parsed-but-skipped row, or {@code null} when
     *                  the row had none or could not be parsed
     * @param identityRecovered {@code true} when the JSON object parsed, so a missing
     *                          {@code project_id} is a known unknown rather than a
     *                          failure to read the row
     */
    public record Skip(String file, long offset, String reason, String projectId,
                       boolean identityRecovered) {
        public Skip(String file, long offset, String reason) {
            this(file, offset, reason, null, false);
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", file);
            row.put("offset", offset);
            row.put("reason", reason);
            return row;
        }
    }

    public record Read(List<Map<String, Object>> rows, List<Skip> skipped) {
        public boolean incomplete() {
            return !skipped.isEmpty();
        }
    }

    private JsonlDiagnostics() {}

    public static Read read(Path path) throws IOException {
        return read(path, path == null ? "" : path.getFileName().toString());
    }

    /**
     * @param label the file name reported on a skip, relative to the tree the reader was
     *              asked about rather than an absolute path
     */
    public static Read read(Path path, String label) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) return new Read(rows, skipped);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException unreadable) {
            skipped.add(new Skip(label, 0L, "unreadable"));
            return new Read(rows, skipped);
        }
        int index = 0;
        while (index < bytes.length) {
            int start = index;
            int end = start;
            while (end < bytes.length && bytes[end] != (byte) '\n') end++;
            boolean terminated = end < bytes.length;
            int lineEnd = end;
            if (lineEnd > start && bytes[lineEnd - 1] == (byte) '\r') lineEnd--;
            index = terminated ? end + 1 : bytes.length;
            if (isBlank(bytes, start, lineEnd)) continue;
            String line = new String(bytes, start, lineEnd - start, StandardCharsets.UTF_8);
            Map<String, Object> row;
            try {
                row = Json.parseObject(line);
            } catch (RuntimeException failed) {
                skipped.add(new Skip(label, start, terminated ? "corrupt" : "truncated"));
                continue;
            }
            if (unsupportedSchema(row)) {
                skipped.add(new Skip(label, start, "unsupported_schema_version",
                        projectIdOf(row), true));
                continue;
            }
            rows.add(row);
        }
        return new Read(rows, skipped);
    }

    public static Map<String, Object> skippedMap(List<Skip> skipped) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", (long) skipped.size());
        List<Map<String, Object>> records = new ArrayList<>(skipped.size());
        for (Skip skip : skipped) records.add(skip.toMap());
        body.put("records", records);
        return body;
    }

    static String projectIdOf(Map<String, Object> row) {
        if (row == null) return null;
        Object value = row.get("project_id");
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    static boolean unsupportedSchema(Map<String, Object> row) {
        Object version = row.get("schema_version");
        if (version == null) return false;
        if (version instanceof Number number) {
            return number.longValue() != SUPPORTED_SCHEMA_VERSION
                    || number.doubleValue() != (double) SUPPORTED_SCHEMA_VERSION;
        }
        return true;
    }

    private static boolean isBlank(byte[] bytes, int start, int end) {
        for (int index = start; index < end; index++) {
            byte b = bytes[index];
            if (b != ' ' && b != '\t' && b != '\r') return false;
        }
        return true;
    }
}
