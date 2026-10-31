# Stage 4 effort routing evaluation — 2026-10-29

The default Jev rubric was exercised with six real classification requests: two
simple edits, two complex tasks, and two ambiguous investigations. No implementer,
reviewer or planner was dispatched, and no personal settings were changed.

The test fixes the candidate model to `gpt-6.1-sol`, configured effort `medium`, and
the supported list `[low, medium, high, xhigh, max]`. The classifier selects effort;
this does not test Jev model selection. Each saved distribution is replayed through
the production `TaskRouting.chooseEffort` and risk-floor functions at three thresholds.
The rubric is the repository default, not a potentially customized personal rubric.

## Live results

| Task | Group | Threshold 0.5 | Threshold 0.6 | Threshold 0.75 |
| --- | --- | --- | --- | --- |
| README spelling correction | Simple | low | low | low |
| One UI label and its test | Simple | low | low | low |
| Crash/partition recovery and fencing | Complex, high risk | xhigh | xhigh | xhigh |
| Concurrent financial-ledger migration | Complex, high risk | max | xhigh | xhigh |
| “Make the application faster”, no profile or target | Ambiguous | medium | medium | medium |
| Intermittent unspecified failure, evidence missing | Ambiguous | medium | medium | medium |

All six requests returned valid typed answers with the expected classifier identity.
Provider-reported totals: 5,494 input tokens, 1,192 output tokens and **$0.000230748**.
No per-task outcome, elapsed coding time or code-quality measurement was collected.
The four clear tasks fell inside their broad, manually reviewed expected bands.
The ambiguous tasks have no correct effort label assigned; their results are not
counted as classification accuracy.

The rubric distinguishes the two clear ends of this small sample. Increasing the
threshold from 0.5 to 0.6 reduces one exceptional `max` suggestion to `xhigh`; increasing
it further to 0.75 changes none of these six decisions. This supports retaining the
current configurable **0.6** default for now. It does not establish an optimal threshold
or calibrated probability of correctness. A larger labelled set and measured task
outcomes would be needed for either claim. In particular, ambiguity in a task description
does not necessarily produce a diffuse Jev distribution: both investigations received
concentrated `medium` advice. Effort selection does not authorize an unspecified change.

## Deterministic selection checks

A separate file contains explicitly invented distributions. It tests the selector,
not the live classifier. The two simple distributions yield `low` and `medium`; the
complex distributions yield `xhigh` and `high` at all three thresholds. A uniform
ambiguous distribution keeps configured effort at every threshold. A borderline
ambiguous distribution chooses `high` at 0.5 and 0.6, and keeps configured effort at
0.75. The distinction between adjacent ambiguity and broadly spread advice is therefore
observable without another model call.

The production selector now handles a single supported level instead of rejecting
it because there is no adjacent pair. Empty lists, duplicate labels, unsupported
probability keys, missing/non-finite/out-of-range probabilities, invalid thresholds
and distributions outside the existing normalization tolerance produce no override.
The risk floor never invents a level absent from the model's capability list.
`decision-routing` passed **87 checks, 0 failed** after these changes. Existing
`task-routing-loop` coverage checks that explicit effort wins over Jev, readiness
sees the effective setting, and mandatory review cannot be removed by role advice.

## Preparatory planning decision

Preparatory planning keeps the operator's configured effort. Its calls happen before
execution-stage task routing, and can change the contract that execution routing reads.
Applying a later classifier result retroactively would misstate what the planner
received. Moving classification earlier or introducing a separate planner classifier
would add a different lifecycle and additional paid calls; that is not part of this
stage. Jev's planning-role recommendation remains advisory. Execution/fix-stage routing
continues to preserve explicit task, run and handover settings, and retains configured
effort when classification is unavailable or insufficiently concentrated.

## Reproduction and receipts

From the repository root after `cmd /c build.cmd`:

```powershell
java -cp out/classes tools/agent/JevEffortEvaluation.java --synthetic tools/agent/fixtures/jev-effort-distributions.json out/stage4-calibration/synthetic.json
java -cp out/classes tools/agent/JevEffortEvaluation.java --live tools/agent/fixtures/jev-effort-cases.json out/stage4-calibration/live.json
java -cp out/test-classes dev.warden.testing.TestMain decision-routing
```

`--synthetic` makes no network request. `--live` explicitly uses the configured Jev
profile and is bounded to at most 12 cases (the supplied set has six). The results
include the dataset, policy hash, typed answers, provider usage and threshold decisions.
The default rubric hash in this run was
`1631e7b360188fea69ebb8c35da40866f18ca150d146471afce0240d47d08ede`.
Receipts from this execution are in `out/stage4-calibration/live.json` and
`out/stage4-calibration/synthetic.json`; `out/` is local evidence and is not committed.
