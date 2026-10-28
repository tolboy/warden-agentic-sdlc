# Jev task classification and effort routing

Jev is a decision model, not a coding worker. Warden calls the OpenRouter Decisions API with typed
questions and receives choices and probabilities. It does not ask Jev to generate JSON through chat,
and does not delegate worker/model switching to the separate Jev Router.
Sources: [Jev guide](https://openrouter.ai/docs/guides/community/jev),
[Decisions schema](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request),
[Jev Router](https://openrouter.ai/docs/guides/routing/routers/jev-router).

## Configure and check

Use `warden settings` for connections, worker/classifier profiles and routing. Classifiers live under
`~/.warden/classifiers/NAME.yaml` in the same `WARDEN_CONFIG_HOME`. They are separate from worker roles:
classification cannot grant coding tools or independent review. `warden profiles` includes a separate
`classifier_profiles` list. The panel creates/edits Jev profiles with an explicit returned-model snapshot.
Workers cannot shadow classifier names.

```yaml
version: 1
profile: jev-classifier
connection: openrouter-personal
model: typesafe/jev-1.14
reported_model: typesafe/jev-1.14-20261014
timeout_seconds: 15
max_input_characters: 8000
enabled: true
```

The connection uses the normal OpenRouter HTTPS endpoint and env/OS-store credential reference.
The adapter selects `/api/alpha/decisions` on that origin. The pinned snapshot above was observed
on 2026-10-28; an arbitrary same-family model or silently newer version is rejected.

```text
warden check --profile jev-classifier
warden classify my-task --dry-run
warden classify my-task
```

The check asks a Choice question about a fresh random marker. Only the correct choice from the pinned
model counts as pong. `classify` evaluates a task without starting workers; its dry run only shows
target stages. Explicit classify works even when automatic routing is disabled. Panel reads never
infer. Both actions can use API tokens independently of worker call limits.

## Automatic routing

The shared `~/.warden/settings.yaml` enables classification for actual `run`/`do` execution and decision
continuations. Editing ping settings preserves routing, and editing routing preserves ping.

```yaml
version: 1
check:
  before_run: true
  timeout_seconds: 90
  max_output_tokens: 512
routing:
  enabled: true
  classifier: jev-classifier
  min_confidence: 0.6
```

After static preflight and before model readiness/worker dispatch, Warden sends a bounded description
of the task, risk and active stages, without repository source. Jev classifies task kind, recommends
planning/review/visual roles, and proposes effort per supported stage. `min_confidence` is a minimum
probability that the task needs the selected effort or a higher level. Warden uses the highest level
supported by that cumulative probability, provided enough mass lies in one adjacent pair. Thus an
almost even xhigh/max split can select xhigh instead of falling back to medium; broadly scattered
advice retains configured effort. The API's separate `confidence` concentration value is recorded.
These initial thresholds are transparent policy, not a benchmarked guarantee of successful work.

Automatic application currently changes effort on configured GPT-6 and Grok 4.7 CLI/Orca profiles with an effort
control that reaches the vendor. Grok 4.7 is offered only low/medium/high/xhigh; max is never requested.
The mapping follows the [xAI model capability table](https://docs.x.ai/developers/model-capabilities/text/reasoning)
and the [Grok headless CLI guide](https://github.com/xai-org/grok-build/blob/main/crates/codegen/xai-grok-pager/docs/user-guide/14-headless-mode.md).
API/OpenCode and other model families keep their current controls.
Selected profiles still pass normal admission, independence and a fresh availability check using
effective effort. High-risk tasks have a high effort floor for classifier recommendations. Explicit
task/CLI/handover overrides win, and fixes inherit the resolved writer effort.

Role recommendations are recorded. They cannot remove mandatory stages, add undeclared workers,
approve actions or switch to paid backups. Workflow selection and escalation remain Warden policy.
Preparation keeps its planner settings; this phase adapts execution stages. Per-turn reclassification,
calibrated thresholds and automatic workflow changes are later work.

Low-probability advice, classifier failure, malformed answers, changed configuration or a different
returned model preserve configured workflow/efforts and record a fallback. No local heuristic silently
impersonates Jev. Strict monetary tasks skip unreserved classifier calls. Time, call and output limits
remain enforced, and model availability is still checked.

Receipts are under `~/.warden/routing/`; runs retain `task-routing.json` and `task_routing` in their
summary. They distinguish suggested, applied and effective efforts, retain typed role advice and
numeric usage, and omit credentials and raw task text.

## Optional cost telemetry

New tasks without an explicit dollar threshold default to `budgets.cost_cap: off`. Reported costs are
recorded and unpriced calls stay unpriced; neither availability nor classification needs a model to
return a bill. `off` still enforces calls, elapsed time and output caps. Existing tasks that explicitly
supply `max_cost_usd` retain the legacy threshold unless they select `off`. Explicit `threshold` and
`strict` remain advanced operator choices with their previous guarantees.

```yaml
budgets:
  cost_cap: off
  max_role_runs: 8
  max_elapsed_minutes: 30
```

Legacy `total_cost_usd` is a reported subtotal for compatibility. `cost_status` says `unknown` or
`partial` when unpriced calls exist, so it is not a complete invoice. Provider-side key limits and
account billing are separate. Jev's optional `usage.cost` is recorded when present, never invented.

## Live validation, 2026-10-28

A low-risk task in the existing Orca hello-world workspace exercised real Jev classification,
CLI readiness, a Codex worker, baseline and acceptance gates. Jev selected low; the worker
launch contained `model_reasoning_effort="low"`. The provider did not independently report
its effective effort, so the receipt says `not_reported_by_provider` rather than attesting it.
The worker added string whitespace trimming, both acceptance commands passed, and the run
reached `ready_for_human` with accept/reject still pending. Low-risk policy skipped review
and visual stages; this live run does not validate those paths. A separate real Grok 4.7
low-effort CLI challenge returned pong. Offline integration tests cover mandatory review,
explicit effort precedence and the Grok-only choice set. This is functional validation,
not evidence that Jev's effort policy is optimally calibrated.
