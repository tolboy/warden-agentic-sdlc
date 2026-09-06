# Warden — specification

The tool runs AI agents by role inside someone else's repository, checks the result
mechanically, and stops in front of a human. It **never merges anything**.

This document is the reference. For an introduction read the [README](../README.md); for a
guided tour read [`docs/WALKTHROUGH.md`](../docs/WALKTHROUGH.md); for what has actually been
run live, [`docs/LIVE-CYCLE.md`](../docs/LIVE-CYCLE.md).

---

## 1. Three places, three responsibilities

The main idea, from which everything else follows:

```
~/.warden/                      WHO runs
  profiles/*.yaml               vendors: which CLI, which flags, which limits
  policy.yaml                   role → profiles, rotation, independence

<any project>/.warden/          WHAT "done" means for this project
  project.yaml                  check commands, scopes, defaults
  tasks/*.yaml                  tasks
  runs/                         the evidence base (gitignored)

warden (installed once)         HOW: gates, roles, the loop, the ledger
```

**A vendor is a property of whoever runs, not of the project.** You have SuperGrok and Codex;
a colleague has Gemini and a local model — and `project.yaml` is identical for both. That is
why the choice of vendor never lands in the project's Git, where it is of use to nobody but
you.

Connecting a new project = describing its check commands. That is all.

**One stage, one evidence directory.** Under `runs/`, each stage of a run writes to
`<run-id>--<name>-<attempt>/` and never edits another's. `<name>` is the role while a role has
one stage in the workflow, and the stage's own name as soon as it has more than one — so
`review` and `review-second`, which are the same role at the same attempt, are
`<run-id>--review-0/` and `<run-id>--review-second-0/`. Before that rule the second reviewer
overwrote the first's prompt, raw output and artifact, and the ledger entry for the first then
pointed at the second's files: not merely lost evidence, but a pointer that reads as if it were
the other vendor's. Two independent readers only mean something while both verdicts survive.

---

## 2. Project files

### `.warden/project.yaml` — the only mandatory file

```yaml
version: 1
project: my-app
base_ref: origin/main

# Named command sets. This is the entire surface of "what done means".
checks:
  fast: ["npm run check"]
  full: ["npm run check", "npm run build"]
  lint: ["npm run lint"]

# Named scopes, so tasks do not repeat paths
scopes:
  ui:      ["src/routes", "src/lib/components"]
  server:  ["src/lib/server"]
  harness: ["scripts", "docs"]

defaults:
  checks: full
  baseline_checks: fast          # optional; omit to keep pre-agent behaviour unchanged
  risk: medium
  max_fix_attempts: 2
  timeout_minutes: 30
```

`baseline_checks` names a set under `checks`. When present it is run in the worktree
**before the first vendor**. A red baseline is `baseline_failed` and zero vendor calls —
the agent is not asked to repair a breakage it did not cause. The same commands are the
floor of the later acceptance gate (baseline first, duplicates dropped), so a narrower
task check cannot hide a regression of project health. Existing projects that omit the
key, and a greenfield `warden init`, keep the previous behaviour: no pre-agent suite.

For a JVM project only the contents of `checks` differ:

```yaml
checks:
  fast: ["./gradlew test"]
  full: ["./gradlew build", "./gradlew detekt"]
```

### `.warden/tasks/<id>.yaml` — a task

```yaml
version: 1
id: settings-button
goal: Add a Settings button to the main menu
risk: low
scope: ui                       # a name from scopes, or an explicit list of paths
checks: fast                    # a name from checks, or an explicit list of commands
acceptance:                     # optional, appended to checks
  - "npm run test -- settings"
```

**The name-or-list rule.** A bare string is always a *name* from `project.yaml`; a list is
explicit values:

```yaml
scope: ui                     # a scope name; must be defined in scopes
scope: ["src/lib", "docs"]    # explicit paths
scope: [ui, "src/extra"]      # names and paths may be mixed inside a list

checks: fast                  # a set name; must be defined in checks
checks: ["./gradlew test"]    # an explicit command
```

Otherwise a typo in a scope name would silently become a path to a directory that does not
exist — and such a blast radius never reports anything as out of bounds, which is to say it
fails open. The tool refuses instead, and shows the available names.

The minimal valid task is four lines: `version`, `id`, `goal`, `scope`. Everything else comes
from `defaults`.

**A task with no executable acceptance criterion is not accepted.** This is not pedantry:
every known successful agent deployment works on mechanically checkable work, and the task
linter is the place where that requirement becomes mandatory rather than aspirational.

---

## 3. User files

