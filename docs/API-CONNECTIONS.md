# API connections

Connections, model profiles, native verification, live pings and role ordering are now available together
in `warden settings`. See [model checks and shared settings](MODEL-CHECKS.md) for automatic pre-run checks,
their token/time bounds and separate diagnostic spending.

Warden can share a user-owned API connection across text profiles and verified OpenCode implementers. CLI subscriptions and legacy `runner: local` profiles keep their existing configuration. `runner: api` does not supply coding tools. OpenCode admission requires a native capability receipt for the exact profile, connection and executable.

Connection files live under `~/.warden/connections/` (or `WARDEN_CONFIG_HOME`). They contain endpoints and credential references, never key values. This increment supports OpenRouter, GonkaRouter, JoinGonka Gateway and custom OpenAI Chat Completions endpoints. The local dashboard edits those same files. On Windows, a connection may reference Credential Manager instead of an environment variable. OpenRouter can mint a user key through PKCE into that vault. Env mode does not require the vault.

## Configure a connection

```text
warden connections add openrouter-personal --provider openrouter --env OPENROUTER_API_KEY
warden connections add gonkarouter-personal --provider gonkarouter --env GONKAROUTER_API_KEY
warden connections add joingonka-personal --provider joingonka --os-store
warden connections list
```

Set an environment variable through your existing secret mechanism in the process that runs Warden, or store the key in Windows Credential Manager:

```text
warden connections add openrouter-personal --provider openrouter --os-store
warden connections auth-set openrouter-personal
```

`auth-set` reads one bounded line from stdin; pressing Enter completes input without requiring EOF. A console prompt hides it. Do not pass the key as an argument. `auth-remove` deletes that local vault entry and does not revoke the key at the provider. If Credential Manager cannot be locked and probed, `--os-store` is refused and nothing is written to a file instead. The helper directory must belong to an individual user, and its ACL must name that full Windows identity; ambiguous names and group owners are refused. Do not paste a key into YAML, command arguments, prompt templates or shell verification probes. Adding a connection or storing a key does not activate a profile or change policy.

OpenRouter login is a separate command. The connection must already use `os-store:warden/<id>`, and the vault must be available. Warden sends the user to `https://openrouter.ai/auth` with an S256 challenge. The default flow listens on `127.0.0.1` for up to three minutes. `--headless` prints that URL and reads the pasted code from stdin, hidden when a console is attached. The code is exchanged at `https://openrouter.ai/api/v1/auth/keys`. A redirect is not followed. The issued key uses the same vault write as `auth-set` and is not placed in the connection file, the command report, or policy. If the file changes before that write, or the vault rejects the key, nothing is saved; revoke the new key in the OpenRouter account. This command does not call a model. GonkaRouter and environment-variable connections are refused before any authorization request.

```text
warden connections auth-login openrouter-personal
warden connections auth-login openrouter-personal --headless
```

The authorization URL is printed before Warden asks for a headless code. The exchange has a 30-second deadline covering the complete response and refuses bodies larger than 8 KiB or malformed UTF-8. Local tests exchange the code with a loopback stub, including stalled and oversized responses. A live browser login was not run.

```yaml
version: 1
connection: openrouter-personal
provider: openrouter
protocol: openai_chat
base_url: https://openrouter.ai/api/v1
auth:
  type: api_key
  credential_ref: env:OPENROUTER_API_KEY
enabled: true
```

Preset providers bind credentials to their documented HTTPS API endpoint. Custom connections require HTTPS, except for explicit loopback endpoints. URL credentials, query strings and fragments are refused. Redirects never forward credentials. Local unauthenticated development is explicit:

```text
warden connections add local-test --provider custom --base-url http://127.0.0.1:11434/v1 --no-auth
```

`connections list`, `profiles`, `doctor` and the dashboard show local credential presence without returning its value or sending inference. Missing, invalid and disabled credentials cannot make a runnable API profile. These states do not assert that the service has accepted a key.

## Edit in the dashboard

The Agents page has a connections editor. It is available from `warden dashboard` and from the page opened by `warden decide`. The form asks for an environment variable name. A key field is shown only after the Windows vault probe succeeds; the value is posted once, cleared from the form, and is not returned by GET or stored in browser storage. Saving, enabling, disabling, or storing a key does not add a profile to policy.

