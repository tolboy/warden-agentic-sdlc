package dev.warden.execution;

import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 2 resilience, against a scripted local socket server: no provider, no credentials, no
 * billing. Each script entry decides what one incoming request gets — a status, a dropped
 * connection, a body cut off mid-way, or a body that never arrives — so every failure phase
 * is produced on purpose rather than hoped for.
 */
public final class ApiResilienceTest implements Suite {
    @Override public String name() { return "api-resilience"; }

    private static final String SECRET = "synthetic-resilience-credential";

    @Override public void run(Check check) throws Exception {
        rateLimitAndGateway(check);
        notSentIsResentAndUnknownIsNot(check);
        slowBodyAndCancel(check);
        roleCallsAreNotResent(check);
        failureEvidenceIsSafe(check);
        unknownCostIsNeverZero(check);
        relayNeverDuplicatesEdits(check);
    }

    // ---------------------------------------------------------------- 429 and 502

    private static void rateLimitAndGateway(Check check) throws Exception {
        List<Long> slept = Collections.synchronizedList(new ArrayList<>());
        ApiRetry.Sleeper sleeper = slept::add;
        try (Script server = new Script(Step.reply(429, "Retry-After: 1\r\n", error("slow down")),
                Step.reply(200, "", "{\"ok\":true}"))) {
            ApiRetry.Outcome outcome = send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: a 429 naming a short Retry-After is asked once more", 2, server.requests.get());
            check.eq("check: the resend waited exactly the named seconds", List.of(1000L), slept);
            check.eq("check: and the second answer is the one returned", 200, outcome.reply().status());
            check.eq("check: both attempts are counted", 2, outcome.attempts().size());
            check.eq("check: the first attempt is recorded as answered 429", 429L, outcome.attempts().get(0).get("http_status"));
            check.eq("check: attempts share one correlation id", outcome.attempts().get(0).get("correlation_id"),
                    outcome.attempts().get(1).get("correlation_id"));
            check.eq("check: each attempt carries the stage that asked", "check", outcome.attempts().get(1).get("stage"));
            check.eq("check: each attempt carries the provider request id", "req-2", outcome.attempts().get(1).get("request_id"));
            check.that("check: each attempt has a measured duration",
                    outcome.attempts().get(0).get("duration_millis") instanceof Long);
        }
        slept.clear();
        try (Script server = new Script(Step.reply(429, "", error("slow down")), Step.reply(200, "", "{}"))) {
            ApiRetry.Outcome outcome = send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: a 429 without Retry-After is not guessed at", 1, server.requests.get());
            check.eq("check: and is returned as the 429 it was", 429, outcome.reply().status());
        }
        try (Script server = new Script(Step.reply(429, "Retry-After: 3600\r\n", error("later")), Step.reply(200, "", "{}"))) {
            send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: an hour's Retry-After is not waited out inside one call", 1, server.requests.get());
        }
        try (Script server = new Script(Step.reply(502, "", error("bad gateway")))) {
            ApiRetry.Outcome outcome = send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: a persistent 502 is bounded at three attempts", 3, server.requests.get());
            check.eq("check: the stop says the retries ran out", "retries_exhausted", outcome.stoppedBecause());
            check.eq("check: the last 502 is what is returned", 502, outcome.reply().status());
            Map<String, Object> evidence = new LinkedHashMap<>();
            outcome.record(evidence);
            check.eq("check: the receipt counts retries", 2L, evidence.get("api_retries"));
            check.eq("check: and names the policy that allowed them", "check", evidence.get("api_retry_policy"));
        }
        try (Script server = new Script(Step.reply(502, "", error("bad gateway")), Step.reply(200, "", "{}"))) {
            ApiRetry.Outcome outcome = send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: a transient 502 recovers on the next attempt", 200, outcome.reply().status());
        }
        try (Script server = new Script(Step.reply(401, "", error("bad key")), Step.reply(200, "", "{}"))) {
            ApiRetry.Outcome outcome = send(server, ApiRetry.Policy.CHECK, sleeper, Duration.ofSeconds(5));
            check.eq("check: an auth refusal is never asked again", 1, server.requests.get());
            check.eq("check: and is returned untouched", 401, outcome.reply().status());
        }
        try (Script server = new Script(Step.reply(502, "", error("bad gateway")), Step.reply(200, "", "{}"))) {
            send(server, ApiRetry.Policy.NOT_SENT_ONLY, sleeper, Duration.ofSeconds(5));
            check.eq("role: a 502 the provider answered is not resent by a role", 1, server.requests.get());
        }
    }

