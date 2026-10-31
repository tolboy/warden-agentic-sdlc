# Stage 3 acceptance loops — 2026-10-28

Branch `grok/acceptance-stage3` (from `grok/api-resilience-stage2`, 1c5d392). Test project
`/workspace/hello-world-test` (base ec5566f), branches `stage3-loopA` (task `health-endpoint`)
and `stage3-loopB` / `stage3-loopB2` (task `version-endpoint`). Warden was run only through the box launcher. No run
was accepted and `warden accept` was never used. All times are CDT (UTC-5).

## Summary

| Criterion | Loop A (CLI implementer + API reviewer) | Loop B (API implementer via OpenCode + CLI reviewer) |
| --- | --- | --- |
| Jev routing | pass (`{implement=medium}` in every run) | pass (`{review=medium}`) |
| Model checks | pass (after the pong fix, 4559ac5) | pass |
| Implement | pass (grok-implement, grok-4.7 medium) | pass in B-5 (`openrouter-coder`, OpenRouter DeepSeek-V4-Flash, 24 relay requests, 1m52s); B-1 to B-4 failed |
| Gates | pass (`node --test`, `node .warden/checks/health.mjs`) | pass in B-5 (`node --test`, `node .warden/checks/version.mjs`, 1.4 s) |
| Mandatory review | pass (GonkaRouter DeepSeek-V4-Flash, verdict pass) and review-second (agy, verdict pass) | pass in B-5: codex-review (gpt-6.1-sol medium) pass in 28 s, agy-review pass in 5m00s. Both raised one non-blocking P2: an unrequested `/health` handler |
| Stop/resume | pass: SIGINT mid-review, continuation reused reproduction, implement and review | **not met**: B-5 got SIGINT 3 s before review ended, but the JVM ignored it, because the launch shell ignored SIGINT (see below). The run budget was then spent |
| End state | `s3-loopA-8` `ready_for_human`, decision pending (accept/reject left to a person) | **`s3-loopB-5` `ready_for_human`**, decision pending, not accepted |

Run budget: the brief allowed about 6 loop runs. 8 dispatching runs were used (B-1, B-2, A-1, A-2,
A-3, A-6, A-8, B-3), plus 3 preflight stops that made no role call (A-4, A-5, A-7). The budget is
spent, so Loop B was unfinished after round 1. Round 2 (2026-10-29) used 2 more runs (B-4, B-5) with Tol's approval.

## Loop A — `health-endpoint`

Profiles:
- Implementer: `grok-implement` (grok CLI, grok-4.7, effort medium chosen by Jev; profile default xhigh).
- Reviewer: first `gonkarouter-glm-review` (GonkaRouter GLM). From A-4 on, `gonkarouter-review` (GonkaRouter `deepseek-ai/DeepSeek-V4-Flash-0731`, API, text artifact only, verified 2026-10-28).
- Review-second: `agy-review` (Antigravity CLI, gemini-3.8-flash-high).
- Checks also pinged `codex-review`.

Timeline:

| Run | Continues | What happened | Result |
| --- | --- | --- | --- |
| A-1 | — | Implement ok ($0.0325), then interrupted during review. No resumable record was written (bug 1). | lost |
| A-2 | — | Implement ok in 1m00s (46610/4972 tokens, $0.0604). Gates ok (817 ms). SIGINT at 18:48:38 during review. Recorded as `controller_interrupted` with a pending decision. | exit 130, resumable |
| A-3 | A-2 | Reproduction reused ("red reused verified base proof from s3-loopA-2"). Implement re-ran anyway (bug 2, $0.0538). Gates ok. GLM review got HTTP 502 after 111 s. | `vendor_call_failed`; retry approved |
| A-4 | A-3 | Reviewer switched to `gonkarouter-review`. Preflight stop with `independent_review_unavailable` because the profile was unverified. Verified it, then aborted A-4. | 0 calls |
| A-5 | A-3 | DeepSeek ping answered HTTP 200, but the reply was not JSON (`check_no_pong/invalid_json`). Aborted. | 0 calls |
| A-6 | A-3 | Reproduction reused from A-2. **Implement reused from A-3** (fix 83e4f87 confirmed live). Gates ok (861 ms). DeepSeek review ok in 1m08s (4268/455 tokens), verdict pass. Then the 5-call task cap was reached, because prior calls in the chain count. | `budget_exhausted`; `max_role_runs` raised to 7 in the test task, retry |
| A-7 | A-6 | DeepSeek pong was not JSON again, so the run stopped. This prompted fix 4559ac5. Aborted. | 0 calls |
| A-8 | A-6 | Warden noted "contract changed … only its budget", so the earlier verdicts still stood. Reproduction reused (A-2), implement and review reused (A-6, `reused_judgements: {from: s3-loopA-6, stages: [implement, review]}`). Gates re-ran ok (832 ms). agy review-second ok in 1m55s (82700/7970 tokens), verdict pass. Browser and look skipped (`visual_qa_required` not met). | **`ready_for_human`**, exit 0 |

