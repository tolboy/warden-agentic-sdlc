package dev.warden.execution;

import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** A bounded text completion, not an agent with repository or command tools. */
public final class ApiExecutor implements RoleExecutor {
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final Function<String, String> environment;
    private final GitRepository git;
    private final Duration timeoutOverride;

    public ApiExecutor() { this(System::getenv, null); }

    public ApiExecutor(Function<String, String> environment, GitRepository git) {
        this(environment, null, git);
    }

    /** A short timeout makes the body deadline testable without a minute-long test. */
    public ApiExecutor(Function<String, String> environment, Duration timeout, GitRepository git) {
        this.environment = Objects.requireNonNullElse(environment, System::getenv);
        this.git = git;
        this.timeoutOverride = timeout;
    }

    @Override public Result execute(Request request) throws Exception {
        long started = System.nanoTime();
        Profile profile = request.profile();
        Connection connection = profile.apiConnection();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runner", "api");
        evidence.put("vendor_turn_started", false);
        if (connection == null) return failure("role_api_connection_missing", started, "", evidence);
        String dispatchedFingerprint = profile.apiFingerprint();
        if (profile.apiSettings().verificationFingerprint() != null
                && !profile.apiSettings().verificationFingerprint().equals(dispatchedFingerprint)) {
            return failure("role_api_profile_changed", started, "", evidence);
        }
        if (profile.apiSettings().verificationFingerprint() != null) {
            // A panel edit applies before the next dispatch, without rewriting an in-flight
            // attempt's frozen endpoint or authorizing a new route implicitly.
            try {
                Connection current = new dev.warden.config.ConnectionStore(profile.apiSettings().home())
                        .versioned(connection.id()).connection();
                if (!current.enabled()) return failure("role_api_connection_disabled", started, "", evidence);
                if (!current.fingerprint().equals(connection.fingerprint())) {
                    return failure("role_api_connection_changed", started, "", evidence);
                }
            } catch (IOException | RuntimeException unavailable) {
                return failure("role_api_connection_missing", started, "", evidence);
            }
        }
        evidence.put("connection", connection.id());
        evidence.put("connection_fingerprint", connection.fingerprint());
        evidence.put("provider", connection.provider());
        evidence.put("endpoint", connection.endpoint());
        evidence.put("model_requested", profile.model());
        evidence.put("cost_status", "unknown");
        if (!connection.enabled()) return failure("role_api_connection_disabled", started, "", evidence);
        // Enforced again at the execution seam, not only when a YAML profile is parsed.
        if (!profile.readOnly() || profile.vision() != null) {
            return failure("role_api_capability_unsupported", started, "", evidence);
        }
        String token;
        try {
            token = new CredentialResolver(environment).resolve(connection);
        } catch (RuntimeException unavailable) {
            // Resolver exceptions must never become a second channel for credential values.
            return failure("role_api_key_missing", started, "", evidence);
        }
        String prompt = Files.readString(request.promptFile(), StandardCharsets.UTF_8);
        int outputLimit = profile.maxOutputTokens();
        if (outputLimit < 1) return failure("role_api_output_limit_missing", started, "", evidence);
        if ("gonkarouter".equals(connection.provider()) && outputLimit > 4096) {
            return failure("role_api_output_limit_unsupported", started, "", evidence);
        }
        evidence.put("max_output_tokens", (long) outputLimit);
        Map<String, Object> body = completionBody(connection, profile.model(), prompt, outputLimit);
        String before = profile.readOnly() && git != null && request.diffBaseCommit() != null
                ? git.fingerprint(request.diffBaseCommit()) : null;
        Duration limit = timeoutOverride != null ? timeoutOverride
                : Duration.ofMinutes(profile.wallClockMinutes());
        HttpReply reply;
        try {
            evidence.put("vendor_turn_started", true);
            reply = call(connection, "POST", URI.create(connection.endpoint()), Json.write(body), token, limit);
        } catch (CallFailure failed) {
            return failure(failed.code, started, "", evidence);
        }
        String safe = redact(reply.body(), token);
        Map<String, Object> envelope = object(safe);
        if (envelope == null && token != null) {
            // Invalid JSON cannot be decoded reliably to rule out encoded reflections.
            safe = "<response omitted: invalid JSON with credential-bearing request>";
        }
        if (envelope != null) {
            envelope = sanitizedObject(envelope, token);
            // Parsing can turn Unicode escapes into a credential that the raw byte-level
            // replacement could not see. Persist only the sanitized, re-encoded envelope.
            safe = Json.write(envelope);
        }
        recordReceipt(connection, envelope, reply.headers(), evidence, token);
        if (!dispatchedFingerprint.equals(profile.apiFingerprint())) {
            persist(request, safe, evidence);
            return failure("role_api_profile_changed", started, safe, evidence);
        }
        evidence.put("http_status", (long) reply.status());
        String httpFailure = errorCode(reply.status(), envelope);
        if (httpFailure != null) {
            evidence.put("body_head", head(safe, 1000));
            persist(request, safe, evidence);
            return failure(httpFailure, started, safe, evidence);
        }
        if (envelope == null) {
            persist(request, safe, evidence);
            return failure("role_api_response_unreadable", started, safe, evidence);
        }
        String served = text(envelope.get("model"));
        if (served == null || !served.equals(profile.model())) {
            persist(request, safe, evidence);
            return failure(served == null ? "role_api_model_identity_unknown" : "role_api_model_mismatch",
                    started, safe, evidence);
        }
        String answer = assistantText(envelope, evidence);
        if (Boolean.TRUE.equals(evidence.get("tool_calls_returned"))) {
            persist(request, safe, evidence);
            return failure("role_api_tools_unsupported", started, safe, evidence);
        }
        if (answer == null) {
            persist(request, safe, evidence);
            return failure("role_api_response_unreadable", started, safe, evidence);
        }
        if ("length".equals(evidence.get("finish_reason"))) {
            persist(request, safe, evidence);
            return failure("role_api_output_truncated", started, safe, evidence);
        }
        if ("tool_calls".equals(evidence.get("finish_reason"))) {
            persist(request, safe, evidence);
            return failure("role_api_tools_unsupported", started, safe, evidence);
        }
        // Local settlement owns schema, artifact defaults and read-only checks. It receives
        // already redacted data, including the artifact, before it writes any bytes.
        Result settled = new LocalHttpExecutor(environment, null, git).settle(request, profile,
                elapsed(started), safe, answer, evidence, token, before);
        if (settled.ok()) return settled;
        // Generic artifact failures enable CLI failover/repair. API transport has no such
        // authorization in this increment; retain the reason while stopping at its boundary.
        evidence.put("settlement_code", settled.code());
        evidence.put("failure", "role_api_artifact_failure");
        return new Result(false, "role_api_artifact_failure", settled.duration(),
                settled.rawOutput(), settled.artifact(), evidence);
    }

