# P1 closure follow-up - 2026-09-08

## Why the loop rejected readiness

Audit run `do-p2-readiness-mtshaetv` in
`~/orca/workspaces/warden/w-p2-readiness` judged a documentation-only candidate.
Astra's initial review (`--review-second-0/artifacts/reviewer.json`) rejected the report's
YES because it substituted planner dependencies for mandatory P1 acceptance (section 6.7).
The fix changed the report to NO; both final reviews accepted that corrected conclusion.
The success gate accepted an accurate negative audit, not completed P1.
The final Astra review also retained P2 feedback about missing full-suite and live-smoke
acceptance mapping. The old candidate and its receipts have been preserved; its gate was later
resolved as an accepted audit result, not as approval of this code.

## Repair in the primary checkout

- Cumulative finding registry retains A and B through `[A,B,C] -> [B,C] -> [C]`. Round
  deltas stay per stage; the registry is inherited by a later judging stage's first
  reading, with `recorded_at_stage` preserved, so review-second cannot treat a closed
  A as an initial finding.
- Repair/recheck packets carry closed records, including scenario, expected/actual and evidence.
  A second independent reviewer receives that packet on first dispatch, together with
  the repair receipt. Their verdict stays independent; closed ids still need new evidence
  to re-enter.
- Optional evidence_refs, scope_relation and supersedes fields preserve legacy artifact input.
- Lifecycle status is derived by the controller, not trusted from reviewer output.
- Reopening requires new evidence. A newly named P1 after a closure requires evidence when
  the artifact spoke `evidence_refs` or `supersedes`; a reused reference already in the
  registry is not new, including a receipt used for an earlier incarnation of the same id
  after a later report replaced `evidence_refs`. The registry keeps `seen_evidence_refs`
  across incarnations; the last `evidence_refs` on a closed record stays the last report.
  A legacy artifact that named neither records `unverified_invariants` instead of stopping.
  Missing evidence on a new-style artifact, duplicate IDs or invalid supersedes stop as
  finding_protocol_failure.
- Explicit continuation retains history when task and acceptance match; historical context
  does not automatically make an old verdict reusable on a new candidate.
- Joined reports expose protocol failures, repair receipts and severity-laundering details.
  The laundering stop covers both a P1 relabelled below the line and a P1 dropped from the
  report on a candidate that did not move, even when another blocker is still open in that
  round, and even on a stage's first reading after `--continue`. Restoring the history is
  not enough if the controller never consults it. A throwaway P1 kept alive for one
  reading does not carry a retraction or a relabel to the human gate.
- The registry belongs to the run and not to a stage. Every dispatch is handed it as it
  stands, so a stage rechecked after another stage closed something still sees that closure
  and cannot raise it again on the evidence it was closed with. Ownership decides closure as
  well as provenance: omission closes a record only when the stage omitting it is the stage
  that filed it, because a second reviewer's silence is not a closure and an invented closure
  would arm the stale-evidence rule against a blocker nobody had filed twice.
- A re-reading is told a repair happened only when one did. On a resumed run the reading is
  restored and no repair sits behind it, and the package now says so and states whether the
  candidate moved, which is the fact that decides whether the reading may drop a finding.
- Human-gate safe_next_step is populated. Git Bash launcher converts classpath with cygpath.

This is a reference/presence invariant, not automated proof that evidence is valid.
Arbitrary semantic matching, observation TTL and planner remain separate. A P1 whose category
is not `product_defect` still blocks acceptance, but it does not buy another implementer call.
A pre-P1 reviewer artifact that never named `evidence_refs` is not a hard protocol failure
when it raises a fresh P1 after a closure; the round records that the invariant could not be
checked. Reopening a known id still needs new evidence.
The code change overwrote no ~/.warden configuration. Running the live smoke later adopted the
shipped reviewer prompt and schema there, with backups; see the smoke section below.

## Validation

