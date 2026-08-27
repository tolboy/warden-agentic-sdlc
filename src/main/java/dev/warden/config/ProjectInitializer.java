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
            else scopes.add("src");
        }

        Files.createDirectories(warden.resolve("tasks"));
        Files.createDirectories(warden.resolve("runs"));
        String project = root.getFileName() == null ? "project" : root.getFileName().toString();
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\nproject: ").append(quote(project)).append("\nbase_ref: ")
                .append(quote(baseRef)).append("\n\nchecks:\n  fast:\n");
        for (String command : detection.checks()) yaml.append("    - ").append(quote(command)).append('\n');
        yaml.append("\nscopes:\n  code:\n");
        for (String scope : scopes) yaml.append("    - ").append(quote(scope)).append('\n');
        yaml.append("\ndefaults:\n  checks: fast\n  risk: medium\n")
                .append("  max_fix_attempts: 2\n  timeout_minutes: 30\n");
        Files.writeString(projectFile, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Path task = warden.resolve("tasks/example.yaml");
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
        Files.writeString(warden.resolve("runs/.gitignore"), """
                *
                !*/
                !*.json
                !*.jsonl
                !.gitignore
                """,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        // Never claim successful initialization for a contract Warden itself cannot load.
        new ConfigLoader().load(root, "example");
        return new Result(projectFile, task, detection.name(), detection.checks());
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
        throw new IOException("no supported build marker found; create .warden/project.yaml manually");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Detection(String name, List<String> checks) {}
}
