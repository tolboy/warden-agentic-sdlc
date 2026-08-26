package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates append-only run evidence without mutating or trusting model output. */
public final class LedgerReader {
    public Map<String, Object> summarize(Path projectRoot) throws IOException {
        Path runs = projectRoot.resolve(".warden/runs");
        List<Map<String, Object>> runRows = new ArrayList<>();
        long passed = 0;
        long failed = 0;
        if (Files.isDirectory(runs)) {
            try (var directories = Files.list(runs)) {
                for (Path run : directories.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
                    List<Map<String, Object>> events = readEvents(run.resolve("evidence.jsonl"));
                    String verdict = "no_evidence";
                    for (Map<String, Object> event : events) {
                        if (event.get("ok") instanceof Boolean ok) verdict = ok ? "passed" : "failed";
                    }
                    if (verdict.equals("passed")) passed++;
                    else if (verdict.equals("failed")) failed++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("run_id", run.getFileName().toString());
                    row.put("verdict", verdict);
                    row.put("events", (long) events.size());
                    runRows.add(row);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", 1L);
        summary.put("run_count", (long) runRows.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("runs", runRows);
        summary.put("future_dimensions", List.of("vendor", "model", "duration_ms", "cost_usd",
                "fix_attempts", "review_findings", "human_decision"));
        return summary;
    }

    private List<Map<String, Object>> readEvents(Path path) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        if (!Files.isRegularFile(path)) return events;
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) events.add(Json.parseObject(line));
        }
        return events;
    }
}
