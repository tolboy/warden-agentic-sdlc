# Stage 6 — catalogs, model versions and documentation — 2026-10-29

Integration closeout: 2026-10-30.

Stage 6 implements catalog lifecycle reporting, explicit Jev version compatibility
confirmation and documentation cleanup. Catalog discovery, access checks and native
admission remain separate. The main plan's six stages are complete within their dated
acceptance boundaries; provider availability and future versions remain observations
to verify when used.

## Catalog contract

A refresh must report what it actually refreshed: a safe non-inference metadata source
where supported, or a local CLI cache with its source date and update instructions.
Reading settings and refreshing model metadata must not send a coding/review prompt.
A panel timestamp alone does not establish that the CLI's underlying cache is current.

Catalog membership, account access, the requested model and native capability admission
are separate facts. A configured model missing from a new catalog remains configured,
with a diagnostic state and an explicit replacement path. Manual configuration stays
available. Refresh does not select the newest model, change roles or grant access.

Implemented sources are Codex `app-server` JSONL `model/list`, Grok `models`, and API
`GET /models`. The Codex handshake sends only `initialize`, `initialized` and paginated
`model/list`; it never starts a thread or turn. Recent Codex query time is labelled as
an observation, with upstream freshness unknown. Grok's refreshed cache carries the
source's `fetched_at`. Failed CLI requests can fall back to a local cache with a typed
diagnostic. Unsupported clients keep manual model entry and update instructions.

Warden expires observations after 24 hours as a conservative policy, not a vendor
guarantee. Missing, invalid or future source dates stay unknown. File modification time
is separate and never substitutes for `fetched_at`. Failed refreshes retain earlier
models only as stale data. Current dated metadata is required for explicit API effort;
ordinary manual models with no effort remain configurable after discovery failure.

## Jev version contract

A new requested Jev model or reported snapshot must be explicit. Decisions API
compatibility and exact returned identity must be checked before the new identity is
trusted. A catalog update alone cannot authorize a classifier migration. Unknown or
incompatible replies must keep normal configured routing or produce a clear refusal;
they cannot silently substitute an identity that was not approved.

Explicit semantic Jev versions and dated snapshots can be saved without modifying Java.
New identities require `profiles --verify NAME --confirm --reported-model EXACT` or the
equivalent settings action. One bounded nonce Choice validates the typed Decisions
schema and exact saved returned identity. Failure never adopts an observed identity.
Successful receipts bind the confirmed identity, profile and connection fingerprint;
changed configuration or an in-flight edit invalidates admission. Existing built-in
compatibility pairs keep their baseline behavior and every reply still checks identity.

The panel preserves unsupported saved effort values and requires explicit correction.
Classifier edits keep file revision protection; verification uses the panel's supplied
credential resolver. Merely saving a version, reading settings, or refreshing a catalog
cannot send the compatibility challenge or create its receipt.

## Documentation cleanup

- The original [API design](DESIGN-API-CONNECTIONS-2026-10-26.md) now separates current
  runtime guidance from the dated 2026-10-26 proposal and subsequent research updates.
  The original claims and design sequence are preserved as historical context.
- The legacy offline OpenCode file is labelled in place, and
  [API example guidance](../examples/api/README.md) distinguishes it from the verified
  native implementer. The legacy file's refusal is not a statement about the native adapter.
- [Examples guidance](../examples/README.md) marks old eval profiles and dated model/flag
  choices as non-default historical experiments. The old `examples/evals/four-vendor`
  directory is absent from the current tree; no profiles were recreated. Its Git snapshot
  was inspected to verify that the old DeepSeek-labelled profile used Codex/OpenRouter,
  not a distinct DeepSeek CLI service.
- [SMOKE.md](SMOKE.md) keeps its dated transcripts intact and links current setup,
  adapter and acceptance guidance. Both top-level READMEs now mention verified native
  OpenCode without implying that the offline template grants admission.

