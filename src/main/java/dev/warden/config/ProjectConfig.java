package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `.warden/project.yaml` — the only file a project must write.
 *
 * It says what "done" means here and nothing about vendors, because a vendor is a property
 * of whoever is running the tool, not of the repository. The same project config works for a
 * colleague with entirely different subscriptions.
 */
public record ProjectConfig(
        String project,
        String baseRef,
        Map<String, List<String>> checks,
        Map<String, List<String>> scopes,
        String defaultChecks,
        String defaultRisk,
        long defaultMaxFixAttempts,
        long defaultTimeoutMinutes) {

    public static final Set<String> RISK_LEVELS = Set.of("low", "medium", "high");

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "project", "base_ref", "checks", "scopes", "defaults");
    private static final Set<String> DEFAULTS = Set.of(
            "checks", "risk", "max_fix_attempts", "timeout_minutes");

    public static ProjectConfig parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        String project = root.requireString("project");
        String baseRef = root.optString("base_ref", "origin/main");
        if (!RepoPath.isSafeRef(baseRef)) {
            root.collector().add("base_ref is not a safe git revision name: " + baseRef);
        }

        Map<String, List<String>> checks = root.namedStringLists("checks");
        if (checks.isEmpty()) {
            root.collector().add("checks must declare at least one named command set — "
                    + "a project with no executable definition of 'done' cannot be gated");
        }

        Map<String, List<String>> scopes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : root.namedStringLists("scopes").entrySet()) {
            scopes.put(entry.getKey(), normalizePaths(root, "scopes." + entry.getKey(), entry.getValue()));
        }

        Values defaults = root.optMap("defaults").rejectUnknownKeys(DEFAULTS);
        String defaultChecks = defaults.optString("checks", checks.keySet().stream().findFirst().orElse(null));
        if (defaultChecks != null && !checks.containsKey(defaultChecks)) {
            root.collector().add("defaults.checks names '" + defaultChecks + "', which is not defined under checks");
        }
        String defaultRisk = defaults.requireEnum("risk", RISK_LEVELS, "medium");
        long maxFixAttempts = defaults.optInt("max_fix_attempts", 2, 0, 10);
        long timeoutMinutes = defaults.optInt("timeout_minutes", 30, 1, 240);

        root.throwIfAny();
        return new ProjectConfig(project, baseRef, checks, scopes,
                defaultChecks, defaultRisk, maxFixAttempts, timeoutMinutes);
    }

    static List<String> normalizePaths(Values root, String field, List<String> raw) {
        return raw.stream().map(candidate -> {
            String normalized = RepoPath.normalize(candidate);
            if (normalized == null) {
                root.collector().add(field + ": '" + candidate
                        + "' is not a safe repository-relative path");
                return candidate;
            }
            return normalized;
        }).toList();
    }
}
