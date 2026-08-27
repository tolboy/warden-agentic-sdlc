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
            check.that("generated task is fail-closed for landing", !loaded.resolved().authority().land());
            check.rejects("init refuses overwrite", "never overwrites", () ->
                    new ProjectInitializer().initialize(root, "origin/main"));
        } finally {
            try (var paths = Files.walk(root)) {
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
