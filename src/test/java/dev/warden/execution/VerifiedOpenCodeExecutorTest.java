package dev.warden.execution;

import com.sun.net.httpserver.HttpServer;

import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An actual fixture child exercises the admitted executor without a paid service or user secrets.
 */
public final class VerifiedOpenCodeExecutorTest implements Suite {
    private static final String SECRET = "synthetic-runtime-upstream-secret";
    private static final String KEY = "WARDEN_TEST_RUNTIME_KEY";
    private static final String MODEL = "z-ai/model-one";

    @Override
    public String name() {
        return "verified-opencode";
    }

    @Override
    public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-verified-opencode-");
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> served = new AtomicReference<>(MODEL);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/v1/chat/completions",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "id",
                                                    "runtime-request-one",
                                                    "model",
                                                    served.get(),
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "message",
                                                                    Map.of(
                                                                            "role",
                                                                            "assistant",
                                                                            "content",
                                                                            "fixture-content"),
                                                                    "finish_reason",
                                                                    "stop")),
                                                    "usage",
                                                    Map.of(
                                                            "prompt_tokens",
                                                            10,
                                                            "completion_tokens",
                                                            5)))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                    exchange.close();
                });
        upstream.start();
        try {
            Files.createDirectories(home.resolve("connections"));
            Path binary = home.resolve("synthetic-opencode.exe");
            Files.writeString(binary, "synthetic-native-binary-bound-by-profile");
            Files.writeString(
                    home.resolve("prompt.md"),
                    "Implement the fixture task and write its artifact.");
            Files.writeString(
                    home.resolve("schema.json"),
                    """
                    {"type":"object","properties":{"role":{"const":"implementer"},"task_id":{"const":"runtime"},"status":{"type":"string"},"summary":{"type":"string"}},"required":["role","task_id","status","summary"],"additionalProperties":false}
                    """);
            Connection connection =
                    new Connection(
                            "local",
                            "custom",
                            "openai_chat",
                            "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                            "env:" + KEY,
                            true);
            Files.writeString(home.resolve("connections/local.yaml"), connection.toYaml());
            String yaml =
                    """
                    version: 1
                    profile: tested-runtime
                    role: implementer
                    runner: direct
                    harness: opencode
                    harness_executable: '%s'
                    connection: local
                    model_vendor: z-ai
                    model: z-ai/model-one
                    read_only: false
                    prompt_template: prompt.md
                    json_schema: schema.json
                    enforce_schema: true
                    limits:
                      wall_clock_minutes: 1
                      max_output_tokens: 2048
                    verification:
                      status: unverified
                    """
                            .formatted(binary.toString().replace('\\', '/'));
            Profile profile = Profile.parse(yaml, "runtime-test").withConnection(connection, home);
            Path earlier = Files.createDirectories(home.resolve("project/.warden/runs/earlier"));
            Files.writeString(earlier.resolve("review.json"), "{\"verdict\":\"fail\"}");
            ProcessRunner processes = new ProcessRunner().withEnvironment(Map.of(KEY, SECRET));
            var normal = new VerifiedOpenCodeExecutor(processes, false, name -> SECRET, command());
            var refused = normal.execute(request(home, profile, "unadmitted"));
            check.eq(
                    "unverified runtime cannot enter the paid dispatch",
                    "harness_coding_unproven",
                    refused.code());
            check.eq("unverified runtime sends no HTTP", 0, hits.get());

            var probe = new VerifiedOpenCodeExecutor(processes, true, name -> SECRET, command());
            var accepted = probe.execute(request(home, profile, "accepted"));
            check.that(
                    "the native verification path runs the same executor to a valid artifact",
                    accepted.ok());
            check.eq("a successful fixture sends exactly one upstream request", 1, hits.get());
            check.eq(
                    "served identity is established by the HTTP response",
                    MODEL,
                    accepted.evidence().get("model_served"));
            check.eq(
                    "the returned artifact passed its declared schema",
                    true,
                    accepted.evidence().get("artifact_schema_verified"));
            check.eq(
                    "the child's process tree settled",
                    true,
                    accepted.evidence().get("process_tree_settled"));
            check.eq(
                    "the dispatch records how run evidence was hidden",
                    RunArtifactShield.bubblewrapWorks() ? "mount_namespace" : "permission_rules",
                    accepted.evidence().get("run_artifacts_hidden"));
            check.eq(
                    "the dispatch records its relay turn limit",
                    8,
                    accepted.evidence().get("max_requests"));
            if (RunArtifactShield.bubblewrapWorks())
                check.that(
                        "the implementer cannot see an earlier run's evidence",
                        accepted.rawOutput().contains("runs_visible=false"));
            check.that(
                    "the run keeps a copy of the artifact beside its evidence",
                    Files.exists(home.resolve("project/accepted/harness-artifact.json")));
            check.that(
                    "fixture child saw no upstream service credential",
                    accepted.rawOutput().contains("upstream_key_present=false"));
            check.that(
                    "receipts and raw output cannot persist the upstream secret",
                    !(Json.write(accepted.evidence()) + accepted.rawOutput()).contains(SECRET));

            Map<String, Object> proof = new java.util.LinkedHashMap<>(accepted.evidence());
            proof.put("coding_tools_verified", true);
            proof.put("independent_gate_passed", true);
            dev.warden.config.CodingAttestation.write(profile, proof);
            Profile admitted =
                    Profile.parse(
                                    yaml
                                            + "  verified_on: '2026-10-27'\n"
                                            + "  connection_fingerprint: '"
                                            + profile.apiFingerprint()
                                            + "'\n",
                                    "admitted")
                            .withConnection(connection, home);
            check.that(
                    "a complete capability receipt admits the ordinary executor",
                    admitted.verified());
            var normalAccepted = normal.execute(request(home, admitted, "ordinary"));
            check.that("ordinary dispatch uses the same verified runtime", normalAccepted.ok());

            served.set("z-ai/substituted-model");
            var mismatch = probe.execute(request(home, profile, "mismatch"));
            check.that("a substituted model cannot finish the dispatch", !mismatch.ok());
            check.that(
                    "a substituted response never reaches the child",
                    !mismatch.rawOutput().contains("fixture-content"));
            check.that(
                    "the failed child cannot create a successful artifact",
                    !Files.exists(home.resolve("project/mismatch/harness-artifact.json")));
            Map<?, ?> relay = (Map<?, ?>) mismatch.evidence().get("relay");
            check.eq(
                    "the original model mismatch is retained",
                    "probe_served_model_mismatch",
                    relay.get("failure"));
            usageIsNotInvented(check);
            providerStopsKeepTheirReason(check);
        } finally {
            upstream.stop(0);
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void providerStopsKeepTheirReason(Check check) {
        check.eq(
                "an upstream timeout that stopped OpenCode is reported as the API timeout",
                "role_api_timeout",
                VerifiedOpenCodeExecutor.processFailure(Map.of("failure", "role_api_timeout")));
        check.eq(
                "a throttled upstream is reported as rate limiting",
                "role_api_rate_limited",
                VerifiedOpenCodeExecutor.processFailure(
                        Map.of("failure", "probe_upstream_http_429")));
        check.eq(
                "an upstream 5xx is reported as a provider error",
                "role_api_provider_error",
                VerifiedOpenCodeExecutor.processFailure(
                        Map.of("failure", "probe_upstream_http_503")));
        check.eq(
                "running out of relay turns is named as the turn limit",
                "role_harness_turn_limit",
                VerifiedOpenCodeExecutor.processFailure(Map.of("failure", "probe_turn_limit")));
        check.eq(
                "running out of relay time is named as the time limit",
                "role_harness_time_limit",
                VerifiedOpenCodeExecutor.processFailure(Map.of("failure", "probe_time_limit")));
        check.eq(
                "a served-model mismatch stays a harness failure",
                "role_harness_process_failed",
                VerifiedOpenCodeExecutor.processFailure(
                        Map.of("failure", "probe_served_model_mismatch")));
        check.eq(
                "an exit without a relay failure stays a harness failure",
                "role_harness_process_failed",
                VerifiedOpenCodeExecutor.processFailure(Map.of("requests", 1)));
    }

    private static void usageIsNotInvented(Check check) {
        Map<String, Object> missing =
                VerifiedOpenCodeExecutor.usage(
                        Map.of("requests", 1, "responses", List.of(Map.of("model", MODEL))));
        check.that(
                "missing input usage is not reported as measured zero",
                missing.get("input") == null);
        check.that(
                "missing output usage is not reported as measured zero",
                missing.get("output") == null);
        Map<String, Object> mixed =
                VerifiedOpenCodeExecutor.usage(
                        Map.of(
                                "requests",
                                2,
                                "responses",
                                List.of(
                                        Map.of(
                                                "usage",
                                                Map.of("prompt_tokens", 5, "completion_tokens", 2)),
                                        Map.of("model", MODEL))));
        check.that(
                "partial known turns cannot become a complete input total",
                mixed.get("input") == null);
        check.that(
                "partial known turns cannot become a complete output total",
                mixed.get("output") == null);
        Map<String, Object> known =
                VerifiedOpenCodeExecutor.usage(
                        Map.of(
                                "requests",
                                2,
                                "responses",
                                List.of(
                                        Map.of(
                                                "usage",
                                                Map.of("prompt_tokens", 5, "completion_tokens", 2)),
                                        Map.of(
                                                "usage",
                                                Map.of(
                                                        "prompt_tokens",
                                                        7,
                                                        "completion_tokens",
                                                        3)))));
        check.eq("complete input usage is summed", 12L, known.get("input"));
        check.eq("complete output usage is summed", 5L, known.get("output"));
    }

    private static RoleExecutor.Request request(Path home, Profile profile, String name)
            throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path run = Files.createDirectories(project.resolve(name));
        TaskSpec.ResolvedTask task =
                new TaskSpec.ResolvedTask(
                        "runtime",
                        "fixture",
                        List.of(),
                        "low",
                        "HEAD",
                        List.of("."),
                        List.of(),
                        List.of("unused"),
                        new TaskSpec.Authority(false, false, false),
                        new TaskSpec.VisualQa(false, List.of(), null, null),
                        new TaskSpec.Budget(1, 1),
                        1,
                        1);
        return new RoleExecutor.Request(
                "runtime",
                "runtime",
                "implementer",
                profile,
                task,
                null,
                project,
                run,
                home.resolve("prompt.md"),
                home.resolve("schema.json"),
                "",
                "runtime",
                List.of());
    }

    private static List<String> command() {
        String executable =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name", "")
                                                .toLowerCase(Locale.ROOT)
                                                .contains("win")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        String classpath =
                Arrays.stream(
                                System.getProperty("java.class.path")
                                        .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                        .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                        .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        return List.of(executable, "-cp", classpath, VerifiedOpenCodeExecutorTest.class.getName());
    }

    /** Synthetic OpenCode process. It never knows the upstream credential. */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("1.19.4");
            return;
        }
        if (args[0].equals("debug")) {
            System.out.println(System.getenv("OPENCODE_CONFIG_CONTENT"));
            return;
        }
        if (!args[0].equals("run"))
            throw new IllegalArgumentException("unexpected fixture invocation");
        System.out.println("upstream_key_present=" + (System.getenv(KEY) != null));
        System.out.println(
                "runs_visible=" + Files.exists(Path.of(".warden/runs/earlier/review.json")));
        Map<String, Object> config = Json.parseObject(System.getenv("OPENCODE_CONFIG_CONTENT"));
        Map<?, ?> provider = (Map<?, ?>) ((Map<?, ?>) config.get("provider")).get("warden");
        String base = (String) ((Map<?, ?>) provider.get("options")).get("baseURL");
        String model = ((String) config.get("model")).substring("warden/".length());
        var request =
                HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header(
                                "Authorization",
                                "Bearer " + System.getenv("WARDEN_PROBE_RELAY_KEY"))
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        Json.write(Map.of("model", model, "messages", List.of()))))
                        .build();
        try (var client = HttpClient.newHttpClient()) {
            var reply = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() != 200) {
                System.out.println("relay_refused=true");
                System.exit(3);
            }
        }
        int fileIndex = List.of(args).indexOf("--file");
        String instruction = Files.readString(Path.of(args[fileIndex + 1]));
        var matcher =
                java.util.regex.Pattern.compile("this exact file: ([^\\r\\n]+)")
                        .matcher(instruction);
        if (!matcher.find()) throw new IllegalArgumentException("artifact destination missing");
        Files.writeString(
                Path.of(matcher.group(1)),
                "{\"role\":\"implementer\",\"task_id\":\"runtime\",\"status\":\"completed\",\"summary\":\"fixture"
                    + " finished\"}");
    }
}
