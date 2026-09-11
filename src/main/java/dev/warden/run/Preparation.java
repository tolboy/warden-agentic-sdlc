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
import dev.warden.role.RoleRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
public final class Preparation {

    public static final Set<String> MODES = Set.of("off", "auto", "always");
    public static final String PROTOCOL = "planner_protocol_violation";
    public static final String DRAFT_INVALID = "planner_draft_invalid";

    /** One retry after a protocol failure, then stop. Not a repair: the draft is discarded. */
    private static final int PROTOCOL_RETRIES = 1;

    public record Outcome(
            boolean ok,
            String code,
            String message,
            RoleRunner.Outcome role,
            TaskDraft.Written written,
            int roleRuns,
            double costUsd,
            int unpriced) {}

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

        int runs = 0;
        double cost = 0;
        int unpriced = 0;
        int protocolAttempts = 0;

        while (true) {
            progress.line("prep  planner  one read-only call, no repair");
            RoleRunner.Outcome outcome;
            try {
                outcome = roles.forDispatch(protocolAttempts + 1)
                        .run(loaded, user, "planner", runId, null, context, dryRun);
            } catch (Exhausted exhausted) {
                return fail("budget_exhausted", exhausted.getMessage(),
                        null, runs, cost, unpriced);
            } catch (dev.warden.ledger.HomeCorpus.UnavailableException unavailable) {
                // Same refusal the loop reports, under the same name. Preparation crosses
                // the dispatch gate like any other paid call, and nothing is spent when it
                // refuses; what was missing was the name on this surface.
                return fail("ledger_unavailable", unavailable.getMessage(),
                        null, runs, cost, unpriced);
            } catch (IllegalStateException unconfigured) {
                return new Outcome(false, "role_unresolved", String.valueOf(unconfigured.getMessage()),
                        null, null, runs, cost, unpriced);
            }
            Spend spend = account(outcome);
            runs += spend.runs;
            cost += spend.cost;
            unpriced += spend.unpriced;

            if (dryRun) {
                return new Outcome(true, "dry_run", "planner dry-run; no contract compiled",
                        outcome, null, 0, 0, 0);
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
                            outcome, runs, cost, unpriced);
                }
                try {
                    git.restoreWorkingTree(baseline);
                } catch (Exception cannotRestore) {
                    return fail(PROTOCOL, "planner mutated the worktree and only the planner's "
                            + "writes could not be discarded for a retry: "
                            + cannotRestore.getMessage(),
                            outcome, runs, cost, unpriced);
                }
                if (protocolAttempts <= PROTOCOL_RETRIES) {
                    gate.grantProtocolRetry();
                    progress.line("      retrying once, on a restored tree");
                    continue;
                }
                return fail(PROTOCOL, "planner mutated the worktree; the draft is discarded. "
                        + "read_only is enforced by a content fingerprint, not by the profile flag.",
                        outcome, runs, cost, unpriced);
            }
            if (!outcome.ok()) {
                return fail(outcome.code(), String.valueOf(outcome.details() == null
                                ? outcome.code() : outcome.details().getOrDefault("message",
                                outcome.code())),
                        outcome, runs, cost, unpriced);
            }
            Map<String, Object> artifact = artifactOf(outcome);
            if (artifact == null) {
                return fail("role_artifact_unparseable",
                        "planner returned no artifact to compile",
                        outcome, runs, cost, unpriced);
            }
            // The executor honours profile.enforce_schema. The planner's draft is compiled
            // into a contract, so Warden checks the shipped schema here as well: a profile
            // that skipped enforcement cannot smuggle a draft the schema would refuse.
            List<String> schemaErrors = Schema.validate(artifact, Json.parse(UserSetup.plannerSchema()));
            if (!schemaErrors.isEmpty()) {
                return fail("role_artifact_schema_violation",
                        "planner draft failed the schema: " + String.join("; ", schemaErrors),
                        outcome, runs, cost, unpriced);
            }
            try {
                TaskDraft.Written written = PlannerDraft.write(root, taskId, goal, project,
                        granted, artifact);
                progress.line("      compiled " + written.file());
                return new Outcome(true, "ok", null, outcome, written, runs, cost, unpriced);
            } catch (TaskDraft.TaskConflict conflict) {
                return fail("task_conflict", conflict.getMessage(), outcome, runs, cost, unpriced);
            } catch (ConfigException invalid) {
                return fail(DRAFT_INVALID, invalid.getMessage(), outcome, runs, cost, unpriced);
            }
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

    private static Outcome fail(String code, String message, RoleRunner.Outcome role,
                                int runs, double cost, int unpriced) {
        return new Outcome(false, code, message, role, null, runs, cost, unpriced);
    }

    private record Spend(int runs, double cost, int unpriced) {}

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifactOf(RoleRunner.Outcome outcome) {
        if (outcome.details() != null && outcome.details().get("artifact") instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Path report = outcome.report();
        if (report == null) return null;
        Path file = report.getParent().resolve("artifacts").resolve("planner.json");
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
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
