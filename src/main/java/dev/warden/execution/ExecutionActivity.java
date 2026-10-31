package dev.warden.execution;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lossy UI projection. These events never authorize dispatch, settlement or accounting. */
public record ExecutionActivity(Attempt attempt, Update update, long elapsedMillis) {
    public enum Kind {
        DISPATCHING, OUTPUT, TOOL_STARTED, TOOL_FINISHED, MESSAGE, PROVIDER_ERROR,
        OBSERVER_UNAVAILABLE, ATTEMPT_FINISHED
    }

    public record Attempt(String stage, String role, String profile, String vendor,
                          String modelRequested, String effortRequested, int number, String evidenceName) {}

    /** Only fixed vocabulary and numeric metadata cross the output-to-UI boundary. */
    public record Update(Kind kind, String tool, Integer exitCode, long stdoutBytes, long stderrBytes) {
        public Update {
            if (tool != null && !java.util.Set.of("shell", "file", "read", "search", "web", "mcp", "other").contains(tool)) {
                tool = "other";
            }
        }
        public static Update of(Kind kind) { return new Update(kind, null, null, 0, 0); }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", label(attempt.stage()));
        result.put("role", label(attempt.role()));
        result.put("profile", label(attempt.profile()));
        result.put("vendor", label(attempt.vendor()));
        result.put("model_requested", label(attempt.modelRequested()));
        result.put("effort_requested", label(attempt.effortRequested()));
        result.put("vendor_attempt", attempt.number());
        result.put("evidence_name", label(attempt.evidenceName()));
        result.put("kind", update.kind().name().toLowerCase(java.util.Locale.ROOT));
        result.put("tool", update.tool());
        result.put("exit_code", update.exitCode());
        result.put("elapsed_millis", Math.max(0, elapsedMillis));
        result.put("stdout_bytes_observed", update.stdoutBytes());
        result.put("stderr_bytes_observed", update.stderrBytes());
        return result;
    }

    public String summary() {
        String detail = switch (update.kind()) {
            case DISPATCHING -> "dispatching";
            case OUTPUT -> "output " + (update.stdoutBytes() + update.stderrBytes()) + " B";
            case TOOL_STARTED -> "tool " + update.tool() + " started";
            case TOOL_FINISHED -> "tool " + update.tool() + " finished"
                    + (update.exitCode() == null ? "" : " exit=" + update.exitCode());
            case MESSAGE -> "published message";
            case PROVIDER_ERROR -> "provider error event";
            case OBSERVER_UNAVAILABLE -> "live output unavailable";
            case ATTEMPT_FINISHED -> "attempt returned";
        };
        return label(attempt.role()) + " · " + label(attempt.vendor()) + "/"
                + label(attempt.modelRequested()) + " " + label(attempt.effortRequested())
                + " #" + attempt.number() + " · " + detail;
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String shortValue = value.substring(0, Math.min(64, value.length()));
        return shortValue.replaceAll("[^\\p{L}\\p{N}._/: -]", "?");
    }
}
