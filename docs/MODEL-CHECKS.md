# Model checks and shared settings

Typed Jev profiles and optional task effort selection use the same settings panel.
See [Jev routing](JEV-ROUTING.md) for Decisions API checks, fallback and optional cost telemetry.

`warden settings --open-orca` opens the dashboard with connections, CLI/API profiles, role ordering,
capability verification and live availability checks in one panel. It reads the same personal files
as commands (`WARDEN_CONFIG_HOME`, otherwise `~/.warden`). Opening or refreshing settings sends no inference.

```text
warden check                       # all configured profiles, including unverified ones
warden ping                        # alias
warden check --profile joingonka-coder
warden check --task my-task         # the task preview's selected profiles
warden settings --project C:/repo --open-orca
```

Checks consume subscription quota or API tokens. API checks send a synthetic prompt without repository
contents or tools and require a fresh random pong value from the exact requested model. HTTP 200, a
balance response, a login page or an old pong is insufficient. Shared API transport retains credential
isolation, origin/redirect controls, body/deadline bounds, provider options and numeric usage receipts.
A CLI check renders the profile's own argv/model/effort/prompt delivery in a disposable Git directory,
with no project contents or MCP servers supplied by Warden, and requires the fresh marker in the
assistant response. CLI launch configuration is not a provider identity attestation. An Orca profile
uses the native worker probe and settled completion; a direct CLI test cannot replace that channel.

Pong means **available at check time**. It does not promise availability throughout a run, prove coding
tools or vision, change verification, establish independent review, assign a profile or permit paid
fallback. Runtime failures retain their existing handling.

API pings use the `check` retry policy: a refused connection, a 502/503, or a 429 with a short
`Retry-After` is retried at most twice; every attempt is recorded (`api_attempt_count`, `api_attempts`).
An auth refusal is asked once and never changes the model. A ping reports `max_output_tokens` and
`reasoning_model`. Reasoning models (DeepSeek, GLM, Qwen3/QwQ, Kimi K2, MiniMax, GPT-5, o-series) think
before answering, so their ping limit is raised to at least 2048 tokens (never above the profile limit,
and at most 4096 on GonkaRouter). A pong cut off by the output limit fails as
`role_api_output_truncated` with `pong_failure: output_limit`; that is a limit problem, not an
authentication problem, and the advice is to raise `max_output_tokens`, not to sign in.
Dated live ping results are in [LIVE-API-PINGS-2026-10-28](LIVE-API-PINGS-2026-10-28.md).

## Before a run

CLI launchers for `run`, `do` and decision continuations attach the same readiness service. After static
admission and before baseline/working role dispatch, the loop checks active stage selections (including
effective effort/profile overrides), fixers and verified roster alternatives. A failed response stops as
`model_check_failed`; it never silently switches models. Dry runs send no ping. Preparation is checked
before its first planner call; execution is checked again after preparation, since the planner can take
time and change the contract. The library `TaskLoop`/`DoCommand` readiness seam is explicit: embedded
callers must install `RunReadiness.live(...)` or their own implementation. Standalone `warden role`
retains its behavior; use `warden check --profile NAME` before an individual role call.

Central personal settings, editable in the same panel:

```yaml
version: 1
check:
  before_run: true
  timeout_seconds: 90
  max_output_tokens: 512
```

The file is `~/.warden/settings.yaml`. An absent file uses these defaults; a project cannot disable it.
Timeout accepts 15–180 seconds and API output ceiling 128–2048 tokens. Orca additionally has its existing
bounded launch/cleanup steps. CLI flags and token/turn limits remain those of the configured profile.
There is no three-hour cache: launch boundaries obtain new answers. Display timestamps are historical.

Diagnostic spending is separate from the working role budget. Receipts under `~/.warden/checks/` record
`diagnostic_calls`, `unpriced_calls`, per-profile usage and `reported_cost_usd` (a known subtotal, never
an assertion that unknown total cost is zero). Automatic checks refuse inference when a task requires a
strict monetary cap and no diagnostic monetary reservation can be proven. The operator can explicitly
disable automatic checks in personal settings; manual checks remain available. Runs retain their check
result in `model-check.json` and `task-run.json`.

## Editing profiles and roles

The panel can create an API text reviewer or OpenCode implementer using standard prompt/schema resources
from `warden setup`, edit a CLI model/effort or API model/connection, run the separate native capability
verification, and save an ordered roster of verified profiles of the correct role. Unrelated policy
constraints, failover consent, CLI flags and verification probes survive editing. Editing removes the
old verification stamp: a pong cannot release a changed model into a role. Saving a connection/profile
does not change the roster or subscription policy. New role kinds, workflow structure, advanced flags
and provider catalog discovery still use the existing files/commands.

Writes validate the strict configuration and require the revision displayed by the editor; stale pages
receive a conflict. Paid actions and mutations require the dashboard page token, JSON requests and the
existing loopback host/origin restrictions. Errors omit provider bodies and arbitrary exception text.
Configuration changes invalidate displayed last results. Approval stays on the native decision page,
opened from this dashboard; accepting a candidate does not land it.
