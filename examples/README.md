# Example configurations and historical experiments

Examples illustrate a contract or adapter. They are not recommended personal settings,
service availability promises, or a current model catalog. Install normal resources with
`warden setup`, choose your own connection/model, and perform the channel's native checks
before assigning a profile. Copying YAML never grants a role or a capability receipt.

| Example | Intended use |
| --- | --- |
| [demo](demo/README.md) | Current deterministic machine-boundary demonstration; no model or key required. |
| [pilot](pilot/README.md) | Opt-in bounded external-pilot template; not a global default or live proof. |
| [API profiles](api/README.md) | Connection-backed text and verified native OpenCode templates, with the legacy offline file labelled separately. |
| `orca/` | Explicit Orca-channel contracts; read the profile's limits and verify through that channel. |
| `agy/` | Adapter examples with specific historical model IDs and flags; confirm the installed client supports them before use. |

## Historical four-vendor eval

`examples/evals/four-vendor` is not present in this checkout. Its older Git snapshots
(such as `94f0c34`) record an experiment, not a personal configuration baseline. Do not
restore those profiles wholesale into `~/.warden` or treat their models, flags, account
names, prices or verification state as current defaults.

That experiment's `deepseek-review` used the Codex client with an OpenRouter-backed
configuration (`model_provider = "openrouter"`). It did not establish a separate
DeepSeek CLI service. Authentication service, model developer and execution client
remain distinct in current Warden settings. Use a connection-backed profile for current
API integration rather than reviving that test arrangement by its old label.

Dated transcripts in [SMOKE.md](../docs/SMOKE.md) are also historical evidence. Current
behavior and verification boundaries are described in [SETUP.md](../docs/SETUP.md),
[API-CONNECTIONS.md](../docs/API-CONNECTIONS.md) and [JEV-ROUTING.md](../docs/JEV-ROUTING.md).
The acceptance reports for [Stage 3](../docs/ACCEPTANCE-STAGE3-2026-10-28.md),
[Stage 4](../docs/ACCEPTANCE-STAGE4-2026-10-29.md) and
[Stage 5](../docs/NOTE-STAGE5-MODEL-SELECTION-2026-10-29.md) identify what was actually run.
