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
baseline commit before Git/Orca can create child worktrees. Do not point Warden development
back at the Living Horizon branch.

## 3. Connect any project

Create:

```text
<project>/.warden/project.yaml
<project>/.warden/tasks/<task-id>.yaml
<project>/.warden/runs/.gitignore
```

Copy the shape from Warden's own `.warden/` directory and replace only project name,
base ref, named checks and named scopes. Vendor/model/account configuration does not belong
in the target repository.

Run the cheap checks before spending an agent call:

```text
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd validate <task-id>
C:\Users\anato\IdeaProjects\warden\bin\warden.cmd gates <task-id> --run-id <run-id>
```

These commands must run with the target project as the current directory.

## 4. Conductor boundary

`conductor/gates.yaml` is the currently executable outer workflow. Conductor owns the
outer timeout and the native fail-closed human gate. Warden owns contract linting and
machine gates. The full implement/fix/review/visual loop is not enabled until the Orca role
adapter is live-tested; the repository must not claim otherwise.

On Windows Conductor v0.1.33 may require `PYTHONUTF8=1` before validation or execution.

## 5. Current live verification

- Java runtime: Temurin 25.0.2; bytecode target: 21.
- Conductor: 0.1.33.
- Orca: 1.4.188, runtime ready.
- Unit/conformance tests: see the latest `test.cmd` output.
- Old JS implementation: reference only at
  `C:\Users\anato\orca\workspaces\Living-horizon\agentic-sdlc-mvp\scripts\agentic-sdlc`.

## 6. Safe next steps

1. Review and create the first Warden baseline commit.
2. Re-open/refresh the Warden project in Orca so its main worktree is materialized.
3. Run a read-only Grok/Claude architecture review through Orca orchestration.
4. Implement and live-test the Orca role adapter and bounded fix loop.
5. Only then add the full Conductor workflow and a small real-project task.
