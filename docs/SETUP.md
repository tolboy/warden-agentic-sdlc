# Warden setup and reuse log

This is the repeatable procedure. Warden is installed once; each target project only gets
its own `.warden/project.yaml` and task files.

## 1. Build Warden offline

Windows:

```text
cd C:\Users\anato\IdeaProjects\warden
build.cmd
test.cmd
bin\warden.cmd --version
```

The installed JDK may be 25; `javac --release 21` deliberately emits Java 21 bytecode.
Kotlin is not required for the MVP and no dependency is downloaded.

## 2. Add Warden to Orca

Warden has been registered as the separate Orca repo `warden`, id
`427085c8-85dc-4d6e-8825-d6dfcc4a4e61`. A fresh repository needs one human-reviewed
baseline commit before Git/Orca can create child worktrees. Baseline `fc54be7` now exists.
Do not point Warden development back at the Living Horizon branch.

On this Windows host the checkout is written by `CodexSandboxOffline` while Orca runs as
the interactive user. Git therefore needs one narrow trust entry before Orca can create a
worktree:

```text
git config --global --add safe.directory C:/Users/anato/IdeaProjects/warden
```

Do not use a wildcard safe-directory entry.

## 3. Connect any project

From the root of the target Git repository, generate a starter contract:

```text
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd init --base-ref HEAD
```

Use `HEAD` only for a local repository with no long-lived remote ref. Otherwise replace it
with the reviewed base ref such as `origin/main`. The command creates:

```text
<project>/.warden/project.yaml
<project>/.warden/tasks/<task-id>.yaml
<project>/.warden/runs/.gitignore
```

Review every generated command and replace the placeholder goal. Do not copy Warden's own
`.warden/`; it describes Warden itself. Vendor/model/account configuration does not belong
in the target repository.

Run the cheap checks before spending an agent call:

```text
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd validate <task-id>
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd gates <task-id> --run-id <run-id>
```

These commands must run with the target project as the current directory.

## 4. Conductor boundary

`conductor/gates.yaml` is the currently executable outer workflow. Its required
`project_dir` input points at the target repository; the compiled Warden classpath is resolved
relative to the workflow file. Conductor owns the
outer timeout and the native fail-closed human gate. Warden owns contract linting, machine
gates and the bounded role loop — Conductor deliberately does not duplicate the role DAG.
Conductor's own workflow still exercises only the machine nodes; the visual leg is not
enabled, and the repository must not claim otherwise.

On Windows Conductor v0.1.33 may require `PYTHONUTF8=1` before validation or execution.

## 4a. Non-ASCII goals on Windows

Measured on this host: `sun.jnu.encoding` is `Cp1252`, so the JVM replaces every Cyrillic
character in a command-line argument with `?` before `main` runs. `-Dsun.jnu.encoding=UTF-8`,
`JDK_JAVA_OPTIONS` and `chcp 65001` were all tried and none of them change it.

Two working options:

```text
warden do --goal-file goal.txt --project C:/path/to/repo --scope ui
```

or enable Windows Settings → Time & language → Language & region → Administrative language
settings → "Use Unicode UTF-8 for worldwide language support", which makes the ANSI codepage
65001 and fixes it for every program.

`warden doctor` reports this under `argument_encoding`, and `warden do` refuses a goal that
arrived as question marks rather than writing it into a contract.

## 5. Current live verification

- Java runtime: Temurin 25.0.2; bytecode target: 21.
- Conductor: 0.1.33.
- Orca: 1.4.188, runtime ready.
- Unit/conformance tests: see the latest `test.cmd` output.
- Old JS implementation: reference only at
  `C:\Users\anato\orca\workspaces\Living-horizon\agentic-sdlc-mvp\scripts\agentic-sdlc`.

## 6. Safe next steps

1. Keep both smokes in `docs/SMOKE.md` reproducible.
2. Verify a writing implementer profile by running its probe standalone, then `--dry-run`,
   then let `warden run` drive it. This is the last step before the loop is live end to end,
   and it is the riskiest one: it is the only role with `read_only: false`.
3. Live-test `runner: orca` from inside an Orca worktree. The adapter is written; completion
   is fail-closed until `worker_done` is proven. Do not point Warden development back at the
   Living Horizon JS branch — that tree is reference-only.
4. Only then enable visual QA pixel-diff; until then `visual_qa.required` fails closed.
