# Automatic Orca projection: implemented fallback, blocked overview

Status: **partial implementation; desktop acceptance is not complete**. This does
not supersede the acceptance requirements in the
[desktop task](prompts/NEXT-SESSION-ORCA-DESKTOP-2026-10-21.md).

## What an ordinary invocation does

Interactive `warden do` and `warden run` resolve observation against the actual
execution worktree, including continuations. A ready, registered Orca workspace
receives the existing card comment and status. An absent or failing UI does not
change the runner, retry a vendor, settle a worker, or change the command's outcome.
Stdout remains the existing JSON result.

Detection has one-second RPC budgets, including Windows executable discovery.
Only an interactive local invocation may attempt `orca open`, once, with a
three-second command budget. There is no startup retry loop. The first failed
card update disables further projection calls for that observer instance; the
failure is reported on stderr and saved locally. These are child-command budgets,
not a real-time guarantee for OS process creation or filesystem calls.

Quiet invocations, redirected invocations without a Java console, CI markers,
SSH sessions and dumb terminals skip automatic desktop discovery and startup.
`--no-workspace-status` disables this projection. Existing independent
`--no-orca-gate` and `--no-decision-page` options still control gate publication
and the decision page. An explicit `--watch` retains its previous opt-in behavior,
including when stdout is redirected or narration is quiet.

After the run is reserved, automatic observation writes an atomic local snapshot,
`.warden/runs/<run-id>/desktop.json`. It records the mode, fallback code, runtime
and worktree identity when verified, and the last requested card state. A requested
state is not proof of successful projection. This file is an observer diagnostic;
the ledger, task report and decision remain authoritative after it stops updating.
It stores neither CLI error text nor environment contents. It never enters the
vendor-call ledger.

Automatic observation creates **no terminals, tasks, Dispatches or agent chats**.
Its resource list is empty. Thus it closes no user, transferred or uncertain
resource and leaves no new observer tabs behind. Compiled documents and logs remain
available through the existing report/history commands. Explicit legacy `--watch`
can still leave a retained view and document helper tabs; no automatic release
guarantee has been added to that path. Supervised `worker-release` and its durable
recovery contract are unchanged.

## Verified contract gap

Installed production CLI and runtime were both **1.4.233**, ready and reachable.
The annotated `v1.4.233` tag resolves to
`c60a9b0a2a8d1aff2da94c2185a6dba238e65755`. Latest published release at inspection
was `v1.4.237`; it was not installed or used as evidence for the installed build.
The CLI's own `agent-context`, version-matched skills and help were inspected.

Sources at that exact installed tag establish the boundary:

- [Terminal creation](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/cli/handlers/terminal.ts)
  creates a shell surface and runs a command. That is a supported observer option;
  it does not import the direct vendor as a native agent session.
- [Terminal close CLI](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/cli/handlers/terminal-close.ts)
  forwards a handle to `terminal.close` or `terminal.closeTab`.
- [Terminal lifecycle RPC](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/terminal/terminal-lifecycle-methods.ts)
  defines both close methods with `TerminalHandle` parameters. There is no expected
  owner, ownership generation or user-input generation precondition on this API.
- [Worker resource ownership](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/orchestration/worker-terminal-ownership.ts)
  associates ownership, process incarnation and takeover state with a Dispatch.
- [Worker release](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/orchestration/worker/worker-release.ts)
  and [ownership guards](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/orchestration/worker/worker-release-ownership-guard.spec.ts)
  protect supervised resources after real user input, transfer and external ownership.
  They are not an ownership lease for an arbitrary `terminal create` observer.

Reading a terminal first and then closing its handle still leaves a race with user
takeover. A local journal proves creation, not current exclusive ownership. A title,
an exited helper, or a matching process name cannot close that gap. Registering a
fake supervised worker would misrepresent execution and settlement. Therefore the
automatic overview is refused with `observer_ownership_unsupported`.

