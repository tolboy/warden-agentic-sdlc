package dev.warden.execution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.warden.json.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * OpenRouter PKCE S256. The authorization code and the issued key stay out of exceptions,
 * files, and the command report. The token endpoint is the documented OpenRouter URL, or
 * an explicit loopback stub used by tests. Redirects are not followed.
 */
public final class OpenRouterLogin {
    public static final URI AUTHORIZE = URI.create("https://openrouter.ai/auth");
    public static final URI EXCHANGE = URI.create("https://openrouter.ai/api/v1/auth/keys");
    private static final Pattern MATERIAL = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._~-]{1,256}");
    private static final int MAX_EXCHANGE_BYTES = 8192;
    private static final String SUCCESS = "Warden received the OpenRouter authorization. You can close this tab.";
    private static final String FAILURE = "Warden did not accept this authorization callback.";

    /** Exchanges one authorization code. Production calls {@link #exchangeLive}. */
    @FunctionalInterface
    public interface Exchanger {
        String exchange(String code, String verifier);
    }

    private enum Failure { STATE, DECLINED, MALFORMED }

    private OpenRouterLogin() {}

    public static String verifier() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } finally {
            java.util.Arrays.fill(raw, (byte) 0);
        }
    }

    /** S256 challenge: base64url(SHA-256(verifier)) without padding. */
    public static String challenge(String verifier) {
        if (!authorizationVerifier(verifier)) throw closed("OpenRouter authorization could not be started");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException missing) {
            throw closed("OpenRouter authorization could not be started");
        }
    }

    public static boolean authorizationVerifier(String value) {
        return value != null && MATERIAL.matcher(value).matches();
    }

    public static boolean authorizationCode(String value) {
        return value != null && CODE.matcher(value).matches();
    }

    /** {@code port} null builds the headless URL, which has no callback and no state. */
    public static String authorizeUrl(Integer port, String challenge, String state) {
        if (!authorizationVerifier(challenge)) throw closed("OpenRouter authorization could not be started");
        if (state != null && !authorizationVerifier(state)) throw closed("OpenRouter authorization could not be started");
        StringBuilder query = new StringBuilder();
        if (port != null) {
            if (port < 1 || port > 65535) throw closed("loopback callback could not be opened");
            query.append("callback_url=")
                    .append(encode("http://127.0.0.1:" + port + "/callback"))
                    .append('&');
        }
        query.append("code_challenge=").append(encode(challenge));
        query.append("&code_challenge_method=S256&key_label=warden");
        if (state != null) query.append("&state=").append(encode(state));
        return AUTHORIZE + "?" + query;
    }

    public static String exchangeLive(String code, String verifier) {
        return exchange(EXCHANGE, code, verifier);
    }

    public static String exchange(URI endpoint, String code, String verifier) {
        return exchange(endpoint, code, verifier, Duration.ofSeconds(30));
    }

    /** Tests can bound a stalled body without waiting for the production deadline. */
    static String exchange(URI endpoint, String code, String verifier, Duration timeout) {
        if (!allowedEndpoint(endpoint)) throw closed("OpenRouter login refused the token endpoint. Nothing was stored.");
        if (!authorizationCode(code) || !authorizationVerifier(verifier)) {
            throw closed("OpenRouter authorization code was rejected. Nothing was stored.");
        }
        String payload = Json.write(Map.of(
                "code", code, "code_verifier", verifier, "code_challenge_method", "S256"));
        long started = System.nanoTime();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout.compareTo(Duration.ofSeconds(20)) < 0 ? timeout : Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            pending = client.sendAsync(request, ignored -> new ExchangeBody());
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            // The future completes only after the entire bounded body, not just headers.
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            return readKey(response);
        } catch (IllegalArgumentException rejected) {
            throw rejected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw closed("OpenRouter login was interrupted. No key was stored.");
        } catch (Exception failure) {
            throw closed("OpenRouter did not issue a key. Nothing was stored.");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            try { client.shutdownNow(); } catch (Exception ignored) { }
        }
    }

    static boolean allowedEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getHost() == null) return false;
        if (!"/api/v1/auth/keys".equals(endpoint.getRawPath())) return false;
        int port = endpoint.getPort();
        if ("https".equalsIgnoreCase(endpoint.getScheme()) && "openrouter.ai".equalsIgnoreCase(endpoint.getHost())) {
            return port == -1 || port == 443;
        }
        return "http".equalsIgnoreCase(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
                && port > 0 && port <= 65535;
    }

    /** Best-effort browser open. The URL is already printed; failure here stores nothing. */
    public static void open(String url) {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) return;
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) desktop.browse(URI.create(url));
        } catch (Exception ignored) { }
    }

    private static String readKey(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        if (response.statusCode() != 200 || bytes.length > MAX_EXCHANGE_BYTES) {
            throw closed("OpenRouter did not issue a key. Nothing was stored.");
        }
        Map<String, Object> parsed;
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            parsed = Json.parseObject(json);
        } catch (RuntimeException | CharacterCodingException invalid) {
            throw closed("OpenRouter did not issue a key. Nothing was stored.");
        }
        if (!(parsed.get("key") instanceof String key) || !CredentialResolver.acceptable(key)) {
            throw closed("OpenRouter returned a key Warden will not store. Revoke it in the OpenRouter account.");
        }
        return key;
    }

    /** Limit response allocation while allowing cancellation to terminate a stalled body. */
    private static final class ExchangeBody implements HttpResponse.BodySubscriber<byte[]> {
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
            if (size > MAX_EXCHANGE_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("OpenRouter response exceeded the allowed size"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static IllegalArgumentException closed(String message) {
        return new IllegalArgumentException(message);
    }

    /** One loopback callback on 127.0.0.1. The response body does not repeat the query. */
    public static final class Callback implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers;
        private final String state;
        private final java.util.concurrent.CompletableFuture<String> pending =
                new java.util.concurrent.CompletableFuture<>();
        private final AtomicBoolean once = new AtomicBoolean();

        private Callback(HttpServer server, ExecutorService workers, String state) {
            this.server = server;
            this.workers = workers;
            this.state = state;
        }

        public static Callback bind(String state) throws IOException {
            if (!authorizationVerifier(state)) throw closed("OpenRouter authorization could not be started");
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService workers = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "warden-openrouter-login");
                thread.setDaemon(true);
                return thread;
            });
            Callback callback = new Callback(server, workers, state);
            server.createContext("/callback", callback::handle);
            server.setExecutor(workers);
            server.start();
            return callback;
        }

        public int port() { return server.getAddress().getPort(); }

        public String await(Duration timeout) {
            try {
                return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException expired) {
                throw closed("OpenRouter authorization timed out. No key was stored.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw closed("OpenRouter login was interrupted. No key was stored.");
            } catch (ExecutionException failed) {
                Failure reason = failed.getCause() instanceof Denied denied ? denied.reason : Failure.MALFORMED;
                String message = switch (reason) {
                    case STATE -> "OpenRouter authorization state did not match. No key was stored.";
                    case DECLINED -> "OpenRouter authorization was declined. No key was stored.";
                    case MALFORMED -> "OpenRouter authorization code was rejected. No key was stored.";
                };
                throw closed(message);
            }
        }

        @Override public void close() {
            server.stop(0);
            workers.shutdownNow();
        }

        private void handle(HttpExchange exchange) {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 405);
                    return;
                }
                if (!once.compareAndSet(false, true)) {
                    send(exchange, 404);
                    return;
                }
                String raw = exchange.getRequestURI().getRawQuery();
                Map<String, String> query = raw == null || raw.length() > 2048 ? null : query(raw);
                if (query == null) {
                    fail(exchange, Failure.MALFORMED);
                    return;
                }
                if (query.containsKey("error")) {
                    fail(exchange, Failure.DECLINED);
                    return;
                }
                String code = query.get("code");
                if (!same(state, query.get("state"))) fail(exchange, Failure.STATE);
                else if (!authorizationCode(code)) fail(exchange, Failure.MALFORMED);
                else {
                    pending.complete(code);
                    send(exchange, 200);
                }
            } catch (Exception failure) {
                pending.completeExceptionally(new Denied(Failure.MALFORMED));
                try { send(exchange, 400); } catch (IOException ignored) { }
            } finally {
                exchange.close();
            }
        }

        private void fail(HttpExchange exchange, Failure reason) throws IOException {
            pending.completeExceptionally(new Denied(reason));
            send(exchange, 400);
        }

        private static void send(HttpExchange exchange, int status) throws IOException {
            byte[] body = (status == 200 ? SUCCESS : FAILURE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split("&", -1)) {
            int separator = part.indexOf('=');
            String name = decode(separator < 0 ? part : part.substring(0, separator));
            String value = decode(separator < 0 ? "" : part.substring(separator + 1));
            if (name == null || value == null) return null;
            values.putIfAbsent(name, value);
        }
        return values;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Denied extends Exception {
        private final Failure reason;
        private Denied(Failure reason) { this.reason = reason; }
    }
}