Current operational sources are [API-CONNECTIONS.md](API-CONNECTIONS.md),
[JEV-ROUTING.md](JEV-ROUTING.md), [MODEL-CHECKS.md](MODEL-CHECKS.md) and the
[main implementation plan](PLAN-MODEL-CONNECTIONS-AND-ROUTING-2026-10-28.md).
The completed live evidence from [Stage 3](ACCEPTANCE-STAGE3-2026-10-28.md),
[Stage 4](ACCEPTANCE-STAGE4-2026-10-29.md) and
[Stage 5](NOTE-STAGE5-MODEL-SELECTION-2026-10-29.md) remains dated evidence rather than a
promise of present gateway availability or current prices.

## Live observations

The production service ran in an isolated `out/stage6-live/home` on 2026-10-29 at
19:17 UTC, using installed CLI clients and the existing OpenRouter credential reference.
No credential values were copied into files. Local evidence is
`out/stage6-live/result.json` (ignored by Git).

| Operation | Observed result | Boundary |
|---|---|---|
| Codex metadata | 8 models; `cli_metadata`; freshness `unknown` | CLI list query does not establish upstream refresh or key access |
| Grok metadata | 4 models; `cli_metadata`; freshness `current` | Current cache observation, no inference/access check |
| OpenRouter metadata | 420 models; `remote_metadata`; freshness `current` | Membership only |
| Jev without confirmation | `classifier_confirmation_required`; no vendor turn | No inference |
| Jev with explicit exact pin | `classifier_compatibility_verified`; reported `typesafe/jev-1.14-20261014` | Existing snapshot, not proof that a future version is deployed |

The Jev response reported 392 input tokens, 92 output tokens and USD 0.000016464.
All YAML configuration hashes were unchanged after refresh and after verification.
The only inference in this check was the explicitly confirmed Jev challenge. This
did not run another coding loop or change the pending human decisions from earlier
acceptance runs.

## Deterministic validation

Protocol fixtures cover stale/missing/future cache timestamps, failed catalog refresh,
removed and never-listed manual models, unchanged profiles/receipts/roles, no implicit
CLI query on settings reads, HTTP refusal, and manual configuration when discovery is
unavailable. CLI fixtures validate metadata-only pagination, refusal, deadlines,
cancellation and cleanup. Jev fixtures exercise fictional future semantic versions
with compatible and incompatible schemas, exact pin mismatch, explicit confirmation,
configuration edits, receipt invalidation and authenticated settings verification.
The fictional versions are test inputs, not provider catalog claims.

The final CLI reader enforces a 1 MiB frame limit and 4 MiB total output limit before
JSON parsing, including an unterminated oversized frame. A watcher retains observed
descendant handles during the request, so cleanup can stop a helper after its parent
exits. Dedicated fixtures verify both cases. Fresh catalog fixtures use relative dates
so the freshness policy remains testable across calendar changes.

After these fixes, a metadata-only live repeat at 2026-10-30 06:33 UTC again returned
8 Codex models with unknown upstream freshness and 4 Grok models with current cache
freshness. Evidence: `out/stage6-live/final-metadata.json`. No additional Jev inference
was sent. The real CLI entry point without confirmation also refused with
`classifier_confirmation_required` and `vendor_turn_started: false`.

Final validation on 2026-10-30:

- `build.cmd`: passed, Java release 21; existing unchecked/internal JDK API warnings.
- Catalog lifecycle, Jev compatibility/routing, model selection and routing loops,
  model settings, catalog UI, vendor settings and effort capabilities: **462 passed,
  0 failed**.
- Config, model checks, native profile verification, API HTTP, service bindings and
  migration, role roster/resolution, connection UI, API effort transport and verified
  OpenCode: **660 passed, 0 failed**.
- Total for those disjoint final groups: **1,122 passed, 0 failed**. Earlier repeated
  runs are not added to that total.
- Rendered settings JavaScript: `node --check` passed. Local Markdown file links:
  107 checked, none missing. `git diff --check` passed.

The full repository test suite was not repeated for this stage. Future Jev versions
were verified only through protocol fixtures; live evidence uses the existing pinned
snapshot. The metadata repeat checks the installed CLI protocol after the final
lifecycle changes, without repeating coding work or spending another Jev call.
