# Changelog

Notable changes to Warden. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the
pre-1.0 caveat that the configuration format may still change between minor versions.

Entries state what a release **can be run against**, not only what was written. A capability
that exists in code but has never been run live says so.

## [Unreleased]

### Changed

- The hygiene job now reads every commit a push or a pull request introduces, not only the
  resulting tree. A tip-only gate was green while a commit added three absolute paths to a
  document and the next commit reworded them away, and the paths were still in the history the
  push would have published; they were found by hand and removed with a history rewrite. The
  patterns and the file lists moved into `scripts/hygiene.sh` so the two scans cannot come to
  disagree, and the script is runnable before a push. Where the range cannot be established —
  a new branch or tag, a force-push whose starting commit was never fetched, a manual run — the
  job says so and reads the tip commit alone rather than claiming a range it did not read.

## [0.2.0] — 2026-09-06

The release where the loop was run against a repository that is not a web application, in a
language the heuristics were not written for, with a different vendor on each of two review
stages, and then accepted and landed. Nothing about that is exotic, and it found eleven things
the tool reported inaccurately — including two that cost money quietly, one that destroyed
evidence, and two that stood between an accepted run and the pull request it was for. They are
under **Fixed**.

### Added

- A Warden process killed while an Orca worker is running can be started again. The Run, task,
  dispatch and coordinator identities, and the read-only fingerprint taken at start, are written
  to `.warden/runs/<run-id>/orca.json` before the worker is launched, so the next invocation
  attaches to the same dispatch instead of opening a second agent in the same checkout. A
  worktree already held by anybody's supervised worker is `role_orca_worker_active` and costs
  nothing. An unreadable record, an unreachable `worker-list`, or a dispatch Orca will not
  describe is `role_orca_lifecycle_unaccounted`: "cannot tell" is never read as "nothing is
  running". Proven live by killing a run mid-flight and collecting its worker afterwards
  (`docs/SMOKE.md`).
- A pending human decision is mirrored into Orca as a decision gate, so it can be answered from
  another machine. `warden approve <run-id> --from-orca` imports the answer, and admits it only
  if it maps exactly onto one of Warden's own options, only while the pending decision has not
  moved, and — for an acceptance — only while the candidate fingerprint still matches.
  `decision.json` stays the only decision: measured on Orca 1.4.196, `gate-resolve` accepts free
  text and re-resolving a settled gate replaces the answer, and a gate edited from `retry` to
  `abort` after the fact left the recorded decision untouched. `--no-orca-gate` turns the mirror
  off; publishing never fails a finished run, and records the reason it did not publish.
- A question from an Orca-hosted agent is now a wait, not an ending. Warden acknowledges the
  question delivery, keeps watching for the role's whole budget, and reports
  `role_human_input_required` only if nobody answered in time — retaining the worker and
  recording the message id, so answering it and running the role again finishes on the same
  worker. Proven live end to end, including the reviewer quoting the answer it was given.

- A visual scenario can wait for something instead of waiting a number. `wait-for=css=.hearth`
  polls for up to 15 seconds and, unlike `wait 30`, can fail: the control never appeared. The
  declared ceiling is added to the adapter's timeout the same way `wait` is, so an honest
  contract is not reported as `visual_qa_unavailable`.
- Every scenario now writes a compact accessibility snapshot (role, name, bounding box,
  focused, ignored) next to its screenshots, ranked so the control the scenario named is
  first rather than lost under the page chrome, and `warden report` shows the frame taken
  after each step together with those names. A human at the gate sees click then result,
  not only the last PNG. An interactive node with no accessible name is kept and labelled;
  an ignored node is marked unreachable instead of looking like an ordinary control; a
  shared a11y line is printed once for the run, not under every scenario. Ranking is
  `--rank-a11y`, the same browserless shape as `--validate-only`.
- A required visual contract must name what it looks at with `testid=`, `role=` or `css=`, and
  is refused at preflight when a scenario goes by `text=` alone, before a vendor is paid. The
  copy on a control is the part a later task is free to change. A `text=` step alongside an
  anchored one is still fine, and a scenario that is only `no-console-errors` needs no locator.
  Asked per scenario: one anchored line never vouched for its neighbour.

### Fixed

