# Architecture status

The source of truth is Warden, not a target-project branch.

| Architecture block | Owner | Current state |
|---|---|---|
| Task spec and linter | Warden config | Implemented: intent, non-goals, risk, scope, authority, acceptance, visual scenarios, budgets and limits |
| Workflow declaration | Warden user policy | Implemented: `workflow.stages` in `~/.warden/policy.yaml` declares the order of stages, what each runs (`role` / `machine_gates` / `visual_harness`), the closed set of conditions gating it, and where a failure or a finding routes. Omitting the block runs the built-in chain. Stop reasons and the fix bound are deliberately not configurable |
| Policy/run controller | Warden | `warden do` is the operator command: isolate (Orca worktree) → draft a task → implement → gates → fix ≤ N → review → fix ≤ N → browser harness → fix ≤ N → visual QA role → fix ≤ N → human gate. Never lands. `warden run` remains the inner loop |
| Role/profile resolver | Warden user policy | Implemented: verification, availability, rotation, independent vendor, and exclusion of profiles that ran out during the run |
| Vendor quota handling | Warden execution | Implemented: a spent subscription is classified apart from an ordinary failure, the role fails over to the next eligible vendor, and both attempts stay in the evidence. Verified live against a genuinely exhausted Codex account |
| Direct CLI adapter | Warden SPI | Implemented. Live: Grok review, Claude Sonnet review, Codex mini implement (`prompt_delivery: stdin`, `--approve-for-me`). JSONL `agent_message` recovery for Codex. Windows batch-shim refusal unchanged |
| Orca adapter | Warden SPI + Orca | Implemented: attaches to the current Orca worktree, starts one supervised worker, completes only on `worker_done` / dispatch settlement. Does not create worktrees. Live lifecycle not yet claimed as proven |
| Local runner | Warden SPI | Named (`runner: local`); resolver refuses `runner_unimplemented` rather than pretending it is Direct CLI |
| Workspace/tool policy | Git + TaskSpec authority | Scope and local write/network/land declarations implemented; tool allowlists pending |
| Evidence ledger | Warden | Implemented: machine reports, append-only JSONL, `warden ledger` aggregation, and per-attempt vendor cost, turns, tokens and model as reported |
| Conductor | Outer controller | Machine-only workflow implemented; it does not duplicate Warden's role loop |
| Visual QA — harness | Warden machine adapter | Implemented and project-neutral: `scripts/visual-qa.mjs` drives Edge/Chrome via CDP and asserts `text=` / `css=` / `testid=` / `role=` matchers as `visible`, `hidden` or `click -> <assertion>`, plus `no-console-errors`. Verifies the viewport it was asked for was the one the CSS saw. A pass without a screenshot is `visual_qa_no_evidence`; a stranger already serving the URL is `visual_qa_port_occupied`; no browser is `visual_qa_unavailable` |
| Visual QA — role | Warden user policy | Implemented, opt-in: a `visual_qa` role receives every screenshot as a vendor attachment (`attachments.flag`, e.g. codex `-i`) plus the harness report, and answers only what a machine cannot measure. Absent from the default policy on purpose — the harness is the floor, and looking costs a vendor call. Shipped profile is unverified until its probe is run |
| Artifact schema | Warden | Implemented: subset validator (`type`/`const`/`enum`/`required`/`properties`/`items`/bounds). An artifact that fails the schema is not written |
| Human gate / LAND | Conductor + human | Fail-closed gate implemented; Warden never lands |

`warden init` now creates a conservative, non-overwriting starter contract for npm, Gradle
wrapper, Maven, Cargo or Make projects. Its inferred commands are a review starting point,
not trusted policy.

## Roles and models

| Where | What it decides |
|---|---|
| `~/.warden/policy.yaml` | which profiles may fill `implementer`, `reviewer`, `visual_qa`; rotation; whether a reviewer must be an independent vendor; which risks pay for review; and `workflow.stages` — the order those roles run in and the condition under which each runs at all |
| `~/.warden/profiles/*.yaml` | one vendor filling one role: command, args, `runner`, `prompt_delivery`, `attachments.flag`, `quota.signatures`, `model`, and the `verification` block the resolver refuses without. `warden profiles --verify <name>` runs that block's probe and keeps the transcript; `--confirm` stamps the date, and only after the probe passed in the same invocation |
| `<project>/.warden/` | what "done" means: checks, scopes, task contracts, `visual_qa` scenarios |

`model:` is passed to the vendor only if the profile's args use `{{model}}` — each vendor
spells the flag differently, so Warden supplies the value and the profile writes the flag.
When a vendor reports a different model than the profile declares, the run report records the
disagreement rather than letting the ledger's `model` column become a wish.

State ownership is intentionally single-source: Warden owns the bounded role/fix loop;
Conductor owns outer kill limits and the human gate; Orca owns worktree and worker lifecycle.
Duplicating the role DAG in Conductor would create two competing resume/retry state machines.

## What a failure is allowed to do

Every vendor failure resolves to exactly one of these, and the choice is made in code:

| Outcome | Response | Why not the other one |
|---|---|---|
| `role_quota_exhausted` | Fail over to the next eligible vendor; exclude this profile for the rest of the process | Retrying the same vendor refuses identically and spends the budget proving it |
| `role_command_failed`, `role_timeout`, `role_artifact_*` | Stop the role; the loop may hand the failure text back to the same vendor as a fix round | Failing over would route around a defect instead of surfacing it |
| `role_prompt_undeliverable`, `authority_denied` | Stop the role; no vendor is dispatched at all | These are configuration faults. Routing around one hides it and pays a second vendor for the same corrupted request |
| `role_orca_unavailable`, `role_orca_no_worktree`, `role_orca_no_coordinator`, `role_orca_unsettled` | Stop the role; no failover to Direct CLI | The profile declared `runner: orca`. Pretending it was a CLI would hide a missing worktree or an unproven settlement |
| `role_runner_unimplemented` | Stop at resolve time | `local` is a name, not a working adapter |
| `visual_qa_failed` | Hand the failing scenario and its screenshot back to the implementer, bounded by `max_fix_attempts` | It is a failing check like any other; leaving it terminal made the one role with eyes the only one whose finding nobody had to act on |
| `visual_qa_unavailable`, `visual_qa_port_occupied` | Stop the run; no fix round | No implementer can install a browser or evict another project's dev server, and asking one to try spends a role run on the operator's configuration |
| `visual_findings_remain` | Stop for a human | The model looked and still objects. Warden does not overrule it |
| `goal_mangled_by_console_encoding` | Refuse before anything is written | The JVM decodes argv with `sun.jnu.encoding`; on a non-UTF-8 Windows host a Cyrillic goal is already `?` by the time `main` runs, and no JVM flag changes it. `--goal-file` is the channel that works |
| `role_violated_read_only` | Discard the artifact whatever it claims | The declaration is intent; the worktree fingerprint is the fact |

The exclusion list for quota lives in memory for the lifetime of one process, deliberately.
Persisting it would leave a vendor disabled after its quota window rolled over, and that kind
of stale state is only ever discovered when it is already wrong.
