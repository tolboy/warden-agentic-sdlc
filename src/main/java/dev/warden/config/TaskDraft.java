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
        // Words in the goal may suggest a screen; only the project can confirm there is one.
        // `looksLikeUi` matches substrings, and on a Russian goal that is generous to a fault
        // — "формулировку" contains "форм", "первый экран" contains "экран". Measured on a
        // repository of prep material with no HTML, no server and a python checker: the
        // drafter wrote `visual_qa: required: true` with two browser scenarios, which the
        // operator then had to delete by hand. A project with nothing to serve has nothing to
        // photograph, whatever the goal says. `requireVisual` still overrides, because a
        // greenfield project's scenarios are its only executable definition of done.
        boolean servesSomething = PreviewServer.detect(projectRoot) != null;
        boolean visual = requireVisual || (looksLikeUi(goal) && servesSomething);
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
                    %s""".formatted(id, quote(goal.strip()), risk, scope,
                    looksLikeUi(goal) && !servesSomething
                            ? "  # The goal reads as if it were about a screen, but this project\n"
                            + "  # serves no preview Warden can detect, so there is nothing to\n"
                            + "  # photograph. Add `visual_qa.preview.start` and `url` here if it\n"
                            + "  # does serve one, then set required: true.\n"
                            : "");
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

    /**
     * A YAML double-quoted scalar that survives being read back.
     *
     * Newlines are the ones that matter, and they were the ones missing. A goal typed on a
     * command line has none, so the omission was invisible until the channel built for goals
     * a command line cannot carry — `--goal-file` — was used for a goal with paragraphs in it.
     * The draft was written with raw newlines inside the quotes, and Warden then refused to
     * parse its own file: `line 3: unterminated quoted string`. The four escapes below are
     * exactly the four the reader in {@code Yaml} accepts, so what is written can be read.
     */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
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
        // "Add a Settings button" / "кнопку Settings". The capital is the whole test: a
        // live draft read "give every lab chapter button a data-testid" and wrote
        // `text=chapter visible` into the contract — an assertion about a word the page
        // happens to contain, which would have gone green without testing anything. A
        // control is named the way it is written on the control.
        java.util.regex.Matcher english = java.util.regex.Pattern
                .compile("\\b([A-Z][A-Za-z0-9_-]*)\\s+[Bb]utton\\b").matcher(goal);
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
        PreviewServer serving = PreviewServer.detect(projectRoot);
        if (serving == null) return "";
        return "  start: \"" + serving.command() + "\"\n"
                + "  url: \"" + serving.url() + "\"\n";
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
        String handle = label == null ? null : testIdHandle(label);
        if (handle == null) {
            String named = "";
            if (label != null) {
                // The goal did name a control, and saying nothing about it would read as if it
                // had not. What cannot be done is guess the attribute: `slug` answers
                // `task-345e4ebd` for «Сохранить», which is a fine file name and a nonsense
                // test id, and a drafted contract asserting it is red by construction while
                // telling nobody what to build.
                named = indent + "# The goal names " + label.replaceAll("\\s+", " ")
                        + ", but a data-testid is written in latin and Warden will not\n"
                        + indent + "# invent one. Put one on that control and say so here, for example:\n"
                        + indent + "#   - \"1280x720: testid=save visible\"\n";
            }
            return named
                    + indent + "# Warden does not invent visual assertions. These two hold for any page:\n"
                    + indent + "# it renders at both viewports and logs no errors, and each one writes a\n"
                    + indent + "# screenshot for the visual_qa role to look at. Replace them with what\n"
                    + indent + "# this task actually promises, for example:\n"
                    + indent + "#   - \"1280x720: testid=settings visible\"\n"
                    + indent + "#   - \"1280x720: css=.settings-panel visible\"\n"
                    + indent + "#   - \"700x400: testid=save click -> css=.saved visible\"\n"
                    + indent + "- \"1280x720: no-console-errors\"\n"
                    + indent + "- \"700x400: no-console-errors\"";
        }
        return indent + "# Put data-testid=\"" + handle + "\" on that control: the words written on a\n"
                + indent + "# control are the part of it a later task is free to change.\n"
                + indent + "- \"1280x720: testid=" + handle + " visible\"\n"
                + indent + "- \"700x400: testid=" + handle + " visible\"";
    }

    /**
     * A {@code data-testid} an implementer can actually be asked to write, or null.
     *
     * {@link #slug} exists to name a file and so never fails: for a label with no latin in it
     * at all it answers {@code task-} plus a hash, which is a stable file name and a test id
     * nobody would ever put in the markup.
     */
    private static String testIdHandle(String label) {
        boolean latin = false;
        for (char c : label.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) { latin = true; break; }
        }
        if (!latin) return null;
        String handle = slug(label);
        return handle.isEmpty() ? null : handle;
    }
}
