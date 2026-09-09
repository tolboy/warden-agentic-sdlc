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

        /** A P1 an implementer can be expected to close by editing the candidate. */
        public boolean repairable() {
            return blocking() && "product_defect".equals(category);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("id_source", idSource);
            value.put("category", category);
            value.put("category_source", categorySource);
            value.put("severity", severity);
            value.put("path", path);
            value.put("message", message);
            for (String field : List.of("scenario", "expected", "actual", "scope_relation"))
                value.put(field, text(raw.get(field)));
            value.put("evidence_refs", refs(raw.get("evidence_refs")));
            value.put("supersedes", refs(raw.get("supersedes")));
            // A serialized empty list is indistinguishable from a field the vendor never
            // named. Remember which, so restore does not treat a legacy artifact as a
            // new-style one and hard-stop a run that had already completed.
            value.put("named_evidence_protocol", namedEvidenceProtocol(raw));
            // Lifecycle is controller-owned; a vendor cannot close a reported blocker.
            value.put("status", "open");
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

    /**
     * Replay a round Warden itself recorded. Provenance is taken from the stored row
     * rather than re-derived: a derived id still has an id string, and feeding it through
     * {@link #of} would stamp it as vendor-named.
     */
    @SuppressWarnings("unchecked")
    public static List<Finding> recorded(Object findings) {
        List<Finding> result = new ArrayList<>();
        if (!(findings instanceof List<?> rows)) return result;
        for (Object row : rows) {
            if (row instanceof Map<?, ?> entry) result.add(fromRecorded((Map<String, Object>) entry));
        }
        return result;
    }

    private static Finding fromRecorded(Map<String, Object> entry) {
        Finding parsed = one(entry);
        String idSource = text(entry.get("id_source"));
        if (!"vendor".equals(idSource) && !"derived".equals(idSource)) idSource = parsed.idSource();
        String categorySource = text(entry.get("category_source"));
        if (!Set.of("vendor", "defaulted_absent", "defaulted_unrecognised").contains(categorySource)) {
            categorySource = parsed.categorySource();
        }
        String category = text(entry.get("category"));
        if (!CATEGORIES.contains(category)) category = parsed.category();
        return new Finding(parsed.id(), idSource, category, categorySource,
                parsed.severity(), parsed.path(), parsed.message(), entry);
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
     * Round deltas stay per stage: one reviewer omitting another's finding is not a closure
     * and not a retraction. The registry itself is the run's lifecycle, so a later stage's
     * first reading still sees what was already closed and cannot raise it as a new
     * requirement on the original evidence.
     *
     * @param previous the same stage's previous round, or null for its first
     */
    public static Map<String, Object> round(String stage, long attempt, String candidateFingerprint,
                                            List<Finding> now, Map<String, Object> previous) {
        return round(stage, attempt, candidateFingerprint, now, previous, null);
    }

    /**
     * @param inherited another stage's latest round, folded into the registry seed underneath
     *                  {@code previous}. Its blocking ids do not become this stage's
     *                  closed/persisted/new_findings.
     */
    public static Map<String, Object> round(String stage, long attempt, String candidateFingerprint,
                                            List<Finding> now, Map<String, Object> previous,
                                            Map<String, Object> inherited) {
        List<Map<String, Object>> history = new ArrayList<>();
        if (inherited != null) history.add(inherited);
        if (previous != null) history.add(previous);
        return roundOf(stage, attempt, candidateFingerprint, now, history);
    }

    /**
     * The same reading, told the run's whole history instead of two rounds of it.
     *
     * Round deltas are still this stage against itself: {@code previous} is the last round
     * this stage recorded, wherever it sits in that history. The registry is the run's and
     * not the stage's, and that is the difference this entry point exists for. A stage that
     * seeded only from its own last round could not see what another stage had closed in
     * between, so a closed requirement re-entered as a new one on its original evidence,
     * which is the one thing the registry is there to refuse.
     *
     * @param history every round recorded so far, oldest first, this one not among them
     */
    public static Map<String, Object> roundOf(String stage, long attempt,
                                              String candidateFingerprint, List<Finding> now,
                                              List<Map<String, Object>> history) {
        Map<String, Object> previous = null;
        if (history != null) for (Map<String, Object> earlier : history) {
            if (earlier != null && stage.equals(earlier.get("stage"))) previous = earlier;
        }
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
            // A previous P1 that this round no longer treats as blocking. Relabelling it below
            // the line, or dropping it from the report, are the two ways a reviewer can make a
            // run go green without anything about the work changing. The loop refuses both
            // when the candidate did not move, even if another blocker is still open in this
            // round; the distinction is recorded so a person can see which path was taken.
            Map<String, String> was = severityById(previous.get("findings"));
            Map<String, String> is = severityById(row.get("findings"));
            List<String> downgraded = new ArrayList<>();
            List<String> retracted = new ArrayList<>();
            for (String id : before) {
                if (after.contains(id)) continue;
                String nowSeverity = is.get(id);
                if (nowSeverity == null) retracted.add(id);
                else if ("P1".equals(was.get(id))) downgraded.add(id);
            }
            row.put("severity_downgraded", downgraded);
            row.put("blocking_retracted", retracted);
        }
        accumulate(row, now, stage, history);
        return row;
    }

    /**
     * The run's lifecycle so far: one record per finding id, in the state the most recent
     * round left it, with the stage that first filed it preserved.
     *
     * Rounds are per stage and this deliberately is not. It is what a reviewer is handed and
     * what the evidence rules are checked against, so it has to be current for the whole run
     * rather than for one stage's own chain of readings.
     */
    public static List<Map<String, Object>> registryOf(List<Map<String, Object>> history) {
        return List.copyOf(merged(history).values());
    }

    /** {@link #registryOf} keyed by id, so the caller can still write to the records. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> merged(List<Map<String, Object>> history) {
        Map<String, Map<String, Object>> known = new LinkedHashMap<>();
        if (history == null) return known;
        for (Map<String, Object> earlier : history) {
            if (earlier == null) continue;
            Object records = earlier.getOrDefault("finding_registry", earlier.get("findings"));
            if (!(records instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)) continue;
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                // The stage that first filed it owns it, and ownership is what decides who may
                // close it. A record carried forward by another stage keeps that name.
                String owner = null;
                Map<String, Object> before = known.get(id);
                if (before != null && before.get("recorded_at_stage") instanceof String filed
                        && !filed.isBlank()) owner = filed;
                if (owner == null && copy.get("recorded_at_stage") instanceof String carried
                        && !carried.isBlank()) owner = carried;
                if (owner == null && earlier.get("stage") instanceof String from
                        && !from.isBlank()) owner = from;
                if (owner != null) copy.put("recorded_at_stage", owner);
                // Latest evidence_refs is the last report; seen_evidence_refs is every
                // receipt this id has ever carried. Replacing the record must not forget
                // an earlier one, or the next reopen would treat it as new.
                copy.put("seen_evidence_refs", unionEvidence(before, copy));
                known.put(id, copy);
            }
        }
        return known;
    }

    /** Durable lifecycle, including records omitted by later reviewers and earlier stages. */
    private static void accumulate(Map<String, Object> row, List<Finding> now,
                                   String stage, List<Map<String, Object>> history) {
        Map<String, Map<String, Object>> known = merged(history);
        Set<String> previouslyRecordedEvidence = new LinkedHashSet<>(evidenceSeenIn(history));
        for (var rec : known.values()) previouslyRecordedEvidence.addAll(recordedEvidence(rec));
        Set<String> present = new LinkedHashSet<>();
        for (Finding finding : now) present.add(finding.id());
        // Omission closes a finding, but never erases its last evidence and scenario, and only
        // the stage that filed it may close it that way. Silence from a second reviewer is not
        // a closure: while it was, an objection nobody had addressed was reported as closed to
        // the human report and to the next repair package, and the closure it invented armed
        // the stale-evidence rule against that reviewer's own first blocker.
        for (var entry : known.entrySet()) {
            if (present.contains(entry.getKey())) continue;
            if (entry.getValue().get("recorded_at_stage") instanceof String filed
                    && !filed.isBlank() && !filed.equals(stage)) continue;
            entry.getValue().put("status", "closed");
        }
        boolean hasClosed = known.values().stream().anyMatch(f -> "closed".equals(f.get("status")));
        List<String> violations = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Finding finding : now) {
            Map<String, Object> value = finding.toMap();
            Map<String, Object> old = known.get(finding.id());
            List<String> links = refs(value.get("supersedes"));
            List<String> evidence = refs(value.get("evidence_refs"));
            if (!seen.add(finding.id())) violations.add(finding.id() + ": duplicate id");
            boolean reopening = old != null && "closed".equals(old.get("status"));
            Set<String> priorEvidence = new LinkedHashSet<>();
            if (old != null) priorEvidence.addAll(recordedEvidence(old));
            priorEvidence.addAll(evidenceSeenFor(history, finding.id()));
            for (String link : links) {
                Map<String, Object> target = known.get(link);
                if (target == null || link.equals(finding.id())) {
                    violations.add(finding.id() + ": unknown or self supersedes " + link);
                } else {
                    reopening |= "closed".equals(target.get("status"));
                    priorEvidence.addAll(recordedEvidence(target));
                    priorEvidence.addAll(evidenceSeenFor(history, link));
                }
            }
            // Unknown wording cannot be matched semantically. A new-style artifact that
            // names evidence_refs or supersedes must produce new evidence for a fresh
            // blocker after a closure, so changing the id cannot bypass the invariant.
            // "New" is relative to every reference already in the registry, not only
            // the same id: an unknown id whose only refs were recorded on a closed
            // finding is the same requirement under a new name. A later report that
            // replaces evidence_refs does not make an earlier receipt new: the guard
            // consults every reference this id, and the registry, has ever carried.
            // A legacy artifact that never spoke those fields cannot be checked that
            // way: record it, do not stop the run. Reopening a known id, and an
            // explicit supersedes link, still require evidence regardless of vintage.
            boolean spokeEvidence = namedEvidenceProtocol(finding.raw());
            boolean newAfterClosure = finding.blocking() && old == null && hasClosed;
            if (newAfterClosure) priorEvidence.addAll(previouslyRecordedEvidence);
            boolean needsEvidence = reopening || !links.isEmpty()
                    || (newAfterClosure && spokeEvidence);
            if (needsEvidence && evidence.stream().noneMatch(ref -> !priorEvidence.contains(ref)))
                violations.add(finding.id() + ": reopening/new requirement needs new evidence_refs");
            if (newAfterClosure && !spokeEvidence)
                unverified.add(finding.id() + ": new blocker after a closure; artifact named no "
                        + "evidence_refs so the evidence invariant could not be checked");
            if (reopening) value.put("status", "reopened");
            Object priorStage = old != null ? old.get("recorded_at_stage") : null;
            value.put("recorded_at_stage", priorStage instanceof String s && !s.isBlank() ? s : stage);
            value.put("seen_evidence_refs", unionEvidence(old, value));
            known.put(finding.id(), value);
        }
        row.put("finding_registry", List.copyOf(known.values()));
        row.put("closed_ids", known.entrySet().stream()
                .filter(e -> "closed".equals(e.getValue().get("status")))
                .map(Map.Entry::getKey).toList());
        row.put("protocol_violations", violations);
        row.put("unverified_invariants", unverified);
    }

    /**
     * Whether this map is a new-style artifact that spoke the evidence protocol.
     * Serialized rounds carry {@code named_evidence_protocol} because an empty
     * {@code evidence_refs} list is otherwise indistinguishable from a field the
     * vendor never named.
     */
    private static boolean namedEvidenceProtocol(Map<String, Object> raw) {
        if (raw.containsKey("named_evidence_protocol"))
            return Boolean.TRUE.equals(raw.get("named_evidence_protocol"));
        return raw.containsKey("evidence_refs") || raw.containsKey("supersedes");
    }

    private static List<String> refs(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) for (Object item : items)
            if (item instanceof String text && !text.isBlank()) result.add(text.trim());
        return result;
    }

    /** Historical then latest receipts on one registry record, first-seen order. */
    private static List<String> recordedEvidence(Map<String, Object> record) {
        if (record == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        seen.addAll(refs(record.get("seen_evidence_refs")));
        seen.addAll(refs(record.get("evidence_refs")));
        return List.copyOf(seen);
    }

    private static List<String> unionEvidence(Map<String, Object> first, Map<String, Object> second) {
        Set<String> seen = new LinkedHashSet<>();
        seen.addAll(recordedEvidence(first));
        seen.addAll(recordedEvidence(second));
        return List.copyOf(seen);
    }

    /** Every evidence reference named in any round of the history, any id. */
    private static Set<String> evidenceSeenIn(List<Map<String, Object>> history) {
        return evidenceSeenFor(history, null);
    }

    /**
     * Receipts already used for {@code id} in earlier rounds. {@code id == null}
     * collects every reference, which is what a fresh P1 after a closure is
     * checked against.
     */
    private static Set<String> evidenceSeenFor(List<Map<String, Object>> history, String id) {
        Set<String> seen = new LinkedHashSet<>();
        if (history == null) return seen;
        for (Map<String, Object> earlier : history) {
            if (earlier == null) continue;
            for (String key : List.of("finding_registry", "findings")) {
                Object records = earlier.get(key);
                if (!(records instanceof List<?> entries)) continue;
                for (Object entry : entries) {
                    if (!(entry instanceof Map<?, ?> map)) continue;
                    if (id != null && !id.equals(map.get("id"))) continue;
                    seen.addAll(refs(map.get("evidence_refs")));
                    seen.addAll(refs(map.get("seen_evidence_refs")));
                }
            }
        }
        return seen;
    }

    /** Severity per finding id, from a recorded round's own finding list. */
    private static Map<String, String> severityById(Object findings) {
        Map<String, String> severities = new LinkedHashMap<>();
        if (findings instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> entry && entry.get("id") != null) {
                    severities.put(String.valueOf(entry.get("id")),
                            String.valueOf(entry.get("severity")));
                }
            }
        }
        return severities;
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
