package dev.warden.execution;

import dev.warden.json.Json;

import java.util.ArrayList;
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
 * A bare {@code 403} in stderr text is not a signature. Rate-limit and quota envelopes
 * mention status codes in ordinary failures, and matching the digits alone would re-route
 * those. A structured error's {@code code} field of 403, or its {@code status} of
 * {@code PERMISSION_DENIED}, is the field the vendor used for the refusal, and it counts
 * even when the message text does not repeat it. Quota is still consulted first by the
 * caller, and only after the call left no usable artifact.
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
        // The caller asks QuotaSignal first. Checked again here so a 403 field on a
        // spent-plan envelope cannot become a location refusal if that order changes.
        // Profile-specific phrases are the caller's; the defaults cover the sentences
        // this class would otherwise steal.
        if (QuotaSignal.detect(List.of(), stdout, stderr).matched()) return Detection.NONE;
        List<String> structured = QuotaSignal.structuredErrors(stdout);
        structured.addAll(QuotaSignal.structuredErrors(stderr));
        Detection fromEvent = match(structured, "structured_error_event");
        if (fromEvent.matched()) return fromEvent;
        // Message text did not name the refusal. The vendor may still have put it in
        // code/status, which are not message keys. Phrases stay first so a location
        // sentence keeps the signature it always recorded.
        Detection fromFields = matchFields(stdout, stderr);
        if (fromFields.matched()) return fromFields;
        return match(QuotaSignal.stderrLines(stderr), "stderr_text");
    }

    /**
     * {@code code: 403} and {@code status: PERMISSION_DENIED} inside an error envelope.
     * A review that happens to contain those keys outside an error object is not one:
     * that object is the model's answer, and a usable artifact never reaches here.
     */
    private static Detection matchFields(String stdout, String stderr) {
        List<Map<String, Object>> objects = new ArrayList<>();
        objects.addAll(QuotaSignal.structuredErrorObjects(stdout));
        objects.addAll(QuotaSignal.structuredErrorObjects(stderr));
        Detection denied = null;
        Detection code = null;
        for (Map<String, Object> object : objects) {
            if (denied == null && (permissionDenied(object.get("status"))
                    || permissionDenied(object.get("code")))) {
                denied = fieldDetection("permission_denied", object);
            }
            if (code == null && (http403(object.get("code")) || http403(object.get("status")))) {
                code = fieldDetection("code 403", object);
            }
        }
        if (denied != null) return denied;
        return code != null ? code : Detection.NONE;
    }

    private static Detection fieldDetection(String signature, Map<String, Object> object) {
        Object message = object.get("message");
        String evidence = message instanceof String text && !text.isBlank()
                ? QuotaSignal.condensed(text)
                : QuotaSignal.condensed(Json.write(object));
        return new Detection(true, signature, evidence, "structured_error_event");
    }

    /** The status string vendors put on a permission refusal, not a sentence about one. */
    private static boolean permissionDenied(Object value) {
        if (!(value instanceof String text)) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return "permission_denied".equals(normalized);
    }

    /** A numeric or string HTTP 403. {@code 403 Forbidden} in prose is not this field. */
    private static boolean http403(Object value) {
        if (value instanceof Number number) return number.doubleValue() == 403.0d;
        if (value instanceof String text) return "403".equals(text.trim());
        return false;
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