Eleven defects found by running a live loop on a documentation repository, where the goal was
Russian prose, the acceptance command was a Python script, and both review stages were filled
by different vendors — then by accepting the result and landing it as a pull request. Every one
of them is a thing the tool said that was not true.

- **Two stages of one role no longer overwrite each other's evidence.** `review` and
  `review-second` are the same role at the same attempt, so both resolved to
  `<run-id>--reviewer-<attempt>/` and the reviewer that finished second replaced the first's
  prompt, raw output and artifact. The ledger kept both entries — it is append-only — and the
  surviving entry for the first reviewer then pointed at the second's files, so following it
  read as if it were that vendor's work. `warden report` printed the consequence: one row
  carrying another vendor's model, duration and token counts. A role dispatched by more than
  one stage now writes under the stage name, and the report joins each row to the evidence its
  own stage wrote.
- **A `rotate` role paired across two stages is no longer reordered by a failure.** The profile
  came from a counter shared by every run in the project and advanced once per dispatch, so a
  run that died before its reviewer, a fix round, or a bare `warden role` all shifted it:
  measured live, two failed runs left the third run's first review with the profile the
  operator had put second. A role with several stages now takes its position from the workflow,
  so the first review stage always gets the first profile. A role with one stage still rotates
  across runs, which is what that shape wanted.
- **A vendor call that failed is costed.** Telemetry was read from the envelope only after a
  valid artifact, so a grok implementer killed by its own turn ceiling reported
  `total_cost_usd: 1.33` and was recorded as free, and an Opus review that did the same hid
  $4.88. Warden told the operator "$0.2804 charged" for a run that spent $5.16. Cost, turns and
  tokens are now read before the outcome is judged.
- **A spent turn ceiling is named as one on every failure path.** The classifier existed but
  sat only on the exit-0 branch, and both measured vendors exit non-zero — grok prints
  `Error: max turns reached`, `claude -p` reports `error_max_turns`. The failure arrived as a
  flat `role_command_failed`, the same code as a broken flag, so the single knob worth turning
  was named nowhere.
- **A turn ceiling no longer throws away the stages that passed.** The run's reason is
  `turn_ceiling_reached`, which `--continue` treats as a failure that was never about the work:
  a run whose implementer and machine gates had both passed used to pay for both again because
  its reviewer ran out of turns.
- **The reviewer prompt no longer tells a model to run commands its profile forbids.** The
  template named `git diff` and `git ls-files` as the way to see the diff; a read-only profile
  granted Read/Grep/Glob can run neither, and one measured review spent 16 of its 74 tool calls
  being refused for doing as it was told, then hit its ceiling. The changed paths are now
  resolved by Warden and rendered into the prompt as `{{changed_files}}`, the git commands are
  offered only to a profile that has a shell, and the read-only rule says outright that having
  no shell is normal for this role.
- **A project that serves nothing gets no browser contract.** `warden do --draft-only` on a
  repository with no HTML, no server and a Python checker wrote `visual_qa: required: true`
  with two scenarios, because the UI markers match substrings and a Russian goal containing
  «формулировку» and «экрана» reads as a screen. The operator had to delete the block by hand
  in the one command whose purpose is to save them that. Words in a goal can suggest a screen;
  only the project can confirm there is one, and the draft now says why it declined.
- **`--dry-run` previews the real pairing.** The rotation counter was deliberately not advanced
  during a preview, so a chain with two review stages showed the same profile on both — the one
  arrangement that cannot happen. The preview exists to show who will be dispatched before
  anyone is paid, and the pair of independent readers is the part most worth seeing.
- **A `.warden` edit no longer invalidates an accepted candidate.** The fingerprint split
  that answers "is this what the human said yes to" filtered Warden's own tree out of the
  path list and not out of the raw-diff shape it hashes first, so a *tracked* file under
  `.warden` still moved it. The case that broke: `warden land` refuses to open a request
  until `project.yaml` declares a `land:` block, and adding that block made the same command
  refuse the run as `candidate_changed`. Untracked files never reach `git diff --raw`, which
  is why the existing test passed and the bug survived.
- **The commented `land:` example parses.** It shipped wrapped across two lines, and Warden's
  YAML is a strict subset in which a flow sequence does not continue over a newline —
  uncommenting it exactly as offered answered `unexpected end of flow collection`, from the
  parser that wrote it. The only instruction an operator has for the one step that reaches
  their forge was one they could not follow.
