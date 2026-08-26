# Architecture status

The source of truth is Warden, not a target-project branch.

| Architecture block | Owner | Current state |
|---|---|---|
| Task spec and linter | Warden config | Implemented: intent, non-goals, risk, scope, authority, acceptance, visual scenarios, budgets and limits |
| Policy/run controller | Warden | Machine-gate slice implemented; bounded agent fix/review loop pending |
| Role/profile resolver | Warden user policy | Implemented core selection: verification, availability, rotation, independent vendor |
| Direct CLI adapter | Warden SPI | Interface defined; production adapter pending verified vendor probes |
| Orca adapter | Warden SPI + Orca | Pending live lifecycle implementation; no fake completion claim |
| Workspace/tool policy | Git + TaskSpec authority | Scope and local write/network/land declarations implemented; tool allowlists pending |
| Evidence ledger | Warden | Machine reports, append-only JSONL and `warden ledger` aggregation implemented; role usage/cost/findings pending |
| Conductor | Outer controller | Machine-only workflow implemented; full loop intentionally absent |
| Visual QA | Project scenarios + adapter | Contract implemented; deterministic baseline/pixel-diff/triage pending |
| Human gate / LAND | Conductor + human | Fail-closed gate implemented; Warden never lands |

`warden init` now creates a conservative, non-overwriting starter contract for npm, Gradle
wrapper, Maven, Cargo or Make projects. Its inferred commands are a review starting point,
not trusted policy.

State ownership is intentionally single-source: Warden will own the bounded role/fix loop;
Conductor owns outer kill limits and the human gate; Orca owns worktree and worker lifecycle.
Duplicating the role DAG in Conductor would create two competing resume/retry state machines.
