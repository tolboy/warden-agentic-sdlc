package dev.warden;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.execution.ApiConnectionCheck;
import dev.warden.execution.ApiExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The public API executor contract is exercised against local HTTP, without credentials or billing. */
public final class ApiExecutorTest implements Suite {
    @Override public String name() { return "api-http"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-api-http-");
        Path prompt = scratch.resolve("prompt.md");
        Files.writeString(prompt, "review the supplied context\nsecond line \"quoted\"", StandardCharsets.UTF_8);
        successfulReceipt(check, prompt);
        missingCredentialDoesNotSend(check, prompt);
        redaction(check, prompt);
        encodedReflectionRedaction(check, prompt);
        taxonomy(check, prompt);
        modelAndProtocolFailures(check, prompt);
        boundedBodyAndDeadline(check, prompt);
        schemaAndSemantics(check, prompt);
        noInferenceChecks(check);
        readOnlyFingerprint(check, scratch);
        gonkaOutputBound(check, prompt);
    }

    private static void successfulReceipt(Check check, Path prompt) throws Exception {
        try (Stub stub = new Stub(200, completion("test/model", artifact("completed"), "stop"))) {
            RoleExecutor.Result result = new ApiExecutor().execute(request(profile(stub.connection(null)), prompt));
            check.that("API completion produces an artifact", result.ok() && result.artifact() != null);
            check.eq("artifact defaults identify the role", "reviewer", result.artifact().get("role"));
            check.eq("requested model is recorded", "test/model", result.evidence().get("model_requested"));
            check.eq("served model comes from envelope", "test/model", result.evidence().get("model_reported"));
            check.eq("generation id comes from envelope", "generation-test", result.evidence().get("generation_id"));
            check.eq("request header receipt is recorded", "request-test", result.evidence().get("request_id"));
            Map<?, ?> tokens = (Map<?, ?>) result.evidence().get("tokens");
            check.eq("input tokens are retained", 11L, tokens.get("input"));
            check.eq("cached tokens survive shared settlement", 3L, tokens.get("cache_read"));
            check.eq("reasoning tokens survive shared settlement", 2L, tokens.get("reasoning"));
            check.that("custom provider pricing is not invented from a similarly named field",
                    !result.evidence().containsKey("cost_usd"));
            Map<String, Object> sent = Json.findLastObject(stub.lastBody);
            check.eq("hard output limit is sent", 128L, sent.get("max_tokens"));
            check.eq("streaming is off", false, sent.get("stream"));
            check.contains("multiline context reaches HTTP intact", stub.lastBody, "second line");
            check.eq("explicit no-auth omits bearer", null, stub.authorization);
            check.that("safe raw transcript is saved", Files.isRegularFile(prompt.getParent()
                    .resolve(String.valueOf(result.evidence().get("raw_stdout")))));
        }
    }

    private static void missingCredentialDoesNotSend(Check check, Path prompt) throws Exception {
        try (Stub stub = new Stub(200, completion("test/model", artifact("completed"), "stop"))) {
            Profile profile = profile(stub.connection("env:TEST_API_KEY"));
            RoleExecutor.Result result = new ApiExecutor(name -> null, null).execute(request(profile, prompt));
            check.eq("missing named credential fails closed", "role_api_key_missing", result.code());
            check.eq("missing credential sends no HTTP request", 0, stub.calls.get());
            check.eq("missing credential is explicitly not a vendor turn", false,
                    result.evidence().get("vendor_turn_started"));
            result = new ApiExecutor(name -> " ", null).execute(request(profile, prompt));
            check.eq("blank credential also fails closed", "role_api_key_missing", result.code());
            result = new ApiExecutor().execute(request(profile(stub.connection(null).withEnabled(false)), prompt));
            check.eq("disabled connection is not attempted", "role_api_connection_disabled", result.code());
            check.eq("disabled connection sends no HTTP request", 0, stub.calls.get());
        }
    }