- **A failed profile probe says what to actually do.** A model the installed CLI was too old
  for came back under "fix the profile's args or authentication" with the vendor's own sentence
  — "requires a newer version of Codex" — buried in the stdout tail. Neither the args nor the
  credentials needed touching. Refused models, refused credentials and a probe killed by its
  wall clock are now told apart.

- The accessibility snapshot no longer stops at the first 60 nodes in document order, which
  on a real page is the skip link and the banner and never the control the scenario named.
  Ranking puts that control first, keeps unnamed interactive nodes, marks ignored nodes as
  unreachable, and `warden report` prints a shared a11y line once instead of under every
  scenario.

- `wait-for=css=.hearth visible` read the trailing assertion as part of the selector, looked
  for a `<visible>` element inside `.hearth`, and then reported a control that was on the
  screen as missing after a full poll. The last word is now read as the assertion it is,
  as everywhere else in the grammar; `hidden` and `click` are refused rather than swallowed.
- A drafted contract no longer invents a `data-testid` from a control whose name has no latin
  in it. The slug function names files and so never fails, answering `task-` plus a hash, which
  made a draft that was red by construction and told nobody what to build. Such a goal now gets
  the honest fallback plus a comment naming the control; a latin one gets the attribute to
  write spelled out.
- A timeout no longer reports an unaccounted lifecycle over a worker that is provably gone.
  Orca answers `dispatch_inactive` when asked to stop something already stopped, so a refused
  fence is now verified with `worker-show`: a settled or unknown dispatch is fenced, and only a
  dispatch still in flight, or one Orca will not describe, blocks later stages.
- A delivery carrying both a question and a `worker_done` read the question first, which
  reported a finished run as waiting on a person. Completion now outranks a question in the
  same delivery.
- Leaving a question delivery unacknowledged made Orca replay it to every later attach, so an
  answered worker could never be collected.
- A JSON argument to the Orca CLI lost its quotes on Windows: Java quotes only arguments
  containing whitespace, and a compact JSON array has none, so `["accept","reject"]` arrived as
  `[accept,reject]` and the first gate publish was refused `invalid_argument`.
  `OrcaClient.jsonArgument` escapes them, checked against the live CLI.
- Warden names the Orca worker terminal at start, which is what the Agent Dashboard shows for
  an agent that does not title itself. An agent CLI that sets its own title still wins, and the
  task's `display_name` is stored by Orca but not rendered in the row.

- `defaults.baseline_checks` — an optional named check set run in the worktree before the
  first vendor. Red is `baseline_failed` and zero vendor calls, so an agent is never asked to
  repair a breakage it did not cause. The same commands become the floor of the later
  acceptance gate (deduplicated, baseline first), so a narrower task check cannot hide a
  regression of project health. A baseline command that mutates project source is
  `baseline_mutated_source`. Existing contracts that omit the key keep their previous
  behaviour; `warden init` writes it only when it detected a real build. Covered by the
  suite. The layer cut this belongs to is [`docs/adr/0001-layer-split.md`](docs/adr/0001-layer-split.md).
- Orca worker fencing on the role runner: an unready `worker-start` is `worker-stop`'d or
  fails `role_orca_lifecycle_unaccounted`; an unacknowledged FIFO delivery is the same code;
  a native agent parked on input is `role_human_input_required` and the worker is retained;
  wall-clock expiry is `role_orca_timeout`, not Direct CLI's `role_timeout`. A live Claude
  Opus 5 reviewer cycle on Orca 1.4.196 is recorded in `docs/SMOKE.md`: visible Agent Dashboard
  activity, a real project gate, typed `worker_done`, FIFO acknowledgement, and worker release.
- Orca 1.4 serializes a message's `--payload` as a JSON string in inbox/check receipts. The
  settlement parser now normalizes that wire shape before checking task/dispatch provenance,
  outcome, and `warden_artifact`; malformed payloads and payloads for another dispatch still
  fail closed.
