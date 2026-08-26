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
    private final Path runDirectory;

    public EvidenceLedger(Path projectRoot, String runId) throws IOException {
        if (!runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IOException("unsafe run id: " + runId);
        }
        runDirectory = projectRoot.resolve(".warden/runs").resolve(runId);
        Files.createDirectories(runDirectory);
    }

    public Path runDirectory() { return runDirectory; }

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
