package dev.warden.role;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.execution.Executors;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One role, start to finish: resolve which vendor fills it, render the prompt, run it, and
 * record what happened.
 *
 * `--dry-run` performs everything except the vendor call. It is the cheapest way to answer
 * "is my setup right" — it shows which profile would run, which were rejected and why, and
 * writes the exact prompt that would have been sent, all without spending a token.
 *
 * <h2>Failing over when a subscription is spent</h2>
 *
 * An exhausted subscription is the one failure that is not about the work. Handing the same
 * failure back to the same vendor is right for a broken build and wrong for a spent plan: the
 * retry refuses identically, and the loop spends its remaining budget discovering that. So a
 * run classified {@code role_quota_exhausted} re-resolves the role with that profile excluded
 * and dispatches the next eligible one.
 *
 * The exclusion is held in this instance and nowhere else. A loop reuses one runner, so a
 * vendor that ran out during the implement step is not tried again at review time; a fresh
 * `warden role` invocation starts clean, because a quota that reset overnight should not stay
 * disabled by a file left behind yesterday. Persisted exhaustion state would be the kind of
 * stale fact that is only discovered when it is already wrong.
 *
 * Failover is not free of consequence for a writing role: an implementer can be cut off after
 * it has already edited the tree. That is recorded rather than smoothed over, and the vendor
 * that inherits the work is told, in its prompt, that it is continuing someone else's.
 */
public final class RoleRunner {

    public record Outcome(
            boolean ok,
            String code,
            String role,
            String profile,
            String vendor,
            Map<String, String> rejected,
            Path report,
            Map<String, Object> details) {}

    /**
     * Consulted immediately before each vendor dispatch, and after a failover decision has
     * been taken. Throwing refuses the dispatch.
     *
     * The seam exists so that a bounded loop's budget is enforced per vendor call rather than
     * per role: without it, a role that failed over twice would spend three times what its
     * caller had counted, and the overrun would be discovered by paying for it.
     */
    @FunctionalInterface
    public interface DispatchGate {
        void requireDispatch();
    }

    private static final DispatchGate ALWAYS = () -> { };

    private final ProcessRunner processes;
    private final DispatchGate gate;

    /** Profiles that reported a spent subscription during the life of this runner. */
    private final Set<String> exhausted = new LinkedHashSet<>();

    public RoleRunner(ProcessRunner processes) { this(processes, ALWAYS); }

    public RoleRunner(ProcessRunner processes, DispatchGate gate) {
        this.processes = processes;
        this.gate = gate;
    }

