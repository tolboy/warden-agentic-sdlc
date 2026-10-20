# Second-opinion review: `AUDIT-AGENT-LOOP-2026-10-19.md` and the uncommitted loop changes

Date: 2026-10-19 (America/Chicago). Reviewer: Grok Bot, an independent second reviewer. Production code was not modified.

What was reviewed:

| Item | State |
|---|---|
| Audit document | `docs/AUDIT-AGENT-LOOP-2026-10-19.md` (untracked). Snapshot `7c7f408` on branch `fix/keep-pinned-contract-copy` |
| Local commit not on remote | `7c7f408 fix(run): keep a copy of the pinned contract in the run directory` |
| Uncommitted changes | 10 modified files (+586/−75) and 3 new files: `WorktreeLease.java`, `WorktreeLeaseTest.java`, `DirectCaptureTest.java` |
| Remote | `origin = git@github-tolboy:tolboy/warden-agentic-sdlc.git`. `origin/main` on GitHub is now `106f2a3` |
| Method | I rebuilt the exact local tree on a Linux box (base `7eab1df`, plus `7c7f408`, plus the working-tree files), then built and ran the tests there, checked every source the audit cites, wrote small offline probes, and applied the patch to current `origin/main` to see if it still fits |

---

## 1. Summary verdict

**Is the audit accurate?** Mostly, for the snapshot it describes. All 10 confirmed defects (F01–F10) still exist in the code, and every file:line it cites points at the code it describes. I found no false positives. A few claims go a little too far, and two priorities should change (§3).

**Is the audit current?** Only partly. The audit's base, `7eab1df`, is **5 commits behind `origin/main`** (#30, #50, #51, #52, #53, merged 2026-10-13…15). The user's local `origin/main` ref is stale; nobody fetched before the audit. Two consequences:
- The local commit `7c7f408` is superseded by #53 ("keep byte-exact pinned contract copies and add restore-contract (#26)"). The branch should be dropped or rebased, not pushed as it is.
- #30 and #50 added **failover on vendor failure** (`failover.on_vendor_failure`, 403 failover, `role_failovers`, `cause`, `vendor_failure_failovers`). The audit's picture of retry and failover ("failover only on quota"), and F04 in particular, are out of date. F01–F10 are still present on `origin/main`, but F04's fix now has to build on the `cause`/`role_failovers` model that already exists.

**Are the local changes correct?** They are aimed at the right things (F10, F01-backoff, F02) and are mostly careful, but **not ready to merge**:
1. **The test suite is red.** Full suite with the changes: **3806 passed, 5 failed** (baseline without them: 3651 / 0). All 5 failures are in the new `direct-capture` suite. Four come from a flaky fixture. One is a real parser bug: the "nested example error" case is still classified as `role_quota_exhausted`.
2. **The new worktree lease fences too easily.** Any descendant process still alive at release puts the tree into `recovery_required`, and every later run is refused until someone deletes a file by hand. That includes a detached build daemon started by a *gate*. A Ctrl+C or SIGTERM of the controller does the same. There is no command to recover. Both cases were reproduced (§4.1).
3. **The patch conflicts with `origin/main`.** `DirectCliExecutor.java` has 3 conflict hunks against #50. The other 9 files apply cleanly, but there are meaningful overlaps: the upstream `RoleRunner` failover (+484 lines) and `QuotaSignal` changes.

**Plan item status:** F10 mostly done (needs a recovery story). F01 backoff part done (the gate/baseline part, which the audit called "a design limit", not started). F02 done in design, but its tests fail. F03–F09 not started. The Web UI and eval phases (queues 4–5) not started.

---

## 2. Plan-item-by-item assessment

Legend: ✅ accurate · ⚠️ accurate but overstated, or needs a correction · ❌ false positive. Status is for the *local tree*.

