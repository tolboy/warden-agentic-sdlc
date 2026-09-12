# What the loop measures about itself

`warden ledger` reads a project's append-only run evidence and answers two questions. The
first is which runs happened and how each ended. The second is what the loop cost to get
there — and that is the `metrics` block this document describes.

```bash
./bin/warden ledger
```

Nothing here is computed by a model, and nothing is reported that a run did not record. The
block is derived from `.warden/runs/<id>/evidence.jsonl`, which is append-only: re-reading a
ledger cannot change it, and no metric can be improved by running the aggregation again.

## The rule that shapes every number

**A value nobody reported stays absent. It never becomes a zero.**

Every vendor runs through its own CLI on the operator's own subscription, so a price exists
only if that CLI printed one. Codex prints no cost; Grok and Claude do. A summary that filled
the gap with `0` would report a run as free when in fact nobody knows what it cost, and the
one number an operator most needs to trust would be the one most quietly wrong.

So each measured quantity is three fields, not one:

| Field | Meaning |
|---|---|
| `known_count` | How many vendor attempts actually reported this value |
| `unknown_count` | How many did not |
| `total` | The sum over the known ones, or `null` when `known_count` is 0 |

`p50` and `p95` follow the same rule: computed over the reported samples only, and `null`
when there are none. Read `total` next to `unknown_count` or do not read it at all. This is
the same reason `warden report` renders a missing value as `?` rather than as a zero, and the
reason a run's ceiling report carries `unpriced_calls` and `cost_ceiling_binding`: a run where
nothing priced itself can spend every allowed call and charge $0.00 against a $40 limit.
`max_role_runs` is the bound that always holds.

## The block, field by field

### `role_runs` — how many vendor calls, and of what

| Field | Meaning |
|---|---|
| `total` | Vendor attempts recorded across every run in this project; a failover adds another attempt |
| `by_role` | Split by `implementer`, `reviewer`, `architect`, `visual_qa`, `planner` |
| `by_profile` | Split by the concrete configured profile used for each attempt |
| `by_vendor` | Split by the vendor that answered, so independence is auditable after the fact |
| `by_model` | Split by the model as the vendor reported it, not as the profile requested it |
| `by_runner` | Split by `direct`, `orca` or `local` — which adapter dispatched the call |
| `by_outcome` | Split by outcome code: `ok`, `role_artifact_schema_violation`, `role_quota_exhausted`, and so on |

A dimension the evidence did not carry is counted under `<unknown>` rather than dropped, so
the splits always sum to `total`.

One `role_run` can contain several `vendor_attempts` when quota failover occurred. Those
attempts, rather than only the final successful vendor, are the unit of every split and every
telemetry count. Historical ledgers without `vendor_attempts` remain one attempt per
`role_run`; older sparse attempt arrays inherit final-attempt fields from the enclosing event
and keep unavailable fields for earlier attempts under `<unknown>`.

### `telemetry` — cost, tokens, wall clock

`cost_usd`, `tokens.input`, `tokens.output`, `tokens.total` and `duration_millis`, each in the
three-field shape above; `duration_millis` also carries `p50` and `p95`.

Cost is only ever what a vendor printed. Warden has no price list and does not want one:
a table of per-model prices in this repository would be wrong within a month, and it would be
wrong silently.

### `iterations` — how much the loop had to go round again

| Field | Meaning |
|---|---|
| `failovers` | Vendor-to-vendor transitions, counted per transition |
| `failover_role_runs` | Role runs that involved at least one, so one run that switched twice is not read as two runs that switched |
| `fix_rounds` | Fix attempts consumed, from each task run's `attempts_used` |
| `fix_rounds_unknown_runs` | Task runs whose attempt count could not be read, kept separate rather than assumed to be 0 |

### `failures` — what refused, split by who could act on it

