package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.VisualQaRunner;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The bounded loop.
 *
 * These are the difference between "there is a loop" and "the loop terminates, stays inside
 * its budget, and hands a human the right thing when it gives up".
 */
public final class TaskLoopTest implements Suite {

    @Override public String name() { return "task loop"; }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-loop-test-");
        try {
            Path home = sandbox.resolve("home");
            new UserSetup().run(home);

            // Clean run: implement, gates pass, review passes, stop for a human.
            Path clean = newProject(sandbox, "clean");
            writeProfiles(home, sandbox, "clean", 1, 1);
            TaskLoop.Outcome ok = loop(clean, home, "r1");
            check.that("a clean run succeeds", ok.ok());
            check.eq("and stops at the human gate", "human_gate", ok.nextAction());
            check.eq("no fix rounds were needed", 0L, ok.summaryReport().get("attempts_used"));
            check.that("the orchestrator lands nothing itself",
                    !Files.exists(clean.resolve(".git/MERGE_HEAD")));
            check.that("cost rolls up to the task, not just the role",
                    ((Number) ok.summaryReport().get("total_cost_usd")).doubleValue() > 0);
            String evidenceBeforeDuplicate = Files.readString(
                    clean.resolve(".warden/runs/r1/evidence.jsonl"));
            check.rejects("a duplicate workflow id is fenced before another dispatch",
                    "run id already reserved", () -> loop(clean, home, "r1"));
            check.eq("duplicate workflow leaves existing evidence byte-for-byte unchanged",
                    evidenceBeforeDuplicate,
                    Files.readString(clean.resolve(".warden/runs/r1/evidence.jsonl")));

            // A failing gate sends the work back with the machine output attached.
            Path fixable = newProject(sandbox, "fixable");
            writeProfiles(home, sandbox, "fixable", 2, 1);
            TaskLoop.Outcome fixed = loop(fixable, home, "r2");
            check.that("a fixable failure still ends green", fixed.ok());
            check.eq("exactly one fix round was used", 1L, fixed.summaryReport().get("attempts_used"));
            String gateContext = Files.readString(
                    fixable.resolve(".warden/runs/r2/context/fix-1-gates.md"));
            check.contains("the implementer is handed the machine failure", gateContext, "Machine gate failed");
            check.contains("and told not to edit the check instead", gateContext, "Fix the cause, not the check");

            // Give up rather than loop for ever.
            Path hopeless = newProject(sandbox, "hopeless");
            writeProfiles(home, sandbox, "hopeless", 99, 1);
            TaskLoop.Outcome gaveUp = loop(hopeless, home, "r3");
            check.that("an unfixable failure stops", !gaveUp.ok());
            check.eq("with the reason named", "gates_not_satisfied", gaveUp.reason());
            check.eq("and escalates to a human", "human_escalation", gaveUp.nextAction());

            // A blocking review sends the work back with expected/actual attached.
            Path reviewed = newProject(sandbox, "reviewed");
            writeProfiles(home, sandbox, "reviewed", 1, 2);
            TaskLoop.Outcome afterReview = loop(reviewed, home, "r4");
            check.that("a blocking review that is addressed ends green", afterReview.ok());
            String reviewContext = Files.readString(
                    reviewed.resolve(".warden/runs/r4/context/fix-1-review.md"));
            check.contains("the finding's expectation is handed back", reviewContext, "expected: the final content");
            check.contains("and its actual", reviewContext, "actual: an intermediate one");

            // Blocking findings that survive the budget escalate rather than merge.
            Path blocked = newProject(sandbox, "blocked");
            writeProfiles(home, sandbox, "blocked", 1, 99);
            TaskLoop.Outcome stillBlocked = loop(blocked, home, "r5");
            check.that("surviving P1s stop the run", !stillBlocked.ok());
            check.eq("with the reason named", "blocking_findings_remain", stillBlocked.reason());

            // Low risk pays no reviewer, but still passes every machine gate.
            Path cheap = newProject(sandbox, "cheap", "low");
            writeProfiles(home, sandbox, "cheap", 1, 99);
            TaskLoop.Outcome lowRisk = loop(cheap, home, "r6");
            check.that("a low-risk task still succeeds", lowRisk.ok());
            check.eq("and skips the reviewer entirely", Boolean.FALSE,
                    lowRisk.summaryReport().get("review_required"));
            check.that("no reviewer step was recorded",
                    steps(lowRisk).stream().noneMatch(step -> "reviewer".equals(step.get("step"))));

            // The budget is enforced before dispatch, not discovered by paying for it.
            Path broke = newProject(sandbox, "broke", "medium", 1);
            writeProfiles(home, sandbox, "broke", 99, 1);
            TaskLoop.Outcome exhausted = loop(broke, home, "r7");
            check.that("an exhausted budget stops the run", !exhausted.ok());
            check.eq("with the reason named", "budget_exhausted", exhausted.reason());
            check.contains("and says which limit was hit",
                    String.valueOf(exhausted.summaryReport().get("budget_stop")), "max_role_runs");

            // A spent subscription is routed around, and the extra dispatch is charged. A
            // failover that the budget did not see would let one task pay for three.
            Path failover = newProject(sandbox, "failover");
            writeProfiles(home, sandbox, "failover", 1, 1);
            writeQuotaProfile(home, sandbox, "failover");
            policy(home, "loop-review", "loop-spent, loop-impl");
            TaskLoop.Outcome routed = loop(failover, home, "r8");
            check.that("a loop whose implementer ran out still finishes", routed.ok());
            check.eq("and every vendor call is counted, the abandoned one included",
                    3L, routed.summaryReport().get("role_runs"));
            Map<String, Object> implStep = steps(routed).stream()
                    .filter(step -> "implementer".equals(step.get("step"))).findFirst().orElseThrow();
            check.eq("the summary names the profile that ran out",
                    List.of("loop-spent"), implStep.get("failed_over_from"));

            // Nothing left to fall back to. This is the case the loop used to report as a
            // failed implementer, sending an operator to read a transcript with no defect in it.
            Path stranded = newProject(sandbox, "stranded");
            writeProfiles(home, sandbox, "stranded", 1, 1);
            writeQuotaProfile(home, sandbox, "stranded");
            policy(home, "loop-review", "loop-spent");
            TaskLoop.Outcome ranOut = loop(stranded, home, "r9");
            check.that("a loop with no vendor left stops", !ranOut.ok());
            check.eq("and calls it what it is", "quota_exhausted", ranOut.reason());
            check.eq("still handing off to a human rather than waiting out the window",
                    "human_escalation", ranOut.nextAction());
            check.contains("the vendor's own message reaches the summary",
                    Json.write(steps(ranOut)), "try again at 9:21 PM");

            declaredWorkflowChecks(check, sandbox, home);
            visualLoopChecks(check, sandbox, home);

            // Whatever happens, the loop only ever hands off to a human.
            for (TaskLoop.Outcome outcome : List.of(ok, fixed, gaveUp, afterReview, stillBlocked,
                    lowRisk, exhausted, routed, ranOut)) {
                check.that("every outcome hands off to a human, never to a merge",
                        outcome.nextAction().equals("human_gate")
                                || outcome.nextAction().equals("human_escalation"));
            }
        } finally {
            deleteTree(sandbox);
        }
    }

    /**
     * The chain is declared, so changing the declaration has to change the run. A workflow
     * that parses but is not the one executed would be the worst of both worlds: an operator
     * reading a file that describes a loop nobody runs.
     */
    @SuppressWarnings("unchecked")
    private void declaredWorkflowChecks(Check check, Path sandbox, Path home) throws Exception {
        // Same medium-risk task, same profiles, same policy roles — only the chain differs.
        Path dropped = newProject(sandbox, "workflow-no-review");
        writeProfiles(home, sandbox, "workflow-no-review", 1, 99);
        workflowPolicy(home, """
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                """);
        TaskLoop.Outcome unreviewed = loop(dropped, home, "w1");
        check.that("a chain without a review stage finishes without one", unreviewed.ok());
        check.that("and no reviewer was dispatched despite a reviewer role being configured",
                steps(unreviewed).stream().noneMatch(step -> "reviewer".equals(step.get("step"))));
        check.eq("the summary records the chain that actually ran",
                List.of("implement", "gates"),
                ((List<Map<String, Object>>) unreviewed.summaryReport().get("workflow")).stream()
                        .map(stage -> stage.get("stage")).toList());
        check.eq("and marks the policy as declaring it", Boolean.TRUE,
                unreviewed.summaryReport().get("workflow_declared"));

        // The same reviewer, gated on risk instead of the review block. A skipped stage says
        // which condition skipped it: "the reviewer did not run" is not an answer by itself.
        Path gated = newProject(sandbox, "workflow-risk-gated", "medium");
        writeProfiles(home, sandbox, "workflow-risk-gated", 1, 99);
        workflowPolicy(home, """
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, when: [risk_high], on_fail: stop, on_findings: fix }
                """);
        TaskLoop.Outcome skipped = loop(gated, home, "w2");
        check.that("a condition that does not hold skips its stage", skipped.ok());
        check.eq("and the summary names the condition, not just the stage",
                List.of(Map.of("stage", "review", "reason", "condition_not_met:risk_high")),
                skipped.summaryReport().get("skipped_stages"));

        // Order is executed, not merely stored.
        Path twice = newProject(sandbox, "workflow-reordered");
        writeProfiles(home, sandbox, "workflow-reordered", 1, 1);
        workflowPolicy(home, """
                    - { stage: review-first, run: role, role: reviewer, on_fail: stop }
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                """);
        TaskLoop.Outcome reordered = loop(twice, home, "w3");
        check.that("a reordered chain runs", reordered.ok());
        check.eq("in the order it was declared",
                List.of("reviewer", "implementer", "gates"),
                steps(reordered).stream().map(step -> step.get("step")).toList());
    }

    /** A policy whose roles are the usual stand-ins but whose chain is written out. */
    private void workflowPolicy(Path home, String stages) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                %s""".formatted(stages));
    }

    /**
     * The browser step is a check like any other, and every other failing check in this loop
     * is handed back to the implementer with its evidence. It used to be terminal: the one
     * role with eyes was the only one whose finding nobody had to act on.
     *
     * Driven by a stand-in browser so the routing is tested, not the CDP driver.
     */
    private void visualLoopChecks(Check check, Path sandbox, Path home) throws Exception {
        // Fails once, then passes: exactly one visual fix round should be spent.
        Path recovering = newProject(sandbox, "visual-recovers", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-recovers", 1, 1);
        int[] visualCalls = {0};
        TaskLoop.Outcome recovered = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(++visualCalls[0] > 1)).run(
                        new ConfigLoader().load(recovering, "hello"), UserConfig.load(home), "v1", false);
        check.that("a visual failure that gets fixed still ends green", recovered.ok());
        check.eq("and it cost exactly one fix round", 1L, recovered.summaryReport().get("attempts_used"));
        String visualContext = Files.readString(
                recovering.resolve(".warden/runs/v1/context/fix-1-visual.md"));
        check.contains("the implementer is handed the failing scenario",
                visualContext, "1280x720: text=Save visible");
        check.contains("and the reason the browser gave", visualContext,
                "no element whose text contains");
        check.contains("and is told not to edit the contract instead",
                visualContext, "Fix the page, not the scenario");

        // Never recovers: the run stops, and says the browser is why.
        Path stuck = newProject(sandbox, "visual-stuck", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-stuck", 1, 1);
        TaskLoop.Outcome gaveUp = new TaskLoop(new ProcessRunner(), (loaded, runId) -> stubVisual(false))
                .run(new ConfigLoader().load(stuck, "hello"), UserConfig.load(home), "v2", false);
        check.that("a visual failure that survives the budget stops the run", !gaveUp.ok());
        check.eq("with the browser's own code", "visual_qa_failed", gaveUp.reason());
        check.eq("and still only ever hands off to a human", "human_escalation", gaveUp.nextAction());

        // A missing browser is not something an implementer can fix, so it is not handed back.
        Path noBrowser = newProject(sandbox, "visual-absent", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-absent", 1, 1);
        TaskLoop.Outcome unavailable = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                        Map.of("code", "visual_qa_unavailable"))).run(
                        new ConfigLoader().load(noBrowser, "hello"), UserConfig.load(home), "v3", false);
        check.eq("an absent browser stops the run without a fix round",
                "visual_qa_unavailable", unavailable.reason());
        check.eq("and spends nothing trying", 0L, unavailable.summaryReport().get("attempts_used"));
        check.that("no visual fix context was written",
                !Files.exists(noBrowser.resolve(".warden/runs/v3/context/fix-1-visual.md")));

        visualRoleChecks(check, sandbox, home);
    }

    /**
     * The role with eyes. The harness measures what a machine can measure; this asks a model
     * whether the result reads correctly to a person, and it is only worth paying for if the
     * screenshots actually reach it — so the stand-in refuses to answer without them.
     */
    private void visualRoleChecks(Check check, Path sandbox, Path home) throws Exception {
        Path shots = sandbox.resolve("shots");
        Files.createDirectories(shots);
        Path wide = shots.resolve("1280x720.png");
        Path narrow = shots.resolve("700x400.png");
        Files.writeString(wide, "stands in for a screenshot; what matters is that it exists");
        Files.writeString(narrow, "stands in for a screenshot; what matters is that it exists");

        // Not configured: the harness alone decides, and no vendor is paid to look.
        Path unwatched = newProject(sandbox, "visual-role-off", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-role-off", 1, 1);
        TaskLoop.Outcome without = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true, wide, narrow)).run(
                        new ConfigLoader().load(unwatched, "hello"), UserConfig.load(home), "v4", false);
        check.that("a green harness with no visual role configured finishes", without.ok());
        check.that("and nothing was dispatched to look at it",
                steps(without).stream().noneMatch(step -> "visual_qa".equals(step.get("step"))
                        && step.get("profile") != null));

        // Configured, and it finds something the browser could not: one fix round, then green.
        Path watched = newProject(sandbox, "visual-role-on", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-role-on", 1, 1);
        writeEyesProfile(home, sandbox, "visual-role-on", 2);
        policyWithEyes(home);
        TaskLoop.Outcome watchedRun = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true, wide, narrow)).run(
                        new ConfigLoader().load(watched, "hello"), UserConfig.load(home), "v5", false);
        check.that("a visual finding that gets fixed still ends green", watchedRun.ok());
        Map<String, Object> eyes = steps(watchedRun).stream()
                .filter(step -> "visual_qa".equals(step.get("step")) && step.get("profile") != null)
                .findFirst().orElseThrow();
        check.eq("the visual role really ran", "loop-eyes", eyes.get("profile"));
        Map<String, Object> report = readJson(
                watched.resolve(".warden/runs/v5--visual-role-0/role-visual_qa.json"));
        check.eq("and both screenshots were handed to it", 2L, report.get("attachment_count"));
        check.contains("they reached the vendor as attachments, not as text",
                Json.write(report.get("command")), "-i");
        String artifact = Files.readString(
                watched.resolve(".warden/runs/v5--visual-role-0/artifacts/visual_qa.json"));
        check.contains("and the vendor confirms it received images, not filenames",
                artifact, "looked at 2 image(s)");
        String context = Files.readString(
                watched.resolve(".warden/runs/v5/context/fix-1-visual-review.md"));
        check.contains("and its finding reached the implementer", context, "the whole word is readable");

        // Configured but never satisfied: the run stops on the visual finding, not on a gate.
        Path stubborn = newProject(sandbox, "visual-role-stuck", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-role-stuck", 1, 1);
        writeEyesProfile(home, sandbox, "visual-role-stuck", 99);
        policyWithEyes(home);
        TaskLoop.Outcome unresolved = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true, wide, narrow)).run(
                        new ConfigLoader().load(stubborn, "hello"), UserConfig.load(home), "v6", false);
        check.that("a visual finding that survives the budget stops the run", !unresolved.ok());
        check.eq("and is named as such", "visual_findings_remain", unresolved.reason());
        check.that("the cost of finding that out is in the summary",
                ((Number) unresolved.summaryReport().get("total_cost_usd")).doubleValue() > 0);
    }

    private void writeEyesProfile(Path home, Path sandbox, String scenario, int passesOn)
            throws IOException {
        Path counter = sandbox.resolve(scenario + "-eyes.count");
        Files.deleteIfExists(counter);
        Files.writeString(home.resolve("profiles/loop-eyes.yaml"), """
                version: 1
                profile: loop-eyes
                role: visual_qa
                vendor: eyesvendor
                command: %s
                read_only: true
                args:
                  - "-cp"
                  - %s
                  - "dev.warden.testing.StubVendor"
                  - "eyes"
                  - "--counter"
                  - %s
                  - "--threshold"
                  - "%d"
                attachments:
                  flag: "-i"
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/visual-qa.md
                json_schema: schemas/visual-qa.json
                artifact:
                  required_fields: [role, task_id, status, verdict, summary, findings]
                verification:
                  verified_on: "2026-08-27"
                """.formatted(yaml(javaExecutable()), yaml(absoluteClassPath()),
                yaml(counter.toString()), passesOn));
    }

    private void policyWithEyes(Path home) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                  visual_qa: { profiles: [loop-eyes], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                """);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path file) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }

    private static VisualQaRunner.Outcome stubVisual(boolean ok) {
        return stubVisual(ok, (Path[]) null);
    }

    /** A browser report in the shape the real adapter writes. */
    private static VisualQaRunner.Outcome stubVisual(boolean ok, Path... screenshots) {
        Map<String, Object> scenario = new java.util.LinkedHashMap<>();
        scenario.put("raw", "1280x720: text=Save visible");
        scenario.put("ok", ok);
        scenario.put("why", ok ? null : "no element whose text contains \"Save\" is visible");
        scenario.put("viewport_effective", Map.of("width", 1280L, "height", 720L));
        scenario.put("console_errors", List.of());
        if (screenshots != null && screenshots.length > 0) {
            scenario.put("screenshot", screenshots[0].toString());
            if (screenshots.length > 1) {
                scenario.put("steps", List.of(Map.of("screenshot_after", screenshots[1].toString())));
            }
        }
        Map<String, Object> adapter = new java.util.LinkedHashMap<>();
        adapter.put("ok", ok);
        adapter.put("code", ok ? "passed" : "visual_qa_failed");
        adapter.put("scenarios", List.of(scenario));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("adapter", adapter);
        return new VisualQaRunner.Outcome(ok, ok ? "passed" : "visual_qa_failed", null, data);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(TaskLoop.Outcome outcome) {
        return (List<Map<String, Object>>) outcome.summaryReport().get("steps");
    }

    private TaskLoop.Outcome loop(Path project, Path home, String runId) throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        return new TaskLoop(new ProcessRunner()).run(loaded, UserConfig.load(home), runId, false);
    }

    private Path newProject(Path sandbox, String name) throws Exception {
        return newProject(sandbox, name, "medium", 20);
    }

    private Path newProject(Path sandbox, String name, String risk) throws Exception {
        return newProject(sandbox, name, risk, 20);
    }

    private Path newProject(Path sandbox, String name, String risk, long maxRoleRuns) throws Exception {
        return newProject(sandbox, name, risk, maxRoleRuns, false);
    }

    private Path newProject(Path sandbox, String name, String risk, long maxRoleRuns, boolean visual)
            throws Exception {
        Path project = sandbox.resolve(name);
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        String existsCheck = WINDOWS
                ? "if exist src\\\\result.txt (exit /b 0) else (exit /b 1)"
                : "test -f src/result.txt";
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: %s
                base_ref: HEAD
                checks:
                  fast: ["%s"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """.formatted(name, existsCheck));
        String visualBlock = visual ? """
                visual_qa:
                  required: true
                  url: "http://127.0.0.1:65535/"
                  scenarios:
                    - "1280x720: text=Save visible"
                """ : "";
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt
                risk: %s
                scope: app
                authority:
                  workspace_write: true
                %sbudgets:
                  max_role_runs: %d
                  max_cost_usd: 10.0
                max_fix_attempts: 2
                """.formatted(risk, visualBlock, maxRoleRuns));
        Files.writeString(project.resolve("README.md"), "seed\n");
        ProcessRunner runner = new ProcessRunner();
        for (List<String> command : List.of(
                List.of("git", "init", "-q", "-b", "main", "."),
                List.of("git", "config", "user.email", "test@example.invalid"),
                List.of("git", "config", "user.name", "test"),
                List.of("git", "add", "-A"),
                List.of("git", "commit", "-qm", "base"))) {
            runner.run(command, project, Duration.ofSeconds(60));
        }
        return project;
    }

    /** Stand-ins whose success attempt is set per scenario, so fix rounds are forced, not hoped for. */
    private void writeProfiles(Path home, Path sandbox, String scenario,
                               int implSucceedsOn, int reviewPassesOn) throws IOException {
        Path implCounter = sandbox.resolve(scenario + "-impl.count");
        Path reviewCounter = sandbox.resolve(scenario + "-review.count");
        Files.deleteIfExists(implCounter);
        Files.deleteIfExists(reviewCounter);
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", implCounter, implSucceedsOn);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", reviewCounter, reviewPassesOn);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                """);
    }

    /** An implementer whose subscription is spent, transcribed from a live Codex refusal. */
    private void writeQuotaProfile(Path home, Path sandbox, String scenario) throws IOException {
        writeProfile(home, "loop-spent", "implementer", "spentvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "quota", sandbox.resolve(scenario + "-spent.count"), 1);
    }

    private void policy(Path home, String reviewers, String implementers) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [%s], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [%s], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                """.formatted(implementers, reviewers));
    }

    private void writeProfile(Path home, String name, String role, String vendor, boolean readOnly,
                              String promptAndSchema, String requiredFields, String mode,
                              Path counter, int threshold) throws IOException {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: %s
                read_only: %s
                args:
                  - "-cp"
                  - %s
                  - "dev.warden.testing.StubVendor"
                  - "%s"
                  - "--counter"
                  - %s
                  - "--threshold"
                  - "%d"
                  - "--prompt-file"
                  - "{{prompt_file}}"
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/%s.md
                json_schema: schemas/%s.json
                artifact:
                  required_fields: [%s]
                verification:
                  verified_on: "2026-08-26"
                """.formatted(name, role, vendor, yaml(javaExecutable()), readOnly,
                yaml(absoluteClassPath()), mode, yaml(counter.toString()), threshold,
                promptAndSchema, promptAndSchema, requiredFields));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        StringBuilder builder = new StringBuilder();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
