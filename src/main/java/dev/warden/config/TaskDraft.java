package dev.warden.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Turns a one-line intent into a task file the linter will accept.
 *
 * Routing stays in code: this does not ask a model what the blast radius should be. Scope
 * and checks come from {@code project.yaml}. A goal with no executable definition of done
 * is still refused — by the existing task linter, after this file is written.
 */
public final class TaskDraft {

    public record Written(Path file, String id, boolean existed) {}

    /** Reusing a slug is safe only when it still denotes the same operator intent. */
    @SuppressWarnings("serial")
    public static final class TaskConflict extends IOException {
        public TaskConflict(String message) { super(message); }
    }

    public Written write(Path projectRoot, String id, String goal, String scope, String risk)
            throws IOException {
        return write(projectRoot, id, goal, scope, risk, false);
    }

    /**
     * @param requireVisual the project resolves to no acceptance command, so the browser
     *                      scenarios are the only executable definition of done available.
     *                      Drafting a non-visual task there produces a contract the linter
     *                      immediately refuses, which reads as a Warden defect rather than as
     *                      the missing check command it actually is.
     */
    public Written write(Path projectRoot, String id, String goal, String scope, String risk,
                         boolean requireVisual) throws IOException {
        if (!RepoPath.isSlug(id)) {
            throw new IOException("task id must be a lowercase slug of at most 80 characters, got '" + id + "'");
        }
        if (goal == null || goal.isBlank()) throw new IOException("goal is required");
        Path file = projectRoot.resolve(".warden/tasks").resolve(id + ".yaml");
        if (Files.isRegularFile(file)) {
            TaskSpec existing;
            try {
                existing = TaskSpec.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            } catch (RuntimeException invalid) {
                throw new TaskConflict("task '" + id + "' already exists but cannot be validated: "
                        + invalid.getMessage());
            }
            boolean sameScope = existing.scope().entries().size() == 1
                    && scope.equals(existing.scope().entries().get(0));
            boolean sameRisk = risk.equals(existing.risk());
            boolean sameGoal = goal.strip().equals(existing.goal());
            boolean sameId = id.equals(existing.id());
            if (!sameId || !sameGoal || !sameScope || !sameRisk) {
                throw new TaskConflict("task '" + id + "' already exists with different intent; "
                        + "choose a different --task-id or explicitly edit/review " + file
                        + ". Existing id='" + existing.id() + "', goal='" + existing.goal()
                        + "', scope=" + existing.scope().entries() + ", risk=" + existing.risk());
            }
            return new Written(file, id, true);
        }
        Files.createDirectories(file.getParent());
        boolean visual = requireVisual || looksLikeUi(goal);
        String label = controlLabel(goal);
        String yaml;
        if (visual) {
            yaml = """
                    version: 1
                    id: %s
                    goal: %s
                    non_goals:
                      - "Do not edit .warden contract files or acceptance commands to make checks pass"
                    risk: %s
                    scope: %s
                    authority:
                      workspace_write: true
                      network: false
                      land: false
                    visual_qa:
                      required: true
                    %s  scenarios:
                    %s
                    """.formatted(id, quote(goal.strip()), risk, scope,
                    previewBlock(projectRoot), scenarioBlock(label));
        } else {
            yaml = """
                    version: 1
                    id: %s
                    goal: %s
                    non_goals:
                      - "Do not edit .warden contract files or acceptance commands to make checks pass"
                    risk: %s
                    scope: %s
                    authority:
                      workspace_write: true
                      network: false
                      land: false
                    visual_qa:
                      required: false
                      scenarios: []
                    """.formatted(id, quote(goal.strip()), risk, scope);
        }
        Files.writeString(file, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new Written(file, id, false);
    }

    /**
     * A slug that is a legal task id. Non-latin text becomes {@code task} plus a short
     * hash so a Russian goal still gets a stable, unique file name rather than an empty one.
     */
    public static String slug(String goal) {
        if (goal == null) return "task";
        StringBuilder builder = new StringBuilder();
        for (char c : goal.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) builder.append(c);
            else if (c == ' ' || c == '-' || c == '_' || c == '.') {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '-') builder.append('-');
            }
        }
        String slug = builder.toString();
        while (slug.startsWith("-")) slug = slug.substring(1);
        while (slug.endsWith("-")) slug = slug.substring(0, slug.length() - 1);
        if (slug.length() > 40) slug = slug.substring(0, 40);
        if (slug.isEmpty() || !RepoPath.isSlug(slug)) {
            slug = "task-" + Integer.toHexString(goal.strip().hashCode()).replace("-", "n");
        }
        if (!RepoPath.isSlug(slug)) slug = "task";
        return slug;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Whether the goal is about something a person looks at.
     *
     * The Russian markers are not decoration: this tool is operated in Russian, and an
     * English-only heuristic meant that "почини вёрстку экрана настроек" quietly got no visual
     * check at all while its English twin got one.
     */
    static boolean looksLikeUi(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase(Locale.ROOT);
        String[] markers = {
                "button", "visible", "click", "viewport", "layout", "hud", "css", "screen",
                "ui ", "modal", "dialog", "responsive", "mobile", "landscape", "portrait",
                "кнопк", "экран", "верстк", "вёрстк", "интерфейс", "стил", "окно", "меню",
                "форм", "мобильн", "ландшафт", "портрет", "разметк", "отображ", "виджет",
        };
        for (String marker : markers) {
            if (g.contains(marker)) return true;
        }
        return false;
    }

