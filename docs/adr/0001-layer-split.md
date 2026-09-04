# ADR 0001 — Warden is a policy/evidence engine, not a workflow runner

Status: accepted

Warden stays a specialised engine for agentic development. It is not a generic
graph runtime, and it is not a second Orca.

```
Warden      domain workflow, gates, safety, evidence, metrics, decisions
Orca        worktrees, agent hosting, dashboard/mobile, terminal and human interaction
Conductor   optional external workflow adapter; not part of the primary path
```

## Why this cut exists

Treating Warden as “another generic workflow runner” makes it a bicycle.
Treating it as the thing that decides *whether* an agent may continue — real
project gates, git isolation, vendor independence, quota, structured artifacts,
a bounded fix loop, and a human decision about a specific tree — gives it a
job nothing else in this stack owns.

Orca is the remote cockpit and, where it can, the host of processes. Conductor
may wrap a Warden command with an outer timeout and an external human gate.
Neither is the role DAG.

## Warden does not grow

- a generic DAG engine, arbitrary node types, or a distributed scheduler
- a web or mobile UI of its own
- a universal checkpoint framework
- hidden chain-of-thought collection
- automatic merge

Conductor is not deleted in this cut. Its validation tests stay. It is not
developed further here. If a later CI or fleet launch needs an external human
gate, it remains a useful adapter — not a second loop.

## What this cut does prove

A project may name a `baseline_checks` set. Those commands run in the worktree
before the first vendor. Red is `baseline_failed` and zero vendor calls, so an
agent is never asked to “fix” a breakage it did not cause. The same commands
are the floor of the later acceptance gate, so an agent cannot break a test
that was green at dispatch and still pass a narrower task check. Existing
projects that omit the key keep their previous behaviour.

After any agent, Warden runs the declared commands itself. The model does not
decide whether a gate passed: exit code and timeout do. A red command is handed
back to the same implementer, bounded, and run again.

`runner: orca` is the place a role is hosted as an Orca worker. A live reviewer
cycle is recorded in `docs/SMOKE.md`; Direct CLI remains the conservative
default while projects opt into Orca hosting. The adapter fails closed: completion is only
`worker_done` / dispatch settlement, an unfenced worker is
`role_orca_lifecycle_unaccounted`, and a native agent waiting for a person is
`role_human_input_required` — never a fix round.

The canonical history of a run is `.warden/runs`. Orca may show a card, a
terminal and an agent row; it is not the audit log. Ledger splits count each
actual vendor dispatch, including a failover attempt, not only the surviving
`role_run`.

Orca 1.4.194 already has the two commands a phone-sized decision would need:

```
orca orchestration gate-create --task <id> --question <text> [--options <json>]
orca orchestration gate-resolve --id <gate_id> --resolution <text>
```

and `worker-show` already publishes three-valued `observation.agentWait`
(proven wait / explicit null / field absent). Warden consumes the wait
observation. It does not yet publish `decision.json` through those gates.
`warden approve` remains the record. If a phone cannot close that command,
the next request is to bind the existing gate commands — not to invent
`external-agent-session`, and not to ship a Warden app.

## Decision that follows a live Orca cycle

- If Orca can host the worker and a person can close the decision from the
  phone into `decision.json`, Warden continues as this specialised core on
  top of Orca.
- If Orca can show the role but cannot take the decision, bind
  `gate-create` / `gate-resolve` to `warden approve`. Do not write a Warden UI.
- If Orca cannot host an agent without losing prompt integrity or evidence,
  Direct CLI stays the runner and Orca stays the board.

A live `runner: orca` reviewer cycle is proven by the dated 2026-09-04 row in
`docs/SMOKE.md`. The remaining product question is the mobile human-decision path, not whether
Orca can host and expose the worker without losing Warden's evidence contract.

A dynamic distributed DAG is the only reason to reopen Conductor or
LangGraph4j. This cut is not that reason.