Final full suite: **checks: 1491 passed, 0 failed**, process exit **0**. Reproduced
2026-09-09 from a clean `test.cmd` build (`javac --release 21`); this fingerprint's
figure, not the 1481/0 of the tree before the cross-stage recheck fix, and not the
1450/0 before the handoff that fix completes.
Production build: `build.cmd`, release 21, exit 0. The acceptance command is `test.cmd`,
which builds both trees with `javac --release 21` and runs `dev.warden.testing.TestMain`.
An earlier full run before the continuation addition passed 1378 checks with zero failures;
the previous fingerprint of this candidate was 1436/0. `git diff --check` passed.
FindingsTest covers cumulative closure, same-ID reopening, renamed reopening with
and without supersedes, a new id that reuses a closed finding's evidence, fresh/stale
references, unknown links, duplicate IDs, old artifacts, retraction/relabel lists with
another P1 still open, legacy new-after-closure as unverified rather than a hard stop,
provenance through `Findings.recorded`, a later stage inheriting the registry
without treating the earlier closures as its own retractions, and a receipt from an
earlier incarnation remaining stale after a later report replaced `evidence_refs`.
TaskLoopTest exercises three real scripted repair rounds, a fourth-review reinvention that
must stop, budget continuation with preserved history, the laundering stop with another
blocker still open, a retraction or a full drop on a resumed first reading, an honest
re-read of the same P1 that still proceeds to repair, a contract_gap that does not buy a
repair, derived provenance across `--continue`, a second independent reviewer receiving
the inherited registry and repair receipt, that reviewer re-raising a closed id on
the original evidence being refused as `finding_protocol_failure`, and a fifth reading
that re-files A on a receipt already used before an earlier closure of A, which must
stop as `finding_protocol_failure` before another repair. These use local
StubVendor processes, not paid providers. Git Bash `./bin/warden --help` exits successfully.

The contract gap found by the failing live smoke was reproduced in a fresh child worktree at
`~/orca/workspaces/p1-closure-smoke/codex-p1-closure-acceptance`. Its corrected
`tools/Check.java` ran 13 positive and negative cases, and a seven-mutation harness showed
the old gate accepted every regression while the corrected gate rejected all seven. The
fixture's Warden contract passed dry-run with no vendor dispatch and no paid calls.

## Live smoke, 2026-09-08

The plan's separately authorised live smoke has now been run, in a purpose-built fixture with
Orca making both worktrees. It is recorded in [`LIVE-CYCLE.md`](LIVE-CYCLE.md) section 10.

Run `p1-closure-live-1` reached the human gate: 3 calls, $0.8244, one unpriced Astra call.
Run `p1-closure-live-2` exercised the repair path: 6 calls, $2.9378, two fix rounds, three
readings by Opus, stopping `blocking_findings_remain` with one open blocker the implementer
correctly refused because the acceptance gate is outside the task's scope.

Proven live by that pair: the cumulative registry across three rounds of one stage, closed
findings retained with severity, scenario and evidence after the reviewer stopped naming them,
the closed list handed to the second repair, the registry handed to the recheck, vendor-supplied
`id`/`category`/`evidence_refs`/`scope_relation`, no protocol violation and no severity
laundering, a populated `safe_next_step` on both the ready and the stopped path, a complete
joined report, the Orca worktree card and gate, and the Git Bash launcher.

Not proven live, and still suite-covered only: the `finding_protocol_failure` stop, since no
reviewer tried to reopen a closure or raise a fresh blocker without evidence; the registry
carried across `--continue`; and the registry on a second independent reviewer, which was not
reached in the failing run.

Running the smoke required adopting the shipped reviewer prompt and schema into `~/.warden`,
because `warden setup` never overwrites an existing one and the operator's pair predated the
evidence and category contract. Backups: `prompts/reviewer.md.before-p1-closure` and
`schemas/reviewer.json.before-p1-closure`. Nothing else in `~/.warden` was touched.

## Approval and P2 boundary

The old `do-p2-readiness-mtshaetv` gate remains a record of the NO audit; accepting it would
not approve this patch or grant permission to start P2 on an unchanged worktree. This patch
is in the primary checkout, not that candidate.

