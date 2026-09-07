# Reproducible cross-project smoke

The narrow, repeatable proof that the machine boundary holds across a project Warden has never
seen:

```text
Orca tracked implementer -> target worktree -> Warden validate/gates -> evidence ledger
                         -> Conductor machine nodes -> fail-closed human gate
```

The smoke uses a throwaway Git repository — not Warden itself, and not a project you care about.

This document is the **cross-project boundary** smoke and predates the full loop. For the loop
end to end with live vendors, read [`LIVE-CYCLE.md`](LIVE-CYCLE.md); for a version that needs
no vendor, no key and no network at all, run [`examples/demo/run.sh`](../examples/demo/README.md).
The dated sections below are kept as they were written, because a transcript that gets edited
after the fact stops being evidence.

## 1. Prerequisites

Build Warden once:

```text
cd <warden>
build.cmd
test.cmd
```

If Orca and your vendor CLI run under different local identities on Windows, add only the
specific repository you intend Orca to use:

```text
git config --global --add safe.directory C:/absolute/path/to/project
```

Never use `safe.directory=*`.

## 2. Target contract

The verified fixture is a clean Git repository with this task:

```yaml
# .warden/project.yaml
version: 1
project: "warden-e2e-smoke"
base_ref: "main"
checks:
  smoke: ["verify.cmd"]
scopes:
  outcome: ["result.txt"]
defaults:
  checks: smoke
  risk: low
  max_fix_attempts: 1
  timeout_minutes: 2
```

```yaml
# .warden/tasks/create-result.yaml
version: 1
id: create-result
goal: Create result.txt containing exactly one line WARDEN_OK
non_goals:
  - "Do not modify verification or Warden contract files"
risk: low
scope: outcome
checks: smoke
authority:
  workspace_write: true
  network: false
  land: false
visual_qa:
  required: false
  scenarios: []
budgets:
  max_role_runs: 3
  max_cost_usd: 2.0
max_fix_attempts: 1
timeout_minutes: 2
```

Commit the contract and checker while deliberately leaving `result.txt` absent.

## 3. Red proof before spending a model call

Run from the target repository:

```text
<warden>\bin\warden.cmd validate create-result
<warden>\bin\warden.cmd gates create-result --run-id smoke-red
<warden>\bin\warden.cmd ledger
```

Expected: validation succeeds, gates return `command_failed`, and ledger contains one failed
run. A missing report is a test failure.

## 4. Orca tracked implementation

Register the target repository, create a task and dispatch it to an agent in a new worktree.
The exact IDs are returned by Orca and must be copied, never guessed:

```text
orca repo add --path C:\absolute\path\to\target --json
orca orchestration run-create --objective "Warden cross-project smoke" --json
orca orchestration task-create --spec "Create result.txt with exactly WARDEN_OK; change nothing else; run verify.cmd; send worker_done once" --json
orca worktree create --repo name:warden-e2e-smoke --name warden-e2e-create-result --no-parent --base-branch main --agent codex --setup run --json
orca terminal wait --terminal <handle> --for tui-idle --timeout-ms 60000 --json
orca orchestration dispatch --task <task-id> --to <handle> --from <coordinator-handle> --inject --json
```

Completion is accepted only when `dispatch-show` is settled and the coordinator receives a
`worker_done` for the same task and dispatch. Terminal text alone is not completion evidence.

## 5. Green proof

Run from the Orca task worktree:

```text
<warden>\bin\warden.cmd gates create-result --run-id smoke-green
<warden>\bin\warden.cmd ledger
git status --short
```

Expected: `code: passed`, ledger verdict `passed`, and `result.txt` is the only changed path.
The report must contain the merge base, exact command exit code, fingerprint and ignored-path
disclosure.

## 6. Conductor boundary

Conductor needs permission to write its own local checkpoint/state. On Windows set UTF-8, then
run with the target worktree as `project_dir`:

