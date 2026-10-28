package dev.warden.config;

import dev.warden.yaml.Yaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** User-wide launch settings. No project can silently disable the operator's live checks. */
public record CheckSettings(boolean beforeRun, int timeoutSeconds, int maxOutputTokens) {
    public static final CheckSettings DEFAULT = new CheckSettings(true, 90, 512);
    public static Path file(Path home) { return home.resolve("settings.yaml"); }

    public static CheckSettings load(Path home) throws IOException {
        Path file = file(home);
        if (!Files.exists(file)) return DEFAULT;
        return parse(Yaml.parse(Files.readString(file)));
    }

    public static CheckSettings parse(Object document) {
        Values root = Values.of(document, "settings.yaml").rejectUnknownKeys(Set.of("version", "check"));
        root.requireVersion(1);
        Values check = Values.of(((Map<?, ?>) document).getOrDefault("check", null), "settings.yaml check")
                .rejectUnknownKeys(Set.of("before_run", "timeout_seconds", "max_output_tokens"));
        boolean enabled = check.optBool("before_run", true);
        int seconds = (int) check.optInt("timeout_seconds", 90, 15, 180);
        int tokens = (int) check.optInt("max_output_tokens", 512, 128, 2048);
        root.throwIfAny(); check.throwIfAny();
        return new CheckSettings(enabled, seconds, tokens);
    }

    public Map<String, Object> toMap() {
        return Map.of("before_run", beforeRun, "timeout_seconds", (long) timeoutSeconds,
                "max_output_tokens", (long) maxOutputTokens);
    }

    public String yaml() {
        return "version: 1\ncheck:\n  before_run: " + beforeRun + "\n  timeout_seconds: " + timeoutSeconds
                + "\n  max_output_tokens: " + maxOutputTokens + "\n";
    }
}
