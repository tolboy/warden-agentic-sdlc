# The hypothesis, how it is tested, and what happens if it fails

Written 2026-09-18. Nothing here has been measured yet: this is the protocol, the decision
rule and the two exits, fixed before the data so the data cannot move them.

## 1. The claim

A deterministic workflow — fixed stages, machine gates, independent readers, a bounded fix
loop and a human decision bound to a specific tree — delivers accepted changes on a real
project **at no more operator effort and no more cost** than an agent orchestrator that lets
the agents decide what to do next (Paperclip, Hermes, OpenClaw and their kind), **and its
verdicts are checkable afterwards** where the orchestrator's are the agent's own account.

The second half is the part the first pilot already supported: the evidence layer held, and
the independent reader found real defects. The first half is what the operator's verdict of
2026-09-15 disputed — "too much fuss, it needs constant rework of its own" — and it is the
half that decides whether Warden is a product or a plugin.

## 2. What counts as evidence

Only what the ledger already records, plus two numbers a person writes down. Nothing is asked
of a model about its own performance.

| Quantity | Warden side | Baseline side |
|---|---|---|
| Outcome | `outcomes.accepted` / `ready_for_human` / `stopped` from `warden ledger --compare`; a stopped chain is not a success | `completed` in the baseline row, judged by the same acceptance commands run afterwards |
| Independent verdict | `candidate_review_passed`, with the reading's assurance label (`independent`, `same_vendor_peer`, …) | one read-only `warden role reviewer <task>` over the finished tree, paid and recorded like any other reading |
| Defects found by a reader that were not in the acceptance | `open_blocking_findings` and the finding registry | the same `warden role reviewer` reading |
| Vendor calls, reported cost, unpriced calls | `role_runs`, `cost_usd`, `unpriced_calls` per chain | `calls`, `cost_usd` in the baseline row, from the orchestrator's own logs; `null` when it does not say |
| Elapsed, excluding the human | `elapsed_seconds` per chain (waiting on the gate is not counted) | `elapsed_minutes` |
| Operator effort | minutes a person spent on the task outside the gate: writing the goal, answering stops, editing configuration, hand-fixing what the loop left | `operator_minutes` |
| Stops that were the tool's own friction | `outcomes.reasons` in the class *not about the work* (`visual_qa_unavailable`, `mcp_config_missing`, `quota_exhausted`, `turn_ceiling_reached`, …) | not applicable; the baseline has no such class, which is itself part of the comparison |

Operator minutes are the one quantity the ledger cannot see, and the one the 2026-09-15
verdict was about. They are written down per task, at the time, in the baseline file's row
for the baseline side and in a one-line note for the Warden side. An estimate made a week
later is not a measurement.

## 3. Protocol

1. **Freeze.** One Warden build, one policy, one roster, for the whole series. The first
   pilot's lesson: a build changed mid-series cannot be compared with itself.
2. **Pick tasks in pairs.** Three to five tasks per target type, each small enough to finish in
   one sitting and real enough to have an acceptance the project already runs. Target types
   in the order the tooling is proven: a browser page (harness available), then a Tauri
   window or a Unity scene (only after the `claude-visual-qa-mcp` probe has passed against a
   server the operator configured; until then those tasks are not comparable, because the
   baseline agent has eyes and the loop does not).