```text
$env:PYTHONUTF8='1'
conductor run <warden>\conductor\gates.yaml `
  --skip-gates --no-interactive `
  --input task=create-result `
  --input run_id=conductor-smoke `
  --input project_dir=C:\absolute\path\to\target-worktree
```

`--skip-gates` selects the first human-gate option. Warden deliberately puts `Reject` first,
so automation must finish failed **after** both machine nodes pass. A success here would be a
fail-open defect.

## Verified 2026-08-26

- Warden baseline: `fc54be7`; follow-up hardening was still uncommitted during the run.
- JVM checks: 155 passed after blocker fixes.
- Orca runtime: 1.4.188; UI showed separate Warden, Claude, Grok and smoke worktrees.
- Codex implementation dispatch: completed, only `result.txt` modified.
- Claude review dispatch: completed with `worker_done`.
- Grok review produced an artifact, but its dispatch capability was revoked after
  `agent_prompt_stalled`; the task is recorded failed rather than relabelled successful.
- Warden: `smoke-green` passed.
- Conductor: validate and machine gates passed; automated human gate selected Reject and the
  workflow terminated failed as designed.

## Verified 2026-08-27 — vendor quota failover

A second, smaller fixture, run because the Codex account's quota was genuinely spent and that
is not a state worth simulating when it is available for free. Reviewer role, two profiles,
Codex first on purpose:

- `warden role reviewer --dry-run` resolved `codex-review`; nothing spent.
- With an inline `{{prompt}}`, the run was refused before dispatch as
  `role_prompt_undeliverable`: a 3660-character argument through an npm `.cmd` shim would have
  reached the vendor as one line with `--json` stripped off. Measured, not assumed.
- With `prompt_delivery: stdin`, Codex was dispatched, refused with
  `role_quota_exhausted` (`detected_by: structured_error_event`, message kept verbatim), and
  the role failed over to `grok-review`, which completed a real review in 139s for $0.026.
- Both transcripts survive: `raw/reviewer.stdout.txt` and `raw/reviewer.attempt-2.stdout.txt`.
- The reviewer returned `verdict: fail` with one P1 against the fixture — `result.txt` was
  written with LF, and the gate's `findstr /x` needs CRLF. Confirmed independently:
  `warden gates` returned `command_failed` on the same tree. The finding was correct.

Two defects in Warden itself were found by this run and fixed: `where.exe` resolution picked
the extensionless npm shim that Java cannot start, and a failed run's report quoted only
stderr while the vendor's own explanation was on stdout.

## Verified 2026-08-27 — Direct CLI loop on cheap models

Throwaway Git repositories under `%LOCALAPPDATA%\\Temp\\warden-e2e-*`, configuration in a
separate `WARDEN_CONFIG_HOME` so the operator's `~/.warden` was not overwritten. Models were
lowered only in that test home: Claude `--model sonnet` (resolved `claude-sonnet-5`), Codex
`-m gpt-5.4-mini`.

- Red gates on a fixture without `result.txt`: `command_failed`, report written.
- Reviewer `warden role reviewer` via Direct CLI (`claude.exe`, argv prompt, `--allowedTools
  Read,Grep,Glob`): 78s, $0.14, fingerprint matched, schema accepted, `verdict: fail`. The P1
  was correct — an `if exist result.txt` check passes for any content. Artifact and raw
  transcript kept.
- Implementer `warden run` (low risk, no review) via Direct CLI (`codex.cmd`, stdin,
  `--approve-for-me`): first attempt exited 2 in 177ms because `--sandbox workspace-write`
  cannot combine with `--approve-for-me` (stderr kept). Retry without `--sandbox` completed
  in 106s, wrote `result.txt` as `WARDEN_OK\\r\\n`, gates passed, `next_action: human_gate`.
  Nothing was merged. Codex `--json` is JSONL; the role artifact is recovered from
  `item.completed` / `agent_message`, not from the trailing `turn.completed` usage event.

Warden is the source of truth for its own behaviour, and it does not create worktrees itself.


