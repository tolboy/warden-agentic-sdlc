package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.execution.DirectCliExecutor;
import dev.warden.execution.ExecutionActivity;
import dev.warden.execution.LiveCliOutput;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Offline processes prove live delivery and independence from a stalled projection. */
public final class LiveCliOutputTest implements Suite {
    private static final String SECRET = "DO_NOT_PROJECT_AUTH_OR_REASONING";
    private static final String START = Json.write(java.util.Map.of("type", "item.started", "item",
            java.util.Map.of("type", "command_execution", "command", "echo " + SECRET)));
    private static final String END = Json.write(java.util.Map.of("type", "item.completed", "item",
            java.util.Map.of("type", "command_execution", "exit_code", 0, "aggregated_output", SECRET)));

    @Override public String name() { return "live-cli-output"; }

    @Override public void run(Check check) throws Exception {
        for (String behavior : List.of("normal", "blocked", "throwing")) {
            Path root = Files.createTempDirectory("warden-live-output-");
            Path release = root.resolve("release");
            Path launches = root.resolve("launches");
            var observed = new CopyOnWriteArrayList<ExecutionActivity.Update>();
            CountDownLatch tool = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            AtomicReference<RoleExecutor.Result> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ProcessRunner processes = new ProcessRunner();
            Thread controller = Thread.ofVirtual().start(() -> {
                try {
                    result.set(new DirectCliExecutor(processes, new GitRepository(root, processes))
                            .execute(request(root, release, launches), event -> {
                                observed.add(event);
                                if (event.kind() != ExecutionActivity.Kind.TOOL_STARTED) return;
                                tool.countDown();
                                if (behavior.equals("throwing")) throw new IllegalStateException(SECRET);
                                if (behavior.equals("blocked")) {
                                    boolean interrupted = false;
                                    while (true) {
                                        try { unblock.await(); break; }
                                        catch (InterruptedException ignored) { interrupted = true; }
                                    }
                                    if (interrupted) Thread.currentThread().interrupt();
                                }
                            }));
                } catch (Throwable error) { failure.set(error); }
            });
            try {
                check.that(behavior + " tool event arrives before child completion", tool.await(10, TimeUnit.SECONDS));
                check.that(behavior + " original process is still running", controller.isAlive() && result.get() == null);
                Files.writeString(release, "finish original process");
                controller.join(Duration.ofSeconds(8));
                check.that(behavior + " UI cannot hold the execution result", !controller.isAlive());
                check.eq(behavior + " observer failure is isolated", null, failure.get());
                if (result.get() != null) {
                    check.eq(behavior + " authoritative artifact is unchanged", "ok", result.get().code());
                    check.eq(behavior + " authoritative cost is unchanged", 0.25, result.get().evidence().get("cost_usd"));
                    check.contains(behavior + " original raw stdout is preserved", Files.readString(root.resolve("raw/reviewer.stdout.txt")), SECRET);
                    check.contains(behavior + " original raw stderr is preserved", Files.readString(root.resolve("raw/reviewer.stderr.txt")), SECRET);
                }
                check.eq(behavior + " no duplicate vendor process", 1, Files.readAllLines(launches).size());
                check.that(behavior + " no raw/auth/reasoning text crosses projection", !observed.toString().contains(SECRET));
            } finally {
                Files.writeString(release, "finish");
                unblock.countDown();
                controller.join(Duration.ofSeconds(2));
            }
        }

        Path root = Files.createTempDirectory("warden-live-framing-");
        Path stdout = root.resolve("stdout"), stderr = root.resolve("stderr");
        Files.writeString(stdout, "");
        Files.writeString(stderr, SECRET);
        var events = new CopyOnWriteArrayList<ExecutionActivity.Update>();
        try (var live = LiveCliOutput.follow(stdout, stderr, events::add)) {
            String oversized = "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\""
                    + SECRET.repeat(3000) + "\"}}\n";
            String deep = "{\"nested\":".repeat(30) + "{}" + "}".repeat(30) + "\n";
            String nested = Json.write(java.util.Map.of("example", Json.parseObject(START))) + "\n";
            Files.writeString(stdout, oversized + deep + nested + START.substring(0, START.length() / 2));
            Thread.sleep(300);
            check.that("oversized/deep/nested/partial objects are not tool events", events.stream().noneMatch(e -> e.kind() == ExecutionActivity.Kind.TOOL_STARTED));
            Files.writeString(stdout, START.substring(START.length() / 2) + "\n" + END + "\n"
                    + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"" + SECRET
                    + "\"},{\"type\":\"tool_use\",\"name\":\"" + SECRET + "\"}]}}\n",
                    java.nio.file.StandardOpenOption.APPEND);
            await(() -> events.stream().anyMatch(e -> e.kind() == ExecutionActivity.Kind.TOOL_FINISHED)
                    && events.stream().anyMatch(e -> "other".equals(e.tool())));
            check.eq("split record is parsed once", 1L, events.stream().filter(e -> e.kind() == ExecutionActivity.Kind.TOOL_STARTED && "shell".equals(e.tool())).count());
            check.that("tool exit metadata is retained", events.stream().anyMatch(e -> e.kind() == ExecutionActivity.Kind.TOOL_FINISHED && Integer.valueOf(0).equals(e.exitCode())));
            check.that("unknown tool names are not copied", events.stream().anyMatch(e -> "other".equals(e.tool())));
            check.that("stderr is visible as bytes only", events.stream().anyMatch(e -> e.stderrBytes() > 0));
            check.that("reasoning and unknown names stay private", !events.toString().contains(SECRET));
            int count = events.size();
            Thread.sleep(300);
            check.eq("unchanged spool is not replayed", count, events.size());
        }
        int closedCount = events.size();
        Files.writeString(stdout, START + "\n", java.nio.file.StandardOpenOption.APPEND);
        Thread.sleep(300);
        check.eq("closing observer never resumes publication", closedCount, events.size());
    }

