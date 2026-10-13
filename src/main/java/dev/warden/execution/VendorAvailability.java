package dev.warden.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a failed vendor run failed because the account cannot use the vendor
 * here, rather than because the work was wrong or the subscription was spent.
 *
 * The specimen is an agy reviewer whose stderr was
 * {@code PERMISSION_DENIED (code 403): Your current account is not eligible for Gemini
 * Code Assist for individuals because it is not currently available in your location},
 * with {@code retryable: false}. That refusal is stable for the process: the next call
 * on the same profile fails the same way. It is not a spent plan, and it is not a
 * verdict. Calling it {@code role_command_failed} made the review stage fail over under
 * {@code failover.on_vendor_failure} without saying why, and a later stage that named
 * the same profile paid for the refusal again.
 *
 * The same two rules as {@link QuotaSignal} apply. Detection runs only after a call has
 * already failed and produced no usable artifact, and only over structured error text
 * and stderr. Free-form stdout is the model's answer; a review that quotes the sentence
 * must not be re-routed. Quota is consulted first by the caller, so a spent plan that
 * also mentions a 403 stays a spent plan.
 *
 * A bare {@code 403} is not a signature. Rate-limit and quota envelopes mention status
 * codes in ordinary failures, and matching the digits alone would re-route those.
 */
public final class VendorAvailability {

    /**
     * Phrases that mean the account cannot use this vendor from here, lowercase.
     * The first match wins. The location phrase is tried before {@code not eligible}
     * so a sentence that says both records the location.
     */
    public static final List<String> SIGNATURES = List.of(
            "not available in your location",
            "available in your location",
            "not eligible",
            "permission_denied",
            "code 403",
            "http 403",
            "permission denied");

    public static final String KIND = "vendor_unavailable";
    public static final String ROLE_CODE = "role_vendor_unavailable";

    public record Detection(boolean matched, String signature, String evidence, String detectedBy) {

        static final Detection NONE = new Detection(false, null, null, null);

        public String roleCode() { return matched ? ROLE_CODE : null; }

        /** The evidence block that goes into the run report. Empty when nothing matched. */
        public Map<String, Object> report() {
            if (!matched) return Map.of();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kind", KIND);
            body.put("matched_signature", signature);
            body.put("detected_by", detectedBy);
            body.put("vendor_message", evidence);
            return body;
        }
    }

    private VendorAvailability() {}

    /**
     * @param stdout structured error events only; free-form answer text is ignored
     * @param stderr structured error events, then the lines as text
     */
    public static Detection detect(String stdout, String stderr) {
        List<String> structured = QuotaSignal.structuredErrors(stdout);
        structured.addAll(QuotaSignal.structuredErrors(stderr));
        Detection fromEvent = match(structured, "structured_error_event");
        if (fromEvent.matched()) return fromEvent;
        return match(QuotaSignal.stderrLines(stderr), "stderr_text");
    }

    private static Detection match(List<String> candidates, String detectedBy) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String haystack = candidate.toLowerCase(Locale.ROOT);
            for (String signature : SIGNATURES) {
                if (haystack.contains(signature)) {
                    return new Detection(true, signature, QuotaSignal.condensed(candidate), detectedBy);
                }
            }
        }
        return Detection.NONE;
    }
}