## What these smokes do and do not prove

What they prove is narrower and more useful than "it works": the pieces compose, and their
failure boundaries are observable from the outside. Every failure recorded above was found by
reading a report, not by inferring it from behaviour.

What they do **not** cover has since been run elsewhere. The combined loop — a writing
implementer, machine gates, an independent reviewer on a second vendor, the browser harness
and the `visual_qa` role, with a fix round ordered by the role with eyes — was run to a green
human gate on a real project; that transcript is [`LIVE-CYCLE.md`](LIVE-CYCLE.md), not this
file.

Still not proven anywhere, and refused rather than faked:

- visual QA pixel-diff and baseline comparison (not implemented);
- tamper-evident external ledger storage;
- automatic landing — which is not a gap but a decision. Warden never merges.

### Attempted 2026-09-03 — `runner: orca` reviewer, Claude `opus`

Throwaway repo `warden-orca-review-smoke`, Orca worktree
`w-orca-claude-review`, isolated `WARDEN_CONFIG_HOME` (operator `~/.warden` not
written). Codex session quota was 100%; the reviewer profile was Claude
`opus` (Opus 5 alias) on `runner: orca`, not a failover from Codex.

- `--dry-run` resolved `orca-claude-review` / `runner: orca` / `model: opus` and
  wrote the prompt. No `raw/`.
- Live `warden role reviewer review-smoke --run-id orca-claude-live` (39s):
  Orca 1.4.196 created a **visible** agent terminal (`surface: visible`,
  `agentIdentity: claude`, dispatch `ctx_731cacb05fa5`). That is the Agents /
  Dashboard row.
- Claude Code printed the folder-trust / “Quick safety check” screen and did
  not become a ready TUI. Orca settled `worker-start` as `ok: true` with
  `state: failed`, `lastError: agent_prompt_stalled`. Warden mapped that to
  `role_orca_start_failed`, `worker_stop_ok: true` (already settled,
  `processAction: none`), closed the coordinator, one `vendor_attempts` row,
  $0. Not `worker_done`. Direct CLI remains the default.

The leftover Claude tab was closed after the run. The failed dispatch remains
`reclaimable` in `worker-list`; it is not a live worker.

Same day, the **loop** (`warden run review-smoke --run-id orca-loop-1`), not a
lone `warden role`:

- Direct CLI implementer (`grok-implement`, grok-4.6, 54.6s, $0.013): worktree
  card said `implement · implementer (grok-implement / grok) · running`.
  `orca terminal list` on that worktree had **no** `agentIdentity` — Grok did
  not appear in Agents.
- Machine gates passed (`java tools/Verify.java`).
- Orca-hosted reviewer (`orca-claude-review`, claude/opus): Warden called
  `orchestration worker-start` (not a human `orca --agent`). A second visible
  Claude terminal appeared (`agentIdentity: claude`, dispatch `ctx_30af11a51417`)
  and stalled the same way (`agent_prompt_stalled` → `role_orca_start_failed`,
  39.9s). Card: `stopped: reviewer_failed · 2 call(s) · $0.0130`.
  `decision.json` pending `retry|abort`. Still not `worker_done`.

### Proven 2026-09-04 — `runner: orca` reviewer in Agent Dashboard

The same throwaway repository was opened in a fresh Orca worktree,
`w-dashboard-proof-20260904`, with the isolated `WARDEN_CONFIG_HOME`. Claude's one-time folder
trust screen was accepted before the measured run; without that preflight Orca correctly
reported `agent_prompt_stalled` and Warden failed `role_orca_start_failed` rather than claiming
an agent was ready.

`warden role reviewer review-smoke --run-id dashboard-proof-green2-20260904` then completed in
148.1s with Claude `opus` (the installed Claude Code 2.1.259 UI identified it as Opus 5 with
high effort) on Orca 1.4.196:

