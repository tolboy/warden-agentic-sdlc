# Architecture status

The source of truth is Warden, not a target-project branch.

| Architecture block | Owner | Current state |
|---|---|---|
| Task spec and linter | Warden config | Implemented: intent, non-goals, risk, scope, authority, acceptance, visual scenarios, budgets and limits |
| Workflow declaration | Warden user policy | Implemented: `workflow.stages` in `~/.warden/policy.yaml` declares the order of stages, what each runs (`role` / `machine_gates` / `visual_harness`), the closed set of conditions gating it, and where a failure or a finding routes. Omitting the block runs the built-in chain. Stop reasons and the fix bound are deliberately not configurable |
| Run narration | Warden CLI | Implemented: `do` and `run` print the plan, each stage as it is reached, the profile and vendor of each dispatch, per-stage result with elapsed time, tokens and cost, each fix round and its destination, and the commands the human gate now expects — all on stderr, so stdout stays the single JSON object Conductor and scripts read. `--quiet` disables it. Nothing printed is evidence; it restates the ledger |
| Policy/run controller | Warden | Live: `warden do` created an Orca worktree from the actual current branch and resolved the whole chain, and run `chapter-hearth-3` carried it through to a green human gate: implementer, gates, independent review, browser harness and the visual role, one fix round, six vendor calls, $3.91 (docs/LIVE-CYCLE.md). `warden do` is the operator command: isolate (Orca worktree) → draft a task (deterministic `TaskDraft`, or an optional read-only `planner` when `--prepare auto|always`) → implement → gates → fix ≤ N → review → fix ≤ N → browser harness → fix ≤ N → visual QA role → fix ≤ N → human gate. Never lands. The planner drafts; Warden validates. `warden run` remains the inner loop |
| Role/profile resolver | Warden user policy | Implemented: verification, availability, rotation, independent vendor, and exclusion of profiles that ran out during the run |
| Vendor quota handling | Warden execution | Implemented: a spent subscription is classified apart from an ordinary failure, and both attempts stay in the evidence. Verified live against a genuinely exhausted Codex account. Whether the role may fail over at all is `failover.on_quota_exhausted` — `confirm` (default) stops with a FAILOVER decision naming both vendors and what the swap costs in independence, `auto` switches and logs `role_failover`, `stop` never switches. The authorisation to proceed is the recorded decision, carried by `warden run --continue <run-id>`, and it permits exactly one role-to-profile substitution |
| Direct CLI adapter | Warden SPI | Implemented. Live: Grok review, Claude Sonnet review, Codex mini implement (`prompt_delivery: stdin`, `--approve-for-me`). JSONL `agent_message` recovery for Codex. Windows batch-shim refusal unchanged |
| Orca adapter | Warden SPI + Orca | Implemented and live: attaches to the current Orca worktree, starts one supervised worker, completes only on `worker_done` / dispatch settlement. An unready start is fenced with `worker-stop` or fails `role_orca_lifecycle_unaccounted`; an unacknowledged delivery is the same code; a native agent parked on input is `role_human_input_required` and the worker is retained. Wall-clock expiry is `role_orca_timeout`, not the Direct CLI `role_timeout`. Does not create worktrees. A Claude Opus 5 reviewer was visible in Agent Dashboard, ran the project gate, returned a typed artifact, and was acknowledged and released on Orca 1.4.196; `docs/SMOKE.md` records the IDs |
| Local runner | Warden SPI | Implemented: `runner: local` POSTs an OpenAI-compatible chat completion to the profile's `endpoint` — a model already serving on this machine (Ollama, LM Studio, llama.cpp), no vendor subscription and no process spawned, so there is no argv for a quote or a newline in the prompt to be torn apart by. `command` is not required, because nothing is started; the report says `dispatch_preview` rather than naming a process. `api_key_env` names an environment variable, read at dispatch and never written to evidence, the ledger or the transcript; declared-but-empty is `role_local_api_key_missing` rather than a silent unauthenticated POST. The answer is settled by the same functions `direct` uses — artifact, required fields, JSON schema, reported status, worktree fingerprint — because two adapters that judged an artifact differently would be two definitions of a valid answer. Tokens come from `usage`; cost is never invented as `$0`. Not yet in a dated live smoke row |
| Configuration integrity | Warden | Implemented: the whole `.warden` tree except `runs/` is snapshotted before the first dispatch and compared after every role and around every gate command. Any modification, addition or deletion is `contract_mutated` naming the path. Because that holds, `.warden` sits outside the task's source blast radius without opening a hole |
| Workspace/tool policy | Git + TaskSpec authority | Scope and local write/network/land declarations implemented; tool allowlists pending |
| Pre-agent baseline | Warden | Implemented: optional `defaults.baseline_checks` names a project check set run before the first vendor. Red is `baseline_failed` and zero vendor calls. The same commands are the floor of the later acceptance gate, so an agent cannot break a test that was green at dispatch and still pass a narrower task check. A baseline command that mutates project source is `baseline_mutated_source`. Omitted by existing projects and by greenfield `warden init`. Not a `workflow.stages` item and not configurable away. Covered by the suite; not a live claim |
| Evidence ledger | Warden | Implemented: machine reports, append-only JSONL, `warden ledger` aggregation, and per-attempt vendor cost, turns, tokens and model as reported. A failover is two `vendor_attempts`, not one `role_run`: every split (`by_profile`, `by_vendor`, `by_model`, `by_outcome`) and every telemetry total counts the actual dispatch. Historical ledgers without the nested array remain one attempt per `role_run`. Every vendor runs through its own CLI on the operator's subscription, so a price exists only if that CLI printed one: `unpriced_calls` and `cost_ceiling_binding` say how much of a run the `max_cost_usd` ceiling actually measured, because a run where nothing priced itself can spend every allowed call and charge $0.00 against a $40 limit. `max_role_runs` is the bound that always holds. Telemetry is read from the vendor's envelope **before** the outcome is judged, so a call that failed is costed like any other: a run whose grok implementer died at its turn ceiling reported `total_cost_usd: 1.33` and was recorded as free, and an Opus review that did the same hid $4.88, because extraction used to happen only after a valid artifact. A ceiling that measures only the calls that worked is not measuring the run most worth costing. Each local append also projects an allowlisted measurement into `<UserConfig.home>/ledger/` through one write-then-deliver protocol; see below. `warden ledger` remains local to the project tree by default; `--global` reads the home corpus from outside a repository, filtered by recorded project identity, and `--import` copies selected local runs and archives through the same allowlist |
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

