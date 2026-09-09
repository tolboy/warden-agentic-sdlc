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
}
