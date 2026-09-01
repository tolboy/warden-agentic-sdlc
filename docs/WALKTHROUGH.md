# How to use this: a walkthrough over a real run

Not a description of intentions — a transcript. Every block below was actually executed and
the output is reproduced as it came. The run used stub "vendors" — ordinary shell scripts — so
that the whole scheme could be shown without spending anything. A real vendor is substituted
by replacing one `command:` line in a profile.

For a version you can run yourself in seconds with no vendor at all, see
[`examples/demo/`](../examples/demo/README.md).

---

## Step 0. Three places you have to keep apart

```
~/.warden/               WHO runs             — vendor profiles and role policy
<any project>/.warden/   WHAT "done" means    — check commands and tasks
warden                   HOW                  — gates, roles, the loop, evidence
```

A vendor is a property of whoever runs, not of the project. That is why no model name is
committed to a project's Git, and why the same `project.yaml` works for a colleague with
different subscriptions.

---

## Step 1. Configure yourself (once)

```
warden setup
```

```json
{"ok":true,"home":"~/.warden",
 "created":["policy.yaml","profiles/grok-review.yaml","profiles/claude-review.yaml",
            "profiles/codex-implement.yaml","prompts/reviewer.md","prompts/implementer.md",
            "schemas/reviewer.json","schemas/implementer.json"]}
```

Existing files are **not overwritten** — a second `setup` only reports what it left alone.

> The configuration directory is overridden by `WARDEN_CONFIG_HOME`. Specifically
> `WARDEN_CONFIG_HOME`, not `WARDEN_HOME`: the launcher `bin\warden.cmd` uses the latter for
> the installation directory, and on the very first live Windows run that name collision moved
> the starter configuration inside the repository instead of the home directory. What caught
> it was not a test but the fact that `setup` prints `home` — which is why it prints it.

```
warden profiles
```

```
  claude-review      role=reviewer     vendor=claude  verified=False  profile is unverified…
  codex-implement    role=implementer  vendor=codex   verified=False  profile is unverified…
  grok-review        role=reviewer     vendor=grok    verified=True
```

Only the Grok profile is marked verified, because only its flags were **established by
running it** rather than read out of documentation. The others carry the exact verification
command where the date would be, and the resolver will not release them until that date is
stamped. Guessing Grok's flags had already cost three failed runs; now the state is visible
instead of being discovered halfway through a loop.

You do not rewrite the probe by hand — it runs by its own command:

```
warden profiles --verify claude-review
```

```json
{"ok":true,"code":"probe_passed","exit_code":0,"stamped":false,
 "transcript":"~/.warden/verification/claude-review.txt",
 "what_to_check":["exits 0 without opening an interactive session",
                  "stdout is a JSON envelope; note which key carries the answer",
                  "--allowedTools is accepted and the run still completes",
                  "whether a cost figure is reported, and under which key"],
 "verified_on":"2026-08-27","stamped":true}
```

The probe passed, so the date is stamped: `verified_on` records that this profile's own probe
ran and passed, which is the thing the resolver needs to know before dispatching flags nobody
has ever executed.

It used to mean more than that on paper — that a person had read the transcript against
`what_to_check` — and nothing enforced it, because the stamp was a command and whoever typed it
was trusted to have read. A field whose stated meaning the tool cannot hold is worse than a
plain fact, so it now says the fact.

Read `what_to_check` regardless. Every useful thing in these profiles was established that way:
which envelope key carries the answer, whether the vendor opens an interactive session, whether
a cost figure appears at all. None of it is in the vendor's documentation.

The date is appended to the end of the `verification:` block and the operator's comments stay
where they are: in these profiles the comments are the most valuable thing in the file — they
are flags established by running.

---

## Step 2. Connect a project

One file. For SvelteKit:

```yaml
# .warden/project.yaml
version: 1
project: demo
base_ref: HEAD
checks:
  fast: ["npm run check"]
scopes:
  app: ["src"]
defaults:
  checks: fast
  risk: medium
```

For a JVM project only the contents of `checks` change — `./gradlew build`. Nothing else.

A task is four lines at minimum:

```yaml
# .warden/tasks/hello.yaml
version: 1
id: hello
goal: Create src/result.txt containing the word ok
risk: medium
scope: app
authority:
  workspace_write: true
```

---

## Step 3. Check the setup without spending anything

```
warden role reviewer hello --dry-run
```

On a machine with no Grok installed, this answers:

```json
{"ok":false,"code":"role_unresolved",
 "details":{"message":"no eligible profile for role reviewer:
   {grok-review=executable_not_found, claude-review=profile_unverified}"}}
```

Each profile has its own reason, named after exactly what was checked.
`executable_not_found` used to be called `unavailable_or_quota_exhausted` — which was a lie:
the probe establishes precisely one thing, whether the executable exists. Nobody knows about a
spent subscription before running, so that is now a separate reason and appears only after the
vendor has said so itself.