| Field | Meaning |
|---|---|
| `baseline` | A project-owned health check failed before any vendor was dispatched |
| `machine_gate` | A declared check exited non-zero |
| `visual_harness` | The browser harness asserted and the assertion failed |
| `visual_role` | The role with eyes objected |
| `visual_qa` | The two above, summed, for a reader who does not care which |
| `quota` | A subscription was spent — classified apart from an ordinary failure, because retrying the same vendor refuses identically and pays for the proof |
| `configuration` | The operator's fault, not the work's: `authority_denied`, `role_prompt_undeliverable`, `role_local_api_key_missing`, `role_local_endpoint_unreachable`, the `role_orca_*` codes, `role_unresolved`, and anything prefixed `config_` or `configuration_` |

The last row is the one to watch on a new install. A high `configuration` count with a low
`role_runs` count means the loop is refusing before it ever reaches a vendor, and no amount of
prompt tuning will change it.

### `human` — the gate Warden never crosses

`decisions`, `by_decision` (`accept` / `reject` / `retry` / `abort` / `switch`), `wait_events`,
and `wait_millis` with `p50` and `p95`. Wait time is measured from the timestamps the decision
record already carries, so a run that sat overnight in front of a person says so.

## What it looks like with no vendor at all

`examples/demo/run.sh` runs the machine half of the loop twice offline — once against a tree
that fails the contract, once against a tree that passes — and prints this ledger at step 7:

```json
{"run_count":2,"passed":1,"failed":1,
 "metrics":{"role_runs":{"total":0,"by_role":{},"by_profile":{},"by_vendor":{},"by_model":{},
                         "by_runner":{},"by_outcome":{}},
            "telemetry":{"cost_usd":{"known_count":0,"unknown_count":0,"total":null}},
            "iterations":{"failovers":0,"fix_rounds":0,"fix_rounds_unknown_runs":0},
            "failures":{"baseline":0,"machine_gate":1,"visual_qa":0,"quota":0,"configuration":0},
            "human":{"decisions":0,"by_decision":{},"wait_events":0}}}
```

Abridged to the fields under discussion; the command prints the full block. Every vendor
field is empty because no vendor ran, and `machine_gate: 1` is the demo's red half being
counted. That is the block behaving correctly: it reports an absence as an absence.

## What it looks like on a real run

Run `chapter-hearth-3`, transcribed in [`LIVE-CYCLE.md`](LIVE-CYCLE.md) — a full cycle to the
human gate with three vendors and one fix round:

```text
stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             ?         1103531/4842   166805
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.3397    543998/23179   712582
visual_qa             0  ok  -                               ?         ?/?                 ?
visual_qa             0  ok  claude/opus                     1.8399    34/15152       243463
implementer           1  ok  codex/gpt-5.6-terra             ?         851760/5597    164552
gates                 1  ok  -                               ?         ?/?                 ?
reviewer              1  ok  grok/grok-4.6                   0.3099    187337/45156   990086
visual_qa             1  ok  -                               ?         ?/?                 ?
visual_qa             1  ok  claude/opus                     1.4202    26/9485        156206

totals   6 vendor call(s), 1 fix round(s), $3.9098 of $40.0000 budget
outcome  ok  ready_for_human  next=human_gate
```

Two things in that table are the point of this document. The `?` in Codex's cost column is
not missing data to be cleaned up later; it is the honest reading, and `$3.9098` is what four
priced calls came to, not what the run cost. And the one fix round was ordered by **no
machine**: the gates were green, the review was `pass`, the harness `passed`, and the P1 came
from the role with eyes.

## What is not measured, and will not be guessed

- **Quality.** Nothing here says whether the work was any good. That is what the review role,
  the browser harness and the human gate are for, and none of them reduces to a number.
- **Unpriced cost.** See above. `unknown_count` is the answer, not an estimate.
- **Time a person spent thinking.** `wait_millis` measures how long a decision sat pending,
  which is not the same thing and is not offered as if it were.
