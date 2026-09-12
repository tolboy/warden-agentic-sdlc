# First external pilot

This opt-in template tests whether Warden reduces active operator time on a real,
bounded, **non-visual** task. It is not a new global default or an implementation of
the full P3/P4 roadmap. No target project or paid run is selected by these files.

## Prepare once

1. Choose a real bugfix or small feature with a working baseline build/test and an
   independently observable acceptance check. Use an isolated checkout of the target
   repository. Record its base commit. Do not choose a UI task and remove its visual
   checks merely to fit this template.
2. Choose a separate, durable pilot configuration directory. Set `WARDEN_CONFIG_HOME`
   to that directory **before** `warden setup`; the normal home and policy stay unchanged.
   Keep this home after deleting the target worktree: its `ledger/` is the measurement corpus.
3. Copy two already validated profiles and their required resources into that home.
   Rename their files and `profile` fields to `pilot-implement` and `pilot-review`.
   Writer: `role: implementer`, writable. Reviewer: `role: reviewer`, read-only,
   different vendor. Pin the actual model/effort and set each profile's existing
   `limits.wall_clock_minutes: 8` (or another explicitly recorded limit).
   Keep provider-specific flags and prompt delivery from the validated profiles;
   this template does not infer command-line compatibility from a model name.
4. Install this `policy.yaml` in the pilot home. It has a singleton writer roster,
   independent review even at low risk, `failover.on_quota_exhausted: stop`, and
   `budget.repair_reserve: full`. No profile rotation or automatic writer replacement.
5. Adapt `task.yaml` into the target's `.warden/tasks/pilot-task.yaml`. Replace the
   placeholder goal. Bind `pilot-scope` and `pilot-acceptance` to real named entries
   in that project's `project.yaml`, or change those selectors to its existing names.
   Keep `max_fix_attempts: 1`. Set the known-spend threshold consciously. Review the
   permissions and actual acceptance commands; `git diff --check` alone is not a
   functional acceptance test. Commit the target's reviewed configuration as baseline.

From the target checkout, with the pilot home selected:

```text
warden validate pilot-task
warden run pilot-task --run-id pilot-preview --dry-run
```

Inspect `budget_plan` and the resolved roster before spending. This exact three-stage
workflow needs two calls for implement/review and four with a review repair/recheck.
Gates consume time but no vendor calls. The four-call cap is not suitable for adding
planner, visual roles, or another reviewer without recalculating the plan.
`timeout_minutes` in the task bounds a gate, not the workflow. The monetary threshold
covers known spend only; unpriced calls and the last call can exceed it. Per-call
timeouts do not provide an overall deadline.

## Freeze and execute later

Record Warden commit, target base commit, task/policy/profile/prompt/schema hashes,
requested model/effort/limits, acceptance and the observation protocol before launch.
For this experiment use the reviewed contract directly (`warden run`); do not buy a
planner call. After these checks, the live command is:

```text
warden run pilot-task --run-id pilot-01
```

This is an **observed single-run experiment**. One ordinary repair of the target code
is allowed. Any stop is an experimental outcome: do not use `--continue`, retry, a new
run ID, a new writer or a larger budget to make the same trial look successful.
These are experiment rules, not newly implemented CLI prohibitions. P3's durable
repair counter across continuations and P4's execution deadline remain deferred.
If the operator must stop a slow run, record the intervention and settle the worker
before starting anything else. A required Warden patch means this version failed the
trial; retain the evidence and count troubleshooting time before planning another trial.

Inspect the finished candidate against the same acceptance used for the baseline.
Human accept remains bound to that candidate; a manual code change requires fresh
verification. Accept does not merge. Use `warden report pilot-01 --text`,
`warden ledger` and `warden ledger --global` to preserve the outcome. For imports,
check both exit status and structured `ok`/`complete`, and keep sources while the
global report says `incomplete`. A successful explicit re-import of identical source
content can reconcile failed delivery; changing the source cannot erase its old loss.

## Measure the process

Compare the same task from the same base with the user's ordinary agent workflow,
without passing the first solution to the second. Match models/effort/acceptance where
possible, label differences, and alternate order on subsequent tasks to reduce the
effect of the operator learning the answer. The first task establishes feasibility;
another 3–5 tasks can provide an initial signal, not statistical proof.

Record these fields manually alongside automatic evidence:

| Field | Meaning |
|---|---|
| setup_minutes | Profile/YAML setup and acceptance preparation specific to this process |
| active_operator_minutes | Setup, answers, supervision, diagnosis, fixes and final acceptance |
| elapsed_minutes | End-to-end time to accepted outcome or stop |
| interventions | Count, duration and reason for each return to the operator |
| accepted | Independent acceptance passed, not just a role's pass verdict |
| defects | Confirmed issues, later regressions and manual repairs |
| known_cost / unpriced_calls | Report separately; unknown is not zero |
| calls / tokens | Actual attempts; keep vendor token semantics separate |
| tool_maintenance_minutes | Work on Warden itself; include it in active time |
| outcome / stop_reason | Include every trial, including failed and stopped runs |

Human gate wait is not active operator time. Summed call durations are not wall-clock
time. Set a decision threshold in advance: for example, roughly 30% less active time
at comparable acceptance and an acceptable cost/delay. If setup and maintenance consume
the savings across several tasks, pause feature expansion and reconsider the product.
