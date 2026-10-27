package dev.warden.execution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.warden.config.Connection;
import dev.warden.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Run-scoped, bounded observer for explicit coding probes. Never gives the agent an upstream key.
 */
final class CodingRelay implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers =
            Executors.newSingleThreadExecutor();
    private final Connection connection;
    private final String model, secret, bearer;
    private final long deadline;
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private final Map<String, String> calls = new HashMap<>();
    private final Set<String> observedTools = new TreeSet<>();
    private String failure;
    private int turns;

    CodingRelay(Connection connection, String model, String secret, Duration limit)
            throws IOException {
        this.connection = connection;
        this.model = model;
        this.secret = secret;
        deadline = System.nanoTime() + limit.toNanos();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    String bearer() {
        return bearer;
    }

    synchronized Map<String, Object> evidence() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", turns);
        result.put("responses", List.copyOf(receipts));
        result.put("observed_tool_results", List.copyOf(observedTools));
        result.put(
                "identity_confirmed",
                failure == null && !receipts.isEmpty() && receipts.size() == turns);
        result.put("cost_status", "provider_usage_recorded_no_strict_cap");
        if (failure != null) result.put("failure", failure);
        return result;
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try {
            String authority = "127.0.0.1:" + server.getAddress().getPort();
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))
                    || exchange.getRequestHeaders().getFirst("Origin") != null
                    || supplied == null
                    || !MessageDigest.isEqual(
                            ("Bearer " + bearer).getBytes(StandardCharsets.UTF_8),
                            supplied.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 403, "application/json", "{}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/v1/chat/completions".equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                send(exchange, 404, "application/json", "{}");
                return;
            }
            if (failure != null || turns >= 8 || System.nanoTime() >= deadline) {
                if (failure == null) failure = "probe_turn_or_time_limit";
                // This dispatch cannot resume. A retryable status would make the SDK wait
                // and would obscure the first upstream failure without permitting progress.
                send(
                        exchange,
                        400,
                        "application/json",
                        Json.write(
                                Map.of(
                                        "error",
                                        Map.of(
                                                "message",
                                                "Warden coding probe stopped",
                                                "type",
                                                "invalid_request_error",
                                                "code",
                                                failure))));
                return;
            }
            byte[] raw = exchange.getRequestBody().readNBytes(512 * 1024 + 1);
            if (raw.length > 512 * 1024) {
                failure = "probe_request_too_large";
                send(exchange, 413, "application/json", "{}");
                return;
            }
            Map<String, Object> body =
                    Json.parseObject(
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(raw))
                                    .toString());
            if (!model.equals(body.get("model"))) {
                failure = "probe_requested_model_mismatch";
                send(exchange, 400, "application/json", "{}");
                return;
            }
            noteToolResults(body.get("messages"));
            body.put("stream", false);
            body.remove("stream_options");
            body.remove("max_completion_tokens");
            body.put("max_tokens", 2048);
            if ("openrouter".equals(connection.provider()))
                body.put("provider", Map.of("require_parameters", true, "allow_fallbacks", false));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                failure = "probe_deadline";
                send(exchange, 504, "application/json", "{}");
                return;
            }
            turns++;
            ApiExecutor.HttpReply reply =
                    ApiExecutor.call(
                            connection,
                            "POST",
                            URI.create(connection.endpoint()),
                            Json.write(body),
                            secret,
                            Duration.ofNanos(
                                    Math.min(Duration.ofSeconds(45).toNanos(), remaining)));
            if (reply.status() != 200) {
                failure = "probe_upstream_http_" + reply.status();
                send(exchange, 502, "application/json", "{}");
                return;
            }
            // Identity is checked before OpenCode receives content or a tool request.
            Map<String, Object> parsed = Json.parseObject(reply.body());
            if (!model.equals(parsed.get("model"))) {
                failure = "probe_served_model_mismatch";
                send(exchange, 502, "application/json", "{}");
                return;
            }
            if (!(parsed.get("choices") instanceof List<?> choices)
                    || choices.size() != 1
                    || !(choices.getFirst() instanceof Map<?, ?> choice)
                    || !(choice.get("message") instanceof Map<?, ?> message)) {
                failure = "probe_upstream_protocol";
                send(exchange, 502, "application/json", "{}");
                return;
            }
            String finish = String.valueOf(choice.get("finish_reason"));
            if (!Set.of("stop", "tool_calls").contains(finish)) {
                failure = "probe_incomplete_response";
                send(exchange, 502, "application/json", "{}");
                return;
            }
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("model", model);
            receipt.put("finish_reason", finish);
            receipt.put("id", parsed.get("id"));
            receipt.put("usage", parsed.get("usage"));
            receipts.add(receipt);
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            if (message.get("content") instanceof String content) delta.put("content", content);
            if (message.get("tool_calls") instanceof List<?> tools) {
                List<Map<String, Object>> indexed = new ArrayList<>();
                for (int index = 0; index < tools.size(); index++) {
                    if (!(tools.get(index) instanceof Map<?, ?> tool)
                            || !(tool.get("function") instanceof Map<?, ?> function))
                        throw new IllegalArgumentException();
                    Map<String, Object> copy = new LinkedHashMap<>();
                    tool.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    copy.put("index", index);
                    indexed.add(copy);
                    if (tool.get("id") instanceof String id
                            && function.get("name") instanceof String name) calls.put(id, name);
                }
                delta.put("tool_calls", indexed);
            }
            String id = parsed.get("id") instanceof String value ? value : "warden-probe";
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of(Map.of("index", 0, "delta", delta)));
            String first = "data: " + Json.write(chunk) + "\n\n";
            chunk.put(
                    "choices",
                    List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)));
            if (parsed.get("usage") != null) chunk.put("usage", parsed.get("usage"));
            String response = first + "data: " + Json.write(chunk) + "\n\ndata: [DONE]\n\n";
            if (secret != null && response.contains(secret)) {
                failure = "probe_credential_reflected";
                send(exchange, 502, "application/json", "{}");
                return;
            }
            send(exchange, 200, "text/event-stream", response);
        } catch (ApiExecutor.CallFailure failed) {
            failure = failed.code;
            send(exchange, 502, "application/json", "{}");
        } catch (RuntimeException | java.nio.charset.CharacterCodingException invalid) {
            failure = "probe_relay_protocol";
            send(exchange, 502, "application/json", "{}");
        } finally {
            exchange.close();
        }
    }

    private void noteToolResults(Object value) {
        if (!(value instanceof List<?> messages)) return;
        for (Object item : messages)
            if (item instanceof Map<?, ?> message && "tool".equals(message.get("role"))) {
                String name = calls.get(message.get("tool_call_id"));
                if (name != null) observedTools.add(name);
            }
    }

    private static void send(HttpExchange exchange, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