- run `run_2656a32b63ea`, task `task_367b76c01219`, dispatch `ctx_4571f80b7920`;
- while Warden was running, the desktop Agent Dashboard showed `1 total`, `WORKING 1`,
  `NEEDS YOU 0`, `DONE 0`, the exact smoke worktree, and live activity including
  `java tools/Verify.java`;
- the archived Orca transcript contains the reviewer's short progress summaries, file reads,
  tool calls, and the gate result `PASS: result.txt contains WARDEN_OK` (exit 0). It does not
  expose private chain-of-thought;
- message `msg_d4642fd42a65` delivered an explicit `outcome: succeeded` and the typed
  `warden_artifact`. Orca 1.4 represented its payload as a JSON string, which the adapter now
  normalizes while still checking the nested task and dispatch IDs;
- Warden returned `ok`, `settlement: completed`, `worker_done_succeeded`, accepted the typed
  artifact, matched the read-only content fingerprint, acknowledged delivery
  `delivery_9058ec7506fc`, and released the worker;
- `worker-show` ended at `dispatchStatus: completed`, `workerState: succeeded`, terminal
  resource `released`, transcript archive `captured`; the desktop dashboard returned to
  `0 total` / `WORKING 0`.

The fixture's reviewer also reported a P2 in `tools/Verify.java`: `strip()` makes this tiny
smoke gate tolerate surrounding blank lines. That does not invalidate the runner lifecycle
proof—the gate reads the declared project file and fails on wrong non-whitespace content—but
the fixture should not be mistaken for a production-strength acceptance suite. Mobile display
and resolving a Warden human decision from Orca Mobile were explicitly left for a later smoke.


### Proven 2026-09-04 — human control and a recoverable Orca lifecycle

Same throwaway repository and isolated `WARDEN_CONFIG_HOME`, in the already-trusted Orca
worktree `w-dashboard-proof-20260904` on Orca 1.4.196. The reviewer profile stays Claude
`opus` (Opus 5) on `runner: orca`. Every line below is a command that ran, not a design note.

**Warden was killed while its worker was running, and did not start a second one.**
`warden role reviewer review-smoke --run-id restart-1-20260904` recorded run `run_eaace88c90d2`,
task `task_907905028954` and dispatch `ctx_0ea77dc4fd6d` in
`.warden/runs/restart-1-20260904/orca.json` before the agent was ready. The Java process was
then killed; `worker-show` still reported `dispatched` and `worker-list` still showed one
active terminal. The same command run again attached: `resumed: true`, recovery verdict
`resume` / `dispatch_in_flight`, `coordinator_reused: true`, and 84s later
`worker_done_succeeded`, `worker_release_ok`, `delivery_accounted`. One dispatch, one agent,
one settlement.

**A second run refused to start beside it.** While that worker was live,
`warden role reviewer review-smoke --run-id contend-1-20260904` failed in 2.7s with
`role_orca_worker_active`, naming `ctx_0ea77dc4fd6d` and how to fence it. Nothing was dispatched
and nothing was spent.

**The read-only window survives the restart.** The fingerprint recorded at `worker-start` is
what the resumed process compares against, so the evidence reads
`read_only_check.since: worker_start` rather than measuring only from the attach. A file
written before the kill would still be a violation.

**A timeout leaves nothing running.** With the same profile at `wall_clock_minutes: 1`,
`timeout-3-20260904` ended after 63.9s with `role_orca_timeout`. Orca refused the fence with
`dispatch_inactive` — it does not stop what is already stopped — so Warden verified the claim
instead of trusting it: `worker-show` reported the dispatch `failed`, stage `process_exited`,
`observation.status: exited`, and `worker-list` showed zero in flight. The record marks the
worker `stopped`, so no later run tries to attach to it. Two earlier attempts,
`timeout-1` and `timeout-2`, are why the verification exists: they reported
`role_orca_lifecycle_unaccounted` over a worker that was provably gone.