    private static void redaction(Check check, Path prompt) throws Exception {
        String secret = "synthetic-api-reflection-token";
        for (int status : List.of(200, 401)) {
            String response = status == 200
                    ? completion("test/model", Json.write(Map.of("status", "completed", "verdict", "pass",
                            "summary", secret, "findings", List.of())), "stop")
                    : Json.write(Map.of("error", Map.of("message", "reflected " + secret)));
            try (Stub stub = new Stub(status, response)) {
                stub.requestId = secret;
                RoleExecutor.Result result = new ApiExecutor(name -> secret, null)
                        .execute(request(profile(stub.connection("env:TEST_API_KEY")), prompt));
                check.that("Bearer is sent to the explicitly chosen connection", ("Bearer " + secret).equals(stub.authorization));
                String persisted = Json.write(result.evidence()) + result.rawOutput()
                        + Json.write(result.artifact()) + Files.readString(prompt.getParent()
                        .resolve(String.valueOf(result.evidence().get("raw_stdout"))));
                check.that("reflected bearer is absent from every retained output for HTTP " + status,
                        !persisted.contains(secret));
                check.contains("redaction leaves an explicit marker", persisted, "<redacted>");
            }
        }
        String escapedSecret = "synthetic-quote\"backslash\\token";
        try (Stub stub = new Stub(401, Json.write(Map.of("error", Map.of("message", escapedSecret))))) {
            RoleExecutor.Result result = new ApiExecutor(name -> escapedSecret, null)
                    .execute(request(profile(stub.connection("env:TEST_API_KEY")), prompt));
            check.that("JSON-escaped credential reflection is redacted", !result.rawOutput().contains("backslash"));
        }
    }

    private static void taxonomy(Check check, Path prompt) throws Exception {
        Object[][] cases = {
                {401, "invalid token", "role_api_auth_invalid"},
                {402, "insufficient credits", "role_api_credits_exhausted"},
                {403, "key budget exceeded", "role_api_budget_exhausted"},
                {402, "in-flight spending budget reached", "role_api_rate_limited"},
                {403, "access denied", "role_api_access_denied"},
                {429, "too many requests", "role_api_rate_limited"},
                {400, "unsupported model", "role_api_model_or_parameter_unsupported"},
                {503, "upstream unavailable", "role_api_provider_error"},
                {302, "redirect", "role_api_protocol_error"}
        };
        for (Object[] item : cases) {
            try (Stub stub = new Stub((int) item[0], Json.write(Map.of("error", Map.of("message", item[1]))))) {
                RoleExecutor.Result result = new ApiExecutor().execute(request(profile(stub.connection(null)), prompt));
                check.eq("status and metadata classify " + item[0] + " " + item[1], item[2], result.code());
                check.eq("HTTP errors do not retry automatically", 1, stub.calls.get());
                check.eq("Retry-After is retained verbatim", "2", result.evidence().get("retry_after"));
            }
        }
        try (Stub stub = new Stub(200, Json.write(Map.of("error", Map.of("code", 429, "message", "wait"))))) {
            check.eq("2xx error envelopes are not successful artifacts", "role_api_rate_limited",
                    new ApiExecutor().execute(request(profile(stub.connection(null)), prompt)).code());
        }
    }

