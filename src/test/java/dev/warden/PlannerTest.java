package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.PlannerDraft;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.ProjectConfig;
import dev.warden.config.TaskDraft;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.ledger.LedgerReader;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;
import dev.warden.role.RoleRunner;
import dev.warden.run.DoCommand;
import dev.warden.run.Preparation;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planner role, the bootstrap that bounds it, and the checks Warden runs on what it
 * returns. Named for the behaviour, not the method.
 */
public final class PlannerTest implements Suite {

    @Override public String name() { return "planner"; }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String GOAL = "Create src/result.txt containing ok";

    @Override public void run(Check check) throws Exception {
        parseAndResolve(check);
        compileRefusals(check);
        amendmentOnlyAdds(check);

        Path sandbox = Files.createTempDirectory("warden-planner-");
        try {
            Path home = sandbox.resolve("home");
            new UserSetup().run(home);
            check.that("setup ships the planner prompt",
                    Files.isRegularFile(home.resolve("prompts/planner.md")));
            check.that("and the planner schema",
                    Files.isRegularFile(home.resolve("schemas/planner.json")));
            Policy shipped = Policy.parse(Files.readString(home.resolve("policy.yaml")), "policy.yaml");
            check.that("the shipped policy does not declare a planner",
                    !shipped.roles().containsKey("planner"));
            check.eq("and its workflow chain is still the built-in one",
                    shipped.workflow().toList(),
                    dev.warden.config.Workflow.builtIn().toList());

            Path project = sandbox.resolve("project");
            scaffoldProject(project);
            writePlannerProfile(home, "stub-plan", "plan", true);
            writePolicy(home, "stub-plan");

            prepareOffWritesTaskDraft(check, sandbox, project, home);
            prepareAutoDispatch(check, sandbox, home);
            prepareAutoRefusesMismatchedIntent(check, sandbox, home);
            skippedAutoModeReachesInnerRun(check, sandbox, home);
            prepareAlwaysDraftOnly(check, sandbox, home);
            prepareAlwaysGoesToLoop(check, sandbox, home);
            prepareAlwaysDoesNotTruncateExisting(check, sandbox, home);
            operatorBlocksSurviveCrlfAndComments(check, sandbox, home);
            prepareAlwaysVisualParity(check, sandbox, home);
            stubDraftRefusals(check, sandbox, home);
            schemaIsEnforcedWhenProfileSkips(check, sandbox, home);
            protocolFailureDiscardsDraft(check, sandbox, home);
            plannerCommitIsNotCarried(check, sandbox, home);
            stagedAdditionIsRestored(check, sandbox, home);
            bootstrapRefusesQuotaFailover(check, sandbox, home);
            failoverThenProtocolKeepsEvidenceNames(check, sandbox, home);
            ledgerCountsPlanner(check, sandbox, home);
            planReviewChecks(check, sandbox, home);
            contractAmendmentChecks(check, sandbox, home);
            reservationMeasuresTheContract(check, sandbox, home);
        } finally {
            deleteTree(sandbox);
        }
    }

    /**
     * An amendment answers readers who found the acceptance too weak. It may add scenarios
     * and named checks and nothing else; the operator's comments, start, url and every
     * existing line stay where they were.
     */
    private void amendmentOnlyAdds(Check check) {
        ProjectConfig project = ProjectConfig.parse("""
                version: 1
                project: fixture
                checks:
                  fast: []
                  text: ["node tools/text.mjs"]
                scopes:
                  code: ["<repository>"]
                """, "project.yaml");
        String before = """
                version: 1
                id: hello
                goal: "Show a greeting and a toggle"
                risk: medium
                scope: code
                visual_qa:
                  required: true
                  # written by hand; the harness serves the page with python
                  start: "python -m http.server 4173 --bind 127.0.0.1"
                  url: "http://127.0.0.1:4173/"
                  scenarios:
                    - "1280x720: testid=greeting visible"
                """;
        Map<String, Object> draft = Map.of(
                "acceptance", List.of(Map.of("check", "text", "covers", "the greeting's text"),
                        Map.of("check", "not-a-check"), "node tools/rm-rf.mjs"),
                "visual_qa", Map.of("required", true, "scenarios", List.of(
                        "1280x720: testid=greeting visible",
                        "1280x720: testid=greeting visible -> text=Hello, World visible")),
                "operator_goal", "something else entirely");
        String amended = PlannerDraft.amend(before, draft, project, "hello.yaml");
        check.that("an amendment that adds something is written", amended != null);
        if (amended == null) return;
        TaskSpec parsed = TaskSpec.parse(amended, "hello.yaml");
        check.eq("the new scenario is appended after the existing one", List.of(
                "1280x720: testid=greeting visible",
                "1280x720: testid=greeting visible -> text=Hello, World visible"),
                parsed.visualQa().scenarios());
        check.eq("the goal is the contract's, not the draft's", "Show a greeting and a toggle", parsed.goal());
        check.contains("the operator's comment is still there", amended, "# written by hand");
        check.contains("and the start command", amended, "python -m http.server 4173");
        // The contract names no checks, so it runs the project's default set, `fast` here. An
        // explicit list replaces the default, so writing `names: [text]` alone dropped it.
        check.contains("a named check from project.yaml is added beside the default the contract ran",
                amended, "names: [fast, text]");
        check.that("a name project.yaml does not define is not", !amended.contains("not-a-check"));
        check.that("and a shell string is never promoted to a check", !amended.contains("rm-rf"));

        check.eq("a draft that adds nothing new is not an amendment", null, PlannerDraft.amend(before,
                Map.of("acceptance", List.of(), "visual_qa", Map.of("required", true,
                        "scenarios", List.of("1280x720: testid=greeting visible"))), project, "hello.yaml"));

        String flow = before.replace("  scenarios:\n    - \"1280x720: testid=greeting visible\"\n",
                "  scenarios: [\"1280x720: testid=greeting visible\"]\n");
        String fromFlow = PlannerDraft.amend(flow, draft, project, "hello.yaml");
        check.eq("a flow list is rewritten as a block holding both", 2,
                fromFlow == null ? -1 : TaskSpec.parse(fromFlow, "hello.yaml").visualQa().scenarios().size());

        // A scenario is free text. Split at its comma, this one became two altered conditions.
        String withComma = before.replace("  scenarios:\n    - \"1280x720: testid=greeting visible\"\n",
                "  scenarios: [\"1280x720: testid=greeting visible -> text=Hello, World visible\"]\n");
        String fromComma = PlannerDraft.amend(withComma, Map.of("visual_qa", Map.of("required", true,
                "scenarios", List.of("1280x720: testid=toggle visible"))), project, "hello.yaml");
        check.eq("a quoted comma in a flow-list scenario survives the rewrite", List.of(
                "1280x720: testid=greeting visible -> text=Hello, World visible",
                "1280x720: testid=toggle visible"),
                fromComma == null ? null : TaskSpec.parse(fromComma, "hello.yaml").visualQa().scenarios());
    }

    private void parseAndResolve(Check check) {
        DoCommand.Options parsed = DoCommand.parse(new String[] {
                "do", "--prepare", "always", "--draft-only", "--in-place", "Add a button"
        });
        check.eq("--prepare always is accepted", "always", parsed.prepare());
        check.that("and --draft-only is still honoured", parsed.draftOnly());
        // Since 2026-09-22 a goal with no contract yet is planned: drafted without a planner, a
        // greenfield page got two generic scenarios and its acceptance was written by hand.
        check.eq("--prepare defaults to auto, which plans only a task with no contract yet",
                "auto", DoCommand.parse(new String[] {"do", "--in-place", "Add a button"}).prepare());
        check.rejects("an unknown --prepare mode is refused", "off, auto or always",
                () -> DoCommand.parse(new String[] {"do", "--prepare", "maybe", "Add a button"}));
        check.eq("auto dispatches only when no contract exists", true,
                Preparation.shouldDispatch("auto", false));
        check.eq("auto dispatches nothing when the contract is already on disk", false,
                Preparation.shouldDispatch("auto", true));
        check.eq("always dispatches even then", true, Preparation.shouldDispatch("always", true));
        check.eq("off never does", false, Preparation.shouldDispatch("off", false));

        Profile planner = Profile.parse("""
                version: 1
                profile: grok-plan
                role: planner
                vendor: grok
                command: grok
                read_only: true
                verification:
                  verified_on: "2026-09-10"
                """, "grok-plan.yaml");
        check.eq("a profile declaring role: planner loads", "planner", planner.role());
        check.that("and is read-only by declaration", planner.readOnly());

        Map<String, Profile> profiles = new LinkedHashMap<>();
        profiles.put("grok-plan", planner);
        Policy policy = Policy.parse("""
                version: 1
                roles:
                  planner:
                    profiles: [grok-plan]
                    strategy: first
                    require_independent_vendor: false
                """, "policy.yaml");
        RoleResolver.Resolution resolved = new RoleResolver().resolve("planner", policy, profiles,
                null, 0, profile -> true);
        check.eq("and resolves by the same rules as every other role",
                "grok-plan", resolved.selected().name());

        profiles.put("grok-plan", Profile.parse("""
                version: 1
                profile: grok-plan
                role: planner
                vendor: grok
                command: grok
                read_only: true
                verification:
                  probe: 'run it once'
                """, "grok-plan.yaml"));
        try {
            new RoleResolver().resolve("planner", policy, profiles, null, 0, profile -> true);
            check.that("an unverified planner is not eligible", false);
        } catch (RoleResolver.Unresolvable failure) {
            check.eq("and is still refused profile_unverified",
                    "profile_unverified", failure.rejected().get("grok-plan"));
        }
    }

