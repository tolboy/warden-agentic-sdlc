package dev.warden;

import dev.warden.ledger.Findings;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.util.List;
import java.util.Map;

public final class FindingsTest implements Suite {
    public String name() { return "findings"; }
    private static Map<String, Object> finding(String id, List<String> evidence, List<String> links) {
        return Map.of("id", id, "severity", "P1", "path", "src/a", "message", id,
                "expected", "good", "actual", "bad", "scenario", "reproduce " + id,
                "evidence_refs", evidence, "supersedes", links);
    }
    private static Map<String, Object> round(Map<String, Object> before, Map<String, Object>... rows) {
        return Findings.round("review", 1, "candidate", Findings.of(Map.of("findings", List.of(rows))), before);
    }
    public void run(Check c) {
        var a = finding("A", List.of("receipt-0"), List.of());
        var b = finding("B", List.of(), List.of());
        var d = finding("C", List.of(), List.of());
        var r0 = round(null, a, b, d);
        var r1 = round(r0, b, d);
        var r2 = round(r1, d);
        c.eq("closures accumulate through three rounds", List.of("A", "B"), r2.get("closed_ids"));
        c.contains("closed scenario retained", r2.get("finding_registry").toString(), "reproduce A");
        c.eq("ordinary closures have no protocol failure", List.of(), r2.get("protocol_violations"));
        c.eq("and nothing unverifiable on a new-style round", List.of(), r2.get("unverified_invariants"));
        c.that("same id cannot reopen with old evidence", !round(r2, a, d).get("protocol_violations").equals(List.of()));
        c.that("renamed closure needs evidence", !round(r2, finding("renamed", List.of(), List.of("A")), d).get("protocol_violations").equals(List.of()));
        c.that("omitting supersedes cannot evade evidence", !round(r2, finding("new-words", List.of(), List.of()), d).get("protocol_violations").equals(List.of()));
        c.that("reused evidence under a new id is still stale",
                !round(r2, finding("new-words", List.of("receipt-0"), List.of()), d)
                        .get("protocol_violations").equals(List.of()));
        c.eq("new evidence under a new id after closure is allowed", List.of(),
                round(r2, finding("new-words", List.of("receipt-1"), List.of()), d)
                        .get("protocol_violations"));
        var reopened = round(r2, finding("A", List.of("receipt-1"), List.of()), d);
        c.eq("new evidence permits reopen", List.of(), reopened.get("protocol_violations"));
        c.eq("reopened id leaves closed set", List.of("B"), reopened.get("closed_ids"));
        c.eq("linked replacement with new evidence allowed", List.of(), round(r2, finding("new", List.of("receipt-1"), List.of("A")), d).get("protocol_violations"));
        c.that("unknown link is protocol failure", !round(r2, finding("new", List.of("receipt-1"), List.of("missing"))).get("protocol_violations").equals(List.of()));
        c.eq("legacy artifacts still accepted initially", List.of(), round(null, Map.of("severity", "P1", "message", "legacy")).get("protocol_violations"));
        c.that("duplicate identity is rejected", !round(null, a, a).get("protocol_violations").equals(List.of()));

        var omitted = round(r0);
        c.eq("omitting a P1 on the same tree is a retraction", List.of("A", "B", "C"),
                omitted.get("blocking_retracted"));
        c.eq("and not a relabel", List.of(), omitted.get("severity_downgraded"));
        var p2 = Map.of("id", "A", "severity", "P2", "path", "src/a", "message", "A",
                "expected", "good", "actual", "bad", "scenario", "reproduce A",
                "evidence_refs", List.of("receipt-0"), "supersedes", List.of());
        var relabelled = round(r0, p2, b, d);
        c.eq("relabelling a P1 on the same tree is a downgrade", List.of("A"),
                relabelled.get("severity_downgraded"));
        c.eq("and not a retraction", List.of(), relabelled.get("blocking_retracted"));
        var noise = finding("noise", List.of("receipt-n"), List.of());
        var droppedBehind = round(r0, noise);
        c.eq("retraction is recorded even when another P1 remains", List.of("A", "B", "C"),
                droppedBehind.get("blocking_retracted"));
        c.eq("and the throwaway is the round's only blocker", List.of("noise"),
                droppedBehind.get("blocking_ids"));
        var relabelledBehind = round(r0, p2, noise);
        c.eq("relabel is recorded even when another P1 remains", List.of("A"),
                relabelledBehind.get("severity_downgraded"));
        var moved = Findings.round("review", 1, "other-tree",
                Findings.of(Map.of("findings", List.of())), r0);
        c.eq("omission on a moved tree is still recorded as a retraction",
                List.of("A", "B", "C"), moved.get("blocking_retracted"));
        c.eq("and the tree is marked as moved", Boolean.TRUE, moved.get("candidate_moved"));

        var legacyFirst = round(null,
                Map.of("id", "X", "severity", "P1", "message", "one"),
                Map.of("id", "Y", "severity", "P1", "message", "two"));
        var legacyNext = round(legacyFirst,
                Map.of("id", "Y", "severity", "P1", "message", "two"),
                Map.of("id", "Z", "severity", "P1", "message", "three"));
        c.eq("legacy new P1 after a closure is not a hard protocol failure",
                List.of(), legacyNext.get("protocol_violations"));
        c.that("but records that the evidence invariant could not be checked",
                !legacyNext.get("unverified_invariants").equals(List.of()));
        var restoredLegacy = Findings.round("review", 1, "candidate",
                Findings.recorded(legacyNext.get("findings")), legacyFirst);
        c.eq("restoring that round does not invent a protocol failure",
                List.of(), restoredLegacy.get("protocol_violations"));

        var withSuggestion = Findings.of(Map.of("findings", List.of(Map.of(
                "id", "gap", "severity", "P1", "path", "src/a", "message", "weak acceptance",
                "category", "contract_gap", "suggestion", "require an exact match")))).get(0);
        c.eq("a vendor suggestion is carried on the recorded finding",
                "require an exact match", withSuggestion.toMap().get("suggestion"));
        c.that("and is not used as the identity", !"require an exact match".equals(withSuggestion.id()));
        var withoutSuggestion = Findings.of(Map.of("findings", List.of(Map.of(
                "id", "plain", "severity", "P1", "path", "src/a", "message", "broken")))).get(0);
        c.that("a finding with no suggestion omits the key rather than writing null",
                !withoutSuggestion.toMap().containsKey("suggestion"));
        manualVerificationDowngrade(c);

        var unnamed = Findings.of(Map.of("findings", List.of(Map.of(
                "severity", "P1", "path", "src/a", "message", "legacy")))).get(0);
        var restored = Findings.recorded(List.of(unnamed.toMap())).get(0);
        c.eq("recorded provenance keeps a derived id", "derived", restored.idSource());
        c.eq("recorded provenance keeps a defaulted category", "defaulted_absent",
                restored.categorySource());
        c.eq("and the id itself is the one that was stored", unnamed.id(), restored.id());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstRegistry = (List<Map<String, Object>>) r0.get("finding_registry");
        c.eq("first filing records the stage", "review",
                firstRegistry.stream().filter(m -> "A".equals(m.get("id"))).findFirst()
                        .orElseThrow().get("recorded_at_stage"));

        var inheritedPass = Findings.round("review-second", 0, "candidate",
                Findings.of(Map.of("findings", List.of())), null, r2);
        c.eq("a later stage's first reading has no retraction list",
                null, inheritedPass.get("blocking_retracted"));
        c.eq("and does not claim the earlier closures as its own", null, inheritedPass.get("closed"));
        c.eq("ordinary inherited silence is not a protocol failure",
                List.of(), inheritedPass.get("protocol_violations"));
        c.contains("but it still holds the closed record",
                inheritedPass.get("finding_registry").toString(), "reproduce A");

        var reraise = Findings.round("review-second", 0, "candidate",
                Findings.of(Map.of("findings", List.of(a))), null, r2);
        c.that("second stage cannot reopen A with original evidence",
                !reraise.get("protocol_violations").equals(List.of()));
        var honestReopen = Findings.round("review-second", 0, "candidate",
                Findings.of(Map.of("findings", List.of(finding("A", List.of("receipt-1"), List.of())))),
                null, r2);
        c.eq("second stage may reopen A with new evidence",
                List.of(), honestReopen.get("protocol_violations"));

        var renamedAcross = Findings.round("review-second", 0, "candidate",
                Findings.of(Map.of("findings", List.of(
                        finding("new-words", List.of("receipt-0"), List.of()), d))),
                null, r2);
        c.that("second stage cannot rename a closed finding onto its original evidence",
                !renamedAcross.get("protocol_violations").equals(List.of()));

        var fresh = Findings.round("review-second", 0, "other-tree",
                Findings.of(Map.of("findings", List.of(finding("D", List.of("receipt-1"), List.of())))),
                null, r2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> laterRegistry = (List<Map<String, Object>>) fresh.get("finding_registry");
        c.eq("inherited closed A keeps review as the filing stage", "review",
                laterRegistry.stream().filter(m -> "A".equals(m.get("id"))).findFirst()
                        .orElseThrow().get("recorded_at_stage"));
        c.eq("new D is filed at review-second", "review-second",
                laterRegistry.stream().filter(m -> "D".equals(m.get("id"))).findFirst()
                        .orElseThrow().get("recorded_at_stage"));
        c.eq("fresh evidence after an inherited closure is allowed",
                List.of(), fresh.get("protocol_violations"));

        crossStageLifecycle(c);
        historicalEvidenceAfterReclosure(c);
        conflictingCrossStageIdentity(c);
    }

    /**
     * Two reviewers share an id. The later one must not replace the owner's category
     * or severity: that replacement is how a product defect became a contract gap and
     * a waiver closed it.
     */
    @SuppressWarnings("unchecked")
    private void conflictingCrossStageIdentity(Check c) {
        var defect = Map.of("id", "A1", "severity", "P1", "category", "product_defect",
                "path", "src/a", "message", "the greeting still does not toggle",
                "evidence_refs", List.of("receipt-defect"), "supersedes", List.of());
        var gap = Map.of("id", "A1", "severity", "P1", "category", "contract_gap",
                "path", "src/a", "message", "no scenario presses Escape",
                "evidence_refs", List.of("receipt-gap"), "supersedes", List.of());
        var passed = Findings.roundOf("review", 0, "tree-1",
                Findings.of(Map.of("findings", List.of())), List.of());
        var filed = Findings.roundOf("review-second", 0, "tree-1",
                Findings.of(Map.of("findings", List.of(defect))), List.of(passed));
        var recheck = Findings.roundOf("review", 1, "tree-2",
                Findings.of(Map.of("findings", List.of(gap))), List.of(passed, filed));
        c.contains("a shared id with a different category is a protocol failure",
                String.valueOf(recheck.get("protocol_violations")),
                "A1: conflicting cross-stage id owned by review-second");
        List<Map<String, Object>> registry =
                (List<Map<String, Object>>) recheck.get("finding_registry");
        Map<String, Object> kept = registry.stream()
                .filter(row -> "A1".equals(row.get("id"))).findFirst().orElseThrow();
        c.eq("the owner's product defect is the record that stays",
                "product_defect", kept.get("category"));
        c.eq("and it stays open", "open", kept.get("status"));
        c.eq("filed by review-second", "review-second", kept.get("recorded_at_stage"));
        c.eq("so the open blocker is that defect", "product_defect",
                Findings.openBlockers(List.of(passed, filed, recheck)).get(0).get("category"));

        var repeated = Findings.roundOf("review", 1, "tree-2",
                Findings.of(Map.of("findings", List.of(defect))), List.of(passed, filed));
        c.eq("the same id with the same category and severity is not a conflict",
                List.of(), repeated.get("protocol_violations"));

        var softened = Map.of("id", "A1", "severity", "P2", "category", "product_defect",
                "path", "src/a", "message", "the greeting still does not toggle",
                "evidence_refs", List.of("receipt-soft"), "supersedes", List.of());
        var downgraded = Findings.roundOf("review", 1, "tree-2",
                Findings.of(Map.of("findings", List.of(softened))), List.of(passed, filed));
        c.contains("a cross-stage severity change is the same refusal",
                String.valueOf(downgraded.get("protocol_violations")),
                "conflicting cross-stage id");
        c.eq("and the P1 is what stays open", "P1",
                Findings.openBlockers(List.of(passed, filed, downgraded)).get(0).get("severity"));
    }

    /**
     * The lifecycle is the run's; the round deltas are each stage's own.
     *
     * Two defects lived in the gap between those two sentences. A stage that had read before
     * seeded its registry from its own last round alone, so a finding another stage had closed
     * in between was invisible to it and could be raised again, as new, on the evidence it was
     * closed with. And any stage's silence closed any record, so one reviewer's quiet turned
     * another reviewer's open objection into a closure nobody had earned.
     */
    private void crossStageLifecycle(Check c) {
        var open = finding("O", List.of("receipt-o"), List.of());
        var reviewFiles = Findings.round("review", 0, "tree-1",
                Findings.of(Map.of("findings", List.of(open))), null);
        var secondFiles = Findings.roundOf("review-second", 0, "tree-1",
                Findings.of(Map.of("findings", List.of(finding("B", List.of("receipt-b"), List.of())))),
                List.of(reviewFiles));
        var secondCloses = Findings.roundOf("review-second", 1, "tree-2",
                Findings.of(Map.of("findings", List.of())), List.of(reviewFiles, secondFiles));
        c.eq("a stage closes only what it filed", List.of("B"), secondCloses.get("closed_ids"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterSilence =
                (List<Map<String, Object>>) secondCloses.get("finding_registry");
        c.eq("another stage's open finding survives that silence", "open",
                afterSilence.stream().filter(m -> "O".equals(m.get("id"))).findFirst()
                        .orElseThrow().get("status"));
        c.eq("and no closure was invented to arm the evidence rule",
                List.of(), secondCloses.get("unverified_invariants"));

        var history = List.of(reviewFiles, secondFiles, secondCloses);
        var stale = Findings.roundOf("review", 1, "tree-3",
                Findings.of(Map.of("findings", List.of(finding("B", List.of("receipt-b"), List.of())))),
                history);
        c.that("a rechecked stage cannot raise what another stage closed, on its old evidence",
                !stale.get("protocol_violations").equals(List.of()));
        var honest = Findings.roundOf("review", 1, "tree-3",
                Findings.of(Map.of("findings", List.of(finding("B", List.of("receipt-new"), List.of())))),
                history);
        c.eq("but may raise it again on evidence nobody has used", List.of(),
                honest.get("protocol_violations"));
        c.eq("and its own omitted finding still closes", List.of("O"), stale.get("closed_ids"));
    }

    /**
     * A receipt used for an earlier incarnation of A is not new after a later
     * report replaced evidence_refs. C stays open so the workflow can close A
     * twice. roundOf is fed every prior round, as TaskLoop does; the snapshot
     * chain is the same facts with only the previous registry in hand.
     */
    @SuppressWarnings("unchecked")
    private void historicalEvidenceAfterReclosure(Check c) {
        var a0 = finding("A", List.of("receipt-0"), List.of());
        var a1 = finding("A", List.of("receipt-1"), List.of());
        var blocker = finding("C", List.of("receipt-c"), List.of());
        var r0 = Findings.roundOf("review", 0, "tree-0",
                Findings.of(Map.of("findings", List.of(a0, blocker))), List.of());
        var r1 = Findings.roundOf("review", 1, "tree-1",
                Findings.of(Map.of("findings", List.of(blocker))), List.of(r0));
        var r2 = Findings.roundOf("review", 2, "tree-2",
                Findings.of(Map.of("findings", List.of(a1, blocker))), List.of(r0, r1));
        c.eq("first reopen of A with a fresh receipt is allowed", List.of(),
                r2.get("protocol_violations"));
        var r3 = Findings.roundOf("review", 3, "tree-3",
                Findings.of(Map.of("findings", List.of(blocker))), List.of(r0, r1, r2));
        c.eq("second closure of A is recorded", List.of("A"), r3.get("closed"));
        List<Map<String, Object>> closedReg =
                (List<Map<String, Object>>) r3.get("finding_registry");
        Map<String, Object> closedA = closedReg.stream()
                .filter(m -> "A".equals(m.get("id"))).findFirst().orElseThrow();
        c.eq("last evidence on A is the second incarnation",
                List.of("receipt-1"), closedA.get("evidence_refs"));
        c.eq("and the registry still lists the first receipt",
                List.of("receipt-0", "receipt-1"), closedA.get("seen_evidence_refs"));

        var reused = Findings.roundOf("review", 4, "tree-4",
                Findings.of(Map.of("findings", List.of(a0, blocker))),
                List.of(r0, r1, r2, r3));
        c.that("reusing evidence from an earlier incarnation is a protocol failure",
                !reused.get("protocol_violations").equals(List.of()));
        var honest = Findings.roundOf("review", 4, "tree-4",
                Findings.of(Map.of("findings", List.of(
                        finding("A", List.of("receipt-2"), List.of()), blocker))),
                List.of(r0, r1, r2, r3));
        c.eq("a reference nobody has used still reopens A", List.of(),
                honest.get("protocol_violations"));
        var renamed = Findings.roundOf("review", 4, "tree-4",
                Findings.of(Map.of("findings", List.of(
                        finding("D", List.of("receipt-0"), List.of()), blocker))),
                List.of(r0, r1, r2, r3));
        c.that("a new id cannot launder a historical reference either",
                !renamed.get("protocol_violations").equals(List.of()));
        var viaLink = Findings.roundOf("review", 4, "tree-4",
                Findings.of(Map.of("findings", List.of(
                        finding("E", List.of("receipt-0"), List.of("A")), blocker))),
                List.of(r0, r1, r2, r3));
        c.that("superseding A cannot reuse a receipt from an earlier incarnation",
                !viaLink.get("protocol_violations").equals(List.of()));

        var s0 = round(null, a0, blocker);
        var s1 = round(s0, blocker);
        var s2 = round(s1, a1, blocker);
        var s3 = round(s2, blocker);
        List<Map<String, Object>> snapReg =
                (List<Map<String, Object>>) s3.get("finding_registry");
        Map<String, Object> snapA = snapReg.stream()
                .filter(m -> "A".equals(m.get("id"))).findFirst().orElseThrow();
        c.eq("a previous-only snapshot still lists both receipts",
                List.of("receipt-0", "receipt-1"), snapA.get("seen_evidence_refs"));
        c.eq("and its last evidence_refs is still the second incarnation",
                List.of("receipt-1"), snapA.get("evidence_refs"));
        c.that("that snapshot still refuses the first receipt as new",
                !round(s3, a0, blocker).get("protocol_violations").equals(List.of()));
    }

    /**
     * A contract gap the task already named under {@code manual_verification} is recorded
     * and does not block. A product defect that happens to contain the same words stays a P1.
     */
    private void manualVerificationDowngrade(Check c) {
        Map<String, Object> gap = Map.of(
                "id", "keyboard-chord", "severity", "P1", "category", "contract_gap",
                "path", "src/keys.js", "message", "no scenario presses Ctrl+K",
                "expected", "a harness scenario covers the chord",
                "actual", "the grammar has no chord", "scenario", "read the handler",
                "suggestion", "verify the keyboard chord by hand", "confidence", "confirmed");
        Findings.Finding filed = Findings.of(Map.of("findings", List.of(gap))).get(0);
        List<Findings.Finding> lowered = Findings.acknowledging(List.of(filed),
                List.of("  ", "Keyboard Chord"));
        c.eq("a matching P1 contract_gap is lowered to P2", "P2", lowered.get(0).severity());
        c.that("and no longer blocks", !lowered.get(0).blocking());
        Map<String, Object> recorded = lowered.get(0).toMap();
        c.eq("the vendor severity is kept beside it", "P1", recorded.get("severity_as_filed"));
        c.eq("the matched item is the one declared, not a re-cased copy",
                "Keyboard Chord", recorded.get("manual_verification"));
        c.eq("and the downgrade is marked advisory", true, recorded.get("advisory"));
        c.eq("the vendor's own finding is left at P1", "P1", filed.severity());
        c.that("and its map is not given the advisory flag", !filed.raw().containsKey("advisory"));

        Findings.Finding defect = Findings.of(Map.of("findings", List.of(Map.of(
                "id", "still-broken", "severity", "P1", "category", "product_defect",
                "path", "src/keys.js", "message", "the keyboard chord handler drops the event",
                "confidence", "confirmed")))).get(0);
        Findings.Finding stayed = Findings.acknowledging(List.of(defect),
                List.of("keyboard chord")).get(0);
        c.eq("a product defect is not lowered even when its text contains the item",
                "P1", stayed.severity());
        c.that("and carries no advisory flag", !stayed.toMap().containsKey("advisory"));

        Findings.Finding elsewhere = Findings.of(Map.of("findings", List.of(Map.of(
                "id", "hover", "severity", "P1", "category", "contract_gap",
                "path", "src/menu.js", "message", "hover is untested",
                "suggestion", "a person should check the hover",
                "confidence", "confirmed")))).get(0);
        Findings.Finding fromSuggestion = Findings.acknowledging(List.of(elsewhere),
                List.of("check the hover")).get(0);
        c.eq("a match in the suggestion is enough", "P2", fromSuggestion.severity());

        Findings.Finding already = Findings.of(Map.of("findings", List.of(Map.of(
                "id", "note", "severity", "P2", "category", "contract_gap",
                "path", "src/keys.js", "message", "keyboard chord needs a person",
                "confidence", "confirmed")))).get(0);
        Findings.Finding untouched = Findings.acknowledging(List.of(already),
                List.of("keyboard chord")).get(0);
        c.eq("a P2 the vendor already filed is not rewritten", "P2", untouched.severity());
        c.that("and is not stamped as a downgrade", !untouched.toMap().containsKey("severity_as_filed"));

        Findings.Finding unrelated = Findings.acknowledging(List.of(filed), List.of("drag")).get(0);
        c.eq("an item that does not occur leaves the P1", "P1", unrelated.severity());
        c.eq("an empty list changes nothing", filed, Findings.acknowledging(List.of(filed), List.of()).get(0));

        var first = Findings.round("review", 1, "tree", lowered, null);
        var second = Findings.round("review", 2, "tree",
                Findings.acknowledging(List.of(filed), List.of("keyboard chord")), first);
        c.eq("the first recorded round was never blocking", List.of(), first.get("blocking_ids"));
        c.eq("so the same downgrade on the next reading is not a relabel",
                List.of(), second.get("severity_downgraded"));
    }
}
