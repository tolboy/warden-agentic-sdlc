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
        check.that(
                "a relay turn may wait longer than 45 s for a slow gateway",
                CodingRelay.turnDeadline(Duration.ofSeconds(240).toNanos())
                        .compareTo(Duration.ofSeconds(90)) >= 0);
        check.that(
                "a relay turn never outlives the dispatch budget",
                CodingRelay.turnDeadline(Duration.ofSeconds(10).toNanos())
                        .equals(Duration.ofSeconds(10)));
        check.that(
                "a slow-gateway profile may get more than four minutes",
                CodingRelay.budget(12).equals(Duration.ofMinutes(12)));
        check.that(
                "the profile wall clock still tightens the relay budget",
                CodingRelay.budget(3).equals(Duration.ofMinutes(3)));
        check.that(
                "the relay budget keeps a hard ceiling",
                CodingRelay.budget(600).equals(CodingRelay.MAX_BUDGET));
        check.that(
                "a relay turn is capped even with a large budget",
                CodingRelay.turnDeadline(Duration.ofMinutes(30).toNanos())
                        .equals(CodingRelay.TURN_CAP));
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
        AtomicReference<String> responseId = new AtomicReference<>("receipt-one");
        AtomicReference<Object> reflectedUsage = new AtomicReference<>();
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
                    Map<String, Object> usage = new LinkedHashMap<>();
                    usage.put("prompt_tokens", 12);
                    usage.put("completion_tokens", 8);
                    if (reflectedUsage.get() != null)
                        usage.put("nested", List.of(Map.of("metadata", reflectedUsage.get())));
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "id",
                                                    responseId.get(),
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
                                                    usage))
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
                                        "n",
                                        100,
                                        "models",
                                        List.of("other-model"),
                                        "plugins",
                                        List.of(Map.of("id", "web")),
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "function",
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                "read",
                                                                "parameters",
                                                                Map.of("type", "object")))),
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
                        "first coding request must use the offered tools",
                        "required",
                        posted.get().get("tool_choice"));
                check.eq(
                        "the requested output limit is clamped",
                        2048L,
                        posted.get().get("max_tokens"));
                check.eq(
                        "the upstream call can generate only one choice",
                        1L,
                        posted.get().get("n"));
                check.that(
                        "child model fallback extras are not forwarded",
                        !posted.get().containsKey("models"));
                check.that(
                        "child remote plugins are not forwarded",
                        !posted.get().containsKey("plugins"));
                post(
                        relay,
                        relay.bearer(),
                        Map.of(
                                "model",
                                "model-one",
                                "tool_choice",
                                "auto",
                                "tools",
                                List.of(
                                        Map.of(
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "read",
                                                        "parameters",
                                                        Map.of("type", "object")))),
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
                check.eq(
                        "after an observed tool result the requested tool policy is retained",
                        "auto",
                        posted.get().get("tool_choice"));
                for (int i = 2; i < 8; i++)
                    post(relay, relay.bearer(), Map.of("model", "model-one"));
                check.eq(
                        "ninth inference is refused",
                        400,
                        post(relay, relay.bearer(), Map.of("model", "model-one")).statusCode());
                check.eq("turn cap prevents another upstream charge", 8, hits.get());
                check.eq(
                        "the stop names the turn cap, not the clock",
                        "probe_turn_limit",
                        relay.evidence().get("failure"));
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
            Map<String, Object> reordered =
                    Json.parseObject(CodingProbe.config("http://127.0.0.1:9/v1", "model-one"));
            Map<String, Object> lateDeny = new LinkedHashMap<>();
            lateDeny.put("read", "allow");
            lateDeny.put("edit", "allow");
            lateDeny.put("write", "allow");
            lateDeny.put("apply_patch", "allow");
            lateDeny.put("bash", "allow");
            lateDeny.put("*", "deny");
            reordered.put("permission", lateDeny);
            check.that(
                    "late wildcard denial cannot disable tools silently",
                    !CodingProbe.validConfig(
                            Json.write(reordered), "http://127.0.0.1:9/v1", "model-one"));
            check.that(
                    "external plugin config cannot pass",
                    !CodingProbe.validConfig(contaminated, "http://127.0.0.1:9/v1", "model-one"));
            String broader =
                    CodingProbe.config("http://127.0.0.1:9/v1", "model-one")
                            .replace("\"*\":\"deny\"", "\"*\":\"allow\"");
            check.that(
                    "a broader merged permission policy cannot pass",
                    !CodingProbe.validConfig(broader, "http://127.0.0.1:9/v1", "model-one"));
            served.set("model-one");
            for (String credential :
                    List.of("synthetic-upstream-credential", "synthetic-\"quoted\\credential")) {
                for (String reflectedField : List.of("id", "nested usage")) {
                    responseId.set(reflectedField.equals("id") ? credential : "receipt-one");
                    reflectedUsage.set(reflectedField.equals("nested usage") ? credential : null);
                    try (var relay =
                            new CodingRelay(c, "model-one", credential, Duration.ofSeconds(20))) {
                        var reflected = post(relay, relay.bearer(), Map.of("model", "model-one"));
                        check.eq(
                                "reflected credential in "
                                        + reflectedField
                                        + " is refused before release",
                                502,
                                reflected.statusCode());
                        Map<String, Object> evidence = relay.evidence();
                        check.eq(
                                "reflection is recorded without provider data",
                                "probe_credential_reflected",
                                evidence.get("failure"));
                        check.eq(
                                "a reflected response cannot be retained as a model receipt",
                                List.of(),
                                evidence.get("responses"));
                        String encodedCredential = Json.write(credential);
                        encodedCredential =
                                encodedCredential.substring(1, encodedCredential.length() - 1);
                        String retained = Json.write(evidence) + reflected.body();
                        check.that(
                                "reflected credential is absent in raw and JSON-escaped forms",
                                !retained.contains(credential)
                                        && !retained.contains(encodedCredential));
                        check.eq(
                                "reflected metadata cannot establish identity",
                                false,
                                evidence.get("identity_confirmed"));
                    }
                }
            }
            responseId.set("receipt-one");
            reflectedUsage.set(null);
            partialBodyClosesBeforeEvidence(check, c, hits);
        } finally {
            upstream.stop(0);
        }
    }

    private void partialBodyClosesBeforeEvidence(
            Check check, Connection connection, AtomicInteger upstreamHits) throws Exception {
        CodingRelay relay =
                new CodingRelay(
                        connection,
                        "model-one",
                        "synthetic-upstream-credential",
                        Duration.ofSeconds(20));
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        int before = upstreamHits.get();
        try (Socket client = new Socket()) {
            client.connect(
                    new InetSocketAddress("127.0.0.1", URI.create(relay.baseUrl()).getPort()));
            String authority = "127.0.0.1:" + URI.create(relay.baseUrl()).getPort();
            String request =
                    "POST /v1/chat/completions HTTP/1.1\r\nHost: "
                            + authority
                            + "\r\nAuthorization: Bearer "
                            + relay.bearer()
                            + "\r\nContent-Type: application/json\r\nContent-Length: 256\r\n\r\n{";
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            boolean handling = false;
            while (!handling && System.nanoTime() < deadline) {
                handling =
                        Thread.getAllStackTraces().values().stream()
                                .anyMatch(
                                        stack ->
                                                Arrays.stream(stack)
                                                        .anyMatch(
                                                                frame ->
                                                                        frame.getClassName()
                                                                                        .equals(
                                                                                                CodingRelay
                                                                                                        .class
                                                                                                        .getName())
                                                                                && frame.getMethodName()
                                                                                        .equals(
                                                                                                "handle")));
                if (!handling) Thread.sleep(10);
            }
            check.that("the authorized partial body reaches the relay handler", handling);
            var stopped =
                    pool.submit(
                            () -> {
                                relay.close();
                                return relay.evidence();
                            });
            Map<String, Object> evidence = null;
            try {
                evidence = stopped.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException blocked) {
                check.that("close before snapshot cannot hang on an incomplete local body", false);
            }
            if (evidence != null) {
                check.eq(
                        "an incomplete local body sends no charged request",
                        0,
                        evidence.get("requests"));
                check.eq(
                        "an incomplete local body cannot prove model identity",
                        false,
                        evidence.get("identity_confirmed"));
            }
        } finally {
            relay.close();
            pool.shutdownNow();
            pool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        check.eq("partial body cleanup does not contact upstream", before, upstreamHits.get());
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
