package dev.warden.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a planner artifact into a frozen task contract, or refuses it.
 *
 * The planner drafts. Warden validates. Nothing the model wrote is trusted as an acceptance
 * command, a scope, a goal, or a grant: each of those is checked against the project and the
 * invocation, and a refusal names the field the way every other config problem does.
 */
public final class PlannerDraft {

    /**
     * What the invocation already authorised for the contract, not for the planner itself.
     * The planner may request less. It may not request more, and asking is a refusal rather
     * than a grant.
     */
    public record Access(boolean workspaceWrite, boolean network, boolean land) {
        public static final Access DO_DEFAULT = new Access(true, false, false);
    }

    public record Compiled(String yaml, List<String> checks, String scope, String risk,
                           Access access) {}

    private PlannerDraft() {}

    /**
     * Validate {@code artifact} against the project and the invocation, then write the
     * contract. The operator's original goal is the prefix of the written goal; the planner
     * may add after it and may not replace or drop it.
     *
     * An existing contract is the same protection {@link TaskDraft} already gives: different
     * intent is a conflict naming the file; the same intent leaves the file untouched so a
     * hand-written visual contract survives {@code --prepare always}.
     */
    public static TaskDraft.Written write(Path projectRoot, String taskId, String operatorGoal,
                                          ProjectConfig project, Access granted,
                                          Map<String, Object> artifact) throws IOException {
        Compiled compiled = compile(taskId, operatorGoal, project, granted, artifact, projectRoot);
        TaskSpec proposed;
        try {
            proposed = TaskSpec.parse(compiled.yaml(), "planner-compiled");
        } catch (RuntimeException invalid) {
            throw new ConfigException("planner draft",
                    "compiled contract is not a valid task: " + invalid.getMessage());
        }
        Path file = projectRoot.resolve(".warden/tasks").resolve(taskId + ".yaml");
        if (Files.isRegularFile(file)) {
            TaskSpec existing;
            try {
                existing = TaskSpec.parse(Files.readString(file, StandardCharsets.UTF_8),
                        file.toString());
            } catch (RuntimeException invalid) {
                throw new TaskDraft.TaskConflict("task '" + taskId
                        + "' already exists but cannot be validated: " + invalid.getMessage());
            }
            boolean sameScope = existing.scope().entries().size() == 1
                    && compiled.scope().equals(existing.scope().entries().get(0));
            boolean sameRisk = compiled.risk().equals(existing.risk());
            boolean sameGoal = proposed.goal().equals(existing.goal());
            boolean sameId = taskId.equals(existing.id());
            if (!sameId || !sameGoal || !sameScope || !sameRisk) {
                throw new TaskDraft.TaskConflict("task '" + taskId
                        + "' already exists with different intent; choose a different --task-id "
                        + "or explicitly edit/review " + file
                        + ". Existing id='" + existing.id() + "', goal='" + existing.goal()
                        + "', scope=" + existing.scope().entries() + ", risk=" + existing.risk());
            }
            return new TaskDraft.Written(file, taskId, true);
        }
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, compiled.yaml(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exists) {
            throw new TaskDraft.TaskConflict("task '" + taskId + "' already exists at " + file);
        }
        return new TaskDraft.Written(file, taskId, false);
    }

    public static Compiled compile(String taskId, String operatorGoal, ProjectConfig project,
                                   Access granted, Map<String, Object> artifact) {
        return compile(taskId, operatorGoal, project, granted, artifact, null);
    }

