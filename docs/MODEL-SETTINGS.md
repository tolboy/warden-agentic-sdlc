# Vendor and role settings

`warden settings` uses the same user configuration as the CLI. The main panel has vendor
cards and ordered role participants; profiles remain internal launch, permission and tool
contracts. There is no second settings database.

- **Ping:** one successful representative per CLI authorization/channel group. Diagnostics
  may try a second distinct configured model if the first fails. This intentionally assumes
  the other CLI models share authentication; it does not attest access to each model.
  API connections check each configured model; a connection without one needs a model first.
- **Refresh models:** authenticated bounded `/models` for API. Codex and Grok use the CLI's
  local model cache, with source timestamp. Refreshing this panel does not refresh the
  vendor CLI's cache. Unsupported/failed discovery shows a documentation link and manual
  entry. Last-good cached lists remain visibly stale after a failed refresh.
- **Role composition:** select vendor/channel and model, drag cards or use arrows, choose
  first-available/rotation, and save. Changing a model preserves tool grants and read-only
  settings, invalidates prior admission and runs preparation in that save action. An
  unsuccessful preparation leaves a saved, visibly pending role; launch remains blocked.
  Reordering alone does not re-probe admitted channels. Existing CLI profiles use their own
  authentication; API coding still requires a real OpenCode tool probe.
- **Jev:** enabling automatic effort hides manual effort selectors. The fallback remains the
  saved effort; explicit task/run overrides win. Questions and criteria are editable as YAML
  in the panel and `~/.warden/jev-routing-policy.yaml`. Numeric decision probabilities come
  from Jev. The application threshold remains `routing.min_confidence` in settings.yaml.

All inference and catalog access is explicit; opening settings does neither. The role
preflight uses the same representative grouping. Settings POSTs require the local page token,
validate revisions, and never return secret values. Configuration rows and labels use text
nodes. If a policy file has malformed YAML, the panel remains open for repair.

The stage table shows the resolved default policy; task-level overrides and Jev choices are
recorded in actual run evidence. A vendor pong and a model catalog are not coding admission.

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
