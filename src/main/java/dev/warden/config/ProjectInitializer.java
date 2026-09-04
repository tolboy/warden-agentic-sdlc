package dev.warden.config;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates a conservative starter config; it never overwrites an existing project contract. */
public final class ProjectInitializer {
    public record Result(Path projectFile, Path exampleTask, String detectedBuild, List<String> checks) {}

    public Result initialize(Path root, String baseRef) throws IOException {
        root = root.toAbsolutePath().normalize();
        Path warden = root.resolve(".warden");
        Path projectFile = warden.resolve("project.yaml");
        if (Files.exists(projectFile)) throw new IOException(projectFile + " already exists; init never overwrites it");

        Detection detection = detect(root);
        List<String> scopes = new ArrayList<>();
        for (String candidate : List.of("src", "test", "tests", "app", "lib", "docs", "scripts")) {
            if (Files.exists(root.resolve(candidate))) scopes.add(candidate);
        }
        if (scopes.isEmpty()) {
            if (Files.isRegularFile(root.resolve("Makefile"))) scopes.add("Makefile");
            // A project from scratch has no directory to protect: whatever the agent creates
            // is new, and a blast radius of `src` would refuse the package.json it has to
            // write. This is the one case where the whole repository is the honest boundary,
            // and it is written into the contract rather than assumed.
            else if (detection.greenfield()) scopes.add(RepoPath.WHOLE_REPOSITORY);
            else scopes.add("src");
        }

        Files.createDirectories(warden.resolve("tasks"));
        Files.createDirectories(warden.resolve("runs"));
        String project = root.getFileName() == null ? "project" : root.getFileName().toString();
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\nproject: ").append(quote(project)).append("\nbase_ref: ")
                .append(quote(baseRef)).append("\n\n");
        if (detection.greenfield()) yaml.append(GREENFIELD_NOTE);
        yaml.append("checks:\n  fast:").append(detection.checks().isEmpty() ? " []" : "").append('\n');
        for (String command : detection.checks()) yaml.append("    - ").append(quote(command)).append('\n');
        yaml.append("\nscopes:\n  code:\n");
        for (String scope : scopes) yaml.append("    - ").append(quote(scope)).append('\n');
        yaml.append("\ndefaults:\n  checks: fast\n");
        // A detected build gives us a real project-health command. Run it before the first
        // vendor and again in the final gate. A greenfield's `fast` set is deliberately
        // empty, so naming it as a baseline would turn "nothing was checked" into a pass.
        if (!detection.greenfield()) yaml.append("  baseline_checks: fast\n");
        yaml.append("  risk: medium\n")
                .append("  max_fix_attempts: 2\n  timeout_minutes: 30\n")
                .append(LAND_NOTE);
        Files.writeString(projectFile, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Path task = warden.resolve("tasks/example.yaml");
        // With no check command there has to be something else that says "done", or the
        // linter would reject the very file init just wrote. Two assertions that hold for any
        // page, and each one leaves a screenshot the visual_qa role can be shown.
        if (detection.greenfield()) {
            Files.writeString(task, """
                version: 1
                id: example
                goal: Replace this with one mechanically verifiable outcome
                non_goals:
                  - "Name at least one thing this task must not change"
                risk: medium
                scope: code
                checks: fast
                authority:
                  workspace_write: true
                  network: false
                  land: false
                # This project has no check command yet, so these scenarios are its definition
                # of done. Point url at the dev server once there is one, and replace the two
                # generic assertions with what this task actually promises.
                visual_qa:
                  required: true
                  url: "http://127.0.0.1:4173/"
                  scenarios:
                    - "1280x720: no-console-errors"
                    - "700x400: no-console-errors"
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 20.0
                max_fix_attempts: 2
                timeout_minutes: 30
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            writeRunsIgnore(warden);
            new ConfigLoader().load(root, "example");
            return new Result(projectFile, task, detection.name(), detection.checks());
        }
        Files.writeString(task, """
                version: 1
                id: example
                goal: Replace this with one mechanically verifiable outcome
                non_goals:
                  - "Name at least one thing this task must not change"
                risk: medium
                scope: code
                checks: fast
                authority:
                  workspace_write: true
                  network: false
                  land: false
                visual_qa:
                  required: false
                  scenarios: []
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 20.0
                max_fix_attempts: 2
                timeout_minutes: 30
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeRunsIgnore(warden);
        // Never claim successful initialization for a contract Warden itself cannot load.
        new ConfigLoader().load(root, "example");
        return new Result(projectFile, task, detection.name(), detection.checks());
    }

    private static void writeRunsIgnore(Path warden) throws IOException {
        Files.writeString(warden.resolve("runs/.gitignore"), """
                *
                !*/
                !*.json
                !*.jsonl
                !.gitignore
                """,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @SuppressWarnings("unchecked")
    private Detection detect(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            Map<String, Object> object = Json.parseObject(Files.readString(packageJson));
            Map<String, Object> scripts = object.get("scripts") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            List<String> commands = new ArrayList<>();
            for (String name : List.of("test", "check", "build")) {
                if (scripts.get(name) instanceof String) commands.add("npm run " + name);
            }
            if (commands.isEmpty()) throw new IOException("package.json has no test/check/build script to gate");
            return new Detection("npm", commands);
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (Files.isRegularFile(root.resolve(windows ? "gradlew.bat" : "gradlew"))) {
            String wrapper = windows ? ".\\gradlew.bat" : "./gradlew";
            return new Detection("gradle-wrapper", List.of(wrapper + " test", wrapper + " build"));
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            String wrapper = Files.isRegularFile(root.resolve(windows ? "mvnw.cmd" : "mvnw"))
                    ? (windows ? ".\\mvnw.cmd" : "./mvnw") : "mvn";
            return new Detection("maven", List.of(wrapper + " test", wrapper + " package"));
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) return new Detection("cargo", List.of("cargo test"));
        if (Files.isRegularFile(root.resolve("Makefile"))) return new Detection("make", List.of("make test"));
        // No build marker AND no files: a project that does not exist yet, which is a
        // different thing from one whose build system is unrecognised. Guessing a check for
        // the second produces a gate that fails for reasons unrelated to the task, so it is
        // still refused. The first gets a contract whose definition of done is written by the
        // operator, or by the browser scenarios `warden do` drafts.
        if (isEmptyProject(root)) return new Detection("none", List.of(), true);
        throw new IOException("no supported build marker found; create .warden/project.yaml manually");
    }

    /** Nothing but hidden metadata: no source, no manifest, nothing to infer a check from. */
    private static boolean isEmptyProject(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.noneMatch(path -> !path.getFileName().toString().startsWith("."));
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * How this project takes an accepted change. Commented out, and left for the operator to
     * write: Warden knows git, not your forge, and a guessed `gh pr create` in a GitLab
     * project is a landing step that fails the first time it is ever needed.
     */
    private static final String LAND_NOTE = """

            # How an accepted candidate becomes a request. `warden land <run-id>` plans it and
            # changes nothing; --commit, --push and --pull-request escalate a step at a time.
            # Warden merges nothing either way: a request is a request.
            #
            # The command is argv, not a shell line, so a title containing a quote stays one
            # argument. Placeholders: {{remote}} {{branch}} {{base}} {{title}} {{body_file}}.
            #
            # land:
            #   remote: origin        # optional; inferred when the repo has exactly one
            #   base: main            # optional; asked of the remote when absent
            #   pull_request: ["gh", "pr", "create", "--base", "{{base}}", "--head",
            #                  "{{branch}}", "--title", "{{title}}", "--body-file", "{{body_file}}"]
            #
            # GitLab: ["glab", "mr", "create", ...]   Gitea: ["tea", "pr", "create", ...]
            """;

    private static final String GREENFIELD_NOTE = """
            # This directory had no files when warden init ran, so there is nothing here to
            # infer a check from. Two consequences, both deliberate and both editable:
            #
            #   checks.fast is empty. Until you write the real command, the browser scenarios
            #   in the task contract are this project's only executable definition of "done",
            #   and a task that declares neither is refused rather than passed.
            #
            #   the scope is the whole repository. A project with no baseline has no existing
            #   code for a blast radius to protect. Narrow it to real directories as soon as
            #   this project has a shape.
            """;

    private record Detection(String name, List<String> checks, boolean greenfield) {
        Detection(String name, List<String> checks) { this(name, checks, false); }
    }
}
