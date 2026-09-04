package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * `.warden/tasks/<id>.yaml` — one unit of work.
 *
 * A task must resolve to at least one executable acceptance command and at least one scope
 * path. Both refusals are deliberate: every industrial deployment of coding agents that
 * worked did so on mechanically verifiable work, and an unbounded scope means a diff nobody
 * can review. This is the cheapest possible place to block, long before any model runs.
 */
public record TaskSpec(
        String id,
        String goal,
        List<String> nonGoals,
        String risk,
        Values.Selector scope,
        Values.Selector checks,
        List<String> acceptance,
        Authority authority,
        VisualQa visualQa,
        Budget budget,
        Long maxFixAttempts,
        Long timeoutMinutes) {

    public record Authority(boolean workspaceWrite, boolean network, boolean land) {}
    public record VisualQa(boolean required, List<String> scenarios, String start, String url) {}
    public record Budget(long maxRoleRuns, double maxCostUsd) {}

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "id", "goal", "non_goals", "risk", "scope", "checks", "acceptance",
            "authority", "visual_qa", "budgets", "max_fix_attempts", "timeout_minutes");
    private static final Set<String> AUTHORITY_KEYS = Set.of("workspace_write", "network", "land");
    private static final Set<String> VISUAL_KEYS = Set.of("required", "scenarios", "start", "url");
    private static final Set<String> BUDGET_KEYS = Set.of("max_role_runs", "max_cost_usd");

    public static TaskSpec parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        String id = root.requireString("id");
        if (id != null && !RepoPath.isSlug(id)) {
            root.collector().add("id must be a lowercase slug of at most 80 characters, got '" + id + "'");
        }
        String goal = root.requireString("goal");
        List<String> nonGoals = root.optStringList("non_goals", List.of());
        String risk = root.requireEnum("risk", ProjectConfig.RISK_LEVELS, null);

        Values.Selector scope = root.selector("scope");
        if (scope.isEmpty()) {
            root.collector().add("scope is required: name a scope from project.yaml, "
                    + "or give an explicit list of paths");
        }
        Values.Selector checks = root.selector("checks");
        List<String> acceptance = root.optStringList("acceptance", List.of());

        Values authorityNode = root.optMap("authority").rejectUnknownKeys(AUTHORITY_KEYS);
        Authority authority = new Authority(
                authorityNode.optBool("workspace_write", true),
                authorityNode.optBool("network", false),
                authorityNode.optBool("land", false));
        if (authority.land()) {
            root.collector().add("authority.land must be false: Warden stops before a human and never lands changes");
        }

        Values visualNode = root.optMap("visual_qa").rejectUnknownKeys(VISUAL_KEYS);
        VisualQa visualQa = new VisualQa(
                visualNode.optBool("required", false),
                visualNode.optStringList("scenarios", List.of()),
                visualNode.optString("start", null),
                visualNode.optString("url", null));
        if (visualQa.required() && visualQa.scenarios().isEmpty()) {
            root.collector().add("visual_qa.scenarios must not be empty when visual QA is required");
        }
        // Whether a scenario names its control in a way that survives a rename is a question
        // about the scenario grammar, and the grammar lives in the adapter: VisualQaRunner asks
        // it with `--validate-only` at preflight, before any vendor is paid. A copy of the rule
        // here would be a second grammar written in regexes, and this is the parser every
        // command goes through — `warden land` re-reads the task file, so a rule enforced here
        // would make a run that a person already accepted unlandable until its contract was
        // edited after the fact.

        Values budgetNode = root.optMap("budgets").rejectUnknownKeys(BUDGET_KEYS);
        Budget budget = new Budget(
                budgetNode.optInt("max_role_runs", 6, 1, 50),
                budgetNode.optDouble("max_cost_usd", 20.0, 0.0, 10000.0));

        Long maxFixAttempts = root.has("max_fix_attempts")
                ? root.optInt("max_fix_attempts", 2, 0, 10) : null;
        Long timeoutMinutes = root.has("timeout_minutes")
                ? root.optInt("timeout_minutes", 30, 1, 240) : null;

        root.throwIfAny();
        return new TaskSpec(id, goal, List.copyOf(nonGoals), risk, scope, checks, acceptance,
                authority, visualQa, budget, maxFixAttempts, timeoutMinutes);
    }

    /**
     * Resolve names against the project config into a concrete, self-contained task.
     * After this there are no indirections left: explicit paths, explicit commands.
     */
    public ResolvedTask resolve(ProjectConfig project, String source) {
        ConfigException.Collector collector = new ConfigException.Collector(source);

        Set<String> paths = new LinkedHashSet<>();
        for (String entry : scope.entries()) {
            List<String> named = project.scopes().get(entry);
            if (named != null) {
                paths.addAll(named);
                continue;
            }
            // A bare `scope: name` is only ever a scope name. Falling back to treating it as
            // a path would turn a typo into a blast radius pointing at a directory that does
            // not exist, which fails open: nothing is ever reported out of scope.
            if (scope.bareName()) {
                collector.add("scope names '" + entry + "', which is not defined under scopes in "
                        + "project.yaml. Available: " + project.scopes().keySet()
                        + ". To use an explicit path, write it as a list: scope: [\"" + entry + "\"]");
                continue;
            }
            String normalized = RepoPath.normalize(entry);
            if (normalized == null) {
                collector.add("scope entry '" + entry
                        + "' is neither a scope defined in project.yaml nor a safe repository-relative path");
            } else {
                paths.add(normalized);
            }
        }

        List<String> commands = new ArrayList<>();
        boolean bareChecks = checks.isEmpty() || checks.bareName();
        List<String> requested = checks.isEmpty()
                ? (project.defaultChecks() == null ? List.of() : List.of(project.defaultChecks()))
                : checks.entries();
        for (String entry : requested) {
            List<String> named = project.checks().get(entry);
            if (named != null) { commands.addAll(named); continue; }
            if (bareChecks) {
                collector.add("checks names '" + entry + "', which is not defined under checks in "
                        + "project.yaml. Available: " + project.checks().keySet()
                        + ". To run a literal command, write it as a list: checks: [\"" + entry + "\"]");
                continue;
            }
            commands.add(entry);
        }
        commands.addAll(acceptance);

        List<String> baselineCommands = project.defaultBaselineChecks() == null
                ? List.of() : project.checks().get(project.defaultBaselineChecks());
        if (!baselineCommands.isEmpty()) {
            // A baseline is not merely a pre-dispatch observation. These commands become the
            // floor of the final gate as well, or an agent could break the project tests that
            // were green before it ran and still satisfy a narrower task check. Preserve the
            // cheap project-health order and avoid executing an identical command twice.
            Set<String> allCommands = new LinkedHashSet<>(baselineCommands);
            allCommands.addAll(commands);
            commands = new ArrayList<>(allCommands);
        }

        // Browser scenarios are an executable definition of done, and a stricter one than
        // most test commands for work a person looks at. Accepting them here is what lets a
        // project with no build system yet — a directory that was empty this morning — be
        // gated at all. What is still refused is a task that defines done nowhere.
        boolean visualDefinesDone = visualQa != null && visualQa.required()
                && !visualQa.scenarios().isEmpty();
        if (commands.isEmpty() && !visualDefinesDone) {
            collector.add("this task resolves to no acceptance command and no visual scenario; "
                    + "a task with no executable definition of 'done' is not runnable. Add a "
                    + "command under checks in project.yaml, or visual_qa scenarios here");
        }
        if (commands.size() > 20) {
            collector.add("a task may declare at most 20 acceptance commands, got " + commands.size());
        }
        for (String command : commands) {
            if (command.length() > 2000 || command.contains("\n") || command.contains("\r")) {
                collector.add("acceptance command must be a single line of at most 2000 characters");
            }
        }

        collector.throwIfAny();
        return new ResolvedTask(
                id,
                goal,
                nonGoals,
                risk != null ? risk : project.defaultRisk(),
                project.baseRef(),
                List.copyOf(paths),
                List.copyOf(baselineCommands),
                List.copyOf(commands),
                authority,
                visualQa,
                budget,
                maxFixAttempts != null ? maxFixAttempts : project.defaultMaxFixAttempts(),
                timeoutMinutes != null ? timeoutMinutes : project.defaultTimeoutMinutes());
    }

    /** A task with every indirection resolved. This is what the gates and roles receive. */
    public record ResolvedTask(
            String id,
            String goal,
            List<String> nonGoals,
            String risk,
            String baseRef,
            List<String> scopePaths,
            List<String> baselineCommands,
            List<String> acceptanceCommands,
            Authority authority,
            VisualQa visualQa,
            Budget budget,
            long maxFixAttempts,
            long timeoutMinutes) {}
}
