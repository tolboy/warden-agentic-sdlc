# The live cycle: what was actually run, on what, and how it ended

A transcript, not a statement of intent. The target is a private SvelteKit application,
referred to here as *the target project*; the worktrees were created by Orca 1.4.190; the
vendors are real and were billed to the operator's own subscriptions. Windows 11, Java 25,
`--release 21`.

Run dates: 2026-08-28 and 2026-08-29.

The project's own file and identifier names have been generalised where they would expose a
private codebase. Every number, verdict and quoted vendor sentence is as recorded.

---

## 1. The operator's entry point: `warden do` creates the isolation itself

```text
warden do --project <project> --scope code --dry-run \
          --task-id warden-e2e-probe "Probe run: confirm the Create button stays visible"
```

```json
{"ok":true,"code":"dry_run",
 "worktree":"<orca-workspaces>/w-warden-e2e-probe",
 "isolated":true,"isolation":"created","worktree_start_ref":"main",
 "steps":[{"step":"implementer",...},{"step":"gates",...},
          {"step":"reviewer",...},{"step":"visual_qa",...}]}
```

Proved by this run:

* Orca created the worktree at Warden's request; Warden ran neither `git branch` nor
  `git worktree add`;
* `worktree_start_ref: main` — `HEAD` resolves to the **actual current branch** rather than
  being substituted with a constant. On `master`, `develop` or a feature branch the worktree
  is cut from there; on a detached HEAD the command refuses (`detached_head`) instead of
  guessing;
* a project contract was created in the new worktree, a task draft was written, and the whole
  chain of stages resolved — all before a single vendor call.

---

## 2. A full cycle on an existing task: the Create button

The task had been set earlier and done: nine lines in a route stylesheet that bring the
mode switch back at widths 640–759.98px in landscape orientation. A phone turned sideways is
wide enough for the studio, but the mobile header hid the only control that opens it. There
was no explicit confirmation that the task was closed.

The run used a chain **without an `implement` stage** — the work was already in the worktree
and there was nothing to implement. This is exactly why the chain is declared rather than
compiled in:

```yaml
workflow:
  stages:
    - { stage: gates,   run: machine_gates, on_fail: fix, recheck_after_fix: true }
    - { stage: review,  run: role, role: reviewer, when: [review_required],
        on_fail: stop, on_findings: fix }
    - { stage: browser, run: visual_harness, when: [visual_qa_required],
        on_fail: fix, recheck_after_fix: true }
```

```text
warden run fix-the-create-button-so-it-works-again --run-id lh-verify-1
warden report lh-verify-1 --text
```

```text
run      lh-verify-1
task     fix-the-create-button-so-it-works-again  risk=medium
goal     Fix the Create button so it works again
outcome  ok  ready_for_human  next=human_gate
human    pending  options=[accept, reject]

stage           attempt  ok  vendor/model                    cost      tokens        ms
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.0835    104042/13006   337546
visual_qa             0  ok  -                               ?         ?/?                 ?

vendor/model                     calls  fail  cost      in/out tokens        ms
grok/grok-4.6                        1     0  0.0835    104042/13006         337546

browser  passed  5 screenshot(s)
  ok    700x400: text=Create visible
  ok    700x400: text=Create click -> css=main.editing visible
  ok    1280x720: text=Create visible
  ok    1280x720: text=Create click -> css=main.editing visible
  ok    400x800: text=Create hidden
  ok    700x400: no-console-errors

changed  1 source file(s) since 5c42b7bcbdb…
  src/routes/layout.css
         plus 3 Warden contract file(s), unchanged since the run snapshot

totals   1 vendor call(s), 0 fix round(s), $0.0835 of $6.0000 budget
```

What that proves, layer by layer:

| Layer | By what exactly |
|---|---|
| Machine gates | `npm run check` and `npm run build` passed in the worktree; no path outside `src`/`scripts` |
| Configuration integrity | the whole `.warden` tree was snapshotted before the first stage and compared after each one; no difference |
| Independent reviewer | Grok 4.6, `verdict: pass`, 0 findings, 104,042 / 13,006 tokens, $0.0835, 337 s |
| Browser | six scenarios across three viewports, including a **click** and the transition into the editing mode |
| Human gate | `decision.json` in state `pending`, options `accept` / `reject` |

The reviewer on the substance of the change, verbatim:

