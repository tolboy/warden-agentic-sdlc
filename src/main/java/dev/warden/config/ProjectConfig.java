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
        List<String> setup,
        Land land,
        String defaultChecks,
        String defaultBaselineChecks,
        String defaultRisk,
        long defaultMaxFixAttempts,
        long defaultTimeoutMinutes) {

    public static final Set<String> RISK_LEVELS = Set.of("low", "medium", "high");

    /**
     * How this project takes a change once a human has accepted it.
     *
     * Every field is optional and nothing here is guessed. Warden knows git, because git is
     * what merge-base, blast radius and the content fingerprint are built on; it does not
     * know your forge. `gh pr create` is GitHub's, `glab mr create` is GitLab's, `tea` is
     * Gitea's, and a repository with no forge at all is a perfectly ordinary thing. So the
     * command that opens the request is written here, as argv rather than a shell line —
     * the same reason profiles spell their vendor flags out, and the same reason a title
     * containing a quote does not become somebody's debugging afternoon.
     *
     * Placeholders: {{remote}} {{branch}} {{base}} {{title}} {{body_file}}.
     *
     * @param remote      the remote to push to; inferred when the repository has exactly one
     * @param base        the branch a request targets; asked of the remote when absent
     * @param pullRequest argv of the command that opens the request, or empty
     */
    public record Land(String remote, String base, List<String> pullRequest) {
        public Land {
            pullRequest = List.copyOf(pullRequest);
        }

        public boolean opensRequests() { return !pullRequest.isEmpty(); }
    }

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "project", "base_ref", "checks", "scopes", "setup", "defaults", "land");
    private static final Set<String> LAND_KEYS = Set.of("remote", "base", "pull_request", "note");
    private static final Set<String> DEFAULTS = Set.of(
            "checks", "baseline_checks", "risk", "max_fix_attempts", "timeout_minutes");

    public static ProjectConfig parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        String project = root.requireString("project");
        String baseRef = root.optString("base_ref", "origin/main");
        if (!RepoPath.isSafeRef(baseRef)) {
            root.collector().add("base_ref is not a safe git revision name: " + baseRef);
        }

        // An explicitly empty set is legal and means "this project has no command to run
        // yet" — a from-scratch project whose definition of done is its browser scenarios.
        // A missing `checks:` block is still refused: that is an omission, not a statement.
        Map<String, List<String>> checks = root.namedStringLists("checks", true);
        if (checks.isEmpty()) {
            root.collector().add("checks must declare at least one named command set — "
                    + "a project with no executable definition of 'done' cannot be gated");
        }

        Map<String, List<String>> scopes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : root.namedStringLists("scopes").entrySet()) {
            List<String> declared = entry.getValue();
            if (declared.contains(RepoPath.WHOLE_REPOSITORY)) {
                // All or a boundary, never both: `[src, <repository>]` reads like a narrowing
                // and means the opposite, so it is refused rather than interpreted.
                if (declared.size() != 1) {
                    root.collector().add("scopes." + entry.getKey() + ": "
                            + RepoPath.WHOLE_REPOSITORY + " cannot be combined with a path; it "
                            + "already means every path in the repository");
                    continue;
                }
                scopes.put(entry.getKey(), List.of(RepoPath.WHOLE_REPOSITORY));
                continue;
            }
            scopes.put(entry.getKey(), normalizePaths(root, "scopes." + entry.getKey(), declared));
        }

        Values defaults = root.optMap("defaults").rejectUnknownKeys(DEFAULTS);
        String defaultChecks = defaults.optString("checks", checks.keySet().stream().findFirst().orElse(null));
        if (defaultChecks != null && !checks.containsKey(defaultChecks)) {
            root.collector().add("defaults.checks names '" + defaultChecks + "', which is not defined under checks");
        }
        // Optional for backwards compatibility and for a greenfield project that has no
        // pre-existing suite yet. When declared, this is the project-health set Warden runs
        // before it pays a vendor; TaskSpec also folds it into the later acceptance gate so
        // an agent cannot break a test that was green at dispatch time.
        String defaultBaselineChecks = defaults.optString("baseline_checks", null);
        if (defaultBaselineChecks != null && !checks.containsKey(defaultBaselineChecks)) {
            root.collector().add("defaults.baseline_checks names '" + defaultBaselineChecks
                    + "', which is not defined under checks");
        } else if (defaultBaselineChecks != null && checks.get(defaultBaselineChecks).isEmpty()) {
            root.collector().add("defaults.baseline_checks names an empty command set; omit it "
                    + "when this project has no baseline check yet");
        }
        String defaultRisk = defaults.requireEnum("risk", RISK_LEVELS, "medium");
        long maxFixAttempts = defaults.optInt("max_fix_attempts", 2, 0, 10);
        long timeoutMinutes = defaults.optInt("timeout_minutes", 30, 1, 240);

        // What has to happen in a checkout before any of `checks` can run. Empty for most
        // projects and load-bearing for the rest: a `git worktree` of an npm project has no
        // node_modules, so `npm run check` there fails for a reason that has nothing to do
        // with the task. Orca runs its own setup when Orca made the worktree, so this is read
        // only when Warden made it.
        List<String> setup = root.optStringList("setup", List.of());

        Values landing = root.optMap("land").rejectUnknownKeys(LAND_KEYS);
        List<String> pullRequest = landing.optStringList("pull_request", List.of());
        Land land = new Land(landing.optString("remote", null),
                landing.optString("base", null), pullRequest);

        root.throwIfAny();
        return new ProjectConfig(project, baseRef, checks, scopes, setup, land,
                defaultChecks, defaultBaselineChecks, defaultRisk, maxFixAttempts, timeoutMinutes);
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
