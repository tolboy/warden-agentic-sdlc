package dev.warden.run;

import dev.warden.config.ConfigException;
import dev.warden.config.ConfigLoader;
import dev.warden.config.PlannerDraft;
import dev.warden.config.ProjectConfig;
import dev.warden.config.TaskDraft;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.config.Workflow;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.json.Schema;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;
import dev.warden.role.RoleRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The planner's place in {@code warden do}: one read-only call, bounded by the profile and
 * by Warden, whose draft is compiled rather than trusted.
 *
 * Not a workflow stage. The shipped chain is unchanged, and nothing here is mandatory —
 * {@code --prepare off} never reaches this class.
 *
 * <h2>The second planner</h2>
 *
 * A policy that names a {@code plan_reviewer} gets a second reading of the compiled contract
 * — the contract, not the draft, because the contract is what every later verdict is
 * measured against. The reviewer objects with findings; a blocking objection sends the first
 * planner back exactly once with those findings as its context, the redraft is compiled and
 * validated again, and the reviewer reads once more. A second objection stops for a person
 * with {@code plan_review_findings_remain}, before any writer is paid. Both readers are
 * read-only, both are one call each, and every call is counted.
 *
 * <p>A contract that was already on disk does not skip that redraft. The planner has already
 * replaced the body by the time the reviewer reads it, carrying only the operator's own
 * blocks (budgets, timeout, {@code use}). The objection is to the planner's text. The file
 * is left in place so the redraft's compile carries those blocks again.
 */
public final class Preparation {

    public static final Set<String> MODES = Set.of("off", "auto", "always");
    public static final String PROTOCOL = "planner_protocol_violation";
    public static final String DRAFT_INVALID = "planner_draft_invalid";
    public static final String REVIEW_REMAINS = "plan_review_findings_remain";
    public static final String AMENDMENT_EMPTY = "contract_amendment_empty";

    /** One retry after a protocol failure, then stop. Not a repair: the draft is discarded. */
    private static final int PROTOCOL_RETRIES = 1;
    /** One redraft after a blocking plan review, then stop for a person. */
    private static final int REDRAFTS = 1;

    public record Outcome(
            boolean ok,
            String code,
            String message,
            RoleRunner.Outcome role,
            TaskDraft.Written written,
            int roleRuns,
            double costUsd,
            int unpriced,
            Map<String, Object> planReview,
            List<Map<String, Object>> calls) {

        public Outcome(boolean ok, String code, String message, RoleRunner.Outcome role,
                       TaskDraft.Written written, int roleRuns, double costUsd, int unpriced,
                       Map<String, Object> planReview) {
            this(ok, code, message, role, written, roleRuns, costUsd, unpriced, planReview, List.of());
        }

        public Outcome(boolean ok, String code, String message, RoleRunner.Outcome role,
                       TaskDraft.Written written, int roleRuns, double costUsd, int unpriced) {
            this(ok, code, message, role, written, roleRuns, costUsd, unpriced, null, List.of());
        }
    }

    private final ProcessRunner processes;
    private final Progress progress;

    /**
     * A contract already in force, the text it had before this preparation, and the reader
     * findings that say its acceptance is too weak. Present only when the loop sends a passing
     * candidate's contract gaps to the planner (`review.contract_gaps: plan`).
     */
    public record Amendment(String before, List<Map<String, Object>> gaps) {}

    private Amendment amendment;
    /** The amended contract this preparation wrote and has not yet seen through review. */
    private String amendedText;
    /** The ceilings of a chain this preparation spends for; none for a first plan. */
    private RoleRunner.DispatchGate chain = () -> { };
    /** Every vendor call this preparation made, in order, for the run summary. */
    private final List<Map<String, Object>> calls = new ArrayList<>();

    /**
     * The same preparation, amending the contract on disk instead of drafting one: the
     * planner is handed the contract and the gaps, only what it adds to the acceptance is
     * kept ({@link PlannerDraft#amend}), the plan reviewer reads the amended contract, and an
     * objection restores the previous text before the one redraft. A second objection leaves
     * the contract as it was.
     */
    public Preparation amending(Amendment amendment) {
        this.amendment = amendment;
        return this;
    }

    /**
     * The same preparation, with every call also admitted by {@code chain} and its wall clock
     * lowered to what {@code chain} has left. An amendment is paid by a chain that already
     * spent; see {@link TaskLoop#amendmentGate}.
     */
    public Preparation within(RoleRunner.DispatchGate chain) {
        this.chain = chain;
        return this;
    }

    public Preparation(ProcessRunner processes, Progress progress) {
        this.processes = processes;
        this.progress = progress == null ? Progress.SILENT : progress;
    }