That is the answer to "why did nothing run": a reason per profile. Not one token spent.

When a profile is eligible, `--dry-run` resolves the vendor and **writes the exact prompt to
disk** that would have been sent, without calling the vendor:

```
.warden/runs/dry/prompts/reviewer.md      ← exists
.warden/runs/dry/raw/                     ← does not, the vendor never ran
```

---

## Step 4. The red half: gates fail before the work

```
warden gates hello --run-id red
→ {"ok":false,"code":"command_failed"}
```

As it should be: `src/result.txt` does not exist yet. This proves the gate is not playing
along.

## Step 5. The implementer does the work

```
warden role implementer hello --run-id live
→ {"ok":true,"profile":"stub-impl","vendor":"stubvendor"}
```

## Step 6. The green half

```
warden gates hello --run-id green
→ {"ok":true,"code":"passed"}
```

## Step 7. Independent review

```
warden role reviewer hello --run-id live --implementer-vendor stubvendor
→ {"ok":true,"profile":"stub-review","vendor":"othervendor",
   "rejected_profiles":{"stub-sneaky":"same_vendor_as_implementer"}}
```

A reviewer from the same vendor that wrote the code is **rejected mechanically**. That is the
only defensible justification for multi-vendor at all: a model reviewing its own output shares
that output's blind spots. The constraint is hard — if there is no independent vendor, the
resolver fails rather than quietly handing the code to its own author for review.

---

## Step 8. What happens when a read-only role writes anyway

A stub reviewer that appends a line to a file while returning `verdict: pass`:

```
warden role reviewer hello --run-id trap
→ {"ok":false,"code":"role_violated_read_only"}
```

```json
"read_only_check": {
  "method": "worktree_content_fingerprint",
  "covers": "tracked edits, deletes, renames and untracked file contents",
  "does_not_cover": "paths ignored by .gitignore",
  "matched": false
}
```

The artifact is **not written**, whatever it claims. The comparison is by content fingerprint,
not by a list of paths: in an already-dirty tree — which is normal after an implementer has
worked — editing an existing file does not change the path list, and a check by name would have
failed open.

The uncovered case is named directly in every run's report rather than hidden in
documentation: Git does not see paths in `.gitignore`, so neither does the check.

---

## Step 9. What is left as evidence

```
.warden/runs/live/
  prompts/implementer.md            the exact prompt sent
  raw/implementer.stdout.txt        raw vendor output, always
  artifacts/implementer.json        the parsed artifact
  role-implementer.json             ledger
  evidence.jsonl                    append-only event stream
```

The ledger gets what needs no experiment — it accumulates by itself:

```
role            "implementer"      cost_usd         0.012
profile         "stub-impl"        num_turns        3
vendor          "stubvendor"       duration_millis  3
model_reported  "stub-impl-model"  prompt_sha256    37c3698c…
                                   artifact_sha256  94016435…
```

---

## Step 10. What happens when a subscription runs out

This step was not done on stub vendors. The Codex account's quota was genuinely spent at that
moment, and it turned out to be the most convenient test rig possible: a real refusal that did
not have to be faked.

The policy: two reviewers in order, Codex first **on purpose**:

```yaml
roles:
  reviewer:
    profiles: [codex-review, grok-review]
    strategy: first
```

```
warden role reviewer create-result --run-id live-stdin
→ {"ok":true,"profile":"grok-review","vendor":"grok",
   "rejected_profiles":{"codex-review":"quota_exhausted_this_run"}}
```

The role completed even though the first vendor never got to it. Both attempts are in the
report:

```json
"vendor_attempts": [
  {"attempt":1,"profile":"codex-review","vendor":"codex","code":"role_quota_exhausted",
   "quota":{"matched_signature":"usage limit","detected_by":"structured_error_event",
            "vendor_message":"You've hit your usage limit. … or try again at 9:21 PM."}},
  {"attempt":2,"profile":"grok-review","vendor":"grok","code":"ok","cost_usd":0.0259}
]
"failed_over_from": ["codex-review"]
```

Why this is a separate code rather than just "it failed". You cannot tell from the exit code:
Codex returns `1` both for an exhausted quota and for a typo in a flag. The difference is what
to do next. An ordinary error is correctly returned to the same vendor along with the failure
text; a spent subscription cannot be — the next call refuses identically, and the loop spends
the rest of the budget finding that out. Quota is the one failure whose correct answer is
**a different vendor**.

Three decisions are visible in that output:

- **The retry time is recorded verbatim and not parsed.** The vendor said "try again at
  9:21 PM": no date, no time zone. Any timestamp Warden derived from that would be a guess in
  the shape of a fact.