> The Create/View switch was already in the DOM for landscape viewports at 640–759.98px
> (MOBILE_VIEW_ONLY_QUERY admits the studio there) but `@media (max-width: 759.98px)` set
> `.mode-switch` to `display:none`. The added later rule restores `display:flex` for that band
> in landscape only. Production CSS keeps hide-then-restore order.

The scenario `400x800: text=Create hidden` is in the contract on purpose: without it, the
cheapest way to satisfy the checks above is to show the switch everywhere, which breaks the
product on a portrait phone. The check forbids not only failure but also the forgery.

The screenshot `700x400-after-click.png` shows the studio open with its tool palette — the
button is not merely visible, it works.

**No decision was recorded.** Acceptance is the only step that treats an agent's output as
trusted, and it stays with a person:

```text
warden approve lh-verify-1 --decision accept --expected-updated-at 2026-08-28T11:53:39.586362700Z
```

`accept` is refused if the worktree fingerprint moved since it was shown. `reject`, `abort`
and `retry` are always available — they permit nothing.

---

## 3. A full cycle with an implementer: stopped by a spent subscription

```text
warden run create-button-testid --run-id e2e-1
```

```text
outcome  STOPPED  quota_exhausted  next=human_escalation
human    pending  options=[retry, abort]

stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  NO  codex/gpt-5.6-terra             ?         ?/?             16519
```

The vendor's message is kept verbatim: "You've hit your usage limit… or try again at
10:55 AM". Warden does not parse a time out of it: the wording carries neither a date nor a
time zone.

This is not a defect in the run; it is the exact path for which quota is separated from an
ordinary error:

* `role_quota_exhausted`, not `implementer_failed` — the operator is not sent to read a
  transcript that contains no defect;
* the profile is excluded for the rest of the process and is not called again;
* failover did not fire only because the policy assigns exactly one profile to the
  `implementer` role;
* `?` rather than `0.0000` for cost and tokens: the vendor did not report them, and the report
  does not hide that.

The task and the worktree stayed ready for a retry once the quota window reopened:

```text
cd <orca-workspaces>/w-warden-e2e-probe
warden run create-button-testid --run-id e2e-2
```

To clean up instead: `orca worktree remove --worktree name:w-warden-e2e-probe`.

---

## 4. Conductor as the outer controller

```text
conductor run conductor\do.yaml --skip-gates --no-interactive
    -i task=fix-the-create-button-so-it-works-again
    -i project_dir=<worktree>
    -i run_id=lh-conductor-1
    -i actor=warden-integration-test
```

```text
┌─ Agent: run_task [iter 1]
└─ ✓ run_task  (21.64s)
   → next: human_approval

┌─ Agent: human_approval [iter 1]
Auto-selecting: Reject (--skip-gates)

┌─ Agent: record_reject [iter 1]
  Script: … dev.warden.Main approve lh-conductor-1 --decision reject
          --expected-updated-at 2026-08-28T12:10:59.410123200Z --actor warden-integration-test
└─ ✓ record_reject  (0.19s)

Workflow terminated at 'rejected': Human rejected the candidate. No changes were landed.
```

Conductor 0.1.33. Verified:

* routing on `run_task.output.exit_code` worked;
* the optimistic-lock token `decision_updated_at` travelled from Warden's output into the
  arguments of `warden approve` through a Conductor template — the one place where these two
  programs are obliged to agree;
* the decision was recorded durably: `state: resolved`, `decision: reject`, `actor` as passed;
* a second decision on the same run was refused (`duplicate_decision`);
* `--skip-gates` selects the **first** option, and in both gates the refusal is first
  (`Reject`, `Abort`). Automation may refuse on a human's behalf; it may not accept. The order
  of options in `do.yaml` holds that invariant, and says so in the file.

The run is deliberately machine-only: a chain of a single `machine_gates` stage, not one vendor
call. What was being tested was the bridge, not a model's opinion.

---

## 5. A project from nothing

```text
warden do --project <empty directory> --in-place --init-repo --dry-run \
          "Build a landing page with a Create button"
```

`git init` plus a baseline commit, a contract with an empty `checks.fast: []` and a
`<repository>` scope, a task draft whose browser scenarios are the definition of done, and the
whole chain resolved. Details are in the README, under *The first real run*.

---

## 6. Three vendors in one run, and what broke while doing it

A second attempt, once all three quotas were open. The roster: implementer — Codex
(`gpt-5.6-terra`); reviewer — Grok 4.6; eyes — Claude Opus through `capabilities.vision` with
`workspace_file` delivery, because Claude has no flag for images but does have a read tool that
shows it the picture rather than the filename.