**A read-only role that writes is still refused after a successful settlement.** A profile
deliberately declared `read_only: true` behind the implementer prompt was pointed at task
`note-smoke`, whose goal is a new file. The Orca-hosted agent created `agent-note.txt`, sent
`worker_done` with `outcome: succeeded`, and Warden released the worker, acknowledged the
delivery, and then failed the role `role_violated_read_only` on the moved fingerprint
(`readonly-2-20260904`). A clean settlement does not buy a pass.

An earlier attempt at the same proof failed for the right reason and is worth recording:
against a goal that contradicted its own gate, Claude Opus 5 changed nothing, reported
`worker_done` BLOCKED, and named both the contradiction and the mislabelled dispatch.

**A question from the agent, an answer from a person, and the same worker finished the job.**
Profile `orca-claude-asks` requires one blocking `orchestration ask` before any review work.
`question-1-20260904` returned `role_human_input_required` in 27s naming message
`msg_02d426898eaf`, retained the coordinator, and recorded the worker `active` /
`awaiting: human` with that message id. A person answered from a separate Orca terminal
(`run-use`, then `orchestration reply --id msg_02d426898eaf`). Running the same role again
attached to `ctx_56b8bbf08bf8`, reused the coordinator, and returned `ok` with
`verdict: pass`; the reviewer's own summary quotes the answer it was given. The worker was
released and the delivery acknowledged.

That last step needed two fixes found by running it. Warden was not acknowledging the question
delivery, so the FIFO replayed the same question to every later attach and an answered worker
could never be collected; and a delivery carrying both a question and a `worker_done` read the
question first. Completion now outranks a question in the same delivery, and a question is
acknowledged and then waited past, so an answer given while the controller is still running
lets that same process finish.

**A decision made in Orca, admitted by Warden only on Warden's terms.** `accept-gate-20260904`
ran the loop green — Grok implementer, machine gates, Claude Opus 5 reviewer on Orca — and
published gate `gate_aa4a43cdc9d5` with options `accept` and `reject`. Answering it from an
Orca terminal and running `warden approve accept-gate-20260904 --from-orca` recorded the
decision with actor `orca-gate:gate_aa4a43cdc9d5` and `lands: false`. Around that, four
refusals, each measured:

| What was tried | What Warden answered |
|---|---|
| Import before anyone answered | `gate_pending` |
| Gate answered "please retry if you can" | `gate_resolution_unmapped` |
| Accept while the candidate had moved | `candidate_changed` |
| Import a second time, and after the gate was re-answered `abort` | `stale_decision` |

The last row is the one that matters. Orca gates are editable: `gate-resolve` takes free text
rather than a declared option, and re-resolving a settled gate replaces the answer — both
measured here. The gate for `gate-a3-20260904` was changed from `retry` to `abort` after the
fact and `decision.json` still reads `retry`. The gate is a doorbell; the record is the record.

**Answering a gate needs a bound Run.** `gate-resolve` from an unbound shell fails
`run_required`. The recipe that works from any Orca terminal, desktop or mobile, is
`orca orchestration run-use --id <run>` and then
`orca orchestration gate-resolve --id <gate> --resolution accept`. Warden closes its own
coordinator after publishing, because a gate is addressed to a Run and not to a terminal.

**A Windows argv defect, found by the first publish failing.** `--options ["accept","reject"]`
arrived at `orca.exe` as `[accept,reject]`: Java quotes only arguments containing whitespace,
and a compact JSON array has none, so the receiving command line parser stripped the quotes.
Orca named it exactly — `invalid_argument`, "its quotes are missing". `OrcaClient.jsonArgument`
now escapes them, verified by sending all three spellings to the live CLI and watching which
one it accepted.

**The Agent Dashboard row is the live terminal title, and the agent overwrites it.** Warden now
renames the worker terminal at start (`worker_row_named: true`, e.g. `claude reviewer -
note-smoke`), which is what a row shows for an agent that never titles itself. Claude Code then
replaces it with its own summary within seconds — the observed titles were `◑ Warden role
reviewer for task review-smoke` and `◐ Warden reviewer for note-smoke`, both compressions of
the first sentence of the spec Warden writes. The task's `display_name` (`reviewer / claude`)
is stored by Orca and does not appear in the row. Closing that gap fully is an Orca change, not
a Warden one.

