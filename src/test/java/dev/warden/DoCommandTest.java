package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskDraft;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.process.ProcessRunner;
import dev.warden.run.DoCommand;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class DoCommandTest implements Suite {
    @Override public String name() { return "do-command"; }

    @Override public void run(Check check) throws Exception {
        check.eq("latin goal becomes a slug", "add-settings-button", TaskDraft.slug("Add Settings button"));
        check.that("a russian goal still yields a legal slug",
                dev.warden.config.RepoPath.isSlug(TaskDraft.slug("Добавить кнопку Settings")));

        DoCommand.Options parsed = DoCommand.parse(new String[] {
                "do", "--project", "C:/repo", "--scope", "code", "--in-place", "Add a Settings button"
        });
        check.eq("positional words join into the goal", "Add a Settings button", parsed.goal());
        check.that("--in-place is isolation opt-out", parsed.inPlace());

        check.rejects("a missing goal is refused before anything runs", "do requires a goal",
                () -> DoCommand.parse(new String[] {"do", "--in-place"}));

        Path sandbox = Files.createTempDirectory("warden-do-");
        try {
            Path project = sandbox.resolve("project");
            Path home = sandbox.resolve("home");
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("package.json"),
                    "{\"name\":\"fixture\",\"scripts\":{\"check\":\"echo ok\"}}");
            git(project, "init", "-q", "-b", "main", ".");
            git(project, "config", "user.email", "test@example.invalid");
            git(project, "config", "user.name", "test");
            git(project, "add", "-A");
            git(project, "commit", "-qm", "base");

            new UserSetup().run(home);
            UserConfig user = UserConfig.load(home);

            DoCommand.Outcome missingGit = new DoCommand(new ProcessRunner()).run(
                    new DoCommand.Options(sandbox.resolve("nope"), "goal", "code", "low",
                            null, "r1", "HEAD", true, true), user);
            check.eq("non-git directories are refused", "project_not_found", missingGit.code());

            DoCommand.Outcome dry = new DoCommand(new ProcessRunner()).run(
                    new DoCommand.Options(project, "Create src/result.txt containing ok",
                            "code", "low", "hello", "do-hello", "HEAD", true, true), user);
            check.that("in-place dry-run writes a task the linter accepts",
                    Files.isRegularFile(project.resolve(".warden/tasks/hello.yaml")));
            ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
            check.eq("drafted goal is the operator's sentence",
                    "Create src/result.txt containing ok", loaded.resolved().goal());
            check.that("drafting never grants land", !loaded.resolved().authority().land());
            check.eq("dry-run does not claim a merge", Boolean.FALSE, dry.report().get("lands"));
            check.that("dry-run stays in the given checkout", Boolean.FALSE.equals(dry.report().get("isolated")));
            check.eq("dry-run has no authorization next action", "none", dry.report().get("next_action"));
            check.that("dry-run creates no approvable decision",
                    !Files.exists(project.resolve(".warden/runs/do-hello/decision.json")));

            nonAsciiGoalChecks(check, sandbox, project, user);
            visualDraftChecks(check, sandbox);
            gitIsolationChecks(check, sandbox);
        } finally {
            deleteTree(sandbox);
        }
    }

    /**
     * A goal in any language has to survive the trip from the operator to the contract.
     *
     * On this Windows host it does not survive argv: the JVM decodes the native command line
     * with sun.jnu.encoding=Cp1252, so every Cyrillic character is a `?` before main() runs,
     * and no JVM flag changes it. The file channel is what works, and the damaged channel is
     * refused rather than written down.
     */
    private void nonAsciiGoalChecks(Check check, Path sandbox, Path project, UserConfig user) throws Exception {
        String goal = "Добавить кнопку Settings на экран";
        Path goalFile = sandbox.resolve("goal.txt");
        Files.writeString(goalFile, goal, java.nio.charset.StandardCharsets.UTF_8);

        DoCommand.Options fromFile = DoCommand.parse(new String[] {
                "do", "--goal-file", goalFile.toString(), "--scope", "code", "--in-place"
        });
        check.eq("a goal read from a UTF-8 file arrives intact", goal, fromFile.goal());
        check.that("and the other flags are still honoured", fromFile.inPlace());

        check.rejects("an unreadable goal file is an error, not an empty goal", "could not be read",
                () -> DoCommand.parse(new String[] {"do", "--goal-file", sandbox.resolve("absent").toString()}));

        // What argv actually delivers here, reproduced exactly.
        DoCommand.Outcome mangled = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, "???????? ?????? Settings", "code", "low",
                        "mangled", "do-mangled", "HEAD", true, true), user);
        if (DoCommand.argumentsCanCarryNonAscii()) {
            check.that("on a UTF-8 host the same text is simply a goal", mangled.report() != null);
        } else {
            check.eq("a goal that arrived as question marks is refused",
                    "goal_mangled_by_console_encoding", mangled.code());
            check.contains("and the working channel is named",
                    String.valueOf(mangled.report().get("message")), "--goal-file");
            check.that("nothing was written for it",
                    !Files.exists(project.resolve(".warden/tasks/mangled.yaml")));
        }
    }

    /**
     * What the drafter is allowed to put in a visual contract. It used to default the control
     * label to "Create" — one application's button — so any other project got a task asserting
     * the visibility of something it had never had. A guessed acceptance criterion is the same
     * failure as a guessed blast radius, one field over.
     */
    /**
     * Isolation with nothing but Git.
     *
     * The property that matters is not that a directory appeared: it is that the checkout the
     * operator was standing in is untouched afterwards. That is the whole promise `--in-place`
     * exists to opt out of, and before this backend a machine without Orca could only opt out.
     */
    private void gitIsolationChecks(Check check, Path sandbox) throws Exception {
        check.eq("a task id is already a legal branch name",
                "w-add-settings-button",
                dev.warden.git.GitWorktreeIsolation.sanitize("w-add-settings-button"));
        check.eq("and one that is not becomes one",
                "fix-the-create-button",
                dev.warden.git.GitWorktreeIsolation.sanitize("Fix the Create button!"));
        check.eq("a name with nothing usable in it still yields a branch",
                "warden-work", dev.warden.git.GitWorktreeIsolation.sanitize("!!!"));

        Path source = sandbox.resolve("isolated");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/a.txt"), "x\n");
        git(source, "init", "-q", "-b", "main", ".");
        git(source, "config", "user.email", "test@example.invalid");
        git(source, "config", "user.name", "test");
        git(source, "add", "-A");
        git(source, "commit", "-qm", "base");

        dev.warden.execution.Isolation.Placement placement =
                new dev.warden.git.GitWorktreeIsolation(new ProcessRunner())
                        .isolate(source, "w-settings", "main");
        check.that("the worktree is isolated", placement.isolated());
        check.eq("and says it made it", "created", placement.reason());
        check.that("it exists on disk", Files.isDirectory(placement.path()));
        check.that("outside the repository, not inside its blast radius",
                !placement.path().toAbsolutePath().normalize()
                        .startsWith(source.toAbsolutePath().normalize()));
        check.that("carrying the committed file", Files.isRegularFile(placement.path().resolve("src/a.txt")));

        ProcessRunner.Result dirty = new ProcessRunner().run(
                List.of("git", "status", "--short"), source, Duration.ofSeconds(30));
        check.eq("and the branch the operator was on is untouched", "", dirty.stdout().strip());

        // Asked twice for the same task: join the worktree rather than refuse. A second
        // `warden do` on one task id is somebody continuing, not somebody colliding.
        dev.warden.execution.Isolation.Placement again =
                new dev.warden.git.GitWorktreeIsolation(new ProcessRunner())
                        .isolate(source, "w-settings", "main");
        check.eq("a second ask joins the same worktree", "already_isolated", again.reason());
        check.eq("at the same path", placement.path(), again.path());

        Path bare = sandbox.resolve("not-a-repo");
        Files.createDirectories(bare);
        check.rejects("a directory that is not a repository is refused, not initialised",
                "not a Git repository",
                () -> new dev.warden.git.GitWorktreeIsolation(new ProcessRunner())
                        .isolate(bare, "w-nope", "main"));
    }

    private void visualDraftChecks(Check check, Path sandbox) throws Exception {
        Path drafts = sandbox.resolve("drafts");
        Files.createDirectories(drafts.resolve(".warden/tasks"));

        new TaskDraft().write(drafts, "named", "Add a Settings button to the header", "code", "low");
        String named = Files.readString(drafts.resolve(".warden/tasks/named.yaml"));
        check.contains("a named control becomes a real assertion", named, "testid=settings visible");
        check.contains("and the draft says who has to write that attribute", named,
                "data-testid=\"settings\"");

        // `slug` never fails, because it names files: for «Сохранить» it answers `task-` plus
        // a hash. Written into a contract that is `testid=task-345e4ebd`, an assertion no
        // markup will ever satisfy and no implementer can read.
        new TaskDraft().write(drafts, "quoted-ru", "Добавить кнопку «Сохранить» на панель", "code", "low");
        String quotedRu = Files.readString(drafts.resolve(".warden/tasks/quoted-ru.yaml"));
        check.that("a control Warden cannot spell is not invented as a testid",
                !quotedRu.contains("testid=task-"));
        check.contains("the draft still says the goal named one", quotedRu, "Сохранить");
        check.contains("and falls back to what holds for any page", quotedRu, "no-console-errors");

        new TaskDraft().write(drafts, "unnamed", "Fix the layout on the settings screen", "code", "low");
        String unnamed = Files.readString(drafts.resolve(".warden/tasks/unnamed.yaml"));
        check.that("a goal that names no control invents none", !unnamed.contains("text=Create"));
        check.contains("and falls back to what holds for any page", unnamed, "no-console-errors");
        check.contains("while telling the operator what to replace it with", unnamed, "css=.settings-panel");

        // Measured on a live draft: a goal asking for "a data-testid on every lab chapter
        // button" produced `text=chapter visible` — an assertion about a word the page
        // happens to contain, which passes without testing anything the task is about.
        new TaskDraft().write(drafts, "incidental",
                "Give every lab chapter button a stable data-testid", "code", "low");
        String incidental = Files.readString(drafts.resolve(".warden/tasks/incidental.yaml"));
        check.that("a lowercase word before \"button\" is not a control name",
                !incidental.contains("text=chapter"));
        check.contains("so the draft falls back to what holds for any page",
                incidental, "no-console-errors");

        new TaskDraft().write(drafts, "russian", "почини вёрстку экрана настроек", "code", "low");
        String russian = Files.readString(drafts.resolve(".warden/tasks/russian.yaml"));
        check.contains("a Russian UI goal gets visual QA too", russian, "required: true");

        new TaskDraft().write(drafts, "backend", "Add a retry to the payment client", "code", "low");
        String backend = Files.readString(drafts.resolve(".warden/tasks/backend.yaml"));
        check.contains("a non-UI goal asks for no browser", backend, "required: false");

        // `--goal-file` exists to carry a goal a command line cannot, so it is the channel
        // whose goals have paragraphs, quotes and Windows paths in them — and it was the one
        // the drafter could not write back. Newlines went into the double-quoted scalar raw
        // and Warden refused to parse its own contract: `line 3: unterminated quoted string`.
        String fromFile = """
                Hold the night torch still so it can be photographed.

                Add a button carrying data-testid="lab-torch" to the lab panel.
                Do not edit src\\lib\\landscape.ts.""";
        new TaskDraft().write(drafts, "fromfile", fromFile, "code", "low");

        check.that("no package.json means no npm preview command",
                !unnamed.contains("npm run preview"));

        // The drafter used to write `npm run preview` for any package.json at all, while the
        // browser stage's own fallback already knew to look for the script and to fall back to
        // `dev` on its own port. A project with only `dev` was handed a contract whose server
        // could never come up, and the failure said nothing about why.
        Path onlyDev = sandbox.resolve("only-dev");
        Files.createDirectories(onlyDev.resolve(".warden/tasks"));
        Files.writeString(onlyDev.resolve("package.json"),
                "{\"scripts\": {\"dev\": \"vite\", \"build\": \"vite build\"}}\n");
        new TaskDraft().write(onlyDev, "devonly", "Add a Settings button to the header", "code", "low");
        String devDraft = Files.readString(onlyDev.resolve(".warden/tasks/devonly.yaml"));
        check.contains("a project with only a dev script gets dev, on its own port",
                devDraft, "npm run dev -- --host 127.0.0.1 --port 5173");
        check.contains("and the url matches the port that was pinned", devDraft,
                "http://127.0.0.1:5173/");

        Path hasPreview = sandbox.resolve("has-preview");
        Files.createDirectories(hasPreview.resolve(".warden/tasks"));
        Files.writeString(hasPreview.resolve("package.json"),
                "{\"scripts\": {\"dev\": \"vite\", \"preview\": \"vite preview\"}}\n");
        new TaskDraft().write(hasPreview, "prev", "Add a Settings button to the header", "code", "low");
        check.contains("preview still wins where it exists: a check is about a build",
                Files.readString(hasPreview.resolve(".warden/tasks/prev.yaml")),
                "npm run preview -- --host 127.0.0.1 --port 4173");

        // Every draft must parse: the indentation of a generated block is not cosmetic.
        Files.writeString(drafts.resolve(".warden/project.yaml"), """
                version: 1
                project: drafts
                base_ref: HEAD
                checks:
                  fast: ["echo ok"]
                scopes:
                  code: ["src"]
                defaults:
                  checks: fast
                """);
        Files.createDirectories(drafts.resolve("src"));
        Files.writeString(drafts.resolve("src/a.txt"), "x");
        git(drafts, "init", "-q", "-b", "main", ".");
        git(drafts, "config", "user.email", "test@example.invalid");
        git(drafts, "config", "user.name", "test");
        git(drafts, "add", "-A");
        git(drafts, "commit", "-qm", "base");
        for (String id : List.of("named", "unnamed", "russian", "backend", "fromfile")) {
            ConfigLoader.Loaded parsedDraft = new ConfigLoader().load(drafts, id);
            check.that("drafted task '" + id + "' passes the linter", parsedDraft.resolved().id().equals(id));
        }
        ConfigLoader.Loaded russianTask = new ConfigLoader().load(drafts, "russian");
        check.eq("and a non-ASCII goal survives the round trip through YAML",
                "почини вёрстку экрана настроек", russianTask.resolved().goal());
        ConfigLoader.Loaded fileTask = new ConfigLoader().load(drafts, "fromfile");
        check.eq("a goal read from a file keeps its paragraphs, quotes and backslashes",
                fromFile, fileTask.resolved().goal());
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.Result result = new ProcessRunner().run(command, cwd, Duration.ofSeconds(30));
        if (!result.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