Each change carries the file revision from the row you opened. If the file changed in another window or command, the panel keeps the draft, asks you to reload, and accepts the new revision only after you choose «Изменить» on that row again. Unfamiliar YAML layouts and files that are not UTF-8 are left unchanged. Vault writes and deletes hold the connection lock from the revision check through the vault operation, so a simultaneous reference edit cannot redirect a stale key operation. This revision describes the YAML file: separate rotations of the same vault entry serialize, but do not have a separate credential revision. Remove is refused while any profile references the connection, and it does not revoke the key at the provider. Check uses the same selected credential source as the list and does not stamp a model or a role. Cancelling the form clears its key field.

```text
warden connections check openrouter-personal
```

The OpenRouter check calls its key metadata endpoint without inference. GonkaRouter/JoinGonka/custom checks report local credential configuration and `auth_verified: false`; Warden does not invent an undocumented auth endpoint or call a model to check a key. JoinGonka binds to `https://gate.joingonka.ai/v1`. It has its own key and account; a GonkaRouter key is not reused there. No JoinGonka fallback control or strict spend cap is claimed.

## Configure and verify a text profile

Run `warden setup` for starter prompt/schema files. Copy an example from `examples/api/` into your user `profiles/` directory. The filename must match `profile`. Select a model available to your account; model IDs differ between gateways.

`runner: api` requires a connection, explicit model and `model_vendor`, and is read-only. It rejects implementer/vision/tools/MCP/effort settings. `model_vendor` identifies the model developer, such as `deepseek` or `z-ai`, rather than a router. Known CLI labels (`codex`, `claude`, `grok`) are normalized when checking independence against API models. Other legacy vendor identities retain their previous interpretation in this increment; broader identity migration remains separate.

```text
warden profiles --verify openrouter-review
```

This is an explicit, potentially paid inference: one bounded synthetic text/artifact probe through the native API executor. It does not run a profile's shell probe and does not inspect a repository. Success stamps the date and a fingerprint of the connection, model, output limit, prompt/schema paths and contents. Changes to those inputs require verification again. The receipt says `text_artifact_only`; it never proves coding tools or review quality.

After verification, explicitly add the profile to the intended role in `~/.warden/policy.yaml`. A normal dry run shows connection and credential readiness without sending inference.

## Context and execution limits

API roles have no filesystem or command tools. A role dispatched through Warden receives a bounded diff and current changed-source snapshot. Warden refuses unsupported/binary/secret/oversized context rather than silently truncating it. Warden's own `.warden` metadata is excluded. The snapshot is limited to changed source; the model must return an inconclusive result when required dependencies or evidence are absent. Native synthetic verification has no repository snapshot because it is not a review.

Every request sets an output token limit (default 2048). GonkaRouter refuses a configured limit above 4096; model fallback is disabled. OpenRouter requires supported parameters and disables provider fallback for this first increment. A response with unknown/substituted model identity, tool calls, truncated output or invalid artifact fails.

HTTP response size is bounded to 4 MiB and the deadline includes the response body. Errors distinguish authorization, credits/budgets, rate limits, unsupported requests, network/protocol failures and identity mismatches, and stop for operator action instead of triggering a code repair. Nothing is routed to another profile, connection or model automatically.

### Failure evidence and retries

Every API failure is recorded as a fixed code plus a `failure_phase` (`connect`, `dns`, `tls_handshake`, `headers`, `body`, `connect_deadline`, `headers_deadline`, `body_deadline`, `cancel`, `http_status`, `parse`, `identity`, `output_limit`) and a `delivery` flag:

- `not_sent`: the request provably never reached the service (refused connection, DNS, TLS handshake, connect deadline). `vendor_turn_started` is `false`.
- `unknown`: the request may have been processed and paid for (connection dropped after sending, body cut, slow body, cancel).
- `answered`: the service returned a status.

No exception message or response body is stored. A provider error is reduced to a short redacted `provider_error` field with key-shaped strings and bearer tokens removed.

Each logical call records a `correlation_id`, `api_retry_policy`, `api_attempt_count`, `api_attempts` (each row: attempt number, correlation id, run id, profile, stage, call, delivery, phase, status, request id, duration) and `api_retry_stopped` (`retries_exhausted`, `deadline`, `cancelled`). Usage and cost are summed across attempts; if any attempt that may have reached the service has no reported cost, `cost_status` is `unknown`, never 0.

