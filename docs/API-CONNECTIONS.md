# API connections

Warden can share a user-owned API connection across read-only text profiles. CLI subscriptions and legacy `runner: local` profiles keep their existing configuration. `runner: api` does not supply coding tools. An OpenCode profile can name a connection, but it is not an eligible implementer until a live tool loop is proven.

Connection files live under `~/.warden/connections/` (or `WARDEN_CONFIG_HOME`). They contain endpoints and credential references, never key values. This increment supports OpenRouter, GonkaRouter and custom OpenAI Chat Completions endpoints. The local dashboard edits those same files. On Windows, a connection may reference Credential Manager instead of an environment variable. OpenRouter can mint a user key through PKCE into that vault. Env mode does not require the vault.

## Configure a connection

```text
warden connections add openrouter-personal --provider openrouter --env OPENROUTER_API_KEY
warden connections add gonkarouter-personal --provider gonkarouter --env GONKAROUTER_API_KEY
warden connections list
```

Set an environment variable through your existing secret mechanism in the process that runs Warden, or store the key in Windows Credential Manager:

```text
warden connections add openrouter-personal --provider openrouter --os-store
warden connections auth-set openrouter-personal
```

`auth-set` reads the key from stdin. A console prompt hides it. Do not pass the key as an argument. `auth-remove` deletes that local vault entry and does not revoke the key at the provider. If Credential Manager cannot be locked and probed, `--os-store` is refused and nothing is written to a file instead. Do not paste a key into YAML, command arguments, prompt templates or shell verification probes. Adding a connection or storing a key does not activate a profile or change policy.

OpenRouter login is a separate command. The connection must already use `os-store:warden/<id>`, and the vault must be available. Warden sends the user to `https://openrouter.ai/auth` with an S256 challenge. The default flow listens on `127.0.0.1` for up to three minutes. `--headless` prints that URL and reads the pasted code from stdin, hidden when a console is attached. The code is exchanged at `https://openrouter.ai/api/v1/auth/keys`. A redirect is not followed. The issued key uses the same vault write as `auth-set` and is not placed in the connection file, the command report, or policy. If the file changes before that write, or the vault rejects the key, nothing is saved; revoke the new key in the OpenRouter account. This command does not call a model. GonkaRouter and environment-variable connections are refused before any authorization request.

```text
warden connections auth-login openrouter-personal
warden connections auth-login openrouter-personal --headless
```

The local test exchanges the code with a loopback stub. A live browser login was not run.

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

Each change carries the file revision from the row you opened. If the file changed in another window or command, the panel keeps the draft, asks you to reload, and accepts the new revision only after you choose «Изменить» on that row again. Unfamiliar YAML layouts and files that are not UTF-8 are left unchanged. Remove is refused while any profile references the connection, and it does not revoke the key at the provider. Check uses the same rules as `warden connections check` and does not stamp a model or a role.

```text
warden connections check openrouter-personal
```

The OpenRouter check calls its key metadata endpoint without inference. GonkaRouter/custom checks report local credential configuration and `auth_verified: false`; Warden does not invent an undocumented auth endpoint or call a model to check a key.

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

HTTP response size is bounded to 4 MiB and the deadline includes the response body. No API error is retried or routed to another profile automatically in this increment. Errors distinguish authorization, credits/budgets, rate limits, unsupported requests, network/protocol failures and identity mismatches, and stop for operator action instead of triggering a code repair.

Input/output/cache/reasoning tokens and request/model receipts are recorded when supplied. OpenRouter-reported `usage.cost` is authoritative for its connection; model-written artifact cost is ignored. GonkaRouter/custom cost remains unknown. Unknown cost is never zero. `runner: api` refuses declared `limits.max_cost_usd`, so strict task budgets cannot mistake an unenforced declaration for a proven spend bound. Advisory budgets retain the usual post-call accounting semantics.

Known credential values are redacted before raw responses, diagnostics, artifacts or verification receipts are persisted. Credentials are resolved inside the HTTP executor and are never installed in a CLI's global configuration. Registered connection environment variables are removed from CLI dispatches and shell verification probes launched by Warden. Existing external Orca processes retain their own environment; API credential forwarding through Orca is not implemented.

## Offline OpenCode profile

`examples/api/opencode-implementer.yaml` is the offline contract for a later coding adapter. It uses `runner: direct` and `harness: opencode` with a connection, an explicit model and `model_vendor`. It is not eligible for a role. `warden profiles --verify` refuses to stamp it, and a hand-written `verified_on` does not make it eligible. Copying it into `profiles/` does not add it to policy.

The adapter restores only that connection's environment variable after removing every connection variable, writes `{env:NAME}` into the generated config, and requires `opencode debug config` to show the base URL and requested model before `run`. Exit 0 without the Warden artifact does not complete the role. A served model that is missing or different fails closed. Read-only is still the worktree fingerprint; permission flags are only a claim. GonkaRouter is refused before any process because the no-fallback header is not a proven config option. Harness-reported cost is not billing, unknown cost is not zero, and `limits.max_cost_usd` is rejected. No live OpenCode or provider call was run for this contract.

## Disable or remove

```text
warden connections disable openrouter-personal
warden connections enable openrouter-personal
warden connections remove openrouter-personal
```

Add refuses to overwrite an existing file. Enable/disable preserves comments in supported YAML layouts and refuses unfamiliar layouts. Removing a connection is refused while any profile references it. These operations manage local configuration; revoke an actual API key in the service's account settings when needed. Removing a local reference does not revoke a remote key.

The full roadmap is in `DESIGN-API-CONNECTIONS-2026-10-26.md`. Provider-backed live inference and billing have not been verified as part of the implementation's local test suite.
