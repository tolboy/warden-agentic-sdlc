package dev.warden.execution;

import dev.warden.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a failed vendor run failed because the subscription ran out, rather than
 * because the work was wrong.
 *
 * The distinction is not cosmetic. A wrong answer should be handed back to the same vendor
 * with the failure text attached; an exhausted subscription must never be, because the next
 * call fails identically and the loop spends its budget on a wall. Quota is the one failure
 * whose correct response is a different vendor.
 *
 * Nothing here can be settled by an exit code. The live specimen this class was built from is
 * Codex, which exits 1 for a usage limit exactly as it exits 1 for a bad flag:
 *
 * <pre>
 * $ codex exec "Reply with exactly: ok" --json           (exit 1)
 * {"type":"thread.started","thread_id":"01a0404c-..."}
 * {"type":"turn.started"}
 * {"type":"error","message":"You've hit your usage limit. Upgrade to Pro (...)
 *                            or try again at 9:21 PM."}
 * {"type":"turn.failed","error":{"message":"You've hit your usage limit. ..."}}
 * </pre>
 *
 * Two details of that transcript shape the design. The quota message arrives on <b>stdout</b>,
 * inside the structured event stream; stderr carried only unrelated MCP transport noise, so a
 * report quoting stderr tells an operator nothing about why the run stopped. And the vendor
 * volunteers a retry time, which is worth keeping verbatim and worth not parsing: "9:21 PM"
 * carries no date and no timezone, so any instant Warden derived from it would be a guess
 * printed as a fact.
 *
 * <h2>Why matching is tiered</h2>
 *
 * Vendor prose is the only signal available, and matching prose has one false positive that
 * matters: a reviewer reading code <i>about</i> rate limiting can print the phrase "usage
 * limit" in a perfectly ordinary answer. Two rules keep that from re-routing a run.
 *
 * <ol>
 *   <li>Detection is consulted only for a run that already failed. A run that produced a valid
 *       artifact is never reclassified.</li>
 *   <li>Only two sources are searched, and free-form stdout is not one of them. A structured
 *       error event is authoritative and is reported as {@code structured_error_event}; stderr
 *       text is the diagnostic channel and is admitted as the weaker {@code stderr_text}.
 *       Unstructured stdout is where the model's own answer lives, so the review above — which
 *       fails, and says "usage limit" while explaining why — is left as an ordinary failure.</li>
 * </ol>
 *
 * That last rule buys safety with a false negative: a vendor announcing a spent plan only as
 * loose stdout prose is reported as {@code role_command_failed}. The cost of that is bounded,
 * because a failed run now carries a tail of <i>both</i> streams into its report, so the
 * operator reads the vendor's own sentence either way. The cost of the opposite mistake is
 * not bounded in the same way: it re-routes work to a second vendor and pays it to fail
 * identically. A profile that knows its vendor's wording can supply it and get the structured
 * treatment; guessing on Warden's behalf is what is refused.
 *
 * The matched phrase and the matched line are both recorded. A misclassification should be
 * readable in the evidence rather than inferred from behaviour.
 */
public final class QuotaSignal {

    /**
     * Phrases that mean "the subscription is spent", lowercase.
     *
     * "usage limit" comes from the Codex transcript above. The rest are the conventional
     * wordings for the same condition. They are deliberately phrases and not single words:
     * the bare word "quota" appears in ordinary prose about quotas, including this file.
     */
    public static final List<String> DEFAULT_SIGNATURES = List.of(
            "usage limit",
            "rate limit",
            "rate_limit",
            "quota exceeded",
            "quota exhausted",
            "out of credits",
            "insufficient credits",
            "insufficient_quota",
            "credit balance is too low",
            "429 too many requests",
            "resource_exhausted");

    /** Keys under which vendors carry the human-readable text of a structured error. */
    private static final List<String> MESSAGE_KEYS =
            List.of("message", "msg", "detail", "description", "text");

    public record Detection(boolean matched, String signature, String evidence, String detectedBy) {

        static final Detection NONE = new Detection(false, null, null, null);

        /** The evidence block that goes into the run report. Empty when nothing matched. */
        public Map<String, Object> report() {
            if (!matched) return Map.of();
            Map<String, Object> quota = new LinkedHashMap<>();
            quota.put("matched_signature", signature);
            quota.put("detected_by", detectedBy);
            quota.put("vendor_message", evidence);
            quota.put("note", "the vendor message is recorded verbatim; Warden does not parse a "
                    + "retry time from it, because the wording carries no date or timezone");
            return quota;
        }
    }

    private QuotaSignal() {}

    /**
     * @param extraSignatures phrases contributed by the profile. They are added to the
     *                        defaults rather than replacing them, so that teaching Warden one
     *                        vendor's wording cannot silently un-teach it another's.
     */
    public static Detection detect(List<String> extraSignatures, String stdout, String stderr) {
        List<String> signatures = new ArrayList<>(DEFAULT_SIGNATURES);
        if (extraSignatures != null) {
            for (String extra : extraSignatures) {
                if (extra != null && !extra.isBlank()) signatures.add(extra.toLowerCase(Locale.ROOT));
            }
        }

        List<String> structured = structuredErrorMessages(stdout);
        structured.addAll(structuredErrorMessages(stderr));
        Detection inEvents = match(signatures, structured, "structured_error_event");
        if (inEvents.matched()) return inEvents;

        // Only reached when the vendor said nothing machine-readable about the failure. stdout
        // is deliberately not consulted here — see the note on false negatives above.
        return match(signatures, lines(stderr), "stderr_text");
    }

    private static Detection match(List<String> signatures, List<String> candidates, String detectedBy) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String haystack = candidate.toLowerCase(Locale.ROOT);
            for (String signature : signatures) {
                if (haystack.contains(signature)) {
                    return new Detection(true, signature, condense(candidate), detectedBy);
                }
            }
        }
        return Detection.NONE;
    }

    /**
     * Pulls the message out of every error-shaped JSON object in a JSONL stream. A vendor that
     * streams events puts the real cause in one of them and then exits with a generic code.
     */
    private static List<String> structuredErrorMessages(String output) {
        List<String> messages = new ArrayList<>();
        if (output == null) return messages;
        for (String line : output.split("\\R")) {
            String candidate = line.strip();
            if (!candidate.startsWith("{")) continue;
            Map<String, Object> parsed;
            try {
                parsed = Json.parseObject(candidate);
            } catch (RuntimeException notJson) {
                continue;
            }
            collectErrorMessages(parsed, messages, 0);
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static void collectErrorMessages(Map<String, Object> object, List<String> into, int depth) {
        if (depth > 4) return;
        String type = object.get("type") instanceof String name ? name.toLowerCase(Locale.ROOT) : "";
        // A present-but-null `error` is the shape of a success envelope, not of a failure.
        // Treating it as error-shaped would hand the model's own answer to the matcher.
        boolean errorShaped = object.get("error") != null || type.contains("error") || type.endsWith("failed");
        if (errorShaped) {
            for (String key : MESSAGE_KEYS) {
                if (object.get(key) instanceof String text) into.add(text);
            }
        }
        Object nested = object.get("error");
        if (nested instanceof Map<?, ?> map) {
            collectErrorMessages((Map<String, Object>) map, into, depth + 1);
        } else if (nested instanceof String text) {
            into.add(text);
        }
    }

    private static List<String> lines(String output) {
        List<String> result = new ArrayList<>();
        if (output == null) return result;
        for (String line : output.split("\\R")) result.add(line.strip());
        return result;
    }

    private static String condense(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 400 ? single : single.substring(0, 400) + "...";
    }
}