Verifying the `claude-visual-qa` profile was not "it exited zero" but a check against pixels:

```text
probe: open this screenshot, name the two buttons in the pill at the top, and count the
       icons on the right
reply: VIEW, CREATE
       8
```

The labels were read correctly, and no filename along that path contains the word VIEW.
Counting the icons on a repeat gave 7 instead of 8 — which is recorded in the profile's
`verification.note`, so the verification date is not read as a promise of precision the probe
did not deliver. This eye is for "readable / overlapping / collapsed", not for counting small
things.

### What the live run found

Five defects, every one of them in code rather than in reasoning about code.

**A prompt carrying JSON never reached the vendor.** The visual-QA prompt includes the
harness report; the report is JSON; JSON has quotes. On Windows the JVM wraps an argument in
quotes if it contains a space, but does not escape a quote inside the value — the receiving
process cuts the argument at that point. What reached `claude.exe` was
`error: unknown option '->'` after 620 ms. The old check looked only for newlines through a
batch shim, and `claude` is a real `.exe`. Now the refusal happens before dispatch, and both
Claude profiles use `prompt_delivery: stdin`. Both halves of the rule are needed:
`-c model_reasoning_effort="high"` contains a quote but no space, the JVM does not wrap it, and
it travels fine — the first version of the rule rejected it.

**The reviewer did not know about the browser layer.** Grok filed a P1 saying "no command
verifies this attribute" — while the scenario one line below verified it by name. The
implementer was sent to write a test for something already checked. The reviewer's prompt did
not mention browser scenarios at all; now they sit next to the acceptance commands.

**The gate charged money for the operator's problem.** A lockfile had been touched by Orca's
own setup, before any agent existed. The scope check fired *after* the implementer and came
back to it as a fix round — which it physically cannot fix, the path being outside its blast
radius. The check now runs before the first dispatch, and `preflight_outside_scope`,
`base_ref_unresolvable` and `contract_mutated` are terminal for the same reason
`visual_qa_unavailable` already was.

**The candidate's fingerprint included Warden's own evidence.** A run that was green at every
stage refused acceptance with `candidate_changed` — because of its own log file sitting inside
`.warden/`. The rule was right and the file set was wrong: a human accepts a change to sources.
Two questions were separated — "did a read-only role touch anything" still includes `.warden`,
"is this the same candidate" no longer does. Contract integrity is not weakened: any change
inside `.warden` during a run already yields `contract_mutated` against the snapshot.

**A turn ceiling is not a failure at the work.** Reviewing a 126-line diff consumed all 16
permitted turns on reading and exited 1 with no artifact; the run recorded
`role_command_failed`. Nothing was broken: the vendor was interrupted by a limit the operator
set, and it had already found two genuine defects by then. There is now a distinct
`role_turns_exhausted` code that names what to raise. No failover is attempted — another vendor
would meet the same ceiling on the same diff.

### The refusal the system had to not swallow

An implementation passed both acceptance commands and Codex returned `status: blocked`:

> The scoped implementation is present and both acceptance commands pass. Production
> persistence remains blocked: server-side frame validation does not allow the new element
> kind, and the required migration is outside the permitted src/scripts paths.

Grok had found the same thing before it. The agent refused both to quietly ship something that
would break on publish and to step outside the blast radius it was given. A syntactically valid
artifact with status `blocked` is not a success, and `semanticFailure` does not treat it as
one.

The operator's answer was not to talk the agent round but to widen the scope to the migrations
directory, where a direct precedent already existed: an earlier migration had added another
element kind in exactly the same way.

### The full cycle, three vendors, green

```text
run      lighthouse-5
task     lighthouse-element  risk=medium
outcome  ok  ready_for_human  next=human_gate
human    pending  options=[accept, reject]

stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             ?         549584/3549    143451
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.2584    148206/34219  1019773
visual_qa             0  ok  -                               ?         ?/?                 ?
visual_qa             0  ok  claude/opus                     1.0181    10/6578        113959

browser  passed  2 screenshot(s)
  ok    1280x720: text=Create click -> testid=tool-lighthouse visible
  ok    ... -> css=.palette button.active[data-testid=tool-lighthouse] visible
  ok    ... -> css=canvas click@0.5,0.12 -> text=water visible
  ok    ... -> css=canvas click@0.5,0.82
  ok    1280x720: no-console-errors

changed  8 source file(s) since 5c42b7bcbdb…
totals   3 vendor call(s), 0 fix round(s), $1.2765 of $14.0000 budget
```

