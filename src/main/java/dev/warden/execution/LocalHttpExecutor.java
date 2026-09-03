package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs a role against a model already serving on this machine over HTTP.
 *
 * {@code direct} starts a vendor CLI; {@code orca} starts a supervised worker. {@code local}
 * talks to something that is already running — Ollama, LM Studio, llama.cpp — at the
 * OpenAI-compatible chat-completion shape. No process is spawned, so there is no argv for a
 * quote or a newline in the prompt to tear apart.
 */
public final class LocalHttpExecutor implements RoleExecutor {

    private static final String REDACTED = "<redacted>";

    private final Function<String, String> environment;
    private final Duration timeoutOverride;
    /** Null when no repository is in play, which is how every executor test constructs one. */
    private final dev.warden.git.GitRepository git;

    public LocalHttpExecutor() {
        this(System::getenv, null);
    }

    /**
     * {@code environment} is how {@code api_key_env} is read at dispatch. Production uses the
     * process environment; a supplied lookup lets a test send a bearer token without mutating it.
     */
    public LocalHttpExecutor(Function<String, String> environment) {
        this(environment, null);
    }

    /**
     * {@code timeout} replaces {@code limits.wall_clock_minutes} so a test can prove the bound
     * without waiting a full minute — the profile floor. Production passes null.
     */
    public LocalHttpExecutor(Function<String, String> environment, Duration timeout) {
        this(environment, timeout, null);
    }

    public LocalHttpExecutor(Function<String, String> environment, Duration timeout,
                             dev.warden.git.GitRepository git) {
        this.environment = Objects.requireNonNullElse(environment, System::getenv);
        this.timeoutOverride = timeout;
        this.git = git;
    }