- `runner: local` — the third runner, named in the spec since the beginning and until now
  answering `role_runner_unimplemented`. It POSTs an OpenAI-compatible chat completion to the
  profile's `endpoint`, reaching a model already serving on this machine (Ollama, LM Studio,
  llama.cpp): no vendor subscription, and no process spawned, so no argv for a quote or a
  newline in the prompt to be torn apart by. `command` is not required, because nothing is
  started; the report carries `dispatch_preview` (method, endpoint, model) instead of naming a
  process that was never launched. `api_key_env` names an environment variable, read at
  dispatch and never written to evidence, the ledger or the raw transcript. A local answer is
  settled by the same functions `direct` uses — artifact, required fields, JSON schema,
  reported status, worktree fingerprint — so a role nobody can argue with cannot pass as a
  role nobody ran. Tokens come from `usage`; cost is never invented as `$0`. New codes:
  `role_local_endpoint_unreachable`, `role_local_api_key_missing`, `role_local_http_error`,
  `role_local_response_unreadable` — none of them handed back to an implementer as a fix
  round, because none of them is about the work. Implemented and covered by tests against a
  JDK `HttpServer`; no dated live smoke row yet.
- `Workspace`: a run writes its stage, cost and pending decision onto the Orca worktree card it
  is running on, and `warden approve` closes it. Three columns, not one per stage.
- The narration is also written to `.warden/runs/<id>/narration.log`, so a run started from a
  script, a scheduler or a terminal since closed still has an account of itself.
- `--watch`: Warden asks the board to open a terminal following that log, titled `warden
  <run-id>` and renamed `- NEEDS YOU` when the loop stops for a person. Warden calls Orca, not
  the other way round: the run is not hosted by the board and survives closing the window.
- A heartbeat while a stage runs. A role is one subprocess that prints nothing until it is
  finished, and the longest measured stage said nothing for twenty-two minutes — during which
  a hung vendor, a dead loop and a finished run look identical from outside. Once a minute the
  loop now says which role is holding it and for how long: a line in the narration, the same
  sentence on the workspace card, and `warden <run-id> · implementer 12m00s` on the tab. The
  card note at the start of a stage names the role too, not only the stage. Nothing here is
  evidence — every beat restates a fact the ledger will hold anyway — so a beat that cannot be
  written is dropped, a beat that throws cannot fail the run, a dry run has none, and a beat
  that wakes after its stage ended is refused rather than allowed to contradict the result
  printed under it. Verified live against Orca 1.4.194.
- The heartbeat, the stage-start card and the `--watch` tab name the profile and vendor filling
  a role, not only the role: `implementer (grok-implement / grok) still working` in the
  narration and on the card, `implementer (grok-implement)` on the tab. The name comes from the
  resolution that dispatched, not a second lookup — asking twice would rotate twice. A stage
  that runs no vendor (`gates`, `browser harness`) keeps the bare name and grows no empty
  brackets; a role whose verdict was carried over dispatches nobody and invents no pair.
- A fix round beats too. It is a second vendor call of the same length as the one that
  provoked it, dispatched after the operator has already spent half an hour, and it ran
  outside any beat: the one place a run is most likely to be waited on was the one place that
  went silent. It now runs under its own beat naming the role that took the work, and its card
  line gains the same bracketed pair: `fix 1/2 · review sent the work back to implementer
  (grok-implement / grok)`.
- `GitWorktreeIsolation`: `warden do` no longer needs Orca. Without `--isolation`, Orca is used
  when it is running and `git worktree` otherwise; `--isolation git|orca` settles it.
- `setup:` in `project.yaml` — what to run in a worktree Warden made, before any check can pass.
- Preflight validation of browser scenarios, via the adapter's own `--validate-only`, so a
  contract that states no assertion costs no vendor call. New code
  `visual_qa_contract_invalid`.
- `warden status --worktrees` lists every pending human decision across the worktrees of the
  current repository — worktree path, run id, task, kind, options, and the `warden approve`
  command including the directory to run it from. A checkout with no `.warden` is skipped;
  a `decision.json` that cannot be parsed is reported rather than dropped. Reads only.

### Changed

- `warden ledger` counts each actual vendor dispatch. A `role_run` that failed over is two
  attempts in `by_profile` / `by_vendor` / `by_model` / `by_outcome` and in cost/token/duration
  totals, not one nested footnote on the survivor. Historical ledgers without `vendor_attempts`
  stay one attempt per `role_run`. Failures gain a `baseline` bucket, separate from
  `machine_gate`.
- `verification.verified_on` now records that a profile's probe ran and passed, and `--verify`
  stamps it. It was documented as a human judgement that nothing enforced and nothing could;
  swapping the vendor behind a role is a config edit and one command.

### Fixed