**A decision gate renders nowhere in Orca's desktop UI.** Checked by publishing a pending gate
and looking: the Agent Dashboard shows agent sessions and read `0 total` with no worker running,
and global search returns worktrees, not orchestration objects. What a person actually sees is
the worktree card Warden already writes, which the search surfaces as
`stopped: preflight_outside_scope · 0 call(s) · $0.0000 · warden approve ui-gate-2-20260904
--decision retry|abort · or orca gate gate_a4d86…`. So the run now says, in its own narration
and on that card, both the gate id and the two commands that answer it from any Orca terminal:
`orchestration run-use --id <run>` then `orchestration gate-resolve --id <gate> --resolution
<choice>`. A gate is answerable from anywhere Orca reaches; it is not visible from anywhere
Orca draws.

**Still not proven.** Orca Mobile: every gate here was answered through the Orca CLI on this
desktop, and no phone was involved. A `switch`/`abort` failover gate was never published live —
only `accept`/`reject` and `retry`/`abort`. Fault injection on acknowledgement and release is
still code and unit tests only. The Orca runner reports no cost, so its calls land in
`unpriced_calls`.

### Proven 2026-09-07 — Orca launch receipt carries the requested effort

The 2026-09-04 rows above proved an Orca-hosted reviewer starts, settles on `worker_done`, is
acknowledged and released. They did not prove what it was *started as*: the launch receipt is
newer than those runs. This row is that check, run through `warden role` so exactly one worker
is paid for.

Config home is the temporary `%LOCALAPPDATA%\Temp\warden-orca-e2e-home`; the operator's
`~/.warden` was not touched. The fixture's `orca-claude-review` had to be corrected first: its
2026-09-03 form declared `prompt_delivery: stdin`, which `runner: orca` now refuses because
Orca does not forward stdin, so the profile no longer loaded at all. Delivery was set to
`workspace_file` and `effort: max` added so the receipt would have something to verify; the
original is kept beside it as `.before-launch-receipt`.

Dry run first, spending nothing, showing what would be asked for:

```text
command_preview: orca orchestration worker-start --task <id> --worktree <cwd>
                 --agent claude --model opus --effort max
```

Live, run `orca-receipt-1` on Orca 1.4.197, `orca_run_id run_d4bddc24757d`:

```json
"launch": {
  "requested":      {"agent": "claude", "model": "opus", "effort": "max"},
  "orca_requested": {"agent": "claude", "model": "opus", "effort": "max"},
  "effective":      {"agent": "claude", "model": "opus", "effort": "max"},
  "status": "matched",
  "effort_source": "warden_profile",
  "source": "orca_launch_receipt",
  "provider_execution_verified": false
}
```

**Proved by this run.** The effort Warden asks for reaches Orca and comes back in both halves
of the receipt, so `effort_source: warden_profile` is a fact and not a hope — an Orca-hosted
Opus no longer runs at Orca's default while a profile claims otherwise. The worker started
ready, settled `worker_done_succeeded`, and was released with its transcript archived
(`processAction: closed_agent_terminal`). `read_only_check` matched on a worktree content
fingerprint taken since role start, so read-only is enforced by measurement rather than by the
profile's own declaration. The typed reviewer artifact came back `verdict: pass`, 8 minutes,
and the worktree was unchanged.

**Still not proven by it.** `provider_execution_verified` stays `false` on purpose: a launch
receipt confirms the options a worker was started with, not which model the provider actually
ran. The Orca runner reported no cost, so the call lands in `unpriced_calls` and no money
ceiling measured it. A mismatching receipt has never been seen live — `launch_model_mismatch`
and `launch_receipt_missing` remain suite-only.
