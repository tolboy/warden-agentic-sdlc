package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.PilotPrepareSpec;
import dev.warden.config.UserConfig;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.PilotPrepareCommand;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Offline {@code warden pilot prepare}: a bundle from explicit inputs, validated with the
 * real parsers, with no vendor call and no mutation of the source, target or global home.
 */
public final class PilotPrepareTest implements Suite {
    @Override public String name() { return "pilot-prepare"; }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-pilot-prepare-");
        Path global = UserConfig.defaultHome();
        HomeSnap globalBefore = snapshotHome(global);
        try {
            happyPath(check, sandbox, global, globalBefore);
            posixShellInjectionIsNotExecuted(check, sandbox, global, globalBefore);
            refusalCases(check, sandbox, global, globalBefore);
            publishOutlastsABusyTree(check, sandbox);
        } finally {
            deleteTree(sandbox);
        }
    }

    private static void happyPath(Check check, Path sandbox, Path global, HomeSnap globalBefore)
            throws Exception {
        Path sourceHome = sandbox.resolve("source-home");
        Path target = sandbox.resolve("target-repo");
        Path output = sandbox.resolve("bundle");
        Path marker = writeMarker(target);
        writeSourceHome(sourceHome, marker.toString(), true, true, "writer", "reader", false);
        gitRepo(target);
        Path specFile = sandbox.resolve("spec.json");
        String goal = "Починить проверку глав — 8 ≠ 6\nKeep \"quotes\" and a second line";
        Files.writeString(specFile, Json.writePretty(validSpec(target, sourceHome, goal,
                List.of("git diff --check"),
                List.of(marker.toString()),
                marker.toString(), 4, 1)), StandardCharsets.UTF_8);

        Map<String, String> sourceBefore = hashTree(sourceHome);
        Map<String, String> targetBefore = hashTree(target);
        Path executed = marker.getParent().resolve("EXECUTED.marker");

        PilotPrepareCommand.Outcome outcome = new PilotPrepareCommand().run(new String[] {
                "pilot", "prepare", "--spec", specFile.toString(), "--output", output.toString()
        });
        check.eq("prepare succeeds", "prepared", outcome.ok() ? "prepared" : outcome.report().get("message"));
        check.eq("code is prepared", PilotPrepareCommand.PREPARED_CODE, outcome.code());
        check.that("bundle directory exists", Files.isDirectory(output));
        check.that("the marker command was not executed", !Files.exists(executed));
        check.eq("source configuration home is unchanged", sourceBefore, hashTree(sourceHome));
        check.eq("target checkout is unchanged", targetBefore, hashTree(target));
        check.that("no .warden was installed into the target",
                !Files.exists(target.resolve(".warden")));
        assertGlobalUnchanged(check, global, globalBefore, "happy path");

        ConfigLoader.Loaded loaded = new ConfigLoader().load(output.resolve("target"), "pilot-task");
        check.eq("unicode/quotes/newlines round-trip into the task goal", goal, loaded.resolved().goal());
        check.eq("green baseline stays the declared baseline",
                List.of("git diff --check"), loaded.resolved().baselineCommands());
        check.eq("prepare writes the declared reproduction into the task",
                List.of(marker.toString()), loaded.resolved().reproduceCommands());
        @SuppressWarnings("unchecked")
        Map<String, Object> reproduction = (Map<String, Object>) outcome.report().get("reproduction_check");
        check.eq("prepare delegates red proof enforcement to the live run", "enforced_by_warden_run",
                reproduction.get("status"));
        check.contains("runbook explains refusal on a green base",
                Files.readString(output.resolve("RUNBOOK.md")), "refuse to dispatch");
        check.that("red acceptance is not in the green baseline",
                !loaded.resolved().baselineCommands().contains(marker.toString()));
        check.that("red acceptance is in the resolved gate",
                loaded.resolved().acceptanceCommands().contains(marker.toString()));
        check.eq("one repair", 1L, loaded.resolved().maxFixAttempts());
        check.eq("four-call cap", 4L, loaded.resolved().budget().maxRoleRuns());
        check.eq("no execution deadline is invented when the spec declares none", null,
                loaded.resolved().budget().maxElapsedMinutes());
        Map<String, Object> deadlineSpec = validSpec(target, sourceHome, "goal",
                List.of("git diff --check"), List.of(marker.toString()), marker.toString(), 4, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> deadlineBudgets = (Map<String, Object>)
                ((Map<String, Object>) deadlineSpec.get("task")).get("budgets");
        deadlineBudgets.put("max_elapsed_minutes", 120L);
        Path deadlineFile = sandbox.resolve("deadline-spec.json");
        Files.writeString(deadlineFile, Json.writePretty(deadlineSpec), StandardCharsets.UTF_8);
        Path deadlineOutput = sandbox.resolve("deadline-bundle");
        PilotPrepareCommand.Outcome deadline = new PilotPrepareCommand().run(new String[] {
                "pilot", "prepare", "--spec", deadlineFile.toString(), "--output", deadlineOutput.toString()
        });
        check.eq("a declared deadline prepares", "prepared",
                deadline.ok() ? "prepared" : deadline.report().get("message"));
        check.eq("and is written into the task", 120L, new ConfigLoader()
                .load(deadlineOutput.resolve("target"), "pilot-task").resolved().budget().maxElapsedMinutes());

        UserConfig user = UserConfig.load(output.resolve("config"));
        check.that("isolated home loads the remapped writer",
                user.profiles().containsKey("pilot-implement"));
        check.that("isolated home loads the remapped reviewer",
                user.profiles().containsKey("pilot-review"));
        check.eq("unrelated profiles were not copied", 2, user.profiles().size());
        check.that("writer stays writable", !user.profiles().get("pilot-implement").readOnly());
        check.that("reviewer stays read-only", user.profiles().get("pilot-review").readOnly());
        check.that("verified_on was copied, not invented",
                user.profiles().get("pilot-implement").verified()
                        && user.profiles().get("pilot-review").verified());
        check.contains("copied writer yaml still has the source stamp",
                Files.readString(output.resolve("config/profiles/pilot-implement.yaml")),
                "verified_on: \"2026-09-14\"");
        check.eq("model is unchanged", "fixture-writer", user.profiles().get("pilot-implement").model());
        check.eq("effort is unchanged", "xhigh", user.profiles().get("pilot-implement").effort());
        check.that("nested schema $ref was copied",
                Files.isRegularFile(output.resolve("config/schemas/implementer-defs.json")));
        check.that("nested prompt path was copied",
                Files.isRegularFile(output.resolve("config/prompts/nested/writer.md")));
        check.eq("policy stops on quota", "stop", user.policy().failoverMode());
        check.that("reviewer must be independent",
                user.policy().roles().get("reviewer").requireIndependentVendor());

        check.eq("status is prepared_not_run", "prepared_not_run", outcome.report().get("status"));
        check.eq("does not claim the target was mutated", Boolean.FALSE,
                outcome.report().get("target_checkout_mutated"));
        @SuppressWarnings("unchecked")
        Map<String, Object> notProven = (Map<String, Object>) outcome.report().get("not_proven");
        check.eq("live readiness is not claimed", "not_proven", notProven.get("live_readiness"));
        check.contains("runbook names the install path",
                Files.readString(output.resolve("RUNBOOK.md")),
                target.resolve(".warden").toString());
        check.contains("runbook uses a fresh preview id",
                Files.readString(output.resolve("RUNBOOK.md")), "pilot-preview-");
        check.that("prepare-report is on disk",
                Files.isRegularFile(output.resolve("prepare-report.json")));
    }

    /**
     * Forces the POSIX PATH walk (even on Windows) with a profile command that would create
     * a marker if interpolated into {@code sh -c}. The Windows {@code where.exe} side-effect
     * fixture in {@link #happyPath} is unchanged.
     */
    @SuppressWarnings("unchecked")
    private static void posixShellInjectionIsNotExecuted(Check check, Path sandbox, Path global,
                                                         HomeSnap globalBefore) throws Exception {
        Path sourceHome = sandbox.resolve("posix-shell-home");
        Path target = sandbox.resolve("posix-shell-target");
        Path output = sandbox.resolve("posix-shell-bundle");
        Files.createDirectories(target.resolve("src"));
        Files.writeString(target.resolve("src/example.txt"), "baseline\n");
        gitRepo(target);

        String markerName = "PILOT_PREPARE_EXECUTED-" + UUID.randomUUID() + ".marker";
        Path cwdMarker = Path.of(markerName).toAbsolutePath().normalize();
        Path targetMarker = target.resolve(markerName);
        Path sandboxMarker = sandbox.resolve(markerName);
        Files.deleteIfExists(cwdMarker);

        String payload = "git; touch " + markerName;
        writeSourceHome(sourceHome, payload, true, true, "writer", "reader", false);
        Path specFile = sandbox.resolve("posix-shell-spec.json");
        Files.writeString(specFile, Json.writePretty(validSpec(target, sourceHome,
                "posix shell injection must not run",
                List.of("git diff --check"),
                List.of("npm run qa:red"),
                null, 4, 1)), StandardCharsets.UTF_8);

        Map<String, String> sourceBefore = hashTree(sourceHome);
        Map<String, String> targetBefore = hashTree(target);

        PilotPrepareCommand.Outcome outcome = new PilotPrepareCommand(new ProcessRunner(), false)
                .run(new String[] {
                        "pilot", "prepare",
                        "--spec", specFile.toString(),
                        "--output", output.toString()
                });
        try {
            // Published, always: a missing executable is an unresolved note, not a refusal. This
            // used to accept either outcome, and a Windows rename that lost a race with a
            // scanner took the shorter branch, so the suite's check count moved between runs.
            check.eq("POSIX-forced prepare publishes with unresolved PATH notes", "prepared",
                    outcome.ok() ? "prepared" : outcome.report().get("message"));
            check.that("POSIX-forced bundle exists", Files.isDirectory(output));
            boolean published = outcome.ok();
            boolean unpublished = !outcome.ok() && !Files.exists(output);
            if (published) {
                check.eq("POSIX-forced code is prepared", PilotPrepareCommand.PREPARED_CODE, outcome.code());
            }
            check.that("shell metacharacters did not create a cwd marker", !Files.exists(cwdMarker));
            check.that("shell metacharacters did not create a target marker", !Files.exists(targetMarker));
            check.that("shell metacharacters did not create a sandbox marker", !Files.exists(sandboxMarker));
            check.eq("POSIX-forced source home is unchanged", sourceBefore, hashTree(sourceHome));
            check.eq("POSIX-forced target is unchanged", targetBefore, hashTree(target));
            check.that("POSIX-forced prepare did not install .warden into the target",
                    !Files.exists(target.resolve(".warden")));
            assertGlobalUnchanged(check, global, globalBefore, "POSIX shell injection");

            Map<String, Object> observations = (Map<String, Object>) outcome.report().get("observations");
            List<Object> unresolved = observations == null
                    ? List.of()
                    : (List<Object>) observations.get("unresolved_dependencies");
            if (unresolved == null) unresolved = List.of();
            String joined = String.join("\n", unresolved.stream().map(String::valueOf).toList());
            if (published) {
                check.contains("POSIX PATH walk recorded the payload", joined, payload);
                check.contains("POSIX PATH walk says the command was not executed", joined, "not executed");
            }

            Map<String, Object> notRun = (Map<String, Object>) outcome.report().get("not_run");
            if (notRun != null) {
                check.eq("POSIX-forced still did not run vendors", "not_run", notRun.get("vendor_calls"));
                check.eq("POSIX-forced still did not run warden run", "not_run", notRun.get("warden_run"));
                check.eq("POSIX-forced still did not run warden do", "not_run", notRun.get("warden_do"));
                check.eq("POSIX-forced still did not run warden role", "not_run", notRun.get("warden_role"));
                check.eq("POSIX-forced still did not create worktrees", "not_created",
                        notRun.get("worktrees_or_terminals"));
                check.eq("POSIX-forced still did not run acceptance", "not_run",
                        notRun.get("acceptance_commands"));
            } else {
                check.that("a refused POSIX-forced prepare still did not invoke live commands", unpublished);
            }
        } finally {
            Files.deleteIfExists(cwdMarker);
            Files.deleteIfExists(targetMarker);
            Files.deleteIfExists(sandboxMarker);
        }
    }

    private static void refusalCases(Check check, Path sandbox, Path global, HomeSnap globalBefore)
            throws Exception {
        Path sourceHome = sandbox.resolve("refuse-home");
        Path target = sandbox.resolve("refuse-target");
        Path marker = writeMarker(target);
        gitRepo(target);

        writeSourceHome(sourceHome, "git", true, true, "writer", "reader", false);
        refuse(check, sandbox, "unknown-key", target, sourceHome,
                spec -> spec.put("planner", true),
                PilotPrepareCommand.INVALID_SPEC, "unsupported key");

        refuse(check, sandbox, "missing-profile", target, sourceHome,
                spec -> profiles(spec).put("implementer", "does-not-exist"),
                PilotPrepareCommand.PREPARE_REFUSED, "was not found");

        Path unverifiedHome = sandbox.resolve("unverified-home");
        writeSourceHome(unverifiedHome, "git", true, false, "writer", "reader", false);
        refuse(check, sandbox, "unverified", target, unverifiedHome,
                spec -> {}, PilotPrepareCommand.PREPARE_REFUSED, "unverified");

        Path sameVendorHome = sandbox.resolve("same-vendor-home");
        writeSourceHome(sameVendorHome, "git", true, true, "shared", "shared", false);
        refuse(check, sandbox, "same-vendor", target, sameVendorHome,
                spec -> {}, PilotPrepareCommand.PREPARE_REFUSED, "matches the implementer");

        Path writableReviewHome = sandbox.resolve("writable-review-home");
        writeSourceHome(writableReviewHome, "git", false, true, "writer", "reader", false);
        refuse(check, sandbox, "writable-reviewer", target, writableReviewHome,
                spec -> {}, PilotPrepareCommand.PREPARE_REFUSED, "must be read_only");

        Path escapeHome = sandbox.resolve("escape-home");
        writeSourceHome(escapeHome, "git", true, true, "writer", "reader", true);
        refuse(check, sandbox, "path-escape", target, escapeHome,
                spec -> {}, PilotPrepareCommand.PREPARE_REFUSED, "not a safe path");

        Path missingResourceHome = sandbox.resolve("missing-resource-home");
        writeSourceHome(missingResourceHome, "git", true, true, "writer", "reader", false);
        Files.deleteIfExists(missingResourceHome.resolve("prompts/nested/writer.md"));
        refuse(check, sandbox, "missing-resource", target, missingResourceHome,
                spec -> {}, PilotPrepareCommand.PREPARE_REFUSED, "missing or not a regular file");

        refuse(check, sandbox, "reproduction-outside-acceptance", target, sourceHome,
                spec -> spec.put("reproduction", Map.of("command", "not an acceptance command")),
                PilotPrepareCommand.INVALID_SPEC, "must be one of checks.acceptance");

        refuse(check, sandbox, "overlap", target, sourceHome, spec -> {
            checks(spec).put("baseline", List.of("npm run qa:red"));
            checks(spec).put("acceptance", List.of("npm run qa:red"));
        }, PilotPrepareCommand.INVALID_SPEC, "cannot also be a green baseline");

        refuse(check, sandbox, "budget", target, sourceHome, spec -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) spec.get("task");
            @SuppressWarnings("unchecked")
            Map<String, Object> budgets = (Map<String, Object>) task.get("budgets");
            budgets.put("max_role_runs", 2L);
        }, PilotPrepareCommand.PREPARE_REFUSED, "needs at least");

        Path collision = sandbox.resolve("existing-bundle");
        Files.createDirectories(collision);
        Path markerFile = collision.resolve("keep-me.txt");
        Files.writeString(markerFile, "preserve\n");
        String before = Files.readString(markerFile);
        Path specFile = sandbox.resolve("collision-spec.json");
        Files.writeString(specFile, Json.writePretty(validSpec(target, sourceHome, "goal",
                List.of("git diff --check"), List.of("npm run qa:red"), null, 4, 1)));
        PilotPrepareCommand.Outcome collided = new PilotPrepareCommand().run(new String[] {
                "pilot", "prepare", "--spec", specFile.toString(), "--output", collision.toString()
        });
        check.eq("existing output is refused", PilotPrepareCommand.OUTPUT_EXISTS, collided.code());
        check.eq("existing bundle contents are preserved", before, Files.readString(markerFile));
        check.eq("collision report says preserved", Boolean.TRUE, collided.report().get("preserved"));
        check.that("collision did not execute the marker",
                !Files.exists(marker.getParent().resolve("EXECUTED.marker")));

        Path failedOutput = sandbox.resolve("failed-output");
        check.that("a refused prepare does not publish a bundle", !Files.exists(failedOutput)
                || !Files.isRegularFile(failedOutput.resolve("prepare-report.json")));
        Path unverifiedSpec = sandbox.resolve("unverified-spec.json");
        Files.writeString(unverifiedSpec, Json.writePretty(validSpec(target, unverifiedHome, "goal",
                List.of("git diff --check"), List.of("npm run qa:red"), null, 4, 1)));
        Path unpublished = sandbox.resolve("must-not-publish");
        PilotPrepareCommand.Outcome unpublishedOutcome = new PilotPrepareCommand().run(new String[] {
                "pilot", "prepare", "--spec", unverifiedSpec.toString(),
                "--output", unpublished.toString()
        });
        check.that("unverified prepare fails", !unpublishedOutcome.ok());
        check.that("failure does not leave a ready bundle", !Files.exists(unpublished));
        check.eq("failure is not advertised ready", Boolean.FALSE,
                unpublishedOutcome.report().get("ready"));

        assertGlobalUnchanged(check, global, globalBefore, "refusals");
        check.that("refusals did not execute the marker command",
                !Files.exists(marker.getParent().resolve("EXECUTED.marker")));

        check.rejects("parse requires --spec", "--spec",
                () -> PilotPrepareCommand.parse(new String[] {"pilot", "prepare", "--output", "x"}));
        check.contains("CLI help documents the command",
                Files.readString(Path.of("src/main/java/dev/warden/Main.java")),
                "warden pilot prepare --spec FILE --output DIR");
        PilotPrepareSpec sample = PilotPrepareSpec.parse(
                Files.readString(Path.of("examples/pilot/prepare-spec.json")),
                "examples/pilot/prepare-spec.json");
        check.eq("shipped sample spec uses the pilot task id", "pilot-task", sample.taskId());
        check.that("shipped sample keeps baseline and acceptance distinct",
                !sample.baselineCommands().isEmpty() && !sample.acceptanceCommands().isEmpty()
                        && sample.baselineCommands().stream().noneMatch(sample.acceptanceCommands()::contains));
    }

    /**
     * The rename that publishes a bundle, against a filesystem that says "in use" first.
     * Windows does exactly that to a tree written a moment ago while a scanner holds a
     * handle in it, and the prepare used to fail on the first refusal one run in three to ten.
     */
    private static void publishOutlastsABusyTree(Check check, Path sandbox) throws Exception {
        Path staging = Files.createDirectories(sandbox.resolve("publish-staging"));
        Files.writeString(staging.resolve("RUNBOOK.md"), "# runbook\n");
        Path output = sandbox.resolve("publish-output");
        int[] calls = {0};
        PilotPrepareCommand.publish(staging, output, (from, to) -> {
            if (++calls[0] <= 2) throw new AccessDeniedException(from + " -> " + to);
            Files.move(from, to);
        }, 5, Duration.ofMillis(1));
        check.eq("a busy tree is renamed on the third attempt", 3, calls[0]);
        check.that("and the bundle is published", Files.isRegularFile(output.resolve("RUNBOOK.md")));

        Path stuck = Files.createDirectories(sandbox.resolve("publish-stuck"));
        int[] stuckCalls = {0};
        check.rejects("a tree that stays busy is refused after the last attempt", "publish-stuck",
                () -> PilotPrepareCommand.publish(stuck, sandbox.resolve("publish-never"), (from, to) -> {
                    stuckCalls[0]++;
                    throw new AccessDeniedException(from.toString());
                }, 4, Duration.ofMillis(1)));
        check.eq("attempts are bounded", 4, stuckCalls[0]);

        int[] existsCalls = {0};
        check.rejects("an output that exists is never retried or replaced", "publish-output",
                () -> PilotPrepareCommand.publish(stuck, output, (from, to) -> {
                    existsCalls[0]++;
                    throw new AccessDeniedException(to.toString());
                }, 4, Duration.ofMillis(1)));
        check.eq("a denial with the output present is final", 1, existsCalls[0]);

        int[] otherCalls = {0};
        check.rejects("any other failure is not retried", "disk full",
                () -> PilotPrepareCommand.publish(stuck, sandbox.resolve("publish-other"), (from, to) -> {
                    otherCalls[0]++;
                    throw new java.io.IOException("disk full");
                }, 4, Duration.ofMillis(1)));
        check.eq("one attempt for a failure that is not a busy tree", 1, otherCalls[0]);
    }

    @SuppressWarnings("unchecked")
    private static void refuse(Check check, Path sandbox, String name, Path target, Path sourceHome,
                               SpecEdit edit, String code, String fragment) throws Exception {
        Map<String, Object> spec = validSpec(target, sourceHome, "goal " + name,
                List.of("git diff --check"), List.of("npm run qa:red"), null, 4, 1);
        edit.apply(spec);
        Path specFile = sandbox.resolve(name + ".json");
        Path output = sandbox.resolve(name + "-out");
        Files.writeString(specFile, Json.writePretty(spec), StandardCharsets.UTF_8);
        Map<String, String> sourceBefore = Files.isDirectory(sourceHome) ? hashTree(sourceHome) : Map.of();
        Map<String, String> targetBefore = hashTree(target);
        PilotPrepareCommand.Outcome outcome = new PilotPrepareCommand().run(new String[] {
                "pilot", "prepare", "--spec", specFile.toString(), "--output", output.toString()
        });
        check.that(name + " fails", !outcome.ok());
        check.eq(name + " code", code, outcome.code());
        check.contains(name + " diagnostic", String.valueOf(outcome.report().get("message")), fragment);
        check.that(name + " does not publish a ready bundle", !Files.exists(output));
        if (Files.isDirectory(sourceHome)) {
            check.eq(name + " leaves the source home unchanged", sourceBefore, hashTree(sourceHome));
        }
        check.eq(name + " leaves the target unchanged", targetBefore, hashTree(target));
    }

    private static Map<String, Object> validSpec(Path target, Path sourceHome, String goal,
                                                 List<String> baseline, List<String> acceptance,
                                                 String reproduction, long maxRoleRuns,
                                                 long maxFixAttempts) {
        Map<String, Object> budgets = new LinkedHashMap<>();
        budgets.put("max_role_runs", maxRoleRuns);
        budgets.put("max_cost_usd", 10.0);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "pilot-task");
        task.put("goal", goal);
        task.put("non_goals", List.of("Do not modify Warden"));
        task.put("risk", "medium");
        task.put("scope", List.of("src"));
        task.put("timeout_minutes", 30L);
        task.put("max_fix_attempts", maxFixAttempts);
        task.put("budgets", budgets);
        Map<String, Object> targetNode = new LinkedHashMap<>();
        targetNode.put("project", "fixture");
        targetNode.put("root", target.toString());
        targetNode.put("base", "HEAD");
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("baseline", baseline);
        checks.put("acceptance", acceptance);
        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put("source_home", sourceHome.toString());
        profiles.put("implementer", "fixture-implement");
        profiles.put("reviewer", "fixture-review");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", 1L);
        spec.put("task", task);
        spec.put("target", targetNode);
        spec.put("checks", checks);
        if (reproduction != null) {
            spec.put("reproduction", Map.of("command", reproduction));
        }
        spec.put("profiles", profiles);
        return spec;
    }

    private static void writeSourceHome(Path home, String command, boolean reviewerReadOnly,
                                        boolean verified, String writerVendor, String reviewerVendor,
                                        boolean escapePrompt) throws Exception {
        Files.createDirectories(home.resolve("profiles"));
        Files.createDirectories(home.resolve("prompts/nested"));
        Files.createDirectories(home.resolve("schemas"));
        Files.writeString(home.resolve("prompts/nested/writer.md"), "# writer\n");
        Files.writeString(home.resolve("prompts/reviewer.md"), "# reviewer\n");
        // A $ref with a fragment, and a document-local one: both are valid JSON Schema, and
        // neither names a file of its own. Found by the review of this change.
        Files.writeString(home.resolve("schemas/implementer.json"),
                "{\"title\":\"implementer\",\"$ref\":\"implementer-defs.json#/$defs/thing\","
                        + "\"properties\":{\"summary\":{\"$ref\":\"#/$defs/text\"}},"
                        + "\"$defs\":{\"text\":{\"type\":\"string\"}}}\n");
        Files.writeString(home.resolve("schemas/implementer-defs.json"),
                "{\"$defs\":{\"thing\":{\"type\":\"object\"}}}\n");
        Files.writeString(home.resolve("schemas/reviewer.json"), "{\"title\":\"reviewer\"}\n");
        String prompt = escapePrompt ? "../outside.md" : "prompts/nested/writer.md";
        if (escapePrompt) {
            Files.writeString(home.getParent().resolve("outside.md"), "secret\n");
        }
        profile(home, "fixture-implement", "implementer", writerVendor, false, command,
                prompt, "schemas/implementer.json", verified);
        profile(home, "fixture-review", "reviewer", reviewerVendor, reviewerReadOnly, command,
                "prompts/reviewer.md", "schemas/reviewer.json", verified);
        profile(home, "unrelated", "planner", "other", true, command,
                "prompts/reviewer.md", "schemas/reviewer.json", verified);
    }

    private static void profile(Path home, String name, String role, String vendor, boolean readOnly,
                                String command, String prompt, String schema, boolean verified)
            throws Exception {
        String effortBlock = """
                model: fixture-%s
                effort: xhigh
                args: ["--reasoning-effort", "{{effort}}"]
                """.formatted("implementer".equals(role) ? "writer" : "reviewer");
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: %s
                runner: direct
                read_only: %s
                %sprompt_delivery: stdin
                limits:
                  wall_clock_minutes: 20
                prompt_template: %s
                json_schema: %s
                """.formatted(name, role, vendor, quoteYaml(command), readOnly, effortBlock, prompt, schema)
                + (verified ? "verification:\n  verified_on: \"2026-09-14\"\n" : ""));
    }

    private static String quoteYaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path writeMarker(Path target) throws Exception {
        Files.createDirectories(target.resolve("src"));
        Files.createDirectories(target.resolve("tools"));
        Files.writeString(target.resolve("src/example.txt"), "baseline\n");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = target.resolve(windows ? "tools/side-effect.cmd" : "tools/side-effect.sh");
        if (windows) {
            Files.writeString(script, """
                    @echo off
                    echo executed>"%~dp0EXECUTED.marker"
                    """);
        } else {
            Files.writeString(script, """
                    #!/bin/sh
                    echo executed > "$(dirname "$0")/EXECUTED.marker"
                    """);
            script.toFile().setExecutable(true);
        }
        return script;
    }

    private static void gitRepo(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.email", "pilot@example.invalid");
        git(root, "config", "user.name", "pilot");
        git(root, "add", "-A");
        git(root, "commit", "-qm", "fixture base");
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.Result result = new ProcessRunner().run(command, cwd, Duration.ofSeconds(30));
        if (!result.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
    }

    private static Map<String, String> hashTree(Path root) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.exists(root)) return hashes;
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(root.relativize(file).toString().replace('\\', '/'),
                        GitRepository.contentSha256(file));
            }
        }
        return hashes;
    }

    private record HomeSnap(boolean exists, String policyHash, List<String> profileNames) {}

    private static HomeSnap snapshotHome(Path home) throws Exception {
        if (!Files.isDirectory(home)) return new HomeSnap(false, null, List.of());
        Path policy = home.resolve("policy.yaml");
        String hash = Files.isRegularFile(policy) ? GitRepository.contentSha256(policy) : null;
        Path profiles = home.resolve("profiles");
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(profiles)) {
            try (var entries = Files.list(profiles)) {
                entries.map(path -> path.getFileName().toString()).sorted().forEach(names::add);
            }
        }
        return new HomeSnap(true, hash, List.copyOf(names));
    }

    private static void assertGlobalUnchanged(Check check, Path global, HomeSnap before, String when)
            throws Exception {
        HomeSnap after = snapshotHome(global);
        check.eq("global home existence unchanged after " + when, before.exists(), after.exists());
        check.eq("global policy.yaml unchanged after " + when, before.policyHash(), after.policyHash());
        check.eq("global profiles unchanged after " + when, before.profileNames(), after.profileNames());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profiles(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("profiles");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checks(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("checks");
    }

    @FunctionalInterface
    private interface SpecEdit { void apply(Map<String, Object> spec) throws Exception; }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