| ID | Audit claim (short) | Code check | Still on `origin/main`? | Priority (audit → mine) | Local status |
|---|---|---|---|---|---|
| F10 | Different run IDs let two direct writers into one checkout | ✅ Only `run.json` is reserved (`EvidenceLedger.java:133`, `TaskLoop.java:599`). There was no tree-level lease | Yes (no lease on main) | P1 → **P1** | **Mostly done.** `WorktreeLease` is held by TaskLoop, Preparation, DoCommand and RoleRunner. But see §4.1: it over-fences and has no recovery path |
| F01 | Rate-limit backoff can run past the chain deadline | ✅ `RoleRunner.java:897-902` slept the whole pause after only a `hasRoom()` check | Yes (`RoleRunner.java:1059`, `sleeper.accept(pause)` unchanged) | P2 → P2 | **Backoff part done** (`requireRetryWait`). Gates and baseline still use `timeout_minutes` without the chain's remaining time (`GateRunner.java:139`). The audit itself calls this documented behaviour (`TaskLoop.java:773`); I agree it is a design choice and not a defect |
| F02 | Losing the stdout tail after 4 MiB destroys the artifact and usage | ✅ `ProcessRunner.java:150-155` keeps a prefix only. `DirectCliExecutor.java:148-169` parses that prefix | Yes (`STDOUT_CAPTURE_BYTES = 4 MiB`) | P2 → **P1** (paid work silently becomes `role_artifact_unparseable` with unknown cost; long Codex JSONL sessions can realistically pass 4 MiB) | **Done in design** (full spool to file, plus a streaming parser). **Tests red.** See §4.2 |
| F03 | Local ledger hides an attempt if the run crashes before `role_run` | ✅ `LedgerReader.java:41-42`. `vendor_attempt` is corpus-only | Yes | P2 → P2 | Not started |
| F04 | A→A retry counted as failover | ✅ `LedgerReader.java:333-336`, `RunReport.java:417+` count `attempts-1` transitions | Partly: main adds `cause` (`failoverCause`), `role_failovers`, `vendor_failure_failovers`, but A→A is still counted as a switch | P2 → P2, **rewrite against main** | Not started |
| F05 | Partial token telemetry becomes zeros | ✅ `RunReport.java:326-350`: any one field present marks the whole object "known" | Yes | P2 → P3 (reporting only) | Not started |
| F06 | `by_model` uses the requested model when the vendor reported none | ✅ `RoleRunner.java:1147-1150` falls back to `profile.model()`. This breaks the promise in `METRICS.md:49` | Yes (`RoleRunner.java:1556`) | P2 → P2 (it blocks honest evals) | Not started |
| F07 | Independence preflight looks only at the first writer | ✅ `TaskLoop.java:1346-1352` uses `break` on the first implementer | Yes (`TaskLoop.java:1405`) | P2 → P2 | Not started |
| F08 | Roster write is not atomic | ✅ `RosterCommand.java:63-69` writes the live file before parsing it, with no lock or digest | Yes | P2 → **P3 now**. It becomes P2 only when the web "Apply" ships. The CLI is single-user and keeps a backup | Not started |
| F09 | Dashboard columns misaligned | ✅ `Dashboard.java:438` has 6 `<th>`. The row builder at `:477` emits 5 cells | Yes (Dashboard unchanged) | P2 → P3 (cosmetic, but misleading) | Not started |
| Context size | Context file read whole, no byte/token budget | ✅ `RoleRunner.java:1217` | Yes | Unranked → P3. Measure first, as the audit says | Not started |
| Quota groups | `quota_group/account_scope` | ⚠️ Sensible, but speculative. No measured incident is cited | — | Unranked → P3 | Not started |
| Web UI (queue 4) | "Next run" form in the existing Dashboard | ⚠️ The scope is reasonable, but it is ordered before the telemetry and eval work it depends on for meaningful previews of cost and choice. F08 must come first | — | queue 4 → after eval records | Not started |
| Evals (queue 5) | Condition keys, pairing, `comparable` flag | ✅ `LedgerCompare.java:538` (key misses model/effort), `:453` (`units >= 3`), `:45` (no `task_pair_id`) | Yes | queue 5 → **raise to queue 2–3** (cheap, and it gates every "which model is better" claim) | Not started |
| Test-run note | Full suite stopped after 18 min with no summary | ⚠️ On Linux the full suite finishes in **~1.5–2 min** (3651 checks, baseline). The 18 minutes is Windows-specific (process/git-heavy fixtures). The audit should have run the suite per-suite, or in CI, rather than leaving "not green, not red" | — | — | — |
| "Three old defects re-checked and closed" | — | Not independently re-verified (no fixtures given) | — | — | — |

