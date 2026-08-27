# Reproducible cross-project smoke

This proves the currently implemented boundary without pretending that `warden run` already
exists:

```text
Orca tracked implementer -> target worktree -> Warden validate/gates -> evidence ledger
                         -> Conductor machine nodes -> fail-closed human gate
```

The smoke uses a throwaway Git repository, not Warden and not Living Horizon.

## 1. Prerequisites

Build Warden once:

```text
cd C:\Users\anato\IdeaProjects\warden
build.cmd
test.cmd
```

On this Windows host, Orca and Codex run under different local identities. Add only the
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
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd validate create-result
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd gates create-result --run-id smoke-red
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd ledger
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
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd gates create-result --run-id smoke-green
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd ledger
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
conductor run C:\Users\anato\IdeaProjects\warden\conductor\gates.yaml `
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

JS scripts under the Living Horizon Orca worktree remain reference-only. Warden is the source
of truth and does not create worktrees.

## What this does not prove yet

Warden now launches roles and owns the bounded fix/review loop. A cheap-model `warden run`
on a throwaway repo has reached `human_gate` with a writing Codex implementer and passing
gates; an independent Claude review was run separately on another fixture. Visual QA
pixel-diff, a live `runner: orca` worker_done cycle, tamper-evident external ledger storage
and automatic LAND remain disabled.

What the smokes prove is narrower and more useful than "it works": the pieces compose, and
their failure boundaries are observable from the outside. Every failure above was found by
reading a report, not by inferring it from behaviour.
