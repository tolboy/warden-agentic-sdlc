# Direct CLI activity projection

This extends the partial desktop implementation in
[the contract report](ORCA-DESKTOP-CONTRACT-2026-10-21.md). It does not complete the
[original desktop acceptance](prompts/NEXT-SESSION-ORCA-DESKTOP-2026-10-21.md).

## Implemented behavior

The selected role publishes its stage, role, resolved profile, vendor, requested
model and effort, attempt number and evidence name before dispatch. The task loop,
initial preparation and a replan continuation pass this projection to the existing
workspace. Other preparation callers retain their existing behavior.

The direct adapter reads its existing raw stdout spool incrementally on a separate
virtual thread. It reports output byte counts, published-message presence, known
tool categories, tool start/completion, numeric exit codes and provider-error
presence. Codex item events, assistant tool-use envelopes and top-level tool events
have conservative recognizers. Unknown formats and plain text remain byte progress.
Pretty-printed objects and records without a newline do not receive live semantic
interpretation. The authoritative final protocol parser remains unchanged.

Provider text, commands and arguments, paths, tool results, stderr text, auth
output and reasoning are not copied to the projection. Tool names map to a fixed
vocabulary. This deliberately provides less content than the original requested
live transcript; a general-purpose secret-safe text renderer is not implemented.
Requested model/effort remain explicitly requested; no reported model, token count
or price is invented. Projection events never enter vendor-call accounting.

The reader polls every 200 ms. A record retains at most 64 KiB, one poll reads at
most 256 KiB, and a backlog skips to a bounded tail while discarding a partial
record. Oversized/deep/malformed objects are ignored. The reader never reopens and
rescans the complete growing log. A blocked or failing consumer can lose events;
it cannot block child pipes, change process settlement, delay return of a direct
result, or dispatch another process. Closing the reader does not cancel execution.

The automatic Orca projection keeps at most 24 sanitized events in `desktop.json`.
Ordinary activity updates coalesce to at most one per second, with immediate
dispatch/return checkpoints. A pending tool event is not replaced by generic byte
progress and is flushed even if the CLI becomes silent. The ring and native card
are lossy projections, not delivery receipts or a replay log. Late events from a
finished/earlier attempt and events after human wait are discarded. A new stage's
heartbeat cannot inherit the preceding vendor's label.

Discovery RPCs retain one-second budgets. Card mutations have a two-second budget:
a real probe under local load exposed a refusal with the previous one-second
limit. A failed mutation still opens the circuit; its receipt saves only numeric
exit/duration/timeout metadata or a fixed transport-exception code. No raw error or
environment output is published.

## Native probe and its limits

Two ordinary `warden run` invocations without `--watch` used the existing disposable
desktop fixture and an isolated, synthetic profile. Each synthetic adapter ran a
real `java -version` helper and emitted vendor-shaped tool events. It then waited
for an explicit local release so the projection could be inspected before the
adapter completed. No paid vendor or real provider protocol was exercised.

For both probes with the two-second card budget, `task-run.json` did not yet exist when inspection
captured `tool_finished`, category `shell`, exit code zero, requested model and
attempt identity in the desktop receipt and native workspace metadata. The adapter
was ready and its release file was still absent. Each run launched one adapter,
completed machine gates and reached `human_gate`. Decisions were then explicitly
closed. Terminal inventory remained one identical operator shell before and after.

Native screenshots and accessibility snapshots were captured. They expose an
important visual boundary: this Orca card renders the comment as **Workspace notes**
metadata rather than an inline event transcript. The screenshot therefore does
not prove an automatically visible live event. The original requirement for one
clear live overview is still blocked by the observer ownership contract. No tabs,
Dispatches, tasks or chats were created for this projection.

Earlier exploratory probes were kept: two invalid-scope refusals, and two runs
whose fixture incorrectly put prose into executable acceptance commands. One of
the latter also recorded a projection refusal with the earlier RPC budget. They
are not counted as successful acceptance. Their decisions were closed, their
evidence was preserved, and the final fixture used executable acceptance commands.

The sanitized result is recorded in
[the activity evidence summary](evidence/orca-live-activity-2026-10-21.json).

## Verification boundary

Offline child-process checks cover delivery before completion, blocked/throwing
UI consumers, unchanged raw bytes and authoritative cost/artifact, exactly one
process, split/oversized/deep/nested records, private text omission, no replay and
observer close. Workspace checks cover burst coalescing, bounded retention, pending
flush, requested versus reported metadata, late attempts and human wait. Existing
task-loop and preparation fixtures check the actual dispatch-to-workspace wiring.

This is not a pass for real Grok/Codex/AGY/DeepSeek display, a full visible failover,
gate import/continuation, controller crash/reconnect or observer takeover/release.
Android and current paid-model availability remain unverified. The upstream
ownership proposal and the restriction against pretending an external process is
a supervised worker remain unchanged.
