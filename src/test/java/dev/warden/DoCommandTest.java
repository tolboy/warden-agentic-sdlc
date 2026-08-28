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
    private void visualDraftChecks(Check check, Path sandbox) throws Exception {
        Path drafts = sandbox.resolve("drafts");
        Files.createDirectories(drafts.resolve(".warden/tasks"));

        new TaskDraft().write(drafts, "named", "Add a Settings button to the header", "code", "low");
        String named = Files.readString(drafts.resolve(".warden/tasks/named.yaml"));
        check.contains("a named control becomes a real assertion", named, "text=Settings visible");

        new TaskDraft().write(drafts, "unnamed", "Fix the layout on the settings screen", "code", "low");
        String unnamed = Files.readString(drafts.resolve(".warden/tasks/unnamed.yaml"));
        check.that("a goal that names no control invents none", !unnamed.contains("text=Create"));
        check.contains("and falls back to what holds for any page", unnamed, "no-console-errors");
        check.contains("while telling the operator what to replace it with", unnamed, "css=.settings-panel");

        new TaskDraft().write(drafts, "russian", "почини вёрстку экрана настроек", "code", "low");
        String russian = Files.readString(drafts.resolve(".warden/tasks/russian.yaml"));
        check.contains("a Russian UI goal gets visual QA too", russian, "required: true");

        new TaskDraft().write(drafts, "backend", "Add a retry to the payment client", "code", "low");
        String backend = Files.readString(drafts.resolve(".warden/tasks/backend.yaml"));
        check.contains("a non-UI goal asks for no browser", backend, "required: false");

        check.that("no package.json means no npm preview command",
                !unnamed.contains("npm run preview"));

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
        for (String id : List.of("named", "unnamed", "russian", "backend")) {
            ConfigLoader.Loaded parsedDraft = new ConfigLoader().load(drafts, id);
            check.that("drafted task '" + id + "' passes the linter", parsedDraft.resolved().id().equals(id));
        }
        ConfigLoader.Loaded russianTask = new ConfigLoader().load(drafts, "russian");
        check.eq("and a non-ASCII goal survives the round trip through YAML",
                "почини вёрстку экрана настроек", russianTask.resolved().goal());
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
