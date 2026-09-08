package dev.warden.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a reviewer objected to, as something a later round can recognise again.
 *
 * A finding used to be a bag of prose with a severity on it. That is enough to hand back to an
 * implementer once, and not enough for any question worth asking after the second round: is
 * this the defect we already tried to fix, or a new one; did the repair close anything; is the
 * loop making progress or paying to rediscover the same objection. Measured on a live run —
 * the second reviewer returned the identical objection after a repair, and the loop had no way
 * to say so, because two reports of one defect were two unrelated blobs of text.
 *
 * So a finding gets an identity. The model may name it, and Warden checks the name; when it
 * does not, Warden derives one from the part of the report that identifies the defect rather
 * than describes it — the path and the normalised message. `id_source` records which happened,
 * because a derived identity and a vendor-supplied one are different strengths of claim.
 *
 * <h2>What this identity does not do</h2>
 *
 * It matches a re-report that keeps the same path and substantially the same wording. A genuine
 * reword by a different vendor produces a different id, and two distinct defects on one line
 * with near-identical messages collide. That is why the loop's progress signal does not rest on
 * finding text alone: the candidate fingerprint answers "did anything change" without reading a
 * word, and this answers the narrower "is it being called the same thing".
 */
public final class Findings {

    private Findings() {}

    /**
     * What kind of problem a finding reports, as a closed set.
     *
     * The distinction the loop actually needs is between a defect in the work and everything
     * else, because only the first is something an implementer can repair. A missing access
     * grant, an unreachable provider or a gap in the task's own contract all arrive as a
     * reviewer's objection and none of them is fixed by handing the diff back.
     *
     * The model proposes and Warden validates: an unrecognised category is not an invented
     * one, it is `product_defect` with the fact that it was defaulted recorded beside it.
     */
    public static final Set<String> CATEGORIES = Set.of(
            "product_defect",
            "investigation_evidence_gap",
            "access_required",
            "contract_gap",
            "tooling_failure",
            "quota_exhausted",
            "provider_unavailable",
            "review_disagreement");

    public static final String DEFAULT_CATEGORY = "product_defect";

    /** One objection, normalised, with an identity that survives being reported again. */
    public record Finding(String id, String idSource, String category, String categorySource,
                          String severity, String path, String message, Map<String, Object> raw) {

        public boolean blocking() { return "P1".equals(severity); }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("id_source", idSource);
            value.put("category", category);
            value.put("category_source", categorySource);
            value.put("severity", severity);
            value.put("path", path);
            value.put("message", message);
            return value;
        }
    }

    /** Every finding in a reviewer artifact, in the order it reported them. */
    @SuppressWarnings("unchecked")
    public static List<Finding> of(Map<String, Object> artifact) {
        List<Finding> findings = new ArrayList<>();
        if (artifact == null || !(artifact.get("findings") instanceof List<?> rows)) return findings;
        for (Object row : rows) {
            if (row instanceof Map<?, ?> entry) findings.add(one((Map<String, Object>) entry));
        }
        return findings;
    }

    private static Finding one(Map<String, Object> entry) {
        String path = text(entry.get("path"));
        String message = text(entry.get("message"));
        String severity = text(entry.get("severity"));
        if (severity.isBlank()) severity = "P3";

        Object named = entry.get("id");
        String id;
        String idSource;
        if (named instanceof String supplied && !supplied.isBlank() && supplied.length() <= 80) {
            id = supplied.trim();
            idSource = "vendor";
        } else {
            id = derive(path, message);
            idSource = "derived";
        }

        Object proposed = entry.get("category");
        String category;
        String categorySource;
        if (proposed instanceof String value && CATEGORIES.contains(value)) {
            category = value;
            categorySource = "vendor";
        } else {
            // Not an error, and not a guess dressed as one. A reviewer that named nothing, or
            // named something outside the set, gets the only category the loop can act on, and
            // the report says the choice was Warden's.
            category = DEFAULT_CATEGORY;
            categorySource = proposed == null ? "defaulted_absent" : "defaulted_unrecognised";
        }
        return new Finding(id, idSource, category, categorySource, severity, path, message, entry);
    }

    /**
     * The identity of a defect, from the two fields that say which defect it is.
     *
     * The message is normalised before hashing — case folded, whitespace collapsed, trailing
     * punctuation dropped — so that reformatting alone does not mint a new finding. Nothing
     * here understands meaning; see the class comment for what that costs.
     */
    private static String derive(String path, String message) {
        String normalised = message.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[\\s.,;:!?]+$", "")
                .trim();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(normalised.getBytes(StandardCharsets.UTF_8));
        return "f-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    /** The ids of the findings that block acceptance, in report order. */
    public static List<String> blockingIds(List<Finding> findings) {
        List<String> ids = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.blocking() && !ids.contains(finding.id())) ids.add(finding.id());
        }
        return ids;
    }

    /**
     * One stage's reading, compared against what the same stage said last time.
     *
     * `closed`, `persisted` and `new_findings` are the history the plan asks for, and they are
     * what a person reads to answer "did the repair achieve anything". `candidate_fingerprint`
     * sits beside them because it answers the same question without trusting any of the text.
     *
     * @param previous the same stage's previous round, or null for its first
     */
    public static Map<String, Object> round(String stage, long attempt, String candidateFingerprint,
                                            List<Finding> now, Map<String, Object> previous) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("attempt", attempt);
        row.put("candidate_fingerprint", candidateFingerprint);
        row.put("findings", now.stream().map(Finding::toMap).toList());
        List<String> blocking = blockingIds(now);
        row.put("blocking_ids", blocking);
        if (previous != null) {
            Set<String> before = new LinkedHashSet<>(idsOf(previous.get("blocking_ids")));
            Set<String> after = new LinkedHashSet<>(blocking);
            row.put("closed", before.stream().filter(id -> !after.contains(id)).toList());
            row.put("persisted", before.stream().filter(after::contains).toList());
            row.put("new_findings", after.stream().filter(id -> !before.contains(id)).toList());
            Object priorFingerprint = previous.get("candidate_fingerprint");
            row.put("candidate_moved", priorFingerprint != null && candidateFingerprint != null
                    && !priorFingerprint.equals(candidateFingerprint));
        }
        return row;
    }

    private static List<String> idsOf(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof List<?> rows) {
            for (Object row : rows) ids.add(String.valueOf(row));
        }
        return ids;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
