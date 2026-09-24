package dev.warden.dashboard;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.Workflow;
import dev.warden.role.RoleResolver;
import dev.warden.run.TaskLoop;
import dev.warden.yaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings a run would start with, as the panel shows them: who can fill each stage and
 * why each other candidate cannot, the limits in force, and where every value comes from.
 *
 * Read-only. Every verdict on a candidate is the resolver's own ({@link RoleResolver#candidates});
 * this class decides nothing about eligibility, it only lays the answers out by stage. The
 * source of a value is one of three levels — {@code personal} (the config home: policy and
 * profiles), {@code project} ({@code .warden/project.yaml}) and {@code task} (a task file) —
 * or {@code default} when nothing declares it and Warden's built-in value applies.
 *
 * The stage table is the policy's answer, before any task: {@code stages_basis} says so. A
 * task can pin a profile in {@code use:} or allow a same-vendor peer in
 * {@code review_assurance}, and what a run of that task would dispatch is its own
 * {@code would_run}, resolved by {@link TaskLoop#dispatchPreview} — the preflight's calls.
 */
public final class ConfigView {

    private ConfigView() {}

    public static Map<String, Object> of(Path project, UserConfig user, RoleResolver.Availability availability)
            throws IOException {
        // One probe per program, however many profiles name it: the panel asks on every
        // refresh, and each probe is a process.
        Map<String, Boolean> found = new LinkedHashMap<>();
        RoleResolver.Availability cached = profile -> found.computeIfAbsent(
                profile.runner() + "|" + profile.command(), ignored -> availability.available(profile));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("config_home", user.home().toString());
        view.put("project", project.toAbsolutePath().normalize().toString());
        view.put("problems", user.problems());
        Policy policy = user.policy();
        Workflow workflow = policy == null ? Workflow.builtIn() : policy.workflow();
        view.put("policy_file", user.home().resolve("policy.yaml").toString());
        view.put("policy_loaded", policy != null);
        view.put("workflow_source", policy != null && policy.workflowDeclared() ? "personal" : "default");
        view.put("stages_basis", "policy");
        view.put("stages", stages(workflow, user, cached));
        view.put("limits", limits(user));
        view.put("tasks", tasks(project, user, cached));
        return view;
    }

    // ------------------------------------------------------------------ stages

    private static List<Map<String, Object>> stages(Workflow workflow, UserConfig user,
                                                    RoleResolver.Availability availability) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Policy policy = user.policy();
        // The writer the chain would dispatch, so a reader is judged as the preflight judges it:
        // against that writer's vendor, not against nobody.
        Map<String, Object> writer = wouldWrite(workflow, user, availability);
        for (Workflow.Stage stage : workflow.stages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("kind", switch (stage.kind()) {
                case ROLE -> "role";
                case MACHINE_GATES -> "gates";
                case VISUAL_HARNESS -> "browser";
            });
            row.put("when", stage.when());
            rows.add(row);
            if (stage.kind() != Workflow.Kind.ROLE) {
                row.put("note", "no profile fills this stage; --use, --effort and --host are refused for it");
                continue;
            }
            row.put("role", stage.role());
            Policy.RoleSpec spec = policy == null ? null : policy.roles().get(stage.role());
            if (spec == null) {
                row.put("unresolved", "role_not_configured");
                continue;
            }
            row.put("source", "personal");
            row.put("strategy", spec.strategy());
            row.put("require_independent_vendor", spec.requireIndependentVendor());
            if (spec.peer() != null) row.put("same_vendor_peer", spec.peer().label());
            boolean reads = stage.onFindings() != null;
            RoleResolver resolver = new RoleResolver();
            List<RoleResolver.Candidate> alone = resolver.candidates(stage.role(), policy, user.profiles(),
                    RoleResolver.Writers.NONE, availability, Set.of());
            RoleResolver.Writers judgedBy = reads && writer != null
                    ? new RoleResolver.Writers(Set.of(String.valueOf(writer.get("vendor"))),
                            Set.of(String.valueOf(writer.get("profile"))), true, false)
                    : null;
            List<RoleResolver.Candidate> afterWriter = judgedBy == null ? null
                    : resolver.candidates(stage.role(), policy, user.profiles(), judgedBy, availability, Set.of());
            String chosen = null;
            try {
                chosen = resolver.resolve(stage.role(), policy, user.profiles(),
                        judgedBy == null ? RoleResolver.Writers.NONE : judgedBy,
                        workflow.rotationPositionOf(stage), availability, Set.of()).selected().name();
            } catch (RoleResolver.Unresolvable nobody) {
                row.put("unresolved", "no_eligible_profile");
            }
            if (afterWriter != null) row.put("judged_against", writer);
            row.put("would_run", chosen);
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (int index = 0; index < alone.size(); index++) {
                RoleResolver.Candidate verdict = alone.get(index);
                Map<String, Object> candidate = candidate(verdict.profile(), user);
                candidate.put("eligible", verdict.eligible());
                candidate.put("rejected", verdict.rejected());
                if (afterWriter != null) {
                    RoleResolver.Candidate judged = afterWriter.get(index);
                    candidate.put("eligible_after_writer", judged.eligible());
                    candidate.put("rejected_after_writer", judged.rejected());
                    candidate.put("assurance", judged.assurance());
                }
                candidate.put("would_run", verdict.profile().equals(chosen));
                candidates.add(candidate);
            }
            row.put("candidates", candidates);
        }
        return rows;
    }

    /** The first role stage's implementer as the resolver would pick it, or null. */
    private static Map<String, Object> wouldWrite(Workflow workflow, UserConfig user,
                                                  RoleResolver.Availability availability) {
        if (user.policy() == null) return null;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE || !"implementer".equals(stage.role())) continue;
            if (!user.policy().roles().containsKey("implementer")) return null;
            try {
                Profile writer = new RoleResolver().resolve("implementer", user.policy(), user.profiles(),
                        RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage), availability,
                        Set.of()).selected();
                Map<String, Object> named = new LinkedHashMap<>();
                named.put("stage", stage.name());
                named.put("profile", writer.name());
                named.put("vendor", writer.vendor());
                return named;
            } catch (RoleResolver.Unresolvable nobody) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> candidate(String name, UserConfig user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profile", name);
        Profile profile = user.profiles().get(name);
        if (profile == null) return row;
        row.put("vendor", profile.vendor());
        row.put("model", profile.model());
        row.put("effort", profile.effort());
        row.put("runner", profile.runner());
        row.put("read_only", profile.readOnly());
        row.put("verified", profile.verified());
        row.put("verify_probe", profile.verificationProbe() != null);
        row.put("max_cost_usd", profile.maxCostUsd());
        row.put("source", "personal");
        row.put("file", user.home().resolve("profiles").resolve(name + ".yaml").toString());
        // Orca workers report no price and no tokens, and an Orca profile with no model runs
        // whatever the agent's TUI defaults to. Both are said, not left as blanks.
        if ("orca".equals(profile.runner())) {
            row.put("cost_reported", false);
            if (profile.model() == null) row.put("model_note", "agent_default");
        }
        return row;
    }

    // ------------------------------------------------------------------ limits

    private static List<Map<String, Object>> limits(UserConfig user) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Policy policy = user.policy();
        if (policy == null) return rows;
        Map<String, Object> raw = raw(user.home().resolve("policy.yaml"));
        limit(rows, raw, "failover.on_quota_exhausted", policy.failoverMode());
        limit(rows, raw, "review.required_for_risk", List.copyOf(policy.reviewRequiredForRisk()));
        limit(rows, raw, "review.repair_severities", List.copyOf(policy.repairSeverities()));
        limit(rows, raw, "review.contract_gaps", policy.contractGaps());
        limit(rows, raw, "budget.repair_reserve", policy.repairReserve());
        limit(rows, raw, "retry.rate_limited", policy.rateLimitRetry().maxAttempts() == 0 ? "off"
                : policy.rateLimitRetry().maxAttempts() + " attempt(s), "
                        + policy.rateLimitRetry().backoffSeconds() + " s apart");
        limit(rows, raw, "escalation", policy.escalation() == null ? null : policy.escalation().toMap());
        return rows;
    }

    private static void limit(List<Map<String, Object>> rows, Map<String, Object> raw, String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("value", value);
        row.put("source", present(raw, key) ? "personal" : "default");
        rows.add(row);
    }

    // ------------------------------------------------------------------ tasks

    private static List<Map<String, Object>> tasks(Path project, UserConfig user,
                                                   RoleResolver.Availability availability) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Path tasks = project.toAbsolutePath().normalize().resolve(".warden/tasks");
        if (!Files.isDirectory(tasks, LinkOption.NOFOLLOW_LINKS)) return rows;
        Map<String, Object> projectRaw = raw(project.resolve(".warden/project.yaml"));
        List<Path> files;
        try (var listing = Files.list(tasks)) {
            files = listing.filter(file -> file.getFileName().toString().endsWith(".yaml")
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        } catch (IOException unreadable) {
            return rows;
        }
        for (Path file : files) {
            String id = file.getFileName().toString().replaceFirst("\\.yaml$", "");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", id);
            row.put("file", file.toString());
            rows.add(row);
            try {
                ConfigLoader.Loaded loaded = new ConfigLoader().load(project, id);
                TaskSpec.ResolvedTask task = loaded.resolved();
                Workflow workflow = user.policy() == null ? Workflow.builtIn() : user.policy().workflow();
                if (task.visualQa().agentEvidence()) workflow = workflow.forAgentEvidence();
                Map<String, Object> raw = raw(file);
                row.put("risk", valued(task.risk(), sourceOf(raw, "risk", projectRaw, "defaults.risk")));
                row.put("max_role_runs", valued(task.budget().maxRoleRuns(),
                        sourceOf(raw, "budgets.max_role_runs", null, null)));
                row.put("max_cost_usd", valued(task.budget().maxCostUsd(),
                        sourceOf(raw, "budgets.max_cost_usd", null, null)));
                row.put("cost_cap", valued(task.budget().costCap(), sourceOf(raw, "budgets.cost_cap", null, null)));
                row.put("max_elapsed_minutes", valued(task.budget().maxElapsedMinutes(),
                        sourceOf(raw, "budgets.max_elapsed_minutes", null, null)));
                row.put("gate_ttl_hours", valued(task.budget().gateTtlHours(),
                        sourceOf(raw, "budgets.gate_ttl_hours", null, null)));
                row.put("max_fix_attempts", valued(task.maxFixAttempts(),
                        sourceOf(raw, "max_fix_attempts", projectRaw, "defaults.max_fix_attempts")));
                row.put("timeout_minutes", valued(task.timeoutMinutes(),
                        sourceOf(raw, "timeout_minutes", projectRaw, "defaults.timeout_minutes")));
                row.put("visual_qa", valued(task.visualQa().required()
                        ? "required · " + task.visualQa().evidence() : "off", sourceOf(raw, "visual_qa", null, null)));
                row.put("review_assurance", valued(task.reviewAssurance(),
                        sourceOf(raw, "review_assurance", null, null)));
                List<Map<String, Object>> dispatch = TaskLoop.dispatchPreview(project, loaded, user, availability);
                row.put("would_run", dispatch);
                row.put("overlay", overlay(loaded.task().use().toMap(), workflow, dispatch));
            } catch (Exception invalid) {
                row.put("problem", String.valueOf(invalid.getMessage()));
            }
        }
        return rows;
    }

    /**
     * The task's {@code use:} rows, each checked the way the preflight checks it: a stage the
     * workflow does not have, and a stage no profile fills, are both refused before dispatch;
     * so is an effort or a host the chosen profile cannot deliver, and a pin nobody can fill
     * stops the run at that stage. The last two come from {@code dispatch}, the task's own
     * {@link TaskLoop#dispatchPreview}, so the panel and the preflight give one answer.
     */
    private static List<Map<String, Object>> overlay(Map<String, Object> use, Workflow workflow,
                                                     List<Map<String, Object>> dispatch) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> kind : use.entrySet()) {
            if (!(kind.getValue() instanceof Map<?, ?> byStage)) continue;
            for (Map.Entry<?, ?> entry : byStage.entrySet()) {
                String stage = String.valueOf(entry.getKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage);
                row.put("what", kind.getKey());
                row.put("value", String.valueOf(entry.getValue()));
                Workflow.Stage named = workflow.stages().stream()
                        .filter(candidate -> candidate.name().equals(stage)).findFirst().orElse(null);
                row.put("refused", named == null ? "run_override_unknown_stage"
                        : named.kind() != Workflow.Kind.ROLE ? "run_override_not_a_role_stage" : null);
                Map<String, Object> dispatched = dispatch.stream()
                        .filter(candidate -> stage.equals(candidate.get("stage"))).findFirst().orElse(null);
                if (row.get("refused") == null && dispatched != null) {
                    if (dispatched.get("skipped") != null) {
                        row.put("skipped", dispatched.get("skipped"));
                    } else if (dispatched.get("refused") != null) {
                        row.put("refused", dispatched.get("refused"));
                        row.put("message", dispatched.get("message"));
                    } else if (dispatched.get("unresolved") instanceof Map<?, ?> reasons) {
                        Object pinned = "profile".equals(kind.getKey()) ? reasons.get(String.valueOf(entry.getValue())) : null;
                        row.put("refused", pinned != null ? String.valueOf(pinned) : "no_eligible_profile");
                        row.put("message", "nobody can fill stage '" + stage + "': " + reasons);
                    }
                }
                row.put("source", "task");
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> valued(Object value, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("value", value);
        row.put("source", source);
        return row;
    }

    private static String sourceOf(Map<String, Object> task, String key,
                                   Map<String, Object> project, String projectKey) {
        if (present(task, key)) return "task";
        if (project != null && present(project, projectKey)) return "project";
        return "default";
    }

    /** The file as a plain mapping, or an empty one when it is absent or does not parse. */
    private static Map<String, Object> raw(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
            return Yaml.parseMapping(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            return Map.of();
        }
    }

    /** Whether a dotted key is spelled out in the mapping, whatever its value. */
    static boolean present(Map<String, Object> raw, String dotted) {
        Object current = raw;
        for (String part : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return false;
            current = map.get(part);
        }
        return true;
    }
}
