# Warden

**Runs AI agents by role inside someone else's repository, checks the result mechanically,
and stops in front of a human. It never merges anything.**

Java, **zero dependencies**, built by a single `javac` invocation. A tool that hands a
language model write access to your repository should not drag a hundred-artifact supply
chain in behind it — and it should build with no network.

[![CI](https://github.com/tolboy/warden-agentic-sdlc/actions/workflows/ci.yml/badge.svg)](https://github.com/tolboy/warden-agentic-sdlc/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

*[Русская версия](README.ru.md)*

```bash
./build.sh          # or build.cmd on Windows
./test.sh           # or test.cmd
./bin/warden --help
```

Compiled with `--release 21` — a floor, not a ceiling: the bytecode runs on any newer JVM.
Override with `WARDEN_JAVA_RELEASE`.

## Try it in ten seconds, spending nothing

```bash
./examples/demo/run.sh
```

No vendor, no API key, no network. It builds a throwaway repository, runs the machine half of
the loop twice — once against a tree that fails the contract and once against a tree that
passes — and prints the evidence both runs left behind. See
[`examples/demo/`](examples/demo/README.md), including a committed
[sample evidence ledger](examples/demo/sample-evidence) if you would rather read than run.

## The first real run

Build Warden once. After that it is one command — a goal and a project:

```text
/path/to/warden/bin/warden do --project /path/to/repo --scope code "Add a Settings button"
```

Nothing is merged — it stops at the human gate.

**About that worktree.** `warden do` does not edit the branch you are looking at. It cuts a
fresh worktree from your actual current branch and runs the loop in there — with `git worktree`
by default, or with [Orca](https://www.onorca.dev/download) when Orca is running, in which case
the worktree appears in Orca's sidebar and on its mobile app. `--isolation git|orca` settles it
either way. Both give the same guarantee, and if isolation cannot be made the command fails
rather than falling back to editing your checkout.

A `git worktree` starts empty of whatever your checks need, so `setup:` in `project.yaml` says
what to run in a fresh one — `npm ci`, or nothing at all for most projects. Orca runs the repo's
own setup when Orca made the worktree.

`--in-place` skips isolation entirely and lets the implementer edit the tree you are standing
in — fine for a throwaway repository, and the wrong choice for anything else.

**Before you pay.** `warden do --draft-only` stops as soon as the worktree exists and a draft
contract has been written, and prints the path to it. Warden does not invent browser
scenarios: for a task whose goal names no control at all, the draft leaves only "the page
renders and logs no console errors" plus a comment saying what belongs there. Almost every
real task has its scenarios written by hand, and this is the one place worth stopping —
after it, vendor calls start costing money.

`--prepare off|auto|always` (default `off`) is separate from that stop. `off` keeps today's
deterministic `TaskDraft`. `auto` dispatches a read-only planner only when the task id has
no contract on disk yet, and reuses a ready contract only when it still preserves the
supplied goal, scope and risk. `always` dispatches it even then. The planner drafts; Warden
validates. `--prepare always --draft-only` spends the planner and never the implementer.

```text
warden do --project /path/to/repo --draft-only "Add a Settings button"
```
```text
warden run <task-id> --run-id <id>
```

**A new project from nothing.** A directory with no `.git` is not an error if that is what you
meant:

```text
warden do --project /path/to/new-project --init-repo --in-place "Landing page with a Create button"
```

`--init-repo` runs `git init` and commits whatever is already in the directory as a baseline.
The option is explicit because creating a commit in someone's directory merely because it has
no `.git` is not something a tool should decide. An empty directory gets a contract that says
so outright: there are no check commands yet (`checks.fast: []`), and the blast radius is the
whole repository (`<repository>`) — there is nothing to protect in a project that did not
exist this morning. The task's browser scenarios then serve as the definition of done; a task
with neither is rejected rather than waved through.

A directory **with files but no recognised build system** is still refused: a guessed check
command is a gate that fails for reasons unrelated to the task.

**A non-Latin goal only works through a file.** The JVM decodes command-line arguments using
`sun.jnu.encoding`; where that is not UTF-8 (ordinary Windows), Cyrillic becomes `?` before
`main` is even entered, and no JVM flag changes it. Warden refuses such a goal rather than
writing it into a contract:

```text
warden do --goal-file goal.txt --project /path/to/repo --scope ui
```

`warden doctor` reports this state under `argument_encoding`.

Piecewise and without spending a token: `init`, `validate`, `gates`, `visual-qa`,
`role --dry-run`.

While the chain runs, `do` and `run` write to stderr which stage it is on, which profile and
vendor is working, what it cost and where it went next — see
[What you see while a run is going](#what-you-see-while-a-run-is-going). stdout stays a single
JSON object.

## A tester with eyes

Visual checking is two layers, and they answer different questions.

**The harness** is a real headless browser over CDP, with zero npm dependencies. Scenarios
live in the task contract and are checked mechanically:

```yaml
visual_qa:
  required: true
  url: "http://127.0.0.1:4173/"
  scenarios:
    - "1280x720: testid=save-button visible"
    - "1280x720: testid=save-button click -> css=.panel.open visible"
    - "700x400: css=#wide-only hidden"
    - "1280x720: testid=stage-chapter click -> wait 32 -> css=.hearth visible"
    - "700x400: no-console-errors"
```

Matchers `text=` / `css=` / `testid=` / `role=`, assertions `visible` / `hidden` / `click`.
A separate `wait N` step holds for N seconds and takes a frame: everything else here judges
the page 1.2 s after a click and is therefore blind to any interface running on its own clock
— animations, a deferred scene, a countdown. A pause asserts nothing, it makes evidence, so a
scenario made only of pauses is rejected; and the declared seconds are added to the adapter's
timeout, because a contract that honestly asked for time should not come back as "browser
unavailable". The harness knows no project's DOM, verifies that the viewport it rendered is
the one that was named, and refuses to run if a stranger is already answering on the URL.

**The `visual_qa` role** is a model that receives the screenshots as *attachments*
(`attachments.flag`, `-i` for codex) or as workspace paths it opens with its own image tool —
never as bare filenames passed off as pictures. It answers only what a machine cannot measure:
clipped text, overlap, a collapsed layout. It is off by default in the policy: the harness is
free, a model's look costs a vendor call.

A failure in either layer comes back to the implementer as an ordinary failed check.

## Why a separate repository

The previous version lived inside the project it was checking. That is inconvenient and
dishonest in three ways at once: editing a gate changed the thing being tested; the task's
blast radius included the gate scripts themselves; and reuse on a second project was possible
only by copy-paste.

Here the tool is separated from the projects it connects to.

## How it is put together

```
   ~/.warden/  WHO runs                      <project>/.warden/  WHAT "done" means
   ├ policy.yaml      role → profiles        ├ project.yaml   checks, scopes, defaults
   ├ profiles/*.yaml  vendor, flags, quota   ├ tasks/*.yaml   goal, risk, scope, visual_qa
   └ prompts/ schemas/                       └ runs/          evidence (runs/.gitignore)
                    │                                    │
                    └──────────────────┬─────────────────┘
                                       ▼
                             warden do "<goal>"
                                       │
                 ┌─────────────────────┴───────────────────────┐
                 │  Orca worktree · task draft (or planner) · linter │
                 │  Warden creates no branches and merges none │
                 └─────────────────────┬───────────────────────┘
                                       ▼
  ╔═══ the loop: what happens next is decided by code, not a model ════════════╗
  ║                                                                            ║
  ║   implementer ──▶ machine gates ──▶ reviewer ──▶ browser ──▶ visual_qa     ║
  ║        ▲              │ fail          │ P1        │ fail       │ P1        ║
  ║        └── fix ≤ N ───┴───────────────┴───────────┴────────────┘           ║
  ║                                                                            ║
  ║   role resolver    verified · independent vendor · rotation · failover     ║
  ║   budget           counts vendor calls, not roles, and before dispatch     ║
  ║   runner           direct · orca · local                                   ║
  ╚══════════════════════════════════╤═════════════════════════════════════════╝
                                     ▼
              ledger · evidence.jsonl · prompts · raw output · screenshots
                                     ▼
                    human gate ──── the only one who merges
```

Three places and one boundary. `~/.warden/` is **who** runs: a vendor is a property of the
operator's subscription, not of the repository, so no model name reaches a project's
**configuration** — not `project.yaml`, not any task. A run's report does name the vendor:
that is evidence, and the `.gitignore` in `runs/` leaves `*.json`/`*.jsonl` visible to Git
while screenshots and raw transcripts stay out. `<project>/.warden/` is **what "done" means
here**: check commands, scopes, tasks. Warden itself is **how**: gates, roles, the loop,
evidence.

The boundary runs through the human gate. Everything to the left is deterministic and leaves
evidence; acceptance is the only step that treats an agent's output as trusted, and it stays
with a person. What of this diagram has actually been run live is in [Status](#status);
per-block status is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

**A human's rejection is an input, not a full stop.** The reason you write on `reject` is the
only feedback in the whole loop that cost someone's attention rather than a vendor call.
`--continue` hands it to the next run — verbatim, to the implementer, marked as a person's
objection rather than a failed check:

```text
warden approve <run-id> --decision reject --note "the fire is drawn twice the height of the person beside it"
warden run <task> --run-id <new> --continue <run-id>
```

It grants no permission: a vendor substitution is still authorised only by a `switch`
decision. It carries exactly that text, and carries it once — after that the context becomes
the specific stage failure, and burying it under a stale rejection would be worse.

**A verdict belongs to a tree, not to a run.** A run that failed on something unrelated to the
work — no browser, an occupied port, a tree that was dirty before it started — used to take a
green implementer and an independent review down with it, and the only way forward was to pay
for both again. `--continue` on a `retry` decision for such a failure carries the verdicts
already reached, but only if **both** the source fingerprint **and** the entire `.warden`
contract are byte-for-byte the same: otherwise a role judged a different candidate, or
different terms. Any doubt means the full chain again, and the reason is recorded in
`reuse_declined`. A role that passed but filed a blocking finding is not carried: that is not
"passed", it is a fix round that has not happened yet. The first fix round in the new run
cancels the carry entirely, and exactly what was carried is stated in the question a human is
asked at the gate.

Connecting a new project = run `init`, review the generated contract, and describe its check
commands. Do not copy Warden's own `.warden/`: that is Warden's contract, not a template.

## What you see while a run is going

The loop runs for tens of minutes. It used to print nothing for all of it and then emit one
JSON object at the end, so "where are we now" had to be worked out from file timestamps in
four run directories. Now `do` and `run` narrate themselves **on stderr**:

```text
run   chapter-hearth-4
task  chapter-with-a-reason   risk=medium   scope=src, scripts
plan  implement -> gates -> review -> browser -> look
bound 12 vendor call(s), $40.0000 ceiling, fix rounds <= 3

note  starting from the rejection recorded on chapter-hearth-3; the implementer is
      given its reason verbatim

[1/5] implement codex-implement  codex/gpt-5.6-terra  dispatching, up to 60 min
      ok  2m45s   tokens 851760/5597   cost ?
[2/5] gates     machine gates: npm run check, npm run build
      ok  18.5s
[3/5] review    grok-review  grok/grok-4.6  dispatching, up to 45 min
      ok  16m30s  tokens 187337/45156   cost $0.3099   verdict pass
[4/5] browser   browser harness: 3 scenario(s) at http://127.0.0.1:4173/?lab
      ok  2m14s   7 screenshot(s)
[5/5] look      claude-visual-qa  claude/opus  dispatching, up to 25 min
      ok  2m36s   cost $1.4202   verdict fail
      1 blocking finding(s) from visual_qa — see warden report for what it saw
      -> fix round 1 of 3: look sends the work back to implementer

done  ready_for_human   6 vendor call(s), $3.9098 charged
      2 of those reported no price at all, so the $ ceiling did not measure them
      the candidate is ready and nothing has been landed
      warden report chapter-hearth-4 --text
      warden approve chapter-hearth-4 --decision <accept|reject>
```

**stdout does not change** — it is still exactly one JSON object, the one Conductor and any
script reads, so redirecting one stream does not disturb the other. `--quiet` turns the
narration off. Nothing printed is evidence: every line restates what is already in the ledger,
which is precisely why it can be turned off without losing anything.

## What it cost, and why that is sometimes unknown

Every vendor here runs through its own CLI on the operator's subscription. The price of a call
is whatever that CLI chose to report: Grok and Claude print a number, Codex prints nothing.
Warden does not estimate the difference — it records what arrived and counts how many calls
reported nothing.

Hence the honest boundary of `budgets.max_cost_usd`: it is a ceiling for **the part of the run
that priced itself**. A run where no vendor named a price can spend every one of its allowed
calls under a $40 limit and charge $0.00 against it. That is why `task-run.json` carries
`unpriced_calls` and `cost_ceiling_binding` next to `total_cost_usd`, and why the terminal
narration says it out loud. The real bound, which always holds, is `max_role_runs`: it counts
calls rather than money, and it is checked before dispatch.

## The run report

Each stage writes its own report and never edits anyone else's — so nothing that already
happened can be quietly rewritten after the fact. That is right for evidence and useless for
the question "how did the run go". A separate command joins them:

```text
warden report <run-id> --text
```

```text
run      do-fix-the-create-button-mc9k1
task     fix-the-create-button  risk=medium
outcome  ok  ready_for_human  next=human_gate
human    pending  options=[accept, reject]

stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             0.4120    18400/2100   96140
gates                 0  ok  -                               ?         ?/?              ?
reviewer              0  ok  grok/grok-4.6                   0.1880    31200/900    41020
visual_qa             0  ok  -                               ?         ?/?              ?

vendor/model                     calls  fail  cost      in/out tokens        ms
codex/gpt-5.6-terra                  1     0  0.4120    18400/2100         96140
grok/grok-4.6                        1     0  0.1880    31200/900          41020

browser  passed  3 screenshot(s)
  ok    1280x720: testid=create-button click -> css=.editor visible
```

`?` means "the vendor did not report this", not zero: a run with "$0.00 for four calls" would
otherwise look exactly like a run where nobody sent a cost — and would be believed. The same
field exists in the JSON as `*_unknown_calls`.

The same report is written automatically to `.warden/runs/<run-id>/report.json` after `do` and
`run`; `warden ledger` aggregates every run in the project.

## The chain of model calls

The order of stages is a declaration, not code. It lives in the same file as the roles and
vendors, so "who runs", "in what order" and "under what condition" are read in one place:

```yaml
# ~/.warden/policy.yaml
roles:
  implementer: { profiles: [codex-implement], strategy: first }
  reviewer:    { profiles: [grok-review, claude-review], strategy: rotate,
                 require_independent_vendor: true }

workflow:
  stages:
    - { stage: implement, run: role, role: implementer, on_fail: stop }
    - { stage: gates,     run: machine_gates, on_fail: fix, recheck_after_fix: true }
    - { stage: review,    run: role, role: reviewer, when: [review_required],
        on_fail: stop, on_findings: fix }
    - { stage: browser,   run: visual_harness, when: [visual_qa_required],
        on_fail: fix, recheck_after_fix: true }
    - { stage: look,      run: role, role: visual_qa, when: [visual_qa_required],
        sees: browser, on_fail: stop, on_findings: fix }
```

`run:` is `role`, `machine_gates` or `visual_harness`. `when:` is a **closed** set of
conditions (`review_required`, `visual_qa_required`, `risk_low|medium|high`); an unknown
condition is a configuration error rather than a silent "false", because false here is the
permissive answer. `on_fail: fix` returns the exact failure text to the implementer, no more
than `max_fix_attempts` times, and re-runs every earlier stage marked `recheck_after_fix` —
otherwise fixing one check can break one already passed. `sees:` binds the role with eyes to a
specific browser stage: you cannot look at pixels nobody photographed.

What is **not** configurable: what a failure is called (`gates_not_satisfied` is not renamed
along with your stage), the budget boundary, and the fact that the last word is a human's. The
block is optional: without it, exactly the chain above runs. `warden doctor` shows the
effective chain under `workflow`; `task-run.json` shows the one actually taken, in `workflow`
and `skipped_stages`.

## When a vendor's subscription runs out

An exhausted quota is the one failure that is not about the work. Warden classifies it apart
from an ordinary error and can hand the role to another vendor, but **asks first by default**:

```yaml
# ~/.warden/policy.yaml
failover:
  on_quota_exhausted: confirm   # confirm (default) | auto | stop

budget:
  repair_reserve: full          # full (default) | partial
```

`repair_reserve` decides how much of the remaining chain a fix round has to be affordable
before it may start. `full` reserves the fix, every judgement it invalidates and every stage
still owed, so a run either completes or does not begin the attempt. `partial` reserves the fix
plus whatever will read its result, allowing paid progress this run cannot finish — a review
that then passes is a verdict a continuation reuses for nothing. Either way the arithmetic is
in `budget_plan` before the first vendor is dispatched, and `--dry-run` prints it.

Why not `auto`: swapping vendors changes the author of the work, and on a small roster it can
cost the run its independent reviewer — two vendors minus one spent subscription is one
vendor, and a model reviewing itself is the exact thing you started two for. That is a
judgement about the value of the result, and it belongs to the operator.

Under `confirm` the run stops, names both vendors, and writes a durable decision of its own
kind (`kind: failover`, options `[abort, switch]` — refusal first, so that automation declines
rather than permits):

```text
role implementer: codex-implement (codex) reported a spent subscription.
Switch to claude-implement (claude)? the candidate is a different vendor from
the implementer (codex), so independence is preserved
```

Permission is a recorded decision, not a flag:

```text
warden approve <run-id> --decision switch
warden run <task> --run-id <new> --continue <run-id>
```

`--continue` reads that run's decision and permits **exactly one** substitution: the named
role onto the named profile. A flag would permit anyone, and a stored "codex ran out" would go
stale the moment the quota window reopened. The same flag also accepts a **rejected** run —
but there it permits nothing, and only carries the rejection's reason to the implementer.
Under `auto` the substitution happens immediately, but the `role_failover` event with both
vendors and an `authorized_by` field still lands in the evidence and in `warden report`.

## Configuring vendors

The resolver will not release a profile whose probe has never run: `verification.verified_on`
is the date this profile's own probe was executed and passed. Swapping the vendor behind a
role is therefore a config edit and one command, not a ceremony.

```text
warden profiles                              what loads, what is eligible, and why not
warden profiles --verify codex-implement     run this profile's probe; stamp it if it passes
```

`--verify` runs the profile's `verification.probe`, saves the transcript under
`~/.warden/verification/`, prints `what_to_check`, and stamps the date when the probe passes.

Read the transcript anyway. `what_to_check` is where you learn which envelope key carries the
answer, whether the vendor prompts for approval, and whether it reports a cost at all — every
one of those was established by reading, and none of it is in the vendor's documentation. What
the date does *not* claim is that you did: the field once said it meant a human judgement, and
nothing enforced that, which made it a guarantee everyone downstream relied on and nobody held.

The enforcement that does hold is mechanical and elsewhere: a `read_only` role that writes gets
`role_violated_read_only` and its artifact discarded, whatever any flag or date claimed. Still
do the implementer last and deliberately — it is the only role with `read_only: false`.

## Status

Honest by column. **Implemented** means it exists and is covered by tests. **Verified live**
means it has been run against real vendors on a real project, with a transcript in
[`docs/LIVE-CYCLE.md`](docs/LIVE-CYCLE.md). **Planned** means named and not built — the
resolver refuses these rather than pretending.

| Capability | Implemented | Verified live | Notes |
|---|:---:|:---:|---|
| Config model, strict YAML/JSON subsets, schema validation | ✅ | ✅ | Offline, no dependencies |
| Git merge-base, content fingerprint (renames and untracked files) | ✅ | ✅ | |
| Bounded process supervisor, preflight and machine gates | ✅ | ✅ | |
| Evidence ledger, `warden ledger`, `warden report` | ✅ | ✅ | Per-attempt vendor cost, tokens, model as reported |
| Contract integrity (`contract_mutated`) | ✅ | ✅ | The whole `.warden` tree except `runs/` |
| Role resolver: verification, rotation, independent vendor | ✅ | ✅ | |
| Direct CLI adapter | ✅ | ✅ | Live with Codex, Grok and Claude |
| Quota detection and failover | ✅ | ✅ | Against a genuinely exhausted Codex account |
| Bounded loop: implement → gates → review → browser → look, fix ≤ N | ✅ | ✅ | Run `chapter-hearth-3`: six vendor calls, $3.91, one fix round |
| Browser harness (CDP, project-neutral) | ✅ | ✅ | Six scenarios across three viewports |
| `visual_qa` role on real screenshots | ✅ | ✅ | Filed a P1 the harness could not see; routed back as a fix round |
| Human gate: durable, cross-process-locked decisions | ✅ | ✅ | `accept` / `reject` / `switch` / `retry` |
| Carrying a rejection or a verdict into the next run | ✅ | ✅ | `--continue` |
| `warden do`: Orca worktree isolation, task draft | ✅ | ✅ | Cut from the branch the operator is actually on |
| Greenfield: `--init-repo`, contract with no invented check | ✅ | ✅ | |
| `warden land`: commit, push, open a request | ✅ | — | Merges nothing; the forge is never guessed |
| Orca adapter as a role runner (`runner: orca`) | ✅ | ✅ | Claude Opus 5 appeared in Agent Dashboard, ran a project gate, returned `worker_done`, and was acknowledged and released; see the 2026-09-04 row in [`docs/SMOKE.md`](docs/SMOKE.md) |
| Local runner (`runner: local`) | ✅ | ❌ | Reaches a model already serving here over the OpenAI chat-completion shape, and holds it to the same artifact, schema, token and read-only evidence as a CLI vendor. No dated smoke row yet |
| Per-vendor tool allowlists | ❌ | ❌ | A profile's args are whatever you wrote — see [`SECURITY.md`](SECURITY.md) |
| Visual QA pixel-diff and baselines | ❌ | ❌ | The harness asserts; it does not compare images |

Commands: `do`, `setup`, `profiles`, `init`, `validate`, `gates`, `visual-qa`, `role`, `run`,
`doctor`, `ledger`, `report`, `status`, `approve`, `land`.

The live runs were made on Windows against a SvelteKit project, with Orca 1.4.190 and Codex,
Grok and Claude on the operator's own subscriptions.

## Documentation

| Document | What it is |
|---|---|
| [`spec/SPEC.md`](spec/SPEC.md) | The full specification: every file, every command, every invariant |
| [`docs/adr/0001-layer-split.md`](docs/adr/0001-layer-split.md) | Why Warden is a policy/evidence engine, Orca is the cockpit, and Conductor stays optional |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Per-block status, and what each failure is allowed to do |
| [`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md) | A guided tour on stub vendors — nothing spent |
| [`docs/LIVE-CYCLE.md`](docs/LIVE-CYCLE.md) | Transcripts of real runs, including what they broke |
| [`docs/METRICS.md`](docs/METRICS.md) | What `warden ledger` measures about the loop, and why a value nobody reported never becomes a zero |
| [`docs/SETUP.md`](docs/SETUP.md) | The repeatable install and per-project connection procedure |
| [`docs/SMOKE.md`](docs/SMOKE.md) | The reproducible red/green cross-project smoke |
| [`examples/demo/`](examples/demo/README.md) | The offline demo and a committed sample evidence ledger |

Section 8 of the specification lists the invariants the implementation must hold. Each one is
a defect that was already found in the previous JavaScript version.

Russian versions: [`README.ru.md`](README.ru.md) and [`spec/ru/SPEC.md`](spec/ru/SPEC.md).

## Contributing, security, licence

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to build, what a good change looks like, and the
  one rule (no dependencies) that shapes the rest.
- [`SECURITY.md`](SECURITY.md) — the threat model, the boundaries Warden actually enforces,
  and the ones it does not. Read this before pointing Warden at a repository you care about.
- [`CHANGELOG.md`](CHANGELOG.md) — what changed, per release.
- Licensed under the [Apache License 2.0](LICENSE).

Maintained by [@tolboy](https://github.com/tolboy). Issues and pull requests
are welcome; questions are best asked as an issue so the answer is findable.