### `~/.warden/profiles/grok-review.yaml`

```yaml
version: 1
profile: grok-review
role: reviewer
vendor: grok
command: grok
runner: direct
args:
  - "--prompt-file"
  - "{{prompt_file}}"
  - "--output-format"
  - "json"
  - "--always-approve"
  - "--reasoning-effort"
  - "high"
  - "--max-turns"
  - "12"
  - "--disable-web-search"
  - "--verbatim"
read_only: true
limits:
  wall_clock_minutes: 20
```

Established facts about a vendor's flags live here, next to the flags, rather than in separate
documentation that drifts out of sync.

The remaining profile fields exist because vendors differ in more than their flags:

```yaml
prompt_delivery: stdin    # argv (default) or stdin
quota:
  signatures: ["plan allowance spent"]   # added to the built-in ones, not replacing them
attachments:
  flag: "-i"              # repeated per file; this is how the visual_qa role sees screenshots
model: grok-4.6-build     # reaches the vendor only if args contain {{model}}
```

`attachments.flag` is what separates a tester role from a role that was sent some filenames.
Without it the screenshots only reach the prompt text; a vendor with its own read tool will
open them, a vendor without one will read the paths as prose. Warden records in the report
which of the two happened.

`prompt_delivery: stdin` is the only channel that survives a Windows `.cmd` shim: cmd.exe
truncates a multi-line argument at the first newline and silently drops every argument after
it. Warden refuses to launch that combination (`role_prompt_undeliverable`) rather than
sending the vendor a corrupted question.

```yaml
runner: direct    # direct (default) | orca | local
```

`runner` selects the adapter, not the model. `direct` runs the vendor's CLI in this process.
`orca` starts a supervised worker inside an **already existing** Orca worktree: Warden creates
no worktrees. Completion is accepted only from `worker_done` or dispatch settlement — terminal
text is not evidence. `local` POSTs an OpenAI-compatible chat completion to the profile's
`endpoint` and reads `choices[0].message.content`. A local profile cannot declare
`capabilities.vision`: the adapter sends only text. An unknown runner is rejected when the profile
is parsed.

A model already serving on this machine — Ollama, LM Studio, llama.cpp — is reached like this:

```yaml
version: 1
profile: local-gemma
role: reviewer
vendor: ollama
runner: local
endpoint: http://127.0.0.1:11434/v1/chat/completions   # absolute http/https; required
api_key_env: LOCAL_WARDEN_KEY   # the NAME of a variable, never a token. Optional
model: gemma4:e4b
read_only: true
limits:
  wall_clock_minutes: 20
```

`command` is absent on purpose: this runner starts no process, so there is no executable to
name, and the report says `dispatch_preview` (method, endpoint, model) rather than claiming an
argv that was never spawned. Every other runner still requires `command` — a local profile that
invented one would become a Direct CLI dispatch of that invention the moment the `runner` line
was deleted.

`api_key_env` is read at dispatch and never stored: not in evidence, not in the ledger, not in
the raw transcript kept beside the run, even when the server reflects the header back. Naming a
variable that is unset or blank is a configuration fault (`role_local_api_key_missing`, naming
the variable), not an unauthenticated POST that comes back as somebody else's 401.

A local answer is settled exactly as a CLI answer is — raw transcript on disk, required fields,
JSON schema, a reported `blocked`/`failed`/`aborted` refused, and the worktree fingerprint
compared around the call. Tokens are recorded from `usage` when the server reports them; a cost
is never invented, so an unpriced call is counted as unpriced rather than as free.

### `~/.warden/policy.yaml`

```yaml
version: 1
roles:
  implementer:
    profiles: [codex-implement]
    strategy: first
  reviewer:
    profiles: [grok-review, claude-review]
    strategy: rotate
    require_independent_vendor: true
  visual_qa:                          # optional; off by default
    profiles: [codex-visual-qa]
    strategy: first
review:
  required_for_risk: [medium, high]
```

The `visual_qa` role runs only if it is declared here **and** the task asks for
`visual_qa.required: true`. The browser harness works independently of it: the harness is the
floor, not a layer on top. The role adds what a machine cannot measure and costs a vendor call
— so the operator enables it, rather than the loop deciding for them.

**`strategy: rotate` means two different things, decided by the workflow rather than by a
counter.** A role dispatched by exactly one stage rotates *across runs*: the position advances
each time the role is invoked, so two implementers take turns run to run. A role dispatched by
**several** stages — `review` and `review-second` below are the shape this exists for — is
spread across those stages instead, in the order `profiles:` lists them, on every run. The
first review stage always gets the first profile.

