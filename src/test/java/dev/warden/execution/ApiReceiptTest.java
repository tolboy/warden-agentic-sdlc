package dev.warden.execution;

import dev.warden.config.Connection;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider normalization is tested without an HTTP request to a paid provider. */
public final class ApiReceiptTest implements Suite {
    @Override public String name() { return "api-receipts"; }

    @Override public void run(Check check) throws Exception {
        Connection openrouter = new Connection("router", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "env:TEST_KEY", true);
        HttpHeaders headers = HttpHeaders.of(Map.of("X-Request-Id", List.of("request-123")), (name, value) -> true);
        Map<String, Object> evidence = new LinkedHashMap<>();
        ApiExecutor.recordReceipt(openrouter, envelope(0.0123), headers, evidence, null);
        check.eq("OpenRouter usage.cost becomes charged USD", 0.0123, evidence.get("cost_usd"));
        check.eq("price source is the provider envelope", "openrouter.usage.cost", evidence.get("cost_source"));
        check.eq("price currency is explicit", "USD", evidence.get("cost_currency"));
        check.eq("reported cost status is explicit", "reported", evidence.get("cost_status"));
        check.eq("request receipt comes from response header", "request-123", evidence.get("request_id"));
        check.eq("generation receipt comes from response body", "generation-123", evidence.get("generation_id"));
        check.eq("upstream provider is distinct from router", "upstream-test", evidence.get("upstream_provider"));
        check.eq("served model is distinct from provider", "test/model", evidence.get("model_reported"));

        for (Object invalid : List.of(-0.01, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, "0.0123")) {
            evidence = new LinkedHashMap<>();
            ApiExecutor.recordReceipt(openrouter, envelope(invalid), headers, evidence, null);
            check.that("untrusted/nonfinite/negative price is not charged: " + invalid,
                    !evidence.containsKey("cost_usd") && !evidence.containsKey("cost_source"));
        }
        evidence = new LinkedHashMap<>();
        ApiExecutor.recordReceipt(openrouter, envelope(0), headers, evidence, null);
        check.eq("an explicit provider-reported zero is distinct from unknown", 0.0, evidence.get("cost_usd"));
        evidence = new LinkedHashMap<>();
        Map<String, Object> noUsage = new LinkedHashMap<>(envelope(1.0));
        noUsage.remove("usage");
        noUsage.put("choices", List.of(Map.of("message", Map.of("content", "{\"cost_usd\":1000000}"))));
        ApiExecutor.recordReceipt(openrouter, noUsage, headers, evidence, null);
        check.that("model-authored cost is not accepted as provider billing", !evidence.containsKey("cost_usd"));

        String secret = "synthetic-receipt-key";
        HttpHeaders reflected = HttpHeaders.of(Map.of("X-Request-Id", List.of(secret),
                "Retry-After", List.of(secret), "X-Gonka-Fallback", List.of(secret)), (name, value) -> true);
        Map<String, Object> reflectedBody = new LinkedHashMap<>(envelope(0.1));
        reflectedBody.put("id", secret);
        reflectedBody.put("model", secret);
        reflectedBody.put("provider", secret);
        evidence = new LinkedHashMap<>();
        ApiExecutor.recordReceipt(openrouter, reflectedBody, reflected, evidence, secret);
        check.that("receipt headers and body metadata cannot retain a reflected credential",
                !Json.write(evidence).contains(secret));
        check.eq("redacted request ID remains explicit", "<redacted>", evidence.get("request_id"));
        check.eq("redacted generation ID remains explicit", "<redacted>", evidence.get("generation_id"));

        Connection gonka = new Connection("gonka", "gonkarouter", "openai_chat",
                "https://api.gonkarouter.io/v1", "env:TEST_KEY", true);
        HttpHeaders gonkaHeaders = HttpHeaders.of(Map.of("X-Request-Id", List.of("gonka-request"),
                "X-Gonka-Fallback", List.of("true")), (name, value) -> true);
        evidence = new LinkedHashMap<>();
        ApiExecutor.recordReceipt(gonka, envelope(0.002), gonkaHeaders, evidence, null);
        check.eq("Gonka request receipt is retained", "gonka-request", evidence.get("request_id"));
        check.eq("unexpected Gonka fallback stays visible", "true", evidence.get("provider_fallback"));
        check.that("Gonka cost is unknown even when similarly named usage exists", !evidence.containsKey("cost_usd"));
        check.eq("Gonka request explicitly disables model fallback", "true",
                ApiExecutor.providerHeaders(gonka).get("X-Gonka-No-Fallback"));

        Map<String, Object> body = ApiExecutor.completionBody(openrouter, "test/model", "context", 128);
        Map<?, ?> routing = (Map<?, ?>) body.get("provider");
        check.eq("OpenRouter requires its declared request parameters", true, routing.get("require_parameters"));
        check.eq("OpenRouter fallback is disabled", false, routing.get("allow_fallbacks"));
        check.that("no alternate models or router mode are injected", !body.containsKey("models") && !body.containsKey("route"));
        check.eq("provider request keeps its output bound", 128, body.get("max_tokens"));
        check.that("custom requests do not receive unsupported router options",
                !ApiExecutor.completionBody(new Connection("local", "custom", "openai_chat",
                        "http://127.0.0.1:1/v1", null, true), "test/model", "context", 128).containsKey("provider"));

        try {
            ApiExecutor.call(openrouter, "GET", URI.create("https://example.invalid/api/v1/key"),
                    null, secret, Duration.ofMillis(100));
            check.that("a different origin is refused before sending a secret", false);
        } catch (ApiExecutor.CallFailure refused) {
            check.eq("origin binding has an explicit error", "role_api_origin_mismatch", refused.code);
        }
    }

    private static Map<String, Object> envelope(Object cost) {
        return Map.of("model", "test/model", "id", "generation-123", "provider", "upstream-test",
                "usage", Map.of("cost", cost, "prompt_tokens", 100, "completion_tokens", 20));
    }
}