    // ---------------------------------------------------------------- delivery

    private static void notSentIsResentAndUnknownIsNot(Check check) throws Exception {
        int port = freePort();
        List<Long> slept = new ArrayList<>();
        ApiRetry.Outcome refused = ApiRetry.send(connection(port), "POST", endpoint(port), "{}", null,
                Duration.ofSeconds(5), ApiRetry.Policy.NOT_SENT_ONLY, slept::add, correlation());
        check.eq("refused: a closed port is a network error", "role_api_network_error", refused.failure().code);
        check.eq("refused: in the connect phase", "connect", refused.failure().phase);
        check.eq("refused: provably not sent", "not_sent", refused.failure().delivery);
        check.eq("refused: with a fixed cause", "connect_refused", refused.failure().cause);
        check.eq("refused: resent within the bound", 3, refused.attempts().size());
        check.that("refused: so nothing was sent at all", refused.nothingSent());
        check.eq("refused: the backoff grows per attempt", List.of(500L, 1000L), slept);

        AtomicReference<Script> late = new AtomicReference<>();
        ApiRetry.Outcome recovered = ApiRetry.send(connection(port), "POST", endpoint(port), "{}", null,
                Duration.ofSeconds(5), ApiRetry.Policy.NOT_SENT_ONLY, millis -> {
                    try { late.set(new Script(port, Step.reply(200, "", "{\"ok\":true}"))); }
                    catch (IOException failed) { throw new IllegalStateException(failed); }
                }, correlation());
        try (Script server = late.get()) {
            check.eq("refused then up: the resend reaches the server", 200, recovered.reply().status());
            check.eq("refused then up: exactly one request was delivered", 1, server.requests.get());
            check.eq("refused then up: the first attempt stays recorded as not sent", "not_sent",
                    recovered.attempts().get(0).get("delivery"));
            check.eq("refused then up: the second as answered", "answered", recovered.attempts().get(1).get("delivery"));
        }

        try (Script server = new Script(Step.DROP, Step.reply(200, "", "{}"))) {
            ApiRetry.Outcome dropped = send(server, ApiRetry.Policy.CHECK, slept::add, Duration.ofSeconds(5));
            check.eq("dropped: a connection closed after the request is a network error",
                    "role_api_network_error", dropped.failure().code);
            check.eq("dropped: its outcome is unknown, not 'not sent'", "unknown", dropped.failure().delivery);
            check.eq("dropped: in the headers phase", "headers", dropped.failure().phase);
            check.eq("dropped: and even a check never resends an unknown outcome", 1, server.requests.get());
            check.that("dropped: it may have been billed", !dropped.nothingSent());
        }
        try (Script server = new Script(Step.CUT, Step.reply(200, "", "{}"))) {
            ApiRetry.Outcome cut = send(server, ApiRetry.Policy.CHECK, slept::add, Duration.ofSeconds(5));
            check.eq("cut body: the failure is in the body phase", "body", cut.failure().phase);
            check.eq("cut body: after headers the outcome is unknown", "unknown", cut.failure().delivery);
            check.eq("cut body: and is not resent", 1, server.requests.get());
        }
    }

    // ---------------------------------------------------------------- deadline and cancel