    private static void encodedReflectionRedaction(Check check, Path prompt) throws Exception {
        String secret = "synthetic-unicode-reflection";
        StringBuilder unicode = new StringBuilder();
        for (char character : secret.toCharArray()) {
            unicode.append('\\').append('u').append(String.format("%04x", (int) character));
        }
        String encodedArtifact = "{\"status\":\"completed\",\"verdict\":\"pass\",\"summary\":\""
                + unicode + "\",\"findings\":[],\"" + unicode + "\":\"reflected-key\"}";
        // The middle form has an encoded role artifact inside a string-valued result:
        // envelope JSON -> content JSON -> result JSON. The final form exercises prose recovery.
        List<String> contents = List.of(encodedArtifact,
                Json.write(Map.of("result", encodedArtifact)),
                "Answer follows:\n```json\n" + encodedArtifact + "\n```");
        for (int index = 0; index < contents.size(); index++) {
            String response = completion("test/model", contents.get(index), "stop");
            // Encode the outer envelope metadata as well, to test the first JSON parse.
            response = response.replace("generation-test", unicode.toString());
            try (Stub stub = new Stub(200, response)) {
                RoleExecutor.Result result = new ApiExecutor(name -> secret, null)
                        .execute(request(profile(stub.connection("env:TEST_API_KEY")), prompt));
                check.that("encoded reflection still yields a structured artifact, case " + index,
                        result.ok() && result.artifact() != null);
                check.eq("decoded Unicode credential value is redacted, case " + index,
                        "<redacted>", result.artifact().get("summary"));
                check.that("decoded Unicode credential key is removed, case " + index,
                        !result.artifact().containsKey(secret) && result.artifact().containsKey("<redacted>"));
                check.eq("Unicode-reflected envelope metadata is redacted, case " + index,
                        "<redacted>", result.evidence().get("generation_id"));
                String saved = Files.readString(prompt.getParent().resolve(String.valueOf(result.evidence().get("raw_stdout"))));
                String retained = Json.write(result.evidence()) + result.rawOutput()
                        + Json.write(result.artifact()) + saved;
                check.that("decoded credential is absent from all retained outputs, case " + index,
                        !retained.contains(secret));
                Map<String, Object> savedEnvelope = Json.parseObject(saved);
                Map<?, ?> choice = (Map<?, ?>) ((List<?>) savedEnvelope.get("choices")).get(0);
                Map<?, ?> message = (Map<?, ?>) choice.get("message");
                Map<String, Object> savedArtifact = dev.warden.execution.DirectCliExecutor.unwrap(
                        Json.findLastObject((String) message.get("content")));
                check.that("saved JSON cannot reveal a credential when parsed again, case " + index,
                        savedArtifact != null && !savedArtifact.containsKey(secret)
                                && "<redacted>".equals(savedArtifact.get("summary")));
            }
        }
        try (Stub stub = new Stub(500, "{\"error\":\"" + unicode)) {
            RoleExecutor.Result result = new ApiExecutor(name -> secret, null)
                    .execute(request(profile(stub.connection("env:TEST_API_KEY")), prompt));
            String saved = Files.readString(prompt.getParent().resolve(String.valueOf(result.evidence().get("raw_stdout"))));
            check.that("malformed Unicode-reflected credentials are omitted before persistence",
                    !saved.contains(unicode) && !saved.contains(secret) && saved.contains("response omitted"));
        }
    }