The audit's own risk notes (ProcessRunner descendants cleanup, Orca historical question) are fair calls to leave unconfirmed. The local change partly addresses the descendant case. `WorktreeLease`'s handling of descendants creates new risks (§4.1).

---

## 3. Corrections to the audit

1. **Rebase the audit on current main.** F04 and the "Повтор и замена" (retry and replacement) row describe failover as quota-only, but #30 and #50 added opt-in failover on `role_command_failed`, timeouts, missing or invalid artifacts, and vendor 403. This changes the risk picture. A "vendor failure" failover is exactly the "repeat without fencing may start two editors" case the audit warns about in its last section. It now exists on main, and it should be fenced by the new lease's process census (see N3).
2. **F02 is more important than F01.** F01 needs a long `Retry-After` *and* a short time budget. F02 hits any verbose writer session and silently throws away paid output.
3. **F08 and F09 are not P2 safety issues.** Move them down; F08 stays a hard prerequisite for any web Apply.
4. **The order of work should put measurement (F03–F06 and eval condition keys) before the UI.** Otherwise the form shows previews and comparisons built on numbers the audit itself calls unreliable.

---

## 4. Review of the local (uncommitted) changes

### Change-to-plan mapping

| Change | Files | Plan item | Verdict |
|---|---|---|---|
| Cross-process worktree lease (OS `FileLock` in the git admin dir, plus a durable `warden-execution.json` marker); reentrant per thread; checks for a retained Orca worker | `execution/WorktreeLease.java` (new), `TaskLoop.java:593-595`, `Preparation.java:193-195`, `DoCommand.java:288-292,604`, `RoleRunner.java:581-583,838`, `Main.java:91,468` | F10 | Concept correct; **over-fences, no recovery path** (§4.1) |
| Child and descendant tracking (100 ms polling), tree-wide terminate | `ProcessRunner.java:70,149-185` | F10 (fencing) / audit risk note | Helpful for timeouts. Tracking for the lease is both too strict and too loose (§4.1) |
| Full raw spool to files; bounded head+tail preview; streaming JSON/JSONL framer; quota detection on retained state | `ProcessRunner.java` (`LimitedBuffer`), `DirectCliExecutor.java:137-160,519-690` | F02 | Sound approach; **one real parser bug, one flaky test, semantic changes** (§4.2) |
| `requireRetryWait`: refuse a backoff that leaves less than `MINIMUM_CALL_SECONDS` | `RoleRunner.java:104,917-940`, `TaskLoop.java:680,4243-4252,4424-4431`, `Preparation.java` | F01 (backoff) | **Correct.** Well tested at the boundary, including fractional seconds |
| Tests | `WorktreeLeaseTest` (550 lines, multi-JVM), `DirectCaptureTest` (244 lines, real child pipes), `RoleRunnerTest` +75, `TaskLoopTest` +38, `TestMain` +2 | — | Good breadth. One flaky test and one failing test; no Windows-specific cases |

### 4.1 Worktree lease (F10)

What is good:
- Multi-JVM exclusion with an OS lock, refusal before reservation, and the "dry-run touches nothing" property are all tested in `WorktreeLeaseTest`.
- Linked worktrees use their own `.git/worktrees/<name>` admin dir (`WorktreeLease.java:215-226`), so separate worktrees stay parallel, as the audit asked.
- The design is sound: a durable "active" marker is written before any spawn, and a dead controller PID is never taken as proof of safety.

Problems:

1. **High: false fences from descendants of gates, git or helpers.** `ProcessRunner.runCaptured` calls `WorktreeLease.track` on *every* process it starts, including gates and every git command (`GateRunner.java:144`, `GitRepository.java:512`), and on every descendant it observes. At close, any tracked process still alive turns the state into `recovery_required` (`WorktreeLease.java:76`). Every later `warden run/role/do` in that tree is then refused until someone deletes `.git/warden-execution.json` by hand.
   - **Reproduced** (probe `probe/LeaseProbe.java`): a "gate" `sh -c 'setsid sleep 15 >/dev/null 2>&1 </dev/null & sleep 1'` exits 0. The lease is then released as `recovery_required`, and the next acquire fails with `worktree_recovery_required`.
   - Realistic triggers for a Java/Kotlin user: the Gradle daemon and Kotlin compile daemon started by a `./gradlew test` gate, a dev server or watcher started by a UI gate, a `git gc --auto` that detaches, and git's fsmonitor daemon on Windows.
   - The same polling is also **too loose**: a grandchild that double-forks within 100 ms is never seen. My first probe variant (parent exits at once) was released as "safe" even though the detached process was alive.
   - Fix: fence only on processes in a *vendor CLI's* tree, which are possible hidden writers. Kill a vendor CLI's surviving descendants when the call ends and record that. Do not fence on gate or git processes, or let the user list allowed daemons. For real containment use OS process groups or job objects (a Job Object on Windows, a process group or cgroup on Linux) instead of 100 ms polling.