    private static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    }

    private static RoleExecutor.Request request(Path root, Path release, Path launches) throws Exception {
        List<String> command = command(release, launches);
        Profile profile = new Profile("synthetic-live", "reviewer", "stub", "stub-model", "medium",
                command.get(0), command.subList(1, command.size()), false, 1, null, null, null, false,
                List.of("status", "summary", "verdict", "findings"), List.of(), "stdin", null, null,
                "direct", null, null, null, List.of(), false);
        var task = new TaskSpec.ResolvedTask("live-output", "offline probe", List.of(), "low", "HEAD",
                List.of("."), List.of(), List.of("unused"), new TaskSpec.Authority(false, false, false),
                new TaskSpec.VisualQa(false, List.of(), null, null), new TaskSpec.Budget(1, 1), 1, 1);
        Path prompt = root.resolve("prompt.md");
        Files.writeString(prompt, "offline observer test");
        return new RoleExecutor.Request("live", "live", "reviewer", profile, task, "0".repeat(40),
                root, root, prompt, null, "", "reviewer", List.of());
    }

    private static List<String> command(Path release, Path launches) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            entries.add(Path.of(entry).toAbsolutePath().toString());
        }
        return List.of(executable, "-cp", String.join(java.io.File.pathSeparator, entries),
                LiveCliOutputTest.class.getName(), release.toString(), launches.toString());
    }

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[1]), "one original process\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println(START);
        System.out.println(END);
        System.err.println(SECRET);
        System.out.flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(Path.of(args[0])) && System.nanoTime() < deadline) Thread.sleep(20);
        System.out.println("{\"structuredOutput\":{\"status\":\"completed\",\"summary\":\"original result\",\"verdict\":\"pass\",\"findings\":[]},\"total_cost_usd\":0.25}");
    }
}