    private static void modelAndProtocolFailures(Check check, Path prompt) throws Exception {
        Object[][] cases = {
                {completion("other/model", artifact("completed"), "stop"), "role_api_model_mismatch"},
                {Json.write(Map.of("choices", List.of())), "role_api_model_identity_unknown"},
                {"not JSON", "role_api_response_unreadable"},
                {completion("test/model", artifact("completed"), "length"), "role_api_output_truncated"},
                {completion("test/model", "plain prose", "stop"), "role_api_artifact_failure"}
        };
        for (Object[] item : cases) {
            try (Stub stub = new Stub(200, (String) item[0])) {
                RoleExecutor.Result result = new ApiExecutor().execute(request(profile(stub.connection(null)), prompt));
                check.eq("invalid completion is refused as " + item[1], item[1], result.code());
                check.that("invalid completion carries no accepted artifact", result.artifact() == null);
            }
        }
        String tools = Json.write(Map.of("model", "test/model", "choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", artifact("completed"),
                        "tool_calls", List.of(Map.of("id", "call-test")))))));
        try (Stub stub = new Stub(200, tools)) {
            check.eq("unsolicited tool calls cannot pass as a text role", "role_api_tools_unsupported",
                    new ApiExecutor().execute(request(profile(stub.connection(null)), prompt)).code());
        }
    }

    private static void boundedBodyAndDeadline(Check check, Path prompt) throws Exception {
        try (Stub stub = new Stub(200, "x".repeat(4 * 1024 * 1024 + 1))) {
            RoleExecutor.Result result = new ApiExecutor().execute(request(profile(stub.connection(null)), prompt));
            check.eq("oversize response is cancelled at the byte bound", "role_api_response_too_large", result.code());
            check.eq("oversize body is not retained", "", result.rawOutput());
        }
        try (Stub stub = new Stub(200, completion("test/model", artifact("completed"), "stop"))) {
            stub.bodyDelay = 1500;
            long start = System.nanoTime();
            RoleExecutor.Result result = new ApiExecutor(System::getenv, Duration.ofMillis(200), null)
                    .execute(request(profile(stub.connection(null)), prompt));
            check.eq("wall deadline includes a body stalled after headers", "role_api_timeout", result.code());
            check.that("stalled body returns inside a bounded wall clock", Duration.ofNanos(System.nanoTime() - start).toMillis() < 1300);
            check.eq("a timed out sent request remains potentially charged", true,
                    result.evidence().get("vendor_turn_started"));
        }
    }

    private static void schemaAndSemantics(Check check, Path prompt) throws Exception {
        Path schema = prompt.getParent().resolve("schema.json");
        Files.writeString(schema, Json.write(Map.of("type", "object", "required", List.of("proof"))));
        try (Stub stub = new Stub(200, completion("test/model", artifact("completed"), "stop"))) {
            RoleExecutor.Request request = request(profile(stub.connection(null)), prompt);
            request = new RoleExecutor.Request(request.runId(), request.workflowRunId(), request.role(),
                    request.profile(), null, null, request.projectRoot(), request.runDirectory(), prompt,
                    schema, "", request.evidenceName(), List.of());
            RoleExecutor.Result result = new ApiExecutor().execute(request);
            check.eq("API artifacts obey the existing schema gate without enabling failover", "role_api_artifact_failure", result.code());
            check.eq("the schema failure reason is retained", "role_artifact_schema_violation", result.evidence().get("settlement_code"));
            check.that("API artifact failure stops the loop instead of requesting a repair",
                    dev.warden.run.TaskLoop.roleFailureRequiresOperator(result.code()));
        }
        try (Stub stub = new Stub(200, completion("test/model", artifact("blocked"), "stop"))) {
            check.eq("blocked model status is not a successful role", "role_api_artifact_failure",
                    new ApiExecutor().execute(request(profile(stub.connection(null)), prompt)).code());
        }
    }

    private static void noInferenceChecks(Check check) throws Exception {
        try (Stub stub = new Stub(200, "{}")) {
            Map<String, Object> result = ApiConnectionCheck.check(stub.connection("env:TEST_API_KEY"), name -> "synthetic");
            check.eq("custom configured credential is not declared auth-verified", false, result.get("auth_verified"));
            check.eq("connection checks do not claim model verification", false, result.get("model_verified"));
            check.eq("unsupported auth checks perform no inference", 0, stub.calls.get());
            result = ApiConnectionCheck.check(stub.connection("env:TEST_API_KEY"), name -> null);
            check.eq("native check reports missing credential without network", "role_api_key_missing", result.get("code"));
        }
    }

    private static void readOnlyFingerprint(Check check, Path scratch) throws Exception {
        Path repository = Files.createDirectory(scratch.resolve("fingerprint"));
        Files.writeString(repository.resolve("source.txt"), "original");
        Files.writeString(repository.resolve(".gitignore"), ".warden/\n");
        ProcessRunner processes = new ProcessRunner();
        for (List<String> command : List.of(List.of("git", "init", "-q", "-b", "main"),
                List.of("git", "config", "user.email", "fixture@example.invalid"),
                List.of("git", "config", "user.name", "fixture"), List.of("git", "add", "-A"),
                List.of("git", "commit", "-qm", "test: seed disposable API fixture"))) {
            if (processes.run(command, repository, Duration.ofSeconds(30)).exitCode() != 0) {
                throw new IOException("could not initialize disposable API fixture");
            }
        }
        String head = processes.run(List.of("git", "rev-parse", "HEAD"), repository, Duration.ofSeconds(10)).stdout().trim();
        Path run = Files.createDirectories(repository.resolve(".warden/runs/test"));
        Path prompt = run.resolve("prompt.md");
        Files.writeString(prompt, "review supplied context");
        try (Stub stub = new Stub(200, completion("test/model", artifact("completed"), "stop"))) {
            stub.beforeReply = () -> Files.writeString(repository.resolve("source.txt"), "unexpected edit");
            RoleExecutor.Request request = request(profile(stub.connection(null)), prompt);
            request = new RoleExecutor.Request(request.runId(), request.workflowRunId(), "reviewer", request.profile(),
                    null, head, repository, run, prompt, null, "", "reviewer", List.of());
            RoleExecutor.Result result = new ApiExecutor(System::getenv, new GitRepository(repository, processes)).execute(request);
            check.eq("API role is held to the existing worktree fingerprint", "role_api_artifact_failure", result.code());
            check.eq("the read-only violation reason is retained", "role_violated_read_only", result.evidence().get("settlement_code"));
        }
    }

    private static void gonkaOutputBound(Check check, Path prompt) throws Exception {
        Connection connection = new Connection("test", "gonkarouter", "openai_chat",
                "https://api.gonkarouter.io/v1", "env:TEST_API_KEY", true);
        Profile profile = Profile.parse(profileYaml(8192), "test.yaml").withConnection(connection);
        RoleExecutor.Result result = new ApiExecutor(name -> "synthetic", null).execute(request(profile, prompt));
        check.eq("Gonka output cap is refused before any HTTP request", "role_api_output_limit_unsupported", result.code());
        check.eq("unsupported output cap is not a vendor turn", false, result.evidence().get("vendor_turn_started"));
    }

    private static Profile profile(Connection connection) { return Profile.parse(profileYaml(128), "test.yaml").withConnection(connection); }

    private static String profileYaml(int maxOutput) {
        return """
                version: 1
                profile: api-test
                role: reviewer
                model_vendor: deepseek
                model: test/model
                runner: api
                connection: test
                read_only: true
                limits: { wall_clock_minutes: 1, max_output_tokens: %d }
                artifact:
                  required_fields: [status, verdict, summary, findings]
                """.formatted(maxOutput);
    }

    private static RoleExecutor.Request request(Profile profile, Path prompt) {
        return new RoleExecutor.Request("run-api", "workflow-api", "reviewer", profile, null, null,
                prompt.getParent(), prompt.getParent(), prompt, null, "", "reviewer", List.of());
    }

    private static String artifact(String status) {
        return Json.write(Map.of("status", status, "verdict", "pass", "summary", "checked", "findings", List.of(),
                "cost_usd", 9999));
    }

    private static String completion(String model, String artifact, String finish) {
        return Json.write(Map.of("model", model, "id", "generation-test", "usage", Map.of(
                "prompt_tokens", 11, "completion_tokens", 7, "cost", 0.002,
                "prompt_tokens_details", Map.of("cached_tokens", 3),
                "completion_tokens_details", Map.of("reasoning_tokens", 2)),
                "choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", artifact)))));
    }

    @FunctionalInterface private interface BeforeReply { void run() throws IOException; }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger calls = new AtomicInteger();
        private final String base;
        private volatile String lastBody;
        private volatile String authorization;
        private volatile String requestId = "request-test";
        private volatile long bodyDelay;
        private volatile BeforeReply beforeReply;

        private Stub(int status, String response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "api-http-fixture"); t.setDaemon(true); return t; });
            server.setExecutor(executor);
            server.createContext("/", exchange -> respond(exchange, status, response));
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void respond(HttpExchange exchange, int status, String response) throws IOException {
            calls.incrementAndGet();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (beforeReply != null) beforeReply.run();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Request-Id", requestId);
            exchange.getResponseHeaders().set("Retry-After", "2");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try {
                if (bodyDelay > 0) Thread.sleep(bodyDelay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException cancelled) {
                // Body-limit and deadline tests deliberately cancel an in-flight response.
            } finally { exchange.close(); }
        }

        private Connection connection(String credentialRef) {
            return new Connection("test", "custom", "openai_chat", base, credentialRef, true);
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
