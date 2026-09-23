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
            String conflict = intentConflict(taskId, operatorGoal, compiled.scope(),
                    compiled.risk(), existing, file);
            if (conflict != null) throw new TaskDraft.TaskConflict(conflict);
            // Same intent: the new plan is applied, and the blocks that are the operator's
            // own are carried across it.
            //
            // Both halves of that were broken. The comparison above used to be the COMPILED
            // goal - the operator's, plus whatever the planner appended to it - against the
            // operator's goal alone, so "same intent" was unreachable for any contract a
            // person had written, and `--prepare always` ended as task_conflict with the
            // planner already paid for (Crumb Raiders, 2026-09-19: four minutes and $0.27 of
            // Grok, discarded). And when it was reachable, the file was kept whole, so a
            // second plan changed nothing at all.
            //
            // `render` writes goal, non_goals, risk, scope, checks, authority and visual_qa
            // and stops there, so applying a plan silently reset `budgets` and
            // `max_fix_attempts` to TaskSpec's defaults - six calls, where this workflow
            // needs five before a single repair. Those are ceilings a person chose, not
            // something a planner may lower by drafting.
            String merged = carryOperatorBlocks(compiled.yaml(),
                    Files.readString(file, StandardCharsets.UTF_8));
            Files.writeString(file, merged, StandardCharsets.UTF_8);
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

    /**
     * The existing contract with what an amending planner added to its acceptance, and
     * nothing else changed; null when the draft adds nothing.
     *
     * An amendment answers readers who found the acceptance too weak to have proved a
     * candidate that already passed. It may add: browser scenarios, appended to the existing
     * `visual_qa` list with the operator's start, url and comments left as they were; and named
     * checks from `project.yaml`, merged into a bare name or a `names:` list. It may not touch
     * the goal, scope, risk, authority, budgets or any existing check or scenario — the draft's
     * other fields are read for nothing. A contract whose checks are a literal command list
     * gains no checks (those are commands, not names), and one with no browser gains no
     * scenarios, because there is nothing to start. The result must parse and resolve against
     * the project, or it is refused.
     */
    public static String amend(String before, Map<String, Object> artifact, ProjectConfig project,
                               String source) {
        TaskSpec existing = TaskSpec.parse(before, source);
        String newline = before.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(before.split("\r?\n", -1)));
        boolean added = false;

        List<String> have = existing.visualQa() == null ? List.of() : existing.visualQa().scenarios();
        List<String> fresh = new ArrayList<>();
        if (artifact.get("visual_qa") instanceof Map<?, ?> visual && visual.get("scenarios") instanceof List<?> listed) {
            for (Object item : listed) {
                if (item instanceof String scenario && !scenario.isBlank()
                        && !have.contains(scenario.strip()) && !fresh.contains(scenario.strip())) {
                    fresh.add(scenario.strip());
                }
            }
        }
        if (!fresh.isEmpty() && existing.visualQa() != null && existing.visualQa().required()) {
            added |= appendScenarios(lines, fresh);
        }

        List<String> named = new ArrayList<>();
        if (artifact.get("acceptance") instanceof List<?> acceptance) {
            // `{check: <name>}` only, exactly as a draft is compiled: a bare string is a shell
            // command and is never promoted to a trusted check, not even by an amendment.
            for (Object item : acceptance) {
                if (item instanceof Map<?, ?> entry && entry.get("check") instanceof String name
                        && project.checks().containsKey(name) && !named.contains(name)) {
                    named.add(name);
                }
            }
        }
        if (!named.isEmpty()) added |= mergeCheckNames(lines, named);

        if (!added) return null;
        String amended = String.join(newline, lines);
        TaskSpec.parse(amended, source).resolve(project, source);
        return amended;
    }

    /** Appends scenarios after the last item of the top-level `visual_qa.scenarios` list. */
    private static boolean appendScenarios(List<String> lines, List<String> fresh) {
        int visual = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("visual_qa:") || lines.get(i).startsWith("visual_qa: ")
                    || lines.get(i).startsWith("visual_qa:\t")) { visual = i; break; }
        }
        if (visual < 0) return false;
        int scenarios = -1;
        int end = lines.size();
        for (int i = visual + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#")) {
                end = i;
                break;
            }
            if (line.stripLeading().startsWith("scenarios:")) scenarios = i;
        }
        if (scenarios < 0) return false;
        String header = lines.get(scenarios);
        int headerIndent = header.length() - header.stripLeading().length();
        String inline = header.stripLeading().substring("scenarios:".length()).strip();
        if (inline.startsWith("[") ) {
            // `scenarios: []` or a flow list: rewritten as a block list holding both.
            List<String> existing = new ArrayList<>();
            String body = inline.substring(1, Math.max(1, inline.lastIndexOf(']'))).strip();
            if (!body.isEmpty()) {
                for (String part : body.split(",")) {
                    String value = part.strip();
                    if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isEmpty()) existing.add(value);
                }
            }
            List<String> block = new ArrayList<>();
            block.add(" ".repeat(headerIndent) + "scenarios:");
            for (String value : existing) block.add(" ".repeat(headerIndent + 2) + "- " + TaskDraft.quote(value));
            for (String value : fresh) block.add(" ".repeat(headerIndent + 2) + "- " + TaskDraft.quote(value));
            lines.remove(scenarios);
            lines.addAll(scenarios, block);
            return true;
        }
        int insertAt = scenarios + 1;
        String itemIndent = " ".repeat(headerIndent + 2);
        for (int i = scenarios + 1; i < end; i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            if (indent <= headerIndent) break;
            if (line.stripLeading().startsWith("-")) {
                insertAt = i + 1;
                itemIndent = line.substring(0, indent);
            }
        }
        List<String> block = new ArrayList<>();
        for (String value : fresh) block.add(itemIndent + "- " + TaskDraft.quote(value));
        lines.addAll(insertAt, block);
        return true;
    }

    /**
     * Merges check names into a bare `checks: name` or a `checks:` block with `names: [...]`,
     * or adds the key when the contract has none. A literal command list is left alone.
     */
    private static boolean mergeCheckNames(List<String> lines, List<String> named) {
        int key = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("checks:") || lines.get(i).startsWith("checks: ")) { key = i; break; }
        }
        List<String> current = new ArrayList<>();
        int end = key + 1;
        if (key >= 0) {
            String inline = lines.get(key).substring("checks:".length()).strip();
            if (inline.startsWith("[")) return false; // literal commands, not names
            if (!inline.isEmpty() && !inline.startsWith("#")) {
                current.add(inline);
            } else {
                while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith(" "))) {
                    String entry = lines.get(end).strip();
                    if (entry.startsWith("names:")) {
                        String list = entry.substring("names:".length()).strip();
                        if (!list.startsWith("[")) return false;
                        for (String part : list.substring(1, Math.max(1, list.lastIndexOf(']'))).split(",")) {
                            if (!part.isBlank()) current.add(part.strip());
                        }
                    } else if (!entry.isEmpty() && !entry.startsWith("#")) {
                        return false; // a shape this edit does not know; leave it to a person
                    }
                    end++;
                }
            }
        }
        List<String> union = new ArrayList<>(current);
        for (String name : named) if (!union.contains(name)) union.add(name);
        if (union.size() == current.size()) return false;
        List<String> block = List.of("checks:", "  names: [" + String.join(", ", union) + "]");
        if (key < 0) {
            int insertAt = lines.size();
            while (insertAt > 0 && lines.get(insertAt - 1).isBlank()) insertAt--;
            lines.addAll(insertAt, block);
        } else {
            for (int i = end - 1; i >= key; i--) lines.remove(i);
            lines.addAll(key, block);
        }
        return true;
    }

    /**
     * The keys a person owns in a contract, in the order they are written.
     *
     * A planner drafts what the work is; these say what it may spend, who does it, and what
     * a person must be shown. `visual_qa` is here because a hand-written browser contract is
     * a promise about the product, and a planner that has never seen the screen must not
     * quietly withdraw it. `use` is here for the same reason one rung down: it names the
     * profile, the effort and the Orca host a person chose for a stage, and a replan that
     * dropped it would send the next run back to the default roster without saying so —
     * losing precisely the settings the overlay exists to keep out of `~/.warden`.
     */
    private static final List<String> OPERATOR_BLOCKS =
            List.of("visual_qa", "use", "budgets", "max_fix_attempts", "timeout_minutes");

    /**
     * The compiled contract with the operator's own blocks taken from the file it replaces.
     *
     * Line-based on purpose: Warden reads a strict YAML subset and does not write it, and
     * re-emitting a hand-written file would drop the comments explaining why a ceiling is
     * what it is - which in these files is the most valuable part.
     */
    static String carryOperatorBlocks(String compiledYaml, String existingYaml) {
        String result = compiledYaml;
        for (String key : OPERATOR_BLOCKS) {
            String existingBlock = blockOf(existingYaml, key);
            if (existingBlock == null) continue;
            String compiledBlock = blockOf(result, key);
            result = compiledBlock == null
                    ? (result.endsWith("\n") ? result : result + "\n") + existingBlock
                    : result.replace(compiledBlock, existingBlock);
        }
        return result;
    }

    /**
     * One top-level block: the line that starts with {@code key:} in the first column, plus
     * every line under it that is indented, blank or a comment. Null when the key is absent.
     *
     * The key index is kept separate from any comment that introduces it: walking {@code start}
     * onto the comment and then scanning from there treated {@code budgets:} as the first line
     * of the body, which is unindented, so a ceiling under {@code # Operator ceiling} was
     * dropped. CRLF is split the way {@link dev.warden.yaml.Yaml} already splits it, so a
     * Windows-saved {@code budgets:} still matches.
     */
    private static String blockOf(String yaml, String key) {
        String[] lines = yaml.split("\r?\n", -1);
        int keyIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.equals(key + ":") || line.startsWith(key + ": ")) {
                keyIndex = index;
                break;
            }
        }
        if (keyIndex < 0) return null;
        int start = keyIndex;
        while (start > 0 && lines[start - 1].startsWith("#")) start--;
        int end = keyIndex + 1;
        while (end < lines.length) {
            String line = lines[end];
            boolean belongs = line.isBlank() || line.startsWith(" ") || line.startsWith("\t")
                    || line.startsWith("#");
            if (!belongs) break;
            end++;
        }
        // A trailing comment block introduces the next key rather than closing this one.
        while (end > keyIndex + 1 && (lines[end - 1].startsWith("#") || lines[end - 1].isBlank())) {
            end--;
        }
        StringBuilder block = new StringBuilder();
        for (int index = start; index < end; index++) block.append(lines[index]).append('\n');
        return block.toString();
    }

    /**
     * Why an existing contract is not this invocation's, or null when it is.
     *
     * The operator's goal is compared as a prefix, not for equality: a planner may append to
     * it and may not replace or drop it, which is the same rule {@link TaskDraft#requireSameIntent}
     * applies. Id, scope and risk are compared exactly - those are the operator's decisions,
     * and a contract that changes one of them is a different piece of work under a reused name.
     *
     * Public so the caller can ask before it pays a planner. A conflict is knowable from the
     * file alone; discovering it after the call is how a plan gets bought and thrown away.
     */
    public static String intentConflict(String taskId, String operatorGoal, String scope,
                                        String risk, TaskSpec existing, Path file) {
        boolean sameScope = existing.scope().entries().size() == 1
                && scope.equals(existing.scope().entries().get(0));
        boolean sameRisk = risk.equals(existing.risk());
        boolean sameGoal = existing.goal() != null
                && existing.goal().startsWith(operatorGoal.strip());
        boolean sameId = taskId.equals(existing.id());
        if (sameId && sameGoal && sameScope && sameRisk) return null;
        return "task '" + taskId + "' already exists with different intent; choose a different "
                + "--task-id or explicitly edit/review " + file
                + ". Existing id='" + existing.id() + "', goal='" + existing.goal()
                + "', scope=" + existing.scope().entries() + ", risk=" + existing.risk();
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
