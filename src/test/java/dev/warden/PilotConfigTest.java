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

/**
 * The shipped pilot files must resolve through the real parser and dry-run path.
 *
 * Resolving includes the roster. A preview whose roles come back `role_unresolved` still
 * finishes as `dry_run`, so asserting only that it finished says nothing about whether the
 * live trial would dispatch anyone. This suite once passed that way with both of its synthetic
 * profiles refused: one unverified, one whose executable did not exist.
 */
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
            // A dry run never executes these commands: resolution only asks whether the
            // executable is on the path, and git is on it wherever this suite can run at all.
            // Synthetic profiles are not evidence that a provider, model or quota is available.
            profile(home, "pilot-implement", "implementer", "writer", false, true);
            profile(home, "pilot-review", "reviewer", "reader", true, true);
            UserConfig user = UserConfig.load(home);
            ConfigLoader.Loaded loaded = new ConfigLoader().load(root, "pilot-task");
            check.eq("pilot has one repair", 1L, loaded.resolved().maxFixAttempts());
            check.eq("pilot caps four calls", 4L, loaded.resolved().budget().maxRoleRuns());
            check.eq("a gate run gets a shared timeout a real baseline can fit in", 30L,
                    loaded.resolved().timeoutMinutes());
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
            check.eq("and the preview names nothing a real run would stop for", null,
                    preview.summaryReport().get("would_stop"));
            check.eq("the writer resolves to the pilot profile", "pilot-implement",
                    step(preview, "implementer").get("profile"));
            check.eq("the reviewer resolves to the pilot profile", "pilot-review",
                    step(preview, "reviewer").get("profile"));
            check.eq("dry run spends no calls", 0L, preview.summaryReport().get("role_runs"));
            check.eq("dry run leaves the target file unchanged", "baseline\n", Files.readString(root.resolve("src/example.txt")));

            // The same files with a reviewer nobody verified. The preview still finishes, since
            // it has no candidate to protect, but it has to say the live run would stop — before
            // the writer is paid, now that readers are checked first — and why, rather than
            // leave the refusal inside its steps.
            profile(home, "pilot-review", "reviewer", "reader", true, false);
            TaskLoop.Outcome refused = new TaskLoop(processes)
                    .run(loaded, UserConfig.load(home), "pilot-unresolved", true);
            check.eq("an unresolvable roster still previews", "dry_run", refused.reason());
            check.eq("but names the stop the live run would reach", "independent_review_unavailable",
                    refused.summaryReport().get("would_stop"));
            check.contains("and why the profile was refused",
                    String.valueOf(refused.summaryReport().get("resolution")), "profile_unverified");
        } finally {
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(TaskLoop.Outcome outcome, String name) {
        if (outcome.summaryReport().get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && name.equals(map.get("step"))) {
                    return (Map<String, Object>) map;
                }
            }
        }
        return Map.of();
    }

    private static void profile(Path home, String name, String role, String vendor, boolean readOnly,
                                boolean verified) throws Exception {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: git
                runner: direct
                read_only: %s
                prompt_delivery: stdin
                limits:
                  wall_clock_minutes: 20
                prompt_template: prompts/%s.md
                json_schema: schemas/%s.json
                """.formatted(name, role, vendor, readOnly, role, role)
                + (verified ? "verification:\n  verified_on: \"2026-09-14\"\n" : ""));
    }
}
