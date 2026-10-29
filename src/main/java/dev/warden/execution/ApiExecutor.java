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
    private final ApiRetry.Policy retryPolicy;
    private final ApiRetry.Sleeper sleeper;

    public ApiExecutor() { this(System::getenv, null); }

    public ApiExecutor(Function<String, String> environment, GitRepository git) {
        this(environment, null, git);
    }

    /** A short timeout makes the body deadline testable without a minute-long test. */
    public ApiExecutor(Function<String, String> environment, Duration timeout, GitRepository git) {
        this.environment = Objects.requireNonNullElse(environment, System::getenv);
        this.git = git;
        this.timeoutOverride = timeout;
        this.retryPolicy = ApiRetry.Policy.NOT_SENT_ONLY;
        this.sleeper = ApiRetry.REAL_SLEEP;
    }

    /**
     * Role calls resend only a request that provably never reached the provider. A 429, a 5xx
     * and every unknown outcome stop with their reason: the role runner's own rate-limit policy
     * and the operator decide whether to pay again, not this transport.
     */
    ApiExecutor(Function<String, String> environment, Duration timeout, GitRepository git,
                ApiRetry.Policy policy, ApiRetry.Sleeper sleeper) {
        this.environment = Objects.requireNonNullElse(environment, System::getenv);
        this.git = git;
        this.timeoutOverride = timeout;
        this.retryPolicy = policy;
        this.sleeper = sleeper;
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
        evidence.put("vendor_turn_started", true);
        ApiRetry.Outcome sent = ApiRetry.send(connection, "POST", URI.create(connection.endpoint()),
                Json.write(body), token, limit, retryPolicy, sleeper,
                ApiRetry.correlation(request.runId(), profile.name(), request.role(),
                        request.evidenceName()));
        sent.record(evidence);
        if (sent.failure() != null) {
            // A call that provably never left this machine spent nothing; the budget gives it back.
            if (sent.nothingSent()) evidence.put("vendor_turn_started", false);
            return failure(sent.failure().code, started, "", evidence);
        }
        reply = sent.reply();
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
        sent.settleCost(evidence);
        if (!dispatchedFingerprint.equals(profile.apiFingerprint())) {
            persist(request, safe, evidence);
            return failure("role_api_profile_changed", started, safe, evidence);
        }
        evidence.put("http_status", (long) reply.status());
        String httpFailure = errorCode(reply.status(), envelope);
        if (httpFailure != null) {
            // Structured fields only. The redacted body stays in the raw transcript file;
            // a prefix of it is not copied into the evidence that reports and ledgers carry.
            evidence.put("failure_phase", "http_status");
            evidence.put("delivery", "answered");
            providerError(envelope, token, evidence);
            persist(request, safe, evidence);
            return failure(httpFailure, started, safe, evidence);
        }
        if (envelope == null) {
            persist(request, safe, evidence);
            evidence.put("failure_phase", "parse");
            return failure("role_api_response_unreadable", started, safe, evidence);
        }
        String served = text(envelope.get("model"));
        if (served == null || !served.equals(profile.model())) {
            persist(request, safe, evidence);
            evidence.put("failure_phase", "identity");
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
            evidence.put("failure_phase", "output_limit");
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
        // Generic artifact failures enable CLI failover/repair. Invalid API artifacts are
        // outside the transient fallback allowlist; retain the reason and stop here.
        evidence.put("settlement_code", settled.code());
        evidence.put("failure", "role_api_artifact_failure");
        return new Result(false, "role_api_artifact_failure", settled.duration(),
                settled.rawOutput(), settled.artifact(), evidence);
    }

    static record HttpReply(int status, HttpHeaders headers, String body) { }

    /**
     * A call that produced no complete reply, described only by a fixed vocabulary.
     *
     * {@code phase} says where the call stopped: {@code request} (refused before any socket),
     * {@code connect}, {@code headers}, {@code body}, {@code deadline} or {@code cancel}.
     * {@code delivery} says what the provider may have seen: {@code not_sent} is known not to
     * have reached it and is the only failure that may be sent again without risking a second
     * charge; {@code unknown} may have been received, processed and billed. {@code cause} is a
     * fixed word derived from the exception type. No exception message or response text is
     * ever kept: either may echo a credential.
     */
    static final class CallFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        final String phase;
        final String delivery;
        final String cause;
        CallFailure(String code) { this(code, "request", "not_sent", null); }
        CallFailure(String code, String phase, String delivery, String cause) {
            super(code, null, false, false);
            this.code = code;
            this.phase = phase;
            this.delivery = delivery;
            this.cause = cause;
        }
        boolean notSent() { return "not_sent".equals(delivery); }
        void describe(Map<String, Object> target) {
            target.put("failure_phase", phase);
            target.put("delivery", delivery);
            if (cause != null) target.put("transport_cause", cause);
        }
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
        // Which await was pending decides the phase. The request body is subscribed only after
        // the connection is up and the request line and headers are on their way; a POST whose
        // body was never pulled cannot have been acted on by the provider.
        java.util.concurrent.atomic.AtomicBoolean bodyPulled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean headersSeen = new java.util.concurrent.atomic.AtomicBoolean();
        boolean post = "POST".equals(method);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(limit)
                    .header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            providerHeaders(connection).forEach(builder::header);
            if (post) {
                HttpRequest.BodyPublisher inner = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
                builder.header("Content-Type", "application/json").POST(new HttpRequest.BodyPublisher() {
                    @Override public long contentLength() { return inner.contentLength(); }
                    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                        bodyPulled.set(true);
                        inner.subscribe(subscriber);
                    }
                });
            } else builder.GET();
            pending = client.sendAsync(builder.build(), ignored -> {
                headersSeen.set(true);
                return new BoundedBody();
            });
            long remaining = limit.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            return new HttpReply(response.statusCode(), response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CallFailure("role_api_interrupted", "cancel",
                    delivery(post, bodyPulled.get(), headersSeen.get()), "cancelled");
        } catch (TimeoutException timeout) {
            throw deadline(post, bodyPulled.get(), headersSeen.get());
        } catch (ExecutionException failed) {
            throw classify(failed, post, bodyPulled.get(), headersSeen.get());
        } catch (IllegalArgumentException invalid) {
            throw new CallFailure("role_api_request_invalid");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            try { client.shutdownNow(); } catch (Exception ignored) { }
        }
    }

    /**
     * A GET carries no body to watch, so only a failed connect proves it was not sent. Model
     * listings and key checks are free either way; only POST completions are retried.
     */
    private static String delivery(boolean post, boolean bodyPulled, boolean headersSeen) {
        if (headersSeen) return "unknown";
        return post && !bodyPulled ? "not_sent" : "unknown";
    }

    private static CallFailure deadline(boolean post, boolean bodyPulled, boolean headersSeen) {
        String during = headersSeen ? "body" : post && !bodyPulled ? "connect" : "headers";
        return new CallFailure("role_api_timeout", "deadline", delivery(post, bodyPulled, headersSeen),
                during + "_deadline");
    }

    /** Exception types only. Their messages can carry hosts, headers or reflected input. */
    private static CallFailure classify(ExecutionException failed, boolean post, boolean bodyPulled,
                                        boolean headersSeen) {
        Throwable cause = failed;
        for (int i = 0; i < 12 && cause != null; i++, cause = cause.getCause()) {
            if (cause instanceof BodyLimit) {
                return new CallFailure("role_api_response_too_large", "body", "unknown", "body_limit");
            }
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                return new CallFailure("role_api_timeout", "connect", "not_sent", "connect_timeout");
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return deadline(post, bodyPulled, headersSeen);
            }
            if (cause instanceof java.nio.channels.UnresolvedAddressException
                    || cause instanceof java.net.UnknownHostException) {
                return new CallFailure("role_api_network_error", "connect", "not_sent", "dns");
            }
            if (cause instanceof java.net.ConnectException) {
                return new CallFailure("role_api_network_error", "connect", "not_sent", "connect_refused");
            }
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                return new CallFailure("role_api_network_error", "connect", "not_sent", "tls_handshake");
            }
        }
        String phase = headersSeen ? "body" : post && !bodyPulled ? "connect" : "headers";
        return new CallFailure("role_api_network_error", phase, delivery(post, bodyPulled, headersSeen),
                "connection_closed");
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

    /**
     * The provider's own error code and type, when they are short identifiers. Messages are
     * prose that may quote the request; they stay in the redacted transcript only.
     */
    static void providerError(Map<String, Object> envelope, String token, Map<String, Object> evidence) {
        if (envelope == null || !(envelope.get("error") instanceof Map<?, ?> error)) return;
        Object code = error.get("code");
        if (code instanceof Number number) evidence.put("provider_error_code", String.valueOf(number.longValue()));
        else if (identifier(code, token) != null) evidence.put("provider_error_code", identifier(code, token));
        String type = identifier(error.get("type"), token);
        if (type != null) evidence.put("provider_error_type", type);
    }

    private static String identifier(Object value, String token) {
        if (!(value instanceof String text) || text.isEmpty() || text.length() > 64) return null;
        if (!text.matches("[A-Za-z0-9_.:-]+")) return null;
        if (token != null && !token.isEmpty() && (text.contains(token) || token.contains(text) && text.length() >= 8)) {
            return null;
        }
        return text;
    }
}
