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
            landChecks(check, clean, "r1");

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

            narrationChecks(check, sandbox, home);
            reusedJudgementChecks(check, sandbox, home);
            carriedRejectionChecks(check, sandbox, home);
            workspaceChecks(check, sandbox, home);
            failoverConfirmationChecks(check, sandbox, home);
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
     * Carrying an accepted candidate to a commit and a branch.
     *
     * Every check here is a way the step could push something nobody agreed to: work that was
     * never accepted, a tree that moved after it was, the branch a request would target, or
     * Warden's own evidence riding along in the commit.
     */
    @SuppressWarnings("unchecked")
    private void landChecks(Check check, Path project, String runId) throws Exception {
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
        check.eq("recording where they came from", Map.of("from", "ru1",
                        "roles", List.of("implementer", "reviewer")),
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
