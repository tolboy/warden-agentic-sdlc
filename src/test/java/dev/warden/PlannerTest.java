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
            prepareAlwaysVisualParity(check, sandbox, home);
            stubDraftRefusals(check, sandbox, home);
            schemaIsEnforcedWhenProfileSkips(check, sandbox, home);
            protocolFailureDiscardsDraft(check, sandbox, home);
            plannerCommitIsNotCarried(check, sandbox, home);
            stagedAdditionIsRestored(check, sandbox, home);
            bootstrapRefusesQuotaFailover(check, sandbox, home);
            failoverThenProtocolKeepsEvidenceNames(check, sandbox, home);
            ledgerCountsPlanner(check, sandbox, home);
        } finally {
            deleteTree(sandbox);
        }
    }

    private void parseAndResolve(Check check) {
        DoCommand.Options parsed = DoCommand.parse(new String[] {
                "do", "--prepare", "always", "--draft-only", "--in-place", "Add a button"
        });
        check.eq("--prepare always is accepted", "always", parsed.prepare());
        check.that("and --draft-only is still honoured", parsed.draftOnly());
        check.eq("--prepare defaults to off so existing projects do not change",
                "off", DoCommand.parse(new String[] {"do", "--in-place", "Add a button"}).prepare());
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
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner(), narration::add).run(
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