## Home measurement corpus

Local evidence stays at `<projectRoot>/.warden/runs/<run-id>/evidence.jsonl`. It is still
the source `warden ledger` reads, and it still holds the raw material of a run: prompts,
vendor streams, finding text, command lines. Deleting the project tree deletes that.

A second, durable record lives at `<UserConfig.home>/ledger/` — by default `~/.warden/ledger/`,
honouring `WARDEN_CONFIG_HOME`. Tests always pass a temporary home and never write into the
operator's own. The home corpus is versioned append-only JSONL, one segment file per writer
process (`host-pid-start`), so two JVMs never interleave lines in one file. Where a single
file must be shared (the event-id index), the exclusive OS lock `JsonFile.underExclusiveLock`
already implements is taken. `JsonFile.writeAtomically` is the model for that index and for
the delivery-mark set, not for the append stream.

The home corpus is a measurement, not an archive of the run. Absence of text does **not**
make it safe to publish: identifiers, timestamps, model names, spend and fingerprints can
still identify an operator, a project and a vendor account. A portable export additionally
omits absolute paths, account names and private URLs; that is still not a public dataset.

Every existing evidence producer writes through `EvidenceLedger.append`, including the
workflow reservation and `markPrepared`, each completed vendor attempt (before another
attempt is considered), gates, visual QA, role outcomes, workflow summaries and human
decisions. `warden ledger` remains local to the project tree by default and keeps those
fields for a well-formed tree. `warden ledger --global` reads the home corpus from
outside a repository, filtered by recorded project identity rather than by path; it
never mints an identity and never constructs an `EvidenceLedger`. Omitting `--project-id`
from outside a project reports measurements whose project identity is unknown
(imported legacy evidence), and that absence stays unknown. One aggregator serves
both sources and never sums them. Paid calls are counted once: the local view still
hides `vendor_attempt` and expands `role_run.vendor_attempts`; the corpus counts
`accounting.counts_as_new_calls`, and expands a historical `role_run.vendor_attempts`
attempt by attempt, suppressing only a matching `vendor_attempt_id`. Sharing a run
instance or operator run label does not cover an entire summary. Missing identities
do not establish coverage, and an id on only
one representation does not prove two distinct calls: that relationship is flagged as
`ambiguous_coverage` rather than merged. A corrupt line, an
unsupported schema version or an incomplete import yields the file, the byte offset,
a skip count and `incomplete`, and does not print an exact total spend. A skipped
import row whose project identity could not be recovered applies to every identity.

`warden ledger --import PATH` is the explicit import of selected local runs and
archives through the same projector. Modern rows are keyed by `event_id`. Rows with
no event id use a deterministic provenance key (type, time, code, ok, and the other
stable identity fields the row already had). Re-importing a source, a copy of it, or
an overlapping archive leaves totals unchanged; an ambiguous match is flagged, not
merged. No read imports, and no raw material is imported.

### Identity and units of accounting

