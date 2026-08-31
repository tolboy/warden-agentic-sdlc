# Security policy

## Reporting a vulnerability

Report privately through GitHub's **Security → Report a vulnerability** form on this
repository. That opens a private advisory only the maintainers can read.

Do not open a public issue for a vulnerability, and do not include credentials, API keys
or vendor transcripts in a report. A path, a command line and the observed behaviour are
enough to reproduce almost anything here.

Expect an acknowledgement within 7 days and an assessment within 30. There is no bounty
programme.

## Supported versions

Warden is pre-1.0. Only the latest tag receives fixes; there are no maintenance branches.

## What Warden is, in threat-model terms

Warden is a **local orchestrator that grants a language-model process write access to a
Git worktree on your machine.** That is the whole security story, and it is worth stating
plainly rather than burying it:

- Warden starts vendor CLIs **you** installed, with **your** credentials, from profiles
  **you** wrote in `~/.warden/`. It ships no keys, opens no network sockets of its own,
  and phones nothing home.
- One of those roles — the implementer — runs with `read_only: false` and edits files.
  This is the intended behaviour, and it is also the largest piece of trust in the system.
- Warden never merges, pushes or opens a pull request on its own. `warden land` does those
  things, only after a recorded human `accept`, and only with explicit `--commit` /
  `--push` / `--pull-request` flags.

## The boundaries Warden actually enforces

These are properties the code holds, not aspirations. Each has a test.

| Boundary | Mechanism |
|---|---|
| A role cannot silently exceed its declared scope | The worktree's content fingerprint is taken before and after every role. A `read_only` role that wrote gets `role_violated_read_only` and its artifact is discarded whatever it claims |
| A run cannot rewrite the terms it is judged by | The whole `.warden` tree except `runs/` is snapshotted before the first dispatch and compared after every role and around every gate command. Any change is `contract_mutated`, naming the path |
| An unvetted vendor profile cannot be dispatched | The resolver refuses any profile without `verification.verified_on`, a date a human stamps after running the probe and reading its output. `--verify` alone never stamps it |
| A vendor swap cannot happen unasked | `failover.on_quota_exhausted` defaults to `confirm`. Authorisation is a recorded decision carried by `warden run --continue`, permitting exactly one role-to-profile substitution |
| Spending cannot run away | `budgets.max_role_runs` is checked before each dispatch and counts vendor calls, not roles. The dollar ceiling is best-effort by construction — see below |
| Acceptance cannot be applied to a different tree | `accept` is refused when the worktree fingerprint moved after the decision was shown (`candidate_changed`) |

## Where the trust actually sits, and what Warden does not protect you from

- **The vendor CLI is trusted with your worktree.** Warden bounds *when* it runs and
  *what is checked afterwards*. It does not sandbox the process, restrict its syscalls, or
  filter its network access. `authority.network: false` in a task is a declaration Warden
  passes to vendors that honour such a flag — it is not enforcement.
- **Tool allowlists are not implemented.** A profile's `args` are whatever you wrote. If
  you pass an auto-approve flag to a vendor, you have granted that vendor whatever the flag
  grants. This is why `verified_on` exists and why it is stamped by a human.
- **The dollar ceiling only measures what priced itself.** A vendor CLI that prints no cost
  contributes `$0.00` to the total. A run where nothing priced itself can spend every
  allowed call under a $40 limit. `unpriced_calls` and `cost_ceiling_binding` report this;
  `max_role_runs` is the bound that always holds.
- **Prompts carry your repository's content to third parties.** Every dispatch sends the
  task contract, and the vendor reads your source. Do not point Warden at a repository whose
  contents you may not send to the vendors in your policy.
- **Evidence is not redacted.** `.warden/runs/<id>/` keeps raw vendor stdout/stderr,
  rendered prompts and screenshots. The shipped `runs/.gitignore` keeps screenshots and raw
  transcripts out of Git while leaving `*.json`/`*.jsonl` visible — read it before committing
  a run, and never commit a run from a repository whose logs contain secrets.
- **`--init-repo` and `--in-place` write to a directory you name.** Both are explicit for
  that reason. `--in-place` skips worktree isolation entirely and lets a role edit the tree
  you are standing in.

## Hardening checklist for an operator

1. Give each vendor CLI its own credential, scoped to what it needs.
2. Run the probe for every profile yourself and read the transcript before stamping
   `verified_on`. Do this first for any profile with `read_only: false`.
3. Leave `failover.on_quota_exhausted: confirm`. `auto` changes the author of the work and
   can silently cost you the independent reviewer.
4. Prefer Orca worktree isolation over `--in-place` for any repository you care about.
5. Keep `.warden/runs/` out of the published history unless you have read what is in it.
6. Treat `warden land --push --pull-request` as the one command that leaves your machine.
