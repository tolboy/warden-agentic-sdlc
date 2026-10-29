# Stage 3 acceptance loops — 2026-10-28

Branch `grok/acceptance-stage3` (from `grok/api-resilience-stage2`, 1c5d392). Test project
`/workspace/hello-world-test` (base ec5566f), branches `stage3-loopA` (task `health-endpoint`)
and `stage3-loopB` (task `version-endpoint`). Warden was run only through the box launcher. No run
was accepted and `warden accept` was never used. All times are CDT (UTC-5).

## Summary

| Criterion | Loop A (CLI implementer + API reviewer) | Loop B (API implementer via OpenCode + CLI reviewer) |
| --- | --- | --- |
| Jev routing | pass (`{implement=medium}` in every run) | pass (`{review=medium}`) |
| Model checks | pass (after the pong fix, 4559ac5) | pass |
| Implement | pass (grok-implement, grok-4.7 medium) | **not met**: 3 attempts, none produced a change |
| Gates | pass (`node --test`, `node .warden/checks/health.mjs`) | not reached |
| Mandatory review | pass (GonkaRouter DeepSeek-V4-Flash, verdict pass) and review-second (agy, verdict pass) | not reached |
| Stop/resume | pass: SIGINT mid-review, continuation reused reproduction, implement and review | **not met**: no run got as far as review |
| End state | `s3-loopA-8` `ready_for_human`, decision pending (accept/reject left to a person) | `s3-loopB-3` `implementer_failed`, retry recorded but not run |

Run budget: the brief allowed about 6 loop runs. 8 dispatching runs were used (B-1, B-2, A-1, A-2,
A-3, A-6, A-8, B-3), plus 3 preflight stops that made no role call (A-4, A-5, A-7). The budget is
spent, so Loop B is still unfinished.

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

Not done, because the budget is spent: a passing implement, gates, codex review, the interrupt mid-review, and a continuation that shows reuse.

To finish Loop B, run (one run, or two with the interrupt):

```
warden run version-endpoint --run-id s3-loopB-4 --continue s3-loopB-3 --use implement=gonkarouter-coder --use review=codex-review
```

Interrupt that run during `[3/6]`, approve retry, then continue it as `s3-loopB-5`.

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

- **fd74184**: the exchange directory is also allowed by its project-relative path, after the denies. The test evaluates the rules last-match-wins, as OpenCode does, for relative and absolute forms (earlier runs stay denied, own artifact allowed). `opencode-limits` now has 35 checks.
- **Provider switch.** GonkaRouter was unstable again (a 502 after a hang, the same ~110 s+ gateway failure as before), so per the steering, the remaining run uses OpenRouter. The new profile `openrouter-coder` uses the same harness and limits (`max_requests: 30`, `max_output_tokens: 8192`, 12 min), connection `openrouter-personal` (`env:OPENROUTER_API_KEY`), and model `deepseek/deepseek-v4-flash-0731`, the same model family as on GonkaRouter. It was verified 2026-10-29: 6 relay requests, 30739/792 tokens, $0.0011, `mount_namespace`, `effective_config_checked: true`. `warden check` gave pong ($0.000014).

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
- **Relay cap of 8 requests and 2048 output tokens.** These are the attested bounds (`CodingAttestation.MAX_REQUESTS`). They are too tight for an exploring model on a real task. Raising them changes the attested security envelope, so they were left for a deliberate decision.
- **Implementer reads `.warden/runs/`.** The OpenCode implementer read earlier runs' raw outputs. A role prompt or tool-scope rule that excludes `.warden/runs/` is a follow-up.
- **Auto-review classifier.**
  - `warden approve … --decision retry --note …` was refused three times with "An error occured while classifying this action", including the approval-card retry.
  - The decision's `updated_at` showed that the first "refused" call had in fact run.
  - Later approvals without `--note` passed.
- **Processes started without `setsid`** were killed with the agent session. Runs were then started with `nohup setsid`.
- **The OpenRouter headless-login URLs** in the test output come from test fixtures, not from a real login.

## Pending decisions left in the test project

- `s3-loopA-8`: human gate (accept/reject) pending. Not accepted.
- `s3-loopB-3`: retry recorded, no continuation run.
- All other runs: aborted, or retry recorded and continued.

## Test suite

- Last full `./test.sh` (after b5a6d86): 6021 passed, 0 failed.
- Targeted suites after the later fixes, all 0 failed: model-check 68, coding-relay 62, verified-opencode 28, interruption-guard 9, the task-routing-loop suite, and vendor-call-failed wording.
- Final full `./test.sh` at 419bd53 (2026-10-28, about 20:05 CDT): **6041 passed, 0 failed**, exit 0.