3. **Run each task both ways, on separate worktrees from the same base.** A: `warden do`
   with the five-role loop from [SETUP](SETUP.md#the-five-role-loop) and `--prepare always`.
   B: the same vendors driven by the orchestrator or by hand, given the same goal text, no
   contract. Order alternates between tasks so learning effects do not all favour one side.
4. **Judge B with A's judge.** After B finishes, run the project's checks and the task's
   acceptance commands, then one `warden role reviewer <task>` over B's tree. That makes the
   verdict on B checkable in the same way as the verdict on A, and it prices B's independent
   reading, which B did not pay for on its own.
5. **Write the baseline rows** (`docs/METRICS.md`, "Baseline file"): `label`, `task_kind`,
   `risk`, `completed`, `operator_minutes`, `elapsed_minutes`, `cost_usd`, `calls`. Unknown is
   `null`, never zero.
6. **Compare.** `warden ledger --compare --baseline-file baseline.json --text` in the target
   project. Groups with fewer than three units are marked not comparable; do not read one run
   as a trend.
7. **Count defects afterwards.** For two weeks after each pair lands, note defects reported
   against either change. This is the only metric that cannot be gamed by either side at the
   time.

What is deliberately not measured: how the agents felt about it, how many tokens were
"saved", or anything a model said about its own work.

## 4. Decision rule

The hypothesis **holds** for a target type when, over the series:

- every A task ends at the human gate with green acceptance and an independent pass, or at a
  stop that names a contract gap the operator agrees was real;
- operator minutes per task on A are no more than on B;
- cost and elapsed on A are within 1.5× of B — the price of the second reading — with unpriced
  calls listed rather than hidden;
- B's tree, read by the same reviewer, carries at least one blocking finding that A's loop
  would have sent back, **or** A's post-landing defect count is no worse than B's.

The hypothesis is **refuted** for a target type when B matches the first condition with fewer
operator minutes and lower cost on the majority of tasks, or when A's stops are mostly in the
*not about the work* class — the tool's own friction, which is the 2026-09-15 verdict restated
as a number.

Anything in between is "not enough data", and the answer to that is more pairs, not a softer
rule.

## 5. Conductor: evaluated, kept optional

What Conductor offers here, measured against the goals of this plan:

| Need | Conductor | What covers it today |
|---|---|---|
| The operator sees every process and each agent's work | No surface of its own | Orca: worktree cards, agent rows, terminals, the run timeline on the dashboard |
| Approve / reject from a phone | An external fail-closed gate, desktop-bound | Orca `gate-create` / `gate-resolve`, imported by `warden approve --from-orca`, with the wait bounded by `--wait-minutes` and the gate's own TTL |
| Change roles and models per vendor | Nothing | `warden roster`, the profile files, `warden profiles --verify` |
| An outer timeout around the whole run | Yes, its node timeout | `budgets.max_elapsed_minutes`, summed across a chain and excluding human wait |
| Fan-out across repositories, a DAG of several Warden tasks, CI-driven launches | Yes | Nothing; not a goal of this plan |
| The visual leg | Not enabled in its workflow | The harness and the visual role, in the loop |

The cost of integrating further is the one [ADR 0001](adr/0001-layer-split.md) named: a second
resume/retry state machine next to `TaskLoop`, `PYTHONUTF8=1` on Windows, and a workflow that
would have to be taught every stage the policy can declare. Nothing in the operator's stated
goals needs a DAG across tasks. The evaluation therefore ends where the ADR did: Conductor
stays an optional adapter, is not developed further here, and is reopened only if a fleet or
CI launch needs a distributed DAG. `conductor/gates.yaml` and its validation tests remain.

## 6. If the hypothesis fails: Warden as a plugin

If the series refutes the first half of the claim, the second half still stands on the
pilot's evidence, and it is what an orchestrator lacks. The plugin keeps exactly that:

- **The judge.** Machine gates, the browser harness, the agent-evidence check
  (`screenshots_taken` verified and hashed), the read-only fingerprint around every reader,
  and an independent reading with an assurance label. Entry point: `warden role reviewer`
  and `warden run` over a tree the host produced.
- **The decision.** `decision.json`, bound to a worktree fingerprint, expiring, importable
  from an Orca gate. Entry point: `warden approve` / `warden status`.
- **The evidence.** `.warden/runs` and the home corpus, `warden ledger --compare` for the
  comparison the host cannot make about itself.

What the plugin drops: `TaskLoop` as the thing that decides what runs next, `warden do` as the
front door, the planner pair. The host (Paperclip, Hermes, OpenClaw) calls one Warden command
per step, reads the single JSON object on stdout, treats `decision.json` as its gate, and
lands nothing on Warden's behalf — Warden never lands. The preconditions are the ones the
layer split already states: the host must accept an external gate and run a command per step,
and it must not be asked to duplicate the role DAG.

Which parts survive is decided by the same series: a judge that found nothing B's own review
missed is not worth carrying either.

## 7. What this document does not claim

No pair has been run. The five-role loop has not run live (GPT Astra's limits were not
restored on 2026-09-18, and the operator asked that the loop not be started). The MCP profile
template has not been probed against any server. The baseline file format exists and the
compare command reads it; both were exercised only by their suites.