    /**
     * Turn an answer into an artifact, or into the reason there is not one.
     *
     * Everything below this line is why a `local` role may be trusted at all. Without it the
     * adapter returned `ok` with a null artifact, no raw transcript, no schema check and no
     * read-only proof — and the loop counted that stage as passed. A live dispatch to a real
     * model exposed it: `role-reviewer.json` held twenty-six keys where a `direct` role holds
     * thirty-six, and every missing one was a check.
     *
     * The checks themselves are not reimplemented here. `unwrap`, `schemaErrors` and
     * `semanticFailure` live on {@link DirectCliExecutor} and are called, not copied: two
     * adapters that judge an artifact differently would be two definitions of a valid answer.
     */
    private Result settle(Request request, Profile profile, Duration duration, String rawResponse,
                          String answer, Map<String, Object> evidence, String token,
                          String fingerprintBefore) throws Exception {
        Path rawDirectory = request.runDirectory().resolve("raw");
        Files.createDirectories(rawDirectory);
        Path stdoutFile = rawDirectory.resolve(request.evidenceName() + ".stdout.txt");
        Files.writeString(stdoutFile, redact(rawResponse, token), StandardCharsets.UTF_8);
        evidence.put("raw_stdout",
                request.runDirectory().relativize(stdoutFile).toString().replace('\\', '/'));
        evidence.put("duration_millis", duration.toMillis());
        recordUsage(rawResponse, evidence);

        // A model reached over HTTP has no shell, but a tool-calling server on the other end
        // does. `read_only` is a claim about what the role may leave behind, and it is checked
        // the same way for every runner or it is not checked at all.
        if (fingerprintBefore != null) {
            String after = git.fingerprint(request.diffBaseCommit());
            boolean unchanged = fingerprintBefore.equals(after);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("method", "worktree_content_fingerprint");
            check.put("covers", "tracked edits, deletes, renames and untracked file contents");
            check.put("does_not_cover", "paths ignored by .gitignore");
            check.put("matched", unchanged);
            evidence.put("read_only_check", check);
            if (!unchanged) {
                evidence.put("failure", "role_violated_read_only");
                return new Result(false, "role_violated_read_only", duration, answer, null, evidence);
            }
        }

        Map<String, Object> parsed = Json.findLastObject(answer);
        Map<String, Object> artifact = parsed == null ? null : DirectCliExecutor.unwrap(parsed);
        if (artifact == null) {
            evidence.put("failure", "role_artifact_unparseable");
            evidence.put("answer_tail", tail(answer, 2000));
            return new Result(false, "role_artifact_unparseable", duration, answer, null, evidence);
        }

        // The same three defaults DirectCliExecutor fills, so a model that omits its own name
        // is not failed for it. Guarded on the task because a role can be dispatched without
        // one; the loop always supplies it, and an absent task must not become an NPE here.
        artifact.putIfAbsent("role", request.role());
        if (request.task() != null) artifact.putIfAbsent("task_id", request.task().id());
        artifact.putIfAbsent("run_id", request.runId());

        List<String> missing = new java.util.ArrayList<>();
        for (String field : profile.requiredArtifactFields()) {
            if (!artifact.containsKey(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            evidence.put("failure", "role_artifact_incomplete");
            evidence.put("missing_fields", missing);
            evidence.put("received_keys", new java.util.ArrayList<>(artifact.keySet()));
            return new Result(false, "role_artifact_incomplete", duration, answer, null, evidence);
        }

        List<String> schemaErrors = DirectCliExecutor.schemaErrors(profile, request, artifact);
        if (!schemaErrors.isEmpty()) {
            evidence.put("failure", "role_artifact_schema_violation");
            evidence.put("schema_errors", schemaErrors.stream().limit(40).toList());
            return new Result(false, "role_artifact_schema_violation", duration, answer, null, evidence);
        }

        String semanticFailure = DirectCliExecutor.semanticFailure(request.role(), artifact);
        if (semanticFailure != null) {
            evidence.put("failure", semanticFailure);
            evidence.put("artifact_status", artifact.get("status"));
            return new Result(false, semanticFailure, duration, answer, artifact, evidence);
        }

        evidence.put("verdict", artifact.get("verdict"));
        return new Result(true, "ok", duration, answer, artifact, evidence);
    }

    /**
     * Tokens, when the server reports them. Ollama does, in the OpenAI `usage` shape; cost it
     * does not, and inventing a zero there would say "this call was free" about a call nobody
     * priced. That distinction is the ledger's, and it already knows how to render it.
     */
    private static void recordUsage(String rawResponse, Map<String, Object> evidence) {
        Map<String, Object> body = Json.findLastObject(rawResponse);
        if (body == null || !(body.get("usage") instanceof Map<?, ?> usage)) return;
        Map<String, Object> tokens = new LinkedHashMap<>();
        if (usage.get("prompt_tokens") instanceof Number in) tokens.put("input", in.longValue());
        if (usage.get("completion_tokens") instanceof Number out) tokens.put("output", out.longValue());
        if (!tokens.isEmpty()) evidence.put("tokens", tokens);
    }

    @Override
    public Result execute(Request request) throws Exception {
        Profile profile = request.profile();
        String endpoint = profile.endpoint();
        Duration limit = requestLimit(profile);
        long started = System.nanoTime();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runner", "local");
        evidence.put("endpoint", endpoint);

        // Resolved before anything is sent. A profile that names `api_key_env` has declared a
        // secret channel; posting anyway with no Authorization header would answer a
        // configuration fault as an HTTP 401, and send the operator to read server logs
        // instead of exporting the variable. Same family as role_prompt_undeliverable.
        String token;
        try {
            token = bearerToken(profile);
        } catch (MissingApiKeyException missing) {
            evidence.put("api_key_env", missing.variable());
            return fail("role_local_api_key_missing", elapsed(started), "", evidence,
                    missing.getMessage(), null);
        }

        String prompt = Files.readString(request.promptFile(), StandardCharsets.UTF_8);
        String body = chatCompletionBody(profile.model(), prompt);

        // Taken before the call and carried on the stack rather than in a field: one executor
        // instance serves every role in a run, and a field would let one role's baseline decide
        // another role's verdict. Null means the check is not armed, and settle() says so by
        // omitting read_only_check rather than by recording a pass nobody performed.
        String fingerprintBefore = profile.readOnly() && git != null && request.diffBaseCommit() != null
                ? git.fingerprint(request.diffBaseCommit())
                : null;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(limit)
                .build();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(limit)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (token != null) builder.header("Authorization", "Bearer " + token);

            HttpResponse<String> response;
            // HttpRequest.timeout() is cancelled once headers arrive. A server that then
            // stalls the body would leave send() blocked past the wall-clock limit, so the
            // future is waited on for the remaining budget and cancelled if the body never
            // completes. The operator still sees role_local_endpoint_unreachable naming the
            // endpoint — same place they go when the port is closed.
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            try {
                response = completeWithin(pending, limit, started);
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                return fail("role_local_endpoint_unreachable", elapsed(started), "",
                        evidence, "interrupted while calling " + endpoint, token);
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                return fail("role_local_endpoint_unreachable", elapsed(started), "",
                        evidence, "timed out calling " + endpoint, token);
            } catch (ExecutionException failed) {
                pending.cancel(true);
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return fail("role_local_endpoint_unreachable", elapsed(started), "",
                            evidence, "interrupted while calling " + endpoint, token);
                }
                if (cause instanceof HttpTimeoutException) {
                    return fail("role_local_endpoint_unreachable", elapsed(started), "",
                            evidence, "timed out calling " + endpoint, token);
                }
                if (cause instanceof IOException || cause instanceof IllegalArgumentException) {
                    return fail("role_local_endpoint_unreachable", elapsed(started), "",
                            evidence, "failed to reach " + endpoint
                                    + (cause.getMessage() == null ? ""
                                    : ": " + cause.getMessage()), token);
                }
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                return fail("role_local_endpoint_unreachable", elapsed(started), "",
                        evidence, "failed to reach " + endpoint
                                + (cause.getMessage() == null ? ""
                                : ": " + cause.getMessage()), token);
            }

            Duration duration = elapsed(started);
            int status = response.statusCode();
            String responseBody = response.body() == null ? "" : response.body();
            // Redact before truncating: a token sitting across the 500-character cut would
            // otherwise leave a prefix in body_head, which RoleRunner copies onto disk.
            String safeBody = redact(responseBody, token);
            if (status < 200 || status >= 300) {
                evidence.put("http_status", (long) status);
                evidence.put("body_head", head(safeBody, 500));
                return fail("role_local_http_error", duration, safeBody, evidence,
                        "HTTP " + status + " from " + endpoint, token);
            }

            Extracted extracted = assistantText(responseBody);
            if (extracted.text() == null) {
                evidence.put("found", extracted.found());
                evidence.put("body_head", head(safeBody, 500));
                return fail("role_local_response_unreadable", duration, safeBody, evidence,
                        extracted.found(), token);
            }
            redactStrings(evidence, token);
            return settle(request, profile, duration, responseBody,
                    redact(extracted.text(), token), evidence, token, fingerprintBefore);
        } finally {
            // shutdownNow rather than close(): close() waits on a keep-alive connection the
            // other side may never drop, and a throw from shutdown must not rewrite the
            // outcome of a request that already finished. Same reason as VisualQaRunner.
            try {
                client.shutdownNow();
            } catch (Exception alreadyGone) {
                // The answer is already decided.
            }
        }
    }

