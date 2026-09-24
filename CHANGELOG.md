# Changelog

Notable changes to Warden. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the
pre-1.0 caveat that the configuration format may still change between minor versions.

Entries state what a release **can be run against**, not only what was written. A capability
that exists in code but has never been run live says so.

## [Unreleased]

### Fixed

- `warden land --commit` commits the accepted paths and nothing else in the index. It ran
  `git add -- <paths>` and then a plain `git commit`, which takes the whole index: a file the
  operator had staged under `.warden/`, or a staged edit whose working copy was put back to
  HEAD's, rode into the accepted commit, and the source fingerprint could see neither. The
  commit is now `git commit --only -- <paths>` with literal pathspecs; whatever else was
  staged stays staged and out of it, and the report lists it as `left_staged`. The plan
  prints the argv that runs and names the paths the commit will hold, computed before
  anything is written; an accepted path already at HEAD is `already_committed` rather than
  committed again, so `land --commit` followed by `land --push` pushes instead of failing
  with "nothing to commit". A commit that still differs from the plan (a pre-commit hook
  that stages more) stops as `commit_differs_from_plan` before the push, and one with the
  planned paths but other content — a formatter hook that rewrites an accepted file and
  stages it again, even in the index alone — stops as `commit_differs_from_accepted`: land
  compares the blob and mode of every path in its commit (`ls-tree`) with what it staged
  from the accepted working tree before the hook ran (`ls-files -s`). A commit refused
  either way is recorded beside the run's decision (`land-refused.json`), and every later
  `land --commit` or `--push` refuses as `refused_commit_in_history` while that commit is
  reachable from HEAD: with the source already at HEAD, a second `--push` used to have
  nothing to commit, nothing to check, and published the commit the first had refused. A
  deletion already staged, as after the `git reset --soft HEAD~1` the refusal recommends,
  is no longer handed to `git add`, which rejected it. Found by the 2026-09-22 review
  (CORE-04) and the review of this change. Suite `land`, new.
- A judging role cannot be given a profile that may write. A reviewer copied from an
  implementer with `read_only: false` and the writer's vendor was labelled `none` by the
  resolver, which asked the independence question only of read-only profiles, so it passed
  `require_independent_vendor: true` and read its own vendor's work; the task's write
  authority let it past the one other check. `reviewer`, `visual_qa` and `plan_reviewer`
  profiles with `read_only: false` are now refused as `judge_not_read_only`, pinned or not,
  and a chain whose judging stage has no other candidate stops before the first dispatch
  under that name, naming the profile; preparation stops before the planner is paid. Found
  by the 2026-09-22 review (CFG-04). Suites `role-resolver`, `role runner`, `task loop`,
  `planner`.
- `warden roster model` clears a stamp written as a flow mapping. It removed whole lines
  holding `verified_on`, so `verification: { verified_on: 2026-08-27 }` — the spelling
  `ConfigTest` itself uses — survived the switch and the new model read as verified. The
  key is now removed from the `verification` mapping in its block or its flow form, keeping
  the other entries and a trailing comment, and the profile is parsed before it is written:
  one that would still read as verified without `--keep-verified` is refused as
  `verification_not_cleared` and left untouched. Found by the 2026-09-22 review (CFG-01).
  Suite `roster`.
- `warden pilot prepare` no longer fails one run in three to ten on Windows. The bundle is
  published with one directory rename, which Windows refuses with `AccessDeniedException`
  while a scanner still holds a file in the tree written a moment before; the fallback moved
  again with `COPY_ATTRIBUTES`, which `Files.move` never supports, so the refusal was final.
  The rename is now retried while the tree is busy, up to two seconds, and never over an
  output that exists. The `pilot-prepare` suite, which counted 7, 129 or 136 checks between
  runs of one build, counted 145 in 25 consecutive runs. Suite `pilot-prepare`.
