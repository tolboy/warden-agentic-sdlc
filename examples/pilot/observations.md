# Pilot observation sheet

One copy per trial, filled in while the trial runs rather than reconstructed afterwards.
Arm A is the operator's ordinary agent workflow; arm B is Warden. Both start from the same
base commit, and neither sees the other's solution. Write `unknown` where a value was not
measured: unknown is not zero.

| Field | A: ordinary workflow | B: Warden |
|---|---|---|
| trial id and date | | |
| target repository and base commit | | |
| Warden commit | — | |
| task sentence and independent acceptance command | | |
| model, effort and limits per role | | |
| order (which arm ran first) | | |
| setup_minutes | | |
| active_operator_minutes | | |
| tool_maintenance_minutes | | |
| elapsed_minutes, start to accepted or stopped | | |
| interventions: count, minutes and reason for each | | |
| outcome and stop_reason | | |
| accepted by the independent acceptance check | | |
| defects found after acceptance | | |
| calls (vendor attempts) | | |
| known_cost_usd and unpriced_calls | | |
| tokens by vendor, in that vendor's own semantics | | |
| notes | | |

Three rules from [the protocol](README.md#measure-the-process) that keep the comparison honest:

- Waiting at the human gate is not active time, and summed call durations are not elapsed time.
- A stopped or failed trial stays in the sample.
- A Warden patch needed in the middle of a trial ends that trial as a failure of that version.