    public static Compiled compile(String taskId, String operatorGoal, ProjectConfig project,
                                   Access granted, Map<String, Object> artifact, Path projectRoot) {
        ConfigException.Collector collector = new ConfigException.Collector("planner draft");
        if (artifact == null) {
            collector.add("artifact is missing");
            throw new ConfigException("planner draft", collector.issues());
        }

        String handed = operatorGoal == null ? "" : operatorGoal.strip();
        String reference = text(artifact.get("operator_goal"));
        if (reference == null) {
            collector.add("operator_goal is required and must equal the goal Warden handed the planner");
        } else if (!handed.equals(reference.strip())) {
            collector.add("operator_goal does not match the goal Warden handed the planner");
        }

        String addition = text(artifact.get("goal_addition"));
        String deliverable = text(artifact.get("deliverable"));
        // Built from the string Warden handed the planner, never from a replacement the model
        // wrote. operator_goal is checked for equality above; a mismatch is already a refusal.
        String contractGoal = handed;
        if (addition != null && !addition.isBlank()) {
            contractGoal = handed + "\n\n" + addition.strip();
        }
        if (deliverable != null && !deliverable.isBlank()
                && (addition == null || !contractGoal.contains(deliverable.strip()))) {
            contractGoal = contractGoal + "\n\nDeliverable: " + deliverable.strip();
        }

        String risk = text(artifact.get("risk"));
        if (risk == null || !ProjectConfig.RISK_LEVELS.contains(risk)) {
            collector.add("risk must be one of " + ProjectConfig.RISK_LEVELS
                    + (risk == null ? " (missing)" : ", got '" + risk + "'"));
        }

        String scope = text(artifact.get("scope"));
        if (scope == null || scope.isBlank()) {
            collector.add("scope is required and must name a scope defined under scopes in project.yaml");
        } else if (!project.scopes().containsKey(scope)) {
            collector.add("scope names '" + scope + "', which is not defined under scopes in "
                    + "project.yaml. Available: " + project.scopes().keySet());
        }

        List<String> checkNames = new ArrayList<>();
        Object acceptance = artifact.get("acceptance");
        if (!(acceptance instanceof List<?> entries) || entries.isEmpty()) {
            collector.add("acceptance is required and must list named checks from project.yaml");
        } else {
            for (int index = 0; index < entries.size(); index++) {
                String field = "acceptance[" + index + "]";
                Object entry = entries.get(index);
                if (entry instanceof String command) {
                    collector.add(field + " is the shell string '" + command
                            + "'; acceptance must name a check under checks: in project.yaml, "
                            + "and a shell string is never promoted to a trusted command. Available: "
                            + project.checks().keySet());
                    continue;
                }
                if (!(entry instanceof Map<?, ?> object)) {
                    collector.add(field + " must be an object with check: <name>, not "
                            + typeName(entry));
                    continue;
                }
                Object named = object.get("check");
                if (!(named instanceof String check) || check.isBlank()) {
                    collector.add(field + ".check is required and must name a check under checks: "
                            + "in project.yaml. Available: " + project.checks().keySet());
                    continue;
                }
                if (object.get("command") != null) {
                    collector.add(field + " declares command, which is a shell string in disguise; "
                            + "name a check from project.yaml instead");
                    continue;
                }
                if (!project.checks().containsKey(check)) {
                    collector.add(field + ".check names '" + check
                            + "', which is not defined under checks in project.yaml. Available: "
                            + project.checks().keySet());
                    continue;
                }
                if (!checkNames.contains(check)) checkNames.add(check);
            }
        }

        Access requested = readAccess(artifact.get("required_access"), collector);
        if (requested != null) {
            if (requested.land() && !granted.land()) {
                collector.add("required_access.land is true, which the invocation did not authorise");
            }
            if (requested.network() && !granted.network()) {
                collector.add("required_access.network is true, which the invocation did not authorise");
            }
            if (requested.workspaceWrite() && !granted.workspaceWrite()) {
                collector.add("required_access.workspace_write is true, which the invocation did not authorise");
            }
        }

        List<String> nonGoals = new ArrayList<>();
        nonGoals.add("Do not edit .warden contract files or acceptance commands to make checks pass");
        Object listed = artifact.get("non_goals");
        if (listed instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String line && !line.isBlank() && !nonGoals.contains(line)) {
                    nonGoals.add(line);
                }
            }
        }

        Visual visual = readVisual(artifact, handed, projectRoot, collector);

        if (taskId == null || !RepoPath.isSlug(taskId)) {
            collector.add("task id must be a lowercase slug of at most 80 characters, got '"
                    + taskId + "'");
        }

        collector.throwIfAny();

        Access access = requested == null ? granted : new Access(
                requested.workspaceWrite(),
                requested.network(),
                requested.land());

        String yaml = render(taskId, contractGoal, nonGoals, risk, scope, checkNames, access,
                projectRoot, handed, visual);
        return new Compiled(yaml, List.copyOf(checkNames), scope, risk, access);
    }

    private record Visual(boolean required, List<String> scenarios) {}

    /**
     * The planner may ask for a visual contract. If it does not, Warden applies the same
     * rule {@link TaskDraft} uses: a UI goal on a project that serves a preview gets the
     * honest default scenarios, so {@code --prepare always} is not weaker than
     * {@code --prepare off} on the same goal.
     */
    private static Visual readVisual(Map<String, Object> artifact, String operatorGoal,
                                     Path projectRoot, ConfigException.Collector collector) {
        if (!artifact.containsKey("visual_qa")) {
            boolean serves = projectRoot != null && PreviewServer.detect(projectRoot) != null;
            return new Visual(TaskDraft.looksLikeUi(operatorGoal) && serves, List.of());
        }
        Object raw = artifact.get("visual_qa");
        if (!(raw instanceof Map<?, ?> object)) {
            collector.add("visual_qa must be an object with required and optional scenarios, got "
                    + typeName(raw));
            return new Visual(false, List.of());
        }
        Object req = object.get("required");
        boolean required;
        if (req instanceof Boolean flag) {
            required = flag;
        } else {
            collector.add("visual_qa.required must be true or false"
                    + (req == null ? " (missing)" : ", got " + typeName(req)));
            required = false;
        }
        List<String> scenarios = new ArrayList<>();
        Object listed = object.get("scenarios");
        if (listed == null) {
            return new Visual(required, List.of());
        }
        if (!(listed instanceof List<?> items)) {
            collector.add("visual_qa.scenarios must be a list of strings, got " + typeName(listed));
            return new Visual(required, List.of());
        }
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            if (item instanceof String scenario && !scenario.isBlank()) scenarios.add(scenario);
            else collector.add("visual_qa.scenarios[" + index + "] must be a non-empty string, got "
                    + typeName(item));
        }
        if (!required) scenarios.clear();
        return new Visual(required, List.copyOf(scenarios));
    }

    private static Access readAccess(Object raw, ConfigException.Collector collector) {
        if (!(raw instanceof Map<?, ?> object)) {
            collector.add("required_access is required and must be an object with "
                    + "workspace_write, network and land");
            return null;
        }
        return new Access(
                flag(object, "workspace_write", collector),
                flag(object, "network", collector),
                flag(object, "land", collector));
    }

    private static boolean flag(Map<?, ?> object, String key, ConfigException.Collector collector) {
        Object value = object.get(key);
        if (value instanceof Boolean flag) return flag;
        collector.add("required_access." + key + " must be true or false"
                + (value == null ? " (missing)" : ", got " + typeName(value)));
        return false;
    }

    private static String render(String id, String goal, List<String> nonGoals, String risk,
                                 String scope, List<String> checks, Access access,
                                 Path projectRoot, String operatorGoal, Visual visual) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("id: ").append(id).append('\n');
        yaml.append("goal: ").append(TaskDraft.quote(goal)).append('\n');
        yaml.append("non_goals:\n");
        for (String line : nonGoals) {
            yaml.append("  - ").append(TaskDraft.quote(line)).append('\n');
        }
        yaml.append("risk: ").append(risk).append('\n');
        yaml.append("scope: ").append(scope).append('\n');
        appendNamedChecks(yaml, checks);
        yaml.append("authority:\n");
        yaml.append("  workspace_write: ").append(access.workspaceWrite()).append('\n');
        yaml.append("  network: ").append(access.network()).append('\n');
        yaml.append("  land: ").append(access.land()).append('\n');
        appendVisualQa(yaml, projectRoot, operatorGoal, visual);
        return yaml.toString();
    }

    /**
     * Named checks are names. A single name is the bare form {@code TaskSpec.resolve} treats
     * as a name that must exist. Several names use the {@code names:} mapping, which resolve
     * still treats as names — unlike a YAML list, which is the handwritten-literal form and
     * would run a disappeared check's former identifier as a shell string.
     */
    private static void appendNamedChecks(StringBuilder yaml, List<String> checks) {
        if (checks.size() == 1) {
            yaml.append("checks: ").append(checks.get(0)).append('\n');
            return;
        }
        yaml.append("checks:\n  names: [");
        for (int index = 0; index < checks.size(); index++) {
            if (index > 0) yaml.append(", ");
            yaml.append(checks.get(index));
        }
        yaml.append("]\n");
    }

    private static void appendVisualQa(StringBuilder yaml, Path projectRoot, String operatorGoal,
                                       Visual visual) {
        if (!visual.required()) {
            yaml.append("visual_qa:\n  required: false\n  scenarios: []\n");
            if (TaskDraft.looksLikeUi(operatorGoal)
                    && (projectRoot == null || PreviewServer.detect(projectRoot) == null)) {
                yaml.append("  # The goal reads as if it were about a screen, but this project\n");
                yaml.append("  # serves no preview Warden can detect, so there is nothing to\n");
                yaml.append("  # photograph. Add `visual_qa.preview.start` and `url` here if it\n");
                yaml.append("  # does serve one, then set required: true.\n");
            }
            return;
        }
        yaml.append("visual_qa:\n  required: true\n");
        if (projectRoot != null) yaml.append(TaskDraft.previewBlock(projectRoot));
        yaml.append("  scenarios:\n");
        if (!visual.scenarios().isEmpty()) {
            for (String scenario : visual.scenarios()) {
                yaml.append("    - ").append(TaskDraft.quote(scenario)).append('\n');
            }
        } else {
            yaml.append(TaskDraft.scenarioBlock(TaskDraft.controlLabel(operatorGoal))).append('\n');
        }
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
