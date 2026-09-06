# Architecture status

The source of truth is Warden, not a target-project branch.

| Architecture block | Owner | Current state |
|---|---|---|
| Task spec and linter | Warden config | Implemented: intent, non-goals, risk, scope, authority, acceptance, visual scenarios, budgets and limits |
| Workflow declaration | Warden user policy | Implemented: `workflow.stages` in `~/.warden/policy.yaml` declares the order of stages, what each runs (`role` / `machine_gates` / `visual_harness`), the closed set of conditions gating it, and where a failure or a finding routes. Omitting the block runs the built-in chain. Stop reasons and the fix bound are deliberately not configurable |
| Run narration | Warden CLI | Implemented: `do` and `run` print the plan, each stage as it is reached, the profile and vendor of each dispatch, per-stage result with elapsed time, tokens and cost, each fix round and its destination, and the commands the human gate now expects — all on stderr, so stdout stays the single JSON object Conductor and scripts read. `--quiet` disables it. Nothing printed is evidence; it restates the ledger |
| Policy/run controller | Warden | Live: `warden do` created an Orca worktree from the actual current branch and resolved the whole chain, and run `chapter-hearth-3` carried it through to a green human gate: implementer, gates, independent review, browser harness and the visual role, one fix round, six vendor calls, $3.91 (docs/LIVE-CYCLE.md). `warden do` is the operator command: isolate (Orca worktree) → draft a task → implement → gates → fix ≤ N → review → fix ≤ N → browser harness → fix ≤ N → visual QA role → fix ≤ N → human gate. Never lands. `warden run` remains the inner loop |
| Role/profile resolver | Warden user policy | Implemented: verification, availability, rotation, independent vendor, and exclusion of profiles that ran out during the run |
| Vendor quota handling | Warden execution | Implemented: a spent subscription is classified apart from an ordinary failure, and both attempts stay in the evidence. Verified live against a genuinely exhausted Codex account. Whether the role may fail over at all is `failover.on_quota_exhausted` — `confirm` (default) stops with a FAILOVER decision naming both vendors and what the swap costs in independence, `auto` switches and logs `role_failover`, `stop` never switches. The authorisation to proceed is the recorded decision, carried by `warden run --continue <run-id>`, and it permits exactly one role-to-profile substitution |
| Direct CLI adapter | Warden SPI | Implemented. Live: Grok review, Claude Sonnet review, Codex mini implement (`prompt_delivery: stdin`, `--approve-for-me`). JSONL `agent_message` recovery for Codex. Windows batch-shim refusal unchanged |
| Orca adapter | Warden SPI + Orca | Implemented and live: attaches to the current Orca worktree, starts one supervised worker, completes only on `worker_done` / dispatch settlement. An unready start is fenced with `worker-stop` or fails `role_orca_lifecycle_unaccounted`; an unacknowledged delivery is the same code; a native agent parked on input is `role_human_input_required` and the worker is retained. Wall-clock expiry is `role_orca_timeout`, not the Direct CLI `role_timeout`. Does not create worktrees. A Claude Opus 5 reviewer was visible in Agent Dashboard, ran the project gate, returned a typed artifact, and was acknowledged and released on Orca 1.4.196; `docs/SMOKE.md` records the IDs |
| Local runner | Warden SPI | Implemented: `runner: local` POSTs an OpenAI-compatible chat completion to the profile's `endpoint` — a model already serving on this machine (Ollama, LM Studio, llama.cpp), no vendor subscription and no process spawned, so there is no argv for a quote or a newline in the prompt to be torn apart by. `command` is not required, because nothing is started; the report says `dispatch_preview` rather than naming a process. `api_key_env` names an environment variable, read at dispatch and never written to evidence, the ledger or the transcript; declared-but-empty is `role_local_api_key_missing` rather than a silent unauthenticated POST. The answer is settled by the same functions `direct` uses — artifact, required fields, JSON schema, reported status, worktree fingerprint — because two adapters that judged an artifact differently would be two definitions of a valid answer. Tokens come from `usage`; cost is never invented as `$0`. Not yet in a dated live smoke row |
| Configuration integrity | Warden | Implemented: the whole `.warden` tree except `runs/` is snapshotted before the first dispatch and compared after every role and around every gate command. Any modification, addition or deletion is `contract_mutated` naming the path. Because that holds, `.warden` sits outside the task's source blast radius without opening a hole |
| Workspace/tool policy | Git + TaskSpec authority | Scope and local write/network/land declarations implemented; tool allowlists pending |
| Pre-agent baseline | Warden | Implemented: optional `defaults.baseline_checks` names a project check set run before the first vendor. Red is `baseline_failed` and zero vendor calls. The same commands are the floor of the later acceptance gate, so an agent cannot break a test that was green at dispatch and still pass a narrower task check. A baseline command that mutates project source is `baseline_mutated_source`. Omitted by existing projects and by greenfield `warden init`. Not a `workflow.stages` item and not configurable away. Covered by the suite; not a live claim |
| Evidence ledger | Warden | Implemented: machine reports, append-only JSONL, `warden ledger` aggregation, and per-attempt vendor cost, turns, tokens and model as reported. A failover is two `vendor_attempts`, not one `role_run`: every split (`by_profile`, `by_vendor`, `by_model`, `by_outcome`) and every telemetry total counts the actual dispatch. Historical ledgers without the nested array remain one attempt per `role_run`. Every vendor runs through its own CLI on the operator's subscription, so a price exists only if that CLI printed one: `unpriced_calls` and `cost_ceiling_binding` say how much of a run the `max_cost_usd` ceiling actually measured, because a run where nothing priced itself can spend every allowed call and charge $0.00 against a $40 limit. `max_role_runs` is the bound that always holds. Telemetry is read from the vendor's envelope **before** the outcome is judged, so a call that failed is costed like any other: a run whose grok implementer died at its turn ceiling reported `total_cost_usd: 1.33` and was recorded as free, and an Opus review that did the same hid $4.88, because extraction used to happen only after a valid artifact. A ceiling that measures only the calls that worked is not measuring the run most worth costing |
| Run report | Warden | Implemented: `warden report <run-id>` joins the per-stage evidence into one view — stages in order, vendor/model/cost/tokens/duration per call, browser scenarios and screenshot hashes, source files changed since the pinned base, and the human decision. A value the vendor never reported stays absent and renders as `?` rather than becoming a zero. Verified live: Grok 4.6 review, $0.0835, 104042/13006 tokens |
| Conductor | Optional outer adapter | Machine-only workflow implemented; it does not duplicate Warden's role loop. Not part of the primary path. See [`docs/adr/0001-layer-split.md`](adr/0001-layer-split.md) |
| Visual QA — harness | Warden machine adapter | Implemented and project-neutral: `scripts/visual-qa.mjs` drives Edge/Chrome via CDP and asserts `text=` / `css=` / `testid=` / `role=` matchers as `visible`, `hidden`, `click` or `click@FX,FY -> <assertion>` (a point inside the element's own box, so a full-window canvas is reachable anywhere and not only at its centre), plus `no-console-errors`, and a `wait <n>` step that holds and photographs — without one, everything the harness sees is the page 1.2 s after a click, which is blind to any UI running on its own clock. Declared waits extend the adapter's own timeout, so a contract that asked for time does not come back as `visual_qa_unavailable`. Verifies the viewport it was asked for was the one the CSS saw. Each scenario writes a bounded accessibility snapshot ranked so the named control is first (then interactive nodes, then named ones), with role, name, bounding box, focused and ignored; ranking is `--rank-a11y` with no browser, the same shape as `--validate-only`. A pass without a screenshot is `visual_qa_no_evidence`; a stranger already serving the URL is `visual_qa_port_occupied`; no browser is `visual_qa_unavailable` |
| Visual QA — role | Warden user policy | Implemented, opt-in, and live: a `visual_qa` role receives every screenshot either as a vendor attachment (`capabilities.vision.delivery: cli_attachment`, e.g. codex `-i`) or as workspace paths it opens with its own image tool (`workspace_file`, e.g. Claude's Read), plus the harness report, and answers only what a machine cannot measure. The prompt states which delivery it got, because a model told images are "attached" when they are paths will answer about filenames and sound just as confident. Absent from the default policy on purpose — the harness is the floor, and looking costs a vendor call. Shipped profile is unverified until its probe is run. Live and load-bearing: on run `chapter-hearth-3` the harness was green and the role filed a P1 the machine had no way to see — a chapter's fire, stones and walker drawn inside a lake — which routed back as a fix round and passed on the second look |
| Artifact schema | Warden | Implemented: subset validator (`type`/`const`/`enum`/`required`/`properties`/`items`/bounds). An artifact that fails the schema is not written |
| Land step | Warden + project contract | Implemented: `warden land <run-id>` refuses anything but a resolved `accept`, refuses a worktree whose source fingerprint moved since it, refuses the branch a request would target, commits exactly the paths the report calls the change, pushes, and opens a request using the argv command the project declares under `land.pull_request`. Merges nothing. Nothing happens without `--commit` / `--push` / `--pull-request`. The forge is never guessed: no declaration, no request |
| Human gate / LAND | Warden + human | Implemented as durable state, not prose: `decision.json` per run, atomic and cross-process locked, with an optimistic-lock token. A rejection is an input rather than a full stop: `warden run --continue <rejected-run>` hands the reason a person gave to the next implementer verbatim, marked as a person's objection rather than a failed check, and authorises nothing. `warden status` / `warden approve` are the only ways in; accepting is refused when the worktree fingerprint moved after the decision was shown. Run from a directory that is not a Warden project and the code is `not_a_warden_project`, not `unknown_run`; `unknown_run` names the project that was consulted. `warden status --worktrees` lists pending decisions across every worktree of the current repository. Warden never lands. Orca is the surface a person may use to see the wait; Conductor may wrap the same command with an external gate. Neither substitutes for `decision.json` |
| Greenfield project | Warden | Implemented: `--init-repo` creates the repository and a baseline commit; `warden init` gives a directory with no files a contract with no invented check and a `<repository>` scope; browser scenarios satisfy the "executable definition of done" requirement on their own. A project with files but an unrecognised build system is still refused |

`warden init` now creates a conservative, non-overwriting starter contract for npm, Gradle
wrapper, Maven, Cargo or Make projects. Its inferred commands are a review starting point,
not trusted policy.

## Roles and models

| Where | What it decides |
|---|---|
| `~/.warden/policy.yaml` | which profiles may fill `implementer`, `reviewer`, `visual_qa`; rotation; whether a reviewer must be an independent vendor; which risks pay for review; and `workflow.stages` — the order those roles run in and the condition under which each runs at all |
| `~/.warden/profiles/*.yaml` | one vendor filling one role: command, args, `runner`, `prompt_delivery`, `attachments.flag`, `quota.signatures`, `model`, and the `verification` block the resolver refuses without. `warden profiles --verify <name>` runs that block's probe and keeps the transcript; `--confirm` stamps the date, and only after the probe passed in the same invocation |
| `<project>/.warden/` | what "done" means: checks, scopes, task contracts, `visual_qa` scenarios |

`model:` is passed to the vendor only if the profile's args use `{{model}}` — each vendor
spells the flag differently, so Warden supplies the value and the profile writes the flag.
When a vendor reports a different model than the profile declares, the run report records the
disagreement rather than letting the ledger's `model` column become a wish.

State ownership is intentionally single-source: Warden owns the bounded role/fix loop,
the gates, the evidence and the decision record; Orca owns worktrees, worker lifecycle
and the surfaces a person uses; Conductor is an optional outer adapter for timeout and
an external human gate. Duplicating the role DAG in Conductor would create two competing
resume/retry state machines. The cut is [`docs/adr/0001-layer-split.md`](adr/0001-layer-split.md).

## What a failure is allowed to do

Every vendor failure resolves to exactly one of these, and the choice is made in code:

| Outcome | Response | Why not the other one |
|---|---|---|
| `role_quota_exhausted` | Fail over to the next eligible vendor; exclude this profile for the rest of the process | Retrying the same vendor refuses identically and spends the budget proving it |
| `role_command_failed`, `role_timeout`, `role_artifact_*` | Stop the role; the loop may hand the failure text back to the same vendor as a fix round | Failing over would route around a defect instead of surfacing it |
| `role_prompt_undeliverable`, `authority_denied` | Stop the role; no vendor is dispatched at all | These are configuration faults. Routing around one hides it and pays a second vendor for the same corrupted request |
| `role_orca_unavailable`, `role_orca_no_worktree`, `role_orca_no_coordinator`, `role_orca_unsettled`, `role_orca_start_failed`, `role_orca_timeout`, `role_orca_lifecycle_unaccounted` | Stop the role; no failover to Direct CLI; no fix round | The profile declared `runner: orca`. Pretending it was a CLI would hide a missing worktree, an unproven settlement, or a worker that was not fenced. `role_timeout` is reserved for Direct CLI after ProcessRunner has killed the process tree |
| `role_human_input_required` | Stop the role; retain the worker | A native agent is parked on input only a person can provide. Handing that to a new implementer would abandon a live session |
| `role_turns_exhausted` | Stop the role; no failover. The run's reason is `turn_ceiling_reached`, and a later `--continue` keeps the verdicts the earlier stages already reached | The vendor was interrupted by a ceiling the operator set, not by anything in the work. Another vendor would meet the same ceiling on the same diff, and `role_command_failed` would send someone to read a transcript with no defect in it. Both measured specimens exit non-zero — grok prints `Error: max turns reached`, `claude -p` reports `error_max_turns` — so the classification is made on both failure paths, not only where a vendor exits 0 without an artifact |
| `role_runner_unimplemented` | Retired; nothing emits it | Every runner the profile schema accepts now has an adapter. The code stays in the ledger's configuration-failure set so evidence written before that still classifies, rather than being reclassified after the fact |
| `role_local_endpoint_unreachable`, `role_local_api_key_missing`, `role_local_http_error`, `role_local_response_unreadable` | Stop the role; no fix round | These are about the endpoint, not about the work. No implementer can start a model that is not serving, export a variable nobody set, or fix a 500 from inside the diff — the same reason `visual_qa_unavailable` and the `role_orca_*` codes are terminal. Each is a separate code because each sends the operator somewhere different: the server, their own shell, the server's logs, and the server's API shape |
| `visual_qa_failed` | Hand the failing scenario and its screenshot back to the implementer, bounded by `max_fix_attempts` | It is a failing check like any other; leaving it terminal made the one role with eyes the only one whose finding nobody had to act on |
| `visual_qa_unavailable`, `visual_qa_port_occupied` | Stop the run; no fix round | No implementer can install a browser or evict another project's dev server, and asking one to try spends a role run on the operator's configuration |
| `visual_findings_remain` | Stop for a human | The model looked and still objects. Warden does not overrule it |
| `goal_mangled_by_console_encoding` | Refuse before anything is written | The JVM decodes argv with `sun.jnu.encoding`; on a non-UTF-8 Windows host a Cyrillic goal is already `?` by the time `main` runs, and no JVM flag changes it. `--goal-file` is the channel that works |
| `role_violated_read_only` | Discard the artifact whatever it claims | The declaration is intent; the worktree fingerprint is the fact |

The exclusion list for quota lives in memory for the lifetime of one process, deliberately.
Persisting it would leave a vendor disabled after its quota window rolled over, and that kind
of stale state is only ever discovered when it is already wrong.

## What Orca shows during a run

Warden calls Orca; Orca never calls Warden. Everything below is something Warden asks for,
which is why closing any of it leaves the loop running.

| Surface | What it says | Written by |
|---|---|---|
| Workspace card comment | `[1/5] implement · implementer (grok-implement / grok) · running · 12m00s` — position, stage, role, the profile and vendor filling it, and the clock while the stage is still going. A stage that runs no vendor (`gates`, `browser harness`) keeps the bare name and grows no brackets | `Workspace.note` at each stage boundary (rewritten with the resolved pair as soon as RoleRunner chooses it), `Workspace.working` once a minute in between |
| Workspace status column | `in-progress` / `in-review` / `completed` — three columns, because the card answers one question: is this waiting for me | `Workspace.state` |
| Terminal tab (`--watch` only) | `warden <run-id> · implementer (grok-implement) 12m00s` while a role runs, `warden <run-id> - NEEDS YOU` when the loop stops for a person. The tab is short: profile, not vendor | `terminal create`, then `terminal rename` on every beat and every state change |
| Terminal contents (`--watch` only) | The narration, followed live from `.warden/runs/<id>/narration.log`, including `... 12m00s   implementer (grok-implement / grok) still working` once a minute | `Progress.toFile`, tailed by the script Warden writes beside it |

Every dispatch that can take minutes runs under a beat, including a fix round. A fix round is
a second call to the same vendor, of the same length, after the operator has already waited
once, and it is the moment a run is most likely to be watched; its card line says which stage
sent the work back and who took it (`fix 1/2 · review sent the work back to implementer
(grok-implement / grok)`).

The tab and the card can disagree for at most one beat, and only in one direction: once the
run has said it stopped, the board refuses any beat that arrives afterwards. A heartbeat is
closed before its stage returns, but closing it only stops the next beat — one already inside
a fifteen-second Orca call can still land, and `implementer 12m05s` written a moment after
`NEEDS YOU` would be the board's own doing, not the loop's.

### Which roles appear in Orca's Agent Dashboard

Measured against Orca 1.4.194, not read from documentation:

- The dashboard lists **agent sessions** — terminals in which Orca itself started a known agent
  CLI. Its `NEEDS YOU` / `WORKING` / `DONE` buckets come from the title the process in that
  terminal emits, and the row's label is the tab title Warden already sets.
- A plain terminal does not become a row by being renamed, even to a title Orca parses as an
  agent status. Renaming one to `✦ …` is enough for Orca to infer an agent *identity* — it
  reads the glyph as Gemini's — and still not enough to produce a row. Warden therefore uses
  no status glyphs: a tab that claims to be a vendor nobody dispatched is worse than a tab
  that says nothing.
- Orchestration objects are not agents. A Run and a task created through
  `orca orchestration task-create --display-name …` leave the dashboard at `0 total`.
- A terminal whose command *is* a vendor CLI does produce a row, including a headless
  invocation that exits — `claude -p … --output-format json` was listed as a row under its tab
  title within seconds.

So the dashboard is reachable through `runner: orca`, and the price is moving the vendor
process out of Warden's own runner into an Orca pseudo-terminal. That costs the guarantees
`direct` exists to provide:
`prompt_delivery: stdin` has no channel through a PTY, and it is the delivery two of the three
verified profiles depend on precisely because Windows argv mangles a prompt containing a quote
or a newline; the wall-clock bound, the descendant kill and the exit code stop being Warden's
to enforce. Trading those for a row in a list is not a trade Warden makes by default. The
`runner: orca` adapter remains the place where anyone who wants it can pay that price
deliberately, per profile.

That path is now measured too. On 2026-09-04 a Claude Opus 5 reviewer launched by Warden was
listed as `WORKING` with its current tool action and worktree, then sent a typed `worker_done`
and disappeared after Warden acknowledged and released it. Orca exposes concise progress and
tool activity, not a model's private chain-of-thought. Direct-runner roles still do not become
Agent Dashboard sessions; they remain visible through Warden narration, worktree cards and the
evidence ledger.

What the row is *called* is the live terminal title, not the task's `display_name`. Warden sets
that title as soon as the worker exists, which is what an agent that never titles itself would
otherwise show as an internal worker handle. An agent CLI that does set its own title wins
within seconds: the observed rows read `◑ Warden role reviewer for task review-smoke`, which is
Claude Code compressing the first sentence of the spec Warden wrote. The `display_name` Warden
sends (`reviewer / claude`) is stored on the task and rendered nowhere. Naming the row
deterministically for every agent would be a change in Orca.

## A supervised worker outlives the process that started it

That is the point of `runner: orca` — an agent parked on a question nobody has answered yet
should still be there tomorrow — and it is why the identities cannot live in memory. Before a
worker is started, `.warden/runs/<run-id>/orca.json` records the Run, task, dispatch and
coordinator handles, the worktree, the vendor and model, and the read-only fingerprint taken at
that moment. The file sits in the one directory both the read-only fingerprint and the contract
snapshot deliberately exclude, so writing it during a run changes no judgement.

Two guards, because they fail in different situations:

| Question | Answered by | When it is the only one that can answer |
|---|---|---|
| Is *my* worker still there? | the record, then `orchestration worker-show` | after a clean kill, where the record exists |
| Does *anybody's* worker hold this worktree? | `orchestration worker-list` | after a crash mid-`worker-start`, where no record was written |

Neither is allowed to read "cannot tell" as "no". A dispatch Orca cannot describe, an
unreadable record and an unreachable census are all `role_orca_lifecycle_unaccounted`; a live
worker in this worktree is `role_orca_worker_active`, refused in seconds and costing nothing.
Only `dispatch_not_found` — Orca saying it has never heard of the dispatch — clears the way for
a fresh start.

A refused fence is not a failed fence. Orca answers `dispatch_inactive` when asked to stop a
worker that has already stopped, so Warden verifies the refusal with `worker-show` rather than
trusting either answer: a settled or unknown dispatch is fenced, and a dispatch still in flight
is not.

## The human decision, and where it can be answered

`decision.json` is the decision. What the Orca gate adds is reach: a run stops for a person,
and the person is not at the worktree. So the pending decision is mirrored into Orca as a
decision gate carrying Warden's own options, and `warden approve --from-orca` carries the
answer back.

The mirror decides nothing, and that is enforced rather than trusted. An imported answer is
admitted only if it maps exactly onto one of the decision's declared options, only while the
pending decision has not moved since the gate was published, and — for an acceptance — only
while the candidate fingerprint still matches. The reason is measured, not hypothetical: on
Orca 1.4.196 `gate-resolve` accepts free text rather than a declared option, and re-resolving a
settled gate silently replaces the answer. A gate changed from `retry` to `abort` after the
fact left the recorded decision reading `retry`.

A question from the agent is handled the same way round. It is a healthy wait, not an ending:
Warden takes delivery of it, keeps watching for the role's whole budget so an answer given
while the controller is still running finishes the work in the same process, and only reports
`role_human_input_required` when the budget expires with the question still open. The worker is
retained and the message id recorded, so answering it and running the role again attaches to
that same worker.