2. **High: no recovery after Ctrl+C or a crash.** A controller stopped by SIGTERM (exit 143; on Windows, Ctrl+C) leaves `"state": "active"`, because try-with-resources does not run during JVM shutdown. **Reproduced**: the next acquire returns `worktree_recovery_required`. The only remedy is the error message's instruction to delete a file inside `.git`. Child PIDs are kept **only in memory** (`Owner.processes`), so after a crash no tool could check whether the old children are gone, even if one existed.
   - Fix: persist each vendor-CLI child's `{pid, start_instant}` when it spawns. Add a shutdown hook that terminates tracked vendor trees and writes the final state. Add `warden recover --worktree` (or a `doctor` action) that releases automatically when the controller and every recorded child (PID plus start instant) are gone, and otherwise prints what is still alive.
3. **Medium (needs verification): the Orca resume path may be blocked.** `TaskLoop.run`, `Preparation.run` and `DoCommand` call `requireLocalDispatch` **regardless of the runner** of the stages ahead (`TaskLoop.java:595`). `RoleRunner` applies it only to non-Orca profiles (`RoleRunner.java:837-839`). Any run directory with an active `orca.json` therefore refuses every workflow at admission, including one whose next stage is the Orca profile that `OrcaExecutor` would use to attach to the retained worker (`OrcaExecutor.java:141-173`). The refusal message tells the operator to "resume that exact role/run through its Orca profile", which the workflow then refuses. All Orca suites pass, but none drives a workflow that continues while a retained worker is recorded active. Add that test, or limit the admission check to runs whose next dispatch is local.
4. **Low: dead API.** `WorktreeLease.requireRecovery(reason)` (`:154`) is used only by tests; no production path marks "missing process receipt".
5. **Low: the `NextStep` stop codes have no entries.** A mid-run `Refused` becomes `stop(..., "worktree_busy" | "worktree_recovery_required")` (`TaskLoop.java:1105-1108`), but `NextStep` has no entry for these codes, so the operator gets a generic next step.
6. **Low: subdirectory roots.** When `.warden/project.yaml` sits in a subdirectory of a repo, the fallback lock `.warden/runs` (`WorktreeLease.java:230`) is per-subdirectory. Two controllers on two subprojects of one checkout would not exclude each other, even though they share the git index. Resolve the lock via `git rev-parse --git-dir`.
7. **Low: the conductor handoff** (`DoCommand.java:604`) releases the lease before the inner Warden acquires it. That leaves a short race window, and DoCommand keeps working on the tree after the conductor returns without holding the lease. Acceptable, but document it.

### 4.2 Full capture and streaming protocol parse (F02)

What is good:
- The raw stream is spooled to disk byte for byte (invalid UTF-8 survives).
- The preview is bounded head+tail.
- A spool I/O failure fails the call instead of presenting a partial transcript.
- Brace framing that understands strings, with rejection of prose `{`, ran against 4.8 MB+ fixtures in the tests.
- `stdout_truncated`, `raw_capture_complete` and `protocol_objects_omitted` are now explicit evidence. This fixes the audit's "the truncation flag never reaches the report" point.

Problems:

1. **High: the test suite fails on a real bug.** `nested-prose-failed` expects `role_command_failed` and gets `role_quota_exhausted` on both JDK 21 and 25, every run. Root cause: `ProtocolState.accept` (`DirectCliExecutor.java:563`) calls `QuotaSignal.detect(signatures, text, null)` with the **whole pretty-printed envelope text**. `QuotaSignal.structuredErrorMessages` splits it into lines again and parses the nested `{"type":"error","message":"usage limit"}` example as a standalone vendor event. The "line candidates inside a complete envelope are discarded" logic works for `tentative`, but this call undoes it.
   - I verified a one-line fix on a scratch copy: pass the text with newlines collapsed, `text.replace('\n',' ').replace('\r',' ')`. That is safe because JSON strings cannot contain raw newlines. The whole `direct-capture`, `role runner` and `worktree lease` set then passes (259/0).
   - A cleaner fix: give `QuotaSignal` an overload that takes an already parsed object and runs `collectErrorMessages` on it.
