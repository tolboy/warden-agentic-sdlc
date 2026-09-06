package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.ProjectInitializer;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;

public final class InitializerTest implements Suite {
    @Override public String name() { return "initializer"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-init-");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("package.json"), """
                    {"name":"fixture","scripts":{"test":"node --test","build":"node build.js"}}
                    """);
            ProjectInitializer.Result result = new ProjectInitializer().initialize(root, "origin/trunk");
            check.eq("npm detected", "npm", result.detectedBuild());
            check.that("project written", Files.isRegularFile(result.projectFile()));
            check.that("example task written", Files.isRegularFile(result.exampleTask()));
            ConfigLoader.Loaded loaded = new ConfigLoader().load(root, "example");
            check.eq("generated project resolves", root.getFileName().toString(), loaded.project().project());
            check.eq("base ref retained", "origin/trunk", loaded.resolved().baseRef());
            check.eq("a detected project opts its real test set into the pre-agent baseline",
                    "fast", loaded.project().defaultBaselineChecks());
            check.eq("the resolved baseline contains the detected project commands",
                    java.util.List.of("npm run test", "npm run build"),
                    loaded.resolved().baselineCommands());
            check.that("generated task is fail-closed for landing", !loaded.resolved().authority().land());

            // The commented `land:` example is the only instruction an operator has for the
            // one step that reaches their forge, and it shipped wrapped across two lines.
            // Warden's YAML is a strict subset in which a flow sequence does not continue
            // over a newline, so uncommenting it verbatim answered `unexpected end of flow
            // collection` — from the parser that wrote the example. Anything a template
            // offers must parse when it is used exactly as offered.
            String written = Files.readString(result.projectFile());
            java.util.List<String> lines = written.lines().toList();
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().equals("# land:")) { start = i; break; }
            }
            check.that("the land example is offered at all", start >= 0);
            StringBuilder block = new StringBuilder();
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (!line.startsWith("#")) break;
                String bare = line.substring(1);
                if (bare.startsWith(" ")) bare = bare.substring(1);
                // Drop the trailing prose comments the operator would not uncomment.
                if (bare.isBlank() || bare.startsWith("GitLab:")) break;
                block.append(bare).append('\n');
            }
            String uncommented = written.lines()
                    .takeWhile(line -> !line.strip().startsWith("# How an accepted"))
                    .collect(java.util.stream.Collectors.joining("\n")) + "\n" + block;
            check.contains("the land example is offered", uncommented, "pull_request:");
            dev.warden.config.ProjectConfig landing =
                    dev.warden.config.ProjectConfig.parse(uncommented, "project.yaml");
            check.eq("and uncommenting it verbatim produces a contract Warden can read",
                    java.util.List.of("gh", "pr", "create", "--base", "{{base}}", "--head",
                            "{{branch}}", "--title", "{{title}}", "--body-file", "{{body_file}}"),
                    landing.land().pullRequest());

            check.rejects("init refuses overwrite", "never overwrites", () ->
                    new ProjectInitializer().initialize(root, "origin/main"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        // A project that does not exist yet. There is nothing to infer a check from, and no
        // existing code for a blast radius to protect — but the contract still has to be one
        // the linter accepts, or the first thing an operator meets is a refusal from init.
        Path empty = Files.createTempDirectory("warden-init-empty-");
        try {
            ProjectInitializer.Result greenfield = new ProjectInitializer().initialize(empty, "HEAD");
            check.eq("an empty directory is detected as a project with no build yet",
                    "none", greenfield.detectedBuild());
            ConfigLoader.Loaded loaded = new ConfigLoader().load(empty, "example");
            check.eq("no command is invented for it", java.util.List.of(),
                    loaded.project().checks().get("fast"));
            check.eq("and an empty command set is not presented as a green baseline", null,
                    loaded.project().defaultBaselineChecks());
            check.eq("so the resolved task asks for no baseline command", java.util.List.of(),
                    loaded.resolved().baselineCommands());
            check.eq("and the whole repository is the declared blast radius",
                    java.util.List.of("<repository>"), loaded.resolved().scopePaths());
            check.that("which the task inherits as its scope",
                    loaded.resolved().scopePaths().contains("<repository>"));
            check.that("the browser scenarios are what define done instead",
                    loaded.resolved().visualQa().required()
                            && !loaded.resolved().visualQa().scenarios().isEmpty());
            check.contains("and the contract says so in the file itself",
                    Files.readString(greenfield.projectFile()), "no existing");
        } finally {
            try (var paths = Files.walk(empty)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        // Unrecognised is not the same as absent: a project with files but no build marker
        // still gets a refusal, because guessing its check would gate the wrong thing.
        Path unknown = Files.createTempDirectory("warden-init-unknown-");
        try {
            Files.writeString(unknown.resolve("main.zig"), "pub fn main() void {}\n");
            check.rejects("an unrecognised build system is still refused, not guessed at",
                    "no supported build marker",
                    () -> new ProjectInitializer().initialize(unknown, "HEAD"));
        } finally {
            try (var paths = Files.walk(unknown)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        Path makeOnly = Files.createTempDirectory("warden-init-make-");
        try {
            Files.writeString(makeOnly.resolve("Makefile"), "test:\n\t@echo ok\n");
            new ProjectInitializer().initialize(makeOnly, "HEAD");
            ConfigLoader.Loaded loaded = new ConfigLoader().load(makeOnly, "example");
            check.eq("file-only fallback scope is valid", java.util.List.of("Makefile"),
                    loaded.resolved().scopePaths());
        } finally {
            try (var paths = Files.walk(makeOnly)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