- `warden land` no longer repeats the subject in the commit message it drafts, and no
  longer claims machine gates, an independent review and the browser harness all passed
  when they did not run. The sentence is built from the run's own `stages` and
  `skipped_stages`; a run that skipped nothing grows no empty skip clause. A stage that ran
  and did not pass is named first and named as failed, rather than dropped from the sentence
  the way it was: landing needs an acceptance and an acceptance needs a green run, so today
  that clause cannot fire, but the silence lived in the message builder rather than in that
  rule and the first path to `land` that skipped it would have inherited it.
- The candidate fingerprint no longer changes when the accepted work is committed.
  `git diff --raw` reports a zeroed destination blob while a change sits in the working tree
  and a real one once it does not, so `warden land --commit` invalidated the acceptance it had
  just acted on and `warden land --push` refused it as `candidate_changed`. Deletions, renames
  and mode changes are still caught; the blob ids were never what carried them.
  **A run accepted before this release cannot be landed after it:** its stored fingerprint was
  computed by the old definition, and failing closed on a value that cannot be compared is the
  correct answer. Commit and push such a run by hand.
- `--goal-file`: a goal with paragraphs was written into the drafted contract with raw
  newlines, and Warden then refused to parse its own file.
- The browser harness rejected `wait N -> no-console-errors` as "only waits" while accepting
  the bare console check, so adding a pause made a valid scenario invalid.
- `warden status` and `warden approve` from a directory that is not a Warden project now
  answer `not_a_warden_project`, naming that directory and saying the run may exist
  elsewhere. When the directory is a project that simply has no such run, `unknown_run`
  names the project that was consulted. The previous answer was `unknown_run` either way,
  which is how an acceptance typed from the wrong checkout looked recorded when it was not.
- `warden status --worktrees` now emits an `approve` instruction that can be pasted into
  PowerShell or cmd.exe: a concrete listed choice rather than `<retry|abort>`, and a
  directory change that switches drive (`pushd` on Windows, because `cd /d` is not a
  PowerShell command). A malformed `decision.json` in the current checkout is reported in
  `worktrees` instead of aborting the command as `warden_error`.

### Known gaps

Supersedes the list under 0.1.0, which stays as it was written:

- Per-vendor tool allowlists are not implemented. A profile's args are whatever you wrote.
- Visual QA has no pixel-diff or baseline comparison; the harness asserts, it does not compare
  images.
- The local runner is implemented and tested, but has no dated row in `docs/SMOKE.md`. A small
  model reached this way is cheap enough to run on every change and should be read as a smoke
  test, not as the independent review: a 4B model will happily return an artifact whose fields
  were copied out of the schema it was handed.

## [0.1.0] — 2026-08-31

First public release. Everything below already existed in the private history; this release is
the point at which the repository, its licence and its documentation became something a
stranger can use.

### Added — the loop

- `warden do` — the one operator command. Isolates the work in an Orca worktree cut from the
  branch the operator is actually on, drafts a task contract, and drives the bounded loop:
  implement → machine gates → fix ≤ N → independent review → fix ≤ N → browser harness →
  fix ≤ N → `visual_qa` role → fix ≤ N → human gate. It never merges.
- `warden run` — the inner loop, for a contract that already exists.
- `--draft-only`, which stops after the worktree and the draft contract exist, so browser
  scenarios can be written before any vendor is paid to satisfy them.
- `--init-repo` and `--in-place` for a directory that is not a repository yet. An empty
  directory gets a contract with no invented check command and a `<repository>` scope; a
  directory with files but an unrecognised build system is still refused.
- Declarative `workflow.stages` in `~/.warden/policy.yaml`: the order of stages, what each one
  runs, the closed set of conditions gating it, and where a failure or a finding routes.

### Added — roles and vendors

- Role resolver with rotation, `require_independent_vendor`, and refusal of any profile whose
  own probe has never run and passed (`verification.verified_on`).
- Direct CLI adapter with evidence, quota detection and delivery checks; JSONL recovery for
  vendors that stream their answer.
- Quota exhaustion classified apart from ordinary failure, with `failover.on_quota_exhausted`
  defaulting to `confirm`: a durable FAILOVER decision that names both vendors and what the
  swap costs in independence, authorising exactly one substitution when carried by
  `--continue`.