- A prompt delivered on stdin (`prompt_delivery: stdin`) can no longer hang the controller
  past every limit. `ProcessRunner` wrote the whole prompt before it started reading the
  child's output and before it started the timeout. A vendor that never read a prompt larger
  than its pipe held that write, and the run, for as long as it lived: no timeout, wall clock
  or chain deadline applied, and no human gate appeared. One that printed before reading
  deadlocked for good, because each side waited on the other. Measured on Windows against the
  old runner: an 8 MB prompt to a child that ignores stdin was still blocked when the test's
  60 s bound expired (timeout 2 s), and a child that prints 4 MB first never returned. The
  output readers now start first, and the prompt is written on its own thread under the
  timeout. On a stop, the process tree is killed, which breaks the pipe and ends the write.
  The stop signals through `ProcessHandle`, not `Process.destroy`: on Unix the latter closes
  stdin after the signal, and that close waits for the lock the blocked write holds, so a child
  that ignored SIGTERM kept the stop inside `destroy` until it exited by itself. Measured in a
  Linux container with a child that ignores SIGTERM and sleeps 40 s: 40.1 s before, 3.1 s
  after (killed after the 2 s grace). Suite `runtime`.
- `warden roster model` switches the model the vendor is asked for, not only the label.
  A direct profile that spells the old model in `args` (every hand-written profile on the
  maintainer's machine passed `--model opus` literally) now has that item rewritten to
  `{{model}}`, and so does the model after a model flag in its `verification.probe`. A
  profile whose args pass no model at all is refused as `model_not_forwarded`, as effort
  already was. `warden profiles --verify` fills `{{model}}` and `{{effort}}` in the probe, so
  the stamp is earned by the model the profile declares. Found moving the roster from Opus 5
  to Opus 5.5 on 2026-09-22. Suites `roster`, `profile verifier`.
- An Orca worker that never took its first turn is not a verdict on the work. On a live run
  in a fresh worktree, Claude Code sat on its once-per-repository trust question, Warden
  fenced the worker after 45 s as `role_orca_start_failed`, the stop was `reviewer_failed`,
  and the `--continue` that followed paid the implementer and the first reader again for a
  tree they had passed. `role_orca_start_failed`, `_no_coordinator`, `_unavailable`,
  `_no_worktree` and `_worker_active` now stop as `orca_worker_not_started` (and
  `role_orca_timeout` as `role_timed_out`), which a continuation carries verdicts past; a
  summary written before this is re-read from its failed step's code. Before fencing,
  Warden keeps the agent tab's last screen as `agent_screen_tail` and names a trust question
  or a CLI update prompt as `agent_blocked_on` with the one action that clears it. Suites
  `workflow`, `next-step`, `orca-settlement`.
- `warden do "…" --watch --use implement=…` no longer corrupts the goal. The positional parser
  read every flag it did not list as taking a value, so `--watch` swallowed the `--use` after
  it, `implement=…` joined the goal, and a contract whose goal was on disk word for word was
  refused as `task_conflict`. `do` now knows which of its flags take a value and refuses an
  unknown one by name; a `task_conflict` names the fields that differ and prints both sides.
  Suite `do-command`.
- An Antigravity (`agy`) spent plan that exits 0 with `Individual quota reached` in the
  JSON `error` field is `role_quota_exhausted`, so failover can switch. bakery-agy-3's
  look used to stop as `role_artifact_incomplete` because the envelope was missing
  `verdict`. Suite `quota signal`.
- A confirmed vendor swap authorises the successor after quota; it is not a pin that
  skips the spent vendor. Pinning it dropped the `role_failover` event the continued
  run has to write. Suite `task loop`.
- A contract proposal on Windows no longer requires `toRealPath()` to equal the
  unresolved path. GitHub's runner spells temp dirs as an 8.3 name or a `\\?\`
  prefix, `prepare` swallowed that as an empty proposal, and `operator-continuation`
  died as `proposal_changed`. Confinement compares real paths. Suite `operator-continuation`.
- A visual task with `evidence: agent` is not a browser contract. Preflight used to ask
  `scripts/visual-qa.mjs --validate-only` about every required scenario, so a Unity look
  that said "photograph the hop-aside" died as `visual_qa_contract_invalid` for want of
  `testid=`. Dry-run stayed silent when node was not on PATH. The adapter is asked only
  when the harness will actually run. Suite `visual qa`. Live-caught on `bakery-agy-1`.
- A strict money cap looks at the profile routing will actually dispatch: a `--use` pin,
  a `--host` twin, an escalation rung and an authorised substitution, not only the policy
  list. A rate-limit retry is charged before the next attempt is admitted, and an unpriced
  attempt is charged at its declared bound against the cap only: it stays in
  `unpriced_calls`, stays out of `total_cost_usd`, and the charge is reported as
  `cost_cap_unpriced_charge_usd` (also on the chain, so a continuation keeps it).
  `--prepare always` keeps operator `budgets` blocks that use CRLF or sit under a comment.
  Suites `planner` and `task loop`.

### Added

- Per-run roster overlays so a Unity loop does not rewrite `~/.warden`. `warden run` and
  `warden do` take `--use <stage>=<profile>`, `--effort <stage>=<level>` and
  `--host <stage>=orca`; a task may declare the same under `use:`, and flags win. Overlays
  carry into `warden do --conductor` through the run's own `run-override.json`, and survive
  a replan: `use:` is an operator block like `visual_qa`. `verified_on` is never touched.
  Every overlay is checked against the roster before the first paid stage, and refused there
  rather than honoured in name: a stage nobody in the workflow answers to, an effort the
  runner cannot deliver, a host the profile cannot travel to. Suite `run-override`.
- `--host <stage>=orca` moves a stage to the `runner: orca` profile the operator declared
  for that role and vendor, so the Agent Dashboard shows a WORKING row. It does **not**
  rewrite a direct profile into an Orca one: `worker-start` forwards agent, model and effort
  and nothing else, so tool grants, sandbox, turn ceiling and MCP configuration would be
  dropped and the direct profile's stamp would be carried onto a channel it never probed.
  A profile with none of those is hosted in place; anything else is refused before a vendor
  is paid, with the twin to declare named in the message. Not live-proven against
  `worker-start` on this change.
- `cast` on the run header and in `task-run.json`: which profile fills each stage, at what
  model, effort and runner, with a per-run overlay marked. Printed before the first dispatch
  and in `--dry-run`, so a roster change can be checked without paying for one. The Orca
  card carries the short form (`implement(grok) -> review(openai) -> look(claude)`).
- Screenshots reach the board. When a run stops for a person, up to four of the images the
  visual stage was judged on are opened in Orca through `file open`, so an `accept` answered
  from a phone is answered while looking at the pixels rather than at a digest.
- `advance` on a failure gate. It closes the run and starts the next id of the same task
  (`--continue`, writers carry, verdicts do not). `--no-start` records the decision and
  prints the command. The Orca gate question names the next step and that advance starts
  the following run. Older `retry`/`abort` files still parse. Conductor's own human gate
  still offers only abort and retry: a decision node that started a nested run would put a
  second retry controller inside the one Warden already is.
- A task may require visual QA with `evidence: agent` and no browser viewport grammar. The
  loop then drops the CDP harness for that run and the visual role takes its own pictures.
  Copy `examples/mcp/unity.json` to `~/.warden/mcp/visual.json` (default
  `http://127.0.0.1:8081/mcp`), and `warden profiles --verify claude-visual-qa-mcp --confirm`
  before a paid look. An unverified camera now stops the run *before* the writer is paid.
  The Bakery task opts in.

### Fixed

- A new run id on a tree an earlier run of this task left no longer marks every reader
  `unproven`. Bakery-7 paid two independent vendors and was then told nobody could be called
  independent, because the run folder had a different name. The writer set is inherited from
  the run whose recorded closing tree **is** this tree — same immutable diff base, same
  candidate fingerprint as the human decision recorded — and from no other. A prior run that
  did not know its own writers is not a source, and a writer dispatched by *this* run proves
  nothing about bytes that were in the tree before it: an unexplained candidate stays
  `unknown_preexisting_candidate`, and every reading over it stays `unproven`.
- A confirmed vendor substitution survives `advance` and `apply`. Those decisions used to
  start the next run with an empty failover map, so a second reader switched after a quota
  stop was silently put back on the spent profile. The substitutions recorded on the prior
  summary travel with the continuation; verdicts still do not, because the contract moved.
- An operator pin that has already reported a spent subscription this run is dropped rather
  than re-dispatched. Rotation and failover can then pick the backup the roster still has.
- Direct stages are named as Orca orchestration tasks on the run the card belongs to, so a
  phone can see `review-second · claude-review` without rewriting the roster onto
  `runner: orca`. Agent Dashboard WORKING rows still need a verified Orca profile. Not
  live-proven against `task-create` on this change.
- `--use <stage>=<profile>` no longer waives the task's `require_independent_vendor`. The pin
  reached the roster through the escalation ladder's path, which skips the independence
  filter by design, so choosing a reader quietly bought a `peer_review` the contract had not
  agreed to. An operator's pin is now filtered like any other choice and refused with the
  resolver's own reason; the ladder keeps its exception, because a rung is a pair the policy
  declared.
- `advance` no longer spends a run id proving nothing changed. The stops it is offered for
  are caused outside the loop — a scope that excludes the file, a contract that forbids the
  change, a failing baseline — and a second pass over an unchanged contract and an unchanged
  tree reaches the same line. When neither has moved, `advance` says so and prints the
  `warden run` command for starting one anyway.
- A task's limits bound the chain of runs `--continue` links, not each run. A `retry` or
  `switch` continuation inherits the calls, reported cost, unpriced calls and fix rounds the
  earlier runs spent, read from the new `chain` block of their summary; before, every
  continuation started with a fresh ceiling and fresh fix rounds. A rejection still starts a
  new chain. `role_runs` and `total_cost_usd` stay per run.
- A stop caused by a vendor's endpoint is no longer a verdict on the work. A timeout, a failed
  vendor process, an unreadable or schema-invalid artifact and an undeliverable prompt used to
  stop the run as `<role>_failed`, which every `--continue` treated as an objection and so paid
  every passed stage again. They are now `role_timed_out`, `vendor_call_failed`,
  `vendor_protocol_failed` and `prompt_undeliverable`, the summary names the failed call as
  `infrastructure_failure`, and a continuation reuses the verdicts that still describe the tree.
  `quota_exhausted` and `failover_requires_confirmation` join them: the first told the operator
  to wait and continue and then declined every verdict on the continuation. The first external
  trial paid a second Codex reading this way. No implementer fix round is spent on any of them,
  except for a writer's own unreadable artifact.
- A `switch` answered on a failover stop carries earlier verdicts. It used to carry only the
  substitution, and run `p2-planner-3` paid its writer thirty minutes to redo its own candidate.
- A rate limit is no longer read as a spent subscription. `QuotaSignal` keeps separate phrase
  lists; a 429 or "rate limit" becomes `role_rate_limited`, which stops the run as
  `rate_limited` without dropping the profile or offering a replacement vendor. A spent-plan
  phrase still wins when both appear, so Claude's session-limit envelope stays a quota stop.
  Measured only offline, against recorded and synthetic transcripts.
- Found by the independent review of the three items above, before merge: a spent-plan phrase
  on stderr now outranks a rate-limit phrase in the event stream; an Orca call the chain
  deadline cut short stops as `budget_exhausted` like a direct one; a failover the budget
  cannot afford returns the spent call instead of throwing past it, so its cost and unpriced
  count are no longer lost; and an Orca worker that reports failure or escalates stops as
  `role_orca_reported_failure` rather than as an endpoint failure a continuation would carry
  verdicts past.
- Found by the review of the pull request: a `--continue` that failed validation wrote a
  summary with no `chain`, so the run continuing it started from zero and could dispatch past
  the task's call ceiling; it now records `continued_from` and the chain it joined. The next
  step no longer previews under the id its live run needs (a dry run reserves it), proposes an
  acceptance edit only for a `contract_gap` finding (other non-product blockers are
  `resolve_blockers`), and `pilot prepare` no longer reads a JSON Schema `$ref` fragment such
  as `#/$defs/text` as a file path.
- `warden pilot prepare` no longer interpolates a profile command into `sh -c` on POSIX.
  Presence is a filesystem PATH walk (Windows still uses `where.exe` as argv), so a value
  such as `git; touch marker` cannot run during offline preparation. Live `run`/`role`
  lookup is unchanged.
- `bin/warden` exports `WARDEN_HOME`. The POSIX launcher computed the home and never exported
  it, so a run started from a target repository could not find `scripts/visual-qa.mjs` and
  stopped `visual_qa_unavailable` after its reviews had already been paid for. `warden.cmd`
  always set it. Found on the first external trial, 2026-09-15.
- A Claude session limit is recognised as an exhausted subscription. The Claude CLI reports it
  as a `result` envelope with `is_error: true` and HTTP 429, which the matcher did not read,
  and "session limit" was not a known wording; three calls on that trial were recorded as
  `role_command_failed`. The `result` key is read only when the vendor flags the envelope as
  an error, so an answer that merely discusses limits is not misread. Suite-covered; not yet
  observed on a live run since the fix.
- Global ledger coverage now matches individual vendor attempt IDs. A journaled call
  no longer suppresses unrelated calls sharing a run instance or operator label.
  Missing attempt identity remains ambiguous rather than inferred from run identity.
- Import conflicts and delivery failures persist an incomplete receipt and withhold
  exact global spend. Successful replay of the same source reconciles failed delivery;
  older contradictory receipts remain visible. Unsuccessful imports exit nonzero.
- Contract-window helper scripts stay in run evidence instead of modifying the task
  directory. Windows helpers include a UTF-8 BOM for PowerShell 5.1 paths, and dry
  runs do not open contract windows or mark the card as waiting.
- `--dry-run` names a role no profile can fill. The preview finished `ok` with
  `role_unresolved` left inside its steps, while the real run would stop before its first
  call; it now sets `would_stop` to the reason the real run stops with, and `resolution`
  to the refused profiles and why each was refused.
- `Workspace.guarded` forwards `show`. The wrapper inherited the interface's empty default,
  so a caller holding a guarded board would have lost the contract window without an error;
  `warden do` happened to hold the unguarded one.
- `warden do` resolves the Orca board only when it has something to write to it. A preview,
  a hand-drafted contract or a Conductor launch no longer asks Orca twice for nothing.
- The pilot template's `timeout_minutes` was 2. It is shared by every command of one gate
  run, so a real baseline would have stopped the trial before its first call; it is 30, and
  the README says to keep validated profile wall clocks rather than tighten them.

### Added

- **Who wrote the code is measured, and every reading says how far it is from them.** The
  summary carries `writer_vendors`, `writer_profiles` and `writer_provenance`: the set grows
  with every writer call, including a failed one and a continuation, and is restored from the
  `chain` block. A reader is independent of the whole set, not only of the last implementer,
  and each reading carries an assurance label: `independent`, `same_vendor_peer`, `peer_review`
  (a pinned assignment), `coauthor`, or `unproven` when the provenance is not known — which is
  never read as independent. `same_vendor_peer` is opt-in twice: a named implementer/reviewer
  pair with different models under `roles.reviewer.same_vendor_peer` in policy, and
  `review_assurance: same_vendor_peer` on the task. `require_independent_vendor: true` stays
  strict; a contradictory configuration is refused; the human gate shows the label. The reader
  preflight asks every reader against the writer's vendor before the writer is paid:
  `independent_review_unavailable` with zero calls and `rejected_profiles` naming the resolver's
  reason per profile. Suite-covered; not run live.
- **An escalation ladder by explicit policy.** `escalation.after_blocking_reviews: N` and
  `rungs: [{implementer, reviewer}, …]` pin a different pair to the stages after N blocking
  reviews of the same candidate; assignments are pinned per stage and restored on a
  continuation, never chosen by rotation over a changed roster. Running out is
  `quality_exhausted`; a rung nobody can fill is `escalation_unavailable`. Suite-covered.
- **A continuation reuses the writer's last fix round.** When a fix round moved the tree, the
  writer's own row for that tree is reused under the implement stage instead of paying the
  writer to reproduce a candidate it already wrote; a retired review is still not carried.
- **A second planner.** The `plan_reviewer` role (read-only) reads the compiled contract, not
  the draft. Blocking findings send the first planner back once with a `prepare-redraft.md`
  context; a second refusal stops as `plan_review_findings_remain`; a reviewer that moved the
  tree is `plan_reviewer_protocol_violation`. The summary carries `plan_review`, both calls
  count in the preparation budget, and `warden setup` ships `prompts/plan-reviewer.md`,
  `schemas/plan-reviewer.json` and an unverified `agy-plan-review` profile (Gemini 3.8 Flash
  through the `agy` CLI, whose calls report no price). Suite-covered; the profile's probe has
  not been run.
- **The reservation is re-measured against the compiled contract** (`P2PLAN-17`). The
  estimate made before a contract exists is kept as `budget_plan_at_reservation` (phase
  `reserve`); once the planner has compiled the contract the plan is measured again against
  it (`budget_plan.measured_against: compiled_contract`, phase `prepared`), and
  `reservation_matched_contract` says whether the two named the same paying stages. The
  historical estimate is never rewritten.
- **`retry` on a failover decision.** After the cause is removed, the same profile runs again
  and earlier verdicts are carried, alongside `abort` and `switch`.
- **A human gate that expires.** `budgets.gate_ttl_hours` writes `expires_at` on the decision;
  `warden status` shows `expired`; accepting after expiry is refused as `gate_expired` unless
  `--acknowledge-expired` is passed; waiting on a gate ends at its expiry.
- **A money cap that is a cap.** `budgets.cost_cap: strict` reserves a proven upper bound per
  call from each profile's `limits.max_cost_usd`; a roster without one stops as
  `cost_cap_unenforceable` before the first call. Without it, `max_cost_usd` stays what it
  was: a threshold on reported spend, and the preview still says so.
- **A bounded retry on a transient rate limit**, opt-in: `retry.rate_limited: { max_attempts,
  backoff_seconds }` in policy (default `max_attempts: 0`). The pause is the vendor's reported
  retry-after when the evidence carries one, otherwise exponential backoff; every pause is on
  the role report as `rate_limit_retry`. No failover, no dropped profile. Suite-covered with a
  test sleeper; no live rate limit was retried.
- **`warden ledger --compare [--baseline-file PATH] [--text]`** (P6b, the small form): two
  slices of the corpus side by side — calls, reported cost and unpriced calls, duration, fix
  rounds, stops by class, roster — with no new dashboard. The protocol for using it is in
  [`docs/HYPOTHESIS.md`](docs/HYPOTHESIS.md).
- **`warden roster`**: which profile fills which role and in which workflow, `roster set` to
  change a role's profiles and strategy, `roster model` to change a profile's model and effort,
  each with a timestamped backup of the file it rewrote; changing a model drops
  `verification.verified_on` unless `--keep-verified`.
- **A run timeline on the dashboard**: stages, calls, fix rounds and stops of a run, read from
  the ledger.
- **A visual role that takes its own screenshots.** A workflow stage may declare
  `evidence: agent` instead of `sees:`; it needs no browser harness. The profile names the
  MCP servers the vendor may reach (`mcp.config`, in the vendor's own format, handed to the
  vendor's own flag through `{{mcp_config}}`) and declares `capabilities.vision.acquires:
  true`. The role saves what it judged under the run's `screenshots/` directory (the prompt
  is told where, and `{{evidence_dir}}` is available in args) and lists the files in
  `screenshots_taken`; Warden verifies each one exists inside the run's evidence and is not
  empty, hashes it onto the step row as `image_evidence`, and refuses a verdict that lists
  none as `visual_qa_no_evidence` — a stop that is not about the work, with no fix round.
  The configuration file's digest is a term of the role contract (`mcp_config_sha256`).
  Before the first call, a stage with `evidence: agent` filled by a profile that does not
  acquire stops as `visual_qa_unavailable`, and a profile whose `mcp.config` file is missing
  stops as `mcp_config_missing`; the dry run names both. `warden setup` ships an unverified
  `claude-visual-qa-mcp` template whose probe must be run against a server of yours. Suite-
  covered with a stand-in vendor; no real MCP server has been tried, and what a screenshot is
  a picture of is not verified by Warden.
- **`verification.expect`**: a substring the probe's stdout has to contain before
  `warden profiles --verify` stamps `verified_on`. Without it the stamp was exit code alone,
  and a headless CLI whose tool call was auto-denied exited 0 with an empty response and was
  stamped verified (measured with `agy` on 2026-09-18). The report carries `expected` and
  `answer_found`; the next step names the refusal. The shipped `agy-plan-review` profile now
  expects the schema title it is asked to read, adds `--add-dir .` to its probe and
  `--dangerously-skip-permissions` to its args, with the measured consequence spelled out in
  the file: `--mode plan --sandbox` do not stop that CLI from writing inside `--add-dir`, so
  the read-only guarantee for the role is Warden's fingerprint check, not the CLI.
- **A policy file that does not parse stops the run** as `policy_invalid`, with the parser's
  complaint in `policy_problem`, before anything is dispatched — dry run included. It used to
  be treated as no policy: the built-in chain ran, every role stage was skipped as
  `role_not_configured`, and a dry run previewed `ok` over a candidate nobody would write.
  Measured on a multi-line flow mapping in `roles:`.
- `budgets.max_elapsed_minutes`: an execution deadline for a task, summed across the runs a
  `--continue` links and not counting time spent waiting for a person. No vendor call starts
  with less than a minute left, each call's wall clock is lowered to what is left, and a
  lowered call that times out stops as `budget_exhausted` with `budget_limit_hit:
  max_elapsed_minutes`. Gates keep `timeout_minutes`. Suite-covered with a test clock; not a
  live claim.
- Optional task `reproduce` proves the acceptance fails on the unchanged source tree before
  the first vendor call. Green reproduction stops `reproduction_passed_before_change`;
  a timeout, a command the shell cannot find, an internal error or a source mutation stops
  `reproduction_inconclusive`. Continuations over candidates carry only verified evidence for
  the same acceptance and diff base; missing proof is recorded without stopping. Dry-run
  previews the checks. A task without `reproduce` keeps its acceptance hash.
- `warden pilot prepare` validates reproduction membership in acceptance, writes `reproduce`
  into the task, and reports `enforced_by_warden_run`; omission explicitly leaves acceptance
  strength unchecked. This is fixture-tested and makes no live-pilot claim. An optional
  `budgets.max_elapsed_minutes` in the spec is written into the task.
- Reviewer findings carry a vendor `suggestion` when one was supplied, omitted otherwise.
  It is not part of finding identity, derivation, registry comparisons or any hash.
- A structured `next_step` beside `safe_next_step` on a stop and on `ready_for_human`: kind,
  one sentence, the complete commands with real run and task ids, edits, consequences, and
  (for `fix_contract` / `repair_or_retry`) the open blocking findings with suggestions. A
  `contract_gap` names the task file, the acceptance currently in force, the reviewer's
  suggestion and that editing it moves `acceptance_sha256`, so no verdict of this run can be
  reused. `warden report --text` prints the block when the field exists and keeps the
  one-line sentence for older summaries; `warden status <run-id>` includes it when present.
- `warden approve <run-id> --from-orca --wait-minutes N` (1..1440): bounded poll, 5 s then
  ×1.5 capped at 10 s, while the Orca gate is pending or unreadable. The answer that is
  finally imported still goes through the existing validations. A deadline is `gate_pending`
  with the decision file unchanged; no answer is never accept or reject. The wait never
  starts, retries or continues a run. `warden run --wait-for-gate N` is the same waiter after
  the loop, skipped (with the reason) when no gate was published.
- `warden pilot prepare --spec FILE --output DIR`: deterministic offline preparation of an
  external-pilot bundle from an explicit JSON spec (task, target, separate baseline and
  acceptance commands, two existing profiles). Writes a new reviewable directory with target
  YAML, isolated config home, observation sheet and runbook. Validates with existing parsers
  before publish; never overwrites DIR; never edits the target checkout or global home; never
  runs checks or vendors. One P2 slice — not all of P2, not P2PLAN-17, not P3/P4.

- `examples/pilot/`: opt-in policy, task template, measurement protocol and a per-trial
  observation sheet for one observed external trial with a fixed writer, independent
  reviewer and one repair. The parser, CallPlan and dry-run path are verified with a roster
  that resolves, and with one that does not. No automatic continuation, overall deadline,
  strict money cap or live external result is claimed.

- A durable home measurement corpus at `<UserConfig.home>/ledger/`. Every existing evidence
  producer writes through one `EvidenceLedger.append`: local evidence first, then an
  allowlisted journal, then this writer's segment under the home, then a best-effort
  delivery mark. Recovery replays delivery, not execution. New paid dispatch refuses
  with `ledger_unavailable` while the corpus cannot be written; settlement of a running
  worker and a human decision are never blocked for analytics; the CLI reports
  `corpus_status` and whether the tree is safe to delete. The projection is an
  allowlist and carries no goal, prompt, vendor reply, finding text, argv or credentials.
  Project identity is not the path. `warden ledger` stays local by default, with the
  same fields and numbers for a well-formed tree. `--global` reads the home corpus from
  outside a repository, filtered by recorded project identity; omitting that filter
  from outside a project reports imported measurements whose identity is unknown.
  `--import PATH` copies selected local runs and archives through the same allowlist;
  a copy or an overlapping archive does not change totals. A corrupt line, an
  unsupported schema version or an incomplete import marks the report `incomplete`
  rather than failing it, and an incomplete corpus does not print an exact total
  spend. Historical `role_run.vendor_attempts` without a journaled attempt stay
  measurable without double-counting a modern journal. The corpus is not safe to
  publish merely because it holds no text.

- A `planner` role. `warden do --prepare off|auto|always` (default `off`) can dispatch a
  read-only planner to turn an operator's goal into a contract Warden itself has validated,
  before the first paying implementer call. The planner drafts; Warden validates: acceptance
  must name a check already in `project.yaml`, scope must be a named scope, the operator's
  goal is carried verbatim, and `required_access` may not exceed the invocation. A planner
  that writes to the worktree is `planner_protocol_violation` and its draft is discarded.
  `--prepare always --draft-only` spends the planner and never the implementer. The call is
  counted under `by_role.planner`. `CallPlan` reserves it in the run marker before dispatch.
  The one-call bootstrap is a dispatch gate: quota failover is not a second call; a protocol
  failure may retry once. A protocol failure restores only what the planner wrote,
  including a commit; work that predates the run survives, including under `--in-place`. If
  restore cannot prove the tree is back at the captured baseline, there is no retry. The
  discarded attempt's evidence keeps its own name. An existing contract is not truncated:
  `--prepare always` keeps a hand-written visual contract, or refuses `task_conflict` when
  the intent differs. `--prepare auto` on a contract the planner already wrote does not
  rewrite it, and refuses `task_conflict` when that file does not preserve the supplied
  operator goal, scope or risk. Several compiled named checks are written as names that
  must keep existing in `project.yaml`, not as a list that would run a removed name as a
  command. A later `warden run` (and Conductor's inner process) joins the prepared
  reservation instead of refusing `run_id_exists`, including when auto skipped the planner,
  so the inner run still records `prepare=auto`. A draft that fails the shipped schema is
  `role_artifact_schema_violation` even when the profile skipped enforcement. The planner
  may request a visual contract, and an omitted `visual_qa` uses the same rule `TaskDraft`
  does. Not built: an interactive draft editor, TTL on network observations, or subtask
  execution. The shipped policy does not declare a planner.

### Changed

- `land.note` in `.warden/project.yaml` is refused as an unknown key. It used to
  validate and be dropped: `ProjectConfig.LAND_KEYS` listed `note`, but the `Land`
  record never carried it.
- The hygiene job now reads every commit a push or a pull request introduces, not only the
  resulting tree. A tip-only gate was green while a commit added three absolute paths to a
  document and the next commit reworded them away, and the paths were still in the history the
  push would have published; they were found by hand and removed with a history rewrite. The
  patterns and the file lists moved into `scripts/hygiene.sh` so the two scans cannot come to
  disagree, and the script is runnable before a push. Where no starting commit is handed over —
  a new branch or tag, a force-push whose starting commit the server no longer serves, a manual
  run — the range is read against the published branch instead, so a tag of an already-published
  commit is an empty range and a manual run on a feature branch still reads the whole branch.
  Only when there is no published branch to compare against either does the job read the tip
  commit alone, and it says so rather than claiming a range it did not read.
- `scripts/hygiene-selftest.sh` checks the gate against throwaway repositories carrying the
  things it must reject, including the pair of commits that adds a path and rewords it away.

### Fixed

- An incomplete import whose skipped row never reached the segment still marks the
  named project's `--global` report incomplete: unsupported-schema rows contribute
  the `project_id` they carried, and a row that could not be parsed applies to every
  identity rather than only the unknown-identity slice. A journaled `vendor_attempt`
  without a run instance no longer suppresses unrelated historical nested attempts
  in the same project; coverage is by run instance, operator run id or
  `vendor_attempt_id`, and a relationship that cannot be proved is flagged rather
  than merged — including when only the journal or only the nested attempt carries
  a `vendor_attempt_id`.

- The preparation reservation no longer mixes the planner's one-call bootstrap with the
  undeclared workflow chain. For a run that continues into the loop, `run.json` records
  the same ceiling and skip predicate the loop will write a moment later: `requested_cap`
  is `budgets.max_role_runs`, and `paying_stages` names the planner plus the stages the
  loop will actually dispatch. `--draft-only` still records the bootstrap (a cap of one
  and the planner alone). `--prepare off` is unchanged.
- After `--prepare always --draft-only`, the next-step line prints the run id that was
  actually reserved, so following it joins the planner's spend instead of starting a loop
  that never sees it. `--prepare off` still prints `--run-id <id>`.
- The architecture diagram in `README.md` and `README.ru.md` no longer lets "(or planner)"
  overflow the ASCII box.

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