- **The exclusion lives in process memory, not in a file.** Within one loop, a vendor that ran
  out during implementation will not be called for review. A new invocation starts clean: a
  quota that reset overnight must not stay disabled by yesterday's file.
- **The budget counts vendor calls, not roles.** Failover adds a call; if the budget could not
  see it, one task with two switches would pay three times the permitted amount.

Detection works off the vendor's own wording — there is no other signal — so the sources are
ranked. A structured event (`{"type":"error"}`) is authoritative; stderr is accepted as the
weaker `stderr_text`; **free text on stdout is not considered at all**. That is where the
model's own words live, and a review that discusses rate-limiting code and then fails must not
be mistaken for a review that hit a limit. The price of that decision is false negatives: a
vendor that reports quota only in prose on stdout will be recorded as an ordinary failure.
Which is why a failure report now carries the tail of **both** streams: even an unrecognised
refusal is something the operator can read with their own eyes.

When there is nobody left to fail over to:

```
→ {"ok":false,"code":"role_quota_exhausted",
   "resolution":"every profile able to fill this role reported a spent subscription;
                 add a profile from another vendor, or wait for the quota window …"}
```

`warden run` stops with `reason: quota_exhausted`, not `implementer_failed`. These are
different events: one sends you to read a transcript in which nothing is broken, the other
sends you to wait for a window.

---

## Step 11. How the first live run found silent prompt corruption

The same run exposed a defect the stub tests could not see, because a stub is an `.exe` while a
real vendor installed from npm is a `.cmd`.

Windows cannot start a `.cmd` directly; it hands it to cmd.exe, and cmd.exe's command line is
line-based. Measured on that machine:

```
sent:      [exec, "# Reviewer line one\nsecond line\nthird line", --json]
received:  ARG1[exec]  ARG2[# Reviewer line one]  COUNT=2
```

The argument is cut at the first newline, **and every argument after it is silently dropped**.
The same argv reaches an `.exe` intact — which is why Grok, a single `grok.exe`, showed nothing
wrong, while Codex received a one-line prompt with `--json` torn off and looked like it was
working. Paying for such an answer and then judging it is worse than not starting.

At first Warden did not reach the vendor at all: `where.exe codex` returns the extensionless
npm shim on its first line, which Java cannot start (`CreateProcess error=193`). The executable
is now chosen by extension rather than by the order of `where.exe` output.

Then, a check before launching:

```json
{"ok":false,"code":"role_prompt_undeliverable",
 "argument_delivery_check":{
   "executable_is_batch_shim":true,
   "covers":"multi-line arguments passed through cmd.exe, which truncates them at the
             first newline and drops every argument after it",
   "does_not_cover":"cmd.exe expansion of %NAME% inside an argument",
   "undeliverable_arguments":["argv[2] (3660 chars)"],
   "resolution":"set prompt_delivery: stdin on this profile, or pass {{prompt_file}} …"}}
```

Nothing spent, the reason named, and the uncovered case — `%NAME%` expansion inside an
argument — named right there rather than hidden in documentation.

There is exactly one working channel for such a vendor:

```yaml
args: ["exec", "-", "--json", "--skip-git-repo-check"]
prompt_delivery: stdin
```

stdin passes through a `.cmd` shim undamaged — established by running it, not by deduction.

---

## Step 12. A tester with eyes

The tester here has two layers, and the layers answer different questions.

**The lower layer is the harness.** A real headless browser over CDP, zero npm dependencies.
The scenario is written in the task contract:

```yaml
visual_qa:
  required: true
  start: "npm run preview -- --host 127.0.0.1 --port 4173"
  url: "http://127.0.0.1:4173/"
  scenarios:
    - "1280x720: text=Save visible"
    - "1280x720: testid=save-button click -> css=.panel.open visible"
    - "700x400: css=#wide-only hidden"
    - "700x400: no-console-errors"
```

Matchers: `text=` (by visible text), `css=`, `testid=`, `role=`. Assertions: `visible`,
`hidden`, `click`. A bare `click` checks that pressing changes anything in the DOM at all;
anything more specific is written after `->` rather than guessed.

A live run against the fixture:

```
warden visual-qa looks-right --run-id green
→ {"ok":true,"code":"passed"}

.warden/runs/green/screenshots/
  1280x720.png
  1280x720-after-click.png
  700x400.png
  visual-qa.json
```

**The upper layer is the `visual_qa` role.** A model that receives the screenshots as
**attachments**:

```yaml
attachments:
  flag: "-i"          # codex: `-i shot.png -i after-click.png`
```

That is the whole difference between "the model was sent some filenames" and "the model
looked". Warden records which of the two happened: a profile with no `attachments.flag` is
marked `attachments_not_passed_to_vendor` rather than being silently promoted to "looked".