Every event receives `event_id`, `schema_version` (currently 1), event time, `type` and a
per-run `seq`. Project identity is not the path: it is the sorted git root-commit set plus
the `project:` name, or a UUID when there is no git history, and either way it is recorded
once under `.warden/runs/.project-identity` and the record wins from then on. That is what
keeps one project to one id when git is missing from a process's PATH, when `rev-list`
outruns its timeout, and when HEAD moves onto an orphan branch with different roots. A
tree that was first seen without git keeps the id it was given, and a later successful
probe writes its git-derived id beside it as `git_project_id` rather than starting a
second identity nothing joins; a probe that could not run at all is marked `provisional`. Copies of a git repository keep the same project id; two projects whose
operators chose the same `run_id` do not merge, because the run instance is a separate
UUID stored in `run-instance.json`. Lineage for `--continue` is the parent run instance,
and only when that file exists — unknown lineage stays unknown. Stage, role invocation,
vendor attempt and candidate fingerprint are linked as separate identifiers when the
producer has them.

An actual vendor attempt is counted once (`accounting.kind = vendor_attempt`, with
`vendor_attempt_count` for failovers). The planner is a call and enters calls and spend.
A `task_run` / `task_dry_run` is a workflow summary and is not a new call. Role success,
workflow outcome and human accept stay three fields under `outcomes` and three aggregates.

### Allowlist projection

The shared record is derived from the local one, never the reverse. Projection is an
allowlist, including through nested structures. A field nobody allowed does not pass
because it is new. Goal, prompt, vendor reply, finding text, arbitrary error strings,
argv, environment, credentials, diffs and sources are excluded. A finding contributes
its id, severity, status, stage and closure/reopen links, and no text. The workflow
records that lifecycle under `finding_history` (and a role_run copies allowlisted
findings from the artifact); both are projected field by field. References to
raw material stay in the run's local `provenance.jsonl`.

`role_contract` is still the reuse comparison and is not changed. A `measurement_context`
snapshot is recorded alongside it: wall-clock and task time limits, turn limit when
declared, effective grants, prompt-template and schema hashes, the Warden version, and
requested / effective / reported model and effort, each labelled with its source. The
label itself is part of the measurement (`source` on the labelled value, and on grants);
it is not stripped as if it were source text. A missing value stays absent and never
becomes zero. An unknown effective setting is never replaced by the requested one.

### Write-then-deliver protocol

One `EvidenceLedger.append` performs four steps. They are not a transaction.

1. **Identity and local write.** The event is assigned its ids, appended to the run's
   `evidence.jsonl`, and forced to disk.
2. **Journal.** The already-projected body (allowlist applied, so the journal holds no
   raw content either) is appended to `outbox.jsonl` beside the run's evidence, with the
   event id and a content hash, and forced. The journal travels with the tree, so an
   undelivered measurement is discoverable from the tree that produced it.
3. **Shared write.** The projected line is appended to this writer's segment under
   `<UserConfig.home>/ledger/segments/` and forced. If the segment already ends in an
   unterminated fragment (a crash mid-line), that tail is isolated with a newline first
   so the new record is not concatenated onto it. The durable acknowledgement is the
   return of that force **and** an independent read of a complete JSON object with that
   `event_id`: before both, nothing may report the measurement as delivered, and after
   it the record survives deletion of the project tree.
4. **Delivery mark, best effort.** `outbox-delivered.json` records the event id. A crash
   before it leaves the event undelivered in the journal, never lost.

The event-id index is an append-only log, `ledger/index.jsonl`, folded into the
`ledger/index.json` snapshot when it grows past a megabyte. Delivering one measurement
appends one line to it and one line to a segment, and costs nothing that scales with the
corpus. The full reconciliation — snapshot, then log, then every line of every segment —
is a recovery step, not a per-append one: it runs the first time a process touches a home,
which is also the only moment a crash between a segment append and the index write could
be hiding. While another process appends, the log grows, and the next delivery reads the
tail it added rather than the whole store.

