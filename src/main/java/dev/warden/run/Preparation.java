package dev.warden.run;

import dev.warden.config.ConfigException;
import dev.warden.config.ConfigLoader;
import dev.warden.config.PlannerDraft;
import dev.warden.config.ProjectConfig;
import dev.warden.config.TaskDraft;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
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
 */
public final class Preparation {

    public static final Set<String> MODES = Set.of("off", "auto", "always");
    public static final String PROTOCOL = "planner_protocol_violation";
    public static final String DRAFT_INVALID = "planner_draft_invalid";
    public static final String REVIEW_REMAINS = "plan_review_findings_remain";

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
            Map<String, Object> planReview) {

        public Outcome(boolean ok, String code, String message, RoleRunner.Outcome role,
                       TaskDraft.Written written, int roleRuns, double costUsd, int unpriced) {
            this(ok, code, message, role, written, roleRuns, costUsd, unpriced, null);
        }
    }

    private final ProcessRunner processes;
    private final Progress progress;

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
        ConfigLoader.Loaded loaded = bootstrap(root, project, taskId, goal, scope, risk);
        Path context = writeContext(root, runId, goal, granted, project);
        GitRepository git = new GitRepository(root, processes);
        GitRepository.WorkingTreeSnapshot baseline;
        try {
            baseline = git.snapshotWorkingTree();
        } catch (Exception cannotSnapshot) {
            baseline = null;
        }
        Bootstrap gate = new Bootstrap();
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
                written = PlannerDraft.write(root, taskId, goal, project, granted, artifact);
                progress.line("      compiled " + written.file());
            } catch (TaskDraft.TaskConflict conflict) {
                return fail("task_conflict", conflict.getMessage(), outcome, spent, reviewRounds);
            } catch (ConfigException invalid) {
                return fail(DRAFT_INVALID, invalid.getMessage(), outcome, spent, reviewRounds);
            }
            if (!reviewsPlans(user)) {
                return new Outcome(true, "ok", null, outcome, written, spent.runs, spent.cost,
                        spent.unpriced, null);
            }

            // The second planner reads the compiled contract. One call, granted here rather
            // than assumed by the bootstrap, so a policy without a reviewer keeps the single
            // call it always had.
            gate.grant(1);
            Path reviewContext = writeReviewContext(root, runId, goal, written, artifact,
                    reviewRounds.size() + 1);
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
                return new Outcome(false, "role_unresolved", String.valueOf(unconfigured.getMessage()),
                        null, written, spent.runs, spent.cost, spent.unpriced, planReview(reviewRounds));
            }
            spent = spent.plus(account(review));
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
                return new Outcome(true, "ok", null, outcome, written, spent.runs, spent.cost,
                        spent.unpriced, planReview(reviewRounds));
            }
            progress.line("      plan review objected: " + blocking.size()
                    + " blocking finding(s)");
            // A contract that was already on disk is not this preparation's to discard: the
            // objection goes to a person, with the contract and the findings both intact.
            if (written.existed() || redrafts >= REDRAFTS) {
                return new Outcome(false, REVIEW_REMAINS, reviewMessage(blocking, written),
                        review, written, spent.runs, spent.cost, spent.unpriced,
                        planReview(reviewRounds));
            }
            // Send the first planner back once, with the objection as its context, and
            // discard only the contract this preparation wrote a moment ago.
            redrafts++;
            gate.grant(1);
            Files.deleteIfExists(written.file());
            plannerContext = writeRedraftContext(root, runId, goal, granted, project, blocking, written);
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

    private static Outcome fail(String code, String message, RoleRunner.Outcome role, Spend spent,
                                List<Map<String, Object>> reviewRounds) {
        return new Outcome(false, code, message, role, null, spent.runs, spent.cost, spent.unpriced,
                planReview(reviewRounds));
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
                + "contract at " + written.file() + " after "
                + (written.existed() ? "reading a contract that already existed"
                        : "the planner's one redraft")
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

    private record Spend(int runs, double cost, int unpriced) {
        Spend plus(Spend other) {
            return new Spend(runs + other.runs, cost + other.cost, unpriced + other.unpriced);
        }
    }

    /**
     * Count the vendor attempts this dispatch actually made. Failover is more than one;
     * a report that treated the planner as free is the defect this repository already
     * refuses everywhere else.
     */
    private static Spend account(RoleRunner.Outcome outcome) {
        int runs = 0;
        double cost = 0;
        int unpriced = 0;
        Object attempts = outcome.details() == null ? null : outcome.details().get("vendor_attempts");
        if (attempts instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                runs++;
                Object usd = map.get("cost_usd");
                if (usd instanceof Number number) cost += number.doubleValue();
                else unpriced++;
            }
        } else if (outcome.details() != null) {
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
     */
    static final class Bootstrap implements RoleRunner.DispatchGate {
        private int remaining = 1;
        private int protocolRetries = PROTOCOL_RETRIES;

        @Override
        public void requireDispatch() {
            if (remaining <= 0) {
                throw new Exhausted("planner bootstrap allows one call with no repair");
            }
            remaining--;
        }

        @Override
        public boolean hasRoom() {
            return remaining > 0;
        }

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
     * A task the planner can be dispatched against without writing a contract. The planner
     * runs before a task exists, so the task cannot bound it: write, network and land are
     * all false, there is no repair, and the profile's wall clock is the limit.
     */
    static ConfigLoader.Loaded bootstrap(Path root, ProjectConfig project, String taskId,
                                         String goal, String scope, String risk) {
        List<String> paths = project.scopes().getOrDefault(scope, List.of());
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
                new TaskSpec.Budget(1, 1.0),
                0L,
                20L);
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

    /** What the second planner is handed: the compiled contract and the draft it came from. */
    private Path writeReviewContext(Path root, String runId, String goal, TaskDraft.Written written,
                                    Map<String, Object> draft, int round) throws Exception {
        Path file = new EvidenceLedger(root, runId).runDirectory()
                .resolve("context").resolve("plan-review-" + round + ".md");
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        body.append("# The compiled contract you are judging\n\n");
        body.append("Operator goal (verbatim)\n: ").append(goal).append("\n\n");
        body.append("Contract file: `").append(written.file()).append("`")
                .append(written.existed() ? " (it already existed; a person wrote or accepted it)" : "")
                .append("\n\n```yaml\n")
                .append(Files.readString(written.file(), StandardCharsets.UTF_8))
                .append("\n```\n\n");
        body.append("# The first planner's draft, for context only\n\n```json\n")
                .append(Json.writePretty(draft)).append("\n```\n\n");
        body.append("Judge the contract, not the draft: Warden compiled the contract from the draft "
                + "and may have refused parts of it. Round ").append(round).append(" of at most 2.\n");
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** What the first planner is handed when it is sent back: its bootstrap and the objection. */
    private Path writeRedraftContext(Path root, String runId, String goal, PlannerDraft.Access granted,
                                     ProjectConfig project, List<Map<String, Object>> blocking,
                                     TaskDraft.Written discarded) throws Exception {
        Path file = new EvidenceLedger(root, runId).runDirectory()
                .resolve("context").resolve("prepare-redraft.md");
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder(bootstrapContext(goal, granted, project));
        body.append("\n# A second planner objected to your first draft\n\n");
        body.append("The contract compiled from it was discarded. This is your one redraft; a "
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
