# Stage 2 note: API resilience and safe recovery

Status: implemented on `grok/api-resilience-stage2` (see "Implemented" below). Follows Stage 1 (`DESIGN-MODEL-ENTITIES-2026-10-28.md`) and section 2 of
[PLAN-MODEL-CONNECTIONS-AND-ROUTING-2026-10-28](PLAN-MODEL-CONNECTIONS-AND-ROUTING-2026-10-28.md).

## What exists today

- `ApiExecutor.send` maps every failure to one coarse code. A `TimeoutException` on the overall
  deadline and an `HttpTimeoutException` both become `role_api_timeout`. Any other
  `ExecutionException` becomes `role_api_network_error`, so connect refused, TLS failure, reset
  while reading the body and DNS failure are indistinguishable.
- HTTP statuses are already classified (`role_api_auth_invalid`, `role_api_rate_limited`,
  `role_api_provider_error`, ...) and `retry-after` is kept as sanitized evidence.
- There is no retry anywhere: one failed call fails the stage.
- Stage 1 made check receipts per connection + model (`CheckEvidence`), so a GLM 502 on
  GonkaRouter no longer paints the whole connection red. Stage 2 should keep that granularity.

## Proposed shape

1. **Failure phase, not text.** Add a `phase` to `CallFailure`: `connect`, `request_sent`,
   `headers`, `body`, `deadline`, `parse`, `identity` (model or nonce mismatch). Derive it from
   the exception type and from which await was pending; never store the exception message or
   the body. `role_api_network_error` keeps its code and gains the phase as evidence, so old
   reports still read.
2. **Sent vs. maybe-sent.** `connect` failures are known not to have reached the provider;
   everything from `request_sent` on has an unknown outcome and may have spent quota. The
   attempt log records this as `delivery: not_sent | unknown | answered`.
3. **Bounded retry policy** (per connection, default on for checks, off for writing roles):
   at most 2 extra attempts, only for `not_sent`, 429 with `retry-after` under a cap, and 502/503.
   Never retry after `auth_invalid`, `model_mismatch`, `output_truncated` or a cancel. Each
   attempt is logged with profile, stage, attempt number, status, durations and usage.
4. **Truncation is not auth.** A pong cut by the output limit reports
   `role_api_output_truncated` with `reasoning`/`max_tokens` evidence and leaves the key
   status untouched (Stage 1 key table already only flips on `role_api_auth_invalid`).
5. **Resume, don't redo.** After a review failure, keep the coding candidate and rerun only the
   unfinished stage when the contract hash and worktree state still match (`TaskLoop`,
   `RoleRunner`). A changed profile file already invalidates via `role_api_profile_changed`.
6. **Fallback only by explicit policy.** A role may name an ordered fallback list of
   connection + model; without it, a failure stops with the reason. Auth errors never open a
   browser or switch models silently.

## Tests to write first

Stub HTTP server cases: 429 with and without `retry-after`, 502, connect refused, reset after
headers, slow body past the deadline, oversized body, truncated pong, wrong model id, cancel.
Assert the phase, the delivery flag, the attempt count, that no secret text is stored, and
that a retried writing stage never applies file changes twice. Live per-service results stay
in a separate document, not in the automated suite.

## Implemented (2026-10-28)

- `ApiExecutor.CallFailure` carries `code`, `phase` and `delivery`; the phase comes from which step
  was pending (connect, headers, body) and the exception type. Messages and bodies are not stored.
- `ApiRetry` holds the policies (`none`, `not_sent_only`, `check`), the attempt log, correlation ids
  and cost settlement (unknown stays unknown). Retry policy is per caller, not per connection:
  roles and the relay use `not_sent_only`, checks use `check`.
- `ModelCheck`: reasoning-model ping floor, `role_api_output_truncated`, truncation advice before
  auth advice, attempts recorded.
- `TaskLoop.infrastructureReason` maps `role_api_*` transport codes so a continuation reuses the
  implementer stage.
- Follow-up, 2026-10-29: ordered per-role fallback is implemented as
  `roles.<role>.api_fallback_profiles`, an explicit subset of the existing roster. Only transient
  API network/timeout/rate-limit/provider failures may switch, under the existing
  `on_vendor_failure` mode and `max_per_stage` bound. Auth, identity, cancellation and
  configuration failures still stop. Verification, independence, process settlement and task
  budget remain required; a confirmed continuation retains the list and switch count.
  See [configuration](API-CONNECTIONS.md#ordered-role-fallback). This follow-up is tested offline;
  live ordered provider fallback remains unverified.
- Cancellation during retry backoff now keeps the earlier attempt as evidence and returns
  `role_api_interrupted` with phase `cancel`; it cannot be mistaken for a transient failure
  eligible for fallback. Failed API and OpenCode relay receipts stay on their vendor attempt
  after a successor succeeds.

Tests: `ApiResilienceTest` (suite `api-resilience`), `ModelCheckTest` (reasoning limit, truncation,
502 retry, auth once), `TaskLoopTest.apiReviewerResumeChecks`, and the new suites
`api-fallback`, `api-fallback-loop`, `shutdown-resume` plus fallback-policy/contract/projection
checks in `workflow` and `home-corpus`. Current verification counts are recorded in the
[Stage 3 follow-up](ACCEPTANCE-STAGE3-2026-10-28.md#follow-up--2026-10-29-windows-automated-coverage).