That second rule is not a refinement. The position used to come from a counter shared by every
run in the project, which meant a run that died before reaching its reviewer, a fix round, or a
bare `warden role` all shifted the pairing: an operator who wrote "the deep reader goes first"
got whichever profile the arithmetic landed on. Rotation state for a paired role is now derived
from `policy.yaml` and nothing else, so a failure cannot reorder it.

`require_independent_vendor` is a hard constraint, not a preference: a reviewer may not share a
vendor with the implementer. If there is no independent vendor the resolver **fails** rather
than quietly handing the code to its own author for review. This is the only defensible
justification for multi-vendor: a model reviewing its own output shares that output's blind
spots.

The optional `workflow:` block declares the order of stages, what each runs
(`role` / `machine_gates` / `visual_harness`), the closed set of conditions gating it, and
where a failure or a finding routes. Omitting it runs the built-in chain. See the README
section *The chain of model calls*.

The optional `failover:` block declares what happens when a vendor reports a spent
subscription: `on_quota_exhausted: confirm` (default) | `auto` | `stop`.

---

## 4. Commands

```
warden setup                create a starter ~/.warden; never overwrites
warden profiles             which profiles load, which are eligible, and why not
warden profiles --verify N  run profile N's own probe; stamps verified_on when it passes
warden init                 create .warden/project.yaml, inferring checks from package.json,
                            build.gradle(.kts), pom.xml, Cargo.toml or Makefile
warden doctor               what is installed, authenticated, and misconfigured
warden do "<goal>"          the whole workflow: isolation (Orca), a task, the loop; stops
                            before a human. --project DIR --scope NAME --in-place --dry-run
                            --init-repo --draft-only --goal-file FILE --quiet
warden validate <task>      check project.yaml, the task, the profiles and the policy
warden gates <task>         preflight and machine gates, no vendors and no cost
warden visual-qa <task>     start the preview, screenshot, assert control visibility
warden role <role> <task>   one role; --dry-run spends nothing
warden run <task>           the whole loop; stops before a human
                            --continue <run-id> carries a recorded decision forward
                            --no-orca-gate does not mirror the pending decision
                            into Orca
warden ledger               a table of runs: verdicts, cost, time, findings
warden report <run-id>      one run joined: stages, vendors, cost, tokens, screenshots,
                            changed files, the human decision. --text for the table
warden status [run-id]      pending and resolved human decisions
                            --worktrees lists pending decisions across every
                            worktree of this repository
warden approve <run-id>     record a decision; never lands changes.
                            Must be run from the worktree the run lives in.
                            Outside a Warden project the code is
                            `not_a_warden_project`, not `unknown_run`.
                            --from-orca takes the answer from the Orca decision
                            gate the run published, so a decision can be made
                            from another machine or a phone
warden land <run-id>        plan (and with --commit/--push/--pull-request, carry out) the
                            commit and request for an accepted run. Merges nothing
```

`warden do` is the command an operator runs. Everything else is a piece of that loop. A goal
plus a project; the blast radius is not guessed (`--scope`, or the single scope in
`project.yaml`). Isolation is `orca worktree create`; Warden never calls `git branch` itself.
`--in-place` is for a throwaway repository without Orca. Nothing is merged.

`warden doctor` is the answer to "I cannot tell whether the machine half works on my box". It
checks Java, the project contract, profile and policy loading, and Orca availability
separately, and reports `argument_encoding`.

`warden gates` costs nothing: it is how you confirm a project is connected at all without
starting a single agent.

`warden profiles --verify` runs the profile's own `verification.probe`, saves the transcript
to `~/.warden/verification/`, prints `what_to_check`, and stamps `verified_on` when the probe
passes. The field records a fact the tool can check — this probe ran on this date and passed —
which is what the resolver needs: nothing is dispatched whose flags have never been executed.

It was once described as a human judgement, that somebody had read the transcript. Nothing
enforced that and nothing could, and a stated guarantee the tool cannot hold is worse than a
plain fact. Read `what_to_check` anyway: it is where an operator learns which envelope key
carries the answer and whether a cost is reported at all. What protects the repository is
invariant 6, not this date.

`do` and `run` narrate their stages on stderr while they work; stdout stays a single JSON
object. `--quiet` turns the narration off.

---

## 5. The loop

```
preflight → implement → machine gates → [fix ≤ N] → independent review → [fix ≤ N]
          → browser harness → [fix ≤ N] → visual role → [fix ≤ N] → human
```

Properties that are there on purpose:

