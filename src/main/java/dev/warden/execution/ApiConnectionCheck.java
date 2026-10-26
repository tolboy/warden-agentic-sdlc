package dev.warden.execution;

import dev.warden.config.Connection;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Credential checks never dispatch an inference or stamp a model as verified. */
public final class ApiConnectionCheck {
    private ApiConnectionCheck() { }

    public static Map<String, Object> check(Connection connection, Function<String, String> environment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection.id());
        result.put("provider", connection.provider());
        result.put("auth_verified", false);
        result.put("inference_performed", false);
        result.put("model_verified", false);
        if (!connection.enabled()) {
            result.put("ok", false);
            result.put("code", "role_api_connection_disabled");
            return result;
        }
        String token;
        try {
            token = new CredentialResolver(environment).resolve(connection);
        } catch (RuntimeException unavailable) {
            result.put("ok", false);
            result.put("credential_configured", false);
            result.put("code", "role_api_key_missing");
            return result;
        }
        result.put("credential_configured", token != null);
        if (!"openrouter".equals(connection.provider())) {
            result.put("ok", true);
            result.put("code", token == null ? "no_auth_required" : "credential_configured_auth_unverified");
            return result;
        }
        try {
            String base = connection.baseUrl().replaceAll("/+$", "");
            ApiExecutor.HttpReply reply = ApiExecutor.call(connection, "GET", URI.create(base + "/key"),
                    null, token, Duration.ofSeconds(15));
            Map<String, Object> body = ApiExecutor.object(ApiExecutor.redact(reply.body(), token));
            result.put("http_status", (long) reply.status());
            String error = ApiExecutor.errorCode(reply.status(), body);
            if (error != null || body == null || !(body.get("data") instanceof Map<?, ?>)) {
                result.put("ok", false);
                result.put("code", error == null ? "role_api_response_unreadable" : error);
            } else {
                result.put("ok", true);
                result.put("auth_verified", true);
                result.put("code", "auth_verified");
            }
        } catch (ApiExecutor.CallFailure failure) {
            result.put("ok", false);
            result.put("code", failure.code);
        }
        return result;
    }
}