    /** Profiles this runner has seen run out, in the order they did. */
    public Set<String> exhaustedProfiles() { return Set.copyOf(exhausted); }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, boolean dryRun) throws Exception {
        return run(loaded, user, role, runId, implementerVendor, contextFile, List.of(), dryRun);
    }

    /**
     * @param attachments files the role must look at rather than read about — screenshots,
     *                    today. Their paths always reach the prompt as {{screenshots}}; a
     *                    profile that declares {@code attachments.flag} also gets them on the
     *                    command line, which is the only way a model actually sees an image.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, List<Path> attachments,
                       boolean dryRun) throws Exception {
        if (user.policy() == null) {
            throw new IllegalStateException("no policy at " + user.home().resolve("policy.yaml")
                    + " — run `warden setup` to create a starter configuration");
        }
        Policy.RoleSpec spec = user.policy().roles().get(role);
        if (spec == null) {
            throw new IllegalStateException("role '" + role + "' is not configured in "
                    + user.home().resolve("policy.yaml") + "; configured roles: " + user.policy().roles().keySet());
        }

        Path root = loaded.root();
        GitRepository git = new GitRepository(root, processes);
        EvidenceLedger ledger = new EvidenceLedger(root, runId);
        TaskSpec.ResolvedTask task = loaded.resolved();
        String mergeBase = git.mergeBase(task.baseRef());

        // Rotation spreads load across profiles; it advances once per role invocation, not
        // once per failover attempt, or a single spent vendor would skew every later run.
        long rotation = nextRotation(root, role, dryRun);

        // Taken before the first vendor runs so that, if a writing role is cut off mid-edit,
        // the successor can be told the tree is not the one the task described.
        String fingerprintAtStart = git.fingerprint(mergeBase);

        List<Map<String, Object>> attempts = new ArrayList<>();
        double spent = 0;

        for (int attempt = 1; ; attempt++) {
            RoleResolver.Resolution resolution;
            try {
                resolution = new RoleResolver().resolve(role, user.policy(), user.profiles(),
                        implementerVendor, rotation, this::available, exhausted);
            } catch (RoleResolver.Unresolvable failure) {
                return unresolved(ledger, role, user, attempts, failure, spent);
            } catch (RuntimeException failure) {
                return unresolved(ledger, role, user, attempts,
                        new RoleResolver.Unresolvable(String.valueOf(failure.getMessage()), Map.of()), spent);
            }

            Profile profile = resolution.selected();

            // An implementer needs write authority from the task itself, not only from its
            // profile. This is a contract error, not a vendor failure: no failover applies.
            if (!profile.readOnly() && !task.authority().workspaceWrite()) {
                Map<String, Object> details = Map.of(
                        "message", "profile '" + profile.name() + "' writes to the workspace, but the task "
                                + "grants authority.workspace_write: false",
                        "resolution", "either raise the task's authority deliberately, or use a read-only role");
                return new Outcome(false, "authority_denied", role, profile.name(), profile.vendor(),
                        resolution.rejected(), null, details);
            }

            boolean inheritsUnfinishedWork = attempt > 1
                    && !git.fingerprint(mergeBase).equals(fingerprintAtStart);
            String evidenceName = attempt == 1 ? role : role + ".attempt-" + attempt;

            Map<String, String> values = promptValues(loaded, task, runId, mergeBase, user, profile,
                    contextFile, inheritsUnfinishedWork, attempts, attachments);

            if (profile.promptTemplate() == null) {
                throw new IllegalStateException("profile '" + profile.name()
                        + "' has no prompt_template; a role with no prompt cannot be run");
            }
            Path templateFile = user.resolve(profile.promptTemplate());
            if (!Files.isRegularFile(templateFile)) {
                throw new IllegalStateException("prompt template not found: " + templateFile);
            }
            String prompt = PromptRenderer.render(Files.readString(templateFile), values, templateFile.toString());

            Path promptDirectory = ledger.runDirectory().resolve("prompts");
            Files.createDirectories(promptDirectory);
            Path promptFile = promptDirectory.resolve(evidenceName + ".md");
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("run_id", runId);
            report.put("role", role);
            report.put("task_id", task.id());
            report.put("profile", profile.name());
            report.put("vendor", profile.vendor());
            report.put("model", profile.model());
            report.put("read_only", profile.readOnly());
            report.put("runner", profile.runner());
            report.put("rotation_counter", rotation);
            report.put("strategy", spec.strategy());
            report.put("independence_required", spec.requireIndependentVendor());
            report.put("avoided_vendor", implementerVendor);
            report.put("rejected_profiles", resolution.rejected());
            report.put("prompt_path", root.relativize(promptFile).toString().replace('\\', '/'));
            report.put("prompt_sha256", GitRepository.sha256(promptFile));
            report.put("diff_base_commit", mergeBase);
            report.put("wall_clock_minutes", profile.wallClockMinutes());
            report.put("command_preview", commandPreview(profile));
            report.put("attachment_count", (long) (attachments == null ? 0 : attachments.size()));
            if (attachments != null && !attachments.isEmpty() && profile.attachmentFlag() == null) {
                // Not fatal: the paths are in the prompt and an agentic vendor can open them.
                // Recorded because "the reviewer looked at the screenshots" and "the reviewer
                // was told where the screenshots are" are different claims.
                report.put("attachments_not_passed_to_vendor",
                        "profile '" + profile.name() + "' declares no attachments.flag, so the "
                                + "images reach it only as paths inside the prompt");
            }
            report.put("vendor_attempt", (long) attempt);

            if (dryRun) {
                report.put("dry_run", true);
                report.put("ok", true);
                Path path = ledger.writeReport("role-" + role + "-dryrun", report);
                ledger.append("role_dry_run", Map.of("role", role, "profile", profile.name(),
                        "vendor", profile.vendor()));
                return new Outcome(true, "dry_run", role, profile.name(), profile.vendor(),
                        resolution.rejected(), path, report);
            }

            // Budget is checked here — after the routing decision, before anything is spent.
            gate.requireDispatch();

            Path schemaFile = profile.jsonSchema() == null ? null : user.resolve(profile.jsonSchema());
            RoleExecutor executor = Executors.forProfile(profile, processes, git);
            RoleExecutor.Result result = executor.execute(new RoleExecutor.Request(
                    runId, role, profile, task, root, ledger.runDirectory(), promptFile, schemaFile,
                    values.get("context"), evidenceName, attachments == null ? List.of() : attachments));

            report.put("dry_run", false);
            report.put("ok", result.ok());
            report.put("code", result.code());
            report.put("duration_millis", result.duration().toMillis());
            report.putAll(result.evidence());
            if (inheritsUnfinishedWork) {
                report.put("inherited_unfinished_work", true);
            }

            attempts.add(attemptRecord(attempt, profile, result, inheritsUnfinishedWork));
            if (report.get("cost_usd") instanceof Number number) spent += number.doubleValue();

            boolean quota = "role_quota_exhausted".equals(result.code());
            if (quota) {
                exhausted.add(profile.name());
                ledger.append("role_quota_exhausted", Map.of(
                        "role", role, "profile", profile.name(), "vendor", profile.vendor(),
                        "quota", result.evidence().getOrDefault("quota", Map.of())));
                if (canFailOver(role, user, implementerVendor, rotation)) {
                    continue;
                }
                // Last vendor standing. The report has to carry the operator's next move,
                // because this is where the run ends and nothing downstream will add it.
                report.put("exhausted_profiles", List.copyOf(exhausted));
                report.put("resolution", "every profile able to fill this role reported a spent "
                        + "subscription; add a profile from another vendor, or wait for the quota "
                        + "window named in the vendor message and re-run");
            }

            if (result.ok() && result.artifact() != null) {
                Path artifactDirectory = ledger.runDirectory().resolve("artifacts");
                Files.createDirectories(artifactDirectory);
                Path artifactFile = artifactDirectory.resolve(role + ".json");
                Files.writeString(artifactFile, Json.writePretty(result.artifact()) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                report.put("artifact_path", root.relativize(artifactFile).toString().replace('\\', '/'));
                report.put("artifact_sha256", GitRepository.sha256(artifactFile));
            }

            report.put("vendor_attempts", attempts);
            report.put("attempts_cost_usd", spent);
            if (attempts.size() > 1) {
                report.put("failed_over_from", attempts.stream()
                        .limit(attempts.size() - 1L)
                        .map(entry -> String.valueOf(entry.get("profile"))).toList());
            }

            Path path = ledger.writeReport("role-" + role, report);
            ledger.append("role_run", report);
            return new Outcome(result.ok(), result.code(), role, profile.name(), profile.vendor(),
                    resolution.rejected(), path, report);
        }
    }

    /**
     * Whether another profile could fill this role once the exhausted ones are removed. Asked
     * before continuing so that the last vendor's failure is reported as its own outcome
     * rather than as an unresolvable role.
     */
    private boolean canFailOver(String role, UserConfig user, String implementerVendor, long rotation) {
        try {
            new RoleResolver().resolve(role, user.policy(), user.profiles(), implementerVendor,
                    rotation, this::available, exhausted);
            return true;
        } catch (RuntimeException none) {
            return false;
        }
    }

    /**
     * No profile could fill the role. When the reason is that every candidate ran out, that is
     * reported as its own code: "nothing is configured" and "everything is spent" call for
     * opposite responses from an operator, and one of them resolves itself with time.
     */
    private Outcome unresolved(EvidenceLedger ledger, String role, UserConfig user,
                               List<Map<String, Object>> attempts, RoleResolver.Unresolvable failure,
                               double spent) throws Exception {
        boolean quota = !attempts.isEmpty()
                || failure.rejected().containsValue("quota_exhausted_this_run");
        String code = quota ? "role_quota_exhausted" : "role_unresolved";

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", String.valueOf(failure.getMessage()));
        details.put("known_profiles", List.copyOf(user.profiles().keySet()));
        details.put("profile_problems", user.problems());
        details.put("rejected_profiles", failure.rejected());
        if (!attempts.isEmpty()) {
            details.put("vendor_attempts", attempts);
            details.put("attempts_cost_usd", spent);
            details.put("exhausted_profiles", List.copyOf(exhausted));
            details.put("resolution", "every profile able to fill this role reported a spent "
                    + "subscription; add a profile from another vendor, or wait for the quota "
                    + "window named in the vendor message and re-run");
        }
        ledger.append(code, Map.of("role", role, "message", String.valueOf(failure.getMessage())));
        return new Outcome(false, code, role, null, null, failure.rejected(), null, details);
    }

    private Map<String, Object> attemptRecord(int attempt, Profile profile, RoleExecutor.Result result,
                                              boolean inheritsUnfinishedWork) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("attempt", (long) attempt);
        entry.put("profile", profile.name());
        entry.put("vendor", profile.vendor());
        entry.put("ok", result.ok());
        entry.put("code", result.code());
        entry.put("duration_millis", result.duration().toMillis());
        Object cost = result.evidence().get("cost_usd");
        if (cost != null) entry.put("cost_usd", cost);
        Object raw = result.evidence().get("raw_stdout");
        if (raw != null) entry.put("raw_stdout", raw);
        Object quota = result.evidence().get("quota");
        if (quota != null) entry.put("quota", quota);
        if (inheritsUnfinishedWork) entry.put("inherited_unfinished_work", true);
        return entry;
    }

    private Map<String, String> promptValues(ConfigLoader.Loaded loaded, TaskSpec.ResolvedTask task,
                                             String runId, String mergeBase, UserConfig user,
                                             Profile profile, Path contextFile,
                                             boolean inheritsUnfinishedWork,
                                             List<Map<String, Object>> attempts,
                                             List<Path> attachments) throws Exception {
        String schemaPretty = "";
        String schemaJson = "";
        if (profile.jsonSchema() != null) {
            Path schemaFile = user.resolve(profile.jsonSchema());
            if (Files.isRegularFile(schemaFile)) {
                Object schema = Json.parse(Files.readString(schemaFile));
                schemaPretty = Json.writePretty(schema);
                schemaJson = Json.write(schema);
            }
        }
        String context = contextFile != null && Files.isRegularFile(contextFile)
                ? Files.readString(contextFile) : "";
        if (inheritsUnfinishedWork) context = handover(attempts) + context;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_id", task.id());
        values.put("run_id", runId);
        values.put("goal", task.goal());
        values.put("non_goals", bullets(task.nonGoals()));
        values.put("risk", task.risk());
        values.put("project", loaded.project().project());
        values.put("base_ref", task.baseRef());
        values.put("diff_base_commit", mergeBase);
        values.put("scope_paths", String.join(", ", task.scopePaths()));
        values.put("acceptance_commands", bullets(task.acceptanceCommands()));
        values.put("visual_scenarios", bullets(task.visualQa().scenarios()));
        values.put("authority", "workspace_write=" + task.authority().workspaceWrite()
                + ", network=" + task.authority().network() + ", land=" + task.authority().land());
        values.put("max_fix_attempts", String.valueOf(task.maxFixAttempts()));
        values.put("timeout_minutes", String.valueOf(task.timeoutMinutes()));
        values.put("budget_max_role_runs", String.valueOf(task.budget().maxRoleRuns()));
        values.put("budget_max_cost_usd", String.valueOf(task.budget().maxCostUsd()));
        values.put("contract_path", loaded.root().relativize(loaded.taskFile()).toString().replace('\\', '/'));
        values.put("schema_json", schemaJson);
        values.put("schema_pretty", schemaPretty);
        values.put("context", context);
        values.put("context_path", contextFile == null ? "" : contextFile.toString());
        values.put("screenshots", attachmentList(attachments));
        return values;
    }

    /**
     * What a vendor is told when it picks up a role another vendor was cut off from. It is
     * given the fact and no reassurance: the tree it is looking at is partly someone else's
     * work, and pretending otherwise produces a second implementation layered on a first.
     */
    private static String handover(List<Map<String, Object>> attempts) {
        StringBuilder builder = new StringBuilder("# You are continuing an interrupted run\n\n");
        for (Map<String, Object> attempt : attempts) {
            builder.append("- `").append(attempt.get("profile")).append("` (vendor `")
                    .append(attempt.get("vendor")).append("`) stopped with `")
                    .append(attempt.get("code")).append("`.\n");
        }
        builder.append("""

                Its subscription ran out; it was not stopped for doing the wrong thing. The
                worktree already contains edits it made, and those edits are unreviewed and may
                be half of a larger change.

                Read the current state of the files in scope before writing anything. Finish the
                task as specified — do not restart it from scratch, and do not assume the
                existing edits are correct.

                """);
        return builder.toString();
    }

    private static String attachmentList(List<Path> attachments) {
        if (attachments == null || attachments.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Path attachment : attachments) {
            builder.append("- ").append(attachment.toAbsolutePath()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String bullets(List<String> items) {
        if (items == null || items.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (String item : items) builder.append("- ").append(item).append('\n');
        return builder.toString().stripTrailing();
    }

    private static List<String> commandPreview(Profile profile) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(profile.command()),
                profile.args().stream()).toList();
    }

    /** A vendor whose executable is absent is skipped, never attempted mid-loop. */
    private boolean available(Profile profile) {
        String command = "orca".equals(profile.runner()) ? "orca" : profile.command();
        if (command.contains("/") || command.contains("\\")) return Files.isExecutable(Path.of(command));
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> probe = windows ? List.of("where.exe", command) : List.of("sh", "-c", "command -v " + command);
        try {
            ProcessRunner.Result result = processes.run(probe, Path.of("."), Duration.ofSeconds(10));
            return result.ok() && !result.stdout().isBlank();
        } catch (Exception failure) {
            return false;
        }
    }

    /** Rotation state lives with the project's run evidence, so it is local and disposable. */
    private long nextRotation(Path root, String role, boolean dryRun) throws Exception {
        Path file = root.resolve(".warden/runs/rotation.json");
        Map<String, Object> state = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            Map<String, Object> parsed = Json.findLastObject(Files.readString(file));
            if (parsed != null) state.putAll(parsed);
        }
        long current = state.get(role) instanceof Number number ? number.longValue() : 0L;
        if (dryRun) return current;
        state.put(role, current + 1);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.writePretty(state) + System.lineSeparator(), StandardCharsets.UTF_8);
        return current;
    }
}
