package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.approval.ApprovalStore;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Connection;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.execution.ApiExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual local HTTP plus the complete loop and durable human switch continuation. */
public final class ApiFallbackLoopTest implements Suite {
    @Override public String name() { return "api-fallback-loop"; }

    @Override public void run(Check check) throws Exception {
        try (Fixture auto = new Fixture("auto", false)) {
            var result = auto.run("auto", null);
            check.that("real HTTP fallback finishes implement, gates and mandatory review", result.ok());
            check.eq("real HTTP fallback ends at the human gate", "human_gate", result.nextAction());
            check.eq("real HTTP fallback writes the candidate once", 1, auto.writes.get());
            check.eq("real HTTP failure is not retried before taking the declared successor",
                    List.of("fixture/primary", "fixture/backup-one"), auto.models);
            check.eq("real HTTP fallback accounts for writer and both API calls", 3L,
                    result.summaryReport().get("role_runs"));
            Map<String, Object> review = auto.role("auto");
            check.eq("real HTTP fallback preserves both API attempts", 2,
                    ((List<?>) review.get("vendor_attempts")).size());
            check.eq("the initial HTTP 502 keeps its API provider diagnosis", "role_api_provider_error",
                    ((Map<?, ?>) ((List<?>) review.get("vendor_attempts")).get(0)).get("code"));
            Map<?, ?> failedAttempt = (Map<?, ?>) ((List<?>) review.get("vendor_attempts")).get(0);
            check.eq("the failed endpoint retains its HTTP status after a later success", 502L,
                    failedAttempt.get("http_status"));
            check.eq("the failed endpoint retains its delivery evidence", "answered", failedAttempt.get("delivery"));
            check.eq("the failed endpoint retains its phase", "http_status", failedAttempt.get("failure_phase"));
            check.eq("unreported failed-call cost remains unknown", "unknown", failedAttempt.get("cost_status"));
            check.that("the failed endpoint retains its correlated API attempt log",
                    failedAttempt.get("api_attempts") instanceof List<?> apiAttempts && apiAttempts.size() == 1
                            && ((Map<?, ?>) apiAttempts.get(0)).get("correlation_id")
                                    .equals(failedAttempt.get("correlation_id")));
            check.eq("the final API response passes the mandatory review", "pass", review.get("verdict"));
            check.eq("the successful full loop leaves acceptance pending", "pending",
                    new ApprovalStore(auto.project).read("auto").state().jsonValue());
        }
        try (Fixture confirm = new Fixture("confirm", true)) {
            var first = confirm.run("confirm-first", null);
            check.eq("real API confirm opens the higher-level switch decision", "failover_requires_confirmation",
                    first.reason());
            Map<?, ?> firstPending = (Map<?, ?>) first.summaryReport().get("failover_pending");
            check.eq("the first approval names the first declared endpoint", "backup-one", firstPending.get("to_profile"));
            check.eq("the first human decision spends no successor HTTP call",
                    List.of("fixture/primary"), confirm.models);
            confirm.approve("confirm-first");

            var second = confirm.run("confirm-second", "confirm-first");
            check.eq("a failed approved endpoint asks for the next explicit switch",
                    "failover_requires_confirmation", second.reason());
            Map<?, ?> secondPending = (Map<?, ?>) second.summaryReport().get("failover_pending");
            check.eq("continued confirmation keeps ordered fallback despite the rotating roster", "backup-two",
                    secondPending.get("to_profile"));
            check.eq("the previous approved substitution counts against the stage bound", 1L,
                    secondPending.get("vendor_failovers"));
            check.eq("continued confirmation remembers every already failed endpoint",
                    List.of("primary", "backup-one"), secondPending.get("exhausted_profiles"));
            check.eq("the first continuation reuses the completed writer", 1, confirm.writes.get());
            confirm.approve("confirm-second");

            var finalRun = confirm.run("confirm-third", "confirm-second");
            check.that("the second approved API successor completes the actual loop", finalRun.ok());
            check.eq("the final confirmed loop remains at the human gate", "human_gate", finalRun.nextAction());
            check.eq("all three confirmed runs share one writer dispatch", 1, confirm.writes.get());
            check.eq("actual HTTP dispatches visit each endpoint exactly once and in declared order",
                    List.of("fixture/primary", "fixture/backup-one", "fixture/backup-two"), confirm.models);
            check.eq("the final continuation spends only its unfinished API reading", 1L,
                    finalRun.summaryReport().get("role_runs"));
            check.eq("the final continuation explicitly reuses implement",
                    Map.of("from", "confirm-second", "stages", List.of("implement")),
                    finalRun.summaryReport().get("reused_judgements"));
            check.eq("continued chain accounting retains all four paid fixture calls", 4L,
                    ((Map<?, ?>) finalRun.summaryReport().get("chain")).get("role_runs"));
            String evidence = Files.readString(confirm.project.resolve(
                    ".warden/runs/confirm-third--reviewer-0/evidence.jsonl"));
            check.contains("the loop records the approved endpoint as human authorized", evidence,
                    "\"authorized_by\":\"human\"");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path scratch = Files.createTempDirectory("warden-api-fallback-loop-");
        final Path project = scratch.resolve("project");
        final Path home = scratch.resolve("home");
        final AtomicInteger writes = new AtomicInteger();
        final List<String> models = new CopyOnWriteArrayList<>();
        final HttpServer server;
        Fixture(String mode, boolean failFirstBackup) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                Map<String, Object> request = Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                String model = String.valueOf(request.get("model"));
                models.add(model);
                boolean failed = model.equals("fixture/primary")
                        || failFirstBackup && model.equals("fixture/backup-one");
                Map<String, Object> artifact = Map.of("role", "reviewer", "task_id", "hello", "status", "completed",
                        "summary", "local HTTP reading", "verdict", "pass", "findings", List.of());
                byte[] body = Json.write(failed ? Map.of("error", Map.of("message", "synthetic bad gateway"))
                        : Map.of("model", model, "choices", List.of(Map.of("finish_reason", "stop", "message",
                                Map.of("role", "assistant", "content", Json.write(artifact)))),
                                "usage", Map.of("prompt_tokens", 9, "completion_tokens", 4)))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failed ? 502 : 200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            server.start();
            new UserSetup().run(home);
            Files.createDirectories(home.resolve("connections"));
            Files.writeString(home.resolve("connections/fixture.yaml"), new Connection("fixture", "custom", "openai_chat",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null, true).toYaml());
            Files.createDirectories(project.resolve(".warden/tasks"));
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("src/base.txt"), "base\n");
            Files.writeString(project.resolve(".gitignore"), ".warden/\n");
            Files.writeString(project.resolve(".warden/project.yaml"),
                    "version: 1\nproject: api-fallback-loop\nbase_ref: HEAD\nchecks: {fast: ['java -version']}\n");
            Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                    version: 1
                    id: hello
                    goal: Create src/result.txt containing ok
                    risk: medium
                    scope: [src]
                    authority: {workspace_write: true}
                    max_fix_attempts: 0
                    budgets: {max_role_runs: 8, max_cost_usd: 1, cost_cap: off}
                    """);
            Files.writeString(home.resolve("profiles/writer.yaml"), """
                    version: 1
                    profile: writer
                    role: implementer
                    vendor: writervendor
                    command: java
                    args: ['-version']
                    read_only: false
                    prompt_template: prompts/implementer.md
                    json_schema: schemas/implementer.json
                    verification: {verified_on: '2026-10-29'}
                    """);
            for (String profile : List.of("primary", "backup-one", "backup-two")) {
                Path file = home.resolve("profiles/" + profile + ".yaml");
                Files.writeString(file, """
                        version: 1
                        profile: %s
                        role: reviewer
                        runner: api
                        connection: fixture
                        model_vendor: readervendor
                        model: fixture/%s
                        read_only: true
                        prompt_template: prompts/reviewer.md
                        json_schema: schemas/reviewer.json
                        limits: {wall_clock_minutes: 1, max_output_tokens: 256}
                        verification:
                          what_to_check: [synthetic local HTTP transport]
                        """.formatted(profile, profile));
                new ProfileVerifier(new ProcessRunner()).stamp(file, UserConfig.load(home).profiles().get(profile),
                        LocalDate.of(2026, 10, 29));
            }
            Files.writeString(home.resolve("policy.yaml"), """
                    version: 1
                    roles:
                      implementer: {profiles: [writer], strategy: first}
                      reviewer:
                        profiles: [primary, backup-two, backup-one]
                        strategy: rotate
                        require_independent_vendor: true
                        api_fallback_profiles: [backup-one, backup-two]
                    review: {required_for_risk: [medium, high]}
                    failover: {on_vendor_failure: %s, max_per_stage: 2}
                    workflow:
                      stages:
                        - {stage: implement, run: role, role: implementer, on_fail: stop}
                        - {stage: gates, run: machine_gates, on_fail: stop}
                        - {stage: review, run: role, role: reviewer, on_fail: stop}
                    """.formatted(mode));
            for (List<String> args : List.of(List.of("init", "--quiet"),
                    List.of("config", "user.email", "test@example.invalid"),
                    List.of("config", "user.name", "Warden Test"), List.of("add", "."),
                    List.of("commit", "--quiet", "-m", "test: local HTTP fallback loop fixture"))) {
                var command = new ArrayList<String>(); command.add("git"); command.addAll(args);
                if (!new ProcessRunner().run(command, project, Duration.ofSeconds(20)).ok())
                    throw new IOException("fixture git command failed");
            }
        }
        TaskLoop.Outcome run(String id, String from) throws Exception {
            TaskLoop loop = new TaskLoop(new ProcessRunner()).withOrcaGate(false)
                    .withReadiness((user, loaded, profiles, strict, progress) -> Map.of("ok", true, "code", "fixture_ready"))
                    .withExecutors((profile, processes, git) -> "api".equals(profile.runner())
                            ? new ApiExecutor(System::getenv, git) : request -> {
                                writes.incrementAndGet();
                                Files.writeString(project.resolve("src/result.txt"), "ok\n");
                                Map<String, Object> artifact = Map.of("role", "implementer", "task_id", "hello",
                                        "status", "completed", "summary", "fixture writer",
                                        "files_changed", List.of("src/result.txt"));
                                return new RoleExecutor.Result(true, "role_completed", Duration.ofMillis(1),
                                        Json.write(artifact), artifact, Map.of("cost_usd", 0.125));
                            });
            Main.Carried carried = from == null
                    ? new Main.Carried(Map.of(), TaskLoop.Continuation.NONE) : Main.continuation(project, from);
            return loop.run(new ConfigLoader().load(project, "hello"), UserConfig.load(home), id, false,
                    carried.failover(), carried.continuation());
        }
        void approve(String id) throws Exception {
            ApprovalStore store = new ApprovalStore(project);
            store.resolve(id, store.read(id).updatedAt().toString(), "switch", "fixture operator", "");
        }
        Map<String, Object> role(String id) throws IOException {
            return Json.parseObject(Files.readString(project.resolve(".warden/runs/" + id + "--reviewer-0/role-reviewer.json")));
        }
        @Override public void close() throws IOException {
            server.stop(0);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(scratch.toAbsolutePath().normalize()))
                        throw new IOException("cleanup outside fallback loop scratch directory");
                    if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