Sequence allocation, the local evidence append, the journal and the delivery-mark set
are serialised per run with the exclusive OS lock `JsonFile.underExclusiveLock` already
implements (`evidence.lock` beside the run's evidence). Two processes must not assign
the same `seq`, splice outbox lines, or lose a delivery mark by an unlocked
read-modify-write.

Recovery replays delivery, not execution. On the next write against that home, every
journal row not marked delivered is re-appended. The corpus is deduplicated by
`event_id`: the same id with the same content hash is a no-op; the same id with different
content is recorded in `conflicts.jsonl` rather than silently dropping one line. No
vendor call is ever repeated to recover the metrics of a call already paid for. A
completed vendor attempt is journaled as its own `vendor_attempt` event before failover
or the next dispatch is considered, so recovery has the cost, tokens and outcome
without executing the call again.

A copy of the corpus taken while a writer is live is either consistent by this protocol
(every forced, acknowledged line is a complete JSON object) or explicitly incomplete
(an isolated unterminated fragment is unreadable on its own line). There is no
atomic snapshot of a live append stream.

### When the corpus cannot be written

A failure at step 3 leaves the local evidence and the journal intact and marks the run
`pending` or `error` in `corpus_status.json`. That file answers one question — did what
this run wrote reach the corpus — and both writers of it, an append after a confirmed
delivery and a recovery, decide through `HomeCorpus.refreshStatusFromJournal`, because
counting different things is how a recovery once overwrote a pending an append had earned.
An absent `corpus_status.json` is not evidence of delivery: the journal is counted instead,
so a run whose status write failed does not read as delivered.

Whether the *tree* may be deleted is a different question with a different lifetime, and it
is counted when it is answered rather than remembered: `tree_safe_to_delete` is true only
when no run under `.warden/runs` has an undelivered journal row. A cached answer is wrong in
both directions — a neighbouring run that fails a minute later leaves a stale yes, and one
that is recovered afterwards leaves a stale no — and a tree deleted on a stale yes loses a
measurement that no import in this slice could bring back. The CLI reports that state (`corpus_status`,
and `tree_safe_to_delete: false`) on approve, `run`, `do` (including a planning-only
`--draft-only` completion), a preflight failure, a standalone role, standalone gates
and visual-qa; the decision or settlement itself still succeeds locally. New paid
dispatch stops: `RoleRunner.DispatchGate.requireDispatch()` is the choke point crossed
before anything is spent. It recovers undelivered rows, then exercises this writer's
segment, the index lock, and index persistence (the same atomic replace delivery uses)
— a writable `.writable` probe is not enough — and refuses with `ledger_unavailable`
until journal and corpus reconcile. Settlement of a worker already running and the
recording of a human decision are never blocked for analytics and are never
re-dispatched. Pending means the tree is not yet safe to delete. After recovery,
completeness is restored by reconciling journal against corpus, not by a timer. Calls
made with `--prepare off`, planning-only runs, refusals before dispatch and a late
approve take this same write path.

## Roles and models

| Where | What it decides |
|---|---|
| `~/.warden/policy.yaml` | which profiles may fill `implementer`, `reviewer`, `visual_qa`, `planner`; rotation; whether a reviewer must be an independent vendor; which risks pay for review; and `workflow.stages` — the order those roles run in and the condition under which each runs at all. `planner` is optional and absent from the shipped policy; `architect` remains a loadable role with no shipped profile and no stage |
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

## The planner drafts; Warden validates

`planner` is a role like the others — a profile can declare it, `policy.yaml` can name it,
and `RoleResolver` applies the same verification, strategy and independence rules. It is
not a workflow stage and it is not in the shipped policy. `warden do --prepare off|auto|always`
(default `off`) is what dispatches it, so no existing project changes behaviour.

The planner runs before a task contract exists, so a task contract cannot bound it. Warden
bounds it instead: the profile's `limits.wall_clock_minutes`, one call with no repair,
`read_only: true` enforced by the worktree content fingerprint, and no authority to write
or to reach the network. The one-call bound is a dispatch gate: quota failover is another
vendor call and is refused, while a protocol failure may retry once after the tree is
restored. A planner that moved the tree is `planner_protocol_violation`. `CallPlan` is
written into the run marker before the vendor is paid, so a report cannot discover the
planner afterwards and call it free. That reservation is one plan: the run's real
ceiling measured with the same skip predicate the loop routes by, not the bootstrap's
cap of one laid against the undeclared chain. `--draft-only` records the bootstrap
itself — a cap of one and `paying_stages` naming the planner alone — because no
workflow stage will run.

What it returns is a draft. Warden compiles that draft into a frozen contract, or refuses
it and names the field: acceptance must resolve to a named check under `checks:` in
`.warden/project.yaml` (a shell string is never promoted), `scope` must be a named scope,
the operator's original goal is carried verbatim, and `required_access` may not exceed
what the invocation already authorised. A draft that fails the shipped schema is
`role_artifact_schema_violation` even when the profile skipped enforcement. The planner
may request a visual contract; if it omits `visual_qa`, Warden applies the same rule
`TaskDraft` uses, so `--prepare always` is not weaker than `--prepare off` on a UI goal.
An existing contract is not truncated: `--prepare always` on a task id that already has a
file keeps that file, or refuses `task_conflict` when the intent differs — the same
protection `TaskDraft` already gives. `--prepare auto` on a contract the planner itself
wrote does not rewrite it through `TaskDraft`; it still refuses `task_conflict` when that
file does not preserve the supplied operator goal, scope or risk, so a ready contract for
one piece of work is not executed as another. Planner-added text after the original goal
is kept. Compiled named checks stay names: several of them are written as
`checks.names`, and a name that later disappears from `project.yaml` is refused rather
than run as a shell string. `--prepare always --draft-only` spends the planner and never
the implementer. The call is a `role_run` and `CallPlan` reserves for it.
A later `warden run` (including Conductor's inner process) joins that reservation instead
of refusing `run_id_exists`. When auto skipped the planner, the same reservation still
records `prepare=auto`, so the inner run's JSON does not say `off`.

**Not built here:** an interactive draft editor, TTL or timestamps on network observations,
and subtask execution. The schema records subtasks; nothing runs them. There is no
`warden draft` command.

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
| `ledger_unavailable` | Stop before dispatch; no fix round, no failover. A `--continue` keeps the verdicts already reached | The home corpus could not be written, which is a fact about the store and not about the diff. Failing over would pay a second vendor to meet the same refusal, and a fix round would ask an implementer to repair a directory. Same class as `visual_qa_unavailable`, with one addition: because nothing was dispatched, the judgements this run already holds are still good, so continuing after the store is restored costs nothing |
| `visual_qa_failed` | Hand the failing scenario and its screenshot back to the implementer, bounded by `max_fix_attempts` | It is a failing check like any other; leaving it terminal made the one role with eyes the only one whose finding nobody had to act on |
| `visual_qa_unavailable`, `visual_qa_port_occupied` | Stop the run; no fix round | No implementer can install a browser or evict another project's dev server, and asking one to try spends a role run on the operator's configuration |
| `visual_findings_remain` | Stop for a human | The model looked and still objects. Warden does not overrule it |
| `goal_mangled_by_console_encoding` | Refuse before anything is written | The JVM decodes argv with `sun.jnu.encoding`; on a non-UTF-8 Windows host a Cyrillic goal is already `?` by the time `main` runs, and no JVM flag changes it. `--goal-file` is the channel that works |
| `role_violated_read_only` | Discard the artifact whatever it claims | The declaration is intent; the worktree fingerprint is the fact |
| `planner_protocol_violation` | Discard the planner's draft; restore only what the planner wrote, including a commit (`reset --mixed` to the captured HEAD, then the dirty files); retry once. A tree Warden did not snapshot is not `reset --hard` — that would destroy work that predates the run, including in `--in-place`. If restore cannot prove the tree is back at the captured HEAD and fingerprint, stop with no retry. The discarded attempt's prompt, raw stdout and report keep their own names so a retry cannot overwrite the receipt. | A planner that moved the worktree is a protocol failure, not an ordinary P1. The second failure stops. |
| `planner_draft_invalid` | Refuse the draft and write no contract | Acceptance must name a `checks:` entry, scope must be a named scope, the operator's goal must be carried verbatim, and `required_access` may not exceed the invocation |

The exclusion list for quota lives in memory for the lifetime of one process, deliberately.
Persisting it would leave a vendor disabled after its quota window rolled over, and that kind
of stale state is only ever discovered when it is already wrong.

## What a repair is allowed to commit the run to

A repair round is not one vendor call. It is the fix, the judgements the fix invalidates, and
the stages still owed after it — and the chain, its conditions and which stages cost a vendor
call are all known before the first dispatch. So the arithmetic happens before the reservation
rather than being discovered by paying for it.

How much of it has to be affordable is `budget.repair_reserve`, and it is a policy choice
rather than a judgement made in code, because the two answers trade against each other rather
than one dominating:

| Mode | Reserves | The case for it |
|---|---|---|
| `full` (default) | the repair, every judgement it invalidates, and every stage still owed | Does not begin a repair unless the remaining calls can fund completion with no further failures. A repair may regress, and the operator may choose not to continue at all |
| `partial` | the repair, plus reaching whichever stage will read its result | Allows a reviewed intermediate result when the operator values progress before funding the full chain. A valid verdict may be reused on continuation; improvement and eventual completion are not guaranteed |

Neither mode is silent. Both publish `budget_plan` before the first dispatch, with the
clean-pass cost and every repair branch costed under both floors, and `--dry-run` prints it.
`reachable_under_cap` describes funding the whole repair branch; the separate
`repair_allowed_under_cap` describes permission under the selected reserve mode. The
calls already spent reaching the branch are counted once, without counting its suffix twice.

The `partial` floor is deliberately not the bare cost of the repair. A machine gate failing
before the first review costs exactly one call to repair — the gate re-runs for free and no
judgement has been given yet to re-establish — so reserving that would let a run pay an
implementer and stop with a changed tree nothing ever looked at.

| Outcome | When |
|---|---|
| `budget_insufficient_to_finish` | The remaining calls do not meet the mode's floor. The summary's `budget_reserve` names the mode, all three numbers, and what was left |
| proceed, with `budget_reserve.will_finish: false` | `partial` only: enough to repair and be judged, not enough to reach the end. Narrated at the time, not discovered in the summary |
| `independent_review_unavailable` | No profile can fill a role a later stage needs, or none that differs from the vendor which wrote the code. Independence is what runs out first: two vendors minus one spent subscription is one vendor. Reported as a fact about the roster, because `reviewer_failed` sends an operator to read a transcript with no defect in it |

`budget_exhausted` still means what it always did: a call was refused at a ceiling. Which
ceiling is now carried as `budget_limit_hit` rather than spelled into a sentence, because the
two are fixed by different edits and the run used to advise raising `max_role_runs` to somebody
whose `max_cost_usd` had run out. The money ceiling's advice also repeats what `unpriced_calls`
means: it measures only the calls whose vendor reported a price.

A budget stop leaves earlier verdicts standing, on the same terms as `turn_ceiling_reached` —
the source fingerprint, the acceptance surface and the judging contract all have to match.

## What a carried verdict has to prove

Reusing a verdict across runs rests on two claims, and until recently only one was checked.

**That the bytes have not moved.** The source fingerprint answers it for the code. For the
terms, the acceptance surface is a second hash beside `contract_sha256`. The whole-tree hash
answers "did anything move", which is the right question while a run is in flight; between
runs it is the wrong one, because a run stopped by its ceiling can only continue if the
ceiling goes up, and the ceiling lives in the same file as the goal. Refusing on the
whole-file hash would make the act of continuing the reason to discard what continuing was
meant to save. `acceptance_sha256` therefore hashes the goal, scope, commands, authority and
browser contract, and not `budgets`, `max_fix_attempts` or `timeout_minutes`. Every other file
under `.warden` still enters by its full content, a carried verdict names the budget change
that was forgiven, and rewriting an acceptance command to make a run go green still moves it.

**That the same judge, under the same rules, would be asked again.** Neither hash can answer
this: both cover the *project's* `.warden`, while the roster, the independence requirement,
the prompt, the schema and the workflow all live in the operator's own home. So an operator
could swap the reviewer roster for a different vendor with different rules, continue the run,
and have the new reviewer's stage satisfied by the old reviewer's verdict — the stage name
matched, and a stage name is a label. Each role dispatch therefore records a `role_contract`
on its step row: role, roster, strategy, independence, profile, vendor, model, effort,
`read_only`, the prompt template and schema digests, and the stage's own routing. A resume
rebuilds it from the current configuration and declines any stage whose terms moved, naming
which one in `reuse_declined_by_stage`. The comparison is against the stage as the workflow
declares it *now*, not against the role the old row happened to name: a stage reassigned from
`reviewer` to `architect` under a stable name is a different job, and its verdict does not
carry — the recorded profile cannot even fill the new role.

Declines are per stage, never global: a changed reviewer says nothing about whether the
implementer's own stage still stands. A summary that predates the contract declines
conservatively, because "no evidence" and "no change" are not the same answer.

**That the verdict is about the candidate it is being spent on, at the moment it is spent.**
The run-level fingerprint proves the prior run's *final* tree matches; it does not prove an
individual stage judged that final tree, nor that the tree has not moved since the resume's
pool was built. Two ways it can drift: a stage that passed an earlier revision inside a run a
later repair changed (its verdict was never about the final tree), and an ordinary implementer
re-dispatch during the resume itself (which moves the tree out from under a later review). So
every role dispatch stamps its step row with the fingerprint of the tree it judged, and a
carried verdict is checked twice — once when the pool is built, and again at the moment it is
consumed, against the tree as it then stands. A prior run that already retired a verdict
(`stages_judging_an_earlier_candidate`) has it declined outright, and a fix-round dispatch is
never an independent reuse candidate: it is an intermediate step in some stage's repair, not a
stage verdict of its own.

Reuse is also a verdict, not an absence of one. A carried judgement restores its coverage
marked `source: reused` with the run it came from and the fingerprint it originally judged —
without that, a run whose every stage was carried reported `candidate_review_passed: false`
about a candidate a reviewer had passed. And `reused_judgements` names what was actually
consumed, recorded as each verdict is spent rather than when the pool is built, because a
verdict that looked reusable at the start can be invalidated before its turn.

## What a finding is, and how a round knows it has seen one before

A finding used to be prose with a severity on it. That is enough to hand back to an
implementer once, and not enough for the question the second round asks: is this the defect we
already tried to fix, or a different one. Two reports of one defect were two unrelated blobs of
text, so nothing could say whether a repair had closed anything.

Each finding now carries an identity and a category, both recorded with their provenance:

- **`id` and `id_source`.** A vendor may name a finding; Warden checks the name. When it does
  not, the id is derived from the path and the normalised message — case folded, whitespace
  collapsed, trailing punctuation dropped — so reformatting alone does not mint a new finding.
  `derived` and `vendor` are different strengths of claim and the report says which it has.
- **`category` and `category_source`.** One of `product_defect`, `investigation_evidence_gap`,
  `access_required`, `contract_gap`, `tooling_failure`, `quota_exhausted`,
  `provider_unavailable`, `review_disagreement`. The model proposes and Warden validates: a
  category outside the set becomes `product_defect` marked `defaulted_unrecognised`, never an
  invented one. The distinction that matters is between a defect in the work and everything
  else, because only the first is something handing the diff back can repair. A P1 whose
  category is not `product_defect` still blocks acceptance, but it stops the run for a person
  instead of buying another implementer call.

Both fields are optional in the shipped schema, so a reviewer that supplies neither still
validates and still gets a usable identity.

`finding_history` records one row per judging round per stage: what it found, the tree it
judged, and — from the second round on — which ids `closed`, which `persisted`, which are
`new_findings`, and whether the candidate moved at all.

**What this identity does not do.** It matches a re-report that keeps the same path and
substantially the same wording. A genuine reword by a different vendor mints a new id, and two
distinct defects on one path with near-identical messages collide. That is exactly why the
loop's progress signal does not rest on finding text alone.

## When a repair round is not repairing anything

`repair_made_no_progress` stops a loop that is paying to rediscover the same objection. Both
halves of the condition are required, and the plan's wording is the reason:

- the same confirmed blockers came back, **and**
- the evidence they are about did not move — the candidate fingerprint is unchanged.

Either alone is a bad signal. A repair that changes the tree and still leaves the finding open
may have closed half of it, and stopping would throw away real progress. A different finding on
an unchanged tree means the reviewer looked somewhere new. Together they mean the round bought
nothing and the next would buy the same.

It is checked *after* the objecting stage re-reads, not straight after the fix. Stopping the
moment a repair writes nothing would be cheaper and wrong: the stage that objected has not
spoken yet, and it is the only thing that can say whether the objection still stands.

Measured live on 2026-09-07 (`docs/LIVE-CYCLE.md` section 8): a repair cost $0.031 and changed
no bytes, and the round then spent $1.46 for one reviewer to reach the same pass and another
the same fail. Nothing compared the tree before the repair with the tree after it.

The implementer is told this in its fix context: a finding it cannot act on should be answered
in its summary rather than with a token edit, because a repair that leaves the candidate
byte-for-byte unchanged ends the run rather than buying another reading of the same bytes.

## What a repair and a re-reading are told

A fix round used to be briefed with the failure text and nothing else, which made every round
after the first worse informed than the first. And a reviewer was told nothing at all, so it
re-investigated from scratch each time and its findings drifted — which is also why two rounds
could not be compared.

**The repair package** carries the goal quoted from the contract rather than summarised, the
non-goals, anything an earlier round already closed (so it stays closed), what the previous
repair reported doing and whether the tree agreed, the failing findings with their ids and
categories, the blast radius and authority it may not exceed, and how much room is left. It
also says plainly that a finding it cannot act on should be answered in the summary rather than
with a token edit, because a repair that changes no bytes ends the run.

**The reviewer package** is given only to a stage re-reading a candidate it has seen before. It
carries the findings that stage filed last time with their ids, the implementer's account of
what it did, and — separately, because the two can disagree — whether the candidate actually
moved. It asks two questions in order: is each finding closed, judged against the code rather
than against the account of it; and did the repair break something that was working. It says to
reuse ids for surviving objections, not to lower a severity or drop a P1 to let a run pass, and
that the whole investigation must happen again when the scope, the acceptance commands or the
contract have changed, or when the earlier verdict rested on an observation that can go stale.

Proven live on 2026-09-08 (`docs/LIVE-CYCLE.md` section 9): Warden derived an id for a finding
the reviewer had not named, handed it back in the recheck package, and the reviewer reused it on
its second reading — `id_source` moving from `derived` to `vendor` — while rewording the message
around it.

## Refusing a pass that was bought by relabelling or by dropping a finding

A reviewer can end a run by calling its own P1 a P2, or by omitting it from the next report. On a
candidate that changed, that may be an honest reconsideration in the light of a repair. On a
candidate that did not change, nothing new was learned about the code, and the only thing that
moved is the report between the run and the human gate. `severity_downgraded_without_change`
refuses both paths — not by overruling the reviewer's judgement of the defect, but by declining
to treat the relabelling or the retraction as a pass, even when another blocker is still open
in the same round, and even on a stage's first reading after `--continue`. Restoring the history
is what makes that comparison possible; the controller then has to consult it, or a resume after
a budget stop can drop a standing P1 on an unchanged candidate and reach the human gate as a
pass. A throwaway P1 kept alive for one reading is not a reason to let the original drop
through. A reviewer that believes the P1 was wrong should say so and why, which is a
`review_disagreement` for a person to settle on the evidence.

## What the summary says, as three answers rather than one

`ok: false` used to mean, indiscriminately, that the investigation found nothing, that a
reviewer rejected the candidate, and that the chain ran out of room. Measured: a live run made
three reviews, the last of them a clean pass, left a good candidate in the worktree, and
reported `budget_exhausted` and nothing else.

- `candidate_review_passed` — did the judging stages that ran pass it, with no blocking finding
  left open. Never true by default: a chain whose reviews were all skipped or never reached has
  not passed review, it has not had one.
- `workflow_incomplete` and `pending_stages` — did the declared chain finish, and which stages
  it still owes. Three states, not one: `did_not_pass` has evidence to read,
  `not_reached` has a ceiling to raise, and `judged_an_earlier_candidate` holds a verdict about
  a tree a later repair replaced. A fix round drops every judging stage from
  `completed_stages`, and only passing again earns the place back — otherwise a reviewer that
  passed, was rechecked after a browser repair and came back with a P1 stayed listed as a
  stage that had passed.
- `safe_next_step` — the one command that moves the run forward without paying twice.

Neither of the first two implies the other, and a run can legitimately be a pass on one and a
no on the other.

## What Orca shows during a run

Warden calls Orca; Orca never calls Warden. Everything below is something Warden asks for,
which is why closing any of it leaves the loop running.

| Surface | What it says | Written by |
|---|---|---|
| Workspace card comment | `[1/5] implement · implementer (grok-implement / grok) · running · 12m00s` — position, stage, role, the profile and vendor filling it, and the clock while the stage is still going. A stage that runs no vendor (`gates`, `browser harness`) keeps the bare name and grows no brackets | `Workspace.note` at each stage boundary (rewritten with the resolved pair as soon as RoleRunner chooses it), `Workspace.working` once a minute in between |
| Workspace status column | `in-progress` / `in-review` / `completed` — three columns, because the card answers one question: is this waiting for me | `Workspace.state` |
| Terminal tab (`--watch` only) | `warden <run-id> · implementer (grok-implement) 12m00s` while a role runs, `warden <run-id> - NEEDS YOU` when the loop stops for a person. The tab is short: profile, not vendor | `terminal create`, then `terminal rename` on every beat and every state change |
| Terminal contents (`--watch` only) | The narration, followed live from `.warden/runs/<id>/narration.log`, including `... 12m00s   implementer (grok-implement / grok) still working` once a minute | `Progress.toFile`, tailed by the script Warden writes beside it |
| Terminal tab, the compiled contract | `warden <run-id> contract`, opened whenever a planner compiled one, and not gated on `--watch`. A planner turns one sentence into the document every later verdict is measured against, and the operator sees it before a writer is dispatched rather than as a path in a terminal they may not have been reading. With `--draft-only` the card also moves to the waiting column, because the next move is a person's | `Workspace.show` from `DoCommand`, printing the contract once |

Both windows read the file as UTF-8 and set the console's output encoding to match. Warden
writes the Windows helper scripts with a UTF-8 BOM so PowerShell 5.1 also decodes
non-ASCII paths inside the script correctly. The contract helper lives under
`.warden/runs/<id>/show-contract.ps1` (or `.sh`), never beside the versioned task.
Dry runs do not open a contract window or move its card to waiting. Showing a contract
does not itself wait for approval; use `--draft-only` to stop before execution.
Warden writes its narration as UTF-8 and Windows PowerShell decodes with the ANSI code page unless it
is told otherwise, so a line like "3 blocking finding(s) from reviewer" with an em dash in it
reached the operator as mojibake in the window Warden had just opened for them.

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

## Cumulative finding lifecycle (P1 follow-up, 2026-09-08)

Each judging round stores a `finding_registry` and `closed_ids`. Round deltas
(`closed` / `persisted` / `new_findings`, retractions, relabels) stay per stage: one
reviewer omitting another's finding is not a closure. The registry is the run's
lifecycle, and every dispatch is handed it as it stands: not only a later stage's
first reading, but a stage rechecked after another stage closed something.
`recorded_at_stage` names who filed each id, so a closed requirement cannot
re-enter as a new one on the original evidence, from any stage on any round.
Ownership decides closure as well as provenance: omission closes a record only
when the stage omitting it is the stage that filed it, because one reviewer's
silence is not another reviewer's closure and an invented closure would arm the
stale-evidence rule against a blocker nobody had filed twice.
Both repair and recheck packages include the cumulative
registry; a second independent reviewer receives it on first dispatch, together
with the repair receipt. An explicit continuation restores that history when task
and acceptance still match, including budget-only changes. Historical findings
are context, not reused verdicts. Legacy history is replayed stage by stage;
absent evidence remains absent. Provenance is taken from the stored row: a derived
id is not restamped as vendor-named just because the recorded map now carries an
id string.

Reviewer fields `evidence_refs`, `supersedes` (arrays of strings) and `scope_relation` are
optional for old artifacts. Status is controller-owned: open, closed, or reopened.
Duplicate IDs, unknown/self supersedes links, and reopening without a new evidence reference
stop with `finding_protocol_failure` before another repair or human-success gate.
After any closure, a new-style artifact that names `evidence_refs` or `supersedes` must
produce new evidence for a fresh P1, even without a supersedes link; changing wording/ID
therefore cannot silently bypass the evidence requirement. "New" is relative to every
reference already in the registry, not only the same id. A later report that replaces
`evidence_refs` does not make an earlier receipt new: the registry keeps
`seen_evidence_refs` across incarnations, and the guard consults historical rounds, not
only the latest record. A legacy artifact that never spoke those fields cannot be checked
that way: the round records `unverified_invariants` and the run continues. Reopening a
known id still requires evidence regardless of vintage.
Evidence quality remains a reviewer obligation; external observation TTL remains later work.

The joined report includes protocol failures, the last repair receipt and severity laundering.
Successful human-gate summaries include an explicit approve command. Existing user prompts
and schemas are never overwritten by setup; adopt the new optional fields deliberately.