2. **Medium: a flaky test.** `DirectCaptureTest.ARTIFACT` and two fixture lines use `Json.write(Map.of(...))`. `Map.of` iteration order is randomised per JVM, and the expected hash is computed in the test JVM while the bytes come from a child JVM. Result: 3–5 of the 5 "stdout byte integrity" checks fail at random (different ones each run). Replacing those three `Map.of` fixtures with literal JSON makes all byte-integrity checks pass on every run (verified).
3. **Medium: telemetry is now merged across every event.** It used to come from the last envelope only. `collectTelemetry(parsed, telemetry)` runs on every complete object, and `putAll` keeps the last value per key (`DirectCliExecutor.java:562`, `merge`). A top-level JSON object printed by a tool, or an intermediate event that carries `cost_usd`, `usage`, `session_id` or `modelUsage`, can now supply the call's cost, tokens or model when the final envelope lacks them (for example after a timeout). That is the opposite of the project's rule that an unknown stays unknown. Restrict telemetry to vendor-typed envelopes (`result`, `turn.completed`, …) or record `telemetry_source`, and add a test where an intermediate object carries a different `cost_usd`.
4. **Medium: no disk cap on the raw spool.** The 4 MiB in-memory cap is gone, and nothing replaces it on disk. A runaway CLI can now write without limit into `.warden/runs/<id>/raw/`. Add a large cap (for example 1–2 GiB), mark the capture `raw_capture_complete=false` / truncated past the cap, and keep the protocol tail.
5. **Medium, Windows-specific: an unsettled capture is reported as a start failure.** If a descendant still holds the stdout pipe after a normal exit, the new code throws `IOException("raw output capture did not settle…")` (`ProcessRunner.java:122`). `DirectCliExecutor` catches every `IOException` as `cannotStart` (`:152`) and reports `role_command_failed`, `exit_code=-1`, `Duration.ZERO`, and no cost. That is wrong: the call ran and may have cost money and produced an artifact.
   - On Linux I could **not** reproduce it: the JDK's Unix `ProcessPipeInputStream` drains and closes when the child exits, so the readers finish anyway. On Windows that mechanism does not exist.
   - It also interacts with upstream #30: `role_command_failed` is now a failover trigger, so a second writer could start while the first one's descendant is still alive. Use a distinct code (`role_capture_unsettled`), keep the telemetry already parsed from the file, never fail over on it, and fence the lease.
6. **Low: polling cost.** Descendant polling every 100 ms for the whole call (`ProcessRunner.java:159-168`) means a process snapshot every 100 ms on Windows, for 30+ minute calls. It is probably acceptable, but measure it, or poll less often and do one final sweep.
7. **Low: a preview change.** `PREVIEW_BYTES` = 256 KiB (128 KiB head + 128 KiB tail). Callers that used `process.stdout()` as the full output for diagnostics (stdout tail, turn-ceiling text) now see a head+tail splice. That looks intentional and fine; it is worth a line in `METRICS.md`/`CHANGELOG.md`.

### 4.3 Rate-limit wait vs. deadline (F01)

- Correct and minimal. The check runs after the attempt is journaled and settled (`RoleRunner.java:909-911`) and reserves nothing. On refusal, the paid attempt is written as a complete `role_run` with `rate_limit_retry_declined` before the exception goes up. Tests cover the 61 s case, the exact boundary, and fractional seconds.
- Minor: the stop is classified `budget_exhausted / max_elapsed_minutes`. For the operator, the actionable cause is "vendor asked for N s; the chain had M s". Make sure `NextStep` says that (resume after `Retry-After`), not only "raise the budget".
- Gate and baseline time against the chain deadline is unchanged (documented in `budget_plan.time_reserve`). Leave it until there is a decision on hard wall-clock semantics.

### 4.4 Consistency and hygiene

