package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `~/.warden/policy.yaml` — which profiles may fill which role.
 *
 * `requireIndependentVendor` is a hard constraint. A reviewer sharing a vendor with the
 * implementer shares its blind spots, which is the only defensible reason to run more than
 * one vendor at all. When it cannot be satisfied the resolver fails rather than quietly
 * handing the code back to its own author for review.
 *
 * The optional `workflow:` block declares the order those roles run in and the conditions
 * under which each runs at all — see {@link Workflow}. Omitting it keeps the built-in chain.
 *
 * `failover.on_quota_exhausted` decides whether a spent subscription may be routed around
 * without asking. It defaults to `confirm`, not `auto`: swapping vendors mid-run changes who
 * wrote the code and can leave one vendor reviewing its own work.
 */
public record Policy(Map<String, RoleSpec> roles, Set<String> reviewRequiredForRisk,
                     Workflow workflow, boolean workflowDeclared, String failoverMode,
                     String repairReserve) {

    public record RoleSpec(String role, List<String> profiles, String strategy,
                           boolean requireIndependentVendor) {}

    public static final Set<String> STRATEGIES = Set.of("rotate", "first");

    private static final Set<String> TOP_LEVEL =
            Set.of("version", "roles", "review", "workflow", "failover", "budget");
    private static final Set<String> FAILOVER_KEYS = Set.of("on_quota_exhausted", "note");
    private static final Set<String> BUDGET_KEYS = Set.of("repair_reserve", "note");

    /**
     * How much of the remaining chain a repair round has to be able to pay for before it is
     * allowed to start.
     *
     * `full` reserves the repair and every stage still owed, so a run either finishes or does
     * not begin the attempt. `partial` reserves the repair and at least one stage that will
     * judge what it produces, and lets the run make paid progress it cannot complete —
     * finishing a review whose verdict a continuation reuses for nothing, at the price of
     * possibly buying a regression instead.
     *
     * `full` is the default because the trade is the operator's to make and the conservative
     * side of it is the one that cannot spend money on an outcome nobody asked for. Neither
     * mode is silent: both record the arithmetic in `budget_plan` before the first dispatch.
     */
    public static final Set<String> REPAIR_RESERVES = Set.of("full", "partial");

    /**
     * What happens when the vendor filling a role reports a spent subscription and another
     * eligible profile exists.
     *
     * `confirm` is the default because a failover is a change of who is doing the work, and
     * on a two-vendor roster it can quietly cost the run its independent reviewer: the last
     * vendor standing would be reviewing its own output. That is a judgement about the value
     * of the result, which is the operator's to make.
     */
    public static final Set<String> FAILOVER_MODES = Set.of("confirm", "auto", "stop");
    private static final Set<String> ROLE_KEYS = Set.of("profiles", "strategy", "require_independent_vendor");
    private static final Set<String> REVIEW_KEYS = Set.of("required_for_risk", "note");

    public static Policy parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        Values rolesNode = root.optMap("roles");
        Map<String, RoleSpec> roles = new LinkedHashMap<>();
        for (String roleName : rolesNode.keys()) {
            if (!Profile.ROLES.contains(roleName)) {
                root.collector().add("roles." + roleName + " is not a known role; expected one of " + Profile.ROLES);
                continue;
            }
            Values spec = rolesNode.optMap(roleName).rejectUnknownKeys(ROLE_KEYS);
            List<String> profiles = spec.requireStringList("profiles");
            String strategy = spec.requireEnum("strategy", STRATEGIES, "rotate");
            boolean independent = spec.optBool("require_independent_vendor", roleName.equals("reviewer"));
            roles.put(roleName, new RoleSpec(roleName, profiles, strategy, independent));
        }
        if (roles.isEmpty()) {
            root.collector().add("roles must declare at least one role");
        }

        Values review = root.optMap("review").rejectUnknownKeys(REVIEW_KEYS);
        List<String> requiredForRisk = review.optStringList("required_for_risk", List.of("medium", "high"));
        for (String risk : requiredForRisk) {
            if (!ProjectConfig.RISK_LEVELS.contains(risk)) {
                root.collector().add("review.required_for_risk contains '" + risk
                        + "', expected one of " + ProjectConfig.RISK_LEVELS);
            }
        }

        // A policy that says nothing about order gets the documented loop. Declaring the
        // block replaces the chain wholesale rather than patching it: a workflow assembled
        // from a default plus overrides is one nobody can read off the file in front of them.
        Values failover = root.optMap("failover").rejectUnknownKeys(FAILOVER_KEYS);
        String failoverMode = failover.requireEnum("on_quota_exhausted", FAILOVER_MODES, "confirm");

        Values budget = root.optMap("budget").rejectUnknownKeys(BUDGET_KEYS);
        String repairReserve = budget.requireEnum("repair_reserve", REPAIR_RESERVES, "full");

        boolean workflowDeclared = root.has("workflow");
        Workflow workflow = workflowDeclared ? Workflow.parse(root, "workflow") : Workflow.builtIn();

        root.throwIfAny();
        return new Policy(roles, Set.copyOf(requiredForRisk), workflow, workflowDeclared,
                failoverMode, repairReserve);
    }

    public boolean reviewRequired(String risk) {
        return reviewRequiredForRisk.contains(risk);
    }
}