    /**
     * The label of the control the goal names, or null when the goal does not name one.
     *
     * Returning a default used to be the behaviour, and the default was "Create" — the control
     * of the one application this was first written against. Any other project got a task file
     * asserting the visibility of a button it has never had, which is a guessed acceptance
     * criterion: the same failure mode the scope rules exist to prevent, one field over.
     */
    static String controlLabel(String goal) {
        if (goal == null || goal.isBlank()) return null;
        // "Add a Settings button" / "кнопку Settings"
        java.util.regex.Matcher english = java.util.regex.Pattern
                .compile("(?i)\\b([A-Za-z][A-Za-z0-9_-]*)\\s+button\\b").matcher(goal);
        if (english.find()) return english.group(1);
        // A quoted name is the operator saying it outright, in any language.
        java.util.regex.Matcher quoted = java.util.regex.Pattern
                .compile("[\"'«]([^\"'»]{1,40})[\"'»]").matcher(goal);
        if (quoted.find()) return quoted.group(1).strip();
        java.util.regex.Matcher russian = java.util.regex.Pattern
                .compile("(?i)кнопк\\w*\\s+([A-Za-z][A-Za-z0-9_-]*)").matcher(goal);
        if (russian.find()) return russian.group(1);
        return null;
    }

    /**
     * A `start`/`url` pair only where there is something to start. Writing an npm command into
     * a Gradle project's contract produces a check that fails for a reason unrelated to the task.
     */
    private static String previewBlock(Path projectRoot) {
        if (!Files.isRegularFile(projectRoot.resolve("package.json"))) return "";
        return "  start: \"npm run preview -- --host 127.0.0.1 --port 4173\"\n"
                + "  url: \"http://127.0.0.1:4173/\"\n";
    }

    /**
     * What the browser is asked to check. When the goal named a control, that control; when it
     * did not, the assertions that hold for any page — it loads, it renders, it does not throw
     * — plus a written instruction to add the real one. A weak honest check that also produces
     * a screenshot for the visual_qa role beats a confident wrong one.
     */
    private static String scenarioBlock(String label) {
        // Written with literal indentation rather than a text block: these lines are nested
        // inside `visual_qa.scenarios`, and a text block's incidental-whitespace stripping
        // silently flattened them to column zero, producing YAML the linter rejected.
        String indent = "    ";
        if (label == null) {
            return indent + "# Warden does not invent visual assertions. These two hold for any page:\n"
                    + indent + "# it renders at both viewports and logs no errors, and each one writes a\n"
                    + indent + "# screenshot for the visual_qa role to look at. Replace them with what\n"
                    + indent + "# this task actually promises, for example:\n"
                    + indent + "#   - \"1280x720: text=Settings visible\"\n"
                    + indent + "#   - \"1280x720: css=.settings-panel visible\"\n"
                    + indent + "#   - \"700x400: testid=save click -> css=.saved visible\"\n"
                    + indent + "- \"1280x720: no-console-errors\"\n"
                    + indent + "- \"700x400: no-console-errors\"";
        }
        return indent + "- \"1280x720: text=" + label + " visible\"\n"
                + indent + "- \"700x400: text=" + label + " visible\"";
    }
}
