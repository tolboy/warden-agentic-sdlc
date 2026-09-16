package dev.warden.config;

import dev.warden.json.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned JSON input for {@code warden pilot prepare}.
 *
 * Every command, path, profile name and budget comes from this document. The goal is copied
 * verbatim — including Unicode, quotes and newlines — and is never parsed for shell commands.
 */
public record PilotPrepareSpec(
        String taskId,
        String goal,
        List<String> nonGoals,
        String risk,
        List<String> scope,
        long timeoutMinutes,
        long maxFixAttempts,
        long maxRoleRuns,
        double maxCostUsd,
        String project,
        Path targetRoot,
        String base,
        List<String> baselineCommands,
        List<String> acceptanceCommands,
        String reproductionCommand,
        Path sourceHome,
        String implementerProfile,
        String reviewerProfile) {

    public static final int VERSION = 1;
    public static final String IMPLEMENTER_BUNDLE_NAME = "pilot-implement";
    public static final String REVIEWER_BUNDLE_NAME = "pilot-review";
    public static final String SCOPE_NAME = "pilot-scope";
    public static final String BASELINE_CHECK_NAME = "pilot-baseline";
    public static final String ACCEPTANCE_CHECK_NAME = "pilot-acceptance";

    private static final Set<String> TOP_LEVEL =
            Set.of("version", "task", "target", "checks", "reproduction", "profiles");
    private static final Set<String> TASK_KEYS = Set.of(
            "id", "goal", "non_goals", "risk", "scope", "timeout_minutes", "max_fix_attempts",
            "budgets");
    private static final Set<String> BUDGET_KEYS = Set.of("max_role_runs", "max_cost_usd");
    private static final Set<String> TARGET_KEYS = Set.of("project", "root", "base");
    private static final Set<String> CHECK_KEYS = Set.of("baseline", "acceptance");
    private static final Set<String> REPRODUCTION_KEYS = Set.of("command");
    private static final Set<String> PROFILE_KEYS = Set.of("source_home", "implementer", "reviewer");

    public static PilotPrepareSpec parse(String jsonText, String source) {
        Values root = Values.of(Json.parse(jsonText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(VERSION);

        if (!root.has("task")) root.collector().add("task is required");
        Values task = root.optMap("task").rejectUnknownKeys(TASK_KEYS);
        String taskId = task.requireString("id");
        if (taskId != null && !RepoPath.isSlug(taskId)) {
            root.collector().add("task.id must be a lowercase slug of at most 80 characters, got '"
                    + taskId + "'");
        }
        String goal = task.requireString("goal");
        List<String> nonGoals = task.optStringList("non_goals", List.of());
        String risk = task.requireEnum("risk", ProjectConfig.RISK_LEVELS, "medium");
        List<String> scope = task.requireStringList("scope");
        List<String> normalizedScope = new ArrayList<>();
        for (String entry : scope) {
            if (RepoPath.WHOLE_REPOSITORY.equals(entry)) {
                normalizedScope.add(RepoPath.WHOLE_REPOSITORY);
                continue;
            }
            String normalized = RepoPath.normalize(entry);
            if (normalized == null) {
                root.collector().add("task.scope entry '" + entry
                        + "' is not a safe repository-relative path");
            } else {
                normalizedScope.add(normalized);
            }
        }
        long timeoutMinutes = task.optInt("timeout_minutes", 30, 1, 240);
        long maxFixAttempts = task.optInt("max_fix_attempts", 1, 0, 10);
        if (task.has("max_fix_attempts") && maxFixAttempts > 1) {
            root.collector().add("task.max_fix_attempts must be 0 or 1 for this one-repair pilot slice, got "
                    + maxFixAttempts);
        }
        Values budgets = task.optMap("budgets").rejectUnknownKeys(BUDGET_KEYS);
        long maxRoleRuns = budgets.optInt("max_role_runs", 4, 1, 50);
        double maxCostUsd = budgets.optDouble("max_cost_usd", 10.0, 0.0, 10000.0);

        if (!root.has("target")) root.collector().add("target is required");
        Values target = root.optMap("target").rejectUnknownKeys(TARGET_KEYS);
        String project = target.requireString("project");
        String targetRootRaw = target.requireString("root");
        String base = target.optString("base", "HEAD");
        if (base != null && !RepoPath.isSafeRef(base)) {
            root.collector().add("target.base is not a safe git revision name: " + base);
        }

        if (!root.has("checks")) root.collector().add("checks is required");
        Values checks = root.optMap("checks").rejectUnknownKeys(CHECK_KEYS);
        if (!checks.has("baseline")) {
            root.collector().add("checks.baseline is required (use [] when there is no green baseline)");
        }
        List<String> baseline = checks.optStringList("baseline", List.of());
        List<String> acceptance = checks.requireStringList("acceptance");
        validateCommands(root, "checks.baseline", baseline);
        validateCommands(root, "checks.acceptance", acceptance);
        Set<String> overlap = new LinkedHashSet<>(baseline);
        overlap.retainAll(new LinkedHashSet<>(acceptance));
        if (!overlap.isEmpty()) {
            root.collector().add("a command listed under checks.acceptance cannot also be a green "
                    + "baseline command; move the suite being repaired out of checks.baseline: "
                    + overlap);
        }

        String reproduction = null;
        if (root.has("reproduction")) {
            Values reproductionNode = root.optMap("reproduction").rejectUnknownKeys(REPRODUCTION_KEYS);
            reproduction = reproductionNode.requireString("command");
            if (reproduction != null) {
                validateCommands(root, "reproduction.command", List.of(reproduction));
                if (!acceptance.contains(reproduction)) {
                    root.collector().add("reproduction.command must be one of checks.acceptance");
                }
            }
        }

        if (!root.has("profiles")) root.collector().add("profiles is required");
        Values profiles = root.optMap("profiles").rejectUnknownKeys(PROFILE_KEYS);
        String sourceHomeRaw = profiles.requireString("source_home");
        String implementer = profiles.requireString("implementer");
        String reviewer = profiles.requireString("reviewer");
        if (implementer != null && implementer.equals(reviewer)) {
            root.collector().add("profiles.implementer and profiles.reviewer must name two different source profiles");
        }

        root.throwIfAny();
        return new PilotPrepareSpec(
                taskId,
                goal,
                List.copyOf(nonGoals),
                risk,
                List.copyOf(normalizedScope),
                timeoutMinutes,
                maxFixAttempts,
                maxRoleRuns,
                maxCostUsd,
                project,
                expandUserPath(targetRootRaw),
                base,
                List.copyOf(baseline),
                List.copyOf(acceptance),
                reproduction,
                expandUserPath(sourceHomeRaw),
                implementer,
                reviewer);
    }

    private static void validateCommands(Values root, String field, List<String> commands) {
        for (int index = 0; index < commands.size(); index++) {
            String command = commands.get(index);
            if (command.length() > 2000 || command.contains("\n") || command.contains("\r")) {
                root.collector().add(field + "[" + index
                        + "] must be a single line of at most 2000 characters");
            }
        }
    }

    /** {@code ~} and {@code ~/...} mean the process user home; nothing else is expanded. */
    public static Path expandUserPath(String raw) {
        if (raw.equals("~")) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }
}