The inspected command schema offers agent launch and supervised worker surfaces,
but no external-process transcript import with an observer ownership lifecycle.
Streaming a bounded, sanitized projection from the existing direct spool is still
implementable; it is deferred until its surface can meet the release requirements.
No second agent, direct-to-Orca profile switch, raw-log replay, or substitute
`worker_done` was introduced.

`orca repo add --path <existing-directory>` is a supported existing-folder entry
point; `project setup-existing-folder` is also exposed. Neither requires a new Git
checkout. Automatic import is deferred with the overview: an unregistered or
mismatched execution folder receives `execution_workspace_unregistered`, rather
than a claim that a view was attached. Existing source registration is not changed.

## Minimal upstream proposal (not implemented commands)

Extend the existing terminal creation/close contract for non-worker observers:

1. Idempotent create returns a durable resource identity, host/runtime identity,
   terminal/process incarnation, and an opaque owner token with generation.
2. Native user input or explicit transfer atomically revokes the observer lease
   and persists that fact across reconnects and restarts.
3. Idempotent release checks that lease and generation atomically with close. It
   returns closed, retained after takeover/transfer, already absent, pending or
   unknown, with the exact recovery operation. It cannot kill the external writer.
4. A read-only resource query/reconcile route exposes those durable outcomes;
   lost create/release replies can be replayed with the original operation ID.

This can reuse Orca's existing worker ownership machinery without inventing a
Task/Dispatch or a vendor completion. A native external-activity surface with the
same ownership guarantees would also satisfy the requirement. No upstream issue
was posted, app updated, dev runtime substituted, or Orca fork modified.

## Validation boundary

Focused final-source checks: **352 passed, 0 failed** (`roster`, `orca-desktop`,
`orca-client`, `orca-workspace`, `run-start-under-orca`, `orca-run-identity`,
`orca-lifecycle`, `orca-recovery`, `orca-settlement`). These counts are one combined
run and are not added to the full registry's count.

Focused tests cover automatic mode refusal, bounded startup, exact execution-path
matching, missing/failed runtime, persistent diagnostics, suppressed late
heartbeats, connection loss without further mutations, and no terminal/worker
creation or close. These checks establish containment, not supported takeover
cleanup of an observer that the runtime cannot own.

Two real CLI runs without `--watch` were executed in a new disposable
`hello-world-test` worktree inside the installed Orca terminal. Both deliberately
stopped at `preflight_outside_scope` before any vendor call. Both reported the
overview limitation and saved card/fallback receipts. The terminal inventory stayed
at one identical existing operator shell before and after. Native desktop pixels,
accessibility state and local receipts were captured. This is a zero-call fallback
probe, **not** the requested two successful runs with live vendor tool events.

Raw local evidence is retained under `out/desktop-contract/` and the disposable
worktree's `.warden/runs/`. These paths are not published links. A sanitized
[machine summary](evidence/orca-desktop-contract-2026-10-21.json) records only the
scope and outcomes.

| Requested desktop acceptance | Status in this implementation |
| --- | --- |
| Two complete runs with visible live vendor/tool events | Blocked: automatic overview ownership/release contract |
| No accumulated automatic tabs; unrelated shell preserved | Zero-call inventory probe passed; full observer takeover/release not available |
| Typed failure and one failover with both attempts visible | Not run; no live event surface claimed |
| Native human gate, exactly-once import and continuation | Not repeated; existing gate implementation preserved |
| UI loss during direct work; killed controller recovers original worker | No new live crash test; observer circuit tested with fake transport |
| Complete same scenario with Orca absent | Missing-runtime fallback unit tested; full vendor scenario not repeated |
| Android | Not checked; no device used |

The full desktop task remains blocked on the ownership contract. Model availability,
Grok launch pinning, Codex submission reliability and paid vendor recovery were not
revalidated by this zero-call probe. Existing settings/report pages, chosen profiles,
model/effort settings and personal authentication configuration were preserved.