1. **Nothing is merged.** The last action is writing a summary and a verdict of `human_gate`
   or `human_escalation`. Acceptance is the only step that treats an agent's output as
   trusted.
2. **Routing is code.** Whether to repeat a fix, whether review is needed, which vendor fills
   a role — decided by counters and exit codes. No model is asked about any of it.
3. **Every attempt leaves evidence.** The exact prompt sent, the raw vendor output, the
   machine report, and the text the implementer received when the work came back.
4. **The tester has eyes, and they come in two layers.** The lower layer is the harness: a real
   headless browser over CDP that locates an element by `text=` / `css=` / `testid=` /
   `role=`, checks visibility, the reaction to a click and the absence of console errors, and
   saves a screenshot. It knows nothing about any particular project. The upper layer is the
   `visual_qa` role: a model that receives the screenshots as **attachments** rather than
   filenames (`attachments.flag`, `-i` for codex), and answers only what a machine cannot
   measure — clipped text, overlap, a collapsed layout. The role is enabled in the policy and
   is off by default: the harness is the floor, and paying for a look is the operator's call.
5. **A visual failure returns to the implementer**, like a failed gate or review, under the
   same attempt limit. `visual_qa_unavailable` and `visual_qa_port_occupied` are the
   exceptions: an implementer can fix neither a missing browser nor somebody else's server on
   the port.
6. **A spent subscription is not the same as failed work.** An ordinary error is correctly
   returned to the same vendor along with the failure text. An exhausted quota cannot be: the
   next call refuses identically, and the loop spends the rest of the budget establishing
   that. The role switches to the next eligible vendor, the exhausted one is excluded for the
   rest of the process, and both attempts stay in the report. The budget counts vendor calls
   rather than roles — otherwise a switch would route around the limit.

None of this is distinguishable by exit code: Codex returns `1` both for an exhausted quota
and for a typo in a flag. The decision is made from the vendor's own wording, and the evidence
records which phrase in which channel triggered it — so that a misclassification can be read
rather than inferred from behaviour.

---

## 6. How Conductor and Orca fit in

```
Warden      domain workflow, gates, safety, evidence, metrics, decisions
Orca        worktrees, agent hosting, dashboard/mobile, terminal and human interaction
Conductor   optional external workflow adapter; not part of the primary path
```

Neither Conductor nor Orca knows a vendor's name. Warden writes `decision.json`; Orca may
show the wait; Conductor may wrap the same command with an outer timeout and an external
gate. The cut is [`docs/adr/0001-layer-split.md`](../docs/adr/0001-layer-split.md).

**The Orca objects a run owns are written down.** `.warden/runs/<run-id>/orca.json` records the
Run, task, dispatch and coordinator identities before the worker is started, plus the
read-only fingerprint taken at that moment. A Warden process that is killed while a worker is
running can therefore be started again: it attaches to the same dispatch instead of opening a
second agent in the same checkout. Two guards, because they fail differently — the record
answers "is my worker still there", and an `orchestration worker-list` scan answers "does
anybody's worker hold this worktree", which is the question that still has an answer when the
record was never written. Neither may read "cannot tell" as "no": an unreadable record, an
unreachable census or a dispatch Orca will not describe is `role_orca_lifecycle_unaccounted`,
and a live worker in this worktree is `role_orca_worker_active`.

**A pending decision is mirrored to Orca as a decision gate, and the mirror decides nothing.**
The run publishes its question with Warden's own options and stores the gate id, the decision's
version token and the candidate fingerprint. `warden approve --from-orca` admits the answer
only if it maps exactly onto one of those options, only while the pending decision has not
moved, and — for an acceptance — only while the candidate fingerprint still matches. This is
not defensiveness about Orca: measured on 1.4.196, `gate-resolve` takes free text rather than a
declared option, and re-resolving a settled gate replaces the answer. Both are reasonable for a
primitive that coordinates agents, and neither may reach an authorization record. `decision.json`
stays the only decision.

A Conductor node, when one is used, looks like this:

```yaml
  - name: task_loop
    type: script
    command: warden
    args: ["run", "{{ workflow.input.task }}", "--json"]
    timeout: 7800          # strictly greater than warden's internal limit
```

**The nested-limit rule.** The outer one must be strictly greater than the inner one:

```
Conductor node timeout  >  warden wall clock  >  vendor limits (--max-turns, effort)
```

Whichever limit fires first decides whether you are left with evidence. If Conductor fires,
the process is killed, the ledger is not written and the reason is unknown. If warden fires,
you have `role_timeout`, a duration, a cost, and confirmation that the tree was not touched.

---

## 7. Prompt substitutions