- **No `CHANGELOG.md` entry** for any of the three changes, although the project lists every fix with its suites (see the entry from `7c7f408`).
- No doc updates for the new evidence fields (`raw_capture_complete`, `stdout_truncated`, `protocol_objects_omitted`, `rate_limit_retry_declined`) or for the `.git/warden-execution.json` lease file.
- The style matches the codebase: fully qualified names inline, long explanatory comments, an explicit `TestMain` registry.
- The local commit `7c7f408` duplicates upstream #53. Drop it when rebasing.
- `git apply --3way` of the working-tree diff onto `origin/main`: 9 files apply cleanly; **`DirectCliExecutor.java` has 3 conflict hunks** (lines ~157, ~311, ~374 of the merged file) against #50's vendor-availability/403 changes. `QuotaSignal` (+73 lines upstream) is used by the new parser and needs a semantic re-check after the merge.

---

## 5. New findings not in the audit

| # | Severity | Finding | Evidence |
|---|---|---|---|
| N1 | High | The audit and the local work are based on a stale `origin/main` (5 commits behind). Local `7c7f408` is superseded by #53. The F04 and failover analysis predates #30/#50 | `git log 7eab1df..origin/main` |
| N2 | High | The lease over-fences on gate and git daemons, and has no recovery path after Ctrl+C or a crash (only a manual file delete) | §4.1-1/2, reproduced |
| N3 | High | Upstream "failover on vendor failure" (#30/#50) is not fenced against a previous writer's surviving descendants. The audit's warning about two editors now applies to code on main. Once rebased, require an empty vendor-process census (or a terminated tree) before any vendor-failure failover | `a268588`, §4.2-5 |
| N4 | High | The new `direct-capture` suite fails (1 real bug + 1 flaky fixture). The full suite with the changes is red | §6 |
| N5 | Medium | Telemetry is merged across all JSON events (could attach cost, tokens or model from a non-vendor event) | §4.2-3 |
| N6 | Medium | No disk cap on raw output now that the memory cap is gone | §4.2-4 |
| N7 | Medium | Possible blocking of the Orca resume path by the runner-agnostic admission check (unverified, no test) | §4.1-3 |
| N8 | Low | Windows test time (18+ min in the audit vs ~2 min on Linux) suggests heavy git/process fixtures. CI already runs `windows-latest` with JDK 21/25; opening a draft PR is the cheapest way to get a full Windows result | `.github/workflows/ci.yml` |
| N9 | Low | `NextStep` has no guidance for `worktree_busy` / `worktree_recovery_required`; `requireRecovery` is unused | §4.1 |

---

## 6. Build and test results (Linux box; JDK 21.0.12 and 25.0.4, `--release 21`)

| Tree | Build | Tests |
|---|---|---|
| Local HEAD `7c7f408` without working-tree changes (baseline) | ✅ | **Full suite: 3651 passed, 0 failed** (~81 s) |
| Local HEAD + uncommitted changes (JDK 25) | ✅ (unchecked-ops note only) | **Full suite: 3806 passed, 5 failed** (~112 s), all in `direct-capture` |
| Same, focused (`worktree lease`, `direct-capture`, `role runner`, `runtime`, `do-command`) | ✅ | 389 passed, 5 failed (exit 1) |
| Same, `task loop` alone | ✅ | 842 passed, 0 failed (~58 s) |
| Same on JDK 21 (`worktree lease`, `direct-capture`, `role runner`) | ✅ | 254 passed, 5 failed |
| Scratch copy, deterministic fixture (no `Map.of`) | ✅ | `direct-capture` 74 passed, 1 failed, the same on every run; only `nested-prose-failed` remains |
| Scratch copy, plus the one-line quota fix (§4.2-1) | ✅ | `direct-capture` + `role runner` + `worktree lease`: **259 passed, 0 failed** |
| Working-tree diff applied to `origin/main` `106f2a3` | ❌ conflicts | 3 hunks in `DirectCliExecutor.java`. Not built |

Failing checks (with the changes):
- `[direct-capture] {jsonl|chatter-jsonl|envelope|chatter-envelope|failed} stdout byte integrity`: flaky; which ones fail varies per run.
- `[direct-capture] nested-prose-failed preserves authoritative classification`: deterministic; expected `role_command_failed`, got `role_quota_exhausted`.

Offline probes (box only, no vendors), kept under `/workspace/warden-review/probe/`:
- `LeaseProbe daemon` → `state=recovery_required`, second run `REFUSED: worktree_recovery_required`.
- `LeaseProbe hold` + SIGTERM → `state=active` left behind, next run `REFUSED`.
- `PipeProbe` (exit 0 with a background child holding stdout) → returns normally on Linux for both old and new code. The Windows behaviour is untested.

---

## 7. Recommended plan changes (prioritized)

1. **P0: Rebase onto `origin/main` before anything else.** Drop `7c7f408` (superseded by #53). Resolve the 3 `DirectCliExecutor` hunks against #50, and recheck `QuotaSignal` use. *Why:* every later fix lands on code that has changed a lot (RoleRunner +484, TaskLoop +277, DirectCliExecutor +347). Continuing on the stale base only grows the conflicts.
2. **P0: Make the suite green.** Replace the `Map.of` fixtures in `DirectCaptureTest` with literal JSON, and stop `ProtocolState.accept` from re-splitting a complete envelope into lines (collapse newlines, or add a parsed-object `QuotaSignal` overload). *Why:* a red, flaky suite hides real regressions, and the fix is about 10 lines (verified).
3. **P1: Lease fencing scope and recovery (new F10b).** (a) Fence only on vendor-CLI process trees; kill surviving vendor descendants when the call ends. (b) Do not fence on gate or git processes. (c) Persist the vendor child `{pid,start}` list. (d) Add a shutdown hook. (e) Add `warden recover --worktree` that releases automatically when every recorded process is provably gone. (f) Add a `NextStep` entry for both codes. Tests: a Gradle-like daemon gate, SIGTERM of the controller, a double-fork vendor helper. *Why:* without this, F10's fix turns a rare concurrency bug into a frequent "tree locked, delete a file in `.git`" incident.
4. **P1: Fence vendor-failure failover (new, from #30/#50).** No failover and no `--continue` while the previous writer's tree is not proven stopped. Give "capture unsettled" its own code that never fails over. *Why:* this is the audit's own two-editors warning, now real on main.
5. **P1: Finish F02 hygiene.** Add a disk cap with a truncation flag. Limit telemetry to vendor envelopes or record `telemetry_source`. Add a Windows test for an inherited pipe after a normal exit. Add the CHANGELOG/METRICS entries. *Why:* it keeps the "unknown stays unknown" rule and bounds the new failure modes.
6. **P1: Verify or fix the Orca admission check.** Add a TaskLoop test that continues a run with a retained active Orca worker, and limit `requireLocalDispatch` at admission to local next dispatches. *Why:* the change may silently disable the attach and recovery path that `OrcaLifecycle` exists for.
7. **P2: F04 rewritten on top of main's `cause` / `role_failovers`.** Add `transition_reason`, with retry ≠ failover, using the cause vocabulary that already exists. *Why:* main has half of this already, so do not invent a parallel scheme.
8. **P2: F06 and eval condition keys (moved up from queue 5).** Add requested/effective/reported dimensions; include model/effort/limits/prompt-schema hashes in `LedgerCompare.conditionKey`; replace `comparable = units >= 3` with an explicit "conditions identical" check. *Why:* this is cheap, and until it is done any "model X is better" conclusion from `ledger --compare` is invalid.
9. **P2: F03, F05, F07** as in the audit.
10. **P3: F08, then the Web UI form, then F09.** F08 is a prerequisite for Apply only. The UI should follow the measurement fixes so that its previews show trustworthy numbers.
11. **Process:** open the rebased work as a draft PR to get the `windows-latest` × JDK 21/25 CI result, since the user's platform is Windows and the audit could not finish the full suite there.

---

## 8. What I could not check

- Windows behaviour: process-tree polling cost, an inherited pipe after a normal exit, `FileLock` semantics, Ctrl+C handling. Everything here ran on Linux; CI on `windows-latest` is the recommended check.
- Live vendors, Orca runtime, quota and failover with real accounts. None were run, same as in the audit.
- The audit's own probes and artifacts under `out/audit-*` (gitignored, not copied), and its dashboard screenshot. F09 was checked by reading the code only.
- The rebased tree: I did not build or test it, because of the conflicts.
- Whether a real CLI path leaves an active `orca.json` that a later workflow is meant to resume (N7) is inferred from the code, not reproduced.
