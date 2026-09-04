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

    /**
     * The profile this resolution just chose, so a beat can name who is actually inside the
     * role without asking the resolver a second time.
     *
     * Resolution advances rotation state and consults the run's exclusion list. A second
     * lookup from the loop would rotate twice and could name a profile that was not the one
     * dispatched. Throwing here is swallowed: naming the vendor is a retelling, not a reason
     * to stop a paid call.
     */
    @FunctionalInterface
    public interface Occupied {
        void by(String profile, String vendor);
    }

    private static final DispatchGate ALWAYS = () -> { };
    private static final Occupied NOBODY = (profile, vendor) -> { };

    private final ProcessRunner processes;
    private final DispatchGate gate;
    private final String pinnedDiffBase;
    private final String workflowRunId;
    private final Map<String, String> authorizedFailover;
    private final dev.warden.run.Progress progress;
    private Occupied occupied = NOBODY;

    /** Profiles that reported a spent subscription during the life of this runner. */
    private final Set<String> exhausted = new LinkedHashSet<>();

    public RoleRunner(ProcessRunner processes) { this(processes, ALWAYS, null, null); }

    public RoleRunner(ProcessRunner processes, DispatchGate gate) {
        this(processes, gate, null, null);
    }

    /**
     * @param pinnedDiffBase immutable commit selected by the outer run controller before any
     *                       vendor is dispatched. Null is retained for the standalone
     *                       {@code warden role} command, which resolves its own one-shot base.
     */
    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase) {
        this(processes, gate, pinnedDiffBase, null);
    }

    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId) {
        this(processes, gate, pinnedDiffBase, workflowRunId, Map.of());
    }

    /**
     * @param authorizedFailover role to profile substitutions a human has already agreed to,
     *                           carried from a resolved `switch` decision on an earlier run.
     *                           Each entry authorises exactly that swap and nothing else.
     */
    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId, Map<String, String> authorizedFailover) {
        this(processes, gate, pinnedDiffBase, workflowRunId, authorizedFailover,
                dev.warden.run.Progress.SILENT);
    }

    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId, Map<String, String> authorizedFailover,
                      dev.warden.run.Progress progress) {
        this.processes = processes;
        this.gate = gate;
        this.pinnedDiffBase = pinnedDiffBase;
        this.workflowRunId = workflowRunId;
        this.authorizedFailover = Map.copyOf(authorizedFailover);
        this.progress = progress;
    }

    /**
     * Who should hear the profile the next resolution actually chooses.
     *
     * One runner is reused for every role in a loop. The loop points this at a callback that
     * reads the beat currently wrapping the dispatch, so a fix round that runs with no beat
     * is a no-op rather than a name invented for a stage that has already ended.
     */
    public RoleRunner occupying(Occupied occupied) {
        this.occupied = occupied == null ? NOBODY : occupied;
        return this;
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
        String mergeBase = pinnedDiffBase != null ? pinnedDiffBase : git.mergeBase(task.baseRef());

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
            // Published from this resolution, not looked up again: a second resolve would
            // advance rotation and could name a profile that was not dispatched.
            publish(profile);

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
            String template = Files.readString(templateFile);
            String prompt = PromptRenderer.render(template, values, templateFile.toString());
            boolean backfilled = backfillNeeded(role, task, template);
            if (backfilled) prompt = prompt + browserScenarioSection(values.get("visual_scenarios"));

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
            if (backfilled) report.put("prompt_backfilled", List.of("visual_scenarios"));
            report.put("rejected_profiles", resolution.rejected());
            report.put("prompt_path", root.relativize(promptFile).toString().replace('\\', '/'));
            report.put("prompt_sha256", GitRepository.sha256(promptFile));
            report.put("diff_base_commit", mergeBase);
            report.put("wall_clock_minutes", profile.wallClockMinutes());
            if ("local".equals(profile.runner())) {
                report.put("dispatch_preview", dispatchPreview(profile));
            } else {
                report.put("command_preview", commandPreview(profile));
            }
            report.put("attachment_count", (long) (attachments == null ? 0 : attachments.size()));
            if (profile.vision() != null) {
                report.put("vision_capability", Map.of(
                        "verified", profile.hasVerifiedVision(),
                        "delivery", profile.vision().delivery()));
            }
            boolean opensFilesItself = profile.vision() != null
                    && "workspace_file".equals(profile.vision().delivery());
            if (attachments != null && !attachments.isEmpty()
                    && profile.attachmentFlag() == null && !opensFilesItself) {
                // Not fatal: the paths are in the prompt and an agentic vendor can open them.
                // Recorded because "the reviewer looked at the screenshots" and "the reviewer
                // was told where the screenshots are" are different claims. A profile that
                // declares `vision.delivery: workspace_file` has made the second claim on
                // purpose, so warning about it would be noise, not a finding.
                report.put("attachments_not_passed_to_vendor",
                        "profile '" + profile.name() + "' declares no attachments.flag and no "
                                + "workspace_file vision, so the images reach it only as paths "
                                + "inside the prompt");
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

            // Said before the call, not after it: this is the line an operator reads while a
            // vendor is busy for ten minutes, and "which model is working right now" is the
            // question the silence was hiding.
            progress.line("      " + profile.name() + "  " + profile.vendor()
                    + (profile.model() == null ? "" : "/" + profile.model())
                    + (attempt > 1 ? "  (vendor attempt " + attempt + ")" : "")
                    + "  dispatching, up to " + profile.wallClockMinutes() + " min");

            Path schemaFile = profile.jsonSchema() == null ? null : user.resolve(profile.jsonSchema());
            RoleExecutor executor = Executors.forProfile(profile, processes, git);
            RoleExecutor.Result result = executor.execute(new RoleExecutor.Request(
                    runId, workflowRunId != null ? workflowRunId : runId,
                    role, profile, task, mergeBase, root, ledger.runDirectory(), promptFile, schemaFile,
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
                Profile candidate = failoverCandidate(role, user, implementerVendor, rotation);
                if (candidate != null) {
                    String mode = user.policy().failoverMode();
                    boolean preAuthorized = candidate.name().equals(authorizedFailover.get(role));
                    if ("auto".equals(mode) || preAuthorized) {
                        // Switching vendors mid-role changes who wrote the work, so it is an
                        // event in its own right rather than a line in a report nobody reads.
                        ledger.append("role_failover", failoverEvent(role, profile, candidate,
                                result, preAuthorized ? "human_switch_decision" : "policy_auto"));
                        continue;
                    }
                    if ("confirm".equals(mode)) {
                        Map<String, Object> pending = failoverEvent(role, profile, candidate,
                                result, "pending_human_confirmation");
                        pending.put("independence_after_switch",
                                independenceNote(candidate, implementerVendor));
                        report.put("failover_pending", pending);
                        report.put("vendor_attempts", attempts);
                        report.put("attempts_cost_usd", spent);
                        report.put("resolution", "policy failover.on_quota_exhausted is 'confirm'. "
                                + "Record the choice with `warden approve <run-id> --decision switch`, "
                                + "then re-run with `--continue <run-id>`; or set "
                                + "failover.on_quota_exhausted: auto to let Warden switch unattended.");
                        Path pendingPath = ledger.writeReport("role-" + role, report);
                        ledger.append("role_run", report);
                        return new Outcome(false, "role_failover_requires_confirmation", role,
                                profile.name(), profile.vendor(), resolution.rejected(),
                                pendingPath, report);
                    }
                    // mode 'stop': a candidate exists and is deliberately not used.
                    report.put("failover_declined_by_policy", candidate.name());
                }
                // Last vendor standing, or a policy that forbids the switch. The report has to
                // carry the operator's next move: this is where the run ends and nothing
                // downstream will add it.
                report.put("exhausted_profiles", List.copyOf(exhausted));
                report.put("resolution", candidate != null
                        ? "another profile could fill this role, but failover.on_quota_exhausted is 'stop'"
                        : "every profile able to fill this role reported a spent subscription; "
                          + "add a profile from another vendor, or wait for the quota window "
                          + "named in the vendor message and re-run");
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
     * Which profile could fill this role once the exhausted ones are removed, or null when
     * none can. Asked before continuing so that the last vendor's failure is reported as its
     * own outcome rather than as an unresolvable role, and so that an operator being asked to
     * confirm a switch is told who would take over.
     */
    private Profile failoverCandidate(String role, UserConfig user, String implementerVendor,
                                      long rotation) {
        try {
            return new RoleResolver().resolve(role, user.policy(), user.profiles(),
                    implementerVendor, rotation, this::available, exhausted).selected();
        } catch (RuntimeException none) {
            return null;
        }
    }

    private static Map<String, Object> failoverEvent(String role, Profile from, Profile to,
                                                     RoleExecutor.Result result, String authorizedBy) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("role", role);
        event.put("from_profile", from.name());
        event.put("from_vendor", from.vendor());
        event.put("to_profile", to.name());
        event.put("to_vendor", to.vendor());
        event.put("cause", "role_quota_exhausted");
        event.put("authorized_by", authorizedBy);
        event.put("quota", result.evidence().getOrDefault("quota", Map.of()));
        return event;
    }

    /**
     * Two vendors and one spent subscription leaves one vendor. The resolver already refuses
     * a reviewer sharing the implementer's vendor, so a switch cannot silently produce
     * self-review — but the operator confirming it should be told the roster is about to get
     * thin, before the next role fails to resolve.
     */
    private static String independenceNote(Profile candidate, String implementerVendor) {
        if (implementerVendor == null) {
            return "no implementer has run yet in this workflow, so independence is unaffected";
        }
        if (candidate.vendor().equals(implementerVendor)) {
            return "the candidate shares the implementer's vendor (" + implementerVendor
                    + "); a role that requires an independent vendor will refuse it";
        }
        return "the candidate is a different vendor from the implementer (" + implementerVendor
                + "), so independence is preserved";
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
        entry.put("runner", profile.runner());
        Object reportedModel = result.evidence().get("model_reported");
        Object model = reportedModel != null ? reportedModel
                : result.evidence().getOrDefault("model", profile.model());
        if (model != null) entry.put("model", model);
        entry.put("ok", result.ok());
        entry.put("code", result.code());
        entry.put("duration_millis", result.duration().toMillis());
        Object cost = result.evidence().get("cost_usd");
        if (cost != null) entry.put("cost_usd", cost);
        Object tokens = result.evidence().get("tokens");
        if (tokens != null) entry.put("tokens", tokens);
        Object raw = result.evidence().get("raw_stdout");
        if (raw != null) entry.put("raw_stdout", raw);
        Object quota = result.evidence().get("quota");
        if (quota != null) entry.put("quota", quota);
        if (inheritsUnfinishedWork) entry.put("inherited_unfinished_work", true);
        return entry;
    }

    /**
     * Whether this prompt has to be told about the browser scenarios after the fact.
     *
     * The scenarios are half the definition of done — for a project with no check command
     * they are all of it — and both the reviewer and the visual_qa prompts have always been
     * given them. The implementer, the one role that can actually satisfy them, was not:
     * measured on a live run, where the contract asked for a `data-testid` the harness needs
     * and the implementer's prompt never mentioned it. It would have been found by the
     * browser stage and sent back as a fix round, paying a vendor to learn something the
     * contract already said.
     *
     * The shipped template now names {{visual_scenarios}}. This covers the templates already
     * sitting in an operator's ~/.warden — `warden setup` never overwrites them, and no
     * operator ever meant to hide the definition of done from the role expected to meet it.
     */
    private static boolean backfillNeeded(String role, TaskSpec.ResolvedTask task, String template) {
        return "implementer".equals(role)
                && task.visualQa().required()
                && !task.visualQa().scenarios().isEmpty()
                && !PromptRenderer.uses(template, "visual_scenarios");
    }

    private static String browserScenarioSection(String scenarios) {
        return """

                ## Browser scenarios that must also pass

                A headless browser runs these against the app after the acceptance commands.
                They are part of the definition of done, not a suggestion: a matcher that finds
                nothing fails the run and comes back to you as a fix round.

                """ + scenarios + "\n";
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
        values.put("vision_note", visionNote(profile, attachments));
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

    /**
     * How the pixels actually reach this vendor, in the words the prompt needs.
     *
     * The two deliveries ask for different behaviour from the model, and getting it wrong is
     * silent: a `workspace_file` profile told the images are "attached" will answer about
     * filenames, and sound just as confident doing it.
     */
    private static String visionNote(Profile profile, List<Path> attachments) {
        boolean any = attachments != null && !attachments.isEmpty();
        if (!any) return "No screenshot reached this run. Say so and return status: \"aborted\".";
        String delivery = profile.vision() == null ? null : profile.vision().delivery();
        if ("cli_attachment".equals(delivery)) {
            return "The images below are attached to this message as image data. Look at them.";
        }
        if ("workspace_file".equals(delivery)) {
            return "The images below are absolute paths in this workspace, not attachments. "
                    + "**Open every one with your own image-reading tool before you answer.** "
                    + "If your tools cannot open an image, say so in `summary` and return "
                    + "status: \"aborted\" — a verdict on files you did not open is worth "
                    + "less than an honest refusal, and Warden checks for exactly that.";
        }
        return "The images below are paths. This profile declares no vision capability, so "
                + "whether you can see them at all is unproven; if you cannot, say so and "
                + "return status: \"aborted\".";
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

    /**
     * What actually leaves this process for a runner that starts none.
     *
     * `command_preview` names an argv, and a local profile has no argv to name. Publishing
     * `["ollama"]` as the dispatch for an HTTP POST would put a process that was never
     * started into the report of every local role, dry runs included — the same class of
     * claim the adapter itself was added to stop making.
     */
    private static Map<String, Object> dispatchPreview(Profile profile) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("method", "POST");
        preview.put("endpoint", profile.endpoint());
        preview.put("model", profile.model());
        return preview;
    }

    /**
     * The beat and the card learn the name here, from the resolution that is about to
     * dispatch. A failure to tell anyone is dropped: this is a retelling, not evidence.
     */
    private void publish(Profile profile) {
        if (profile == null) return;
        try {
            occupied.by(profile.name(), profile.vendor());
        } catch (RuntimeException | Error notOurProblem) {
            // Naming the vendor is not a reason to stop the run.
        }
    }

    /** A vendor whose executable is absent is skipped, never attempted mid-loop. */
    private boolean available(Profile profile) {
        // A local profile has no process to find: the model is already listening on HTTP.
        // Probing `command` would skip every local profile whose command is a label.
        if ("local".equals(profile.runner())) return true;
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