After deterministic validation, this code is ready to enter a fresh P1 review. P2 must not be
started or marked as entered until that fresh P1 candidate is accepted. Include the patch in
the candidate's scope and obtain fresh required gates and reviews tied to its fingerprint.
Do not reuse the old audit's human gate for changed code.
The plan's separately authorised bounded live smoke has since been run and is recorded above:
a candidate-changing repair, two fix rounds and three readings of one stage, ending at the
human gate on one run and at a bounded stop on the other. It did not exercise Orca as a role
runner, so `worker_done`/settlement receipts still rest on the 2026-09-04 smoke in
[`SMOKE.md`](SMOKE.md); every role here ran through `runner: direct`.

## What a fresh review of this candidate found, 2026-09-09

The patch was frozen at `b47546c` in the Orca worktree
`~/orca/workspaces/warden/p1-candidate` and reviewed as task
`p1-closure-candidate` on a chain of `gates -> review -> review-second`, with no implement
stage because the candidate already existed. Two runs, `p1-candidate-20260909` and
`p1-candidate-20260909-cont`: twelve vendor calls, $24.12 priced plus two Astra calls the
vendor reports no price for, and a green gate on every round.

Opus 5 at effort max read the diff four times and GPT 6 Astra twice. **Seven P1 defects were
found in this code.** Six were repaired inside the loop and confirmed closed by the reviewer
that had filed each one. Every one of the seven was in the guard rather than in the registry:
`Findings` computed `blocking_retracted`, `severity_downgraded` and the closure set correctly
throughout, and the controller did not always ask for them.

Those runs are also the live evidence for two things section 10 above could claim only from
the suite. The registry crossed a run boundary on `--continue`, with Opus re-filing the same
finding id on a resumed first reading out of restored history rather than inventing a new one.
And a second independent reviewer read the cumulative registry and disagreed with the first
about it, which is how the cross-stage handoff was found at all.

The seventh, `p1-registry-recheck-history`, was closed by hand after the operator stopped the
paid loop, together with two non-blocking defects beside it,
`p1c-inherited-open-finding-closed-by-silence` and
`p1c-resume-recheck-package-claims-a-repair`. **Those last three fixes were written outside the
loop and no vendor has read them.** They are covered by the suite and both halves were
mutation-checked: seeding a rechecked stage from its own round alone, and letting any stage's
silence close any record, each make the new checks fail, and the second also breaks an existing
one by manufacturing the closure that arms the stale-evidence rule. A fresh run is still owed
on this fingerprint before a person accepts it.

Left open deliberately: `p1c-recheck-context-file-collides-across-stages`, a P3. Both reviewer
stages of a two-reviewer workflow write their recheck package under one file name in the run's
context directory, because the name is derived from the role rather than the stage. The
dispatched prompt is written and hashed per dispatch, so no evidence is lost; the context
directory alone is wrong.

A later reading of this fingerprint filed `p1c-historical-evidence-becomes-new-after-reclosure`.
`accumulate` stored `finding.toMap()` over the previous record, so a second incarnation of A
replaced `evidence_refs` and receipt-0 satisfied the new-evidence predicate after the second
closure. The registry now keeps `seen_evidence_refs` across incarnations, and the guard
consults historical rounds, not only the latest record. The last `evidence_refs` on a closed
finding remains the last report.

This candidate also carries edits to `docs/ADAPTIVE-WORKFLOW-PLAN.md` made during those runs.
The primary checkout's copy of that plan was rewritten separately on 2026-09-09 and is the
specification of record. Whoever lands this candidate should keep that rewrite and drop this
candidate's older edits to that one file rather than merging them.

## Other Hermes observations

Launcher and safe_next_step are fixed here. Dashboard tab creation, missing narration of an
implementer completion, unpriced Astra calls and the stale w-p1-verdict controller/lock have
not been repaired by this change. No stale lock was deleted and no paid loop was restarted.
Implementer-first audit dispatch is the P2 planning work itself. Unknown prices remain unknown;
this follow-up does not claim that the old $20 ceiling covered all provider calls.