Retry policies are fixed in code and capped at 2 extra attempts (3 in total):

| Caller | Policy | Resent |
| --- | --- | --- |
| Writing and reviewing roles (`ApiExecutor`), OpenCode relay upstream | `not_sent_only` | only `not_sent`, backoff 0.5 s × attempt |
| Pong checks (`ModelCheck`) | `check` | `not_sent`; 429 with a whole-second `Retry-After` ≤ 10 s; 502/503; backoff 1 s |

An unknown outcome, an auth refusal, a model mismatch, a truncated answer and a cancel are never resent. A role call therefore cannot send the same turn twice, and the relay forwards at most one upstream request per OpenCode request, so a tool call (file edit) is never duplicated by a retry. Cancelling during backoff stops at once.

### Continuing after an API failure

API transport failures map to infrastructure reasons (`role_api_timeout` → `role_timed_out`; network/provider/protocol/unsupported → `vendor_call_failed`; `rate_limited`; credits/budget → `quota_exhausted`; auth/access → `vendor_unavailable`; truncation/identity/artifact → `vendor_protocol_failed`). These are not judgements about the work, so `warden run --continue` keeps the completed candidate and its stage results and reruns only the unfinished stage: a reviewer that failed on a 502 is called again without rerunning the implementer. Configuration codes (missing key, disabled connection, changed profile) are not mapped and still require the operator.

Auth errors never open a browser, change the key status beyond `auth_invalid`, or switch the model. Provider or model fallback happens only through an explicit policy the operator wrote; an ordered per-role fallback list is not implemented yet.

Input/output/cache/reasoning tokens and request/model receipts are recorded when supplied. OpenRouter-reported `usage.cost` is authoritative for its connection; model-written artifact cost is ignored. GonkaRouter/custom cost remains unknown. Unknown cost is never zero. `runner: api` refuses declared `limits.max_cost_usd`, so strict task budgets cannot mistake an unenforced declaration for a proven spend bound. Advisory budgets retain the usual post-call accounting semantics.

Known credential values are redacted before raw responses, diagnostics, artifacts or verification receipts are persisted. Credentials are resolved inside the HTTP executor and are never installed in a CLI's global configuration. Registered connection environment variables are removed from CLI dispatches and shell verification probes launched by Warden. Existing external Orca processes retain their own environment; API credential forwarding through Orca is not implemented.

## Legacy offline OpenCode profile

`examples/api/opencode-implementer.yaml` is the offline contract for a later coding adapter. It uses `runner: direct` and `harness: opencode` with a connection, an explicit model and `model_vendor`. It is not eligible for a role. `warden profiles --verify` refuses to stamp it, and a hand-written `verified_on` does not make it eligible. Copying it into `profiles/` does not add it to policy.

The adapter restores only that connection's environment variable after removing every connection variable, writes `{env:NAME}` into the generated config, and requires `opencode debug config` to show the base URL and requested model before `run`. Exit 0 without the Warden artifact does not complete the role. A served model that is missing or different fails closed. Read-only is still the worktree fingerprint; permission flags are only a claim. GonkaRouter is refused before any process because the no-fallback header is not a proven config option. Harness-reported cost is not billing, unknown cost is not zero, and `limits.max_cost_usd` is rejected. No live OpenCode or provider call was run for this contract.

## Explicit live coding check

`coding-check` is a separate, potentially paid experiment, available without activating a profile. It supports the pinned native OpenCode **1.19.4** executable. For example, install it locally with `npm install --prefix out/opencode-runtime --no-audit --no-fund opencode-ai@1.19.4`, then use the packaged `node_modules/opencode-windows-x64/bin/opencode.exe`. Warden records the binary hash and checks its version. Use a disposable working copy: the command creates a new `warden-coding-probe-*` directory there and lets the coding agent run shell commands. This is not an OS sandbox.

```text
warden connections coding-check joingonka-personal --project C:\path\to\disposable-worktree --opencode C:\path\to\opencode.exe --model zai-org/GLM-5.3-Flash
```

Each check creates a failing greeting test, asks OpenCode to read/edit/run it and write an artifact, then independently reruns the original unchanged test. A plain text answer, missing observed read/edit/bash tool results, an altered test, incomplete output or a substituted model cannot pass. A failed check is retained, not silently retried. Receipts, redacted event output and effective config checks remain in that fixture directory.

