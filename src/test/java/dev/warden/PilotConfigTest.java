package dev.warden;

import dev.warden.config.*;
import dev.warden.process.ProcessRunner;
import dev.warden.run.CallPlan;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** The shipped pilot files must resolve through the real parser and dry-run path. */
public final class PilotConfigTest implements Suite {
    public String name() { return "pilot-config"; }

    public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-pilot-config-");
        try {
            Path root = sandbox.resolve("project");
            Path home = sandbox.resolve("home");
            Files.createDirectories(root.resolve(".warden/tasks"));
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("src/example.txt"), "baseline\n");
            Files.writeString(root.resolve(".gitignore"), ".warden/runs/\n");
            Files.writeString(root.resolve(".warden/project.yaml"), """
                    version: 1
                    project: pilot-fixture
                    base_ref: HEAD
                    checks:
                      pilot-acceptance: ["git diff --check"]
                    scopes:
                      pilot-scope: ["src"]
                    defaults:
                      checks: pilot-acceptance
                      risk: medium
                    """);
            Files.copy(Path.of("examples/pilot/task.yaml"), root.resolve(".warden/tasks/pilot-task.yaml"));
            ProcessRunner processes = new ProcessRunner();
            for (List<String> command : List.of(List.of("git", "init", "-q"), List.of("git", "add", "."),
                    List.of("git", "-c", "user.name=Pilot test", "-c", "user.email=pilot@example.invalid",
                            "commit", "-qm", "test baseline"))) {
                var result = processes.run(command, root, Duration.ofSeconds(15));
                if (!result.ok()) throw new IllegalStateException(result.stderr());
            }
            new UserSetup().run(home);
            Files.copy(Path.of("examples/pilot/policy.yaml"), home.resolve("policy.yaml"),
                    StandardCopyOption.REPLACE_EXISTING);
            // A dry run must never execute these commands. Profiles are synthetic, not
            // evidence that a user's provider/model or quota is available.
            profile(home, "pilot-implement", "implementer", "writer", false);
            profile(home, "pilot-review", "reviewer", "reader", true);
            UserConfig user = UserConfig.load(home);
            ConfigLoader.Loaded loaded = new ConfigLoader().load(root, "pilot-task");
            check.eq("pilot has one repair", 1L, loaded.resolved().maxFixAttempts());
            check.eq("pilot caps four calls", 4L, loaded.resolved().budget().maxRoleRuns());
            check.eq("pilot stops on quota", "stop", user.policy().failoverMode());
            check.that("review stays mandatory at low risk", user.policy().reviewRequired("low"));
            check.that("reviewer must be independent", user.policy().roles().get("reviewer").requireIndependentVendor());
            CallPlan plan = new CallPlan(user.policy().workflow(), stage -> false);
            check.eq("clean pilot needs two calls", 2, plan.minimumToFinish());
            check.eq("repairing a review reserves writer and re-review", 2, plan.reserveForRepair(2));
            check.eq("review repair reruns gates", List.of("gates"),
                    user.policy().workflow().recheckBefore(2).stream().map(Workflow.Stage::name).toList());
            TaskLoop.Outcome preview = new TaskLoop(processes).run(loaded, user, "pilot-preview", true);
            check.that("pilot files pass the actual dry-run path", preview.ok());
            check.eq("dry run spends no calls", 0L, preview.summaryReport().get("role_runs"));
            check.eq("dry run leaves the target file unchanged", "baseline\n", Files.readString(root.resolve("src/example.txt")));
        } finally {
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void profile(Path home, String name, String role, String vendor, boolean readOnly) throws Exception {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: never-execute-pilot-test
                runner: direct
                read_only: %s
                prompt_delivery: stdin
                limits:
                  wall_clock_minutes: 8
                prompt_template: prompts/%s.md
                json_schema: schemas/%s.json
                """.formatted(name, role, vendor, readOnly, role, role));
    }
}
