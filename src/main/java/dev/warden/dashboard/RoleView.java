package dev.warden.dashboard;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A best-effort projection, never completion evidence or a scheduling input. */
public final class RoleView {
    private RoleView() {}
    public static void update(Path directory, String name, Map<String, Object> fields) {
        if (!name.matches("[a-zA-Z0-9._-]+")) return;
        try {
            Path file = directory.resolve("view-" + name + ".json");
            JsonFile.underExclusiveLock(file, "view-" + name + ".lock", () -> {
                Map<String, Object> value = Files.isRegularFile(file)
                        ? new LinkedHashMap<>(Json.parseObject(Files.readString(file))) : new LinkedHashMap<>();
                value.putAll(fields);
                value.put("schema_version", 1L);
                value.put("updated_at", Instant.now().toString());
                value.put("evidence", false);
                JsonFile.writeAtomically(file, value);
                return null;
            });
        } catch (Exception ignored) {
            // A view may go stale; a failed view must not fail a paid run.
        }
    }
}
