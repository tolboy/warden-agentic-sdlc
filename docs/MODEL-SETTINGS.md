# Vendor and role settings

`warden settings` uses the same user configuration as the CLI. The main panel has vendor
cards and ordered role participants. CLI cards are grouped by the service a profile is bound
to (`service:`; OpenAI, xAI, Google Antigravity), across profiles and direct/Orca launch
methods, never by the model developer. API cards
name the API service and identify the configured connection separately. Neither CLI
profile names nor Orca create additional vendor cards. Profiles remain internal launch, permission and tool
contracts. There is no second settings database.

- **Ping:** one successful representative for the displayed CLI service, preferring a direct
  CLI over an orchestration adapter. This proves a response from that service, not that
  every account/launch configuration works. Diagnostics
  may try a second distinct configured model if the first fails. This intentionally assumes
  the other CLI models share authentication; it does not attest access to each model.
  API connections check each configured model; a connection without one needs a model first.
- **Refresh models:** authenticated bounded `/models` for API. Codex and Grok use the CLI's
  local model cache, with source timestamp. Refreshing this panel does not refresh the
  vendor CLI's cache. Unsupported/failed discovery shows a documentation link and manual
  entry. Last-good cached lists remain visibly stale after a failed refresh.
- **Role composition:** select vendor/channel and model, drag cards or use arrows, choose
  first-available/rotation. Changes save automatically through one serialized local write
  queue, including role order, model, effort, launch method and strategy. Vendor choice is distinct from the optional
  expandable launch-method selector; Orca appears only there. Changing a model preserves tool grants and read-only
  settings, invalidates prior admission and leaves a saved, visibly pending role. Preparation is a separate explicit action
  that may call a model; autosaving never runs a probe. Launch remains blocked until admission.
  Reordering alone does not re-probe admitted channels. Existing CLI profiles use their own
  authentication; API coding still requires a real OpenCode tool probe.
- **Jev:** enabling automatic effort hides manual effort selectors. The fallback remains the
  saved effort; explicit task/run overrides win. Questions and criteria are editable as YAML
  in the panel and `~/.warden/jev-routing-policy.yaml`. Numeric decision probabilities come
  from Jev. The application threshold remains `routing.min_confidence` in settings.yaml.

All inference and catalog access is explicit; opening settings does neither. Role preflight separately checks the selected authorization/channel groups, including
Orca when that is the requested launch method. Settings POSTs require the local page token,
validate revisions, and never return secret values. Configuration rows and labels use text
nodes. If a policy file has malformed YAML, the panel remains open for repair.

The stage table shows the resolved default policy; task-level overrides and Jev choices are
recorded in actual run evidence. A vendor pong and a model catalog are not coding admission.

## Services, accounts and the binding migration

The design is in `DESIGN-MODEL-ENTITIES-2026-10-28.md`. In short:

- A CLI profile names `service:` (whose subscription answers) and optionally `account:`
  (which login, when one service has several). The model developer (`vendor:` /
  `model_vendor:`) is used only for review independence. API profiles are shown inside
  their connection. A CLI profile without a binding Warden can prove is shown on the
  "needs a binding" card with the reason; Warden does not guess one.
- Two accounts on one service share a card but keep separate checks and authentication.
- Card status comes from the per-model receipts in `checks/states.json`: `answered`,
  `partial` (some models failed; the card lists only those) or `failed`. The API-key
  table reports `auth_evidence` from the same receipts, so a key that a model answered
  through is not shown as unchecked.
- Migration of an existing configuration:

  ```
  warden profiles migrate-services                      # preview; writes nothing
  warden profiles migrate-services --apply --map NAME=SERVICE[:ACCOUNT] ...
  warden profiles migrate-services --rollback ID        # restores the backed-up bytes
  ```

  Apply takes a lock, backs the touched files up under
  `backups/service-bindings-<stamp>/` with a manifest, adds `service:`/`account:` lines
  (comments and line endings kept), and moves stale `vendors/cli-*` caches of services that
  no longer exist into the backup. Ambiguous profiles stay byte-identical until mapped.
  A re-run is a no-op; rollback refuses if a file changed after the migration.
- Adding the binding changes the profile file, so its earlier ping receipts no longer match
  and the cards show it unchecked until the next Ping. `verified_on` is kept. Builds older
  than this change reject the new keys.
- Effort levels come from one list (`EffortCapabilities`): the model catalog when it is
  loaded, otherwise the built-in table, in the order none..ultra. The UI offers it, save
  refuses anything outside it (`effort_unsupported`), and automatic routing chooses only
  among the levels its policy names (low..max), so `ultra` can be chosen by hand but is
  never picked automatically.
- Autosave conflicts: when another tab saved first, the page reloads the server state and
  re-applies only the fields this tab changed. A field changed in both tabs keeps the
  other tab's value and is shown as a notice; it is never overwritten silently.
  `node src/test/js/model-settings-page.mjs` checks the merge and card rendering.

## Validation on 2026-10-28

The settings screen was exercised in Orca with actual user configuration: vendor cards and
model menus loaded, arrow ordering retained the unsaved strategy, automatic effort hid manual
selectors, multiline Jev policy saved successfully, and each stage candidate had six cells
matching its six headers. Browser console was clear. An erroneous YAML remains editable.

Live checks covered all configured vendor/channel groups. GonkaRouter DeepSeek answered once
but later failed a fresh nonce challenge; its native text artifact probe passed. GonkaRouter
GLM had a provider HTTP502 during native verification. A full all-vendor loop correctly stopped
before writing when GonkaRouter failed readiness. CLI and API catalogs are therefore not an
assurance that every model will answer every time. The optional Orca Grok channel also failed
its diagnostic; direct Grok remained usable.

A separate eligible chain kept the same isolated configuration home and omitted only the
GonkaRouter stage. Real Grok implementation (low), baseline, acceptance gates, Codex review
(medium), DeepSeek CLI review and AGY GPT-OSS review passed. JoinGonka's native artifact and
fresh ping had passed, but its final repository review ended with `role_api_network_error`.
The loop therefore did not report success or approve/land the candidate. OpenRouter Jev
classified both live attempts and recorded its probability distributions and chosen effort.

Verification: 853 regression checks passed, plus a focused panel/connection run of 373 checks
(overlapping suites, not an additive unique total). These include local authorization/token
and body-size guards, failed policy repair, stale edits, role composition, vendor grouping,
optional billing and malformed API pong diagnostics.

CLI/API labels describe only the connection mechanism; billing/subscription status is unknown
and is never inferred from the executable or model developer. API credentials and new-channel
forms still use deliberate submission; ordinary role, Jev and readiness selectors autosave.
Autosave reports saved/failed status, uses revision checks, serializes edits across roles, and
keeps pending admission separate from successful local persistence.