    private static void slowBodyAndCancel(Check check) throws Exception {
        try (Script server = new Script(Step.STALL)) {
            long start = System.nanoTime();
            ApiRetry.Outcome slow = send(server, ApiRetry.Policy.CHECK, millis -> {}, Duration.ofMillis(400));
            long took = Duration.ofNanos(System.nanoTime() - start).toMillis();
            check.eq("slow body: the deadline covers a body stalled after headers", "role_api_timeout", slow.failure().code);
            check.eq("slow body: phase deadline", "deadline", slow.failure().phase);
            check.eq("slow body: cause names the body", "body_deadline", slow.failure().cause);
            check.eq("slow body: outcome unknown", "unknown", slow.failure().delivery);
            check.eq("slow body: not resent", 1, server.requests.get());
            check.that("slow body: returns near the deadline, took " + took + "ms", took < 2500);
        }
        try (Script server = new Script(Step.STALL)) {
            AtomicReference<ApiRetry.Outcome> result = new AtomicReference<>();
            Thread caller = new Thread(() -> result.set(send(server, ApiRetry.Policy.CHECK, millis -> {},
                    Duration.ofSeconds(20))));
            caller.start();
            server.awaitRequests(1, 5000);
            caller.interrupt();
            caller.join(5000);
            check.that("cancel: an interrupted call returns promptly", !caller.isAlive());
            ApiRetry.Outcome cancelled = result.get();
            check.eq("cancel: has its own code", "role_api_interrupted", cancelled == null ? null : cancelled.failure().code);
            check.eq("cancel: phase cancel", "cancel", cancelled == null ? null : cancelled.failure().phase);
            check.eq("cancel: and is never resent", 1, server.requests.get());
        }
        int port = freePort();
        AtomicReference<ApiRetry.Outcome> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(ApiRetry.send(connection(port), "POST", endpoint(port), "{}", null,
                Duration.ofSeconds(20), ApiRetry.Policy.NOT_SENT_ONLY,
                millis -> { throw new InterruptedException("operator cancelled"); }, correlation())));
        caller.start();
        caller.join(5000);
        check.eq("cancel during backoff: stops instead of resending", "cancelled",
                result.get() == null ? null : result.get().stoppedBecause());
        check.eq("cancel during backoff: one attempt only", 1, result.get() == null ? -1 : result.get().attempts().size());
        check.eq("cancel during backoff: logical failure is cancellation, not the earlier network error",
                "role_api_interrupted", result.get() == null || result.get().failure() == null
                        ? null : result.get().failure().code);
        check.eq("cancel during backoff: phase prevents API fallback", "cancel",
                result.get() == null || result.get().failure() == null ? null : result.get().failure().phase);
    }

    // ---------------------------------------------------------------- roles

    private static void roleCallsAreNotResent(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-api-resilience-");
        Path prompt = scratch.resolve("prompt.md");
        Files.writeString(prompt, "review the supplied context");
        try (Script server = new Script(Step.reply(502, "", error("upstream exploded with detail")),
                Step.reply(200, "", completion("test/model", "stop")))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role 502: classified as a provider error", "role_api_provider_error", result.code());
            check.eq("role 502: one request, no automatic resend", 1, server.requests.get());
            check.eq("role 502: the phase is the HTTP status", "http_status", result.evidence().get("failure_phase"));
            check.eq("role 502: the provider answered", "answered", result.evidence().get("delivery"));
            check.eq("role 502: the attempt count is recorded", 1L, result.evidence().get("api_attempt_count"));
            check.that("role 502: no raw response head is stored", !result.evidence().containsKey("body_head"));
            check.that("role 502: no provider message text is stored",
                    !Json.write(result.evidence()).contains("exploded"));
        }
        try (Script server = new Script(Step.reply(429, "Retry-After: 1\r\n", error("slow")),
                Step.reply(200, "", completion("test/model", "stop")))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role 429: rate limited", "role_api_rate_limited", result.code());
            check.eq("role 429: never resent by a role", 1, server.requests.get());
        }
        int port = freePort();
        RoleExecutor.Result refused = executor().execute(request(profile(connection(port)), prompt));
        check.eq("role refused: a network error", "role_api_network_error", refused.code());
        check.eq("role refused: not a vendor turn, so no budget is spent", false,
                refused.evidence().get("vendor_turn_started"));
        check.eq("role refused: three counted attempts", 3L, refused.evidence().get("api_attempt_count"));
        check.eq("role refused: delivery not_sent", "not_sent", refused.evidence().get("delivery"));
        try (Script server = new Script(Step.DROP, Step.reply(200, "", completion("test/model", "stop")))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role dropped: one request only", 1, server.requests.get());
            check.eq("role dropped: still a vendor turn, it may have been billed", true,
                    result.evidence().get("vendor_turn_started"));
            check.eq("role dropped: outcome unknown", "unknown", result.evidence().get("delivery"));
        }
        try (Script server = new Script(Step.reply(200, "", completion("other/model", "stop")))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role wrong model: refused", "role_api_model_mismatch", result.code());
            check.eq("role wrong model: identity phase", "identity", result.evidence().get("failure_phase"));
            check.eq("role wrong model: one request", 1, server.requests.get());
        }
        try (Script server = new Script(Step.reply(200, "", completion("test/model", "length")))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role output limit: truncation code", "role_api_output_truncated", result.code());
            check.eq("role output limit: its own phase", "output_limit", result.evidence().get("failure_phase"));
        }
        try (Script server = new Script(Step.reply(200, "", "not json at all"))) {
            RoleExecutor.Result result = executor().execute(request(profile(connection(server.port)), prompt));
            check.eq("role invalid JSON: unreadable", "role_api_response_unreadable", result.code());
            check.eq("role invalid JSON: parse phase", "parse", result.evidence().get("failure_phase"));
        }
        for (String code : List.of("role_api_network_error", "role_api_timeout", "role_api_provider_error",
                "role_api_rate_limited", "role_api_output_truncated", "role_api_model_mismatch")) {
            check.that("an API transport failure is not a verdict on the work: " + code,
                    dev.warden.run.TaskLoop.infrastructureReason(code) != null);
        }
        check.eq("a changed connection is left to the role contract check", null,
                dev.warden.run.TaskLoop.infrastructureReason("role_api_connection_changed"));
    }

    // ---------------------------------------------------------------- safety

    private static void failureEvidenceIsSafe(Check check) throws Exception {
        try (Script server = new Script(Step.reply(401, "X-Request-Id-Echo: " + SECRET + "\r\n",
                "{\"error\":{\"message\":\"key " + SECRET + " is wrong\",\"code\":\"invalid_api_key\"}}"))) {
            ApiRetry.Outcome outcome = ApiRetry.send(connection(server.port), "POST", endpoint(server.port), "{}",
                    SECRET, Duration.ofSeconds(5), ApiRetry.Policy.CHECK, millis -> {}, correlation());
            Map<String, Object> evidence = new LinkedHashMap<>();
            outcome.record(evidence);
            ApiExecutor.providerError(ApiExecutor.object(outcome.reply().body()), SECRET, evidence);
            check.that("safety: a reflected credential is in no attempt row or summary",
                    !Json.write(evidence).contains(SECRET));
            check.eq("safety: the provider's short error code is kept", "invalid_api_key", evidence.get("provider_error_code"));
            check.that("safety: the provider's message is not", !Json.write(evidence).contains("is wrong"));
        }
        int port = freePort();
        ApiRetry.Outcome refused = ApiRetry.send(connection(port), "POST", endpoint(port), "{}", SECRET,
                Duration.ofSeconds(5), ApiRetry.Policy.NONE, millis -> {}, correlation());
        String retained = Json.write(refused.attempts());
        check.that("safety: no exception text is stored for a transport failure",
                !retained.contains("Exception") && !retained.contains("127.0.0.1"));
    }

    private static void unknownCostIsNeverZero(Check check) {
        Map<String, Object> first = new LinkedHashMap<>(Map.of("delivery", "unknown"));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("delivery", "answered", "cost_usd", 0.002));
        ApiRetry.Outcome outcome = new ApiRetry.Outcome(null, null, List.of(first, second),
                Map.of("correlation_id", "c"), ApiRetry.Policy.CHECK, null);
        Map<String, Object> evidence = new LinkedHashMap<>(Map.of("cost_usd", 0.002, "cost_status", "reported"));
        outcome.settleCost(evidence);
        check.eq("cost: an unpriced attempt that may have billed makes the total unknown", "unknown",
                evidence.get("cost_status"));
        check.that("cost: and the total is absent rather than the last reply's price",
                !evidence.containsKey("cost_usd"));
        check.eq("cost: the known part is kept as a subtotal", 0.002, evidence.get("reported_cost_usd_subtotal"));
        Map<String, Object> free = new LinkedHashMap<>(Map.of("delivery", "not_sent"));
        outcome = new ApiRetry.Outcome(null, null, List.of(free, second), Map.of("correlation_id", "c"),
                ApiRetry.Policy.CHECK, null);
        evidence = new LinkedHashMap<>(Map.of("cost_usd", 0.002, "cost_status", "reported"));
        outcome.settleCost(evidence);
        check.eq("cost: an attempt that never left is free, so the total stays known", 0.002, evidence.get("cost_usd"));
        Map<String, Object> unpriced = new LinkedHashMap<>(Map.of("delivery", "answered"));
        outcome = new ApiRetry.Outcome(null, null, List.of(unpriced), Map.of("correlation_id", "c"),
                ApiRetry.Policy.CHECK, null);
        evidence = new LinkedHashMap<>(Map.of("cost_status", "unknown"));
        outcome.settleCost(evidence);
        check.that("cost: a single unpriced attempt is not settled to zero", !evidence.containsKey("cost_usd"));
    }

    // ---------------------------------------------------------------- the writer's relay

    private static void relayNeverDuplicatesEdits(Check check) throws Exception {
        String toolCall = Json.write(Map.of("id", "turn-1", "model", "model-one", "choices", List.of(Map.of(
                "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls", List.of(
                        Map.of("id", "call-1", "type", "function", "function",
                                Map.of("name", "edit", "arguments", "{\"path\":\"a.txt\"}"))))))));
        // The upstream drops the connection after reading the request: the edit may already
        // have been generated and billed. A resend could hand OpenCode a second edit.
        try (Script upstream = new Script(Step.DROP, Step.reply(200, "", toolCall))) {
            try (CodingRelay relay = new CodingRelay(connection(upstream.port), "model-one", SECRET, Duration.ofSeconds(20))) {
                relay.sleeper = millis -> {};
                HttpResponse<String> answer = post(relay);
                check.eq("relay dropped: the writer gets a failure, not a guess", 502, answer.statusCode());
                check.eq("relay dropped: upstream saw exactly one request", 1, upstream.requests.get());
                Map<String, Object> evidence = relay.evidence();
                check.eq("relay dropped: the failure is classified", "role_api_network_error", evidence.get("failure"));
                Map<?, ?> detail = (Map<?, ?>) evidence.get("failure_detail");
                check.eq("relay dropped: and recorded as an unknown outcome", "unknown",
                        detail == null ? null : detail.get("delivery"));
                check.that("relay dropped: the credential is not in the evidence", !Json.write(evidence).contains(SECRET));
            }
        }
        int port = freePort();
        AtomicReference<Script> late = new AtomicReference<>();
        try (CodingRelay relay = new CodingRelay(connection(port), "model-one", SECRET, Duration.ofSeconds(20))) {
            relay.sleeper = millis -> {
                if (late.get() == null) {
                    try { late.set(new Script(port, Step.reply(200, "", toolCall))); }
                    catch (IOException failed) { throw new IllegalStateException(failed); }
                }
            };
            HttpResponse<String> answer = post(relay);
            try (Script upstream = late.get()) {
                check.eq("relay not sent: a request that never left is sent once it can be", 200, answer.statusCode());
                check.eq("relay not sent: upstream produced exactly one edit", 1, upstream == null ? -1 : upstream.requests.get());
                check.eq("relay not sent: and the writer received exactly one tool call", 1,
                        answer.body().split("\"call-1\"", -1).length - 1);
                List<?> attempts = (List<?>) relay.evidence().get("upstream_attempts");
                check.eq("relay not sent: both attempts are on the record", 2, attempts == null ? -1 : attempts.size());
            }
        }
    }

    private static HttpResponse<String> post(CodingRelay relay) throws Exception {
        Map<String, Object> body = Map.of("model", "model-one", "messages", List.of(Map.of("role", "user", "content", "go")),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "edit",
                        "parameters", Map.of("type", "object")))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(relay.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + relay.bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static ApiRetry.Outcome send(Script server, ApiRetry.Policy policy, ApiRetry.Sleeper sleeper, Duration limit) {
        return ApiRetry.send(connection(server.port), "POST", endpoint(server.port), "{\"probe\":true}", null,
                limit, policy, sleeper, correlation());
    }

    private static Map<String, Object> correlation() {
        return ApiRetry.correlation("run-resilience", "api-test", "check", "ping");
    }

    private static ApiExecutor executor() {
        return new ApiExecutor(System::getenv, Duration.ofSeconds(10), null, ApiRetry.Policy.NOT_SENT_ONLY, millis -> {});
    }

    private static Connection connection(int port) {
        return new Connection("test", "custom", "openai_chat", "http://127.0.0.1:" + port + "/v1", null, true);
    }

    private static URI endpoint(int port) { return URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"); }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Profile profile(Connection connection) {
        return Profile.parse("""
                version: 1
                profile: api-test
                role: reviewer
                model_vendor: deepseek
                model: test/model
                runner: api
                connection: test
                read_only: true
                limits: { wall_clock_minutes: 1, max_output_tokens: 128 }
                artifact:
                  required_fields: [status, verdict, summary, findings]
                """, "test.yaml").withConnection(connection);
    }

    private static RoleExecutor.Request request(Profile profile, Path prompt) {
        return new RoleExecutor.Request("run-api", "workflow-api", "reviewer", profile, null, null,
                prompt.getParent(), prompt.getParent(), prompt, null, "", "reviewer", List.of());
    }

    private static String error(String message) {
        return Json.write(Map.of("error", Map.of("message", message, "code", "synthetic_error")));
    }

    private static String completion(String model, String finish) {
        String artifact = Json.write(Map.of("status", "completed", "verdict", "pass", "summary", "checked",
                "findings", List.of()));
        return Json.write(Map.of("model", model, "id", "generation-test",
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7),
                "choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", artifact)))));
    }

    /** What one incoming request gets. */
    private interface Step {
        void act(Socket socket, OutputStream out, int number) throws Exception;

        static Step reply(int status, String headers, String body) {
            return (socket, out, number) -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 " + status + " Scripted\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + bytes.length + "\r\nConnection: close\r\nX-Request-Id: req-" + number
                        + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
            };
        }

        /** Read the request, then hang up without a word. */
        Step DROP = (socket, out, number) -> socket.close();

        /** Headers and a partial fixed-length body, then an orderly EOF. */
        Step CUT = (socket, out, number) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\n"
                    + "Connection: close\r\n\r\n{\"model\":\"test").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // FIN follows the queued response bytes. An abortive SO_LINGER(0) reset can
            // discard those bytes before HttpClient reads them on Windows, which tests a
            // missing response header rather than the intended incomplete body.
            socket.shutdownOutput();
            socket.close();
        };

        /** Headers at once, then a body that never comes. */
        Step STALL = (socket, out, number) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(10_000);
        };
    }

    /** A one-request-per-connection HTTP/1.1 server that follows a script, last step repeating. */
    private static final class Script implements AutoCloseable {
        private final ServerSocket server;
        private final List<Step> steps;
        private final int port;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());

        Script(Step... steps) throws IOException { this(0, steps); }

        Script(int port, Step... steps) throws IOException {
            this.server = new ServerSocket();
            this.server.setReuseAddress(true);
            this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            this.port = server.getLocalPort();
            this.steps = List.of(steps);
            Thread accept = new Thread(this::accept, "api-resilience-fixture");
            accept.setDaemon(true);
            accept.start();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    open.add(socket);
                    Thread handler = new Thread(() -> handle(socket), "api-resilience-request");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                int length = readHeaders(in);
                in.readNBytes(length);
                int number = requests.incrementAndGet();
                steps.get(Math.min(number - 1, steps.size() - 1)).act(socket, socket.getOutputStream(), number);
            } catch (Exception ignored) {
                // A cancelled or timed-out client closes its end; that is the point of the test.
            }
        }

        private static int readHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            int next;
            while (matched < 4 && (next = in.read()) != -1) {
                head.write(next);
                matched = (next == '\r' && (matched == 0 || matched == 2)) || (next == '\n' && (matched == 1 || matched == 3))
                        ? matched + 1 : 0;
            }
            for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            return 0;
        }

        void awaitRequests(int count, long millis) throws InterruptedException {
            long until = System.currentTimeMillis() + millis;
            while (requests.get() < count && System.currentTimeMillis() < until) Thread.sleep(10);
        }

        @Override public void close() throws IOException {
            server.close();
            synchronized (open) {
                for (Socket socket : open) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        }
    }
}