The role runs only after the harness has already agreed, and it answers only what a machine
cannot measure: clipped text, overlap, a collapsed layout. Its findings have the same shape as
the reviewer's, so they return to the implementer through the same mechanism.

By default the role is **off** in the policy. The harness is the floor and it is free; a
model's look costs a vendor call, and that is the operator's decision rather than the loop's.

---

## Step 13. Three things the harness stopped doing silently

Each was found by running, not by reasoning.

**It was testing somebody else's application.** Another project's SvelteKit app was already
listening on `127.0.0.1:4173`. The harness saw the port answer, did not start its own server,
and photographed a stranger's page. That time the check failed — but it could just as easily
have passed.

```
→ {"ok":false,"code":"visual_qa_port_occupied"}
   "something is already answering at http://127.0.0.1:4173/, and this task declares its own
    start command. Warden will not guess whether that server is this project or a stale one
    from another: stop it, change visual_qa.url to a free port, or drop visual_qa.start."
```

**It reported a viewport that never existed.** Mobile emulation switched itself on below a
width of 800, and a page with no `<meta name="viewport">` lays out at 980px in mobile mode. A
`700x400` scenario rendered at 980 and was called `700x400`. Measured:

```
asked for 700  →  viewport_effective: {"width": 980}  →  viewport_honoured: false
```

Now `mobile` is enabled explicitly (`"700x400 mobile: ..."`), and a mismatch between the
declared and the actual width is a scenario failure with an explanation rather than a silent
substitution.

**It knew the name of somebody else's button.** The harness code contained `.mode-switch`,
`.mobile-view-note` and `/growing/i` — one application's private DOM inside a tool that calls
itself general. And for any UI goal without an explicit control name, `warden do` filled in a
scenario with a `Create` button — the same one, from the same application. A guessed acceptance
criterion is the same mistake as a guessed blast radius, one field to the right. Now, if the
goal names no control, what gets written is what is true of any page:

```yaml
  scenarios:
    # Warden does not invent visual assertions. These two hold for any page:
    #   - "1280x720: text=Settings visible"
    - "1280x720: no-console-errors"
    - "700x400: no-console-errors"
```

A weak honest check that still takes a screenshot for the `visual_qa` role beats a confident
wrong one.

---

## Step 14. A goal in Russian

```
warden do "почини вёрстку экрана настроек" --scope ui
→ {"ok":false,"code":"goal_mangled_by_console_encoding",
   "message":"the goal arrived as \"?????? ??????? ?????? ????????\" …"}
```

The JVM decodes command-line arguments through `sun.jnu.encoding`; on that machine it is
`Cp1252`, and Cyrillic becomes `?` before `main` is entered. Verified:
`-Dsun.jnu.encoding=UTF-8`, `JDK_JAVA_OPTIONS` and `chcp 65001` change nothing. So a mangled
goal is rejected rather than written into a contract, and there is a channel that works:

```
warden do --goal-file goal.txt --scope ui
→ goal: "почини вёрстку экрана настроек"
   visual_qa: required: true
```

`warden doctor` prints this state under `argument_encoding`, so you learn about it before the
contract is full of question marks rather than after.

---

## How to substitute a real vendor

Replace one line in the profile:

```yaml
command: /tmp/vendors/review.sh     →     command: grok
```

and, for an unverified profile, run its `verification.probe`, confirm the four points in
`what_to_check`, and stamp `verified_on`. Until then the resolver will not release it.

**The order matters separately for Codex.** It is the only role with `read_only: false`, which
makes an unverified auto-approval flag the riskiest single thing in the whole pipeline. Run the
probe standalone first, then `--dry-run`, and only then let the loop drive it.

---

## What this walkthrough does not show

Everything above runs on stubs and a couple of small live probes. Since it was written, the
full chain has been run end to end against real vendors on a real project — a writing
implementer, machine gates, an independent reviewer on a second vendor, the browser harness,
and a `visual_qa` role that filed a P1 the machine could not see and ordered a fix round. That
is a different document: [`LIVE-CYCLE.md`](LIVE-CYCLE.md), with the costs, tokens and verdicts.

Still not shown anywhere, because it has not been done:

- **A live `runner: orca` role cycle.** The adapter is written to Orca's CLI contract: it
  creates no worktrees and completes only on `worker_done` or dispatch settlement. Until that
  runs against a live worker, it is not a proven path. Orca *did* create the worktrees in the
  live runs, but that is `OrcaIsolation` — a different code path.
- **Visual pixel-diff and baselines.** The harness checks assertions; it does not compare
  images. A task with `visual_qa.required: true` and no browser fails `visual_qa_unavailable`
  rather than passing quietly.
- **Quota detection from free-form stdout text.** Left out deliberately (Step 10): a false
  positive there costs money, a false negative does not.