    static record HttpReply(int status, HttpHeaders headers, String body) { }

    static final class CallFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        CallFailure(String code) { super(code); this.code = code; }
    }

    static Map<String, Object> completionBody(Connection connection, String model, String prompt, int outputLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", outputLimit);
        body.put("stream", false);
        if ("openrouter".equals(connection.provider())) {
            body.put("provider", Map.of("require_parameters", true, "allow_fallbacks", false));
        }
        return body;
    }

    static Map<String, String> providerHeaders(Connection connection) {
        return "gonkarouter".equals(connection.provider()) ? Map.of("X-Gonka-No-Fallback", "true") : Map.of();
    }

    /** The completion future includes the entire bounded body, not just response headers. */
    static HttpReply call(Connection connection, String method, URI uri, String body,
                          String token, Duration limit) throws CallFailure {
        URI origin = URI.create(connection.baseUrl());
        if (uri.getHost() == null || !origin.getScheme().equalsIgnoreCase(uri.getScheme())
                || !origin.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(origin) != effectivePort(uri) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new CallFailure("role_api_origin_mismatch");
        }
        long started = System.nanoTime();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(limit).version(HttpClient.Version.HTTP_1_1).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(limit)
                    .header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            providerHeaders(connection).forEach(builder::header);
            if ("POST".equals(method)) builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            else builder.GET();
            pending = client.sendAsync(builder.build(), ignored -> new BoundedBody());
            long remaining = limit.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            return new HttpReply(response.statusCode(), response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CallFailure("role_api_interrupted");
        } catch (TimeoutException timeout) {
            throw new CallFailure("role_api_timeout");
        } catch (ExecutionException failed) {
            Throwable cause = failed;
            for (int i = 0; i < 12 && cause != null; i++, cause = cause.getCause()) {
                if (cause instanceof BodyLimit) throw new CallFailure("role_api_response_too_large");
                if (cause instanceof java.net.http.HttpTimeoutException) throw new CallFailure("role_api_timeout");
            }
            throw new CallFailure("role_api_network_error");
        } catch (IllegalArgumentException invalid) {
            throw new CallFailure("role_api_request_invalid");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            try { client.shutdownNow(); } catch (Exception ignored) { }
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static final class BodyLimit extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription next) {
            subscription = next;
            delegate.onSubscribe(next);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > MAX_RESPONSE_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new BodyLimit());
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String body) {
        try {
            Object value = Json.parse(body);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (RuntimeException invalid) { return null; }
    }

    static String errorCode(int status, Map<String, Object> envelope) {
        Object error = envelope == null ? null : envelope.get("error");
        if (status >= 200 && status < 300 && error == null) return null;
        Map<?, ?> detail = error instanceof Map<?, ?> map ? map : Map.of();
        String diagnostic = Json.write(detail).toLowerCase(java.util.Locale.ROOT);
        int effective = status;
        if (detail.get("code") instanceof Number code) effective = code.intValue();
        if (effective == 401) return "role_api_auth_invalid";
        if (diagnostic.contains("budget") || diagnostic.contains("spending limit")) {
            if (diagnostic.contains("in-flight") || diagnostic.contains("in_flight")) return "role_api_rate_limited";
            return "role_api_budget_exhausted";
        }
        if (effective == 402 || diagnostic.contains("insufficient credit")) return "role_api_credits_exhausted";
        if (effective == 429 || diagnostic.contains("rate_limit") || diagnostic.contains("rate limit")) {
            return "role_api_rate_limited";
        }
        if (effective == 403) return "role_api_access_denied";
        if (effective == 400 || effective == 404 || effective == 422) return "role_api_model_or_parameter_unsupported";
        if (effective >= 500) return "role_api_provider_error";
        return "role_api_protocol_error";
    }

    static void recordReceipt(Connection connection, Map<String, Object> envelope,
                              HttpHeaders headers, Map<String, Object> evidence, String token) {
        headers.firstValue("x-request-id").ifPresent(value -> evidence.put("request_id", sanitizeText(value, token, 0)));
        headers.firstValue("x-gonka-fallback").ifPresent(value -> evidence.put("provider_fallback", sanitizeText(value, token, 0)));
        headers.firstValue("retry-after").ifPresent(value -> evidence.put("retry_after", sanitizeText(value, token, 0)));
        if (envelope == null) return;
        putSafeText(evidence, "model_reported", envelope.get("model"), token);
        putSafeText(evidence, "generation_id", envelope.get("id"), token);
        putSafeText(evidence, "upstream_provider", envelope.get("provider"), token);
        if (!(envelope.get("usage") instanceof Map<?, ?> usage)) return;
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokenCount(tokens, "input", usage.get("prompt_tokens"));
        tokenCount(tokens, "output", usage.get("completion_tokens"));
        if (usage.get("prompt_tokens_details") instanceof Map<?, ?> detail) {
            tokenCount(tokens, "cache_read", detail.get("cached_tokens"));
        }
        if (usage.get("completion_tokens_details") instanceof Map<?, ?> detail) {
            tokenCount(tokens, "reasoning", detail.get("reasoning_tokens"));
        }
        if (!tokens.isEmpty()) evidence.put("tokens", tokens);
        if ("openrouter".equals(connection.provider()) && usage.get("cost") instanceof Number cost
                && Double.isFinite(cost.doubleValue()) && cost.doubleValue() >= 0) {
            evidence.put("cost_usd", cost.doubleValue());
            evidence.put("cost_source", "openrouter.usage.cost");
            evidence.put("cost_currency", "USD");
            evidence.put("cost_status", "reported");
        }
    }

    private static void tokenCount(Map<String, Object> target, String key, Object value) {
        if (value instanceof Number count && count.longValue() >= 0) target.put(key, count.longValue());
    }

    private static String assistantText(Map<String, Object> envelope, Map<String, Object> evidence) {
        if (!(envelope.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)) return null;
        putText(evidence, "finish_reason", choice.get("finish_reason"));
        if (!(choice.get("message") instanceof Map<?, ?> message)) return null;
        if (message.get("tool_calls") instanceof List<?> tools && !tools.isEmpty()) {
            evidence.put("tool_calls_returned", true);
        }
        return text(message.get("content"));
    }

    private static void putText(Map<String, Object> target, String key, Object value) {
        if (value instanceof String string) target.put(key, string);
    }

    private static void putSafeText(Map<String, Object> target, String key, Object value, String token) {
        if (value instanceof String string) target.put(key, sanitizeText(string, token, 0));
    }

    private static String text(Object value) { return value instanceof String string ? string : null; }

    /** Redact both literal and JSON-escaped reflections before parsing or persistence. */
    static String redact(String value, String token) {
        if (value == null) return "";
        if (token == null || token.isEmpty()) return value;
        String quoted = Json.write(token);
        String escaped = quoted.substring(1, quoted.length() - 1);
        return value.replace(escaped, "<redacted>").replace(token, "<redacted>");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizedObject(Map<String, Object> value, String token) {
        return (Map<String, Object>) sanitize(value, token, 0);
    }

    /** Also sanitize JSON inside string fields: content/result can be parsed again at settlement. */
    private static Object sanitize(Object value, String token, int depth) {
        if (token == null || token.isEmpty()) return value;
        // An adversarial response must not escape redaction by nesting another encoded JSON
        // string. Beyond the bound discard the branch instead of retaining uncertain bytes.
        if (depth >= 64) return "<redacted: nesting limit>";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                clean.put(sanitizeText(String.valueOf(entry.getKey()), token, depth + 1),
                        sanitize(entry.getValue(), token, depth + 1));
            }
            return clean;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitize(item, token, depth + 1)).toList();
        }
        return value instanceof String text ? sanitizeText(text, token, depth + 1) : value;
    }

    private static String sanitizeText(String value, String token, int depth) {
        if (depth >= 64) return "<redacted: nesting limit>";
        String clean = redact(value, token);
        String stripped = clean.strip();
        if (stripped.isEmpty()) return clean;
        char first = stripped.charAt(0);
        if (first == '{' || first == '[' || first == '"') {
            try {
                Object parsed = Json.parse(stripped);
                Object sanitized = sanitize(parsed, token, depth + 1);
                // Preserve the exact original content unless decoding exposed a secret.
                return Objects.equals(parsed, sanitized) ? clean : Json.write(sanitized);
            } catch (RuntimeException invalid) {
                // A role may surround its final artifact with prose or Markdown fences.
            }
        }
        if (clean.indexOf('{') >= 0) {
            Map<String, Object> artifact = Json.findLastObject(clean);
            if (artifact != null) {
                Object sanitized = sanitize(artifact, token, depth + 1);
                if (!Objects.equals(artifact, sanitized)) return Json.write(sanitized);
            }
        }
        if (token == null || token.isEmpty()) return clean;
        // Plain diagnostic strings can mix literal characters with Unicode escape spelling.
        StringBuilder pattern = new StringBuilder();
        for (char character : token.toCharArray()) {
            pattern.append("(?:").append(java.util.regex.Pattern.quote(String.valueOf(character)))
                    .append("|(?i:").append(java.util.regex.Pattern.quote("\\u" + String.format("%04x", (int) character)))
                    .append("))");
        }
        return java.util.regex.Pattern.compile(pattern.toString()).matcher(clean).replaceAll("<redacted>");
    }

    private static void persist(Request request, String safe, Map<String, Object> evidence) throws IOException {
        Path directory = request.runDirectory().resolve("raw");
        Files.createDirectories(directory);
        Path path = directory.resolve(request.evidenceName() + ".stdout.txt");
        Files.writeString(path, safe, StandardCharsets.UTF_8);
        evidence.put("raw_stdout", request.runDirectory().relativize(path).toString().replace('\\', '/'));
    }

    private static Result failure(String code, long started, String raw, Map<String, Object> evidence) {
        Duration duration = elapsed(started);
        evidence.put("duration_millis", duration.toMillis());
        evidence.put("failure", code);
        return new Result(false, code, duration, raw, null, evidence);
    }

    private static Duration elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started); }
    private static String head(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit); }
}