    private Duration requestLimit(Profile profile) {
        return timeoutOverride != null ? timeoutOverride : Duration.ofMinutes(profile.wallClockMinutes());
    }

    /**
     * Waits until the body has been collected, not only until headers exist. Remaining
     * budget is measured from {@code started} so header time is not granted twice.
     */
    private static HttpResponse<String> completeWithin(
            CompletableFuture<HttpResponse<String>> pending, Duration limit, long started)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = limit.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) throw new TimeoutException("wall clock already elapsed");
        return pending.get(remaining, TimeUnit.NANOSECONDS);
    }

    /**
     * Reads {@code api_key_env} at dispatch. The value is used as a request header and then
     * dropped: it must not appear in evidence, the ledger, or the raw output we keep, even
     * when the endpoint reflects the Authorization header it received.
     *
     * There are two honest configurations, and this returns one or refuses: no
     * {@code api_key_env} means no auth on purpose; {@code api_key_env} naming a variable
     * that holds a value means a bearer header. A name with nothing behind it is neither,
     * and it fails closed rather than quietly downgrading to an unauthenticated POST.
     */
    private String bearerToken(Profile profile) throws MissingApiKeyException {
        if (profile.apiKeyEnv() == null) return null;
        String token = environment.apply(profile.apiKeyEnv());
        if (token == null || token.isBlank()) throw new MissingApiKeyException(profile);
        return token;
    }

    /** Names the variable, never a value — there is no value, and there never will be one here. */
    private static final class MissingApiKeyException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String variable;

        MissingApiKeyException(Profile profile) {
            super("profile '" + profile.name() + "' declares api_key_env " + profile.apiKeyEnv()
                    + ", and that environment variable is unset or blank; export it, or drop "
                    + "api_key_env if the endpoint needs no authorization");
            this.variable = profile.apiKeyEnv();
        }

        String variable() { return variable; }
    }

    private static String chatCompletionBody(String model, String prompt) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model == null ? "" : model);
        body.put("messages", List.of(message));
        body.put("stream", Boolean.FALSE);
        return Json.write(body);
    }

    /**
     * Walks {@code choices[0].message.content}. Anything else is named rather than guessed:
     * a 2xx envelope with no assistant text is a different operator action from a connection
     * failure or an HTTP error.
     */
    @SuppressWarnings("unchecked")
    private static Extracted assistantText(String body) {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (Json.JsonException bad) {
            return Extracted.missing("body is not JSON (" + bad.getMessage() + ")");
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return Extracted.missing("got " + Json.typeName(parsed) + " rather than an object");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object choices = root.get("choices");
        if (!(choices instanceof List<?> list)) {
            return Extracted.missing("choices is " + (choices == null ? "missing" : Json.typeName(choices)));
        }
        if (list.isEmpty()) {
            return Extracted.missing("choices is an empty array");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return Extracted.missing("choices[0] is " + Json.typeName(first));
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            return Extracted.missing("choices[0].message is "
                    + (message == null ? "missing" : Json.typeName(message)));
        }
        Object content = msg.get("content");
        if (!(content instanceof String text)) {
            return Extracted.missing("choices[0].message.content is "
                    + (content == null ? "missing" : Json.typeName(content)));
        }
        return new Extracted(text, null);
    }

    private static Result fail(String code, Duration duration, String raw,
                               Map<String, Object> evidence, String message, String token) {
        evidence.put("failure", code);
        evidence.put("message", message);
        redactStrings(evidence, token);
        return new Result(false, code, duration, redact(raw == null ? "" : raw, token), null, evidence);
    }

    /**
     * Walks every string already in evidence so a later field cannot become a second leak
     * path. Nested maps are not used by this adapter; longs (http_status) are left alone.
     */
    private static void redactStrings(Map<String, Object> evidence, String token) {
        if (token == null || token.isEmpty()) return;
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            if (entry.getValue() instanceof String value) {
                entry.setValue(redact(value, token));
            }
        }
    }

    /**
     * Replaces the bearer token wherever a response or diagnostic would otherwise keep it.
     * The JSON-escaped form is removed first so a quote or backslash in the secret cannot
     * survive as {@code \"} while the raw value is gone.
     */
    private static String redact(String text, String token) {
        if (text == null) return "";
        if (token == null || token.isEmpty()) return text;
        String result = text;
        String escaped = jsonStringContents(token);
        if (!escaped.equals(token) && !escaped.isEmpty()) {
            result = result.replace(escaped, REDACTED);
        }
        return result.replace(token, REDACTED);
    }

    private static String jsonStringContents(String token) {
        String written = Json.write(token);
        return written.length() >= 2 ? written.substring(1, written.length() - 1) : written;
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    private static String head(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** The end, not the start: an artifact that failed to parse failed at the end of an answer. */
    private static String tail(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }

    private record Extracted(String text, String found) {
        static Extracted missing(String found) { return new Extracted(null, found); }
    }
}