    private void compileRefusals(Check check) {
        ProjectConfig project = ProjectConfig.parse("""
                version: 1
                project: demo
                checks:
                  fast: ["echo ok"]
                  full: ["echo full"]
                scopes:
                  app: ["src"]
                """, "project.yaml");
        PlannerDraft.Access granted = PlannerDraft.Access.DO_DEFAULT;

        check.rejects("a bare shell string as an acceptance entry is refused",
                "shell string",
                () -> PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                        "acceptance", List.of("npm test")))));
        check.rejects("an invented scope is refused",
                "not defined under scopes",
                () -> PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                        "scope", "invented"))));
        check.rejects("a draft that replaces the operator's goal is refused",
                "does not match the goal Warden handed",
                () -> PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                        "operator_goal", "a different goal"))));
        check.rejects("a draft that drops the operator's goal is refused",
                "operator_goal",
                () -> {
                    Map<String, Object> dropped = draft(Map.of());
                    dropped.remove("operator_goal");
                    PlannerDraft.compile("hello", GOAL, project, granted, dropped);
                });
        check.rejects("a draft asking for land the invocation did not grant is refused",
                "did not authorise",
                () -> PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                        "required_access", Map.of("workspace_write", true, "network", false, "land", true)))));
        check.rejects("and network that was not granted is refused rather than granted",
                "did not authorise",
                () -> PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                        "required_access", Map.of("workspace_write", true, "network", true, "land", false)))));

        PlannerDraft.Compiled compiled = PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of()));
        check.contains("a valid draft carries the operator's goal verbatim", compiled.yaml(), GOAL);
        check.contains("and names the project's check, not a shell string", compiled.yaml(), "checks: fast");
        check.that("and never grants land", compiled.access().land() == false);

        PlannerDraft.Compiled two = PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                "acceptance", List.of(Map.of("check", "fast"), Map.of("check", "full")))));
        check.contains("two named checks are written as names", two.yaml(), "names: [fast, full]");
        check.that("not as a list of identifiers that resolve can demote to commands",
                !two.yaml().contains("checks: [fast, full]"));
        TaskSpec.ResolvedTask resolvedTwo = TaskSpec.parse(two.yaml(), "compiled").resolve(project, "compiled");
        check.eq("and resolve to the project's commands, not the names as shell strings",
                List.of("echo ok", "echo full"), resolvedTwo.acceptanceCommands());
        ProjectConfig renamed = ProjectConfig.parse("""
                version: 1
                project: demo
                checks:
                  fast: ["echo ok"]
                  complete: ["echo full"]
                scopes:
                  app: ["src"]
                """, "project.yaml");
        check.rejects("a compiled name that later disappears is refused, not run as a shell string",
                "which is not defined under checks",
                () -> TaskSpec.parse(two.yaml(), "compiled").resolve(renamed, "compiled"));

        PlannerDraft.Compiled visual = PlannerDraft.compile("hello", GOAL, project, granted, draft(Map.of(
                "visual_qa", Map.of("required", true,
                        "scenarios", List.of("1280x720: testid=save visible")))));
        check.contains("a planner that asks for a visual contract gets it",
                visual.yaml(), "required: true");
        check.contains("with the scenarios it named", visual.yaml(), "testid=save visible");
        TaskSpec.parse(visual.yaml(), "compiled-visual");
    }

    private void prepareOffWritesTaskDraft(Check check, Path sandbox, Path project, Path home)
            throws Exception {
        Path expectedRoot = sandbox.resolve("expected-off");
        Files.createDirectories(expectedRoot.resolve("src"));
        Files.writeString(expectedRoot.resolve("src/seed.txt"), "x\n");
        new TaskDraft().write(expectedRoot, "hello", GOAL, "app", "low");
        String expected = Files.readString(expectedRoot.resolve(".warden/tasks/hello.yaml"),
                StandardCharsets.UTF_8);

        UserConfig user = UserConfig.load(home);
        List<String> narration = new ArrayList<>();
        DoCommand.Outcome off = new DoCommand(new ProcessRunner(), narration::add).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-off",
                        "HEAD", true, false, false, false, false, true, null, "off"),
                user);
        check.eq("--prepare off still drafts", "drafted", off.code());
        check.contains("and still prints a placeholder run id, because nothing was reserved",
                String.join("\n", narration), "warden run hello --run-id <id>");
        String actual = Files.readString(project.resolve(".warden/tasks/hello.yaml"),
                StandardCharsets.UTF_8);
        check.eq("--prepare off writes the contract TaskDraft writes today", expected, actual);
        check.eq("and reports the mode that was in force", "off", off.report().get("prepare"));
        check.that("and dispatched no planner",
                !Files.exists(project.resolve(".warden/runs/do-off/prompts/planner.md")));
    }

    private void prepareAutoDispatch(Check check, Path sandbox, Path home) throws Exception {
        Path existing = sandbox.resolve("auto-exists");
        scaffoldProject(existing);
        new TaskDraft().write(existing, "hello", GOAL, "app", "low");
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome skipped = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(existing, GOAL, "app", "low", "hello", "do-auto-skip",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.eq("--prepare auto with a contract on disk still drafts", "drafted", skipped.code());
        check.that("and dispatches nothing",
                !Files.exists(existing.resolve(".warden/runs/do-auto-skip/prompts/planner.md")));
        check.eq("reporting auto", "auto", skipped.report().get("prepare"));

        writeImplementerProfile(home);
        writePolicy(home, "stub-plan", "stub-impl", "confirm");
        DoCommand.Outcome skippedRun = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(existing, GOAL, "app", "low", "hello", "do-auto-skip-run",
                        "HEAD", true, true, false, false, false, false, null, "auto"),
                UserConfig.load(home));
        check.that("--prepare auto without --draft-only still skips the planner when the contract exists",
                !Files.exists(existing.resolve(".warden/runs/do-auto-skip-run/prompts/planner.md")));
        check.eq("and the command still reports auto", "auto", skippedRun.report().get("prepare"));
        Path summary = existing.resolve(".warden/runs/do-auto-skip-run/task-run.json");
        check.that("the run's own JSON was written", Files.isRegularFile(summary));
        Map<String, Object> runSummary = Json.parseObject(Files.readString(summary, StandardCharsets.UTF_8));
        check.eq("and records prepare=auto even though no model prepared it",
                "auto", runSummary.get("prepare"));
        Path skippedMarker = existing.resolve(".warden/runs/do-auto-skip-run/run.json");
        check.that("skipped auto still reserved the run so an inner process can join it",
                Files.isRegularFile(skippedMarker));
        Map<String, Object> skippedReservation = Json.parseObject(
                Files.readString(skippedMarker, StandardCharsets.UTF_8));
        check.eq("with the mode that was in force", "auto", skippedReservation.get("prepare"));
        writePolicy(home, "stub-plan");

        Path missing = sandbox.resolve("auto-missing");
        scaffoldProject(missing);
        DoCommand.Outcome dispatched = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(missing, GOAL, "app", "low", "hello", "do-auto-run",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.eq("--prepare auto with no contract dispatches the planner", "drafted", dispatched.code());
        check.that("exactly once",
                Files.isRegularFile(missing.resolve(".warden/runs/do-auto-run/prompts/planner.md")));
        check.that("and never the implementer",
                !Files.exists(missing.resolve(".warden/runs/do-auto-run/prompts/implementer.md")));
        check.contains("and compiled a contract Warden will accept",
                Files.readString(missing.resolve(".warden/tasks/hello.yaml")),
                "checks: fast");
        String planned = Files.readString(missing.resolve(".warden/tasks/hello.yaml"),
                StandardCharsets.UTF_8);
        DoCommand.Outcome again = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(missing, GOAL, "app", "low", "hello", "do-auto-again",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.eq("--prepare auto on a contract the planner wrote still drafts",
                "drafted", again.code());
        check.that("and dispatches no second planner",
                !Files.exists(missing.resolve(".warden/runs/do-auto-again/prompts/planner.md")));
        check.eq("and leaves the planner-compiled contract untouched",
                planned, Files.readString(missing.resolve(".warden/tasks/hello.yaml"),
                        StandardCharsets.UTF_8));
    }

    private void prepareAutoRefusesMismatchedIntent(Check check, Path sandbox, Path home)
            throws Exception {
        UserConfig user = UserConfig.load(home);
        Path mismatchedGoal = sandbox.resolve("auto-mismatch-goal");
        scaffoldProject(mismatchedGoal);
        new TaskDraft().write(mismatchedGoal, "hello", GOAL, "app", "low");
        DoCommand.Outcome differentGoal = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(mismatchedGoal, "Investigate why src/result.txt exists",
                        "app", "low", "hello", "do-auto-mismatch-goal",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.that("--prepare auto refuses an existing contract written for a different goal",
                !differentGoal.ok());
        check.eq("as task_conflict, not by running that contract",
                "task_conflict", differentGoal.code());
        check.contains("and the file still holds the original goal",
                Files.readString(mismatchedGoal.resolve(".warden/tasks/hello.yaml"),
                        StandardCharsets.UTF_8),
                GOAL);

        Path mismatchedRisk = sandbox.resolve("auto-mismatch-risk");
        scaffoldProject(mismatchedRisk);
        new TaskDraft().write(mismatchedRisk, "hello", GOAL, "app", "low");
        DoCommand.Outcome differentRisk = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(mismatchedRisk, GOAL, "app", "high", "hello",
                        "do-auto-mismatch-risk",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.that("--prepare auto refuses when the invocation risk does not match the contract",
                !differentRisk.ok());
        check.eq("as task_conflict for a mismatched risk", "task_conflict", differentRisk.code());

        Path mismatchedScope = sandbox.resolve("auto-mismatch-scope");
        scaffoldProject(mismatchedScope);
        Files.writeString(mismatchedScope.resolve(".warden/project.yaml"),
                Files.readString(mismatchedScope.resolve(".warden/project.yaml"),
                        StandardCharsets.UTF_8).replace(
                        "scopes:\n  app: [\"src\"]\n",
                        "scopes:\n  app: [\"src\"]\n  other: [\"src\"]\n"),
                StandardCharsets.UTF_8);
        git(mismatchedScope, "add", "-A");
        git(mismatchedScope, "commit", "-qm", "two scopes");
        new TaskDraft().write(mismatchedScope, "hello", GOAL, "app", "low");
        DoCommand.Outcome differentScope = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(mismatchedScope, GOAL, "other", "low", "hello",
                        "do-auto-mismatch-scope",
                        "HEAD", true, false, false, false, false, true, null, "auto"),
                user);
        check.that("--prepare auto refuses when the invocation scope does not match the contract",
                !differentScope.ok());
        check.eq("as task_conflict for a mismatched scope", "task_conflict", differentScope.code());
    }

    private void skippedAutoModeReachesInnerRun(Check check, Path sandbox, Path home)
            throws Exception {
        Path project = sandbox.resolve("auto-inner");
        scaffoldProject(project);
        new TaskDraft().write(project, "hello", GOAL, "app", "low");
        writeImplementerProfile(home);
        writePolicy(home, "stub-plan", "stub-impl", "confirm");
        UserConfig user = UserConfig.load(home);
        // The Conductor entry point is `warden run` with no withPreparation: a new process
        // that joins whatever `warden do --prepare auto` recorded. recordSkipped is that
        // recording when the planner did not run.
        TaskLoop.Preparation recorded = Preparation.recordSkipped(project, "do-auto-inner",
                "hello", "auto");
        check.eq("skipped auto reserved the run as auto", "auto", recorded.mode());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        TaskLoop.Outcome inner = new TaskLoop(new ProcessRunner()).run(
                loaded, user, "do-auto-inner", true);
        check.that("the inner warden run joins the skipped-auto reservation rather than run_id_exists",
                !"run_id_exists".equals(inner.reason()));
        Path summary = project.resolve(".warden/runs/do-auto-inner/task-run.json");
        check.that("the inner run wrote its JSON", Files.isRegularFile(summary));
        Map<String, Object> runSummary = Json.parseObject(
                Files.readString(summary, StandardCharsets.UTF_8));
        check.eq("and records prepare=auto even though no planner ran and no withPreparation was set",
                "auto", runSummary.get("prepare"));
        String workflow = Files.readString(Path.of("conductor/do.yaml"), StandardCharsets.UTF_8);
        check.contains("Conductor's inner warden run is told the prepare mode",
                workflow, "--prepare");
        writePolicy(home, "stub-plan");
    }

    private void prepareAlwaysDraftOnly(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("always-draft");
        scaffoldProject(project);
        UserConfig user = UserConfig.load(home);
        List<String> narration = new ArrayList<>();
        // What the board was asked to show. A compiled contract that only exists as a path in
        // a terminal is the run's most consequential artifact and its least visible one.
        List<String> shown = new ArrayList<>();
        dev.warden.run.Workspace.Source board = worktree -> new dev.warden.run.Workspace() {
            @Override public void note(String text) { shown.add("note: " + text); }
            @Override public void state(State state) { shown.add("state: " + state); }
            @Override public void show(Path file, String runId, String title) {
                check.eq("presentation belongs to the reserved run", "do-always", runId);
                shown.add("show: " + title + " -> " + file.getFileName());
            }
        };
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner(), narration::add, board, false).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-always",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("--prepare always --draft-only runs the planner", "drafted", outcome.code());
        check.eq("and reports corpus delivery on the planning-only completion", "ok",
                outcome.report().get("corpus_status"));
        check.eq("and says the tree is safe to delete after a confirmed write", true,
                outcome.report().get("tree_safe_to_delete"));
        String printed = String.join("\n", narration);
        check.contains("the next-step line names the run id that was reserved",
                printed, "warden run hello --run-id do-always");
        check.that("and does not print the placeholder an operator would not join",
                !printed.contains("--run-id <id>"));
        check.eq("and reports always", "always", outcome.report().get("prepare"));
        String board_ = String.join(" | ", shown);
        check.contains("the board is shown the contract the planner compiled", board_,
                "show: warden do-always contract -> hello.yaml");
        check.contains("the card says where it is and that nothing was dispatched", board_,
                "no writer has been dispatched");
        check.contains("and that the next move is a person's", board_,
                "state: WAITING_FOR_HUMAN");
        check.that("and writes the compiled contract",
                Files.isRegularFile(project.resolve(".warden/tasks/hello.yaml")));
        check.that("and never dispatches the implementer",
                !Files.exists(project.resolve(".warden/runs/do-always/prompts/implementer.md"))
                        && !Files.exists(project.resolve(".warden/runs/do-always/role-implementer.json")));
        check.that("the planner prompt was sent",
                Files.isRegularFile(project.resolve(".warden/runs/do-always/prompts/planner.md")));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) outcome.report().get("budget_plan");
        check.that("CallPlan reserved the planner before dispatch",
                plan != null && String.valueOf(plan.get("paying_stages")).contains("planner"));
        String reservation = Files.readString(
                project.resolve(".warden/runs/do-always/run.json"), StandardCharsets.UTF_8);
        check.contains("the run marker carried that reservation", reservation, "paying_stages");
        check.contains("naming the planner before any vendor ran", reservation, "planner");
        shown.clear();
        DoCommand.Outcome dry = new DoCommand(new ProcessRunner(), narration::add, board, false).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-dry-existing",
                        "HEAD", true, true, false, false, false, true, null, "always"), user);
        check.that("preview of an existing compiled contract succeeds", dry.ok());
        check.eq("preview opens no contract window and sets no waiting card", List.of(), shown);
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) Json.parse(Files.readString(
                project.resolve(".warden/runs/do-always/artifacts/planner.json"),
                StandardCharsets.UTF_8));
        check.that("and the vendor itself saw the reservation (not a plan reconstructed afterwards)",
                Boolean.TRUE.equals(artifact.get("call_plan_reserved_before_dispatch")));

        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        try {
            TaskLoop.Outcome joined = new TaskLoop(new ProcessRunner()).run(
                    loaded, UserConfig.load(home), "do-always", true);
            check.that("a later warden run joins the prepared reservation rather than run_id_exists",
                    !"run_id_exists".equals(joined.reason()));
        } catch (dev.warden.ledger.EvidenceLedger.RunExistsException duplicate) {
            check.that("a later warden run joins the prepared reservation rather than run_id_exists: "
                    + duplicate.getMessage(), false);
        }
        try {
            new TaskLoop(new ProcessRunner()).run(loaded, UserConfig.load(home), "do-always", true);
            check.that("a second controller on the prepared run is still refused", false);
        } catch (dev.warden.ledger.EvidenceLedger.RunExistsException duplicate) {
            check.contains("as the same reservation fence", duplicate.getMessage(), "already reserved");
        }
    }

    @SuppressWarnings("unchecked")
    private void prepareAlwaysGoesToLoop(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("always-loop");
        scaffoldProject(project);
        writeImplementerProfile(home);
        writePolicy(home, "stub-plan", "stub-impl", "confirm");
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-always-loop",
                        "HEAD", true, false, false, false, false, false, null, "always"),
                user);
        check.that("--prepare always without --draft-only reaches the loop",
                outcome.ok() || "ready_for_human".equals(outcome.code()));
        Path marker = project.resolve(".warden/runs/do-always-loop/run.json");
        check.that("the run marker was written", Files.isRegularFile(marker));
        Map<String, Object> reservation = Json.parseObject(
                Files.readString(marker, StandardCharsets.UTF_8));
        Map<String, Object> reservedPlan = (Map<String, Object>) reservation.get("budget_plan");
        check.that("CallPlan reserved a plan before the loop", reservedPlan != null);
        check.eq("the reservation's cap is the run's ceiling, not the bootstrap's one",
                6L, reservedPlan.get("requested_cap"));
        check.eq("and it says this chain can finish",
                Boolean.TRUE, reservedPlan.get("sufficient_for_success"));
        List<?> stages = (List<?>) reservedPlan.get("paying_stages");
        check.that("it names the planner", stages != null && stages.contains("planner"));
        check.that("and the implement stage the loop will dispatch",
                stages != null && stages.contains("implement"));
        check.that("and not the reviewer this low-risk task skips",
                stages != null && !stages.contains("review"));
        check.that("and not the visual role this task does not ask for",
                stages != null && !stages.contains("look"));
        check.eq("minimum_success_calls matches those paying stages",
                (long) stages.size(), reservedPlan.get("minimum_success_calls"));
        Path summary = project.resolve(".warden/runs/do-always-loop/task-run.json");
        check.that("the loop wrote its JSON", Files.isRegularFile(summary));
        Map<String, Object> loopPlan = (Map<String, Object>) Json.parseObject(
                Files.readString(summary, StandardCharsets.UTF_8)).get("budget_plan");
        check.eq("the two records of one run name the same paying stages",
                loopPlan.get("paying_stages"), reservedPlan.get("paying_stages"));
        check.eq("and the same cap",
                loopPlan.get("requested_cap"), reservedPlan.get("requested_cap"));
        writePolicy(home, "stub-plan");
    }

    private void stubDraftRefusals(Check check, Path sandbox, Path home) throws Exception {
        refuseDraft(check, sandbox, home, "plan-shell",
                "a bare shell string as an acceptance entry is refused");
        refuseDraft(check, sandbox, home, "plan-scope",
                "an invented scope is refused");
        refuseDraft(check, sandbox, home, "plan-goal",
                "a draft that replaces the operator's goal is refused");
        refuseDraft(check, sandbox, home, "plan-access",
                "a draft asking for authority the invocation did not grant is refused");
        writePlannerProfile(home, "stub-plan", "plan", true);
    }

    private void schemaIsEnforcedWhenProfileSkips(Check check, Path sandbox, Path home)
            throws Exception {
        writePlannerProfile(home, "stub-plan", "plan-schema", true, "", false);
        Path project = sandbox.resolve("schema-skip");
        scaffoldProject(project);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-schema",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that("a draft that fails the shipped schema is refused even when the profile skipped enforcement",
                !outcome.ok());
        check.eq("as role_artifact_schema_violation, not a compiled contract",
                "role_artifact_schema_violation", outcome.code());
        check.that("and the compiled contract is not written",
                !Files.exists(project.resolve(".warden/tasks/hello.yaml")));
        check.that("the raw output is kept",
                Files.isRegularFile(project.resolve(".warden/runs/do-schema/raw/planner.stdout.txt")));
        writePlannerProfile(home, "stub-plan", "plan", true);
    }

    private void refuseDraft(Check check, Path sandbox, Path home, String mode, String description)
            throws Exception {
        writePlannerProfile(home, "stub-plan", mode, true);
        Path project = sandbox.resolve("refuse-" + mode);
        scaffoldProject(project);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-" + mode,
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that(description, !outcome.ok());
        if ("plan-shell".equals(mode)) {
            check.that("a shell string is a schema or compile refusal, not a written contract",
                    "role_artifact_schema_violation".equals(outcome.code())
                            || Preparation.DRAFT_INVALID.equals(outcome.code()));
        } else {
            check.eq(description + " as planner_draft_invalid",
                    Preparation.DRAFT_INVALID, outcome.code());
        }
        check.that("and the compiled contract is not written for " + mode,
                !Files.exists(project.resolve(".warden/tasks/hello.yaml")));
    }

    private void protocolFailureDiscardsDraft(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("protocol");
        scaffoldProject(project);
        Files.writeString(project.resolve("src/seed.txt"), "operator-edit\n");
        Files.writeString(project.resolve("notes.md"), "keep me\n");
        writePlannerProfile(home, "stub-plan", "plan-sneaky", true);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-sneaky",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that("a planner that writes to the worktree fails", !outcome.ok());
        check.eq("as a protocol failure, not an ordinary P1",
                Preparation.PROTOCOL, outcome.code());
        check.that("and its draft is not used",
                !Files.exists(project.resolve(".warden/tasks/hello.yaml")));
        check.eq("the operator's uncommitted edit survives the protocol restore",
                "operator-edit\n", Files.readString(project.resolve("src/seed.txt")));
        check.eq("and so does an untracked file that predates the run",
                "keep me\n", Files.readString(project.resolve("notes.md")));
        check.that("the file the planner created is gone",
                !Files.exists(project.resolve("src/result.txt")));
        Path run = project.resolve(".warden/runs/do-sneaky");
        check.that("the discarded attempt's prompt survives the retry",
                Files.isRegularFile(run.resolve("prompts/planner.md")));
        check.that("and the retry writes its prompt under its own name",
                Files.isRegularFile(run.resolve("prompts/planner.attempt-2.md")));
        check.that("the discarded attempt's raw stdout survives",
                Files.isRegularFile(run.resolve("raw/planner.stdout.txt")));
        check.that("and the retry's raw stdout is a different file",
                Files.isRegularFile(run.resolve("raw/planner.attempt-2.stdout.txt")));
        check.that("the discarded attempt's role report survives",
                Files.isRegularFile(run.resolve("role-planner.json")));
        check.that("and the retry writes its report under its own name",
                Files.isRegularFile(run.resolve("role-planner.attempt-2.json")));
        writePlannerProfile(home, "stub-plan", "plan", true);
    }

    private void plannerCommitIsNotCarried(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("protocol-commit");
        scaffoldProject(project);
        Files.writeString(project.resolve("src/seed.txt"), "operator-edit\n");
        Files.writeString(project.resolve("notes.md"), "keep me\n");
        String headBefore = new ProcessRunner().run(List.of("git", "rev-parse", "HEAD"),
                project, Duration.ofSeconds(30)).stdout().strip();
        writePlannerProfile(home, "stub-plan", "plan-commit", true,
                yaml("--counter") + ", " + yaml(".warden/runs/do-commit/plan-counter")
                        + ", " + yaml("--threshold") + ", " + yaml("2"));
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-commit",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("a planner that commits is restored and the retry may draft",
                "drafted", outcome.code());
        String headAfter = new ProcessRunner().run(List.of("git", "rev-parse", "HEAD"),
                project, Duration.ofSeconds(30)).stdout().strip();
        check.eq("the planner's commit is not the run's HEAD", headBefore, headAfter);
        check.that("and the file it committed is gone",
                !Files.exists(project.resolve("src/result.txt")));
        check.eq("the operator's uncommitted edit survives a committed protocol failure",
                "operator-edit\n", Files.readString(project.resolve("src/seed.txt")));
        check.eq("and so does an untracked file that predates the run",
                "keep me\n", Files.readString(project.resolve("notes.md")));
        Path run = project.resolve(".warden/runs/do-commit");
        check.that("the discarded committing attempt kept its raw stdout",
                Files.isRegularFile(run.resolve("raw/planner.stdout.txt")));
        check.that("and the successful retry wrote a different raw stdout",
                Files.isRegularFile(run.resolve("raw/planner.attempt-2.stdout.txt")));
        String ledger = Files.readString(run.resolve("evidence.jsonl"), StandardCharsets.UTF_8);
        check.contains("the first ledger event still names the discarded stdout",
                ledger, "raw/planner.stdout.txt");
        check.contains("and the retry's ledger event names its own",
                ledger, "raw/planner.attempt-2.stdout.txt");
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = telemetryCost(project);
        check.that("the protocol-failing call kept its reported cost, so both calls are counted",
                cost.get("total") instanceof Number number && number.doubleValue() >= 0.019);
        writePlannerProfile(home, "stub-plan", "plan", true);
    }

    private void stagedAdditionIsRestored(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("protocol-staged");
        scaffoldProject(project);
        Files.writeString(project.resolve("src/seed.txt"), "operator-edit\n");
        Files.writeString(project.resolve("notes.md"), "keep me\n");
        writePlannerProfile(home, "stub-plan", "plan-stage", true);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-stage",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that("a planner that stages a new file without committing fails", !outcome.ok());
        check.eq("as a protocol failure", Preparation.PROTOCOL, outcome.code());
        check.that("and its draft is not used",
                !Files.exists(project.resolve(".warden/tasks/hello.yaml")));
        check.that("the staged file is gone from the worktree",
                !Files.exists(project.resolve("src/result.txt")));
        ProcessRunner.Result stillStaged = new ProcessRunner().run(
                List.of("git", "diff", "--cached", "--name-only", "--", "src/result.txt"),
                project, Duration.ofSeconds(30));
        check.that("and is gone from the index", stillStaged.stdout().isBlank());
        check.eq("the operator's uncommitted edit survives a staged protocol failure",
                "operator-edit\n", Files.readString(project.resolve("src/seed.txt")));
        check.eq("and so does an untracked file that predates the run",
                "keep me\n", Files.readString(project.resolve("notes.md")));
        writePlannerProfile(home, "stub-plan", "plan", true);
    }

    private void bootstrapRefusesQuotaFailover(Check check, Path sandbox, Path home)
            throws Exception {
        Path project = sandbox.resolve("bootstrap-quota");
        scaffoldProject(project);
        writePlannerProfile(home, "stub-plan-quota", "quota", true);
        writePlannerProfile(home, "stub-plan", "plan", true);
        writePolicy(home, "stub-plan-quota, stub-plan", "codex-implement", "auto");
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-quota",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that("a spent planner does not fail over past the one-call bootstrap", !outcome.ok());
        check.eq("it stops as the spent subscription, not as a drafted contract",
                "role_quota_exhausted", outcome.code());
        Path run = project.resolve(".warden/runs/do-quota");
        check.that("the successor was never dispatched",
                !Files.exists(run.resolve("raw/planner.attempt-2.stdout.txt")));
        check.that("the first call still left its receipt",
                Files.isRegularFile(run.resolve("raw/planner.stdout.txt")));
        check.that("and the draft is not used",
                !Files.exists(project.resolve(".warden/tasks/hello.yaml")));
        String reservation = Files.readString(run.resolve("run.json"), StandardCharsets.UTF_8);
        check.contains("CallPlan still reserved the planner before that call",
                reservation, "planner");
        writePlannerProfile(home, "stub-plan", "plan", true);
        writePolicy(home, "stub-plan");
    }

    private void failoverThenProtocolKeepsEvidenceNames(Check check, Path sandbox, Path home)
            throws Exception {
        Path project = sandbox.resolve("names-failover");
        scaffoldProject(project);
        writePlannerProfile(home, "stub-plan-quota", "quota", true);
        writePlannerProfile(home, "stub-plan-sneaky", "plan-sneaky", true);
        writePolicy(home, "stub-plan-quota, stub-plan-sneaky", "codex-implement", "auto");
        new TaskDraft().write(project, "hello", GOAL, "app", "low");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        UserConfig user = UserConfig.load(home);
        ProcessRunner processes = new ProcessRunner();
        GitRepository git = new GitRepository(project, processes);
        GitRepository.WorkingTreeSnapshot baseline = git.snapshotWorkingTree();
        RoleRunner roles = new RoleRunner(processes).atStage("prepare");
        RoleRunner.Outcome first = roles.forDispatch(1)
                .run(loaded, user, "planner", "do-names", null, null, false);
        check.eq("failover then a mutating successor is a protocol failure",
                "role_violated_read_only", first.code());
        Path run = project.resolve(".warden/runs/do-names");
        check.that("the spent vendor kept its raw stdout",
                Files.isRegularFile(run.resolve("raw/planner.stdout.txt")));
        check.that("and the mutating successor wrote a different one",
                Files.isRegularFile(run.resolve("raw/planner.attempt-2.stdout.txt")));
        git.restoreWorkingTree(baseline);
        RoleRunner.Outcome retry = roles.forDispatch(2)
                .run(loaded, user, "planner", "do-names", null, null, false);
        check.eq("the protocol retry is its own dispatch",
                "role_violated_read_only", retry.code());
        check.that("and did not overwrite the failover attempt's raw stdout",
                Files.isRegularFile(run.resolve("raw/planner.attempt-2.stdout.txt")));
        check.that("writing the retry under a third name",
                Files.isRegularFile(run.resolve("raw/planner.attempt-3.stdout.txt")));
        check.that("the retry's prompt is a different file too",
                Files.isRegularFile(run.resolve("prompts/planner.attempt-3.md")));
        writePlannerProfile(home, "stub-plan", "plan", true);
        writePolicy(home, "stub-plan");
    }

    private void prepareAlwaysDoesNotTruncateExisting(Check check, Path sandbox, Path home)
            throws Exception {
        Path project = sandbox.resolve("no-truncate");
        scaffoldProject(project);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome first = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-trunc-1",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("the first --prepare always drafts", "drafted", first.code());
        Path file = project.resolve(".warden/tasks/hello.yaml");
        String drafted = Files.readString(file, StandardCharsets.UTF_8);
        String handWritten = drafted.replace(
                "required: false\n  scenarios: []",
                "required: true\n  scenarios:\n    - \"1280x720: testid=save visible\"");
        Files.writeString(file, handWritten, StandardCharsets.UTF_8);

        DoCommand.Outcome second = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-trunc-2",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("--prepare always on an existing same-intent contract still drafts",
                "drafted", second.code());
        check.eq("and reports that the task existed", true, second.report().get("task_existed"));
        String kept = Files.readString(file, StandardCharsets.UTF_8);
        check.contains("the hand-written visual contract survives", kept, "testid=save visible");
        check.contains("and visual_qa stays required", kept, "required: true");

        Path conflict = sandbox.resolve("conflict");
        scaffoldProject(conflict);
        Files.writeString(conflict.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: "a different goal"
                risk: low
                scope: app
                checks: fast
                visual_qa:
                  required: false
                  scenarios: []
                """, StandardCharsets.UTF_8);
        DoCommand.Outcome refused = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(conflict, GOAL, "app", "low", "hello", "do-trunc-3",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.that("an existing contract with different intent is refused", !refused.ok());
        check.eq("as task_conflict, naming the file", "task_conflict", refused.code());
        check.contains("and the existing file is not truncated",
                Files.readString(conflict.resolve(".warden/tasks/hello.yaml")),
                "a different goal");
        check.that("and no planner was dispatched to find that out",
                !Files.exists(conflict.resolve(".warden/runs/do-trunc-3/prompts/planner.md")));

        // A contract a person wrote by hand holds the operator's goal and nothing the planner
        // would append, so comparing the compiled goal to it could never match: `--prepare
        // always` used to pay a planner and then refuse its own work as task_conflict.
        // Measured on the Crumb Raiders trial, 2026-09-19. The plan is applied now, and the
        // ceilings that are the operator's - which render() does not write at all - come
        // across with it.
        Path handMade = sandbox.resolve("hand-written");
        scaffoldProject(handMade);
        Path handFile = handMade.resolve(".warden/tasks/hello.yaml");
        new TaskDraft().write(handMade, "hello", GOAL, "app", "low");
        Files.writeString(handFile, Files.readString(handFile, StandardCharsets.UTF_8)
                + """
                budgets:
                  # Five roles before a single repair; six is not enough.
                  max_role_runs: 11
                  max_cost_usd: 33.0
                max_fix_attempts: 3
                """, StandardCharsets.UTF_8);
        DoCommand.Outcome replanned = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(handMade, GOAL, "app", "low", "hello", "do-replan",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("--prepare always over a hand-written contract drafts rather than conflicting",
                "drafted", replanned.code());
        String replannedYaml = Files.readString(handFile, StandardCharsets.UTF_8);
        check.contains("the plan is applied", replannedYaml, "checks: fast");
        check.contains("the operator's call ceiling survives it", replannedYaml, "max_role_runs: 11");
        check.contains("with the comment that explains the number", replannedYaml,
                "Five roles before a single repair");
        check.contains("and so does the repair ceiling", replannedYaml, "max_fix_attempts: 3");
        check.eq("the contract still parses", "hello",
                TaskSpec.parse(replannedYaml, handFile.toString()).id());
        check.eq("and the ceiling is what the loop would read", 11L,
                TaskSpec.parse(replannedYaml, handFile.toString()).budget().maxRoleRuns());
    }

    /**
     * Two shapes of a hand-written ceiling that {@code --prepare always} used to drop:
     * CRLF on the key line, and a comment sitting directly above {@code budgets:}.
     */
    private void operatorBlocksSurviveCrlfAndComments(Check check, Path sandbox, Path home)
            throws Exception {
        ProjectConfig projectCfg = ProjectConfig.parse("""
                version: 1
                project: demo
                checks:
                  fast: ["echo ok"]
                scopes:
                  app: ["src"]
                """, "project.yaml");
        PlannerDraft.Access granted = PlannerDraft.Access.DO_DEFAULT;

        Path crlf = sandbox.resolve("crlf-blocks");
        scaffoldProject(crlf);
        PlannerDraft.write(crlf, "hello", GOAL, projectCfg, granted, draft(Map.of()));
        Path crlfFile = crlf.resolve(".warden/tasks/hello.yaml");
        String crlfExisting = Files.readString(crlfFile, StandardCharsets.UTF_8);
        if (!crlfExisting.endsWith("\n")) crlfExisting += "\n";
        Files.writeString(crlfFile, crlfExisting + "budgets:\r\n  max_cost_usd: 0.05\r\n",
                StandardCharsets.UTF_8);
        PlannerDraft.write(crlf, "hello", GOAL, projectCfg, granted, draft(Map.of()));
        String crlfMerged = Files.readString(crlfFile, StandardCharsets.UTF_8);
        check.contains("a CRLF budgets block survives replan", crlfMerged, "max_cost_usd: 0.05");
        check.eq("and the loop would still read the ceiling", 0.05,
                TaskSpec.parse(crlfMerged, crlfFile.toString()).budget().maxCostUsd());

        Path commented = sandbox.resolve("comment-blocks");
        scaffoldProject(commented);
        PlannerDraft.write(commented, "hello", GOAL, projectCfg, granted, draft(Map.of()));
        Path commentedFile = commented.resolve(".warden/tasks/hello.yaml");
        String commentedExisting = Files.readString(commentedFile, StandardCharsets.UTF_8);
        if (!commentedExisting.endsWith("\n")) commentedExisting += "\n";
        Files.writeString(commentedFile, commentedExisting
                + "# Operator ceiling\nbudgets:\n  max_cost_usd: 0.05\n",
                StandardCharsets.UTF_8);
        PlannerDraft.write(commented, "hello", GOAL, projectCfg, granted, draft(Map.of()));
        String commentedMerged = Files.readString(commentedFile, StandardCharsets.UTF_8);
        check.contains("a comment above budgets is kept with the block", commentedMerged,
                "Operator ceiling");
        check.contains("and the ceiling under it survives replan", commentedMerged,
                "max_cost_usd: 0.05");
        check.eq("so the money cap is still what the person wrote", 0.05,
                TaskSpec.parse(commentedMerged, commentedFile.toString()).budget().maxCostUsd());
    }

    private void prepareAlwaysVisualParity(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("visual-parity");
        scaffoldProject(project);
        Files.writeString(project.resolve("package.json"),
                "{\"scripts\": {\"preview\": \"vite preview\"}}\n");
        String uiGoal = "Add a Settings button";
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, uiGoal, "app", "low", "hello", "do-visual",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("--prepare always on a UI goal still drafts", "drafted", outcome.code());
        String yaml = Files.readString(project.resolve(".warden/tasks/hello.yaml"),
                StandardCharsets.UTF_8);
        check.contains("and is not weaker than --prepare off: visual_qa is required",
                yaml, "required: true");
        check.contains("with a scenario for the control the goal names", yaml, "testid=settings");
        check.contains("and the preview this project actually serves", yaml, "npm run preview");
    }

    @SuppressWarnings("unchecked")
    private void ledgerCountsPlanner(Check check, Path sandbox, Path home) throws Exception {
        Path project = sandbox.resolve("ledger");
        scaffoldProject(project);
        UserConfig user = UserConfig.load(home);
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "low", "hello", "do-ledger",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                user);
        check.eq("the ledger run drafted", "drafted", outcome.code());
        Map<String, Object> summary = new LedgerReader().summarize(project);
        Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
        Map<String, Object> roleRuns = (Map<String, Object>) metrics.get("role_runs");
        Map<String, Object> byRole = (Map<String, Object>) roleRuns.get("by_role");
        check.eq("the planner's call appears under by_role.planner", 1L, byRole.get("planner"));
        Map<String, Object> telemetry = (Map<String, Object>) metrics.get("telemetry");
        Map<String, Object> cost = (Map<String, Object>) telemetry.get("cost_usd");
        check.that("and its cost is counted, not reported as free",
                cost.get("total") instanceof Number number && number.doubleValue() > 0);
    }

    private static Map<String, Object> draft(Map<String, Object> overrides) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("role", "planner");
        artifact.put("task_id", "hello");
        artifact.put("status", "completed");
        artifact.put("operator_goal", GOAL);
        artifact.put("task_kind", "feature");
        artifact.put("deliverable", "src/result.txt containing ok");
        artifact.put("non_goals", List.of());
        artifact.put("subtasks", List.of());
        artifact.put("acceptance", List.of(Map.of("check", "fast")));
        artifact.put("target", "local");
        artifact.put("required_access", Map.of("workspace_write", true, "network", false, "land", false));
        artifact.put("risk", "low");
        artifact.put("scope", "app");
        artifact.put("estimate", Map.of("rationale", "small"));
        artifact.put("stop_conditions", List.of());
        artifact.putAll(overrides);
        return artifact;
    }

    private void scaffoldProject(Path project) throws Exception {
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        String existsCheck = WINDOWS
                ? "if exist src\\\\result.txt (exit /b 0) else (exit /b 1)"
                : "test -f src/result.txt";
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: demo
                base_ref: HEAD
                checks:
                  fast: ["%s"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """.formatted(existsCheck));
        Files.writeString(project.resolve("src/seed.txt"), "seed\n");
        git(project, "init", "-q", "-b", "main", ".");
        git(project, "config", "user.email", "test@example.invalid");
        git(project, "config", "user.name", "test");
        git(project, "add", "-A");
        git(project, "commit", "-qm", "base");
    }

    private void writePolicy(Path home, String planner) throws IOException {
        writePolicy(home, planner, "codex-implement", "confirm");
    }

    /**
     * The second planner reads the compiled contract, not the draft; a blocking objection
     * sends the first planner back once; a second one stops for a person; every reading and
     * every redraft is a counted call.
     */
    @SuppressWarnings("unchecked")
    private void planReviewChecks(Check check, Path sandbox, Path home) throws Exception {
        check.that("setup ships the plan-reviewer prompt",
                Files.isRegularFile(home.resolve("prompts/plan-reviewer.md")));
        check.that("and its schema", Files.isRegularFile(home.resolve("schemas/plan-reviewer.json")));
        check.that("and an unverified agy template for it",
                Files.isRegularFile(home.resolve("profiles/agy-plan-review.yaml")));
        check.that("the shipped template parses as a plan_reviewer profile",
                "plan_reviewer".equals(Profile.parse(
                        Files.readString(home.resolve("profiles/agy-plan-review.yaml")),
                        "agy-plan-review.yaml").role()));

        writePlannerProfile(home, "stub-plan", "plan", true);
        writeReviewerProfile(home, "stub-plan-review", "plan-review-pass",
                sandbox.resolve("plan-review-pass.count"), 1);
        writeReviewingPolicy(home, "stub-plan", "stub-plan-review");

        Path passing = sandbox.resolve("plan-review-pass");
        scaffoldProject(passing);
        DoCommand.Outcome passed = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(passing, GOAL, "app", "low", "hello", "do-pr-pass",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                UserConfig.load(home));
        check.that("a reviewed draft still stops at --draft-only", passed.ok());
        Map<String, Object> review = (Map<String, Object>) passed.report().get("plan_review");
        check.eq("the second planner passed", "pass", review == null ? null : review.get("verdict"));
        check.eq("in one round", 1L, review == null ? null : review.get("rounds"));
        Map<String, Object> reservation = Json.parseObject(Files.readString(
                passing.resolve(".warden/runs/do-pr-pass/run.json")));
        Map<String, Object> reservedPlan = (Map<String, Object>) reservation.get("budget_plan");
        check.eq("the reservation names both readings", List.of("planner", "plan-review"),
                reservedPlan.get("paying_stages"));
        check.eq("two calls were counted for preparation", 2L,
                reservation.get("preparation_role_runs"));
        String reviewerArtifact = Files.readString(
                passing.resolve(".warden/runs/do-pr-pass/artifacts/plan_reviewer.json"));
        check.contains("the reviewer was handed the compiled contract", reviewerArtifact,
                "saw_contract=true");

        writeReviewerProfile(home, "stub-plan-review", "plan-review-once",
                sandbox.resolve("plan-review-once.count"), 2);
        Path redrafted = sandbox.resolve("plan-review-once");
        scaffoldProject(redrafted);
        DoCommand.Outcome redraft = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(redrafted, GOAL, "app", "low", "hello", "do-pr-once",
                        "HEAD", true, false, false, false, false, true, null, "always"),
                UserConfig.load(home));
        check.that("an objection answered by the redraft still drafts", redraft.ok());
        Map<String, Object> twice = (Map<String, Object>) redraft.report().get("plan_review");
        check.eq("the reviewer read twice", 2L, twice.get("rounds"));
        check.eq("and passed the redraft", "pass", twice.get("verdict"));
        check.eq("four calls: draft, read, redraft, read", 4L, Json.parseObject(Files.readString(
                redrafted.resolve(".warden/runs/do-pr-once/run.json"))).get("preparation_role_runs"));
        check.that("the redraft was briefed with the objection",
                Files.readString(redrafted.resolve(".warden/runs/do-pr-once/context/prepare-redraft.md"))
                        .contains("the acceptance cannot fail for the goal"));
        check.that("and the contract on disk is the redraft's",
                Files.isRegularFile(redrafted.resolve(".warden/tasks/hello.yaml")));

        writeReviewerProfile(home, "stub-plan-review", "plan-review-fail",
                sandbox.resolve("plan-review-fail.count"), 1);
        Path refused = sandbox.resolve("plan-review-fail");
        scaffoldProject(refused);
        DoCommand.Outcome stopped = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(refused, GOAL, "app", "low", "hello", "do-pr-fail",
                        "HEAD", true, false, false, false, false, false, null, "always"),
                UserConfig.load(home));
        check.that("a second objection stops before any writer", !stopped.ok());
        check.eq("under its own name", "plan_review_findings_remain", stopped.code());
        check.contains("naming the finding", String.valueOf(stopped.report().get("message")),
                "the acceptance cannot fail for the goal");
        check.eq("after four counted calls", 4L, stopped.report().get("preparation_role_runs"));
        check.that("and no implementer ran",
                !Files.exists(refused.resolve(".warden/runs/do-pr-fail/role-implementer.json")));

        writePolicy(home, "stub-plan");
    }

    /**
     * P2PLAN-17. The reservation is measured before any contract exists, against the
     * invocation's risk; the planner then compiles a contract with another risk, and the
     * loop measures that. The record a person reads before the loop must name the stages
     * the loop will pay for, and must keep the earlier estimate as an estimate.
     */
    @SuppressWarnings("unchecked")
    private void reservationMeasuresTheContract(Check check, Path sandbox, Path home) throws Exception {
        writePlannerProfile(home, "stub-plan", "plan", true);
        writeImplementerProfile(home);
        writePolicy(home, "stub-plan", "stub-impl", "confirm");
        Path project = sandbox.resolve("reservation-risk");
        scaffoldProject(project);
        // The invocation says medium, so the reservation reserves a review. The stub planner
        // compiles the contract as low, so the loop skips it.
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(
                new DoCommand.Options(project, GOAL, "app", "medium", "hello", "do-res-risk",
                        "HEAD", true, false, false, false, false, false, null, "always"),
                UserConfig.load(home));
        check.that("the run reaches the loop", outcome.ok() || "ready_for_human".equals(outcome.code()));
        Map<String, Object> reservation = Json.parseObject(Files.readString(
                project.resolve(".warden/runs/do-res-risk/run.json")));
        Map<String, Object> reservedPlan = (Map<String, Object>) reservation.get("budget_plan");
        Map<String, Object> estimate = (Map<String, Object>) reservation.get("budget_plan_at_reservation");
        check.that("the pre-contract estimate is kept", estimate != null);
        check.eq("under its own phase", "reserve", estimate == null ? null : estimate.get("phase"));
        check.eq("and named as measured against the invocation", "invocation",
                estimate == null ? null : estimate.get("measured_against"));
        check.that("the estimate reserved a review for the medium-risk invocation",
                estimate != null && String.valueOf(estimate.get("paying_stages")).contains("review"));
        check.eq("the plan in force is measured against the compiled contract", "compiled_contract",
                reservedPlan.get("measured_against"));
        check.that("and no longer names the review the low-risk contract skips",
                !String.valueOf(reservedPlan.get("paying_stages")).contains("review"));
        check.eq("the difference is recorded, not hidden", Boolean.FALSE,
                reservation.get("reservation_matched_contract"));
        Map<String, Object> summary = Json.parseObject(Files.readString(
                project.resolve(".warden/runs/do-res-risk/task-run.json")));
        Map<String, Object> loopPlan = (Map<String, Object>) summary.get("budget_plan");
        check.eq("run.json and task-run.json name the same paying stages",
                loopPlan.get("paying_stages"), reservedPlan.get("paying_stages"));
        check.eq("and the summary carries the estimate too", "reserve",
                ((Map<String, Object>) summary.get("budget_plan_at_reservation")).get("phase"));
        check.eq("with the mismatch visible", Boolean.FALSE, summary.get("reservation_matched_contract"));
        writePolicy(home, "stub-plan");
    }

    /**
     * The supervisor's half of `review.contract_gaps: plan`: a run stopped for the planner is
     * amended by it, read by the plan reviewer, and answered `retry` by Warden itself, so the
     * chain goes on without a person typing anything.
     */
    @SuppressWarnings("unchecked")
    private void contractAmendmentChecks(Check check, Path sandbox, Path home) throws Exception {
        writePlannerProfile(home, "stub-plan-amend", "plan-amend", true);
        writeReviewerProfile(home, "stub-plan-review", "plan-review-pass",
                sandbox.resolve("amend-review.count"), 1);
        writeReviewingPolicy(home, "stub-plan-amend", "stub-plan-review");
        Path project = sandbox.resolve("amend");
        scaffoldProject(project);
        Path task = project.resolve(".warden/tasks/hello.yaml");
        Files.writeString(task, """
                version: 1
                id: hello
                goal: "%s"
                risk: low
                scope: app
                checks: fast
                visual_qa:
                  required: true
                  # the operator's own browser contract
                  start: "python -m http.server 4173 --bind 127.0.0.1"
                  url: "http://127.0.0.1:4173/"
                  scenarios:
                    - "1280x720: testid=greeting visible"
                """.formatted(GOAL));
        Path summaryFile = project.resolve(".warden/runs/gap-1/task-run.json");
        Files.createDirectories(summaryFile.getParent());
        Map<String, Object> summary = new LinkedHashMap<>(Map.of("run_id", "gap-1", "task_id", "hello",
                "reason", TaskLoop.CONTRACT_AMENDMENT, "decision_kind", "failure",
                "finding_history", List.of(Map.of("stage", "review", "attempt", 0, "findings", List.of(
                        Map.of("id", "GAP-1", "severity", "P3", "category", "contract_gap", "status", "open",
                                "message", "the acceptance never checks the text"))))));
        Files.writeString(summaryFile, Json.write(summary));
        new dev.warden.approval.ApprovalStore(project).createFailure("gap-1", "hello",
                TaskLoop.CONTRACT_AMENDMENT, summaryFile, null);
        Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis, millis -> { },
                (path, gate) -> { throw new IllegalStateException("no gate here"); }, home);

        Map<String, Object> report = Main.amendContract(project, "gap-1", summary,
                new String[] {"--no-workspace-status", "--quiet"}, env);
        Map<String, Object> amendment = (Map<String, Object>) report.get("contract_amendment");
        check.eq("the planner's amendment went through", true, amendment == null ? null : amendment.get("ok"));
        String after = Files.readString(task);
        TaskSpec amended = TaskSpec.parse(after, task.toString());
        check.eq("the scenario it added follows the operator's own", List.of(
                "1280x720: testid=greeting visible",
                "1280x720: testid=greeting visible -> text=Hello, World visible"),
                amended.visualQa().scenarios());
        check.contains("with the operator's comment and start command kept", after,
                "# the operator's own browser contract");
        var decided = new dev.warden.approval.ApprovalStore(project).read("gap-1");
        check.eq("Warden answered the pending decision with retry", "retry", decided.decision());
        check.eq("as itself, not as a person", "warden:contract-amendment", decided.actor());
        check.contains("naming the gap it amended for", String.valueOf(decided.note()), "GAP-1");
        Map<String, Object> receipt = Json.parseObject(Files.readString(
                project.resolve(".warden/runs/gap-1").resolve(TaskLoop.AMENDMENT_RECEIPT)));
        check.eq("what the amendment spent is written for the chain", "settled", receipt.get("state"));
        check.eq("the planner and the plan reviewer, both counted", 2L,
                ((Number) receipt.get("role_runs")).longValue());

        // A plan reviewer that never read the amendment — here, a spent quota — is not a pass,
        // and the amendment was written to the real file for it to read. That text must not
        // stay the contract after Warden said the contract is as it was.
        writeReviewerProfile(home, "stub-plan-review", "quota", sandbox.resolve("amend-quota.count"), 1);
        Path unread = sandbox.resolve("amend-unread");
        scaffoldProject(unread);
        Path unreadTask = unread.resolve(".warden/tasks/hello.yaml");
        String inForce = """
                version: 1
                id: hello
                goal: "%s"
                risk: low
                scope: app
                checks: fast
                visual_qa:
                  required: true
                  start: "python -m http.server 4173 --bind 127.0.0.1"
                  url: "http://127.0.0.1:4173/"
                  scenarios:
                    - "1280x720: testid=greeting visible"
                """.formatted(GOAL);
        Files.writeString(unreadTask, inForce);
        Path unreadSummary = unread.resolve(".warden/runs/gap-1/task-run.json");
        Files.createDirectories(unreadSummary.getParent());
        Files.writeString(unreadSummary, Json.write(summary));
        new dev.warden.approval.ApprovalStore(unread).createFailure("gap-1", "hello",
                TaskLoop.CONTRACT_AMENDMENT, unreadSummary, null);
        Map<String, Object> refused = (Map<String, Object>) Main.amendContract(unread, "gap-1", summary,
                new String[] {"--no-workspace-status", "--quiet"}, env).get("contract_amendment");
        check.eq("an amendment nobody reviewed does not go through", false,
                refused == null ? null : refused.get("ok"));
        check.eq("and the contract is exactly as it was", inForce, Files.readString(unreadTask));
        check.eq("the decision is left to a person", dev.warden.approval.HumanDecision.State.PENDING,
                new dev.warden.approval.ApprovalStore(unread).read("gap-1").state());

        // The chain pays for the amendment, so the chain's ceilings bound each of its calls,
        // not only the loop's decision to route there. Money: the planner's $0.01 takes the
        // chain to its ceiling, and the plan reviewer must not start after it.
        writeReviewerProfile(home, "stub-plan-review", "plan-review-pass",
                sandbox.resolve("amend-spent-review.count"), 1);
        Path spent = amendmentProject(sandbox.resolve("amend-spent"), inForce
                + "budgets:\n  max_role_runs: 40\n  max_cost_usd: 0.5\n", summary,
                Map.of("runs", List.of("gap-1"), "role_runs", 3L, "cost_usd", 0.495,
                        "elapsed_seconds", 100L, "elapsed_known", true));
        Map<String, Object> overBudget = (Map<String, Object>) Main.amendContract(spent, "gap-1", summary,
                new String[] {"--no-workspace-status", "--quiet"}, env).get("contract_amendment");
        check.eq("a plan reviewer is not dispatched once the planner spent the chain's money",
                "budget_exhausted", overBudget == null ? null : overBudget.get("code"));
        check.eq("the unreviewed amendment is withdrawn", inForce + "budgets:\n  max_role_runs: 40\n"
                + "  max_cost_usd: 0.5\n", Files.readString(spent.resolve(".warden/tasks/hello.yaml")));
        check.eq("and the receipt counts the planner alone: the reviewer was never paid", 1L, ((Number) Json.parseObject(Files.readString(
                spent.resolve(".warden/runs/gap-1").resolve(TaskLoop.AMENDMENT_RECEIPT)))
                .get("role_runs")).longValue());

        // Time: 90 seconds of the chain's ten minutes are left, and the planner's profile
        // declares two. The call gets what the chain has, not what the profile allows.
        Path late = amendmentProject(sandbox.resolve("amend-late"), inForce
                + "budgets:\n  max_role_runs: 40\n  max_cost_usd: 5.0\n  max_elapsed_minutes: 10\n", summary,
                Map.of("runs", List.of("gap-1"), "role_runs", 3L, "cost_usd", 0.1,
                        "elapsed_seconds", 510L, "elapsed_known", true));
        Main.amendContract(late, "gap-1", summary, new String[] {"--no-workspace-status", "--quiet"}, env);
        boolean capped;
        try (var files = Files.walk(late.resolve(".warden/runs/gap-1-amend"))) {
            capped = files.filter(Files::isRegularFile).anyMatch(file -> {
                try {
                    return Files.readString(file).contains("\"wall_clock_capped_by\"");
                } catch (IOException unreadable) {
                    return false;
                }
            });
        }
        check.that("the planner's wall clock is lowered to what the chain's deadline leaves", capped);
        writePolicy(home, "stub-plan");
    }

    /** A project whose run gap-1 stopped for a contract amendment, with the chain it spent. */
    private Path amendmentProject(Path project, String contract, Map<String, Object> summary,
                                  Map<String, Object> chain) throws Exception {
        scaffoldProject(project);
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), contract);
        Path summaryFile = project.resolve(".warden/runs/gap-1/task-run.json");
        Files.createDirectories(summaryFile.getParent());
        Map<String, Object> withChain = new LinkedHashMap<>(summary);
        withChain.put("chain", chain);
        Files.writeString(summaryFile, Json.write(withChain));
        new dev.warden.approval.ApprovalStore(project).createFailure("gap-1", "hello",
                TaskLoop.CONTRACT_AMENDMENT, summaryFile, null);
        return project;
    }

    private void writeReviewingPolicy(Path home, String planner, String reviewer) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  planner:
                    profiles: [%s]
                    strategy: first
                    require_independent_vendor: false
                  plan_reviewer:
                    profiles: [%s]
                    strategy: first
                    require_independent_vendor: false
                  implementer:
                    profiles: [codex-implement]
                    strategy: first
                    require_independent_vendor: false
                  reviewer:
                    profiles: [grok-review]
                    strategy: first
                    require_independent_vendor: true
                review:
                  required_for_risk: [medium, high]
                """.formatted(planner, reviewer));
    }

    private void writeReviewerProfile(Path home, String name, String stubMode, Path counter,
                                      int threshold) throws IOException {
        Files.deleteIfExists(counter);
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: plan_reviewer
                vendor: reviewvendor
                command: %s
                read_only: true
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "%s", "--counter", %s, "--threshold", "%d", "--prompt-file", "{{prompt_file}}"]
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/plan-reviewer.md
                json_schema: schemas/plan-reviewer.json
                artifact:
                  required_fields: [role, task_id, status, verdict, summary, findings]
                verification:
                  verified_on: "2026-09-18"
                """.formatted(name, yaml(javaExecutable()), yaml(absoluteClassPath()), stubMode,
                yaml(counter.toString()), threshold));
    }

    private void writePolicy(Path home, String planner, String implementer, String failover)
            throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  planner:
                    profiles: [%s]
                    strategy: first
                    require_independent_vendor: false
                  implementer:
                    profiles: [%s]
                    strategy: first
                    require_independent_vendor: false
                  reviewer:
                    profiles: [grok-review]
                    strategy: first
                    require_independent_vendor: true
                review:
                  required_for_risk: [medium, high]
                failover: { on_quota_exhausted: %s }
                """.formatted(planner, implementer, failover));
    }

    private void writeImplementerProfile(Path home) throws IOException {
        Files.writeString(home.resolve("profiles/stub-impl.yaml"), """
                version: 1
                profile: stub-impl
                role: implementer
                vendor: stubvendor
                command: %s
                read_only: false
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "impl", "--prompt-file", "{{prompt_file}}"]
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/implementer.md
                json_schema: schemas/implementer.json
                artifact:
                  required_fields: [role, task_id, status, summary, files_changed]
                verification:
                  verified_on: "2026-09-10"
                """.formatted(yaml(javaExecutable()), yaml(absoluteClassPath())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> telemetryCost(Path project) throws Exception {
        Map<String, Object> summary = new LedgerReader().summarize(project);
        Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
        Map<String, Object> telemetry = (Map<String, Object>) metrics.get("telemetry");
        return (Map<String, Object>) telemetry.get("cost_usd");
    }

    private void writePlannerProfile(Path home, String name, String stubMode, boolean verified)
            throws IOException {
        writePlannerProfile(home, name, stubMode, verified, "", true);
    }

    private void writePlannerProfile(Path home, String name, String stubMode, boolean verified,
                                     String extraArgs) throws IOException {
        writePlannerProfile(home, name, stubMode, verified, extraArgs, true);
    }

    private void writePlannerProfile(Path home, String name, String stubMode, boolean verified,
                                     String extraArgs, boolean enforceSchema) throws IOException {
        String verification = verified
                ? "  verified_on: \"2026-09-10\"\n"
                : "  probe: 'run it once'\n";
        String extras = extraArgs == null || extraArgs.isBlank() ? "" : ", " + extraArgs;
        String enforce = enforceSchema ? "" : "                enforce_schema: false\n";
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: planner
                vendor: stubvendor
                command: %s
                read_only: true
                args: ["-cp", %s, "dev.warden.testing.StubVendor", "%s"%s, "--prompt-file", "{{prompt_file}}"]
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/planner.md
                json_schema: schemas/planner.json
%s                artifact:
                  required_fields: [role, task_id, status, operator_goal, task_kind, deliverable, non_goals, subtasks, acceptance, target, required_access, risk, estimate, stop_conditions, scope]
                verification:
                %s
                """.formatted(name, yaml(javaExecutable()), yaml(absoluteClassPath()), stubMode,
                extras, enforce, verification));
    }

    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        String[] entries = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator));
        StringBuilder builder = new StringBuilder();
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
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
                if (WINDOWS) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