- Orca adapter (`runner: orca`) that attaches to the current worktree and completes only on
  `worker_done` or dispatch settlement. Written and tested; the full live lifecycle is not
  claimed as proven.
- `warden profiles --verify <name>` runs a profile's own probe and keeps the transcript;
  `--confirm` stamps the date, and only if the probe passed in the same invocation.

### Added — verification and evidence

- Machine gates over a pinned merge base, with blast-radius enforcement before and after every
  command.
- Contract integrity: the whole `.warden` tree except `runs/` is snapshotted before the first
  dispatch and compared after every role and around every gate command. Any change is
  `contract_mutated`, naming the path.
- Content fingerprint that survives renames and untracked files, used to discard the artifact
  of a `read_only` role that wrote.
- Append-only evidence ledger, `warden ledger` aggregation, and `warden report <run-id>`
  joining one run into a single view. A value the vendor never reported renders as `?` rather
  than becoming a zero.
- Cost honesty: `unpriced_calls` and `cost_ceiling_binding` state how much of a run the
  `max_cost_usd` ceiling actually measured. `max_role_runs` is the bound that always holds.
- Strict, dependency-free JSON and YAML subset parsers, and a subset JSON-Schema validator; an
  artifact that fails its schema is not written.

### Added — visual QA

- A project-neutral browser harness over CDP (`scripts/visual-qa.mjs`) with `text=` / `css=` /
  `testid=` / `role=` matchers, `visible` / `hidden` / `click` / `click@FX,FY` assertions,
  `no-console-errors`, and a `wait <n>` step that holds and photographs — without which
  everything the harness sees is the page 1.2 s after a click.
- A `visual_qa` role that receives screenshots as vendor attachments or as workspace paths it
  opens itself, and whose prompt states which of the two it got.
- Fail-closed outcomes that are distinct on purpose: `visual_qa_unavailable`,
  `visual_qa_port_occupied`, `visual_qa_no_evidence`.

### Added — the human gate

- Durable per-run `decision.json`: atomic, cross-process locked, with an optimistic-lock
  token. `warden status` and `warden approve` are the only ways in.
- `accept` is refused when the worktree fingerprint moved after the decision was shown.
- A rejection is an input rather than a full stop: `warden run --continue <rejected-run>` hands
  the reason a person gave to the next implementer verbatim, marked as a person's objection
  rather than a failed check, and authorises nothing.
- `retry` on a failure that was never about the work carries verdicts already reached, but only
  when both the source fingerprint and the entire contract are byte-for-byte unchanged;
  otherwise the reason is recorded in `reuse_declined`.
- `warden land <run-id>` plans and, with explicit `--commit` / `--push` / `--pull-request`,
  carries out the commit and request for an accepted run. The forge is never guessed. Merges
  nothing.

### Added — operability

- Stage-by-stage narration on stderr from `do` and `run`, while stdout stays a single JSON
  object. `--quiet` turns it off; nothing printed is evidence.
- `warden doctor`, including `argument_encoding`, which reports the Windows console encoding
  that silently turns a Cyrillic goal into question marks before `main` runs. Such a goal is
  refused rather than written into a contract; `--goal-file` is the channel that works.

### Added — for this release specifically

- Apache License 2.0, `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md` and this changelog.
- English-first documentation, with the Russian originals kept under `README.ru.md`,
  `docs/ru/` and `spec/ru/`.
- GitHub Actions CI across Linux and Windows on JDK 21 and 25, plus a hygiene job that fails
  the build if an absolute personal path, a machine-local identifier or a broken documentation
  link returns to the tree.
- `examples/demo/` — a reproducible fixture that runs a full red/green gate cycle offline with
  no vendor, no key and no network, and a committed sample evidence ledger.

### Known gaps

These are named rather than hidden, and the code refuses rather than pretending:

- `runner: local` is a name; the resolver answers `runner_unimplemented`. *(Closed after
  0.1.0 — see Unreleased.)*
- Per-vendor tool allowlists are not implemented. A profile's args are whatever you wrote.
- Visual QA has no pixel-diff or baseline comparison; the harness asserts, it does not compare
  images.
- The Orca adapter's full live lifecycle as a role runner has not been proven end to end.

[Unreleased]: https://github.com/tolboy/warden-agentic-sdlc/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/tolboy/warden-agentic-sdlc/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/tolboy/warden-agentic-sdlc/releases/tag/v0.1.0
