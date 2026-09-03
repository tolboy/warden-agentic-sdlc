# The demo that spends nothing

```bash
./examples/demo/run.sh
```

```text
examples\demo\run.cmd
```

Needs a JDK 21+ and `git`. No vendor, no API key, no network, no npm. It finishes in a few
seconds and deletes its own scratch repository unless you pass `--keep`.

## What it actually proves

Most of Warden's promises are about what happens when something is *wrong*, and a demo that
only shows the green path proves nothing: a gate that has never refused is not known to work.
So this runs the machine half of the loop twice over the same contract.

| Step | What runs | What it shows |
|---|---|---|
| 1 | `warden validate create-result` | The contract resolves: task, risk, blast radius, the exact acceptance command. Nothing is written and nothing is spent |
| 2 | `warden gates create-result --run-id demo-red` | `result.txt` is absent, the check exits 1, and Warden refuses with `command_failed`. **Exit code 1 here is the demo succeeding** |
| 3 | a `printf` | One line of work, done by hand, standing in for what an implementer role would have written |
| 4 | `warden gates create-result --run-id demo-green` | The same contract over the fixed tree: `passed` |
| 5 | `find .warden/runs` | Two run directories, neither of which overwrote the other |
| 6 | the green run's `machine-gate.json` | Pinned base commit, contract hash, changed paths, the command's exit code and both streams, and the worktree fingerprint |
| 7 | `warden ledger` | Both runs joined: one passed, one failed, and a metrics block whose vendor fields are honestly empty because no vendor ran |

Step 7 is dense on purpose. What each field of that metrics block means, and why a value
nobody reported is `null` rather than `0`, is [`docs/METRICS.md`](../../docs/METRICS.md).

## What it does not prove

No language model participates in any of this. The role loop, the vendor resolver, failover,
the browser harness and the human gate are not exercised here — those need vendor CLIs, a
subscription and, for the harness, a browser. This is the part of Warden that runs on a bare
JDK, and it is deliberately the part a stranger can verify in ten seconds without trusting
anyone.

For what a live run with real vendors looked like, read
[`docs/LIVE-CYCLE.md`](../../docs/LIVE-CYCLE.md) — a transcript, not a description.

## The fixture

`fixture/` is a complete, minimal Warden-connected project. It is the smallest thing that is
still a real contract:

- **`.warden/project.yaml`** — one named check set and one named scope. `checks.smoke` is
  `java tools/Verify.java`, a single-file source program rather than a shell one-liner,
  because Warden runs a check through `cmd.exe` on Windows and `/bin/sh` elsewhere and a
  project whose contract needs two spellings cannot be gated by one contract.
- **`.warden/tasks/create-result.yaml`** — the task: goal, non-goals, risk, the scope it may
  touch (`result.txt` and nothing else), what authority it claims, and its budget ceilings.
- **`tools/Verify.java`** — the entire definition of "done" for this project.
- **`.warden/runs/.gitignore`** — exactly what `warden init` writes: run JSON stays visible to
  Git, screenshots and raw vendor transcripts do not.

Copy this shape into a real project by running `warden init` there, not by copying these
files. And never copy Warden's own `.warden/` — that is Warden's contract, not a template.

## `sample-evidence/`

The output of one real run of this demo, committed so you can see what evidence looks like
without running anything. Both `machine-gate.json` files are byte-for-byte as Warden wrote
them.

The only edit: in the two `evidence.jsonl` files, the scratch directory's absolute path was
rewritten from the capturing machine's temp directory to `C:\Temp\warden-demo`. The capture
was made on Windows, which is why the recorded `stdout` carries `\r\n` — Warden records what
the command actually emitted rather than normalising it.

Read `demo-red/machine-gate.json` first. The interesting fields are `merge_base` (the commit
the judgement is anchored to), `contract_sha256` (so a later run can prove the terms did not
move), `commands[0].exit_code` with both streams, and `does_not_cover`, which states out loud
what the gate did *not* look at.