The reviewer, verbatim:

> The lighthouse is a permanent Create-palette kind (`data-testid=tool-lighthouse`), allowed
> only in the central share of a deep water band, drawn with a sand-and-stone islet under the
> tower, and admitted by a new `frame_elements_valid` migration that copies the current
> allowlist and adds lighthouse. Existing kinds' placement and draw helpers are unchanged.

The role with eyes, verbatim:

> the canvas shows a lighthouse standing in the middle of the lake on a small sandy islet of
> its own, with a lit lamp and glow — mid-water, well clear of both shores, exactly what the
> task asks for.

Two P3 findings, both cosmetic and both unrelated to this task: a status line writing
"1 DETAILS" next to "1 DETAIL", and one caption filling its tile almost edge to edge where its
neighbours have margins.

`css=canvas click@0.5,0.82` is the click at a point inside the element. Without it, a
full-window canvas is reachable only at its centre, and no scenario can place anything in the
water.

---

## 7. The full cycle to the human gate, and a fix ordered by the eyes

2026-08-29. This task was about neither layout nor a button: one chapter of the target
project's story had no meaning — a walker carried a stone to a pile of stones, put it down and
stopped. The goal gave a direction (a hearth on the stones rather than one more stone) and left
the design to the implementer, while making mandatory only what can be checked: the template
id, the kind of gesture, `data-testid` on the workbench buttons, and "legible at a glance at
1280x720".

The task contract leans on the `wait` step, without which this project cannot be checked at
all: the workbench panel enters the DOM about six seconds after load, and the chapter's payoff
lands about twenty-eight seconds after it is staged.

```yaml
- "1280x720: wait 7 -> testid=lab-chapter-stone-upon-stone click -> wait 20
   -> css=canvas visible -> wait 14 -> css=canvas visible -> no-console-errors"
```

```text
stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             ?         1103531/4842   166805
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.3397    543998/23179   712582
visual_qa             0  ok  -                               ?         ?/?                 ?
visual_qa             0  ok  claude/opus                     1.8399    34/15152       243463
implementer           1  ok  codex/gpt-5.6-terra             ?         851760/5597    164552
gates                 1  ok  -                               ?         ?/?                 ?
reviewer              1  ok  grok/grok-4.6                   0.3099    187337/45156   990086
visual_qa             1  ok  -                               ?         ?/?                 ?
visual_qa             1  ok  claude/opus                     1.4202    26/9485        156206

totals   6 vendor call(s), 1 fix round(s), $3.9098 of $40.0000 budget
outcome  ok  ready_for_human  next=human_gate
```

The fix round was ordered by **no machine**. The gates were green, Grok's review was `pass`,
the harness was `passed`. The P1 came from the role with eyes, verbatim:

> the whole tableau is standing on water … at wait-5 the fire's base sits at ~y 578, above
> the lake's near edge at y 590, i.e. mid-basin. A hearth burning on a lake reads as a
> rendering fault, not as a reason, and it directly contradicts the chapter's own name,
> 'the mark on the open ground'.

The implementer fixed the staging, the reviewer re-read the tree (`recheck_after_fix`), and on
the second look the role returned `pass`: the walker approaches two stones on open ground, and
fourteen seconds later a hearth burns there with smoke. `stale_judgements` in `task-run.json`
is empty — no stage judged a tree that no longer exists.

What this run cost the tool: four Warden defects, found along the way and fixed in Warden
itself — the harness's blindness to an interface running on its own clock; a
`text=chapter visible` in a draft contract produced from a word that merely sat before
"button"; an implementer that was never shown the browser scenarios; and a readiness probe
that spoke HTTP/2 to a dev server and heard nothing back. The last one cost a whole run:
`visual_qa_unavailable` is a stop with no fix round, and it threw away a green implementer and
a green review.

---

## 8. What this file still does not claim

* The Orca adapter (`runner: orca`) as a **role runner** is not confirmed live. Orca created
  worktrees in these runs — that is `OrcaIsolation`, a different code path.
* `warden do --conductor` (Conductor launched by Warden itself) has not been run: what was
  tested is the same workflow launched directly from Conductor's own CLI.
* Failover confirmed by a human decision (`--continue`) is covered by tests but has not been
  run against a genuinely exhausted quota: on the second attempt all three quotas were open.
