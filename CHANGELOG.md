# Changelog

Notable changes to Warden. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the
pre-1.0 caveat that the configuration format may still change between minor versions.

Entries state what a release **can be run against**, not only what was written. A capability
that exists in code but has never been run live says so.

## [Unreleased]

### Added

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

- `verification.verified_on` now records that a profile's probe ran and passed, and `--verify`
  stamps it. It was documented as a human judgement that nothing enforced and nothing could;
  swapping the vendor behind a role is a config edit and one command.

### Fixed

- `warden land` no longer repeats the subject in the commit message it drafts, and no
  longer claims machine gates, an independent review and the browser harness all passed
  when they did not run. The sentence is built from the run's own `stages` and
  `skipped_stages`; a run that skipped nothing grows no empty skip clause.
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
- The Orca adapter's full live lifecycle as a role runner has not been proven end to end.
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

[Unreleased]: https://github.com/tolboy/warden-agentic-sdlc/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tolboy/warden-agentic-sdlc/releases/tag/v0.1.0
