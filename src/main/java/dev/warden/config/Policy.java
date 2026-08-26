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
 */
public record Policy(Map<String, RoleSpec> roles, Set<String> reviewRequiredForRisk) {

    public record RoleSpec(String role, List<String> profiles, String strategy,
                           boolean requireIndependentVendor) {}

    public static final Set<String> STRATEGIES = Set.of("rotate", "first");

    private static final Set<String> TOP_LEVEL = Set.of("version", "roles", "review");
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

        root.throwIfAny();
        return new Policy(roles, Set.copyOf(requiredForRisk));
    }

    public boolean reviewRequired(String risk) {
        return reviewRequiredForRisk.contains(risk);
    }
}
