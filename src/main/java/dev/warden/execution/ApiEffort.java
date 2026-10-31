package dev.warden.execution;

import java.util.Map;
import java.util.Set;

/** Exact gateway effort delivery; token usage is never proof of the selected effort. */
final class ApiEffort {
    private static final Set<String> LEVELS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");

    static void initialize(String effort, Map<String,Object> evidence) {
        evidence.put("effort_requested", effort);
        evidence.put("effort_transmitted", null);
        evidence.put("effort_reported", null);
        evidence.put("effort_delivery", "openrouter_reasoning");
        evidence.put("effort_transmission", effort == null ? "not_requested" : "not_sent");
        evidence.put("effort_verification", "not_reported_by_provider");
    }

    static void apply(String effort, Map<String,Object> body) {
        if (effort != null) body.put("reasoning", Map.of("effort", effort));
    }

    static void sent(String effort, boolean answered, boolean nothingSent, Map<String,Object> evidence) {
        if (effort == null) return;
        evidence.put("effort_transmission", nothingSent ? "not_sent" : answered ? "sent" : "unknown");
        evidence.put("effort_transmitted", answered && !nothingSent ? effort : null);
    }

    /** Only structured provider metadata counts; reasoning prose and reasoning_tokens do not. */
    static boolean reported(String requested, Map<String,Object> envelope, Map<String,Object> evidence) {
        if (envelope == null) return true;
        Object value = envelope.get("reasoning_effort");
        if (envelope.get("reasoning") instanceof Map<?,?> reasoning) value = reasoning.get("effort");
        if (value == null) return true;
        if (!(value instanceof String reported) || !LEVELS.contains(reported)) {
            // Never copy arbitrary provider text: it may reflect credentials. An explicit
            // unknown setting is a conflicting report, not absence of a report.
            evidence.put("effort_reported", "unrecognized");
            evidence.put("effort_verification", requested == null ? "unrecognized_provider_report" : "provider_mismatch");
            return requested == null;
        }
        evidence.put("effort_reported", reported);
        evidence.put("effort_verification", requested == null ? "reported_by_provider"
                : requested.equals(reported) ? "confirmed_by_provider" : "provider_mismatch");
        return requested == null || requested.equals(reported);
    }

    static void copy(Map<String,Object> source, Map<String,Object> target) {
        for (String field : Set.of("effort_requested", "effort_transmitted", "effort_reported", "effort_delivery",
                "effort_transmission", "effort_verification")) if (source.containsKey(field)) target.put(field, source.get(field));
    }
}
