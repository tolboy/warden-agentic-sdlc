package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.VisualQaRunner;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.TaskLoop;
import dev.warden.run.Workspace;
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
            reportChecks(check, clean, "r1");
            landChecks(check, sandbox, home, clean, "r1");

            // A failing gate sends the work back with the machine output attached.
            Path fixable = newProject(sandbox, "fixable");
            writeProfiles(home, sandbox, "fixable", 2, 1);
            Board fixBoard = new Board();
            TaskLoop.Outcome fixed = loop(fixable, home, "r2", fixBoard);
            check.that("a fixable failure still ends green", fixed.ok());
            check.eq("exactly one fix round was used", 1L, fixed.summaryReport().get("attempts_used"));
            // A fix round is a second vendor call of the same length as the first, and it used
            // to run outside any beat, so the loop went quiet exactly where it is most likely
            // to be waited on.
            check.that("a fix round says who it sent the work back to",
                    fixBoard.anyNote("sent the work back to implementer"));
            check.that("and which profile and vendor took it, once the resolution had run",
                    fixBoard.anyNote("sent the work back to implementer (loop-impl / implvendor)"));
            // The pair can only reach the card through the note builder set alongside the
            // beat that wraps the fix dispatch, so a fix round running unwatched again would
            // take this check with it. A stub vendor answers in milliseconds, so no beat of
            // its own can fire inside a one-minute interval; HeartbeatTest owns the timing.
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
            // The repair has to move the tree: dropping the finding on identical bytes is the
            // laundering stop, not an addressed review.
            Path reviewed = newProject(sandbox, "reviewed");
            writeProfiles(home, sandbox, "reviewed", 1, 2);
            writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                    "implementer", "role, task_id, status, summary, files_changed",
                    "impl-grows", sandbox.resolve("reviewed-impl.count"), 1);
            TaskLoop.Outcome afterReview = loop(reviewed, home, "r4");
            check.that("a blocking review that is addressed ends green", afterReview.ok());
            String reviewContext = Files.readString(
                    reviewed.resolve(".warden/runs/r4/context/fix-1-review.md"));
            check.contains("the finding's expectation is handed back", reviewContext, "expected: the final content");
            check.contains("and its actual", reviewContext, "actual: an intermediate one");

            // Blocking findings that survive escalate rather than merge. The stand-in
            // implementer rewrites identical bytes, so the reviewer's second reading is of the
            // same tree and repeats itself — which the loop now names for what it is rather
            // than spending the rest of its fix rounds rediscovering.
            Path blocked = newProject(sandbox, "blocked");
            writeProfiles(home, sandbox, "blocked", 1, 99);
            TaskLoop.Outcome stillBlocked = loop(blocked, home, "r5");
            check.that("surviving P1s stop the run", !stillBlocked.ok());
            check.eq("naming the repair that achieved nothing rather than only the findings",
                    "repair_made_no_progress", stillBlocked.reason());
            check.eq("after one fix round, not after exhausting them all", 1L,
                    stillBlocked.summaryReport().get("attempts_used"));

            // Low risk pays no reviewer, but still passes every machine gate.
            Path cheap = newProject(sandbox, "cheap", "low");
            writeProfiles(home, sandbox, "cheap", 1, 99);
            TaskLoop.Outcome lowRisk = loop(cheap, home, "r6");
            check.that("a low-risk task still succeeds", lowRisk.ok());
            check.eq("and skips the reviewer entirely", Boolean.FALSE,
                    lowRisk.summaryReport().get("review_required"));
            check.that("no reviewer step was recorded",
                    steps(lowRisk).stream().noneMatch(step -> "reviewer".equals(step.get("step"))));

            // The budget is enforced before dispatch, not discovered by paying for it — and,
            // since the chain and its conditions are both known in advance, before a repair
            // whose consequences the run could not afford to judge. One call bought the
            // implementer; the gate then failed, and repairing would need a fix and a review
            // nobody could pay for. The old behaviour dispatched into that and reported
            // `budget_exhausted`, which reads as "we tried" rather than "this was arithmetic".
            Path broke = newProject(sandbox, "broke", "medium", 1);
            writeProfiles(home, sandbox, "broke", 99, 1);
            TaskLoop.Outcome exhausted = loop(broke, home, "r7");
            check.that("an allowance that cannot finish stops the run", !exhausted.ok());
            check.eq("before the repair rather than during it",
                    "budget_insufficient_to_finish", exhausted.reason());
            @SuppressWarnings("unchecked")
            Map<String, Object> reserve =
                    (Map<String, Object>) exhausted.summaryReport().get("budget_reserve");
            check.eq("naming the stage that wanted to send work back", "gates",
                    reserve.get("at_stage"));
            // The refusal is on the narrower number: one call for the fix, which nothing could
            // then read. Finishing would have wanted two, the second for the review.
            check.eq("what the repair alone would have cost", 1L,
                    reserve.get("calls_needed_to_repair"));
            check.eq("and what finishing from there would have cost", 2L,
                    reserve.get("calls_needed_to_repair_and_finish"));
            check.eq("against what was actually left", 0L, reserve.get("calls_remaining"));
            check.contains("the operator is told the number to raise the cap to",
                    String.valueOf(exhausted.summaryReport().get("safe_next_step")),
                    "max_role_runs");
            // The arithmetic was available before anything was spent, and saying so afterwards
            // is not the same as saying it in time.
            @SuppressWarnings("unchecked")
            Map<String, Object> plan =
                    (Map<String, Object>) exhausted.summaryReport().get("budget_plan");
            check.eq("the plan was costed before the first dispatch", 2L,
                    plan.get("minimum_success_calls"));
            check.eq("and said outright that the cap could not reach the end",
                    Boolean.FALSE, plan.get("sufficient_for_success"));
            check.eq("the implementer that did pass is not buried by the stop", Boolean.TRUE,
                    exhausted.summaryReport().get("workflow_incomplete"));
            check.eq("and the stages nobody reached are named",
                    List.of(Map.of("stage", "gates", "reason", "did_not_pass"),
                            Map.of("stage", "review", "reason", "not_reached")),
                    exhausted.summaryReport().get("pending_stages"));

            // The other ceiling. It is hit for a different reason and fixed in a different
            // place, and the run used to tell whoever ran out of money to raise the call
            // limit — a next step that would not have moved the run one call further.
            Path pricey = newProject(sandbox, "money-ceiling", "medium", 20, "0.005");
            writeProfiles(home, sandbox, "money-ceiling", 1, 1);
            TaskLoop.Outcome overspent = loop(pricey, home, "mc1");
            check.that("a spent money ceiling stops the run", !overspent.ok());
            check.eq("under the same reason as the call ceiling", "budget_exhausted",
                    overspent.reason());
            check.eq("but the summary says which of the two it was", "max_cost_usd",
                    overspent.summaryReport().get("budget_limit_hit"));
            String moneyStep = String.valueOf(overspent.summaryReport().get("safe_next_step"));
            check.contains("and the next step names the limit that actually bound",
                    moneyStep, "max_cost_usd");
            check.that("rather than the one that did not",
                    !moneyStep.contains("raise budgets.max_role_runs"));
            check.contains("with the caveat that the ceiling only measures priced calls",
                    moneyStep, "reported none and were not counted against it");
            check.contains("and the rendered report separates the two ceilings on its own line",
                    dev.warden.ledger.RunReport.render(
                            new dev.warden.ledger.RunReport().of(pricey, "mc1")),
                    "limit=max_cost_usd");

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

            // A tree that was already dirty outside the task's scope is the operator's
            // problem, and the run must find that out before it pays anyone to discover it.
            Path dirty = newProject(sandbox, "dirty-before-start");
            writeProfiles(home, sandbox, "dirty-before-start", 1, 1);
            Files.writeString(dirty.resolve("README.md"), "edited before the run\n");
            TaskLoop.Outcome refusedEarly = loop(dirty, home, "p1");
            check.that("a pre-existing out-of-scope change stops the run", !refusedEarly.ok());
            check.eq("and says so as a preflight failure",
                    "preflight_outside_scope", refusedEarly.reason());
            check.eq("before a single vendor was dispatched",
                    0L, refusedEarly.summaryReport().get("role_runs"));
            check.eq("naming the path nobody in this run touched",
                    List.of("README.md"), refusedEarly.summaryReport().get("preexisting_violations"));
            check.that("no role step was recorded at all", steps(refusedEarly).isEmpty());

            // The same tree, previewed. A dry run has no candidate to protect, so it does not
            // stop — but it is the operator's "will this work", and staying silent here meant
            // the preview resolved the whole chain and the real run then refused to dispatch.
            TaskLoop.Outcome previewed = new TaskLoop(new ProcessRunner())
                    .run(new ConfigLoader().load(dirty, "hello"), UserConfig.load(home), "p2", true);
            check.eq("a dry run still finishes", "dry_run", previewed.reason());
            check.eq("but names the path that will stop the real one",
                    List.of("README.md"), previewed.summaryReport().get("preexisting_violations"));
            check.eq("and says what it would have stopped for", "preflight_outside_scope",
                    previewed.summaryReport().get("would_stop"));

            baselineChecks(check, sandbox, home);
            roleFailureRoutingChecks(check);
            narrationChecks(check, sandbox, home);
            reusedJudgementChecks(check, sandbox, home);
            recheckFindingsChecks(check, sandbox, home);
            stagedResumeChecks(check, sandbox, home);
            independenceChecks(check, sandbox, home);
            judgingContractChecks(check, sandbox, home);
            resumeCurrencyChecks(check, sandbox, home);
            findingIdentityChecks(check);
            findingHistoryChecks(check, sandbox, home);
            nonactionableBlockerChecks(check, sandbox, home);
            handoverPackageChecks(check, sandbox, home);
            cumulativeClosureChecks(check, sandbox, home);
            crossStageRegistryChecks(check, sandbox, home);
            crossStageRecheckChecks(check, sandbox, home);
            historicalEvidenceReuseChecks(check, sandbox, home);
            severityLaunderingChecks(check, sandbox, home);
            severityLaunderingBehindBlockerChecks(check, sandbox, home);
            severityLaunderingOnResumeChecks(check, sandbox, home);
            provenanceResumeChecks(check, sandbox, home);
            liveRunShapeChecks(check, sandbox, home);
            carriedRejectionChecks(check, sandbox, home);
            workspaceChecks(check, sandbox, home);
            failoverConfirmationChecks(check, sandbox, home);
            declaredWorkflowChecks(check, sandbox, home);
            pairedReviewChecks(check, sandbox, home);
            visualLoopChecks(check, sandbox, home);
            a11yReportChecks(check, sandbox);

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
     * Carrying an accepted candidate to a commit and a branch.
     *
     * Every check here is a way the step could push something nobody agreed to: work that was
     * never accepted, a tree that moved after it was, the branch a request would target, or
     * Warden's own evidence riding along in the commit.
     */
    @SuppressWarnings("unchecked")
    private void landChecks(Check check, Path sandbox, Path home, Path project, String runId)
            throws Exception {
        dev.warden.run.LandCommand land = new dev.warden.run.LandCommand(new ProcessRunner());
        dev.warden.run.LandCommand.Options plan = new dev.warden.run.LandCommand.Options(
                runId, false, false, false, null, null, null, null, null);

        check.eq("a pending run cannot be landed", "not_accepted",
                land.run(plan, project).code());

        dev.warden.approval.ApprovalStore store = new dev.warden.approval.ApprovalStore(project);
        dev.warden.approval.HumanDecision pending = store.read(runId);
        store.resolve(runId, pending.updatedAt().toString(), "accept", "tester", "");

        // The fixture is on `main`, which is exactly the branch a request would target.
        check.eq("and an accepted one is still refused on the default branch",
                "refuses_default_branch", land.run(plan, project).code());

        new ProcessRunner().run(List.of("git", "checkout", "-q", "-b", "work"), project,
                Duration.ofSeconds(60));

        dev.warden.run.LandCommand.Outcome planned = land.run(plan, project);
        check.that("on a branch of its own it plans", planned.ok());
        check.eq("and changes nothing yet", "planned", planned.code());
        check.eq("the commit covers the source the run changed, and only that",
                List.of("src/result.txt"), planned.report().get("paths"));
        check.contains("the exact commands are printed rather than described",
                String.valueOf(planned.report().get("would_run")), "git add -- src/result.txt");
        check.eq("and it says outright that it merges nothing",
                Boolean.FALSE, planned.report().get("lands"));

        Map<String, Object> report = new dev.warden.ledger.RunReport().of(project, runId);
        check.that("the accepted run skipped a browser stage",
                listOf(report.get("skipped_stages")).stream().anyMatch(item ->
                        item instanceof Map<?, ?> row && "browser".equals(row.get("stage"))));
        String message = String.valueOf(planned.report().get("commit_message"));
        landMessageTellsTheTruth(check, report, message, planned.report());

        // The acceptance is of one tree, not of a task in general.
        String accepted = Files.readString(project.resolve("src/result.txt"));
        Files.writeString(project.resolve("src/result.txt"), accepted + "changed after the yes\n");
        check.eq("a tree that moved after the acceptance is refused", "candidate_changed",
                land.run(plan, project).code());
        Files.writeString(project.resolve("src/result.txt"), accepted);

        // Pushing needs somewhere to push, and this fixture has no remote. Saying so beats
        // inventing `origin`.
        check.eq("a push with no remote is refused, not guessed at", "no_remote",
                land.run(new dev.warden.run.LandCommand.Options(
                        runId, true, true, false, null, null, null, null, null), project).code());

        dev.warden.run.LandCommand.Outcome committed = land.run(
                new dev.warden.run.LandCommand.Options(
                        runId, true, false, false, null, null, null, null, null), project);
        check.that("committing works on its own", committed.ok());
        check.eq("and stops there", "committed", committed.code());
        ProcessRunner.Result show = new ProcessRunner().run(
                List.of("git", "show", "--name-only", "--format=", "HEAD"), project,
                Duration.ofSeconds(60));
        check.eq("the commit holds the source file", "src/result.txt", show.stdout().strip());
        check.that("and none of Warden's own evidence", !show.stdout().contains(".warden"));

        // A declared chain with no conditional stages: the message must not grow an empty
        // clause about skipping just because the skipped list is present and empty.
        Path noSkip = newProject(sandbox, "land-no-skip");
        writeProfiles(home, sandbox, "land-no-skip", 1, 1);
        workflowPolicy(home, """
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                """);
        TaskLoop.Outcome noSkipRun = loop(noSkip, home, "ln1");
        check.that("a chain with nothing to skip still finishes", noSkipRun.ok());
        dev.warden.approval.ApprovalStore noSkipStore = new dev.warden.approval.ApprovalStore(noSkip);
        dev.warden.approval.HumanDecision noSkipPending = noSkipStore.read("ln1");
        noSkipStore.resolve("ln1", noSkipPending.updatedAt().toString(), "accept", "tester", "");
        new ProcessRunner().run(List.of("git", "checkout", "-q", "-b", "land-no-skip"), noSkip,
                Duration.ofSeconds(60));
        dev.warden.run.LandCommand.Outcome noSkipPlanned = land.run(
                new dev.warden.run.LandCommand.Options(
                        "ln1", false, false, false, null, null, null, null, null), noSkip);
        check.that("and its land still plans", noSkipPlanned.ok());
        Map<String, Object> noSkipReport = new dev.warden.ledger.RunReport().of(noSkip, "ln1");
        check.that("and that chain really skipped nothing",
                listOf(noSkipReport.get("skipped_stages")).isEmpty());
        landMessageTellsTheTruth(check, noSkipReport,
                String.valueOf(noSkipPlanned.report().get("commit_message")),
                noSkipPlanned.report());
    }

    /**
     * The commit message is what a later reader has. It must not repeat its subject, must
     * not name a check the run never performed, and must keep the evidence pointer and
     * {@code Ran-by:} lines the rest of the land step already documented.
     */
    @SuppressWarnings("unchecked")
    private void landMessageTellsTheTruth(Check check, Map<String, Object> report, String message,
                                          Map<String, Object> landReport) {
        String goal = String.valueOf(report.get("goal"));
        int newline = goal.indexOf('\n');
        String subject = (newline < 0 ? goal : goal.substring(0, newline)).strip();
        check.eq("the subject appears once and not twice", 1, countExactLines(message, subject));
        check.eq("the pull request title is that subject",
                subject, landReport.get("pull_request_title"));
        check.contains("the evidence directory is pointed at", message,
                "the evidence is in .warden/runs/" + report.get("run_id") + ".");
        for (Object item : listOf(report.get("vendors"))) {
            if (!(item instanceof Map<?, ?> row)) continue;
            check.contains("Ran-by lines survive unchanged", message,
                    "Ran-by: " + row.get("vendor") + "/" + row.get("model"));
        }

        List<Map<String, Object>> skipped = new java.util.ArrayList<>();
        for (Object item : listOf(report.get("skipped_stages"))) {
            if (item instanceof Map<?, ?> row) skipped.add((Map<String, Object>) row);
        }
        if (skipped.isEmpty()) {
            check.that("a run with nothing skipped grows no empty skip clause",
                    !message.toLowerCase().contains("skipped"));
        } else {
            check.contains("a run with a skipped stage says so", message.toLowerCase(), "skipped");
            String passedClause = passedClause(message);
            for (Map<String, Object> row : skipped) {
                String stage = String.valueOf(row.get("stage"));
                check.contains("and names the skipped stage", message, stage);
                check.that("and does not claim that stage passed",
                        !passedClause.contains(stage));
                if (row.get("reason") != null) {
                    check.contains("with the reason the run recorded", message,
                            String.valueOf(row.get("reason")));
                }
            }
            check.that("and does not credit the browser harness that never ran",
                    !message.contains("browser harness") && !message.contains("all passed"));
        }
    }

    /** The clause that names what passed, or empty when the message never says so. */
    private static String passedClause(String message) {
        for (String clause : message.split("\\. ")) {
            if (clause.contains("passed") && !clause.toLowerCase().contains("skipped")) return clause;
        }
        return "";
    }

    private static int countExactLines(String text, String line) {
        int count = 0;
        for (String row : text.split("\\R", -1)) {
            if (row.equals(line)) count++;
        }
        return count;
    }

    private static List<Object> listOf(Object value) {
        return value instanceof List<?> rows ? List.copyOf(rows) : List.of();
    }

    /**
     * The joined report, checked against a run that actually happened rather than against a
     * directory hand-built to match. Every stage writes its own file and never edits another;
     * this asserts that the join over those files answers "which model did what, and what did
     * it cost" — the question the evidence directory does not answer on its own.
     */
    @SuppressWarnings("unchecked")
    private void reportChecks(Check check, Path project, String runId) throws Exception {
        Map<String, Object> report = new dev.warden.ledger.RunReport().of(project, runId);
        check.eq("the report is about the run it was asked for", runId, report.get("run_id"));
        check.eq("and carries the goal from the task contract, not from a model",
                "Create src/result.txt", report.get("goal"));

        List<Map<String, Object>> stages = (List<Map<String, Object>>) report.get("stages");
        check.eq("every step of the loop appears", List.of("implementer", "gates", "reviewer"),
                stages.stream().map(stage -> stage.get("step")).toList());
        Map<String, Object> implement = stages.get(0);
        check.eq("a role stage is joined to the vendor that filled it",
                "implvendor", implement.get("vendor"));
        check.eq("and to the profile it came from", "loop-impl", implement.get("profile"));
        check.that("and to what it cost", implement.get("cost_usd") instanceof Number);
        check.that("and how long it took", implement.get("duration_millis") instanceof Number);

        List<Map<String, Object>> vendors = (List<Map<String, Object>>) report.get("vendors");
        check.eq("vendors roll up one row per vendor and model", 2, vendors.size());
        check.that("with the calls counted",
                vendors.stream().allMatch(row -> ((Number) row.get("calls")).longValue() == 1));

        // The number a vendor never reported must not become a zero. A run summarised as
        // "$0.00 across four calls" is one an operator will believe.
        check.that("a token count no stand-in reported is counted as unknown, not as zero",
                vendors.stream().anyMatch(row -> ((Number) row.get("tokens_unknown_calls")).longValue() > 0));

        check.eq("the machine gate stage is identified as one", "machine_gates",
                stages.get(1).get("kind"));
        check.that("the changed file the implementer wrote is in the report",
                ((List<Object>) report.get("changed_files")).contains("src/result.txt"));
        Map<String, Object> decision = (Map<String, Object>) report.get("decision");
        check.eq("and the pending human decision is part of the result", "pending",
                decision.get("state"));

        String rendered = dev.warden.ledger.RunReport.render(report);
        check.contains("the terminal rendering names the vendor", rendered, "implvendor");
        check.contains("and says what still has to happen", rendered, "human_gate");
    }

    /**
     * Routing around a spent subscription changes who wrote the code, and on a small roster
     * it can cost the run its independent reviewer. The default is therefore to stop and ask,
     * and the authorisation to proceed is a recorded human decision — not a flag, which
     * anyone could pass, and not persisted "codex is out", which goes stale the moment the
     * quota window rolls over.
     */
    @SuppressWarnings("unchecked")
    private void failoverConfirmationChecks(Check check, Path sandbox, Path home) throws Exception {
        Path asked = newProject(sandbox, "failover-confirm");
        writeProfiles(home, sandbox, "failover-confirm", 1, 1);
        writeQuotaProfile(home, sandbox, "failover-confirm");
        policy(home, "loop-review", "loop-spent, loop-impl", "confirm");

        TaskLoop.Outcome stopped = loop(asked, home, "f1");
        check.that("the shipped default does not switch vendors unattended", !stopped.ok());
        check.eq("it stops and says what it is waiting for",
                "failover_requires_confirmation", stopped.reason());

        Map<String, Object> pending =
                (Map<String, Object>) stopped.summaryReport().get("failover_pending");
        check.eq("the spent profile is named", "loop-spent", pending.get("from_profile"));
        check.eq("and so is the one that would take over", "loop-impl", pending.get("to_profile"));
        check.eq("the cause is not confused with an ordinary failure",
                "role_quota_exhausted", pending.get("cause"));

        dev.warden.approval.ApprovalStore store = new dev.warden.approval.ApprovalStore(asked);
        dev.warden.approval.HumanDecision decision = store.read("f1");
        check.eq("the human is asked a different question from retry-or-give-up",
                "failover", decision.kind().jsonValue());
        check.eq("and abort is offered first, so automation declines rather than grants",
                List.of("abort", "switch"), decision.options());
        check.contains("the question names both vendors", decision.reason(), "spentvendor");
        check.contains("and says what the switch costs in independence",
                decision.reason(), "independence");

        // Nothing changes until a person answers, and answering is the whole authorisation.
        store.resolve("f1", decision.updatedAt().toString(), "switch", "tester", "");
        TaskLoop.Outcome resumed = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(asked, "hello"), UserConfig.load(home), "f2", false,
                Map.of("implementer", "loop-impl"));
        check.that("with the switch recorded, the run completes on the other vendor", resumed.ok());
        String evidence = Files.readString(
                asked.resolve(".warden/runs/f2--implementer-0/evidence.jsonl"));
        check.contains("and the substitution is an event, not a footnote",
                evidence, "\"type\":\"role_failover\"");
        check.contains("attributed to the human who allowed it",
                evidence, "human_switch_decision");

        // `auto` is the opt-out, and it still leaves the same event behind.
        Path unattended = newProject(sandbox, "failover-auto");
        writeProfiles(home, sandbox, "failover-auto", 1, 1);
        writeQuotaProfile(home, sandbox, "failover-auto");
        policy(home, "loop-review", "loop-spent, loop-impl", "auto");
        TaskLoop.Outcome automatic = loop(unattended, home, "f3");
        check.that("failover.on_quota_exhausted: auto switches without asking", automatic.ok());
        check.contains("and records who authorised it",
                Files.readString(unattended.resolve(".warden/runs/f3--implementer-0/evidence.jsonl")),
                "policy_auto");

        // A switch is also a workflow-level fact: whoever reads the run report should not
        // have to know which stage directory to open to find out the author changed.
        Map<String, Object> report = new dev.warden.ledger.RunReport().of(unattended, "f3");
        List<Map<String, Object>> switches = (List<Map<String, Object>>) report.get("failovers");
        check.eq("the run report names the substitution", 1, switches.size());
        check.eq("from the profile that ran out", "loop-spent", switches.get(0).get("from_profile"));
        check.eq("to the one that finished", "loop-impl", switches.get(0).get("to_profile"));

        // `stop` refuses the switch even though a candidate exists.
        Path refused = newProject(sandbox, "failover-stop");
        writeProfiles(home, sandbox, "failover-stop", 1, 1);
        writeQuotaProfile(home, sandbox, "failover-stop");
        policy(home, "loop-review", "loop-spent, loop-impl", "stop");
        TaskLoop.Outcome declined = loop(refused, home, "f4");
        check.eq("failover.on_quota_exhausted: stop never switches",
                "quota_exhausted", declined.reason());
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

    /**
     * Two stages, one role: two independent readers of the same diff.
     *
     * This is what `rotate` is for, and both halves of it were broken in a way only a live
     * run showed. The pairing came from a counter shared by every run in the project, so a
     * run that died before its reviewer shifted it and the operator got their two profiles in
     * the wrong order. And both stages resolved to the same evidence directory, so the second
     * reviewer's prompt, raw output and artifact overwrote the first's — leaving the ledger's
     * entry for the first pointing at the second's files, which is worse than losing them.
     */
    private void pairedReviewChecks(Check check, Path sandbox, Path home) throws Exception {
        Path paired = newProject(sandbox, "workflow-paired", "medium");
        writeProfiles(home, sandbox, "workflow-paired", 1, 99);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("workflow-paired-review2.count"), 1);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review, loop-review-2], strategy: rotate, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop }
                    - { stage: review-second, run: role, role: reviewer, on_fail: stop }
                """);

        // A counter left over from earlier runs is exactly the state that used to invert the
        // pairing. It must now change nothing at all.
        Files.createDirectories(paired.resolve(".warden/runs"));
        Files.writeString(paired.resolve(".warden/runs/rotation.json"),
                "{\"reviewer\": 7}\n");

        // The preview exists to show who will be dispatched before anyone is paid, and the
        // pairing of two independent readers is the thing most worth seeing there. While the
        // index came from a counter that a dry run deliberately did not advance, the preview
        // named the same profile on both stages — the one arrangement that cannot happen.
        TaskLoop.Outcome preview = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(paired, "hello"), UserConfig.load(home), "paired-dry", true);
        check.eq("a dry run previews the real pairing, not one profile twice",
                List.of("loop-review", "loop-review-2"), steps(preview).stream()
                        .filter(step -> "reviewer".equals(step.get("step")))
                        .map(step -> String.valueOf(step.get("profile"))).toList());
        check.that("and it still dispatches nobody",
                !Files.exists(paired.resolve(".warden/runs/paired-dry--review-0/raw")));

        TaskLoop.Outcome run = loop(paired, home, "paired");
        List<String> reviewers = steps(run).stream()
                .filter(step -> "reviewer".equals(step.get("step")))
                .map(step -> String.valueOf(step.get("profile"))).toList();
        check.eq("each review stage gets a different profile, in the order policy lists them",
                List.of("loop-review", "loop-review-2"), reviewers);
        check.eq("and the stage that dispatched each one is recorded",
                List.of("review", "review-second"), steps(run).stream()
                        .filter(step -> "reviewer".equals(step.get("step")))
                        .map(step -> step.get("stage")).toList());

        check.that("the first reviewer's artifact survives the second",
                Files.isRegularFile(paired.resolve(".warden/runs/paired--review-0/artifacts/reviewer.json")));
        check.that("and the second writes its own, beside it",
                Files.isRegularFile(paired.resolve(
                        ".warden/runs/paired--review-second-0/artifacts/reviewer.json")));
        check.that("each keeps the prompt it was actually sent",
                Files.isRegularFile(paired.resolve(".warden/runs/paired--review-0/prompts/reviewer.md"))
                        && Files.isRegularFile(paired.resolve(
                                ".warden/runs/paired--review-second-0/prompts/reviewer.md")));
        check.eq("and the evidence file names the profile that wrote it", "loop-review-2",
                readJson(paired.resolve(".warden/runs/paired--review-second-0/role-reviewer.json"))
                        .get("profile"));

        // The report is where the misattribution showed: a row for one vendor carrying the
        // other's model, duration and token counts, because both rows read one file.
        Map<String, Object> report = new dev.warden.ledger.RunReport().of(paired, "paired");
        List<Map<String, Object>> roleStages = ((List<Map<String, Object>>) report.get("stages")).stream()
                .filter(stage -> "reviewer".equals(stage.get("role"))).toList();
        check.eq("the report joins each review row to its own evidence",
                List.of("loop-review", "loop-review-2"),
                roleStages.stream().map(stage -> stage.get("profile")).toList());
        check.eq("including the vendor each row actually ran",
                List.of("reviewvendor", "secondvendor"),
                roleStages.stream().map(stage -> stage.get("vendor")).toList());
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
        // The fix moved the candidate, which took the review's completion away; the recheck
        // that followed re-established it and has to give it back. Getting only half of that
        // right leaves a run that finished cleanly reporting an outstanding review.
        check.eq("a review re-established by its recheck is complete again", Boolean.FALSE,
                recovered.summaryReport().get("workflow_incomplete"));
        check.eq("and the candidate is reported as having passed review", Boolean.TRUE,
                recovered.summaryReport().get("candidate_review_passed"));
        check.that("the review stage is listed among those that completed",
                listOf(recovered.summaryReport().get("completed_stages")).contains("review"));
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
     * What a human reads of the accessibility snapshot. The adapter ranking is
     * {@link VisualQaTest}; this is the join: unnamed interactive nodes stay
     * visible, ignored nodes are not printed as ordinary controls, and five
     * scenarios that share a list do not print it five times.
     */
    @SuppressWarnings("unchecked")
    private void a11yReportChecks(Check check, Path sandbox) throws Exception {
        Path project = sandbox.resolve("a11y-report");
        Files.createDirectories(project.resolve(".warden/runs/ay1"));
        Path visualDir = project.resolve(".warden/runs/ay1--visual-qa-0");
        Files.createDirectories(visualDir);

        Map<String, Object> box = new java.util.LinkedHashMap<>();
        box.put("x", 10);
        box.put("y", 20);
        box.put("width", 80);
        box.put("height", 24);
        List<Map<String, Object>> same = List.of(
                axReport("link", "Home", false, box),
                axReport("link", "Docs", false, box),
                axReport("button", "Ghost", true, box),
                axReport("button", "", false, box),
                axReport("button", "Save", false, box));
        List<Map<String, Object>> scenarios = List.of(
                visualScenario("1280x720: testid=save visible", same),
                visualScenario("700x400: testid=save visible", same),
                visualScenario("1280x720: testid=menu visible", same),
                visualScenario("1024x768: css=.panel visible", same),
                visualScenario("1280x720: no-console-errors", same));
        Map<String, Object> adapter = new java.util.LinkedHashMap<>();
        adapter.put("ok", true);
        adapter.put("code", "passed");
        adapter.put("scenarios", scenarios);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", true);
        body.put("code", "passed");
        body.put("adapter", adapter);
        Files.writeString(visualDir.resolve("visual-qa.json"), Json.writePretty(body));

        Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("step", "visual_qa");
        step.put("attempt", 0L);
        step.put("ok", true);
        step.put("code", "passed");
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("task_id", "hello");
        summary.put("ok", true);
        summary.put("reason", "human_gate");
        summary.put("next_action", "human_gate");
        summary.put("steps", List.of(step));
        Files.writeString(project.resolve(".warden/runs/ay1/task-run.json"),
                Json.writePretty(summary));

        Map<String, Object> report = new dev.warden.ledger.RunReport().of(project, "ay1");
        Map<String, Object> visual = (Map<String, Object>) report.get("visual");
        List<Map<String, Object>> joined = (List<Map<String, Object>>) visual.get("scenarios");
        check.eq("five scenarios are joined", 5, joined.size());
        boolean unnamed = false;
        boolean ignored = false;
        boolean boxed = true;
        for (Map<String, Object> scenario : joined) {
            List<Map<String, Object>> a11y = (List<Map<String, Object>>) scenario.get("a11y");
            for (Map<String, Object> node : a11y) {
                if (!(node.get("box") instanceof Map<?, ?>)) boxed = false;
                if ("button".equals(node.get("role")) && "".equals(node.get("name"))
                        && !Boolean.TRUE.equals(node.get("ignored"))) {
                    unnamed = true;
                }
                if (Boolean.TRUE.equals(node.get("ignored"))) ignored = true;
            }
        }
        check.that("every summarised node carries a bounding box", boxed);
        check.that("an unnamed interactive node is kept in the report", unnamed);
        check.that("ignored is carried through, not dropped", ignored);

        String rendered = dev.warden.ledger.RunReport.render(report);
        check.contains("an interactive node with no accessible name is identifiable",
                rendered, "(no accessible name)");
        check.contains("an ignored node is marked unreachable",
                rendered, "button:Ghost [unreachable]");
        check.that("and is not printed as an ordinary control",
                !rendered.contains("button:Ghost |") && !rendered.contains("button:Ghost\n"));
        int a11yLines = 0;
        for (int from = 0; (from = rendered.indexOf("        a11y  ", from)) >= 0;
             from += "        a11y  ".length()) {
            a11yLines++;
        }
        check.eq("identical a11y is printed once for the run, not under every scenario",
                1, a11yLines);
    }

    private static Map<String, Object> axReport(String role, String name, boolean ignored,
                                                Map<String, Object> box) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("role", role);
        node.put("name", name);
        node.put("ignored", ignored);
        node.put("box", box);
        return node;
    }

    private static Map<String, Object> visualScenario(String raw, List<Map<String, Object>> a11y) {
        Map<String, Object> scenario = new java.util.LinkedHashMap<>();
        scenario.put("raw", raw);
        scenario.put("ok", true);
        scenario.put("a11y", a11y);
        return scenario;
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
        // The repair has to move the tree: dropping the visual P1 on identical bytes is the
        // laundering stop, not a fixed finding.
        Path watched = newProject(sandbox, "visual-role-on", "medium", 20, true);
        writeProfiles(home, sandbox, "visual-role-on", 1, 1);
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("visual-role-on-impl.count"), 1);
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
        // The eyes object to the same screenshot twice and the stand-in implementer rewrites
        // identical bytes, so the round achieved nothing and the run names that rather than
        // spending the rest of its fix attempts on the same pixels.
        check.eq("and is named as such", "repair_made_no_progress", unresolved.reason());
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

    /**
     * The run narrates itself while it works.
     *
     * Not evidence — every line restates the ledger — but a twenty-minute loop that printed
     * nothing until it was over meant the only way to see where it had got to was to stat
     * files in four run directories.
     */
    private void narrationChecks(Check check, Path sandbox, Path home) throws Exception {
        Path project = newProject(sandbox, "narrated");
        writeProfiles(home, sandbox, "narrated", 1, 1);
        List<String> printed = new java.util.ArrayList<>();
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner()).withProgress(printed::add)
                .run(loaded, UserConfig.load(home), "n1", false);
        String all = String.join("\n", printed);
        check.that("the run says what it is about to do", all.contains("plan  implement"));
        check.that("and names each stage as it reaches it", all.contains("] implement")
                && all.contains("] gates"));
        check.that("and says how each one went", all.contains("      ok"));
        check.that("and ends by naming the command that shows the whole run",
                all.contains("warden report n1 --text"));
        check.that("and the command the person is now expected to run",
                all.contains("warden approve n1 --decision"));
        check.that("a run that narrates still returns the same outcome", outcome.ok());
        check.eq("and counts the vendor calls that reported no price at all",
                0L, outcome.summaryReport().get("unpriced_calls"));
        check.eq("so the $ ceiling is known to have measured this run",
                Boolean.TRUE, outcome.summaryReport().get("cost_ceiling_binding"));
    }

    /**
     * A verdict is about a tree, not about a run.
     *
     * A run that stopped because no browser was available threw away an implementer and an
     * independent review that had both passed on a tree nobody has touched since, and the
     * only way forward was to pay for both again. Measured once at $0.34 and twenty minutes,
     * for a defect that turned out to be in Warden.
     */
    private void reusedJudgementChecks(Check check, Path sandbox, Path home) throws Exception {
        Path project = newProject(sandbox, "reusable", "medium", 20, true);
        writeProfiles(home, sandbox, "reusable", 1, 1);
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        // The browser is simply not there: a failure that is not about the work.
        TaskLoop unavailable = new TaskLoop(new ProcessRunner(), (l, r) ->
                new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                        Map.of("message", "no Chrome/Edge executable found")));
        TaskLoop.Outcome stopped = unavailable.run(loaded, UserConfig.load(home), "ru1", false);
        check.eq("the run stops on the environment, not the work",
                "visual_qa_unavailable", stopped.reason());

        dev.warden.approval.ApprovalStore decisions = new dev.warden.approval.ApprovalStore(project);
        decisions.resolve("ru1", decisions.read("ru1").updatedAt().toString(), "retry", "operator",
                "installed a browser");

        List<String> printed = new java.util.ArrayList<>();
        Board reusedBoard = new Board();
        TaskLoop.Outcome resumed = new TaskLoop(new ProcessRunner(),
                (l, r) -> stubVisual(true))
                .withProgress(printed::add)
                .withWorkspace(reusedBoard)
                .run(new ConfigLoader().load(project, "hello"), UserConfig.load(home), "ru2",
                        false, Map.of(), new TaskLoop.Continuation("ru1", null, true));
        check.that("the second run reaches the human gate", resumed.ok());
        check.that("and says which verdicts it kept",
                String.join("\n", printed).contains("reused from ru1"));
        java.util.List<String> carried = reusedBoard.notes.stream()
                .filter(note -> note.contains("implement · implementer"))
                .toList();
        check.that("a carried-over verdict still has a running card", !carried.isEmpty());
        check.that("and names no vendor, because nobody was dispatched",
                carried.stream().noneMatch(note -> note.contains("(")));
        check.eq("recording which stages they came from, not which roles", Map.of("from", "ru1",
                        "stages", List.of("implement", "review")),
                resumed.summaryReport().get("reused_judgements"));
        check.that("and charging nothing for them", (Long) resumed.summaryReport().get("role_runs") == 0L);

        // Now the same thing after the tree moved: nothing may be carried over.
        Files.writeString(project.resolve("src/moved.txt"), "changed after the verdicts\n");
        TaskLoop.Outcome moved = new TaskLoop(new ProcessRunner(),
                (l, r) -> stubVisual(true))
                .run(new ConfigLoader().load(project, "hello"), UserConfig.load(home), "ru3",
                        false, Map.of(), new TaskLoop.Continuation("ru1", null, true));
        check.that("a tree that changed keeps nothing",
                moved.summaryReport().get("reused_judgements") == null);
        check.contains("and says why", String.valueOf(moved.summaryReport().get("reuse_declined")),
                "judged a different candidate");

        // The same argument, for the failure that turned out to be far more common than a
        // missing browser. A reviewer stopped by its own `--max-turns` said nothing about the
        // work: the fix is a number in a profile, and the diff waiting for the next run is one
        // no vendor has objected to. Measured live, twice in one afternoon — and because the
        // run-level reason was the flat `reviewer_failed`, the retry paid for an implementer
        // and a machine gate that had both already passed on this exact tree.
        Path ceiling = newProject(sandbox, "ceiling-retry", "medium");
        writeProfiles(home, sandbox, "ceiling-retry", 1, 1);
        writeProfile(home, "loop-ceiling", "reviewer", "ceilingvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "turn-ceiling", sandbox.resolve("ceiling-retry-review.count"), 1);
        policy(home, "loop-ceiling", "loop-impl");
        TaskLoop.Outcome hitCeiling = loop(ceiling, home, "tc1");
        check.eq("a reviewer stopped at its ceiling names the ceiling, not the reviewer",
                "turn_ceiling_reached", hitCeiling.reason());

        dev.warden.approval.ApprovalStore ceilingDecisions =
                new dev.warden.approval.ApprovalStore(ceiling);
        ceilingDecisions.resolve("tc1", ceilingDecisions.read("tc1").updatedAt().toString(),
                "retry", "operator", "raised --max-turns");
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome afterRaise = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(ceiling, "hello"), UserConfig.load(home), "tc2",
                        false, Map.of(), new TaskLoop.Continuation("tc1", null, true));
        check.that("the retry reaches the human gate", afterRaise.ok());
        check.eq("keeping the implement stage that had already passed on this tree",
                Map.of("from", "tc1", "stages", List.of("implement")),
                afterRaise.summaryReport().get("reused_judgements"));
    }

    /**
     * A recheck that reads the verdict, not just the exit code.
     *
     * Declaring `recheck_after_fix` on a review stage buys a second reading of the diff after
     * a later stage sends the work back — the case where a browser fix rewrites code an
     * independent reviewer has already passed. The re-dispatch happened and was paid for; what
     * did not happen was reading what came back. A reviewer that ran cleanly and returned a
     * blocking finding was recorded as a passing recheck, the loop carried on, and the human
     * gate went on to say every stage that ran had passed. The most expensive thing the run
     * produced was the objection, and it was the one thing thrown away.
     */
    @SuppressWarnings("unchecked")
    private void recheckFindingsChecks(Check check, Path sandbox, Path home) throws Exception {
        Path regressed = newProject(sandbox, "recheck-regression", "medium", 20, true);
        Path implCounter = sandbox.resolve("recheck-regression-impl.count");
        Path reviewCounter = sandbox.resolve("recheck-regression-review.count");
        Files.deleteIfExists(implCounter);
        Files.deleteIfExists(reviewCounter);
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", implCounter, 1);
        // Passes the diff it is first shown; objects to what the browser fix round does to it.
        writeProfile(home, "loop-regress", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-regresses", reviewCounter, 2);
        policy(home, "loop-regress", "loop-impl");

        List<String> printed = new java.util.ArrayList<>();
        int[] visualCalls = {0};
        TaskLoop.Outcome caught = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(++visualCalls[0] > 1))
                .withProgress(printed::add)
                .run(new ConfigLoader().load(regressed, "hello"), UserConfig.load(home),
                        "rk1", false);

        check.that("a fix that breaks an earlier verdict never reaches the human gate",
                !caught.ok());
        check.eq("and the stop names the objection, not the stage that sent the work back",
                "blocking_findings_remain", caught.reason());
        check.that("the reviewer really was re-dispatched rather than assumed",
                Integer.parseInt(Files.readString(reviewCounter).strip()) == 2);
        check.contains("and the narration says what happened in those words",
                String.join("\n", printed), "broke a verdict");

        List<Map<String, Object>> coverage =
                (List<Map<String, Object>>) caught.summaryReport().get("review_coverage");
        check.eq("the finding is recorded against the stage that made it", "review",
                coverage.get(0).get("stage"));
        check.eq("with the count that blocked acceptance", 1L,
                coverage.get(0).get("blocking_findings"));
        check.eq("so the candidate is not reported as having passed review", Boolean.FALSE,
                caught.summaryReport().get("candidate_review_passed"));

        // The stage that just failed must not still be listed as one that passed. A fix moves
        // the candidate, so every verdict about the old one stops counting as finished
        // business, and only passing again earns the place back.
        check.that("the review that objected is no longer a completed stage",
                !listOf(caught.summaryReport().get("completed_stages")).contains("review"));
        check.eq("and both unfinished stages are named, with what happened to each",
                List.of(Map.of("stage", "review", "reason", "did_not_pass"),
                        Map.of("stage", "browser", "reason", "did_not_pass")),
                caught.summaryReport().get("pending_stages"));

        // The other half of the same rule: a judging stage the workflow does not recheck is
        // left holding a verdict about a tree that no longer exists, and that is a third
        // state rather than either of the two above.
        Path unrechecked = newProject(sandbox, "recheck-not-declared", "medium", 20, true);
        Files.deleteIfExists(sandbox.resolve("recheck-not-declared-impl.count"));
        Files.deleteIfExists(sandbox.resolve("recheck-not-declared-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("recheck-not-declared-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("recheck-not-declared-review.count"), 1);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix, recheck_after_fix: false }
                    - { stage: browser, run: visual_harness, when: [visual_qa_required], on_fail: fix, recheck_after_fix: true }
                """);
        int[] shots = {0};
        TaskLoop.Outcome overtaken = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(++shots[0] > 1))
                .run(new ConfigLoader().load(unrechecked, "hello"), UserConfig.load(home),
                        "rk2", false);
        check.that("the run still reaches the human gate", overtaken.ok());
        check.eq("but the review that was overtaken by the fix is not counted as complete",
                List.of(Map.of("stage", "review", "reason", "judged_an_earlier_candidate")),
                overtaken.summaryReport().get("pending_stages"));
        check.eq("and the candidate is not claimed to have passed review", Boolean.FALSE,
                overtaken.summaryReport().get("candidate_review_passed"));
        check.contains("the person at the gate is told before being asked to accept",
                String.valueOf(overtaken.summaryReport().get("decision_reason")),
                "did not finish");

        // The third way a recheck can come back: the role did not complete at all. Not an
        // objection to the work, and it must not leave the stage marked as one that passed
        // either.
        Path crashed = newProject(sandbox, "recheck-crashes", "medium", 20, true);
        Files.deleteIfExists(sandbox.resolve("recheck-crashes-impl.count"));
        Files.deleteIfExists(sandbox.resolve("recheck-crashes-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("recheck-crashes-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-then-fails", sandbox.resolve("recheck-crashes-review.count"), 2);
        policy(home, "loop-review", "loop-impl");
        int[] looks = {0};
        TaskLoop.Outcome broke = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(++looks[0] > 1))
                .run(new ConfigLoader().load(crashed, "hello"), UserConfig.load(home),
                        "rk3", false);
        check.that("a recheck that cannot complete stops the run", !broke.ok());
        check.eq("named after the role, not after the stage that sent work back",
                "reviewer_failed", broke.reason());
        check.that("and the stage it happened in is not counted as passed",
                !listOf(broke.summaryReport().get("completed_stages")).contains("review"));
        check.eq("the candidate is not claimed to have passed review", Boolean.FALSE,
                broke.summaryReport().get("candidate_review_passed"));
    }

    /**
     * Continuing a run that ran out of room, without paying twice and without letting one
     * reviewer's verdict stand in for another's.
     *
     * Two things had to be true at once and were not. A stop for a spent call ceiling says
     * nothing about the diff, so the verdicts already reached on it should survive — but the
     * only way to continue is to raise the ceiling, and the ceiling lives in the task file,
     * whose hash is what proves the verdicts still apply. And a chain that reviews twice wrote
     * both verdicts under the role's name, so the survivor could be handed to whichever review
     * stage asked first: a run could satisfy `review-second` with `review`'s reading and report
     * two independent reviews of the same diff.
     */
    @SuppressWarnings("unchecked")
    private void stagedResumeChecks(Check check, Path sandbox, Path home) throws Exception {
        Path paired = newProject(sandbox, "resume-paired", "medium", 2);
        writeProfiles(home, sandbox, "resume-paired", 1, 1);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("resume-paired-review2.count"), 1);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review, loop-review-2], strategy: rotate, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix }
                    - { stage: review-second, run: role, role: reviewer, on_fail: stop, on_findings: fix }
                """);

        TaskLoop.Outcome ranOut = loop(paired, home, "sr1");
        check.that("two calls cannot reach a chain that reviews twice", !ranOut.ok());
        check.eq("and the run says which ceiling it hit", "budget_exhausted", ranOut.reason());
        Map<String, Object> plan = (Map<String, Object>) ranOut.summaryReport().get("budget_plan");
        check.eq("the shortfall was arithmetic available before the first dispatch", 3L,
                plan.get("minimum_success_calls"));
        check.eq("and the plan said so rather than discovering it", Boolean.FALSE,
                plan.get("sufficient_for_success"));

        // The three questions, separated. This is the live run's exact shape: a good candidate
        // that passed the review it could afford, inside a workflow that did not finish.
        check.eq("the review that did run is not buried by the stop", Boolean.TRUE,
                ranOut.summaryReport().get("candidate_review_passed"));
        check.eq("while the chain is honestly reported as incomplete", Boolean.TRUE,
                ranOut.summaryReport().get("workflow_incomplete"));
        check.eq("naming the stage that never got a turn",
                List.of(Map.of("stage", "review-second", "reason", "not_reached")),
                ranOut.summaryReport().get("pending_stages"));

        // The only edit that makes continuing possible, and the one edit no reviewer's verdict
        // depends on. Every other byte of the contract is identical.
        Files.writeString(paired.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 10.0
                max_fix_attempts: 2
                """);
        TaskLoop.Outcome resumed = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(paired, "hello"), UserConfig.load(home), "sr2", false,
                Map.of(), new TaskLoop.Continuation("sr1", null, true));
        check.that("raising only the call ceiling lets the run continue", resumed.ok());
        check.eq("the verdicts already paid for are kept, named by stage",
                List.of("implement", "review"),
                ((Map<String, Object>) resumed.summaryReport().get("reused_judgements")).get("stages"));
        Map<String, Object> budgetOnly =
                (Map<String, Object>) resumed.summaryReport().get("contract_change_budget_only");
        check.eq("and the edited contract is recorded, not glossed over", 2L,
                budgetOnly.get("prior_max_role_runs"));
        check.eq("with the number it became", 6L, budgetOnly.get("now_max_role_runs"));

        Map<String, Object> second = steps(resumed).stream()
                .filter(step -> "review-second".equals(step.get("stage"))).findFirst().orElseThrow();
        check.that("the second review is not satisfied by the first one's verdict",
                second.get("reused_from") == null);
        check.eq("it is a genuinely different reader", "loop-review-2", second.get("profile"));
        check.eq("and only that one stage was paid for", 1L,
                resumed.summaryReport().get("role_runs"));
        check.eq("leaving nothing outstanding", Boolean.FALSE,
                resumed.summaryReport().get("workflow_incomplete"));

        // The same continuation after a real change to what the work is judged by must not
        // borrow anything. Otherwise "budget-only" would be a hole rather than a distinction.
        Path moved = newProject(sandbox, "resume-acceptance-moved", "medium", 1);
        writeProfiles(home, sandbox, "resume-acceptance-moved", 1, 1);
        TaskLoop.Outcome firstPass = loop(moved, home, "am1");
        check.eq("one call buys an implementer and no reviewer", "budget_exhausted",
                firstPass.reason());
        // Raising the ceiling and moving the goal, in one edit. The first half would have been
        // forgiven on its own; the second half is the whole reason forgiveness is bounded.
        Files.writeString(moved.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt and also src/second.txt
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 10.0
                max_fix_attempts: 2
                """);
        TaskLoop.Outcome afterGoalChange = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(moved, "hello"), UserConfig.load(home), "am2", false,
                Map.of(), new TaskLoop.Continuation("am1", null, true));
        check.that("a changed goal carries no earlier verdict",
                afterGoalChange.summaryReport().get("reused_judgements") == null);
        check.contains("and says the change reached what the work is judged by",
                String.valueOf(afterGoalChange.summaryReport().get("reuse_declined")),
                "reaches what the work is judged by");
    }

    /**
     * A repair is a promise that somebody will read what it produces, and the roster is where
     * that promise is kept or broken.
     *
     * The order used to be wrong: repair, move the tree, then discover at the review stage
     * that the only vendor able to fill it is the one that just wrote the code. The run ended
     * holding an unjudged candidate, having paid for the repair that made it unjudgeable, and
     * reported the whole thing as a failed reviewer — which sends an operator to read a
     * transcript when the actual problem is a roster with one vendor on it.
     */
    @SuppressWarnings("unchecked")
    private void independenceChecks(Check check, Path sandbox, Path home) throws Exception {
        Path thin = newProject(sandbox, "roster-too-thin");
        Path implCounter = sandbox.resolve("roster-too-thin-impl.count");
        Files.deleteIfExists(implCounter);
        // Leaves the gate red on its first call, so a repair is genuinely asked for.
        writeProfile(home, "loop-impl", "implementer", "onevendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", implCounter, 2);
        // The whole roster: one vendor, wearing both hats.
        writeProfile(home, "loop-review", "reviewer", "onevendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("roster-too-thin-review.count"), 1);
        policy(home, "loop-review", "loop-impl");

        List<String> printed = new java.util.ArrayList<>();
        TaskLoop.Outcome refused = new TaskLoop(new ProcessRunner())
                .withProgress(printed::add)
                .run(new ConfigLoader().load(thin, "hello"), UserConfig.load(home), "iu1", false);

        check.that("a repair no later stage could judge is refused", !refused.ok());
        check.eq("and the stop is about the roster, not about the reviewer",
                "independent_review_unavailable", refused.reason());
        check.eq("nothing was spent on the repair", 1L, refused.summaryReport().get("role_runs"));
        check.eq("and no fix round was counted", 0L, refused.summaryReport().get("attempts_used"));
        Map<String, Object> gap =
                (Map<String, Object>) refused.summaryReport().get("unavailable_role");
        check.eq("the role nobody can fill is named", "reviewer", gap.get("role"));
        check.eq("as is the stage that needed it", "review", gap.get("needed_for"));
        check.eq("and the vendor it would have had to differ from", "onevendor",
                gap.get("must_differ_from_vendor"));
        check.contains("the operator is told to widen the roster, not to read a transcript",
                String.valueOf(refused.summaryReport().get("resolution")),
                "Add a profile from another vendor");
        check.contains("and the terminal says it stopped before the repair",
                String.join("\n", printed), "stopping before the repair rather than after it");
    }

    /**
     * The live run, scripted.
     *
     * A six-call ceiling, a chain that reviews twice and then looks at the page, and a
     * reviewer that objects, objects, and passes. That is what happened: implement, review,
     * fix, review, fix, review — six calls, a good candidate, one clean verdict, and three
     * stages the run never reached. It was reported as `budget_exhausted` and nothing else,
     * and an operator reading that had no way to tell it apart from a run that produced
     * nothing.
     *
     * Every claim here is one the summary could not previously make: that the shortfall was
     * arithmetic available before the first dispatch, that the review which passed is not
     * buried by the stop, and that the stages still owed are named rather than left to be
     * worked out from what happens to be absent.
     */
    @SuppressWarnings("unchecked")
    private void liveRunShapeChecks(Check check, Path sandbox, Path home) throws Exception {
        // The default reserve first. Six calls cannot finish this chain, so the second repair
        // is never started and four calls are kept rather than spent on progress the run
        // could not conclude.
        Path guarded = newProject(sandbox, "live-shape-full", "medium", 6, true);
        liveShapeProfiles(home, sandbox, "live-shape-full");
        liveShapePolicy(home, "full");
        TaskLoop.Outcome heldBack = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true))
                .run(new ConfigLoader().load(guarded, "hello"), UserConfig.load(home), "lf1", false);
        check.eq("the shipped default will not start a repair it cannot finish",
                "budget_insufficient_to_finish", heldBack.reason());
        check.eq("so two of the six calls are still unspent", 4L,
                heldBack.summaryReport().get("role_runs"));
        Map<String, Object> held = (Map<String, Object>) heldBack.summaryReport().get("budget_reserve");
        check.eq("and the reserve that refused it is the full one", "full",
                held.get("repair_reserve"));
        // The refusal lands on the second repair, at the review stage: one call for the fix,
        // one to re-read it, one for the review nobody has run yet.
        check.eq("naming what finishing would have needed", 3L,
                held.get("calls_needed_to_repair_and_finish"));
        check.contains("the operator is told the setting that would allow partial progress",
                String.valueOf(heldBack.summaryReport().get("resolution")),
                "budget.repair_reserve: partial");

        // The same fixture with the trade taken deliberately, which is the live run's arc:
        // implement, review, fix, review, fix, review — six calls, one clean verdict, and
        // three stages nobody reached.
        Path replay = newProject(sandbox, "live-shape", "medium", 6, true);
        liveShapeProfiles(home, sandbox, "live-shape");
        liveShapePolicy(home, "partial");

        List<String> printed = new java.util.ArrayList<>();
        TaskLoop.Outcome replayed = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true))
                .withProgress(printed::add)
                .run(new ConfigLoader().load(replay, "hello"), UserConfig.load(home), "lr1", false);

        check.that("six calls do not finish this chain", !replayed.ok());
        check.eq("and the ceiling is what stopped it", "budget_exhausted", replayed.reason());
        check.eq("naming which of the two ceilings", "max_role_runs",
                replayed.summaryReport().get("budget_limit_hit"));
        check.eq("every one of the six was used", 6L, replayed.summaryReport().get("role_runs"));
        check.eq("across two repair rounds", 2L, replayed.summaryReport().get("attempts_used"));

        // Before anything was dispatched.
        Map<String, Object> plan = (Map<String, Object>) replayed.summaryReport().get("budget_plan");
        check.eq("the clean-pass cost was known up front", 3L, plan.get("minimum_success_calls"));
        check.eq("and the mode that would be applied to a repair", "partial",
                plan.get("repair_reserve"));
        // Every stage that can send work back: the gate, both reviews, and the browser.
        check.eq("and every repair branch was costed with it", 4,
                listOf(plan.get("recovery_branches")).size());
        // A machine gate failing before any review costs one call to repair, and nothing would
        // read the result. Partial mode has to reach the first judging stage, not stop at the
        // arithmetic floor of the repair itself.
        Map<String, Object> gateBranch = (Map<String, Object>) listOf(plan.get("recovery_branches"))
                .stream().filter(row -> "gates".equals(((Map<String, Object>) row).get("stage")))
                .findFirst().orElseThrow();
        check.eq("a gate repair costs one call on its own", 1L,
                gateBranch.get("calls_needed_to_repair"));
        check.eq("but partial mode requires the review that would read it", 2L,
                gateBranch.get("calls_required_here"));
        check.contains("the terminal said so before the first vendor ran",
                String.join("\n", printed), "vendor call(s) if nothing has to be repaired");
        check.contains("and warned when the second repair could not reach the end",
                String.join("\n", printed), "this run will stop with the chain unfinished");

        // The three questions, answered separately.
        check.eq("the review that finally passed is not buried by the stop", Boolean.TRUE,
                replayed.summaryReport().get("candidate_review_passed"));
        check.eq("with no blocking finding left open", 0L,
                replayed.summaryReport().get("open_blocking_findings"));
        check.eq("while the chain is reported incomplete", Boolean.TRUE,
                replayed.summaryReport().get("workflow_incomplete"));
        check.eq("naming the stages that never got a turn",
                List.of("review-second", "browser"),
                listOf(replayed.summaryReport().get("pending_stages")).stream()
                        .map(row -> ((Map<String, Object>) row).get("stage")).toList());
        check.contains("and handing over the command that continues without paying twice",
                String.valueOf(replayed.summaryReport().get("safe_next_step")),
                "--continue lr1");

        // The same three answers where an operator actually reads them. A separation that
        // lives only in JSON is one nobody reading `warden report` benefits from.
        String rendered = dev.warden.ledger.RunReport.render(
                new dev.warden.ledger.RunReport().of(replay, "lr1"));
        check.contains("the rendered report keeps the review apart from the stop", rendered,
                "the candidate passed every review that ran");
        check.contains("names what the chain still owes", rendered, "review-second (not_reached)");
        check.contains("and prints the command that continues it", rendered, "do next");

        // The same chain with room to work, back on the default reserve: nothing about the
        // full mode prevents a run whose ceiling actually fits.
        Path funded = newProject(sandbox, "live-shape-funded", "medium", 12, true);
        liveShapeProfiles(home, sandbox, "live-shape-funded");
        liveShapePolicy(home, "full");
        TaskLoop.Outcome complete = new TaskLoop(new ProcessRunner(),
                (loaded, runId) -> stubVisual(true))
                .run(new ConfigLoader().load(funded, "hello"), UserConfig.load(home), "lr2", false);
        check.that("a cap that fits the chain reaches the human gate", complete.ok());
        check.eq("with nothing outstanding", Boolean.FALSE,
                complete.summaryReport().get("workflow_incomplete"));
        check.eq("the second reader really did read", "loop-review-2",
                steps(complete).stream()
                        .filter(step -> "review-second".equals(step.get("stage")))
                        .findFirst().orElseThrow().get("profile"));
        check.that("and the browser stage ran",
                listOf(complete.summaryReport().get("completed_stages")).contains("browser"));
    }

    /**
     * A carried verdict has to answer two questions, and only ever answered one.
     *
     * That the bytes have not moved is what the source fingerprint and the `.warden` hashes
     * are for. That the same judge, under the same rules, would be asked again was checked by
     * nothing — and could not have been, because both hashes cover the *project's* `.warden`
     * while the roster, the independence requirement, the prompt and the schema all live in
     * the operator's own home. Reproduced on stubs: swap the reviewer roster for a different
     * vendor with a reviewer set to object, continue the run, and it reaches the human gate
     * with `role_runs: 0` and the new mandatory reviewer never called.
     *
     * Each case below changes exactly one term and expects the review verdict to be declined
     * and the implementer's, which nothing called into question, to be kept.
     */
    @SuppressWarnings("unchecked")
    private void judgingContractChecks(Check check, Path sandbox, Path home) throws Exception {
        // A run that stops on something that was never about the work, leaving both verdicts
        // standing and reusable. Every case then continues from it.
        Path base = newProject(sandbox, "judging-contract", "medium", 20, true);
        writeProfiles(home, sandbox, "judging-contract", 1, 1);
        TaskLoop.Outcome stopped = new TaskLoop(new ProcessRunner(), (l, r) ->
                new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                        Map.of("message", "no browser"))).run(
                new ConfigLoader().load(base, "hello"), UserConfig.load(home), "jc1", false);
        check.eq("the run stops on the environment, not the work",
                "visual_qa_unavailable", stopped.reason());

        // The control: nothing changed, so both verdicts are kept and nothing is dispatched.
        TaskLoop.Outcome unchanged = continueFrom(base, home, "jc1", "jc-control");
        check.eq("an unchanged contract keeps both verdicts",
                List.of("implement", "review"), reusedStages(unchanged));
        check.eq("and pays for nothing", 0L, unchanged.summaryReport().get("role_runs"));

        // A carried verdict is still a verdict. The reuse branch used to add the step row and
        // return, restoring no coverage — so a run whose every stage was carried reported
        // `candidate_review_passed: false`, and `warden report --text` told the operator the
        // candidate had not passed review about a candidate a reviewer had passed.
        check.eq("a fully reused run still reports the review that passed", Boolean.TRUE,
                unchanged.summaryReport().get("candidate_review_passed"));
        check.eq("with nothing outstanding", Boolean.FALSE,
                unchanged.summaryReport().get("workflow_incomplete"));
        Map<String, Object> carriedCoverage =
                (Map<String, Object>) listOf(unchanged.summaryReport().get("review_coverage"))
                        .stream().filter(row -> "review".equals(((Map<String, Object>) row).get("stage")))
                        .findFirst().orElseThrow();
        check.eq("marked as carried rather than as a fresh dispatch", "reused",
                carriedCoverage.get("source"));
        check.eq("naming the run it came from", "jc1", carriedCoverage.get("reused_from"));
        check.eq("and the profile that reached it", "loop-review", carriedCoverage.get("profile"));
        check.eq("about the candidate the reuse was checked against",
                unchanged.summaryReport().get("candidate_fingerprint"),
                carriedCoverage.get("candidate_fingerprint"));
        check.contains("and the rendered report says so too",
                dev.warden.ledger.RunReport.render(
                        new dev.warden.ledger.RunReport().of(base, "jc-control")),
                "the candidate passed every review that ran");

        // 1. The roster is swapped for a different vendor. This is the reproduction.
        writeProfile(home, "audit-review", "reviewer", "auditvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("judging-contract-audit.count"), 99);
        policy(home, "audit-review", "loop-impl");
        TaskLoop.Outcome swapped = continueFrom(base, home, "jc1", "jc-roster");
        check.eq("a swapped reviewer roster does not inherit the old reviewer's verdict",
                List.of("implement"), reusedStages(swapped));
        check.contains("and says which term moved",
                declinedReason(swapped, "review"), "roster changed");
        check.that("so the new reviewer actually ran", steps(swapped).stream()
                .anyMatch(step -> "audit-review".equals(step.get("profile"))));
        // Its objection is honoured rather than skipped past. The stand-in implementer rewrites
        // identical bytes, so the repair achieves nothing and the run says that rather than
        // spending its remaining fix rounds rediscovering the same finding.
        check.eq("and its objection stands rather than being skipped past",
                "repair_made_no_progress", swapped.reason());

        // 2. Independence alone. Same profile, same prompt, one flag.
        writeProfiles(home, sandbox, "judging-contract", 1, 1);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                """);
        TaskLoop.Outcome independence = continueFrom(base, home, "jc1", "jc-independence");
        check.eq("dropping the independence requirement is a change of terms",
                List.of("implement"), reusedStages(independence));
        check.contains("named as such", declinedReason(independence, "review"),
                "require_independent_vendor changed");

        // 3. The prompt the reviewer was given.
        writeProfiles(home, sandbox, "judging-contract", 1, 1);
        Path template = home.resolve("prompts/reviewer.md");
        String templateBefore = Files.readString(template);
        Files.writeString(template, templateBefore + "\nAlso check the changelog.\n");
        TaskLoop.Outcome reprompted = continueFrom(base, home, "jc1", "jc-prompt");
        check.eq("an edited reviewer prompt is a different reviewer",
                List.of("implement"), reusedStages(reprompted));
        check.contains("named as such", declinedReason(reprompted, "review"),
                "prompt_template_sha256 changed");
        // Restored, so the next case measures the term it means to change and not this one.
        Files.writeString(template, templateBefore);

        // 4. The schema its answer has to satisfy.
        Path schema = home.resolve("schemas/reviewer.json");
        String original = Files.readString(schema);
        Files.writeString(schema, original.replace("\"findings\"", "\"findings\" "));
        TaskLoop.Outcome reschema = continueFrom(base, home, "jc1", "jc-schema");
        check.eq("so is an edited answer schema", List.of("implement"), reusedStages(reschema));
        check.contains("named as such", declinedReason(reschema, "review"),
                "json_schema_sha256 changed");
        Files.writeString(schema, original);

        // 5. The workflow, keeping the stage name. A finding that used to send work back and
        // now stops the run is a different bargain, under a label that did not move.
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: stop }
                """);
        TaskLoop.Outcome rerouted = continueFrom(base, home, "jc1", "jc-routing");
        check.eq("a stage that kept its name but changed its routing is not the same stage",
                List.of("implement"), reusedStages(rerouted));
        check.contains("named as such", declinedReason(rerouted, "review"),
                "on_findings changed");
    }

    private TaskLoop.Outcome continueFrom(Path project, Path home, String priorRunId, String runId)
            throws Exception {
        return new TaskLoop(new ProcessRunner(), (l, r) -> stubVisual(true)).run(
                new ConfigLoader().load(project, "hello"), UserConfig.load(home), runId, false,
                Map.of(), new TaskLoop.Continuation(priorRunId, null, true));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> reusedStages(TaskLoop.Outcome outcome) {
        Object reused = outcome.summaryReport().get("reused_judgements");
        if (!(reused instanceof Map<?, ?> map)) return List.of();
        return listOf(((Map<String, Object>) map).get("stages"));
    }

    @SuppressWarnings("unchecked")
    private static String declinedReason(TaskLoop.Outcome outcome, String stage) {
        Object declined = outcome.summaryReport().get("reuse_declined_by_stage");
        if (!(declined instanceof Map<?, ?> map)) return "";
        return String.valueOf(((Map<String, Object>) map).get(stage));
    }

    /**
     * A carried verdict must be about the candidate it is spent on, not merely about the prior
     * run's final tree.
     *
     * The first check proved the same judge under the same rules would be asked again. It did
     * not prove the verdict describes the tree that stands at the moment it is consumed, and
     * three ways for those to diverge slipped through:
     *
     *  - A1: an ordinary implementer re-dispatch during the resume moves the tree after the
     *    reuse pool was built, and a review carried for the old tree is spent on the new one.
     *  - A2: the prior run's review judged a revision that a later repair replaced, so the
     *    review's tree never was the prior run's final tree — the run-level fingerprint check
     *    passes while the per-verdict one should not.
     *  - B: the workflow reassigns a stage's role under a stable name, and the old role's
     *    verdict stands in for the new role's because the contract check compared the old role
     *    against itself.
     */
    @SuppressWarnings("unchecked")
    private void resumeCurrencyChecks(Check check, Path sandbox, Path home) throws Exception {
        // A1 — the implementer rewrites during the resume.
        Path a1 = newProject(sandbox, "resume-writer-rewrites", "medium", 20, true);
        Path a1impl = sandbox.resolve("resume-writer-rewrites-impl.count");
        Path a1review = sandbox.resolve("resume-writer-rewrites-review.count");
        Files.deleteIfExists(a1impl);
        Files.deleteIfExists(a1review);
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", a1impl, 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", a1review, 1);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome a1first = new TaskLoop(new ProcessRunner(),
                (l, r) -> new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                        Map.of("message", "no browser"))).run(
                new ConfigLoader().load(a1, "hello"), UserConfig.load(home), "wr1", false);
        check.eq("A1 first run stops on the missing browser", "visual_qa_unavailable",
                a1first.reason());
        check.eq("its implementer wrote once", "1", Files.readString(a1impl).strip());
        check.eq("its reviewer read once", "1", Files.readString(a1review).strip());

        // Only the implementer's contract changes, so only the implement stage should decline
        // reuse on its own terms; the review declines because the re-dispatch moved the tree.
        Path implPrompt = home.resolve("prompts/implementer.md");
        Files.writeString(implPrompt, Files.readString(implPrompt) + "\nAn added instruction.\n");
        TaskLoop.Outcome a1resumed = continueFrom(a1, home, "wr1", "wr2");
        check.eq("the re-dispatched implementer changed the tree", "2",
                Files.readString(a1impl).strip());
        check.that("the review is not carried across that change",
                !reusedStages(a1resumed).contains("review"));
        check.eq("it re-ran on the new tree instead", "2", Files.readString(a1review).strip());
        check.that("and the resume records that the carried verdict was stale at consumption",
                listOf(a1resumed.summaryReport().get("reuse_declined_at_consumption")).stream()
                        .anyMatch(row -> row instanceof Map<?, ?> m && "review".equals(m.get("stage"))));
        check.eq("so the candidate that passed review is the one now in the worktree",
                Boolean.TRUE, a1resumed.summaryReport().get("candidate_review_passed"));

        // A2 — the prior run's review judged a revision a later repair replaced.
        Path a2 = newProject(sandbox, "resume-stale-review", "medium", 20, true);
        Path a2impl = sandbox.resolve("resume-stale-review-impl.count");
        Files.deleteIfExists(a2impl);
        Files.deleteIfExists(sandbox.resolve("resume-stale-review-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", a2impl, 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("resume-stale-review-review.count"), 1);
        // The review does not recheck after a fix, so a browser repair leaves its verdict about
        // an earlier revision — exactly the shape the prior run retires.
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix, recheck_after_fix: false }
                    - { stage: browser, run: visual_harness, when: [visual_qa_required], on_fail: fix, recheck_after_fix: true }
                """);
        int[] a2visual = {0};
        TaskLoop.Outcome a2first = new TaskLoop(new ProcessRunner(),
                (l, r) -> ++a2visual[0] == 1 ? stubVisual(false)
                        : new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                                Map.of("message", "no browser"))).run(
                new ConfigLoader().load(a2, "hello"), UserConfig.load(home), "sr-first", false);
        check.eq("A2 first run stops on the browser after a repair", "visual_qa_unavailable",
                a2first.reason());
        check.eq("the repair moved the tree", "2", Files.readString(a2impl).strip());
        check.eq("and the prior run retired its review rather than keeping it", Boolean.FALSE,
                a2first.summaryReport().get("candidate_review_passed"));
        check.that("naming it as judging an earlier candidate",
                listOf(a2first.summaryReport().get("stages_judging_an_earlier_candidate"))
                        .contains("review"));

        TaskLoop.Outcome a2resumed = continueFrom(a2, home, "sr-first", "sr-second");
        check.that("the resume does not resurrect the retired review",
                !reusedStages(a2resumed).contains("review"));
        // The repair superseded the implement stage's own tree, so that stage verdict is stale
        // too: the tree it produced was replaced before the run ended. Nothing is carried, and
        // the resume rebuilds honestly rather than reusing a verdict about a vanished revision.
        check.that("nothing stale is carried across the repair",
                reusedStages(a2resumed).isEmpty());
        check.contains("and the decline says the prior run had already retired the review",
                declinedReason(a2resumed, "review"), "retired");
        check.that("the resume reaches the human gate", a2resumed.ok());
        check.eq("with a review that genuinely read this candidate", Boolean.TRUE,
                a2resumed.summaryReport().get("candidate_review_passed"));

        // B — the stage keeps its name but changes its role.
        Path b = newProject(sandbox, "resume-role-swapped", "medium", 20, true);
        Files.deleteIfExists(sandbox.resolve("resume-role-swapped-impl.count"));
        Files.deleteIfExists(sandbox.resolve("resume-role-swapped-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("resume-role-swapped-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("resume-role-swapped-review.count"), 1);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome bfirst = new TaskLoop(new ProcessRunner(),
                (l, r) -> new VisualQaRunner.Outcome(false, "visual_qa_unavailable", null,
                        Map.of("message", "no browser"))).run(
                new ConfigLoader().load(b, "hello"), UserConfig.load(home), "rs1", false);
        check.eq("B first run stops on the missing browser", "visual_qa_unavailable",
                bfirst.reason());

        // A separate reader for the reassigned stage, with its own prompt and schema so its
        // artifact is an architect's and not a reviewer's.
        Files.writeString(home.resolve("prompts/architect.md"), "Assess the design.\n");
        Files.writeString(home.resolve("schemas/architect.json"), """
                { "title": "Architect artifact", "type": "object",
                  "required": ["role", "task_id", "status", "summary"],
                  "properties": {
                    "role": { "const": "architect" },
                    "task_id": { "type": "string" },
                    "status": { "enum": ["completed", "aborted"] },
                    "summary": { "type": "string" } } }
                """);
        writeProfile(home, "loop-architect", "architect", "architectvendor", true,
                "architect", "role, task_id, status, summary",
                "verdict-pass", sandbox.resolve("resume-role-swapped-arch.count"), 1);
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review], strategy: first, require_independent_vendor: true }
                  architect: { profiles: [loop-architect], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: architect, on_fail: stop }
                    - { stage: browser, run: visual_harness, when: [visual_qa_required], on_fail: fix, recheck_after_fix: true }
                """);
        TaskLoop.Outcome bresumed = continueFrom(b, home, "rs1", "rs2");
        check.that("the reviewer's verdict does not satisfy the architect stage",
                !reusedStages(bresumed).contains("review"));
        check.contains("declined because the stage now runs a role that profile cannot fill",
                declinedReason(bresumed, "review"), "architect");
        check.that("and the architect actually ran, as itself",
                steps(bresumed).stream().anyMatch(s -> "loop-architect".equals(s.get("profile"))
                        && "review".equals(s.get("stage"))
                        && !Boolean.TRUE.toString().equals(String.valueOf(s.get("reused")))
                        && s.get("reused_from") == null));
        check.that("the implementer's own verdict, unchallenged, is still reused",
                reusedStages(bresumed).contains("implement"));
    }

    /**
     * A finding a later round can recognise, and a repair that is measured rather than assumed.
     *
     * Two reports of one defect used to be two unrelated blobs of prose, so the loop could not
     * say whether a repair had closed anything or whether it was paying to rediscover the same
     * objection. Measured live on 2026-09-07: a repair cost $0.031 and changed nothing, and the
     * recheck then spent $1.46 for one reviewer to reach the same pass and another the same
     * fail. Nothing compared the tree before the repair with the tree after it.
     */
    @SuppressWarnings("unchecked")
    private void findingHistoryChecks(Check check, Path sandbox, Path home) throws Exception {
        Path stalled = newProject(sandbox, "repair-changes-nothing", "medium", 20);
        Files.deleteIfExists(sandbox.resolve("repair-changes-nothing-impl.count"));
        // Writes the result once so the gate passes, then reports success and changes nothing.
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("repair-changes-nothing-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-repeats-p1", sandbox.resolve("repair-changes-nothing-review.count"), 1);
        policy(home, "loop-review", "loop-impl");

        List<String> printed = new java.util.ArrayList<>();
        TaskLoop.Outcome first = new TaskLoop(new ProcessRunner())
                .withProgress(printed::add)
                .run(new ConfigLoader().load(stalled, "hello"), UserConfig.load(home), "fh1", false);

        // The reviewer objected, the implementer was handed it and wrote identical bytes, and
        // the reviewer then said the same thing about the same tree.
        check.that("a repair that achieves nothing stops the run", !first.ok());
        check.eq("and says so in its own words", "repair_made_no_progress", first.reason());
        Map<String, Object> stall =
                (Map<String, Object>) first.summaryReport().get("repair_made_no_progress");
        check.eq("naming the stage whose finding went unaddressed", "review", stall.get("at_stage"));
        check.eq("and the finding still open, by id",
                List.of("still-broken"), stall.get("open_blocking_ids"));
        check.contains("the terminal says why another round was not bought",
                String.join("\n", printed), "the same finding came back");
        check.contains("and the next step points at a person, not another fix round",
                String.valueOf(first.summaryReport().get("safe_next_step")),
                "not for another fix round");

        // The history is the point: one row per judging round, and what moved between them.
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) first.summaryReport().get("finding_history");
        check.eq("both readings were recorded", 2, history.size());
        check.eq("for the stage that judged", "review", history.get(0).get("stage"));
        check.eq("naming the blocking finding by a stable id",
                List.of("still-broken"), history.get(0).get("blocking_ids"));
        Map<String, Object> finding =
                ((List<Map<String, Object>>) history.get(0).get("findings")).get(0);
        check.eq("the vendor's own id is kept", "vendor", finding.get("id_source"));
        check.eq("and its category is taken as offered", "product_defect", finding.get("category"));
        check.eq("recorded as the vendor's choice", "vendor", finding.get("category_source"));
        check.that("the round records the tree it judged",
                history.get(0).get("candidate_fingerprint") instanceof String);

        // The second round is the comparison, and it is what the stop rests on.
        Map<String, Object> second = history.get(1);
        check.eq("the repair closed nothing", List.of(), second.get("closed"));
        check.eq("the same finding persisted", List.of("still-broken"),
                second.get("persisted"));
        check.eq("and nothing new was found", List.of(), second.get("new_findings"));
        check.eq("against a candidate that never moved", Boolean.FALSE,
                second.get("candidate_moved"));
    }

    /**
     * A P1 the reviewer itself said is not the implementer's does not buy a repair.
     *
     * Categories used to be recorded and then ignored: every P1 drove a fix round, and an
     * unrelated edit that moved the fingerprint defeated the no-progress detector. The live
     * run paid a repair plus a re-read for a contract_gap the implementer had already refused.
     */
    @SuppressWarnings("unchecked")
    private void nonactionableBlockerChecks(Check check, Path sandbox, Path home) throws Exception {
        Path gap = newProject(sandbox, "contract-gap-no-repair", "medium", 20);
        Files.deleteIfExists(sandbox.resolve("contract-gap-no-repair-impl.count"));
        Files.deleteIfExists(sandbox.resolve("contract-gap-no-repair-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("contract-gap-no-repair-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-categorised", sandbox.resolve("contract-gap-no-repair-review.count"), 1);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome stopped = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(gap, "hello"), UserConfig.load(home), "cg1", false);
        check.that("a contract_gap P1 does not reach the human gate", !stopped.ok());
        check.eq("it stops for a person rather than buying a repair",
                "blocking_findings_remain", stopped.reason());
        check.eq("naming the leftover by id", List.of("acceptance-too-weak"),
                stopped.summaryReport().get("nonactionable_blocking_ids"));
        check.eq("the implementer ran once, for the original write, not again", 1L,
                steps(stopped).stream().filter(s -> "implementer".equals(s.get("step"))).count());
        check.eq("and the reviewer was not paid to re-read the same tree", 1L,
                steps(stopped).stream().filter(s -> "reviewer".equals(s.get("step"))).count());
        check.contains("the next step points at the task, not another fix",
                String.valueOf(stopped.summaryReport().get("safe_next_step")),
                "not the implementer's to close");
        Map<String, Object> filed =
                ((List<Map<String, Object>>) ((List<Map<String, Object>>) stopped.summaryReport()
                        .get("finding_history")).get(0).get("findings")).get(0);
        check.eq("the vendor's category is what routed it", "contract_gap", filed.get("category"));

        Path mixed = newProject(sandbox, "mixed-then-gap", "medium", 20);
        Files.deleteIfExists(sandbox.resolve("mixed-then-gap-impl.count"));
        Files.deleteIfExists(sandbox.resolve("mixed-then-gap-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("mixed-then-gap-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-mixed-then-gap", sandbox.resolve("mixed-then-gap-review.count"), 1);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome afterDefect = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(mixed, "hello"), UserConfig.load(home), "mg1", false);
        check.that("closing the product_defect does not pass the run", !afterDefect.ok());
        check.eq("the leftover gap still stops for a person",
                "blocking_findings_remain", afterDefect.reason());
        check.eq("without buying a second repair for it", 2L,
                steps(afterDefect).stream().filter(s -> "implementer".equals(s.get("step"))).count());
        check.eq("the reviewer read twice: once to file both, once to see the gap remain", 2L,
                steps(afterDefect).stream().filter(s -> "reviewer".equals(s.get("step"))).count());
        check.eq("and names the leftover", List.of("acceptance-too-weak"),
                afterDefect.summaryReport().get("nonactionable_blocking_ids"));
    }

    /**
     * Identity and category, at the level they are decided.
     *
     * A vendor that names neither still has to produce a finding a second round can recognise,
     * and a vendor that invents a category must not have it believed.
     */
    private void findingIdentityChecks(Check check) {
        Map<String, Object> bare = Map.of("findings", List.of(Map.of(
                "severity", "P1", "path", "src/a.txt", "message", "The label   is CLIPPED.")));
        Map<String, Object> reworded = Map.of("findings", List.of(Map.of(
                "severity", "P1", "path", "src/a.txt", "message", "the label is clipped")));
        var one = dev.warden.ledger.Findings.of(bare).get(0);
        var two = dev.warden.ledger.Findings.of(reworded).get(0);
        check.eq("a finding with no id gets one derived", "derived", one.idSource());
        check.eq("stable across case and whitespace and trailing punctuation",
                one.id(), two.id());
        check.that("and it looks like an id rather than a sentence",
                one.id().startsWith("f-") && one.id().length() == 14);
        check.eq("a category nobody offered is not invented",
                "product_defect", one.category());
        check.eq("and the report says Warden chose it",
                "defaulted_absent", one.categorySource());

        Map<String, Object> invented = Map.of("findings", List.of(Map.of(
                "severity", "P1", "path", "src/a.txt", "message", "x",
                "category", "vibes")));
        var third = dev.warden.ledger.Findings.of(invented).get(0);
        check.eq("a category outside the set is refused, not passed through",
                "product_defect", third.category());
        check.eq("and the refusal is visible", "defaulted_unrecognised", third.categorySource());

        Map<String, Object> other = Map.of("findings", List.of(Map.of(
                "severity", "P1", "path", "src/b.txt", "message", "the label is clipped")));
        check.that("the same words about a different file are a different finding",
                !one.id().equals(dev.warden.ledger.Findings.of(other).get(0).id()));

        Map<String, Object> noisy = Map.of("findings", List.of(
                Map.of("severity", "P2", "path", "src/a.txt", "message", "nit"),
                Map.of("severity", "P1", "path", "src/a.txt", "message", "real")));
        check.eq("only what blocks acceptance is counted as blocking", 1,
                dev.warden.ledger.Findings.blockingIds(
                        dev.warden.ledger.Findings.of(noisy)).size());
    }

    @SuppressWarnings("unchecked")
    private void cumulativeClosureChecks(Check check, Path sandbox, Path home) throws Exception {
        for (String mode : List.of("review-closes-three", "review-reinvents-closed")) {
            Path project = newProject(sandbox, mode, "medium", 20);
            Path taskFile = project.resolve(".warden/tasks/hello.yaml");
            Files.writeString(taskFile, Files.readString(taskFile).replace("max_fix_attempts: 2", "max_fix_attempts: 3"));
            writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                    "implementer", "role, task_id, status, summary, files_changed",
                    "impl-grows", sandbox.resolve(mode + "-impl.count"), 1);
            writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                    "reviewer", "role, task_id, status, verdict, summary, findings",
                    mode, sandbox.resolve(mode + "-review.count"), 1);
            policy(home, "loop-review", "loop-impl");
            TaskLoop.Outcome result = new TaskLoop(new ProcessRunner()).run(
                    new ConfigLoader().load(project, "hello"), UserConfig.load(home), "closure", false);
            check.eq(mode + " result", mode.equals("review-closes-three")
                    ? "ready_for_human" : "finding_protocol_failure", result.reason());
            String repair = Files.readString(project.resolve(".warden/runs/closure/context/fix-3-review.md"));
            check.contains("third repair retains A", repair, "- A");
            check.contains("third repair retains B", repair, "- B");
            String recheck = Files.readString(project.resolve(".warden/runs/closure/context/fix-3-review-recheck.md"));
            check.contains("third reviewer gets cumulative registry", recheck, "Cumulative finding registry");
            check.contains("third reviewer gets original closed defect", recheck, "defect A");
            check.that("terminal state has safe next step", result.summaryReport().get("safe_next_step") instanceof String);
        }
        // Cap 3: implement + review spend 2, the repair reserve is 2, so the first run
        // stops before any fix. Cap 6 used to let two repairs through; the implementer
        // then judged a stale revision, resume rewrote the tree, and dropping A was
        // allowed because candidate_moved was already true.
        Path project = newProject(sandbox, "closure-resume", "medium", 3);
        Path taskFile = project.resolve(".warden/tasks/hello.yaml");
        Files.writeString(taskFile, Files.readString(taskFile).replace("max_fix_attempts: 2", "max_fix_attempts: 3"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("closure-resume-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-closes-three", sandbox.resolve("closure-resume-review.count"), 1);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome stopped = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(project, "hello"), UserConfig.load(home), "before", false);
        check.eq("closure chain stops at reserve", "budget_insufficient_to_finish", stopped.reason());
        Files.writeString(taskFile, Files.readString(taskFile).replace("max_role_runs: 3", "max_role_runs: 20"));
        TaskLoop.Outcome resumed = continueFrom(project, home, "before", "after");
        // review-closes-three drops A on its second call. The resume re-dispatches the
        // reviewer at attempt 0 against the restored round, on a tree the reused
        // implementer did not touch. That drop used to reach ready_for_human because
        // the laundering guard lived only inside the fix loop.
        check.eq("dropping a P1 on resume of an unchanged tree is not a pass",
                "severity_downgraded_without_change", resumed.reason());
        Map<String, Object> caught =
                (Map<String, Object>) resumed.summaryReport().get("severity_downgraded_without_change");
        check.that("the stop recorded which ids moved", caught != null);
        if (caught != null) {
            check.eq("naming the finding the resumed first reading dropped",
                    List.of("A"), caught.get("retracted_ids"));
            check.eq("the other path is empty", List.of(), caught.get("downgraded_ids"));
        }
        String history = resumed.summaryReport().get("finding_history").toString();
        check.contains("resume retains original closed defect", history, "defect A");
        check.contains("resume records history provenance", history, "source_run=before");
        String context = Files.readString(project.resolve(".warden/runs/after/context/fix-0-review-recheck.md"));
        check.contains("resume reviewer gets closures before first new repair", context, "defect A");
        check.eq("resume did not buy a repair after the drop", 0L,
                steps(resumed).stream().filter(s -> "implementer".equals(s.get("step"))
                        && s.get("reused_from") == null).count());
    }

    /**
     * A later reviewer inherits the registry, with stage provenance, and cannot raise a
     * closed finding as a new requirement on the original evidence.
     *
     * Round comparison stays per stage so one reader omitting another's finding is not a
     * closure. The registry is the run's lifecycle: review-second's first dispatch used to
     * get no package and treat A as an initial finding.
     */
    @SuppressWarnings("unchecked")
    private void crossStageRegistryChecks(Check check, Path sandbox, Path home) throws Exception {
        Path handoff = newProject(sandbox, "registry-handoff", "medium", 20);
        Path handoffTask = handoff.resolve(".warden/tasks/hello.yaml");
        Files.writeString(handoffTask, Files.readString(handoffTask)
                .replace("max_fix_attempts: 2", "max_fix_attempts: 3"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("registry-handoff-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-closes-one", sandbox.resolve("registry-handoff-review.count"), 2);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-reports-inherited", sandbox.resolve("registry-handoff-review2.count"), 1);
        twoReviewerPolicy(home);

        TaskLoop.Outcome handed = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(handoff, "hello"), UserConfig.load(home), "handoff", false);
        check.eq("an honest second reader still reaches the human gate",
                "ready_for_human", handed.reason());
        Map<String, Object> secondStep = steps(handed).stream()
                .filter(s -> "review-second".equals(s.get("stage"))).findFirst().orElse(null);
        check.that("review-second dispatched", secondStep != null);
        if (secondStep == null) return;
        Path secondArtifactFile = handoff.resolve(String.valueOf(secondStep.get("artifact_path")));
        Path secondPromptFile = secondArtifactFile.getParent().getParent().resolve("prompts/reviewer.md");
        String secondPrompt = Files.readString(secondPromptFile);
        check.contains("the second reviewer is told a previous stage already read",
                secondPrompt, "A previous stage has already read this candidate");
        check.contains("and is given the cumulative registry",
                secondPrompt, "Cumulative finding registry");
        check.contains("including the closed defect", secondPrompt, "reproduce A");
        check.contains("with the stage that filed it", secondPrompt, "recorded_at_stage");
        check.contains("and the repair receipt", secondPrompt, "What the implementer says it did");
        Map<String, Object> secondArtifact = Json.parseObject(Files.readString(secondArtifactFile));
        check.contains("the inherited package reached the vendor",
                String.valueOf(secondArtifact.get("summary")), "inherited=true");
        check.contains("carrying the registry",
                String.valueOf(secondArtifact.get("summary")), "registry=true");
        check.contains("carrying the repair",
                String.valueOf(secondArtifact.get("summary")), "repair=true");
        check.contains("carrying closed A",
                String.valueOf(secondArtifact.get("summary")), "closed_A=true");
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) handed.summaryReport().get("finding_history");
        Map<String, Object> secondRound = history.stream()
                .filter(row -> "review-second".equals(row.get("stage"))).findFirst().orElseThrow();
        check.eq("omitting the first reviewer's findings is not this stage's retraction",
                null, secondRound.get("blocking_retracted"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondRegistry =
                (List<Map<String, Object>>) secondRound.get("finding_registry");
        Map<String, Object> closedA = secondRegistry.stream()
                .filter(row -> "A".equals(row.get("id"))).findFirst().orElseThrow();
        check.eq("A stays closed in the inherited registry", "closed", closedA.get("status"));
        check.eq("and still names the stage that filed it", "review", closedA.get("recorded_at_stage"));

        Path reraise = newProject(sandbox, "registry-reraise", "medium", 20);
        Path reraiseTask = reraise.resolve(".warden/tasks/hello.yaml");
        Files.writeString(reraiseTask, Files.readString(reraiseTask)
                .replace("max_fix_attempts: 2", "max_fix_attempts: 3"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("registry-reraise-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-closes-one", sandbox.resolve("registry-reraise-review.count"), 2);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-reraise-stale", sandbox.resolve("registry-reraise-review2.count"), 1);
        twoReviewerPolicy(home);

        TaskLoop.Outcome refused = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(reraise, "hello"), UserConfig.load(home), "reraise", false);
        check.that("re-raising a closed finding does not reach the human gate", !refused.ok());
        check.eq("it stops as a protocol failure, not another repair",
                "finding_protocol_failure", refused.reason());
        check.eq("the second reviewer did not buy a repair", 2L,
                steps(refused).stream().filter(s -> "implementer".equals(s.get("step"))).count());
        check.contains("naming that A needed new evidence",
                String.valueOf(refused.summaryReport().get("finding_protocol_failure")),
                "needs new evidence_refs");
    }

    /**
     * A stage rechecked after another stage's closure can still see that closure.
     *
     * review passes; review-second files B, then closes it on a tree the repair moved and
     * files C instead, which buys a second repair; review is rechecked and raises B again on
     * the evidence B was closed with. No round delta can catch that, because review's own
     * last round never held B. The registry can, once it belongs to the run rather than to
     * one stage's chain of readings.
     */
    @SuppressWarnings("unchecked")
    private void crossStageRecheckChecks(Check check, Path sandbox, Path home) throws Exception {
        Path project = newProject(sandbox, "registry-recheck", "medium", 20);
        Path taskFile = project.resolve(".warden/tasks/hello.yaml");
        Files.writeString(taskFile, Files.readString(taskFile)
                .replace("max_fix_attempts: 2", "max_fix_attempts: 3"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("registry-recheck-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-raises-closed-on-recheck",
                sandbox.resolve("registry-recheck-review.count"), 1);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-second-closes-then-new",
                sandbox.resolve("registry-recheck-review2.count"), 1);
        twoReviewerPolicy(home);

        TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(project, "hello"), UserConfig.load(home), "recheck", false);
        check.eq("raising another stage's closure on its original evidence stops the run",
                "finding_protocol_failure", outcome.reason());
        check.contains("naming the rule it broke",
                String.valueOf(outcome.summaryReport().get("finding_protocol_failure")),
                "needs new evidence_refs");
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) outcome.summaryReport().get("finding_history");
        Map<String, Object> closure = history.stream()
                .filter(row -> "review-second".equals(row.get("stage")))
                .reduce((first, second) -> second).orElseThrow();
        check.eq("the stage that filed B is the one that closed it",
                List.of("B"), closure.get("closed"));
        Map<String, Object> firstReading = history.stream()
                .filter(row -> "review".equals(row.get("stage"))).findFirst().orElseThrow();
        check.eq("the passing first reading closed nothing of anyone else's",
                List.of(), firstReading.get("closed_ids"));
    }

    /**
     * Evidence already used for a previous incarnation of A is not new after a
     * later report replaced evidence_refs. C stays open so the loop can close A
     * twice; the fifth reading re-files A on receipt-0 and must stop as a
     * protocol failure before another repair.
     */
    @SuppressWarnings("unchecked")
    private void historicalEvidenceReuseChecks(Check check, Path sandbox, Path home)
            throws Exception {
        Path project = newProject(sandbox, "historical-evidence", "medium", 20);
        Path taskFile = project.resolve(".warden/tasks/hello.yaml");
        Files.writeString(taskFile, Files.readString(taskFile)
                .replace("max_fix_attempts: 2", "max_fix_attempts: 5"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("historical-evidence-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-reuses-historical-evidence",
                sandbox.resolve("historical-evidence-review.count"), 1);
        policy(home, "loop-review", "loop-impl");

        TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(project, "hello"), UserConfig.load(home),
                "historical", false);
        check.eq("reusing a receipt from an earlier incarnation stops the run",
                "finding_protocol_failure", outcome.reason());
        check.contains("naming the rule it broke",
                String.valueOf(outcome.summaryReport().get("finding_protocol_failure")),
                "needs new evidence_refs");
        check.eq("and does not buy another repair after that reading", 5L,
                steps(outcome).stream().filter(s -> "implementer".equals(s.get("step"))).count());
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) outcome.summaryReport().get("finding_history");
        Map<String, Object> secondClose = history.stream()
                .filter(row -> "review".equals(row.get("stage"))
                        && List.of("A").equals(row.get("closed")))
                .reduce((first, second) -> second).orElseThrow();
        List<Map<String, Object>> registry =
                (List<Map<String, Object>>) secondClose.get("finding_registry");
        Map<String, Object> closedA = registry.stream()
                .filter(row -> "A".equals(row.get("id"))).findFirst().orElseThrow();
        check.eq("the last evidence on A is still the second incarnation",
                List.of("receipt-1"), closedA.get("evidence_refs"));
        check.eq("and the first receipt is still on the record",
                List.of("receipt-0", "receipt-1"), closedA.get("seen_evidence_refs"));
    }

    private void twoReviewerPolicy(Path home) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review, loop-review-2], strategy: rotate, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix, recheck_after_fix: true }
                    - { stage: review-second, run: role, role: reviewer, on_fail: stop, on_findings: fix }
                """);
    }

    /**
     * What a repair and a re-reading are actually told.
     *
     * A fix round used to be briefed with the failure text and nothing else, which made every
     * round after the first worse informed than the first: no statement of the goal it was
     * still working towards, no memory of what it had already closed, no idea how much room
     * was left. And a reviewer was told nothing at all, so it re-investigated from scratch
     * every round and its findings drifted — the same defect described differently, which is
     * also why two rounds could not be compared.
     */
    @SuppressWarnings("unchecked")
    private void handoverPackageChecks(Check check, Path sandbox, Path home) throws Exception {
        Path handover = newProject(sandbox, "handover-packages", "medium", 20);
        Files.deleteIfExists(sandbox.resolve("handover-packages-impl.count"));
        Files.deleteIfExists(sandbox.resolve("handover-packages-review.count"));
        // The implementer moves the tree on its repair, so the round is real progress and the
        // reviewer's second reading is of something new.
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("handover-packages-impl.count"), 1);
        // Objects once, then passes — and reports back what its prompt contained.
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-reports-context", sandbox.resolve("handover-packages-review.count"), 2);
        policy(home, "loop-review", "loop-impl");

        TaskLoop.Outcome done = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(handover, "hello"), UserConfig.load(home), "hp1", false);
        check.that("the round closes and the run reaches the human gate", done.ok());

        // The repair package: the goal, the constraints and the budget, not just the failure.
        String repair = Files.readString(handover.resolve(".warden/runs/hp1/context/fix-1-review.md"));
        check.contains("the repair is given the goal, quoted rather than summarised",
                repair, "# The goal, unchanged");
        check.contains("and the goal itself", repair, "Create src/result.txt");
        check.contains("the finding is named by its id so the answer can be matched to it",
                repair, "prior-finding");
        check.contains("and by the kind of problem it is", repair, "- category: product_defect");
        check.contains("the blast radius is restated", repair, "# What you may not do");
        check.contains("with the scope it may not leave", repair, "src");
        check.contains("and the contract it may not edit", repair, "hashed");
        check.contains("the room left is stated", repair, "# What is left");
        check.contains("naming which round this is", repair, "fix round 1 of");
        check.contains("and that nothing here lands", repair, "Nothing is landed");

        // The reviewer package: what it filed, what the implementer says it did, and whether
        // the tree agrees.
        String recheck = Files.readString(
                handover.resolve(".warden/runs/hp1/context/fix-1-review-recheck.md"));
        check.contains("the reviewer is told it has read this before",
                recheck, "You have already read this candidate");
        check.contains("and is given back its own finding, by id", recheck, "prior-finding");
        check.contains("with the implementer's account of what it did",
                recheck, "What the implementer says it did");
        check.contains("and whether the candidate actually moved",
                recheck, "did change since your last reading");
        check.contains("it is asked whether the findings actually closed",
                recheck, "actually closed");
        check.contains("and told a regression is a finding, not a footnote",
                recheck, "a regression is a new finding");
        check.contains("told to reuse ids so a survivor is distinguishable from a new finding",
                recheck, "Reuse the finding ids");
        check.contains("told not to launder severity", recheck, "Do not lower a severity");
        check.contains("and told when a narrowed reading is not allowed",
                recheck, "repeat it if the scope");

        // The stub answers with what its prompt actually contained, so this is not a claim
        // about a file on disk but about what reached the vendor.
        Map<String, Object> second = steps(done).stream()
                .filter(s -> "reviewer".equals(s.get("step"))
                        && Long.valueOf(1L).equals(s.get("attempt")))
                .findFirst().orElseThrow();
        Map<String, Object> artifact = Json.parseObject(Files.readString(
                handover.resolve(String.valueOf(second.get("artifact_path")))));
        check.contains("the recheck package reached the vendor, not just the evidence directory",
                String.valueOf(artifact.get("summary")), "recheck_package=true");
        check.contains("carrying the id it filed last time",
                String.valueOf(artifact.get("summary")), "prior_id=true");

        // And the receipt the run keeps about the repair.
        Map<String, Object> receipt =
                (Map<String, Object>) done.summaryReport().get("last_repair_receipt");
        check.eq("the repair receipt records the round", 1L, receipt.get("attempt"));
        check.eq("and that the candidate really moved", Boolean.TRUE,
                receipt.get("moved_candidate"));
    }

    /**
     * A pass bought by relabelling or by dropping a finding is not a pass.
     *
     * A reviewer can end a run by calling its own P1 a P2, or by omitting it. On a candidate
     * that changed that may be an honest reconsideration; on one that did not change, nothing
     * new was learned about the code and the only thing that moved is the report between the
     * run and the human gate. Both paths have to stop, or the weaker one is the way through.
     */
    @SuppressWarnings("unchecked")
    private void severityLaunderingChecks(Check check, Path sandbox, Path home) throws Exception {
        record Mode(String project, String reviewMode, String idKey, String otherKey, String description) {}
        for (Mode mode : List.of(
                new Mode("severity-laundering", "review-launders-severity", "downgraded_ids",
                        "retracted_ids", "a P1 relabelled below the line does not carry the run"),
                new Mode("severity-retraction", "review-retracts-finding", "retracted_ids",
                        "downgraded_ids",
                        "a P1 dropped on an unchanged candidate does not carry the run"))) {
            Path project = newProject(sandbox, mode.project(), "medium", 20);
            Files.deleteIfExists(sandbox.resolve(mode.project() + "-impl.count"));
            Files.deleteIfExists(sandbox.resolve(mode.project() + "-review.count"));
            // Writes identical bytes on the repair, so the second reading is of the same candidate.
            writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                    "implementer", "role, task_id, status, summary, files_changed",
                    "impl", sandbox.resolve(mode.project() + "-impl.count"), 1);
            writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                    "reviewer", "role, task_id, status, verdict, summary, findings",
                    mode.reviewMode(), sandbox.resolve(mode.project() + "-review.count"), 2);
            policy(home, "loop-review", "loop-impl");

            List<String> printed = new java.util.ArrayList<>();
            TaskLoop.Outcome refused = new TaskLoop(new ProcessRunner())
                    .withProgress(printed::add)
                    .run(new ConfigLoader().load(project, "hello"), UserConfig.load(home),
                            mode.project(), false);
            check.that(mode.description(), !refused.ok());
            check.eq(mode.project() + " refusal names what happened",
                    "severity_downgraded_without_change", refused.reason());
            Map<String, Object> caught =
                    (Map<String, Object>) refused.summaryReport().get("severity_downgraded_without_change");
            check.eq(mode.project() + " naming the finding",
                    List.of("disputed-one"), caught.get(mode.idKey()));
            check.eq(mode.project() + " the other path is empty",
                    List.of(), caught.get(mode.otherKey()));
            check.eq(mode.project() + " stage", "review", caught.get("at_stage"));
            check.contains(mode.project() + " terminal",
                    String.join("\n", printed),
                    "not treating a dropped or relabelled finding as a fixed one");
            check.contains(mode.project() + " next step",
                    String.valueOf(refused.summaryReport().get("safe_next_step")),
                    "settle that on the evidence");
        }
    }

    /**
     * A throwaway blocker kept alive for one round does not carry a retraction or a
     * relabel past the stop.
     *
     * requireNoSeverityLaundering used to return whenever any P1 was still open, so dropping
     * the confirmed findings on a byte-identical tree and filing noise instead reached the
     * human gate as a pass. The lists are computed against the previous round only; if the
     * laundering round is allowed to proceed, no later round ever sees the dropped ids.
     */
    @SuppressWarnings("unchecked")
    private void severityLaunderingBehindBlockerChecks(Check check, Path sandbox, Path home)
            throws Exception {
        record Mode(String project, String reviewMode, String idKey, List<String> ids,
                    String description) {}
        for (Mode mode : List.of(
                new Mode("launder-behind-drop", "review-launders-behind-blocker", "retracted_ids",
                        List.of("real-one", "real-two"),
                        "dropping confirmed P1s behind a throwaway blocker does not pass"),
                new Mode("launder-behind-relabel", "review-relabels-behind-blocker",
                        "downgraded_ids", List.of("real-one"),
                        "relabelling a P1 behind a throwaway blocker does not pass"))) {
            Path project = newProject(sandbox, mode.project(), "medium", 20);
            Files.deleteIfExists(sandbox.resolve(mode.project() + "-impl.count"));
            Files.deleteIfExists(sandbox.resolve(mode.project() + "-review.count"));
            writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                    "implementer", "role, task_id, status, summary, files_changed",
                    "impl", sandbox.resolve(mode.project() + "-impl.count"), 1);
            writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                    "reviewer", "role, task_id, status, verdict, summary, findings",
                    mode.reviewMode(), sandbox.resolve(mode.project() + "-review.count"), 1);
            policy(home, "loop-review", "loop-impl");
            List<String> printed = new java.util.ArrayList<>();
            TaskLoop.Outcome refused = new TaskLoop(new ProcessRunner())
                    .withProgress(printed::add)
                    .run(new ConfigLoader().load(project, "hello"), UserConfig.load(home),
                            mode.project(), false);
            check.that(mode.description(), !refused.ok());
            check.eq(mode.project() + " refusal names what happened",
                    "severity_downgraded_without_change", refused.reason());
            Map<String, Object> caught =
                    (Map<String, Object>) refused.summaryReport().get("severity_downgraded_without_change");
            check.eq(mode.project() + " naming the original finding(s)",
                    mode.ids(), caught.get(mode.idKey()));
            check.eq(mode.project() + " did not buy a second repair", 2L,
                    steps(refused).stream().filter(s -> "implementer".equals(s.get("step"))).count());
            check.contains(mode.project() + " next step",
                    String.valueOf(refused.summaryReport().get("safe_next_step")),
                    "settle that on the evidence");
        }
    }

    /**
     * Restoring finding history on --continue is not enough: the first reading of the
     * resumed run has to be checked against it.
     *
     * requireNoSeverityLaundering used to live only inside the fix loop. A resume after a
     * budget stop restores the previous round and re-dispatches the reviewer at attempt 0.
     * When that reading dropped every P1, repairableFindings was 0, the loop was never
     * entered, and the run reached the human gate as a pass on a tree no repair had touched.
     * An honest re-read of the same P1s still proceeds to repair.
     */
    @SuppressWarnings("unchecked")
    private void severityLaunderingOnResumeChecks(Check check, Path sandbox, Path home)
            throws Exception {
        Path drop = newProject(sandbox, "launder-on-resume", "medium", 3);
        Files.deleteIfExists(sandbox.resolve("launder-on-resume-impl.count"));
        Files.deleteIfExists(sandbox.resolve("launder-on-resume-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl", sandbox.resolve("launder-on-resume-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review-retracts-finding", sandbox.resolve("launder-on-resume-review.count"), 2);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome stopped = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(drop, "hello"), UserConfig.load(home), "lr-before", false);
        check.eq("resume-launder first run stops at reserve",
                "budget_insufficient_to_finish", stopped.reason());
        Path dropTask = drop.resolve(".warden/tasks/hello.yaml");
        Files.writeString(dropTask, Files.readString(dropTask).replace("max_role_runs: 3", "max_role_runs: 20"));
        TaskLoop.Outcome resumed = continueFrom(drop, home, "lr-before", "lr-after");
        check.that("a full retraction on resume is not a pass", !resumed.ok());
        check.eq("it names the same stop as a retraction after a repair",
                "severity_downgraded_without_change", resumed.reason());
        Map<String, Object> caught =
                (Map<String, Object>) resumed.summaryReport().get("severity_downgraded_without_change");
        check.that("the full-drop stop recorded which ids moved", caught != null);
        if (caught != null) {
            check.eq("naming the dropped finding", List.of("disputed-one"), caught.get("retracted_ids"));
        }
        check.eq("resume did not buy a repair to justify the drop", 0L,
                steps(resumed).stream().filter(s -> "implementer".equals(s.get("step"))
                        && s.get("reused_from") == null).count());
        check.contains("next step is a person settling the disagreement",
                String.valueOf(resumed.summaryReport().get("safe_next_step")),
                "settle that on the evidence");

        Path keep = newProject(sandbox, "honest-resume", "medium", 3);
        Files.deleteIfExists(sandbox.resolve("honest-resume-impl.count"));
        Files.deleteIfExists(sandbox.resolve("honest-resume-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("honest-resume-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("honest-resume-review.count"), 3);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome honestStopped = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(keep, "hello"), UserConfig.load(home), "hr-before", false);
        check.eq("honest-resume first run stops at reserve",
                "budget_insufficient_to_finish", honestStopped.reason());
        Path keepTask = keep.resolve(".warden/tasks/hello.yaml");
        Files.writeString(keepTask, Files.readString(keepTask).replace("max_role_runs: 3", "max_role_runs: 20"));
        TaskLoop.Outcome honest = continueFrom(keep, home, "hr-before", "hr-after");
        check.eq("an honest re-read of the same P1 still proceeds to repair",
                "ready_for_human", honest.reason());
        check.that("the resume paid for a repair after the standing P1",
                steps(honest).stream().anyMatch(s -> "implementer".equals(s.get("step"))
                        && s.get("reused_from") == null));
    }

    /**
     * Provenance survives an explicit continuation. A derived id still has an id string
     * in the recorded round, and replaying it through Findings.of would stamp it vendor.
     */
    @SuppressWarnings("unchecked")
    private void provenanceResumeChecks(Check check, Path sandbox, Path home) throws Exception {
        Path project = newProject(sandbox, "provenance-resume", "medium", 20);
        Files.deleteIfExists(sandbox.resolve("provenance-resume-impl.count"));
        Files.deleteIfExists(sandbox.resolve("provenance-resume-review.count"));
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve("provenance-resume-impl.count"), 1);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve("provenance-resume-review.count"), 2);
        policy(home, "loop-review", "loop-impl");
        TaskLoop.Outcome first = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(project, "hello"), UserConfig.load(home), "pr1", false);
        check.that("the first run reaches the human gate", first.ok());
        Map<String, Object> filed =
                ((List<Map<String, Object>>) ((List<Map<String, Object>>) first.summaryReport()
                        .get("finding_history")).get(0).get("findings")).get(0);
        check.eq("the first reading derived the id", "derived", filed.get("id_source"));
        check.eq("and defaulted the category", "defaulted_absent", filed.get("category_source"));
        TaskLoop.Outcome resumed = continueFrom(project, home, "pr1", "pr2");
        check.that("the continuation still reaches the human gate", resumed.ok());
        Map<String, Object> restored =
                ((List<Map<String, Object>>) ((List<Map<String, Object>>) resumed.summaryReport()
                        .get("finding_history")).get(0).get("findings")).get(0);
        check.eq("continuation keeps the derived id", "derived", restored.get("id_source"));
        check.eq("continuation keeps the defaulted category", "defaulted_absent",
                restored.get("category_source"));
        check.eq("and the id itself is the one that was stored", filed.get("id"), restored.get("id"));
    }

    /** The roster the live run had: one writer, two independent readers. */
    private void liveShapeProfiles(Path home, Path sandbox, String scenario) throws IOException {
        Files.deleteIfExists(sandbox.resolve(scenario + "-impl.count"));
        Files.deleteIfExists(sandbox.resolve(scenario + "-review.count"));
        Files.deleteIfExists(sandbox.resolve(scenario + "-review2.count"));
        // An implementer whose repairs actually move the tree, so each round is genuine
        // progress and the budget reserve — not the no-progress detector — is what this
        // fixture measures.
        writeProfile(home, "loop-impl", "implementer", "implvendor", false,
                "implementer", "role, task_id, status, summary, files_changed",
                "impl-grows", sandbox.resolve(scenario + "-impl.count"), 1);
        // Objects twice, passes on the third reading — the run's actual arc.
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve(scenario + "-review.count"), 3);
        writeProfile(home, "loop-review-2", "reviewer", "secondvendor", true,
                "reviewer", "role, task_id, status, verdict, summary, findings",
                "review", sandbox.resolve(scenario + "-review2.count"), 1);
    }

    private void liveShapePolicy(Path home, String repairReserve) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [loop-impl], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [loop-review, loop-review-2], strategy: rotate, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                budget: { repair_reserve: %s }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix, recheck_after_fix: true }
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix, recheck_after_fix: true }
                    - { stage: review-second, run: role, role: reviewer, on_fail: stop, on_findings: fix }
                    - { stage: browser, run: visual_harness, when: [visual_qa_required], on_fail: fix, recheck_after_fix: true }
                """.formatted(repairReserve));
    }

    /**
     * A human rejection is the only feedback in this loop that costs a person's attention.
     * It used to be written to `decision.json` and read by nobody.
     */
    private void carriedRejectionChecks(Check check, Path sandbox, Path home) throws Exception {
        Path project = newProject(sandbox, "rejected-once");
        writeProfiles(home, sandbox, "rejected-once", 1, 1);
        TaskLoop.Outcome first = loop(project, home, "rj1");
        check.that("the first run reaches the human gate", first.ok());
        dev.warden.approval.ApprovalStore decisions = new dev.warden.approval.ApprovalStore(project);
        decisions.resolve("rj1", decisions.read("rj1").updatedAt().toString(), "reject", "operator",
                "the fire is drawn twice the height of the person standing next to it");

        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        TaskLoop.Outcome second = new TaskLoop(new ProcessRunner())
                .run(loaded, UserConfig.load(home), "rj2", false, Map.of(),
                        new TaskLoop.Continuation("rj1", "the fire is drawn twice the height "
                                + "of the person standing next to it"));
        check.that("the next run still reaches the human gate", second.ok());
        check.eq("and records where it came from", "rj1",
                second.summaryReport().get("continued_from"));
        Path context = project.resolve(".warden/runs/rj2/context/fix-0-rejection.md");
        check.that("the reason is written where the implementer is pointed at it",
                Files.isRegularFile(context));
        String carried = Files.readString(context);
        check.contains("with the person's own words", carried, "twice the height");
        check.contains("and says plainly that a person, not a check, objected",
                carried, "A person rejected the previous candidate");
    }

    private TaskLoop.Outcome loop(Path project, Path home, String runId) throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        return new TaskLoop(new ProcessRunner()).run(loaded, UserConfig.load(home), runId, false);
    }

    private TaskLoop.Outcome loop(Path project, Path home, String runId, Workspace board)
            throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        return new TaskLoop(new ProcessRunner()).withWorkspace(board)
                .run(loaded, UserConfig.load(home), runId, false);
    }

    private TaskLoop.Outcome dryLoop(Path project, Path home, String runId, Workspace board)
            throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "hello");
        return new TaskLoop(new ProcessRunner()).withWorkspace(board)
                .run(loaded, UserConfig.load(home), runId, true);
    }

    /**
     * A project-owned test suite is green before a vendor, red changes never become the
     * implementer's mystery, and the same commands remain in the post-agent gate.
     */
    private void baselineChecks(Check check, Path sandbox, Path home) throws Exception {
        Path red = baselineProject(sandbox, "baseline-red", false);
        writeProfiles(home, sandbox, "baseline-red", 1, 1);
        Board redBoard = new Board();
        TaskLoop.Outcome refused = loop(red, home, "base-red-1", redBoard);
        check.that("a red project baseline stops the run", !refused.ok());
        check.eq("and is named independently of a candidate gate failure", "baseline_failed",
                refused.reason());
        check.eq("no vendor is charged for a failure that predates it", 0L,
                refused.summaryReport().get("role_runs"));
        check.that("the implementer process was never launched",
                !Files.exists(sandbox.resolve("baseline-red-impl.count")));
        check.that("only baseline evidence precedes the stop",
                steps(refused).size() == 1 && "baseline".equals(steps(refused).get(0).get("step")));
        check.eq("the Orca-facing card asks for a person", Workspace.State.WAITING_FOR_HUMAN,
                redBoard.last());
        check.that("and says no vendor was dispatched",
                redBoard.anyNote("no vendor dispatched"));

        Path green = baselineProject(sandbox, "baseline-green", true);
        // The first implementer deliberately leaves the focused project check red. This one
        // fixture therefore proves the complete boundary we care about: known-green project
        // baseline -> vendor change -> real command failure -> bounded repair -> green gate.
        writeProfiles(home, sandbox, "baseline-green", 2, 1);
        TaskLoop.Outcome first = loop(green, home, "base-green-1");
        check.that("a green project baseline allows the normal loop", first.ok());
        check.eq("a real failed project command caused exactly one repair round", 1L,
                first.summaryReport().get("attempts_used"));
        String repairContext = Files.readString(
                green.resolve(".warden/runs/base-green-1/context/fix-1-gates.md"));
        check.contains("the failing project command is returned to the same implementer",
                repairContext, "Machine gate failed");
        check.eq("the baseline is the first recorded step", "baseline",
                steps(first).get(0).get("step"));
        check.eq("the baseline report is a distinct machine receipt", "passed",
                first.summaryReport().get("baseline_code"));
        Map<String, Object> joined = new dev.warden.ledger.RunReport()
                .of(green, "base-green-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> joinedStages =
                (List<Map<String, Object>>) (List<?>) joined.get("stages");
        check.eq("the joined report classifies baseline as a machine gate", "machine_gates",
                joinedStages.get(0).get("kind"));
        check.eq("without confusing it with post-agent acceptance", "baseline",
                joinedStages.get(0).get("phase"));
        Map<String, Object> finalGate = steps(first).stream()
                .filter(step -> "gates".equals(step.get("step"))).findFirst().orElseThrow();
        Map<String, Object> gateReport = Json.parseObject(Files.readString(
                Path.of(String.valueOf(finalGate.get("report")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) gateReport.get("commands");
        check.eq("the final gate runs both baseline and focused task checks", 2, commands.size());
        check.contains("the baseline command remains first after the agent changed the tree",
                String.valueOf(commands.get(0).get("command")), "health.txt");
        check.contains("and the task check follows it", String.valueOf(commands.get(1).get("command")),
                "result.txt");

        dev.warden.approval.ApprovalStore approvals =
                new dev.warden.approval.ApprovalStore(green);
        dev.warden.approval.HumanDecision pending = approvals.read("base-green-1");
        approvals.resolve("base-green-1", pending.updatedAt().toString(), "reject", "test",
                "exercise a continuation without changing the candidate");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(green, "hello");
        TaskLoop.Outcome continued = new TaskLoop(new ProcessRunner()).run(
                loaded, UserConfig.load(home), "base-green-2", false, Map.of(),
                new TaskLoop.Continuation("base-green-1",
                        "exercise a continuation without changing the candidate"));
        check.eq("an unchanged continuation reuses the original green receipt", "base-green-1",
                continued.summaryReport().get("baseline_reused_from"));
        Map<String, Object> reused = steps(continued).stream()
                .filter(step -> "baseline".equals(step.get("step"))).findFirst().orElseThrow();
        check.eq("the timeline marks reuse rather than pretending a command ran again", true,
                reused.get("reused"));
        check.eq("and points to the receipt that was actually trusted", "base-green-1",
                reused.get("reused_from"));

        Files.writeString(green.resolve("src/health.txt"), "red\n");
        TaskLoop.Outcome moved = new TaskLoop(new ProcessRunner()).run(
                new ConfigLoader().load(green, "hello"), UserConfig.load(home),
                "base-green-3", false, Map.of(),
                new TaskLoop.Continuation("base-green-1",
                        "candidate changed after the receipt"));
        check.eq("a changed continuation cannot borrow the old baseline", "baseline_failed",
                moved.reason());
        check.contains("the refusal says why reuse was declined",
                String.valueOf(moved.summaryReport().get("baseline_reuse_declined")),
                "worktree changed");
        check.eq("and it still spends no new vendor call", 0L,
                moved.summaryReport().get("role_runs"));
    }

    private void roleFailureRoutingChecks(Check check) {
        check.that("a native agent waiting for a person is never sent to a new implementer",
                TaskLoop.roleFailureRequiresOperator("role_human_input_required"));
        check.that("an unfenced Orca worker blocks every later dispatch",
                TaskLoop.roleFailureRequiresOperator("role_orca_lifecycle_unaccounted"));
        check.that("an Orca wall-clock stop is infrastructure, not failed work",
                TaskLoop.roleFailureRequiresOperator("role_orca_timeout"));
        check.that("a direct CLI timeout may retry after ProcessRunner killed its process tree",
                !TaskLoop.roleFailureRequiresOperator("role_timeout"));
        check.that("a schema defect still belongs to the agent repair loop",
                !TaskLoop.roleFailureRequiresOperator("role_artifact_schema_violation"));
    }

    /** Every note and state the loop pushed at a board, in order. */
    private static final class Board implements Workspace {
        private final List<String> notes = new java.util.ArrayList<>();
        private final List<State> states = new java.util.ArrayList<>();
        private final List<String> beats = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void note(String text) { notes.add(text); }
        @Override public void state(State state) { states.add(state); }
        @Override public void working(String who, long millis) { beats.add(who); }

        State last() { return states.isEmpty() ? null : states.get(states.size() - 1); }

        boolean anyNote(String needle) {
            return notes.stream().anyMatch(note -> note.contains(needle));
        }
    }

    /**
     * The board is a convenience, and a convenience that can abort a paid twenty-minute loop
     * is a liability. This is the invariant, not the wiring.
     */
    private void workspaceChecks(Check check, Path sandbox, Path home) throws Exception {
        Path watched = newProject(sandbox, "watched");
        writeProfiles(home, sandbox, "watched", 1, 1);
        Board board = new Board();
        TaskLoop.Outcome ok = loop(watched, home, "wb1", board);
        check.that("a watched run still reaches the human gate", ok.ok());
        check.eq("the card ends in the column that means somebody is waited on",
                Workspace.State.WAITING_FOR_HUMAN, board.last());
        check.that("the run says it is running before it says it is done",
                board.states.get(0) == Workspace.State.RUNNING);
        check.that("every stage reported itself to the card", board.anyNote("gates"));
        check.that("and the card names the role holding the loop, not only the stage",
                board.anyNote("implement · implementer · running"));
        check.that("and which profile and vendor are inside that role, from the resolution that ran",
                board.anyNote("implement · implementer (loop-impl / implvendor) · running"));
        check.that("a machine-gates stage names neither a profile nor a vendor",
                board.notes.stream().anyMatch(note ->
                        note.contains("gates · gates · running") && !note.contains("(")));
        check.that("the last note carries the command that answers it",
                board.anyNote("warden approve wb1 --decision"));
        check.that("and what it cost, because a phone cannot run warden report",
                board.anyNote("call(s)"));

        // A preview has not produced anything a person could act on, so it must not move a
        // card into a column that says one of them is waiting.
        Path previewed = newProject(sandbox, "previewed");
        writeProfiles(home, sandbox, "previewed", 1, 1);
        Board untouched = new Board();
        dryLoop(previewed, home, "wb2", untouched);
        check.that("a dry run leaves the board alone", untouched.notes.isEmpty());
        check.that("including its column", untouched.states.isEmpty());
        check.that("and it announces no working role, because none was dispatched",
                untouched.beats.isEmpty());

        Path unlucky = newProject(sandbox, "unlucky");
        writeProfiles(home, sandbox, "unlucky", 1, 1);
        TaskLoop.Outcome survived = loop(unlucky, home, "wb3", new Workspace() {
            @Override public void note(String text) { throw new IllegalStateException("orca died"); }
            @Override public void state(State state) { throw new IllegalStateException("orca died"); }
            @Override public void working(String who, long millis) {
                throw new IllegalStateException("orca died");
            }
            @Override public void watch(Path narration, String runId) {
                throw new IllegalStateException("orca died");
            }
        });
        check.that("a board that throws on every call does not fail the run", survived.ok());
        check.eq("nor change where the run ended", "human_gate", survived.nextAction());
        Workspace guarded = Workspace.guarded(new Workspace() {
            @Override public void note(String text) { throw new IllegalStateException("orca died"); }
            @Override public void state(State state) { throw new IllegalStateException("orca died"); }
            @Override public void watch(Path narration, String runId) {
                throw new IllegalStateException("orca died");
            }
        });
        guarded.note("x");
        guarded.state(Workspace.State.RUNNING);
        guarded.watch(sandbox.resolve("nowhere.log"), "wb3");
        check.that("and the guard covers opening a live view, not just writing to the card", true);

        // `--watch` writes narration.log before the loop reserves the run, so the reservation
        // has to know that file is not evidence. It did not, and every watched run failed its
        // first attempt with `run_id_exists` — found on the first run of Warden against Warden.
        Path watched2 = newProject(sandbox, "watched-fresh");
        writeProfiles(home, sandbox, "watched-fresh", 1, 1);
        Path narration = watched2.resolve(".warden/runs/wb4/narration.log");
        Files.createDirectories(narration.getParent());
        Files.writeString(narration, "run   wb4\n");
        TaskLoop.Outcome watchedFirst = loop(watched2, home, "wb4", new Board());
        check.that("a run whose narration file already exists still starts", watchedFirst.ok());
        check.that("and the narration it was following is still there",
                Files.isRegularFile(narration));

        Path occupied = newProject(sandbox, "occupied");
        writeProfiles(home, sandbox, "occupied", 1, 1);
        Path stray = occupied.resolve(".warden/runs/wb5/machine-gate.json");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, "{}");
        check.rejects("but real evidence in the directory still fences the run",
                "already contains evidence", () -> loop(occupied, home, "wb5"));

        narrationChecks(check, sandbox);
    }

    /**
     * The account of a run, kept where a closed terminal cannot take it.
     *
     * It is what `--watch` follows, which is why it has to survive a directory that does not
     * exist yet: the header is printed before the ledger has made anything.
     */
    private void narrationChecks(Check check, Path sandbox) throws Exception {
        Path log = sandbox.resolve("unmade/run/narration.log");
        dev.warden.run.Progress toFile = dev.warden.run.Progress.toFile(log);
        toFile.line("run   n1");
        toFile.line("bound 3 vendor call(s)");
        check.that("the narration sink makes its own directory", Files.isRegularFile(log));
        String written = Files.readString(log);
        check.contains("and keeps the first line", written, "run   n1");
        check.contains("appending rather than replacing", written, "bound 3 vendor call(s)");

        StringBuilder terminal = new StringBuilder();
        Path teed = sandbox.resolve("teed/narration.log");
        dev.warden.run.Progress both = dev.warden.run.Progress.tee(
                text -> terminal.append(text).append('\n'), dev.warden.run.Progress.toFile(teed));
        both.line("[1/3] implement  running");
        check.contains("a teed line reaches the terminal", terminal.toString(), "[1/3] implement");
        check.contains("and the file, from one call", Files.readString(teed), "[1/3] implement");
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

    /** A task whose money ceiling binds before its call ceiling does. */
    private Path newProject(Path sandbox, String name, String risk, long maxRoleRuns,
                            String maxCostUsd) throws Exception {
        return newProject(sandbox, name, risk, maxRoleRuns, false, maxCostUsd);
    }

    private Path newProject(Path sandbox, String name, String risk, long maxRoleRuns, boolean visual)
            throws Exception {
        return newProject(sandbox, name, risk, maxRoleRuns, visual, "10.0");
    }

    private Path newProject(Path sandbox, String name, String risk, long maxRoleRuns,
                            boolean visual, String maxCostUsd) throws Exception {
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
                    - "1280x720: testid=save visible"
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
                  max_cost_usd: %s
                max_fix_attempts: 2
                """.formatted(risk, visualBlock, maxRoleRuns, maxCostUsd));
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

    private Path baselineProject(Path sandbox, String name, boolean green) throws Exception {
        Path project = sandbox.resolve(name);
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        String healthCheck = WINDOWS
                ? "powershell -NoLogo -NoProfile -Command \"if ((Get-Content -Raw -LiteralPath "
                    + "'src/health.txt').Trim() -eq 'green') { exit 0 } else { exit 1 }\""
                : "test \"$(cat src/health.txt)\" = green";
        String resultCheck = WINDOWS
                ? "if exist src\\result.txt (exit /b 0) else (exit /b 1)"
                : "test -f src/result.txt";
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: %s
                base_ref: HEAD
                checks:
                  health: [%s]
                  task: [%s]
                scopes:
                  app: ["src"]
                defaults:
                  checks: task
                  baseline_checks: health
                  risk: medium
                """.formatted(name, yaml(healthCheck), yaml(resultCheck)));
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt without breaking project health
                risk: medium
                scope: app
                authority: { workspace_write: true }
                budgets: { max_role_runs: 6, max_cost_usd: 10.0 }
                max_fix_attempts: 2
                """);
        Files.writeString(project.resolve("src/health.txt"), green ? "green\n" : "red\n");
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
        policy(home, reviewers, implementers, "auto");
    }

    private void policy(Path home, String reviewers, String implementers, String failover)
            throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [%s], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [%s], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                failover: { on_quota_exhausted: %s }
                """.formatted(implementers, reviewers, failover));
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
