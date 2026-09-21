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
     * "usage limit" comes from the Codex transcript above, and "session limit" from the Claude
     * CLI's HTTP 429 envelope. The rest are the conventional wordings for the same condition.
     * They are deliberately phrases and not single words: the bare word "quota" appears in
     * ordinary prose about quotas, including this file.
     */
    public static final List<String> QUOTA_SIGNATURES = List.of(
            "usage limit",
            "session limit",
            "quota exceeded",
            "quota exhausted",
            "quota reached",
            "out of credits",
            "insufficient credits",
            "insufficient_quota",
            "credit balance is too low");

    /**
     * Phrases that mean "slow down", lowercase, which is not the same sentence.
     *
     * They used to sit in the list above, and a single 429 was then read as a spent plan: the
     * role's profile was dropped for the rest of the process and the run either failed over to
     * another vendor or stopped for a person to choose one. A rate limit is transient and says
     * nothing about the subscription — the Claude API documents 429 for request-rate and
     * spend-rate limits alike — so it gets its own kind, keeps the profile eligible, and stops
     * the run without asking who should replace a vendor that is not gone.
     *
     * A spent-subscription phrase wins when both appear. Claude's session-limit envelope is an
     * HTTP 429, and the more specific sentence is the one to believe.
     */
    public static final List<String> RATE_LIMIT_SIGNATURES = List.of(
            "rate limit",
            "rate_limit",
            "429 too many requests",
            "resource_exhausted");

    /** Every phrase either list recognises, in the order they are tried. */
    public static final List<String> DEFAULT_SIGNATURES = concat(QUOTA_SIGNATURES, RATE_LIMIT_SIGNATURES);

    public static final String QUOTA_EXHAUSTED = "quota_exhausted";
    public static final String RATE_LIMITED = "rate_limited";

    /** Keys under which vendors carry the human-readable text of a structured error. */
    private static final List<String> MESSAGE_KEYS =
            List.of("message", "msg", "detail", "description", "text");

    /**
     * @param kind {@link #QUOTA_EXHAUSTED} or {@link #RATE_LIMITED}; null when nothing matched
     */
    public record Detection(boolean matched, String signature, String evidence, String detectedBy,
                            String kind) {

        static final Detection NONE = new Detection(false, null, null, null, null);

        public boolean rateLimited() { return RATE_LIMITED.equals(kind); }

        /** The role outcome code this detection stands for, or null when nothing matched. */
        public String roleCode() {
            if (!matched) return null;
            return rateLimited() ? "role_rate_limited" : "role_quota_exhausted";
        }

        /** The evidence block that goes into the run report. Empty when nothing matched. */
        public Map<String, Object> report() {
            if (!matched) return Map.of();
            Map<String, Object> quota = new LinkedHashMap<>();
            quota.put("kind", kind);
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
        // A profile's own phrases describe its vendor's spent plan; that is the only thing the
        // profile key has ever been documented to mean, so they join the quota list.
        List<String> quota = new ArrayList<>(QUOTA_SIGNATURES);
        if (extraSignatures != null) {
            for (String extra : extraSignatures) {
                if (extra != null && !extra.isBlank()) quota.add(extra.toLowerCase(Locale.ROOT));
            }
        }

        List<String> structured = structuredErrorMessages(stdout);
        structured.addAll(structuredErrorMessages(stderr));
        // stderr is consulted only as text, and stdout never is — see the note on false
        // negatives above. The kind is decided across both sources before the source is: a
        // spent plan named anywhere outranks a rate limit named anywhere, because a CLI may put
        // a generic 429 in its event stream and the reason for it on stderr. Within a kind, the
        // structured event is the stronger evidence and is the one recorded.
        List<String> text = lines(stderr);
        for (Detection found : List.of(
                match(quota, structured, "structured_error_event", QUOTA_EXHAUSTED),
                match(quota, text, "stderr_text", QUOTA_EXHAUSTED),
                match(RATE_LIMIT_SIGNATURES, structured, "structured_error_event", RATE_LIMITED),
                match(RATE_LIMIT_SIGNATURES, text, "stderr_text", RATE_LIMITED))) {
            if (found.matched()) return found;
        }
        return Detection.NONE;
    }

    private static Detection match(List<String> signatures, List<String> candidates, String detectedBy,
                                   String kind) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String haystack = candidate.toLowerCase(Locale.ROOT);
            for (String signature : signatures) {
                if (haystack.contains(signature)) {
                    return new Detection(true, signature, condense(candidate), detectedBy, kind);
                }
            }
        }
        return Detection.NONE;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
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
        // Claude does not emit an error event: a refused call is a `result` envelope, subtype
        // `success`, with `is_error: true` and the vendor's sentence under `result`. That key
        // holds the model's own answer whenever the call succeeded, so it is read only when
        // the vendor itself flagged the envelope.
        boolean flaggedByVendor = Boolean.TRUE.equals(object.get("is_error"));
        boolean errorShaped = flaggedByVendor || object.get("error") != null
                || type.contains("error") || type.endsWith("failed");
        if (errorShaped) {
            for (String key : MESSAGE_KEYS) {
                if (object.get(key) instanceof String text) into.add(text);
            }
        }
        if (flaggedByVendor && object.get("result") instanceof String text) into.add(text);
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
