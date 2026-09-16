# Sample operator runbook (placeholders only)

This is the shape `warden pilot prepare` writes to `RUNBOOK.md` inside a new bundle.
Paths below are placeholders so this committed example does not name a local checkout.
A generated runbook fills them from the spec you passed.

## What the command does not do

It does not execute baseline, acceptance or reproduction commands, call a vendor, invoke
`warden run` / `do` / `role`, create worktrees, or modify the target checkout or global home.

This is one P2 slice: deterministic offline preparation from an explicit JSON spec. It does
not close all of P2 or P2PLAN-17. P3 durable repair-across-resume and P4 strict money or
deadline are not implemented.

## Install the target contract

After review, copy `target/.warden/` to:

    REPLACE-WITH-TARGET-CHECKOUT/.warden

`warden pilot prepare` does not copy those files there.

Isolated config home (set before validate/run):

    REPLACE-WITH-BUNDLE/config

## Preview (spends nothing; uses a fresh run id)

```powershell
$env:WARDEN_CONFIG_HOME = 'REPLACE-WITH-BUNDLE\config'
Set-Location 'REPLACE-WITH-TARGET-CHECKOUT'
warden validate pilot-task
warden run pilot-task --run-id pilot-preview-<date>-<id> --dry-run
```

A dry-run reserves that run id. A later live run needs a different one and a separate
authorisation. Green baseline, quota, authentication and live readiness remain unproven.

## Declared checks (not_run)

- Green baseline: the commands that must already pass, never the suite being repaired.
- Red acceptance: the independent check the writer is asked to restore.
- Reproduction: recorded for the observation sheet; still `not_run` until the operator runs it.
