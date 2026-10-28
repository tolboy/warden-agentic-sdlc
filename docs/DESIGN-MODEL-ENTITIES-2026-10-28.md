# Separated model entities (Stage 1 of the 2026-10-28 plan)

Status: implemented on `grok/model-entities-stage1`. Scope: P0 of
[PLAN-MODEL-CONNECTIONS-AND-ROUTING-2026-10-28](PLAN-MODEL-CONNECTIONS-AND-ROUTING-2026-10-28.md).

## The defect

`VendorSettingsService.cliProvider` named a CLI profile's *service* after its *model developer*
(`Profile.modelVendor()`). A Codex CLI started with `--profile deepseek` and `vendor: deepseek`
therefore became a "DeepSeek CLI vendor": a service that does not exist, with invented billing,
catalog and check semantics. The same rule turned `agy` with `vendor: codex` into an OpenAI-ish
label and would put any router-backed CLI model under its developer.

## Entities

| Entity | Meaning | Where it lives |
|---|---|---|
| **Service** | Who authenticates and bills the call: `openai`, `x-ai`, `anthropic`, `google`, `antigravity`, or an API provider (`openrouter`, `gonkarouter`, ...). | CLI: `service:` in the profile. API/OpenCode: the connection's `provider`. |
| **Account** | One authentication inside a service. Two accounts never share a check or a readiness representative. | CLI: `account:` in the profile (default `default`). API: the connection id. |
| **Model** | Model id plus its **developer** (`vendor` / `model_vendor`). The developer is used only for review independence and receipts, never to name a service. | Unchanged. |
| **Executor** | The client/harness that runs the role: Codex, Grok, Claude, Antigravity CLI, OpenCode, Orca, HTTP. A launch method, never a service. | `command`, `runner`, `harness`. |
| **Role composition** | Ordered profiles per role plus strategy. Profiles stay the launch, permission and tool contract. | `policy.yaml` routes, unchanged. |

Old to new: `Profile.vendor` keeps its old meaning (model developer). `cli-provider-<x>` card ids
now use the bound service instead of the developer. `VendorSettingsService.vendorId` (technical
readiness group) now hashes service + account + command + runner + auth selectors.

## Binding resolution (`ServiceBinding.of(profile)`)

1. API runner or OpenCode harness: service = connection provider, account = connection id (`source: connection`).
2. `runner: local`: an OpenAI-compatible endpoint, shown as `local` (`source: endpoint`).
3. CLI profile with `service:` (and optional `account:`): `source: explicit`.
4. Legacy CLI profile without `service:` is inferred **only** when all hold (`source: inferred`):
   - the executable is a known single-service client (`codex`->openai, `grok`->x-ai, `claude`->anthropic,
     `gemini`->google) and the model developer is that service, or the executable is `agy`
     (Antigravity serves several developers' models under one login);
   - argv carries no provider/account selector (`--profile`, Codex `-p`, Codex `-c model_provider=...`
     or `...base_url...`, `--config`, `--api-key`, `--base-url`, `--api-base`, `--endpoint`,
     `--provider`, `--oss`, `--local-provider`, `--auth-file`, `--credential`, `--auth-mode`,
     `--organization`, `--project`).
5. Everything else is `needs_mapping`: no service, no billing, no catalog, no vendor ping. When the
   executable's own service is plausible (selector present but developer matches) it is offered as a
   *suggestion* that is applied only by an explicit mapping. A developer that differs from a
   single-service client (the DeepSeek-through-Codex case) gets no suggestion at all.

Warden never reads another program's configuration or tokens to decide this.

## Display

One card per service. CLI cards list their accounts; each account is checked separately (one
representative per account, at most one alternative model). API cards stay one per connection.
`cli-unmapped` is a separate card titled as needing a mapping. Its Ping/refresh are refused with
`service_mapping_required`; launch readiness is unaffected because it uses technical groups.

## Migration (`warden profiles migrate-services`)

* Default is a preview: per-profile current binding, proposed action, and stale vendor caches whose
  id no longer exists (for example `vendors/cli-provider-deepseek-*.json`). Nothing is written.
* `--apply` writes `service:`/`account:` lines into inferred profiles and into profiles named by
  `--map NAME=SERVICE[:ACCOUNT]`. Ambiguous profiles without a map stay `needs_mapping`.
  Every changed file and every moved cache is copied first into `backups/service-bindings-<stamp>/`
  with a manifest recording each file's hash before and after. New text must parse before it is
  written; writes hold the settings lock.
* Re-running after a successful apply changes nothing and creates no backup.
* `--rollback <backup-id>` restores the files only if each still has the hash the migration wrote;
  any later edit refuses the whole rollback rather than overwriting it.
* Adding `service`/`account` changes no argv, model, permissions or tools, so `verification.verified_on`
  is kept. Changing a model still drops admission (unchanged `RoleRosterEditor` rule).
* Older Warden builds reject the new keys (strict profile parser); rollback is the way back.

## Not changed

Tool restrictions, read-only/write permission, review independence (`independenceVendor()` still
uses the model developer), historical reports (they record profile names and developers, which keep
their meaning), and API connection semantics.
