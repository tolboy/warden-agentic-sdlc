package dev.warden.execution;

import com.sun.net.httpserver.HttpServer;

import dev.warden.config.Connection;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real loopback HTTP verifies the trusted boundary before any paid coding probe. */
public final class CodingRelayTest implements Suite {
    @Override
    public String name() {
        return "coding-relay";
    }

    @Override
    public void run(Check check) throws Exception {
        java.nio.file.Path artifact =
                java.nio.file.Files.createTempFile("warden-coding-artifact-", ".json");
        try {
            java.nio.file.Files.writeString(artifact, "{status:done,summary:greeting\" fixed}");
            check.that(
                    "malformed model artifact is refused without a parser exception",
                    !CodingProbe.validArtifact(artifact));
            java.nio.file.Files.writeString(
                    artifact, "{\"status\":\"done\",\"summary\":\"fixed\"}");
            check.that(
                    "a complete structured artifact can pass", CodingProbe.validArtifact(artifact));
            java.nio.file.Files.writeString(artifact, "{\"status\":\"done\"}");
            check.that(
                    "a status without summary cannot complete the probe",
                    !CodingProbe.validArtifact(artifact));
        } finally {
            java.nio.file.Files.deleteIfExists(artifact);
        }
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<Map<String, Object>> posted = new AtomicReference<>();
        AtomicReference<String> served = new AtomicReference<>("model-one");
        AtomicReference<String> upstreamAuth = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/v1/chat/completions",
                exchange -> {
                    hits.incrementAndGet();
                    upstreamAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    posted.set(
                            Json.parseObject(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    Map<String, Object> message =
                            hits.get() == 1
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "call-one",
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    "read",
                                                                    "arguments",
                                                                    "{}"))))
                                    : Map.of("role", "assistant", "content", "safe reply");
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "id",
                                                    "receipt-one",
                                                    "model",
                                                    served.get(),
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "message",
                                                                    message,
                                                                    "finish_reason",
                                                                    hits.get() == 1
                                                                            ? "tool_calls"
                                                                            : "stop")),
                                                    "usage",
                                                    Map.of(
                                                            "prompt_tokens",
                                                            12,
                                                            "completion_tokens",
                                                            8)))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                    exchange.close();
                });
        upstream.start();
        var c =
                new Connection(
                        "local",
                        "custom",
                        "openai_chat",
                        "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                        "env:TEST_KEY",
                        true);
        try {
            try (var relay =
                    new CodingRelay(
                            c,
                            "model-one",
                            "synthetic-upstream-credential",
                            Duration.ofSeconds(20))) {
                var denied = post(relay, "wrong-bearer", Map.of("model", "model-one"));
                check.eq("an untrusted child cannot forward requests", 403, denied.statusCode());
                check.eq("denied auth never reaches upstream", 0, hits.get());
                var first =
                        post(
                                relay,
                                relay.bearer(),
                                Map.of(
                                        "model",
                                        "model-one",
                                        "stream",
                                        true,
                                        "max_tokens",
                                        99999,
                                        "messages",
                                        List.of()));
                check.eq("a matching model response becomes SSE", 200, first.statusCode());
                check.contains("tool calls retain an SSE index", first.body(), "\"index\":0");
                check.that(
                        "the upstream credential never reaches the child",
                        !first.body().contains("synthetic-upstream-credential"));
                check.eq(
                        "only relay installs the upstream bearer",
                        "Bearer synthetic-upstream-credential",
                        upstreamAuth.get());
                check.eq(
                        "the upstream body is bounded nonstreaming",
                        false,
                        posted.get().get("stream"));
                check.eq(
                        "the requested output limit is clamped",
                        2048L,
                        posted.get().get("max_tokens"));
                post(
                        relay,
                        relay.bearer(),
                        Map.of(
                                "model",
                                "model-one",
                                "messages",
                                List.of(
                                        Map.of(
                                                "role",
                                                "tool",
                                                "tool_call_id",
                                                "call-one",
                                                "content",
                                                "file contents"))));
                check.eq(
                        "tool results link to observed upstream requests",
                        List.of("read"),
                        relay.evidence().get("observed_tool_results"));
                for (int i = 2; i < 8; i++)
                    post(relay, relay.bearer(), Map.of("model", "model-one"));
                check.eq(
                        "ninth inference is refused",
                        400,
                        post(relay, relay.bearer(), Map.of("model", "model-one")).statusCode());
                check.eq("turn cap prevents another upstream charge", 8, hits.get());
                check.eq(
                        "limit stop cannot yield a successful identity receipt",
                        false,
                        relay.evidence().get("identity_confirmed"));
            }
            int before = hits.get();
            try (var relay =
                    new CodingRelay(
                            c,
                            "model-one",
                            "synthetic-upstream-credential",
                            Duration.ofSeconds(20))) {
                check.eq(
                        "different requested model is refused",
                        400,
                        post(relay, relay.bearer(), Map.of("model", "model-two")).statusCode());
                check.eq("requested model mismatch never goes upstream", before, hits.get());
            }
            served.set("model-two");
            try (var relay =
                    new CodingRelay(
                            c,
                            "model-one",
                            "synthetic-upstream-credential",
                            Duration.ofSeconds(20))) {
                var mismatch = post(relay, relay.bearer(), Map.of("model", "model-one"));
                check.eq("substituted served model is refused", 502, mismatch.statusCode());
                check.that(
                        "a substituted response cannot trigger tools or expose content",
                        !mismatch.body().contains("safe reply"));
                check.eq(
                        "failed model evidence cannot pass",
                        false,
                        relay.evidence().get("identity_confirmed"));
                check.eq(
                        "a stopped dispatch refuses local retries",
                        400,
                        post(relay, relay.bearer(), Map.of("model", "model-one")).statusCode());
                check.eq(
                        "a retry preserves the original failure",
                        "probe_served_model_mismatch",
                        relay.evidence().get("failure"));
            }
            check.that(
                    "own minimal config passes validation",
                    CodingProbe.validConfig(
                            CodingProbe.config("http://127.0.0.1:9/v1", "model-one"),
                            "http://127.0.0.1:9/v1",
                            "model-one"));
            String contaminated =
                    CodingProbe.config("http://127.0.0.1:9/v1", "model-one")
                            .replace("\"plugin\":[]", "\"plugin\":[\"foreign-plugin\"]");
            check.that(
                    "external plugin config cannot pass",
                    !CodingProbe.validConfig(contaminated, "http://127.0.0.1:9/v1", "model-one"));
            String broader =
                    CodingProbe.config("http://127.0.0.1:9/v1", "model-one")
                            .replace("\"*\":\"deny\"", "\"*\":\"allow\"");
            check.that(
                    "a broader merged permission policy cannot pass",
                    !CodingProbe.validConfig(broader, "http://127.0.0.1:9/v1", "model-one"));
        } finally {
            upstream.stop(0);
        }
    }

    private HttpResponse<String> post(CodingRelay relay, String token, Map<String, Object> body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(relay.baseUrl() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