Available in any role prompt template:

```
{{task_id}} {{run_id}} {{goal}} {{risk}} {{project}}
{{base_ref}} {{diff_base_commit}}
{{scope_paths}} {{checks}} {{acceptance}}
{{contract_path}} {{prompt_file}} {{schema_json}} {{schema_pretty}}
{{context}} {{context_path}}          — the machine refusal or findings on a work return
{{screenshots}}                       — absolute screenshot paths; for visual_qa
{{visual_scenarios}}                  — what the harness was told to check
{{changed_files}}                     — the paths changed since the diff base, resolved
```

`{{changed_files}}` exists because a prompt must not depend on tools the profile may not have.
The reviewer template used to name `git diff` and `git ls-files` as the way to find the diff,
and a read-only profile whose tool grant is Read/Grep/Glob cannot run either: one measured
review spent 16 of its 74 tool calls being refused for following that instruction, and then hit
its turn ceiling. Warden can always run git; the model may not be able to.

Additionally available in a profile's arguments: `{{prompt}}`, `{{prompt_file}}`,
`{{schema_json}}`, `{{repo_root}}`, `{{run_id}}`, `{{task_id}}` and `{{model}}`.

---

## 8. Invariants the tool must hold

This is what the conformance suite checks. Every item is a defect that has already been found.

1. The blast radius is computed from `git merge-base HEAD <base_ref>`, not from the branch
   tip. Otherwise, after a `git fetch`, somebody else's upstream commits get attributed to the
   task.
2. The task contract is hashed at preflight and re-checked after **every** acceptance command.
   An acceptance command is arbitrary shell and can rewrite the commands that follow it.
3. Every process launch must terminate: after a kill, a grace timer and a forced resolution. A
   killed process whose grandchildren still hold the pipe never produces a `close` event.
4. A report is always written, failures included. An operator must never be left without
   evidence.
5. The human gate fails closed: `reject` is the first option, because that is the one
   automation will select.
6. A read-only role is checked by the **content** fingerprint of the tree, not by a list of
   paths: in an already-dirty tree, editing an existing file does not change the path list.
7. A vendor's permission flag is never a guarantee. The guarantee is the check afterwards.
8. An artifact that fails its schema is not written.
9. Skip an unverified profile rather than attempting to run it: a vendor whose binary is not
   found is excluded by the resolver rather than failing in the middle of the loop. The reason
   given names exactly what was checked — the probe establishes the binary's presence and knows
   nothing about a subscription.
10. A spent subscription is not returned to the same vendor. It is classified apart from an
    ordinary failure, the role switches to another vendor, and the exclusion lives in process
    memory — not in a file that would leave a vendor disabled after its quota reset.
11. A visual check may not silently measure the wrong application. If something is already
    answering on the URL and the task declared its own `start`, the run is refused: Warden
    cannot tell its own server from a forgotten stranger's, and one such run has already
    happened — the harness photographed an unrelated SvelteKit app on 127.0.0.1:4173.
12. The declared viewport must match the one the CSS saw. Mobile emulation without a
    `<meta name="viewport">` lays the page out at 980px, and a report saying "700x400" would be
    a lie.
13. A goal in any language reaches the contract intact or does not reach it at all. The JVM
    decodes argv through `sun.jnu.encoding`; where that is not UTF-8, Cyrillic becomes `?`
    before `main` is entered and no JVM flag changes it — hence `--goal-file`, and hence a
    mangled goal being rejected rather than written into a contract.
14. A prompt that will not reach the vendor intact is not sent. A multi-line argument through a
    Windows `.cmd` shim loses everything after the first newline, including the flags that
    follow; the launch is refused before the call rather than paid for.
15. A declared `baseline_checks` set runs before the first vendor. Red is `baseline_failed`
    and zero vendor calls. The model does not decide whether that gate, or any later
    acceptance command, passed: exit code and timeout do.

---

## 9. What the tool deliberately does not do

- It does not merge, does not push, and does not call `git branch` or `git worktree add`.
  `warden do` asks Orca to create a worktree; without Orca, `--in-place` only.
- It does not let a model choose the vendor.
- It stores no secrets: authentication stays with the vendor's CLI.
- It does not claim to cover paths ignored by `.gitignore` — Git does not see them, so neither
  does the check. This is stated in every ledger rather than hidden in documentation.
- It does not become a generic DAG engine, a distributed scheduler, a checkpoint framework,
  or a web/mobile UI. Those jobs, if they exist, belong elsewhere — see
  [`docs/adr/0001-layer-split.md`](../docs/adr/0001-layer-split.md).
