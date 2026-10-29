package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.config.ConfigLoader;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.execution.RoleExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** A real child-JVM shutdown must leave a continuation that does not dispatch the writer again. */
public final class ShutdownResumeTest implements Suite {
    @Override public String name() { return "shutdown-resume"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-shutdown-resume-");
        Path root = scratch.resolve("project");
        Path home = scratch.resolve("home");
        fixture(root, home, scratch);
        Path log = scratch.resolve("controller.log");
        Process child = new ProcessBuilder(command("controller", root.toString(), home.toString(),
                scratch.toString())).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            if (!child.waitFor(45, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
                throw new IllegalStateException("shutdown fixture did not exit: " + Files.readString(log));
            }
            check.eq("controller exits while its review is in progress", 130, child.exitValue());
            Path summary = root.resolve(".warden/runs/interrupted/task-run.json");
            check.that("shutdown hook wrote the interrupted run receipt", Files.isRegularFile(summary));
            if (!Files.isRegularFile(summary)) {
                throw new IllegalStateException("missing shutdown receipt: " + Files.readString(log));
            }
            Map<?, ?> stopped = (Map<?, ?>) Json.parse(Files.readString(summary));
            check.eq("shutdown is an infrastructure interruption", "controller_interrupted", stopped.get("reason"));
            check.that("shutdown preserves the writer's candidate", Files.isRegularFile(root.resolve("src/result.txt")));
            check.eq("candidate content survives shutdown", "ok\n", Files.readString(root.resolve("src/result.txt")));
            check.eq("writer completed once before shutdown", 1, count(scratch.resolve("implement.count")));
            check.eq("review was entered before shutdown", 1, count(scratch.resolve("review.count")));
            List<?> steps = (List<?>) stopped.get("steps");
            check.that("receipt retains the finished implement stage", steps.stream().anyMatch(row ->
                    row instanceof Map<?, ?> step && "implement".equals(step.get("stage"))));
            check.that("unfinished review has no completed receipt", steps.stream().noneMatch(row ->
                    row instanceof Map<?, ?> step && "review".equals(step.get("stage"))));
            int gatesBefore = count(scratch.resolve("gates.count"));
            check.that("machine checks completed before interruption", gatesBefore > 0);

            ApprovalStore decisions = new ApprovalStore(root);
            var pending = decisions.read("interrupted");
            check.eq("interruption leaves the decision pending", "pending", pending.state().jsonValue());
            decisions.resolve("interrupted", pending.updatedAt().toString(), "retry", "operator", "resume local fixture");
            TaskLoop.Outcome resumed = loop(root, home, scratch, false).run(
                    new ConfigLoader().load(root, "hello"), UserConfig.load(home), "resumed", false,
                    Map.of(), new TaskLoop.Continuation("interrupted", null, true));
            check.that("continuation completes the mandatory chain", resumed.ok());
            check.eq("continuation reaches the human gate", "human_gate", resumed.nextAction());
            check.eq("continuation reuses the finished implementer", Map.of("from", "interrupted",
                    "stages", List.of("implement")), resumed.summaryReport().get("reused_judgements"));
            check.eq("writer is dispatched only once across shutdown and resume", 1,
                    count(scratch.resolve("implement.count")));
            check.eq("unfinished reviewer is dispatched again", 2, count(scratch.resolve("review.count")));
            check.that("continuation reruns machine gates", count(scratch.resolve("gates.count")) > gatesBefore);
            check.eq("successful continuation still needs a human decision", "pending",
                    decisions.read("resumed").state().jsonValue());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static TaskLoop loop(Path root, Path home, Path scratch, boolean interrupt) {
        return new TaskLoop(new ProcessRunner()).withOrcaGate(false)
                .withExecutors((profile, processes, git) -> request -> {
                    boolean writer = "implementer".equals(request.role());
                    increment(scratch.resolve(writer ? "implement.count" : "review.count"));
                    if (writer) {
                        Files.writeString(root.resolve("src/result.txt"), "ok\n");
                    } else if (interrupt) {
                        Files.writeString(scratch.resolve("review.entered"), "entered");
                        // The controller exits from its watchdog while the real TaskLoop is
                        // blocked here. Only production shutdown hooks can conclude the run.
                        while (true) Thread.sleep(1_000);
                    }
                    Map<String, Object> artifact = writer
                            ? Map.of("role", "implementer", "task_id", "hello", "status", "completed",
                                    "summary", "local writer", "files_changed", List.of("src/result.txt"))
                            : Map.of("role", "reviewer", "task_id", "hello", "status", "completed",
                                    "summary", "local review", "verdict", "pass", "findings", List.of());
                    return new RoleExecutor.Result(true, "role_ok", Duration.ofMillis(1), Json.write(artifact),
                            artifact, Map.of("exit_code", 0L, "vendor_turn_started", true));
                });
    }

    private static void fixture(Path root, Path home, Path scratch) throws Exception {
        new UserSetup().run(home);
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.writeString(root.resolve("src/base.txt"), "baseline\n");
        Files.writeString(root.resolve(".gitignore"), ".warden/\n");
        String gateInvocation = command("gate", scratch.resolve("gates.count").toString()).stream()
                .map(value -> "\"" + value + "\"").collect(Collectors.joining(" "));
        // cmd /c strips a leading quoted executable differently from an ordinary batch
        // line. Keep the platform's quoting inside a fixture script, as a project would.
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Files.writeString(root.resolve(windows ? ".warden/gate.cmd" : ".warden/gate.sh"),
                (windows ? "@echo off\r\n" : "#!/bin/sh\n") + gateInvocation + "\n");
        String gate = windows ? "call .warden\\gate.cmd" : "sh .warden/gate.sh";
        Files.writeString(root.resolve(".warden/project.yaml"), "version: 1\nproject: shutdown-resume\n"
                + "base_ref: HEAD\nchecks: {pass: [" + Json.write(gate) + "]}\n");
        Files.writeString(root.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt containing ok
                risk: medium
                scope: [src]
                max_fix_attempts: 0
                budgets: {max_role_runs: 6, max_cost_usd: 1, cost_cap: off}
                """);
        Files.writeString(home.resolve("profiles/shutdown-impl.yaml"), profile("shutdown-impl", "implementer", false));
        Files.writeString(home.resolve("profiles/shutdown-review.yaml"), profile("shutdown-review", "reviewer", true));
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: {profiles: [shutdown-impl], strategy: first}
                  reviewer: {profiles: [shutdown-review], strategy: first, require_independent_vendor: true}
                review: {required_for_risk: [medium, high]}
                workflow:
                  stages:
                    - {stage: implement, run: role, role: implementer, on_fail: stop}
                    - {stage: gates, run: machine_gates, on_fail: stop}
                    - {stage: review, run: role, role: reviewer, on_fail: stop}
                """);
        ProcessRunner processes = new ProcessRunner();
        for (List<String> args : List.of(List.of("init", "--quiet"),
                List.of("config", "user.email", "test@example.invalid"),
                List.of("config", "user.name", "Warden Test"), List.of("add", "."),
                List.of("commit", "--quiet", "-m", "test: shutdown resume fixture"))) {
            List<String> command = new ArrayList<>();
            command.add("git"); command.addAll(args);
            if (!processes.run(command, root, Duration.ofSeconds(15)).ok()) {
                throw new IllegalStateException("could not prepare shutdown fixture");
            }
        }
    }

    private static String profile(String name, String role, boolean readOnly) {
        return "version: 1\nprofile: " + name + "\nrole: " + role + "\nvendor: " + name
                + "\ncommand: java\nargs: ['-version']\nread_only: " + readOnly
                + "\nprompt_template: prompts/" + role + ".md\njson_schema: schemas/" + role
                + ".json\nverification: {verified_on: '2026-10-29'}\n";
    }

    private static List<String> command(String... args) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(java.io.File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(executable, "-cp", cp, ShutdownResumeTest.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    private static int count(Path path) throws Exception {
        return Files.isRegularFile(path) ? Integer.parseInt(Files.readString(path).strip()) : 0;
    }

    private static void increment(Path path) throws Exception {
        Files.writeString(path, Integer.toString(count(path) + 1));
    }

    /** System.exit runs shutdown hooks on Windows too; Process.destroy uses TerminateProcess there. */
    public static void main(String[] args) throws Exception {
        if ("gate".equals(args[0])) {
            increment(Path.of(args[1]));
            return;
        }
        if (!"controller".equals(args[0])) throw new IllegalArgumentException("unknown shutdown helper mode");
        Path root = Path.of(args[1]), home = Path.of(args[2]), scratch = Path.of(args[3]);
        Thread watchdog = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!Files.exists(scratch.resolve("review.entered"))) {
                    if (System.nanoTime() > deadline) Runtime.getRuntime().halt(2);
                    Thread.sleep(20);
                }
                System.exit(130);
            } catch (InterruptedException failed) {
                Thread.currentThread().interrupt();
            }
        }, "local-controller-stop");
        watchdog.setDaemon(true);
        watchdog.start();
        var result = loop(root, home, scratch, true).run(new ConfigLoader().load(root, "hello"),
                UserConfig.load(home), "interrupted", false);
        throw new IllegalStateException("shutdown fixture unexpectedly completed: " + result.reason());
    }
}
