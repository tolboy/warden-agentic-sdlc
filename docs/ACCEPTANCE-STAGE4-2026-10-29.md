# Stage 4 acceptance — model capabilities and effort — 2026-10-29

Stage 4 is implemented. Model selection through Jev remains Stage 5.

## Delivered behavior

| Requirement | Evidence |
| --- | --- |
| Per-model capability registry with provenance | Shared `EffortCapabilities` drives settings, routing and admission. Catalog receipts retain source, observation date, connection identity and delivery method. CLI compatibility fallback remains labelled as a fallback; an unknown date is not invented. |
| API transmission | Native API and fresh pongs send exact `reasoning.effort` after catalog admission. Real loopback HTTP tests inspect the request body. Unsupported/missing/stale capability data stops before a request. |
| OpenCode transmission | The verified adapter configures the relay, which enforces the selected value on every upstream turn and strips competing harness reasoning options. Verified harness tests observe the effective upstream JSON. |
| CLI/Orca transmission | CLI argv and Orca launch receipts have separate transmission evidence. Failed spawn and mismatched/unknown Orca launch settings do not claim delivery. Known model catalog restrictions apply to explicit run overrides too. |
| Unsupported level and fallback | No Warden remapping from `max` to another value. Unsupported effort and provider effort mismatches do not trigger automatic provider fallback. Classifier refusal/failure retains configured workflow and effort; explicit task/run/handover choices retain priority when admissible. |
| Planning | Preparatory planning retains its own explicit settings. Execution routing occurs later; retroactive application would misreport the planner's call. |
| Questions and threshold | Six real Jev requests plus deterministic distribution replay compare simple, complex and ambiguous cases at 0.5/0.6/0.75. Keep 0.6 provisionally; no optimum or task-performance guarantee claimed. |
| Honest receipts | Requested, transmitted, effective and independently provider-reported effort are distinct. Token usage never confirms effort. Unknown values and provider mismatches are explicit. Aggregate relay confirmation requires all turns to report the matching value and is cleared after later failures. Safe measurements preserve provenance and transmission fields. |

## Catalog and protocol boundary

OpenRouter's model metadata supplies `reasoning.supported_efforts`; its chat field is
`reasoning.effort`. The adapter also accepts explicitly compatible custom endpoints
using that metadata schema. API model membership without effort metadata is insufficient.
Direct GonkaRouter/JoinGonka effort settings remain unsupported until their model metadata
and wire controls are registered; their ordinary API/OpenCode usage still works.

The fresh live OpenRouter catalog listed **none, low, high, max** for
`deepseek/deepseek-v4-flash-0731`. In particular, Warden does not offer `medium` for this
model. A gateway can translate effort internally; exact Warden transmission does not
prove what its upstream provider ultimately used.

Protocol source, checked 2026-10-29:
[OpenRouter reasoning controls and model metadata](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens).

## Live acceptance

Used a disposable configuration home under `out/stage4-live/home`; personal settings
were not edited. Requests used the existing OS credential reference without copying
secret values into profiles or reports.

- Native text API: DeepSeek via OpenRouter, requested/transmitted `low`, schema-valid
  reviewer artifact, native verification stamped. Provider model identity matched.
  Provider-reported usage: 645 input / 168 output tokens, $0.0000460873.
- Real OpenCode 1.19.4: six relay requests, each transmitted `low`; model identity,
  read/edit/bash tools, unchanged test, source change and final artifact all passed.
  Native coding verification stamped. Usage: 31,722 input / 902 output tokens;
  raw per-turn usage cost sums to $0.0011859868. The existing aggregate coding cost
  remains unknown, and is not represented as zero or a strict spending guarantee.
- Neither service response independently reported an effort value. Both receipts say
  `not_reported_by_provider`, with transmitted `low` and null reported effort.
- The first native API probe itself passed, but stamping refused an inline YAML
  `verification` map. Changed the disposable profile to the supported block layout;
  the second native probe and stamp passed. Both paid calls are retained in the logs.
- Jev: six real typed decisions passed. See the
  [evaluation report](NOTE-STAGE4-EFFORT-EVALUATION-2026-10-29.md) for distributions,
  thresholds, usage and the preparatory-planning decision.

Local receipts: `out/stage4-live/result.json`, `api-verify.log`, `api-verified.log`,
`coding-verify.log`, `home/verification/`, `home/vendors/api-openrouter-personal-catalog.json`;
Jev receipts: `out/stage4-calibration/live.json` and `synthetic.json`.
The local directories are ignored run artifacts, not committed personal configuration.

## Validation

Clean Windows `cmd /c build.cmd` succeeded (Java release 21).
Targeted regression groups all passed:

- Configuration/routing/workflow/override: **564 passed, 0 failed**,
  `out/stage4-config-final.log`.
- Transport/model checks/resilience/fallback: **403 passed, 0 failed**,
  `out/stage4-transport-final.log`.
- Launch settings and safe corpus projection: **221 passed, 0 failed**,
  `out/stage4-evidence-final.log`.
- Role runner, direct capture, Orca start and fallback: **388 passed, 0 failed**,
  `out/stage4-evidence-remaining.log`.

The groups overlap in fallback coverage; these counts are executions, not unique tests.
The full repository suite was not repeated.
The model-settings JavaScript also passed `node --check` after extraction from the rendered
Java text block.

Review found and fixed stale aggregate confirmation after a later relay failure and
acceptance of unknown structured provider effort as absent metadata. New HTTP500/timeout
and unknown-value tests cover those cases. One new settings test originally used an inline
policy layout incompatible with the existing settings editor; the fixture now uses its
normal block layout. No acceptance or landing was performed on a project candidate.