    public static String parseMode(String raw) {
        if (raw == null || raw.isBlank()) return "off";
        String mode = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "--prepare must be off, auto or always; got '" + raw + "'");
        }
        return mode;
    }

    /**
     * {@code always} dispatches. {@code auto} dispatches only when the task id has no
     * contract on disk yet. {@code off} never does.
     */
    public static boolean shouldDispatch(String mode, boolean contractExists) {
        if ("always".equals(mode)) return true;
        if ("auto".equals(mode)) return !contractExists;
        return false;
    }

    /** Whether the policy names a second planner. */
    public static boolean reviewsPlans(UserConfig user) {
        return user != null && user.policy() != null
                && user.policy().roles().containsKey("plan_reviewer");
    }

    /**
     * The paying roles preparation dispatches on a clean path, in order, for the call plan.
     * The second reading is one call when it is declared; its redraft and re-reading are a
     * recovery branch, costed like a repair rather than reserved for a clean pass.
     */
    public static List<String> payingStages(UserConfig user) {
        return reviewsPlans(user) ? List.of("planner", "plan-review") : List.of("planner");
    }

    /**
     * Persist the prepare mode when auto skipped the planner, so a later controller —
     * Conductor's inner {@code warden run}, or this process — records {@code prepare=auto}
     * rather than {@link TaskLoop.Preparation#NONE}'s {@code off}. No vendor ran; the
     * reservation still has to exist for the inner process to join it.
     */
    public static TaskLoop.Preparation recordSkipped(Path root, String runId, String taskId,
                                                     String mode) throws IOException {
        return recordSkipped(root, runId, taskId, mode, null);
    }

    public static TaskLoop.Preparation recordSkipped(Path root, String runId, String taskId,
                                                     String mode, Path home) throws IOException {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("prepare", mode);
        EvidenceLedger ledger = new EvidenceLedger(root, runId, home);
        ledger.reserveWorkflowRun(taskId, extra);
        ledger.markPrepared(0, 0, 0);
        return new TaskLoop.Preparation(mode, true, 0, 0, 0, false);
    }

    public Outcome run(Path root, ProjectConfig project, UserConfig user, String taskId,
                       String runId, String goal, String scope, String risk,
                       PlannerDraft.Access granted, boolean dryRun) throws Exception {
        if (amendment == null) return prepare(root, project, user, taskId, runId, goal, scope, risk, granted, dryRun);
        // Whatever ends an amendment short of a passed plan review — an unavailable reviewer,
        // a timeout, an unreadable verdict, an exception — ends it with the contract that was
        // in force. The amended text is written before the review so the reviewer reads the
        // real file; that must never make it the contract by default.
        Outcome outcome = null;
        try {
            outcome = prepare(root, project, user, taskId, runId, goal, scope, risk, granted, dryRun);
            return outcome;
        } finally {
            if (outcome == null || !outcome.ok()) {
                withdrawAmendment(root.resolve(".warden/tasks").resolve(taskId + ".yaml"));
            }
        }
    }

    /**
     * Put the contract back as it was before this amendment, unless someone else has edited
     * it since: a person's edit made while the planner worked is theirs, not this run's to undo.
     */
    private void withdrawAmendment(Path taskFile) {
        if (amendedText == null) return;
        try {
            String now = Files.readString(taskFile, StandardCharsets.UTF_8);
            if (now.equals(amendedText)) {
                Files.writeString(taskFile, amendment.before(), StandardCharsets.UTF_8);
                progress.line("      the amendment was withdrawn; the contract is as it was");
            } else if (!now.equals(amendment.before())) {
                progress.line("      the contract was edited by someone else while the amendment was "
                        + "read; it is left as they wrote it");
            }
        } catch (IOException unreadable) {
            progress.line("      could not put the contract back after the amendment failed: "
                    + unreadable.getMessage() + "; check " + taskFile);
        } finally {
            amendedText = null;
        }
    }

    /**
     * The most vendor calls one amendment can make: the planner, its one protocol retry and
     * its one redraft, and each reading of the plan reviewer when the policy names one. The
     * loop routes a contract gap to the planner only when the chain can pay for all of them.
     */
    public static int mostAmendmentCalls(UserConfig user) {
        return 1 + PROTOCOL_RETRIES + REDRAFTS + (reviewsPlans(user) ? 1 + REDRAFTS : 0);
    }

    private Outcome prepare(Path root, ProjectConfig project, UserConfig user, String taskId,
                            String runId, String goal, String scope, String risk,
                            PlannerDraft.Access granted, boolean dryRun) throws Exception {
        ConfigLoader.Loaded loaded = bootstrap(root, project, taskId, goal, scope, risk);
        Path context = writeContext(root, runId, goal, granted, project);
        if (amendment != null) appendAmendment(context);
        GitRepository git = new GitRepository(root, processes);
        GitRepository.WorkingTreeSnapshot baseline;
        try {
            baseline = git.snapshotWorkingTree();
        } catch (Exception cannotSnapshot) {
            baseline = null;
        }
        Bootstrap gate = new Bootstrap(chain);
        RoleRunner roles = new RoleRunner(processes, gate).atStage("prepare");

        Spend spent = new Spend(0, 0, 0);
        int protocolAttempts = 0;
        int redrafts = 0;
        Path plannerContext = context;
        List<Map<String, Object>> reviewRounds = new ArrayList<>();

        // A plan reviewer that may write would be refused after the planner was paid.
        String writableJudge = writableJudge(roles, user, "plan-review", "plan_reviewer");
        if (writableJudge != null) {
            return fail(RoleResolver.JUDGE_NOT_READ_ONLY, writableJudge, null, spent, reviewRounds);
        }

        // Operator blocks as they stood before this preparation's first compile. Carried on
        // every write — including the redraft — so a planner-written visual_qa from draft 1
        // is not frozen as if a person had authored it.
        Path existingTask = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
        String operatorBlocks = null;
        if (amendment == null && Files.isRegularFile(existingTask)) {
            operatorBlocks = Files.readString(existingTask, StandardCharsets.UTF_8);
        }

        while (true) {
            progress.line("prep  planner  one read-only call, no repair"
                    + (redrafts > 0 ? " (redraft " + redrafts + " after the plan review objected)" : ""));
            RoleRunner.Outcome outcome;
            try {
                outcome = roles.atStage("prepare").forDispatch(protocolAttempts + redrafts + 1)
                        .run(loaded, user, "planner", runId, null, plannerContext, dryRun);
            } catch (Exhausted exhausted) {
                return fail("budget_exhausted", exhausted.getMessage(), null, spent, reviewRounds);
            } catch (dev.warden.ledger.HomeCorpus.UnavailableException unavailable) {
                // Same refusal the loop reports, under the same name. Preparation crosses
                // the dispatch gate like any other paid call, and nothing is spent when it
                // refuses; what was missing was the name on this surface.
                return fail("ledger_unavailable", unavailable.getMessage(), null, spent, reviewRounds);
            } catch (IllegalStateException unconfigured) {
                return new Outcome(false, "role_unresolved", String.valueOf(unconfigured.getMessage()),
                        null, null, spent.runs, spent.cost, spent.unpriced, planReview(reviewRounds));
            }
            spent = spent.plus(account(outcome));
            noteCall("planner", protocolAttempts + redrafts + 1, outcome);

            if (dryRun) {
                Map<String, Object> preview = null;
                if (reviewsPlans(user)) {
                    // The second reader is previewed too, so a dry run says whether the roster
                    // can fill it rather than finding out after the first planner was paid.
                    gate.grant(1);
                    RoleRunner.Outcome reviewer = roles.atStage("plan-review").forDispatch(1)
                            .run(loaded, user, "plan_reviewer", runId, null, context, true);
                    preview = Map.of("dry_run", true, "profile", String.valueOf(reviewer.profile()),
                            "ok", reviewer.ok(), "code", String.valueOf(reviewer.code()));
                }
                return new Outcome(true, "dry_run", "planner dry-run; no contract compiled",
                        outcome, null, 0, 0, 0, preview);
            }
            // The executor's fingerprint check fires only when the profile declared
            // read_only. The snapshot around this call is the bootstrap's own check:
            // a planner that moved the tree is a protocol failure either way.
            boolean mutated = movedSince(git, baseline);
            if ((!outcome.ok() && PROTOCOL.equals(protocolCode(outcome))) || mutated) {
                protocolAttempts++;
                progress.line("      protocol failure  "
                        + (mutated && outcome.ok() ? PROTOCOL : outcome.code())
                        + "  draft discarded");
                if (baseline == null) {
                    return fail(PROTOCOL, "planner mutated the worktree; Warden has no snapshot "
                            + "of the tree from before the call, so it will not restore and will "
                            + "not retry. read_only is enforced by a content fingerprint, not by "
                            + "the profile flag.",
                            outcome, spent, reviewRounds);
                }
                try {
                    git.restoreWorkingTree(baseline);
                } catch (Exception cannotRestore) {
                    return fail(PROTOCOL, "planner mutated the worktree and only the planner's "
                            + "writes could not be discarded for a retry: "
                            + cannotRestore.getMessage(),
                            outcome, spent, reviewRounds);
                }
                if (protocolAttempts <= PROTOCOL_RETRIES) {
                    gate.grantProtocolRetry();
                    progress.line("      retrying once, on a restored tree");
                    continue;
                }
                return fail(PROTOCOL, "planner mutated the worktree; the draft is discarded. "
                        + "read_only is enforced by a content fingerprint, not by the profile flag.",
                        outcome, spent, reviewRounds);
            }
            if (!outcome.ok()) {
                return fail(outcome.code(), String.valueOf(outcome.details() == null
                                ? outcome.code() : outcome.details().getOrDefault("message",
                                outcome.code())),
                        outcome, spent, reviewRounds);
            }
            Map<String, Object> artifact = artifactOf(outcome);
            if (artifact == null) {
                return fail("role_artifact_unparseable",
                        "planner returned no artifact to compile",
                        outcome, spent, reviewRounds);
            }
            // The executor honours profile.enforce_schema. The planner's draft is compiled
            // into a contract, so Warden checks the shipped schema here as well: a profile
            // that skipped enforcement cannot smuggle a draft the schema would refuse.
            List<String> schemaErrors = Schema.validate(artifact, Json.parse(UserSetup.plannerSchema()));
            if (!schemaErrors.isEmpty()) {
                return fail("role_artifact_schema_violation",
                        "planner draft failed the schema: " + String.join("; ", schemaErrors),
                        outcome, spent, reviewRounds);
            }
            TaskDraft.Written written;
            try {
                if (amendment != null) {
                    Path taskFile = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
                    String amended = PlannerDraft.amend(amendment.before(), artifact, project,
                            taskFile.toString());
                    if (amended == null) {
                        return fail(AMENDMENT_EMPTY, "the planner added no named check and no browser "
                                + "scenario the contract did not already have; the contract is as it "
                                + "was, and the gaps go to a person", outcome, spent, reviewRounds);
                    }
                    Files.writeString(taskFile, amended, StandardCharsets.UTF_8);
                    amendedText = amended;
                    written = new TaskDraft.Written(taskFile, taskId, true);
                    progress.line("      amended " + written.file());
                } else {
                    written = PlannerDraft.write(root, taskId, goal, project, granted, artifact,
                            operatorBlocks);
                    progress.line("      compiled " + written.file());
                }
            } catch (TaskDraft.TaskConflict conflict) {
                return fail("task_conflict", conflict.getMessage(), outcome, spent, reviewRounds);
            } catch (ConfigException invalid) {
                return fail(DRAFT_INVALID, invalid.getMessage(), outcome, spent, reviewRounds);
            }
            if (!reviewsPlans(user)) {
                return done(true, "ok", null, outcome, written, spent, null);
            }

            // The second planner reads the compiled contract. One call, granted here rather
            // than assumed by the bootstrap, so a policy without a reviewer keeps the single
            // call it always had.
            gate.grant(1);
            Path reviewContext = writeReviewContext(root, runId, goal, written, artifact,
                    reviewRounds.size() + 1, user, project);
            GitRepository.WorkingTreeSnapshot beforeReview = snapshotOrNull(git);
            progress.line("prep  plan-review  a second planner reads the compiled contract");
            RoleRunner.Outcome review;
            try {
                review = roles.atStage("plan-review").forDispatch(reviewRounds.size() + 1)
                        .run(loaded, user, "plan_reviewer", runId, null, reviewContext, false);
            } catch (Exhausted exhausted) {
                return fail("budget_exhausted", exhausted.getMessage(), null, spent, reviewRounds);
            } catch (dev.warden.ledger.HomeCorpus.UnavailableException unavailable) {
                return fail("ledger_unavailable", unavailable.getMessage(), null, spent, reviewRounds);
            } catch (IllegalStateException unconfigured) {
                return done(false, "role_unresolved", String.valueOf(unconfigured.getMessage()),
                        null, written, spent, planReview(reviewRounds));
            }
            spent = spent.plus(account(review));
            noteCall("plan_reviewer", reviewRounds.size() + 1, review);
            if (movedSince(git, beforeReview) || "role_violated_read_only".equals(review.code())) {
                restoreQuietly(git, beforeReview);
                return fail("plan_reviewer_protocol_violation", "the plan reviewer mutated the "
                        + "worktree; its verdict is discarded. read_only is enforced by a content "
                        + "fingerprint, not by the profile flag.", review, spent, reviewRounds);
            }
            if (!review.ok()) {
                return fail(review.code(), String.valueOf(review.details() == null
                                ? review.code() : review.details().getOrDefault("message", review.code())),
                        review, spent, reviewRounds);
            }
            Map<String, Object> verdict = artifactOf(review, "plan_reviewer");
            if (verdict == null) {
                return fail("role_artifact_unparseable", "plan reviewer returned no artifact",
                        review, spent, reviewRounds);
            }
            List<String> verdictErrors = Schema.validate(verdict,
                    Json.parse(UserSetup.planReviewerSchema()));
            if (!verdictErrors.isEmpty()) {
                return fail("role_artifact_schema_violation", "plan review failed the schema: "
                        + String.join("; ", verdictErrors), review, spent, reviewRounds);
            }
            List<Map<String, Object>> blocking = blockingFindings(verdict);
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("round", (long) (reviewRounds.size() + 1));
            round.put("profile", review.profile());
            round.put("vendor", review.vendor());
            round.put("verdict", verdict.get("verdict"));
            round.put("blocking_findings", (long) blocking.size());
            round.put("findings", verdict.get("findings") instanceof List<?> list ? list : List.of());
            reviewRounds.add(round);
            if (blocking.isEmpty() && !"fail".equals(verdict.get("verdict"))) {
                progress.line("      plan review passed"
                        + (reviewRounds.size() > 1 ? " on the redraft" : ""));
                return done(true, "ok", null, outcome, written, spent, planReview(reviewRounds));
            }
            progress.line("      plan review objected: " + blocking.size()
                    + " blocking finding(s)");
            if (amendment != null) {
                // An amendment is this preparation's own edit to a contract that was in force:
                // the objection withdraws it, and the one redraft starts from the old text.
                withdrawAmendment(written.file());
                if (redrafts >= REDRAFTS) {
                    return done(false, REVIEW_REMAINS, reviewMessage(blocking, written)
                            + " The amendment was withdrawn; the contract is as it was.",
                            review, written, spent, planReview(reviewRounds));
                }
                redrafts++;
                gate.grant(1);
                plannerContext = writeRedraftContext(root, runId, goal, granted, project, blocking,
                        written, false);
                appendAmendment(plannerContext);
                progress.line("      sending the objection back to the planner once");
                continue;
            }
            // The reviewer objected to the text the planner just compiled. A file that was
            // already on disk is not a reason to skip the redraft: that file's body has
            // already been replaced, and only the operator's blocks from the pre-compile
            // snapshot (budgets, timeout, use, and visual_qa only when the person wrote it)
            // are carried across. Deleting it would drop those blocks, so it stays and the
            // next compile carries from the snapshot again. A contract this preparation
            // created has no such blocks and is discarded so the redraft writes a new one.
            if (redrafts >= REDRAFTS) {
                return done(false, REVIEW_REMAINS, reviewMessage(blocking, written),
                        review, written, spent, planReview(reviewRounds));
            }
            redrafts++;
            gate.grant(1);
            boolean keepOperatorBlocks = written.existed();
            if (!keepOperatorBlocks) Files.deleteIfExists(written.file());
            plannerContext = writeRedraftContext(root, runId, goal, granted, project, blocking,
                    written, keepOperatorBlocks);
            progress.line("      sending the objection back to the planner once");
        }
    }

    private static String protocolCode(RoleRunner.Outcome outcome) {
        return "role_violated_read_only".equals(outcome.code()) ? PROTOCOL : outcome.code();
    }

    private static boolean movedSince(GitRepository git, GitRepository.WorkingTreeSnapshot baseline) {
        if (baseline == null) return false;
        try {
            return !git.matchesSnapshot(baseline);
        } catch (Exception cannotTell) {
            return true;
        }
    }

    private static GitRepository.WorkingTreeSnapshot snapshotOrNull(GitRepository git) {
        try {
            return git.snapshotWorkingTree();
        } catch (Exception cannotSnapshot) {
            return null;
        }
    }

    private static void restoreQuietly(GitRepository git, GitRepository.WorkingTreeSnapshot baseline) {
        if (baseline == null) return;
        try {
            git.restoreWorkingTree(baseline);
        } catch (Exception cannotRestore) {
            // The stop names the violation; a tree that cannot be restored is the person's.
        }
    }

    /**
     * The refusal for a judging role that cannot be filled because its candidates may write,
     * or null. Shared with the loop's preflight so both surfaces say the same thing.
     */
    static String writableJudge(RoleRunner roles, UserConfig user, String stage, String role) {
        if (user == null || user.policy() == null || !user.policy().roles().containsKey(role)) return null;
        Map<String, String> refused = roles.explainFill(user, stage, role, RoleResolver.Writers.NONE, 0);
        if (refused == null) return null;
        List<String> writable = refused.entrySet().stream()
                .filter(entry -> RoleResolver.JUDGE_NOT_READ_ONLY.equals(entry.getValue()))
                .map(Map.Entry::getKey).toList();
        if (writable.isEmpty()) return null;
        return "stage '" + stage + "' judges as role '" + role + "', and profile"
                + (writable.size() == 1 ? " '" + writable.get(0) + "' declares" : "s " + writable + " declare")
                + " read_only: false. A judge that may write can change what it judges, and "
                + "whether its vendor is independent of the writer would go unchecked. Set "
                + "read_only: true in the profile and verify it again, or give the role a "
                + "read-only profile. Nothing was dispatched. Every candidate refused: " + refused;
    }

    private Outcome fail(String code, String message, RoleRunner.Outcome role, Spend spent,
                         List<Map<String, Object>> reviewRounds) {
        return done(false, code, message, role, null, spent, planReview(reviewRounds));
    }

    private Outcome done(boolean ok, String code, String message, RoleRunner.Outcome role,
                         TaskDraft.Written written, Spend spent, Map<String, Object> planReview) {
        return new Outcome(ok, code, message, role, written, spent.runs, spent.cost, spent.unpriced,
                planReview, List.copyOf(calls));
    }

    /** One row of this preparation, shaped so a run report can join it to the role file. */
    private void noteCall(String role, int attempt, RoleRunner.Outcome outcome) {
        if (outcome == null) return;
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", role);
        step.put("attempt", (long) attempt);
        step.put("ok", outcome.ok());
        step.put("code", outcome.code());
        if (outcome.profile() != null) step.put("profile", outcome.profile());
        if (outcome.vendor() != null) step.put("vendor", outcome.vendor());
        if (outcome.report() != null) step.put("report", outcome.report().toString());
        if (outcome.details() != null) {
            if (outcome.details().get("cost_usd") != null) step.put("cost_usd", outcome.details().get("cost_usd"));
            if (outcome.details().get("vendor_attempts") != null) {
                step.put("vendor_attempts", outcome.details().get("vendor_attempts"));
            }
        }
        calls.add(step);
    }

    private static Map<String, Object> planReview(List<Map<String, Object>> rounds) {
        if (rounds == null || rounds.isEmpty()) return null;
        Map<String, Object> review = new LinkedHashMap<>();
        Map<String, Object> last = rounds.get(rounds.size() - 1);
        review.put("verdict", last.get("verdict"));
        review.put("rounds", (long) rounds.size());
        review.put("blocking_findings", last.get("blocking_findings"));
        review.put("history", List.copyOf(rounds));
        return review;
    }

    private static List<Map<String, Object>> blockingFindings(Map<String, Object> verdict) {
        List<Map<String, Object>> blocking = new ArrayList<>();
        if (!(verdict.get("findings") instanceof List<?> rows)) return blocking;
        for (Object row : rows) {
            if (row instanceof Map<?, ?> finding && "P1".equals(finding.get("severity"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) finding;
                blocking.add(typed);
            }
        }
        return blocking;
    }

    private static String reviewMessage(List<Map<String, Object>> blocking, TaskDraft.Written written) {
        StringBuilder message = new StringBuilder("the plan reviewer still objects to the compiled "
                + "contract at " + written.file() + " after the planner's one redraft"
                + (written.existed()
                        ? "; operator blocks from the pre-existing contract were kept"
                        : "")
                + "; no writer was dispatched. Blocking findings:");
        for (Map<String, Object> finding : blocking) {
            message.append(" [").append(finding.get("category") == null ? "other" : finding.get("category"))
                    .append("] ").append(finding.get("message"));
            if (finding.get("suggestion") != null) {
                message.append(" (suggestion: ").append(finding.get("suggestion")).append(')');
            }
            message.append(';');
        }
        return message.toString();
    }

    record Spend(int runs, double cost, int unpriced) {
        Spend plus(Spend other) {
            return new Spend(runs + other.runs, cost + other.cost, unpriced + other.unpriced);
        }
    }

    /**
     * Count the vendor attempts this dispatch actually made. Failover is more than one;
     * a report that treated the planner as free is the defect this repository already
     * refuses everywhere else.
     */
    static Spend account(RoleRunner.Outcome outcome) {
        int runs = 0;
        double cost = 0;
        int unpriced = 0;
        Object attempts = outcome.details() == null ? null : outcome.details().get("vendor_attempts");
        if (attempts instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                if (Boolean.FALSE.equals(map.get("budget_charged"))) continue;
                runs++;
                Object usd = map.get("cost_usd");
                if (usd instanceof Number number) cost += number.doubleValue();
                else unpriced++;
            }
        } else if (outcome.details() != null) {
            if (Boolean.FALSE.equals(outcome.details().get("budget_charged"))) return new Spend(0, 0, 0);
            runs = 1;
            Object usd = outcome.details().get("cost_usd");
            if (usd instanceof Number number) cost += number.doubleValue();
            else unpriced++;
        } else if (outcome.profile() != null) {
            runs = 1;
            unpriced = 1;
        }
        return new Spend(runs, cost, unpriced);
    }

    private static Map<String, Object> artifactOf(RoleRunner.Outcome outcome) {
        return artifactOf(outcome, "planner");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifactOf(RoleRunner.Outcome outcome, String role) {
        if (outcome.details() != null && outcome.details().get("artifact") instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Path report = outcome.report();
        if (report == null) return null;
        Path file = report.getParent().resolve("artifacts").resolve(role + ".json");
        try {
            if (Files.isRegularFile(file)) {
                return (Map<String, Object>) Json.parse(
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * One paying call, plus the bounded protocol retry, and nothing else. Quota failover
     * is another vendor call, so {@link #hasRoom()} is false after the first dispatch and
     * RoleRunner returns the spent-subscription outcome instead of paying a successor.
     * The plan review and the one redraft it may cause are granted one call at a time by
     * the preparation that decides to make them; nothing here assumes them.
     *
     * Every call granted here must also be admitted by {@code chain}, which answers for the
     * money, the calls and the time of a chain the preparation spends for.
     */
    static final class Bootstrap implements RoleRunner.DispatchGate {
        private final RoleRunner.DispatchGate chain;
        private int remaining = 1;
        private int protocolRetries = PROTOCOL_RETRIES;

        Bootstrap() { this(() -> { }); }

        Bootstrap(RoleRunner.DispatchGate chain) { this.chain = chain; }

        @Override
        public void requireDispatch() {
            if (remaining <= 0) {
                throw new Exhausted("planner bootstrap allows one call with no repair");
            }
            chain.requireDispatch();
            remaining--;
        }

        @Override
        public boolean hasRoom() {
            return remaining > 0 && chain.hasRoom();
        }

        @Override
        public java.time.Duration wallClockCap() { return chain.wallClockCap(); }

        @Override
        public void reserve(Double declaredBound) { chain.reserve(declaredBound); }

        @Override
        public void settleAttempt(Object costUsd, Double declaredBound) {
            chain.settleAttempt(costUsd, declaredBound);
        }

        /** The chain's reservation goes back; this bootstrap's own grant stays spent. */
        @Override
        public void release() { chain.release(); }

        void grantProtocolRetry() {
            if (protocolRetries <= 0) return;
            protocolRetries--;
            remaining++;
        }

        /** One more call, for a reading or a redraft the preparation decided to make. */
        void grant(int calls) {
            remaining += Math.max(0, calls);
        }
    }

    @SuppressWarnings("serial")
    static final class Exhausted extends RuntimeException {
        Exhausted(String message) { super(message); }
    }

    /**
     * The ceilings the planner's own view should show.
     *
     * A contract already on disk has the numbers a person chose. The bootstrap used to
     * substitute {@code Budget(1, 1.0)} and 20 minutes and label them {@code source: task},
     * so a planner that spent $1.89 against a $40 contract looked like it had overrun a
     * $1 cap nobody set. Measured 2026-09-26 on Berloga-AI: the task said 5 minutes.
     * Without a contract there is nothing to copy, and the one-call gate is the real bound.
     */
    public record PlannerLimits(TaskSpec.Budget budget, long timeoutMinutes) {}

    public static PlannerLimits plannerLimits(Path root, ProjectConfig project, String taskId) {
        Path contract = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
        if (Files.isRegularFile(contract)) {
            try {
                TaskSpec existing = TaskSpec.parse(
                        Files.readString(contract, StandardCharsets.UTF_8), contract.toString());
                TaskSpec.ResolvedTask resolved = existing.resolve(project, contract.toString());
                return new PlannerLimits(resolved.budget(), resolved.timeoutMinutes());
            } catch (RuntimeException | java.io.IOException unreadable) {
                // A contract that does not resolve still has to dispatch the planner.
                // The loop refuses it on its own if preparation gets that far.
            }
        }
        return new PlannerLimits(new TaskSpec.Budget(1, 1.0), project.defaultTimeoutMinutes());
    }

    /**
     * A task the planner can be dispatched against without writing a contract. The planner
     * runs before a task exists, so the task cannot bound it: write, network and land are
     * all false, there is no repair, and the profile's wall clock is the limit.
     * When a contract is already on disk, its budget and timeout are what the view records.
     */
    static ConfigLoader.Loaded bootstrap(Path root, ProjectConfig project, String taskId,
                                         String goal, String scope, String risk) {
        List<String> paths = project.scopes().getOrDefault(scope, List.of());
        PlannerLimits limits = plannerLimits(root, project, taskId);
        TaskSpec.ResolvedTask resolved = new TaskSpec.ResolvedTask(
                taskId,
                goal,
                List.of(),
                risk,
                project.baseRef(),
                List.copyOf(paths),
                List.of(),
                List.of(),
                new TaskSpec.Authority(false, false, false),
                new TaskSpec.VisualQa(false, List.of(), null, null),
                limits.budget(),
                0L,
                limits.timeoutMinutes());
        TaskSpec spec = TaskSpec.parse("version: 1\n"
                + "id: " + taskId + "\n"
                + "goal: " + TaskDraft.quote(goal) + "\n"
                + "risk: " + risk + "\n"
                + "scope: " + scope + "\n"
                + "visual_qa:\n"
                + "  required: true\n"
                + "  scenarios:\n"
                + "    - \"1280x720: no-console-errors\"\n",
                "planner-bootstrap");
        Path projectFile = root.resolve(".warden/project.yaml");
        Path taskFile = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
        return new ConfigLoader.Loaded(root, projectFile, taskFile, project, spec, resolved);
    }

    private Path writeContext(Path root, String runId, String goal, PlannerDraft.Access granted,
                              ProjectConfig project) throws Exception {
        Path file = new EvidenceLedger(root, runId).runDirectory()
                .resolve("context").resolve("prepare.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, bootstrapContext(goal, granted, project), StandardCharsets.UTF_8);
        return file;
    }

    private static String bootstrapContext(String goal, PlannerDraft.Access granted,
                                           ProjectConfig project) {
        StringBuilder body = new StringBuilder();
        body.append("# Planner bootstrap\n\n");
        body.append("You are read-only. You have no authority to write or to reach the network.\n");
        body.append("A content fingerprint of the worktree is taken around this call; if anything\n");
        body.append("moves, your draft is discarded as a protocol failure.\n\n");
        body.append("Operator goal (verbatim; carry this into operator_goal, do not replace it)\n");
        body.append(": ").append(goal).append("\n\n");
        body.append("The invocation authorised the following for the *contract you draft*, not for you:\n");
        body.append("- workspace_write: ").append(granted.workspaceWrite()).append('\n');
        body.append("- network: ").append(granted.network()).append('\n');
        body.append("- land: ").append(granted.land()).append('\n');
        body.append("You may request less. You may not request more.\n\n");
        body.append("Named checks in this project (acceptance must name one of these, never a shell string):\n");
        for (var entry : project.checks().entrySet()) {
            body.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append('\n');
        }
        body.append("\nNamed scopes in this project (scope must be one of these):\n");
        for (var entry : project.scopes().entrySet()) {
            body.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append('\n');
        }
        return body.toString();
    }

    /**
     * What an amending planner is told on top of the bootstrap: what it may change, the
     * contract as it stands, and the findings it is answering.
     */
    private void appendAmendment(Path context) throws IOException {
        StringBuilder body = new StringBuilder("\n# This is an amendment to a contract in force, not a new plan\n\n");
        body.append("The task below has been implemented and passed the checks it has. Independent readers of\n"
                + "the candidate found its acceptance too weak to have proved it. Close those gaps, and\n"
                + "only those:\n\n"
                + "- keep `operator_goal`, `scope` and `risk` exactly as in the contract below;\n"
                + "- in `acceptance`, name checks from the list above that would fail if a finding's\n"
                + "  expected behaviour broke; in `visual_qa.scenarios`, add browser scenarios in the\n"
                + "  harness grammar `WxH: <matcher> <assertion> [-> <matcher> <assertion> ...]`, with\n"
                + "  matchers `testid=`, `css=`, `role=`, `text=` and assertions `visible`, `hidden`,\n"
                + "  `click`; a `text=` step needs an anchored step (`testid=`, `css=`, `role=`) before it\n"
                + "  in the same scenario, e.g. `1280x720: testid=greeting visible -> text=Hello, World visible`;\n"
                + "- nothing you leave out is removed: Warden keeps every existing check and scenario and\n"
                + "  adds only what you name that is not there yet; your other fields are not used;\n"
                + "- if a finding cannot be expressed as a named check or a scenario, say so in `summary`.\n\n");
        body.append("## The contract as it stands\n\n```yaml\n").append(amendment.before().strip()).append("\n```\n\n");
        body.append("## What the readers found\n\n");
        for (Map<String, Object> gap : amendment.gaps()) {
            body.append("- **").append(gap.get("severity")).append("** [").append(gap.get("category")).append("] ")
                    .append(gap.get("message")).append('\n');
            for (String key : List.of("expected", "actual", "suggestion")) {
                if (gap.get(key) != null) body.append("  - ").append(key).append(": ").append(gap.get(key)).append('\n');
            }
        }
        Files.writeString(context, body.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    /** What the second planner is handed: the compiled contract and the draft it came from. */
    private Path writeReviewContext(Path root, String runId, String goal, TaskDraft.Written written,
                                    Map<String, Object> draft, int round, UserConfig user,
                                    ProjectConfig project) throws Exception {
        Path file = new EvidenceLedger(root, runId).runDirectory()
                .resolve("context").resolve("plan-review-" + round + ".md");
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        body.append("# The compiled contract you are judging\n\n");
        body.append("Operator goal (verbatim)\n: ").append(goal).append("\n\n");
        body.append("Contract file: `").append(written.file()).append("`");
        body.append(written.existed()
                ? " (compiled by the planner; operator blocks such as budgets were carried from the file that was already there)"
                : " (compiled by the planner for this preparation)");
        body.append("\n\n```yaml\n")
                .append(Files.readString(written.file(), StandardCharsets.UTF_8))
                .append("\n```\n\n");
        body.append("# The first planner's draft, for context only\n\n```json\n")
                .append(Json.writePretty(draft)).append("\n```\n\n");
        body.append("Judge the contract, not the draft: Warden compiled the contract from the draft "
                + "and may have refused parts of it. Round ").append(round).append(" of at most 2.\n");
        body.append(judgingStages(written, user, project));
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * The stages that will judge the candidate this contract asks for.
     *
     * A plan reviewer that is not told this files {@code acceptance_gap} against a check
     * script when {@code review} and {@code review-second} are about to read the content.
     * The conditions are the same ones {@link TaskLoop} routes by.
     */
    private static String judgingStages(TaskDraft.Written written, UserConfig user, ProjectConfig project) {
        if (user == null || user.policy() == null) return "";
        Workflow workflow = user.policy().workflow();
        String risk = "medium";
        TaskSpec.ResolvedTask task = null;
        try {
            TaskSpec spec = TaskSpec.parse(Files.readString(written.file(), StandardCharsets.UTF_8),
                    written.file().toString());
            task = spec.resolve(project, written.file().toString());
            if (spec.risk() != null) risk = spec.risk();
        } catch (Exception unreadable) {
            task = null;
        }
        boolean reviewByRisk = user.policy().reviewRequired(risk);
        StringBuilder body = new StringBuilder();
        body.append("\n# Stages that will judge the candidate\n\n");
        body.append("After this contract is accepted, Warden runs the workflow below. ");
        body.append("A named check is not the only acceptance when a judging stage will run: ");
        body.append("those stages read the candidate and can object to its content.\n\n");
        body.append("Risk: ").append(risk).append(reviewByRisk
                ? " — review is required.\n" : " — review is not required.\n");
        for (Workflow.Stage stage : workflow.stages()) {
            if ("implementer".equals(stage.role()) && stage.onFindings() == null
                    && stage.kind() == Workflow.Kind.ROLE) {
                continue;
            }
            String why = stageWillNotRun(stage, user, task, reviewByRisk, risk);
            body.append("- `").append(stage.name()).append("`");
            if (stage.role() != null) body.append(" (").append(stage.role()).append(')');
            body.append(why == null ? " will run" : " will not run (" + why + ")");
            if (stage.onFindings() != null) body.append("; findings route to ").append(stage.onFindings());
            body.append('\n');
        }
        return body.toString();
    }

    /** Same conditions as {@code TaskLoop}: a stage runs only when every one holds. */
    private static String stageWillNotRun(Workflow.Stage stage, UserConfig user,
                                          TaskSpec.ResolvedTask task, boolean reviewByRisk, String risk) {
        if (stage.kind() == Workflow.Kind.ROLE && (user.policy() == null
                || !user.policy().roles().containsKey(stage.role()))) {
            return "role " + stage.role() + " is not on the roster";
        }
        if (task == null && !stage.when().isEmpty()) return "the contract could not be measured";
        for (String condition : stage.when()) {
            boolean holds = switch (condition) {
                case "review_required" -> reviewByRisk;
                case "visual_qa_required" -> task != null && task.visualQa().required();
                case "risk_low" -> "low".equals(risk);
                case "risk_medium" -> "medium".equals(risk);
                case "risk_high" -> "high".equals(risk);
                default -> true;
            };
            if (!holds) return "condition " + condition + " is not met";
        }
        return null;
    }

    /** What the first planner is handed when it is sent back: its bootstrap and the objection. */
    private Path writeRedraftContext(Path root, String runId, String goal, PlannerDraft.Access granted,
                                     ProjectConfig project, List<Map<String, Object>> blocking,
                                     TaskDraft.Written discarded, boolean keepOperatorBlocks) throws Exception {
        Path file = new EvidenceLedger(root, runId).runDirectory()
                .resolve("context").resolve("prepare-redraft.md");
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder(bootstrapContext(goal, granted, project));
        body.append("\n# A second planner objected to your first draft\n\n");
        body.append(keepOperatorBlocks
                ? "This is your one redraft. The contract file stays, and the blocks a person wrote "
                        + "(budgets, timeout, use) are carried into the compile. A second objection "
                        + "stops for a person. Address each finding below or say in `summary` why it is wrong.\n\n"
                : "The contract compiled from it was discarded. This is your one redraft; a "
                        + "second objection stops for a person. Address each finding below or say in "
                        + "`summary` why it is wrong.\n\n");
        for (Map<String, Object> finding : blocking) {
            body.append("- **").append(finding.get("severity")).append("** [")
                    .append(finding.get("category") == null ? "other" : finding.get("category"))
                    .append("] ").append(finding.get("message")).append('\n');
            body.append("  - expected: ").append(finding.get("expected")).append('\n');
            body.append("  - actual: ").append(finding.get("actual")).append('\n');
            if (finding.get("suggestion") != null) {
                body.append("  - suggestion: ").append(finding.get("suggestion")).append('\n');
            }
        }
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