Stop/resume evidence:
- A-2 was interrupted in `[3/6] review` and left `controller_interrupted` with steps and a pending decision.
- A-3 continued it without re-running reproduction, but with the implement bug.
- A-6 and A-8 then showed that finished stages are reused: implement and review were not dispatched again, and only machine gates (cheap, always re-checked) and the unfinished review-second ran.

Changed files in the candidate (not landed): `serve.mjs`, `test/health.test.mjs`. A patch is saved at `/tmp/s3-loopA-candidate.patch`, and the run directories are in `.warden/runs/`.

What acceptance would show: `warden approve s3-loopA-8 --decision accept` would record a human acceptance of a candidate that passed gates and two independent reviews (deepseek and google; the implementer is grok). Nothing would be landed. Landing is a separate operator step. This was deliberately not done.

Tokens and cost for Loop A:
- grok: $0.0325 (A-1), $0.0604 (A-2) and $0.0538 (A-3), $0.1467 in total.
- Jev: about $1.65e-5 per classification.
- GonkaRouter DeepSeek: 4268/455 tokens, cost unknown (not reported).
- agy: 82700/7970 tokens, cost unknown.
- GLM review (A-3): cost unknown.
- Ping and verification calls: tokens recorded, cost unknown.

## Loop B — `version-endpoint`

Profiles:
- Implementer: `joingonka-coder` (OpenCode 1.19.4 through the Warden relay, JoinGonka `zai-org/GLM-5.3-Flash`, wall clock 12 min) for B-1 and B-2. From B-3 on, `gonkarouter-coder` (same harness, GonkaRouter `deepseek-ai/DeepSeek-V4-Flash-0731`, new profile, coding capability verified 2026-10-28).
- Reviewer: `codex-review` (codex CLI, gpt-6.1-sol, medium).
- Review-second: `agy-review`.

Timeline:

| Run | What happened | Result |
| --- | --- | --- |
| B-1 | Reproduction red as expected ("every reproduction command failed on the unchanged tree"). JoinGonka coding turns ran past the relay's 45 s per-turn bound and 4 min window. | `implementer_failed`; aborted. Led to fixes 1c50622 and 7b019fd. |
| B-2 | Same cast. Upstream connection closed at relay turn 7. This was reported as a harness process failure. | `vendor_call_failed`; aborted. Led to fix cebdce2. |
| (verify) | `warden profiles --verify gonkarouter-coder`: the 1st attempt passed the probe but did not stamp (the profile had no `verification:` block); the 2nd hit GonkaRouter 502 after 110 s on relay turn 5; the 3rd verified and stamped. | profile verified |
| B-3 | Fresh run. The implementer used all 8 relay requests exploring the tree (it also read earlier runs' raw output under `.warden/runs/`) without editing anything (empty candidate). The stop was reported as `role_harness_process_failed`. 63529/2101 tokens, cost unknown. The interrupt helper never fired because the run ended before review. | `implementer_failed`; retry recorded. Led to fix 419bd53 and a local `.git/info/exclude` entry for `.warden/runs/`. |

At the end of round 1, a passing implement, gates, codex review, the interrupt mid-review and a continuation showing reuse were still missing. Round 2 follows.

## Loop B, round 2 — 2026-10-29

Tol decided to raise the relay limits and to hide run records from the implementer, then allowed up to 2 more Loop B runs.

Changes before the runs:
- **e7f958e**: per-profile relay limits and hidden run records.
  - New `limits.max_requests` (OpenCode profiles only, ceiling 32). `max_output_tokens` is capped at 8192, and at 4096 on a GonkaRouter connection because GonkaRouter refuses `max_tokens` above 4096.
  - The request limit is part of the profile fingerprint, so changing it needs re-verification.
  - New `RunArtifactShield`:
    - OpenCode permission rules deny `read`/`edit` of `.warden/runs/` (relative and absolute) and shell commands that name `.warden/runs` or the Warden home.
    - On Linux, when bubblewrap works, the harness also runs in a mount namespace with a tmpfs over `<project>/.warden/runs` and the Warden home. Only this dispatch's own exchange directory (`opencode-io-*`, holding the dispatch file, OpenCode state and the artifact) is bound back.
    - The evidence records `run_artifacts_hidden` (`mount_namespace` or `permission_rules`) and `hidden_paths`.
  - New suite `opencode-limits`. `verified-opencode` checks that the fixture cannot see the run tree.
- Profiles (backup in `~/.warden/backups/`): `gonkarouter-coder` and `joingonka-coder` now have `max_requests: 30`, `max_output_tokens: 8192`, `wall_clock_minutes: 12`.
- Re-verification:
  - `gonkarouter-coder` verified 2026-10-29: 6 relay requests, 30645/800 tokens, `mount_namespace`, `effective_config_checked: true`, effective output cap 4096. `warden check` gave pong.
  - `joingonka-coder` verified 2026-10-29: 8 relay requests, 41721/1080 tokens, about $0.0003.

| Run | Provider | What happened | Result |
| --- | --- | --- | --- |
| B-4 | GonkaRouter `deepseek-ai/DeepSeek-V4-Flash-0731` via `gonkarouter-coder` | Fresh branch `stage3-loopB2` from ec5566f. Reproduction red as expected. The implementer worked 20 OpenCode steps in about 7 min and wrote `serve.mjs` (+13) and `test/version.test.mjs`. Two problems: (1) **shield bug**: OpenCode matches paths inside the project in project-relative form, so the absolute allow rule for the exchange directory never matched, and the `.warden/runs/*` deny blocked the implementer's own artifact write 6 times; (2) relay step 21 hung for about 2.5 min and GonkaRouter answered **HTTP 502 Bad Gateway**. I also sent SIGINT at about 02:57; the run ended at 9m16s as `role_api_timeout`. | `role_timed_out`, 1 vendor call, tokens not reported; left pending (not continued). Led to fix fd74184. |
| B-5 | OpenRouter `deepseek/deepseek-v4-flash-0731` via `openrouter-coder` (switched after the B-4 502) | Fresh run on the same branch at 03:04 CDT. Jev `{review=medium}`, all 4 pongs. Implement ok in 1m52s: 24 relay requests, all HTTP 200, 288367/8295 tokens, **$0.0098** (OpenRouter-reported). Shield: `mount_namespace`, `hidden_paths [<project>/.warden/runs, …]`. The implementer's only touches of `.warden/runs` were its own artifact (read/edit allowed) and 2 `ls`/`cat` shell commands on its exchange (denied by the bash rule). It never read an earlier run. Gates ok (1.4 s). codex review pass in 28 s (55536/933). agy review-second pass in 5m00s (94621/18697). Browser and look skipped. SIGINT was sent at 03:07:55, during review, but had no effect (see below). | **`ready_for_human`**, exit 0, 3 vendor calls. Decision pending, not accepted. Candidate: `serve.mjs` (+12: `GET /version` → `{name, version}` from package.json, plus an unrequested `GET /health`) and `test/version.test.mjs`. Patch at `/tmp/s3-loopB-candidate.patch`. |

- **fd74184**: the exchange directory is also allowed by its project-relative path, after the denies. The test evaluates the rules last-match-wins, as OpenCode does, for relative and absolute forms (earlier runs stay denied, own artifact allowed). `opencode-limits` now has 35 checks.
- **Provider switch.** GonkaRouter was unstable again (a 502 after a hang, the same ~110 s+ gateway failure as before), so per the steering, the remaining run uses OpenRouter. The new profile `openrouter-coder` uses the same harness and limits (`max_requests: 30`, `max_output_tokens: 8192`, 12 min), connection `openrouter-personal` (`env:OPENROUTER_API_KEY`), and model `deepseek/deepseek-v4-flash-0731`, the same model family as on GonkaRouter. It was verified 2026-10-29: 6 relay requests, 30739/792 tokens, $0.0011, `mount_namespace`, `effective_config_checked: true`. `warden check` gave pong ($0.000014).

**Why stop/resume was not shown in Loop B.**
- `/tmp/s3-interrupt.sh` sent SIGINT to the Warden JVM 3 s before the codex review finished, and the run carried on to `ready_for_human`. B-4's SIGINT was ignored the same way: that run kept going until the GonkaRouter 502.
- Cause: runs were started from a non-interactive shell with `nohup setsid … &`. Such background children inherit SIGINT and SIGQUIT as ignored (`SigIgn: 0000000000000006` in `/proc/<pid>/status`), and the JVM does not install a handler for a signal that was ignored at startup. So `InterruptionGuard` never saw it.
- This is a harness/launch problem, not a Warden bug: Loop A's stop/resume (A-2) shows the guard works when the signal arrives.
- The 2-run budget was spent (B-4, B-5), so no third run was made.
- To show it, start the run with SIGINT restored, for example `python3 -c 'import os,signal,sys; signal.signal(signal.SIGINT, signal.SIG_DFL); os.execvp(sys.argv[1], sys.argv[1:])' /tmp/s3-run.sh …`, or interrupt with SIGTERM, which the guard also handles. Then run `warden approve <id> --decision retry` and `warden run version-endpoint --continue <id> --use implement=openrouter-coder --use review=codex-review`.

Loop B round 2 tokens and cost:
- B-5 implementer: $0.0098.
- openrouter-coder verify: $0.0011. Check: $0.000014.
- joingonka-coder verify: about $0.0003.
- gonkarouter-coder verify and B-4: tokens recorded, no price reported.
- codex (55536/933) and agy (94621/18697): no price reported.

## Bugs found and fixed (all on `grok/acceptance-stage3`, each with tests)

| Commit | Fix | Found by |
| --- | --- | --- |
| 1c50622 | The relay allows up to 120 s per upstream coding turn (was 45 s). | B-1 |
| 7b019fd | The OpenCode relay window follows the profile wall clock, up to 12 min. | B-1 |
| cebdce2 | A provider stop under OpenCode is reported with its API reason (`role_api_*`), not as a harness failure. | B-2 |
| b5a6d86 | An interrupted controller (SIGINT/TERM) writes a resumable record: `controller_interrupted` (not about the work), steps so far, and a pending decision. New `InterruptionGuard`. | A-1 |
| 83e4f87 | The continuation reuse pool is built after Jev routing sets efforts, so a routed effort (medium) matches the earlier run instead of the profile default (xhigh). | A-3 (an extra grok implement, $0.0538) |
| 3716542 | A `vendor_call_failed` stop from an API role is worded as an API call failure that points to `api_attempts`, not to a process/stderr. | A-3 |
| 4559ac5 | The model check pings once more when an API model answers the pong in prose (`invalid_json`/`missing_text`). The receipt records `ping_answers: 2` and `first_pong_failure`. A wrong nonce or a truncated pong is never asked again. | A-5, A-7 (DeepSeek failed about 1 in 3 pings) |
| 419bd53 | The relay names its own stop: `role_harness_turn_limit` or `role_harness_time_limit` instead of `role_harness_process_failed`. | B-3 |
| e7f958e | Per-profile relay limits (`max_requests`, output up to 8192, 4096 on GonkaRouter) and `RunArtifactShield` (permission rules plus bubblewrap mount namespace hide `.warden/runs/` and the Warden home from the OpenCode implementer). | B-3, Tol's decision |
| fd74184 | The shield also allows the exchange directory by its project-relative path, so the implementer can write its own artifact. | B-4 |

## Other problems observed (not Warden code)

- **GonkaRouter reliability.**
  - A GLM review returned HTTP 502 after 111 s, and a GLM ping took about 55 s.
  - During the gonkarouter-coder verification, relay turn 5 returned HTTP 502 after 110 s.
  - Earlier, there was a 503/429 outage of about an hour.
  - The gateway seems to end requests at about 110 s.
- **JoinGonka.** Coding turns are slow and the connection closes mid-dispatch.
- **GonkaRouter again (B-4).** A relay turn hung about 2.5 min, then returned HTTP 502 Bad Gateway. For that reason, the rest of Loop B moved to OpenRouter.
- **Relay cap of 8 requests and 2048 output tokens.** Raised per profile by Tol's decision (e7f958e). B-5 needed 24 requests.
- **Implementer reads `.warden/runs/`.** Fixed in e7f958e and fd74184 (see Loop B round 2). In B-5 the implementer never read an earlier run.
- **Auto-review classifier.**
  - `warden approve … --decision retry --note …` was refused three times with "An error occured while classifying this action", including the approval-card retry.
  - The decision's `updated_at` showed that the first "refused" call had in fact run.
  - Later approvals without `--note` passed.
- **Processes started without `setsid`** were killed with the agent session. Runs were then started with `nohup setsid`.
- **The OpenRouter headless-login URLs** in the test output come from test fixtures, not from a real login.

## Pending decisions left in the test project

- `s3-loopA-8`: human gate (accept/reject) pending. Not accepted.
- `s3-loopB-3`: retry recorded, no continuation run.
- `s3-loopB-4`: `role_timed_out` (GonkaRouter 502), decision pending, not continued.
- `s3-loopB-5`: human gate (accept/reject) pending. Not accepted. Its candidate is left uncommitted in the working tree of `hello-world-test` branch `stage3-loopB2`.
- All other runs: aborted, or retry recorded and continued.

## Test suite

- Last full `./test.sh` (after b5a6d86): 6021 passed, 0 failed.
- Targeted suites after the later fixes, all 0 failed: model-check 68, coding-relay 62, verified-opencode 28, interruption-guard 9, the task-routing-loop suite, and vendor-call-failed wording.
- Final full `./test.sh` at 419bd53 (2026-10-28, about 20:05 CDT): **6041 passed, 0 failed**, exit 0.
- Final full `./test.sh` at 29f9f7d (2026-10-29, about 03:30 CDT): **6080 passed, 0 failed**, exit 0. Targeted suites after fd74184: opencode-limits 35, verified-opencode 32, coding-relay 62.

## Follow-up — 2026-10-29, Windows automated coverage

Continued on `codex/agent-loop-priority` from `8e25674`. No additional live acceptance
run or paid provider call was made, and the test project's pending decisions were untouched.

- `shutdown-resume` starts a child JVM running the real `TaskLoop`, with deterministic
  local role fixtures. After the writer and gates finish and review starts, the controller
  calls `System.exit(130)`. The production shutdown hooks write `controller_interrupted`
  with a pending decision and retain the candidate. A retry continuation reuses implement,
  reruns gates and completes the unfinished review; the writer's dispatch count stays one.
  This exercises actual JVM shutdown on Windows. The review executor is injected, so
  provider-process cleanup and external signal delivery remain outside this fixture.
- `api-fallback-loop` uses a local HTTP server and the real `ApiExecutor` inside `TaskLoop`.
  It covers HTTP 502 followed by an explicitly permitted backup, and two consecutive
  human-confirmed switches through `ApprovalStore` and `Main.continuation`. The completed
  writer is reused, failed endpoints are not called again, switch limits and call accounting
  survive continuation, and the successful candidate still stops at the pending human gate.
- Stage 2's remaining ordered fallback policy is implemented; see the
  [Stage 2 note](NOTE-STAGE2-API-RESILIENCE-2026-10-28.md) and
  [configuration](API-CONNECTIONS.md#ordered-role-fallback). Cancellation during API retry
  backoff now reports `role_api_interrupted`, preserving the original attempt log, so a
  cancelled transport failure cannot open provider fallback. Each failed endpoint retains
  its structured API/relay receipt after a later successor succeeds.
- Windows fixture repairs preserve the assertions: an incomplete-body server now sends an
  orderly TCP EOF instead of an abortive reset that could discard unread headers; OpenCode
  limits use a platform-absolute executable path and canonical slash-separated permission
  inputs. Production path admission and body-phase classification are unchanged.

Verification (Windows, JDK 25.0.2, Java release 21):

- Clean `cmd /c build.cmd`: exit 0.
- Full `java -cp out/test-classes dev.warden.testing.TestMain`: **6201 passed, 1 failed**,
  exit 1. The sole failure was the POSIX-only `/opt/opencode` fixture in `opencode-limits`.
  All other suites, including the new fallback, HTTP continuation and shutdown cases, ran
  in this full execution. Log: `out/stage2-stage3-full-2026-10-29.log`.
- After the fixture-only portability fix and another clean build, `opencode-limits`:
  **31 passed, 0 failed**, exit 0. The Linux-only mount-namespace assertions do not run on
  Windows. Log: `out/stage2-stage3-opencode-limits-2026-10-29.log`.
- The full suite was not repeated after that test-only correction. Separately, the strict
  incomplete-body assertion passed in three consecutive `api-resilience` runs, each
  **102 passed, 0 failed**. `git diff --check`: exit 0.

At this point live Loop B stop/resume remained open. The box follow-up was stopped;
local reruns were allowed. The live Windows follow-up below subsequently demonstrated
interruption and implementation reuse with real OpenCode/API and CLI roles.

## Live local Loop B recovery — 2026-10-29

Recreated the recovery scenario in `out/stage3-loopb-local-20261029/project`, using
an isolated configuration home beside it. Personal profiles and the remote candidate
were not modified. This is a fresh small version-function task, rather than a copy of
the remote HTTP endpoint project. Jev and the second reviewer were omitted to isolate
the remaining interruption/reuse criterion; their earlier acceptance results stand.

- Implementer: OpenCode 1.19.4, OpenRouter `deepseek/deepseek-v4-flash-0731`,
  four-minute wall clock, at most 30 relay requests, output cap 8192.
- Reviewer: actual `codex-review` CLI profile, `gpt-6.1-sol`, requested effort medium.
- Fresh coding verification passed: real reads, edits, shell tools, unchanged test,
  model identity and schema-valid artifact; six relay requests. Both profiles passed
  fresh model checks before execution.
- One zero-role-call preflight (`local-b-interrupted`) refused the verification probe
  directory left in the project. Moved it to the adjacent `probe-evidence` directory.

| Run | Result |
| --- | --- |
| `local-b-stop` | DeepSeek implemented in 46.7 s with six relay requests; syntax and version tests passed. Sent external Windows `CTRL_C_EVENT` five seconds into real CLI review. JVM exited 130 and durably recorded `controller_interrupted`, finished implementation, unchanged candidate and pending decision. |
| `local-b-resumed` | Recorded retry. Baseline and implementation reused; gates reran and passed. CLI review stopped because atomic replacement of `.git/warden-execution.json` was denied. Correctly fenced the checkout as `worktree_recovery_required`. |
| `local-b-complete` | After the receipt fix and inspection/recovery, recorded retry and continued. Baseline and implementation reused again; gates reran; Codex review passed in 31.4 s. Reached `ready_for_human` with pending accept/reject and a released execution lease. |

Exactly one implementation dispatch exists across the chain. Its candidate fingerprint
is `35dc30c16c2ff3ed34b8f32fc78e8e0ed6d349f1a8b24d98900ce2c94a5aa3e5` throughout.
The chain receipt accounts for four role calls, including interrupted/failed reviews;
verification and model-check calls are separate. No acceptance or landing was performed.

The Windows replacement failure was reproduced with a real reader holding the destination
without delete sharing (`AccessDeniedException`). `JsonFile` now retries access-denied
atomic replacement for at most 500 ms, preserving the previous complete receipt.
Persistent denial still fails closed and cancellation preserves the interrupt flag.
Before `recover --confirm-stopped`, inspected the failed run's 23 process identities:
none remained alive; one PID had been reused by a newer unrelated process and was left alone.

Verification after the fix: clean `cmd /c build.cmd` passed; `json-file` **13 passed,
0 failed**; `worktree lease` plus `shutdown-resume` **97 passed, 0 failed**; live final
continuation passed. The full suite was not repeated. OpenCode's Windows shield used
permission rules, not Linux mount namespaces.

Local evidence under `out/stage3-loopb-local-20261029/`:
`result.json`, `interrupt.json`, `recovery-process-survey.json`, `verify.log`,
`interrupted.log`, `resumed.log`, `complete.log`, `run_interrupt.py`, and project
`.warden/runs/<run-id>/task-run.json`, role receipts and pending decision.
The signal helper starts Java in its own hidden console, attaches to that console and
sends Ctrl+C; it does not inject an executor or call `System.exit` inside the controller.

The remaining Loop B recovery criterion is now demonstrated locally. External Linux
signal delivery, current GonkaRouter/JoinGonka reliability and live ordered provider
fallback are still unverified by this follow-up. OpenRouter's raw implementation usage
reports about $0.001497, but the aggregate role/chain cost remains unknown/unpriced;
the displayed $0 charged is not evidence of a free run or a strict monetary cap.