A run-scoped loopback relay keeps the upstream key in Warden. OpenCode receives a random temporary relay credential; it does not receive the service key in arguments, environment or config. The relay permits only the selected model and Chat Completions route, at most eight requests, a four-minute window, 2048 output tokens per request, 512 KiB request bodies and the existing 4 MiB/45-second upstream response bound. It requests a complete nonstreaming upstream response, validates its model identity before releasing content/tools, and converts it into SSE for OpenCode. GonkaRouter's no-fallback header is sent by Warden's HTTP client. JoinGonka fallback behavior remains unproven. Usage from all observed calls is retained; this is not a strict monetary cap.

OpenCode uses fresh XDG config/data/cache/state and database paths, project config is disabled and pure mode excludes external plugins. Warden checks the effective provider, model, permissions, plugins, commands and MCP configuration before inference. This protects this probe's configuration; it does not prove arbitrary agent code is confined to the fixture directory.

**A successful coding check does not stamp a profile or enable a loop role.** It remains a standalone diagnostic. Use native profile verification below for loop admission. Live checks of this dedicated probe were performed on 2026-10-27; consult their receipts for the exact connection/model pair and outcome.

## Verified OpenCode implementer

`examples/api/joingonka-coder.yaml` describes the admitted path. Set `harness_executable` to the absolute path of the pinned native OpenCode 1.19.4 executable, use explicit positive `limits.max_output_tokens`, and keep `role: implementer`, `read_only: false`, `enforce_schema: true`. Other roles and legacy profiles without the executable remain ineligible. Strict `max_cost_usd` remains unsupported.

```text
warden profiles --verify joingonka-coder
warden profiles
```

Run verification from a disposable project: it creates a new `warden-profile-probe-*` fixture there. Warden invokes the same executor used for normal dispatch, through the bounded relay. Native verification checks observed reading and shell use, an actual source change, an initially failing and then independently passing unchanged test, and an artifact valid against the profile schema. Edits may use either a dedicated file tool or the shell; the original observed tool list is retained. Prose promises, a successful process exit, or a hand-written date cannot substitute for that proof.

Success writes `verification/<profile>.coding.json` and stamps the profile fingerprint/date. The receipt binds the profile/connection, prompt and schema contents, executable path and SHA-256, adapter/version, role and limits. Missing or changed inputs invalidate admission even on a cached profile. A narrower deadline for one task tightens execution without changing the underlying verified configuration. Native verification reloads inputs before stamping so a proof cannot stamp settings changed during the test.

The loop uses the verified native executable with fresh per-run config/state and the same relay. Instructions and schema travel through a dispatch file, avoiding shell quoting of JSON. Upstream keys remain outside the agent process. The first offered coding-tool request requires a tool choice; metadata requests without tools remain text calls. Every response model is checked before releasing content or tool requests. A mismatched model or adapter/configuration failure stops for operator action rather than triggering an automatic paid replacement or a code-repair attempt. A mismatch after earlier valid edits retains those edits and all earlier call receipts.

Saving or verifying a profile does not change policy. Explicitly select the verified profile for an implementer role or a task override. The first release keeps an eight-request/2048-output-token/four-minute ceiling (or a tighter profile/task limit). Missing usage remains unknown; partial known calls do not become a complete zero-valued total. Costs remain provider evidence/advisory, and tool permissions are not an OS sandbox.

## Disable or remove

```text
warden connections disable openrouter-personal
warden connections enable openrouter-personal
warden connections remove openrouter-personal
```

Add refuses to overwrite an existing file. Enable/disable preserves comments in supported YAML layouts and refuses unfamiliar layouts. A running loop rechecks a verified API profile's connection before its next dispatch: a disabled, missing or changed connection stops that dispatch before HTTP. A request already sent finishes against its original connection. Removing a connection is refused while any profile references it. These operations manage local configuration; revoke an actual API key in the service's account settings when needed. Removing a local reference does not revoke a remote key.

The full roadmap is in `DESIGN-API-CONNECTIONS-2026-10-26.md`. Live probe results are separate from the offline test suite. Reported usage/cost is provider evidence, not independently reconciled billing.