- **Anything across projects, unless you ask.** Plain `warden ledger` still reads one
  project's `.warden/runs` and prints the same fields it always has. `warden ledger --global`
  reads `<UserConfig.home>/ledger/` from outside a repository, filtered by the recorded
  project identity (`--project-id`, or the identity already recorded for the current
  project). Omitting both, from outside a project, reports measurements whose project
  identity is unknown — imported legacy evidence that never carried one, with that
  absence left unknown rather than inferred. One aggregator serves both sources and
  never sums a local tree together with the copy of it that reached the home. A paid
  call is counted once: locally by expanding `role_run.vendor_attempts`; on the corpus
  by `accounting.counts_as_new_calls`, except a historical `role_run` that still
  carries `vendor_attempts` and has no journaled attempt covering that run by
  `run_instance_id`, operator run id or `vendor_attempt_id`, which is expanded so
  those attempts stay measurable. Missing identities do not establish coverage, and
  an id on only one representation does not prove two distinct calls: that
  relationship is flagged as `ambiguous_coverage` rather than merged. Evidence whose
  accounting unit cannot be established is counted as unknown.

## What the home corpus records, and what stays unknown

The home corpus is the allowlisted projection of the same events, not a second opinion
about them. It records opaque ids, hashes, requested/effective/reported model and effort
with their sources, role/runner, cost, tokens, time, stop reasons, verdicts, finding
lifecycle (id, severity, status, stage, closure and reopen links), task kind/risk, the
role contract hashes, and a `measurement_context` snapshot of limits, grants and versions.
Each requested, effective and reported value keeps the `source` label that named it
(profile, launch_receipt, vendor, unknown); an explicit unknown source is kept, not
dropped.

It does not record goal, prompt, vendor reply, finding text, arbitrary error strings,
argv, environment, credentials, diffs or source text. A field nobody allowlisted does not
pass because it is new. A missing numeric value stays absent; it is never stored as
zero, and an unknown effective setting is never filled in from the requested one.

Role success, workflow outcome and human accept are three fields. A vendor attempt is
counted once, on the `vendor_attempt` event journaled as soon as that call returns;
the enclosing `role_run` is a summary and is not a new call. A workflow summary carried
through a resume is not a new call; the planner is a call. A reservation and
`markPrepared` are `run_reserved` / `run_prepared` measurements (phase `reserve` /
`prepared`) and are not calls. Unknown lineage is left unknown.

Finding identity in the corpus comes from `findings` on the role event and from
`finding_history` on the workflow summary: id, severity, status, stage, closure and
reopen links. Finding text does not enter.

The corpus is not safe to publish because it holds no text. Identifiers and spend still
describe an operator and a vendor account.

## `--global`, `--import`, and incomplete reports

`warden ledger --global` does not load profiles, does not mint a project identity, and
does not replay recovery. Physical run instances (`run_instance_id`) stay distinct from
a lineage merged across `--continue`. Known and unknown counts survive into the output.
Imported rows that never had a `project_id` are the `--global` report from outside a
project without `--project-id`; a named `--project-id` does not absorb them.

`warden ledger --import PATH` is the one explicit import of selected local runs and
archives. It uses the same allowlist projector as the write path. Records that already
carry an `event_id` are keyed by it. Records that do not — the committed demo evidence
is this shape — get a deterministic provenance key: SHA-256 of a fixed material string
of type, time, code, ok, seq, operator run id, run instance and role/stage when those
fields were present. Absolute paths and raw fields never enter the key. Re-importing
the same source, a copy of it at another path, or an archive that overlaps a source
already imported leaves the totals unchanged. The same provenance key with a different
content hash is an integrity conflict: flagged, not merged. Contracts, units, lineage,
effort, cost and launch receipts the legacy evidence never had stay unknown. The import
receipt records when it ran, the transform version (`measurement-projector-1`) and how
complete the source was. A read never imports.

A corrupt line, a truncated last line or an unsupported `schema_version` does not fail
the report. The output names the file, the byte offset, a skip count and `incomplete`.
An incomplete import is retained the same way: the receipt in `imports.jsonl` is part
of the subsequent global report, even though the segments only contain rows that
parsed. A skipped row whose JSON still parsed contributes its `project_id` to that
receipt; a skipped row that could not be parsed applies to every identity, because
failure to recover its project does not establish that the loss cannot affect the
one being summarised. An incomplete corpus does not print an unconditional success
rate or an exact total spend: `telemetry.cost_usd.total` is `null` even when some
priced rows were readable.
