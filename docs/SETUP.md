# Setup

Warden is installed once. Each project you point it at gets only its own
`.warden/project.yaml` and task files — never a vendor name, never a model, never a key.

Throughout this document, `<warden>` is wherever you cloned this repository and `<project>`
is the repository you want Warden to work on.

## Requirements

| | |
|---|---|
| JDK | 21 or newer. Bytecode targets 21 deliberately; a newer JDK is fine |
| Git | Any recent version |
| Node | Only for the browser harness (`scripts/visual-qa.mjs`). Standard library only |
| Browser | Only for the browser harness: Chrome or Edge, already installed |
| Vendor CLIs | Whatever you intend to use — `codex`, `claude`, `grok`, … Installed and signed in by you |
| [Orca](https://www.onorca.dev/download) | Optional. `warden do` uses it for isolation when it is running, and shows the run on its board and mobile app. Without it, `git worktree` |

Nothing is downloaded at build time. If the build needs the network, that is a bug.

## 1. Build Warden, offline

```bash
cd <warden>
./build.sh
./test.sh
./bin/warden --version
```

```text
cd <warden>
build.cmd
test.cmd
bin\warden.cmd --version
```

Your installed JDK may be newer than 21; `javac --release 21` deliberately emits Java 21
bytecode so one build runs everywhere. Override the floor with `WARDEN_JAVA_RELEASE` if you
have a reason.

Before going further, run the offline demo. It takes seconds, costs nothing, and proves the
machine half of the loop works on your machine:

```bash
./examples/demo/run.sh
```

## 2. Create your operator configuration

```text
<warden>/bin/warden setup
```

This creates `~/.warden/` and never overwrites a file that already exists:

```text
~/.warden/
├ policy.yaml            which profiles may fill which role; rotation; failover; workflow
├ profiles/*.yaml        one vendor filling one role: command, args, quota signatures, model
├ prompts/*.md           the role prompt templates (implementer, reviewer, visual_qa, planner)
└ schemas/*.json         the JSON schema each role's artifact must satisfy
```

A `planner` prompt and schema are shipped next to the other three. The shipped policy does
not name the role; `warden do --prepare auto|always` is how it runs, and only if a profile
declares it. The planner drafts; Warden validates.

Set `WARDEN_CONFIG_HOME` to put this somewhere other than `~/.warden`. Note that this is
**not** `WARDEN_HOME`, which the launcher scripts use for the installation directory — the two
were once the same name, and the collision silently pointed `warden setup` at the repository.

The shipped profiles are examples, and none of them is usable yet. That is the next step.

## 3. Verify each profile before you let it run

A profile is not released by the resolver until its own probe has run and passed:
`verification.verified_on` is the date that happened. Changing which vendor fills a role is an
edit to these files plus one command.

```text
warden profiles                                       what loads, what is eligible, and why not
warden profiles --verify grok-review                  run this profile's probe; stamp it if it passes
```

`--verify` keeps the transcript and prints `what_to_check`. Read it: that list is where you
find out which envelope key carries the answer, whether the vendor opens an interactive prompt,
and whether it reports a cost at all. The date does not claim you read it — it once did, and
nothing enforced that. What it can enforce is `verification.expect`: a substring the probe's
output has to contain, or nothing is stamped. Use it whenever the probe asks a question with
a known answer; a headless CLI whose file read was silently refused exits 0 with an empty
reply, and exit code alone would have stamped it.

**Do the implementer last, and do it deliberately.** It is the only role with
`read_only: false` — the only one that writes to your worktree, and the one whose profile
carries whatever auto-approval flag its vendor needs. Run its probe standalone, then
`warden role implementer <task> --dry-run`, and only then let the loop drive it. What protects
the repository is not the date: it is the fingerprint taken around every role.

## Changing the roster

`warden roster` prints which profile fills which role, and the workflow those roles run in,
without opening the files. JSON is the default; `--text` is a table.

```text
warden roster
warden roster --text
warden roster set reviewer --profiles grok-review,claude-review --strategy rotate
warden roster model grok-review --model grok-4.6-build --effort high
```

`set` rewrites only the `profiles:` line (and `strategy:` if you pass it) under that role in
`policy.yaml`. Comments, spacing and the rest of the file stay as they were. A copy is written
first as `policy.yaml.before-roster-<timestamp>`. If the role is not in `roles:` yet, a block
is appended there rather than invented elsewhere.

`model` does the same to `profiles/<name>.yaml`. Changing the model invalidates
`verification.verified_on` — that date stamped a probe against the old model — so the line is
removed unless you pass `--keep-verified`. The command prints `verify_with` for
`warden profiles --verify <name>`.

Set `WARDEN_CONFIG_HOME` the same way every other command does if the configuration is not in
`~/.warden`.

## Visual QA through the vendor's own tools (MCP)

The browser harness drives a page over CDP. It cannot drive a Unity scene, a Tauri window, or
a canvas that answers only to the application's own events. For those, the visual role can
bring its own camera: a profile that names the MCP servers the vendor may reach, and a workflow
stage that says the agent takes the pictures.

```yaml
# ~/.warden/profiles/claude-visual-qa-mcp.yaml  (shipped by `warden setup`, unverified)
mcp:
  config: mcp/visual.json          # the vendor's own format; Warden hands it to the flag below
args: [..., "--mcp-config", "{{mcp_config}}", "--allowedTools", "Read,Glob,mcp__browser"]
capabilities:
  vision: { delivery: workspace_file, verification: required, acquires: true }
```

```yaml
# ~/.warden/policy.yaml
workflow:
  stages:
    - { stage: look, run: role, role: visual_qa, when: [visual_qa_required],
        evidence: agent, on_fail: stop, on_findings: fix }
```

`evidence: agent` needs no `browser` stage and cannot also `sees:` one. What Warden adds is the
same for every target: the role is told where to save its screenshots (the run's
`screenshots/` directory; `{{evidence_dir}}` in args if the vendor wants it as a flag) and to
list them in `screenshots_taken`; each listed file must exist there and be non-empty, its
digest goes onto the step row as `image_evidence`, and a verdict that lists none is refused as
`visual_qa_no_evidence` — no fix round, because no implementer can make a model take a
picture. The configuration file's digest is recorded on the role contract. Warden never reads
the file's format and never checks what a picture shows: that is what the profile's probe is
for, and the template's `verification.probe` asks the model to take one screenshot, open it
with its own read tool and name two words visible in it. Run that against **your** server, look
at `probe.png` yourself, and only then stamp `verified_on`.

Which server drives which target is your choice, and outside what this repository has tried:

| Target | What the profile's `mcp/visual.json` names | What the probe has to show |
|---|---|---|
| Svelte, React, plain browser UI | a browser-automation MCP server (Playwright- or DevTools-based) with a screenshot tool | the dev server's page, not `about:blank` |
| Tauri 2 desktop | a desktop-automation server that can focus the application's window and capture it; or the app's WebView through the same browser server when it exposes a DevTools port | the window, at the size the scenario names |
| Unity | an editor bridge exposing play-mode control and a screenshot or camera capture tool | the scene in play mode, not the editor chrome |
| Node.js services | usually nothing to look at; keep `visual_qa.required: false` and let the machine gates judge | — |

Grant the role only the server's tools and `Read`: it is read-only, and it does not need a
shell to take a picture. For `runner: orca` Warden cannot hand the file to the worker; name
the servers in the vendor's own settings and keep `mcp.config` pointing at a copy so its digest
is still recorded. Before the first call the loop checks that the profile chosen for an
`evidence: agent` stage declares `acquires: true` (`visual_qa_unavailable` otherwise) and that
the configuration file exists (`mcp_config_missing`); `--dry-run` reports the same.

## The five-role loop

The shape this branch was built to run, written as policy — two planners, one writer, two
readers, every hand-back bounded:

```yaml
roles:
  planner:       { profiles: [claude-plan],      strategy: first }
  plan_reviewer: { profiles: [agy-plan-review],  strategy: first, require_independent_vendor: true }
  implementer:   { profiles: [grok-implement],   strategy: first }
  reviewer:      { profiles: [astra-review, claude-review], strategy: rotate,
                   require_independent_vendor: true }
workflow:
  stages:
    - { stage: implement,     run: role, role: implementer, on_fail: stop }
    - { stage: gates,         run: machine_gates, on_fail: fix, recheck_after_fix: true }
    - { stage: review,        run: role, role: reviewer, when: [review_required],
        on_fail: stop, on_findings: fix, recheck_after_fix: true }
    - { stage: review-second, run: role, role: reviewer, when: [review_required],
        on_fail: stop, on_findings: fix }
```

`warden do <goal> --prepare always` runs the planner, then the plan reviewer over the compiled
contract; blocking findings send the planner back once. The implementer writes; the first
reviewer stage takes the first profile of the rotation and the second stage the second, on
every run; each reader's blocking findings go back to the implementer under
`max_fix_attempts`, and `recheck_after_fix` on the first review means a repair made for the
second reader is read again by the first. The run ends at the human gate. Profile names are
yours: `warden roster` shows what fills what. Nothing here has run live yet; `--dry-run`
prices it without dispatching anyone.

## 4. Connect a project

From the root of the target repository:

```text
<warden>/bin/warden init --base-ref origin/main
```

Use `--base-ref HEAD` only for a local repository with no long-lived remote ref; otherwise
name the reviewed base such as `origin/main`. The command creates:

```text
<project>/.warden/project.yaml
<project>/.warden/tasks/<task-id>.yaml
<project>/.warden/runs/.gitignore
```

`init` recognises npm, a Gradle wrapper, Maven, Cargo and Make, and writes a conservative
starter contract. **Its inferred check commands are a review starting point, not policy** —
read every one and replace the placeholder goal. A directory with files but no recognised
build system is refused rather than given a guessed command: a guessed gate fails for reasons
that have nothing to do with the task.

Do not copy Warden's own `.warden/` into a project. That directory describes Warden itself.

Then run the cheap checks, before spending anything on an agent:

```text
<warden>/bin/warden validate <task-id>
<warden>/bin/warden gates <task-id> --run-id <run-id>
```

Both must run with the target project as the current directory.

## 5. Isolation

`warden do` cuts a worktree from the branch you are actually on, so the loop never writes to
your working checkout. Two backends, one guarantee:

| | |
|---|---|
| `git worktree` | The default. Needs nothing you do not already have. The checkout goes in `<repo>-worktrees/<name>`, beside the repository rather than inside it |
| Orca | Used automatically when Orca is running. The worktree appears on its board and its mobile app, which is the reason to prefer it |

`--isolation git` or `--isolation orca` settles it explicitly. Asking for Orca when Orca is down
fails rather than quietly substituting the other: asking for a backend is a statement.

A fresh `git worktree` has none of what your checks need — an npm project has no
`node_modules` — so declare it once in the project contract:

```yaml
setup:
  - "npm ci"
```

That runs only in a worktree Warden made, and only when it is new. Orca runs the repository's
own setup when Orca made the checkout, so this is not read then.

`--in-place` skips isolation and lets the implementer edit the tree you are standing in. That
is the whole difference; decide accordingly.

`do` is the only command that isolates anything. `run`, `role`, `gates`, `visual-qa`, `report`,
`status`, `approve` and `land` all work in whatever directory you are already in — including a
worktree you made yourself.

A fresh repository needs one commit before Git can create worktrees from it.

**On Windows, if Orca and your vendor CLI run under different local identities**, Git will
refuse to operate on the checkout until you add one narrow trust entry:

```text
git config --global --add safe.directory C:/absolute/path/to/project
```

Add the specific repository. Never use `safe.directory=*`.

## 6. Non-ASCII goals on Windows

The JVM decodes command-line arguments using `sun.jnu.encoding`. Where the ANSI code page is
not UTF-8 — the ordinary Windows default — every Cyrillic character in an argument becomes `?`
before `main` runs. `-Dsun.jnu.encoding=UTF-8`, `JDK_JAVA_OPTIONS` and `chcp 65001` do not
change it.

Two things that work:

```text
warden do --goal-file goal.txt --project <project> --scope ui
```

or enable Windows Settings → Time & language → Language & region → Administrative language
settings → "Use Unicode UTF-8 for worldwide language support", which makes the ANSI code page
65001 and fixes it for every program.

`warden doctor` reports this under `argument_encoding`, and `warden do` refuses a mangled goal
rather than writing question marks into a contract.

## 7. Conductor, if you use it

Conductor is optional. `conductor/gates.yaml` is the
executable outer workflow: its required `project_dir` input points at the target repository,
and the compiled Warden classpath is resolved relative to the workflow file.

The division is deliberate. Conductor owns the outer timeout and the native fail-closed human
gate; Warden owns contract linting, machine gates and the bounded role loop. Conductor does
not duplicate the role DAG — two competing resume/retry state machines would be worse than
either.

Conductor's own workflow currently exercises only the machine nodes. The visual leg is not
enabled there, and this repository does not claim otherwise.

On Windows, Conductor may require `PYTHONUTF8=1` before validation or execution.

## 8. Checking your setup

```text
warden doctor
```

reports the Java runtime, which profiles load and which are eligible, the effective workflow
chain, Orca readiness, and `argument_encoding`. It is the first thing to paste into a bug
report.
