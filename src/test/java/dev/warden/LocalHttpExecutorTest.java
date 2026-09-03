package dev.warden;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.warden.config.Profile;
import dev.warden.execution.Executors;
import dev.warden.execution.LocalHttpExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The local runner talks HTTP, not a vendor CLI. The cases here are the ones that send an
 * operator to different places: a working completion, a server error, a 2xx that is not a
 * completion, a dead port, and a prompt that would have been torn apart by argv quoting.
 */
public final class LocalHttpExecutorTest implements Suite {

    @Override public String name() { return "local-http"; }

    @Override public void run(Check check) throws Exception {
        parseChecks(check);
        Path scratch = Files.createTempDirectory("warden-local-http-");
        Path promptFile = scratch.resolve("prompt.md");
        Files.writeString(promptFile, "review this change", StandardCharsets.UTF_8);

        wellFormedAnswer(check, promptFile);
        httpErrorNamesStatus(check, promptFile);
        bodyWithoutChoices(check, promptFile);
        refusedConnection(check, promptFile);
        stalledBodyTimesOut(check, promptFile);
        quotedPromptSurvives(check, scratch);
        reflectedBearerIsRedactedFromHttpError(check, promptFile);
        reflectedBearerIsRedactedFromUnreadableBody(check, promptFile);
        answerWithoutArtifactIsUnparseable(check, promptFile);
        missingRequiredFieldsAreNamed(check, promptFile);
        blockedStatusIsNotAPass(check, promptFile);
        rawTranscriptIsRedactedOnDisk(check, scratch);
        readOnlyIsCheckedForLocalRolesToo(check, scratch);
        schemaIsEnforcedOnLocalAnswersToo(check, scratch);
        reportedUsageBecomesTokensAndNothingBecomesCost(check, promptFile);
        declaredApiKeyWithNoValueFailsClosed(check, promptFile);
        check.that("runner local is the HTTP adapter, not a CLI",
                Executors.forProfile(localProfile("http://127.0.0.1:9/v1/chat/completions"),
                        null, null) instanceof LocalHttpExecutor);
    }

    private static void parseChecks(Check check) {
        check.rejects("local runner without endpoint is refused by name", "endpoint",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        command: ollama
                        runner: local
                        """, "p.yaml"));
        check.rejects("local endpoint must be an absolute http URL", "endpoint",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: /v1/chat/completions
                        """, "p.yaml"));
        check.rejects("ftp is not an http endpoint", "endpoint",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: ftp://127.0.0.1/v1/chat/completions
                        """, "p.yaml"));
        check.rejects("local runner cannot claim workspace_file vision",
                "does not support capabilities.vision",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: visual_qa
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: http://127.0.0.1:11434/v1/chat/completions
                        capabilities:
                          vision: { delivery: workspace_file, verification: required }
                        verification:
                          verified_on: 2026-08-27
                        """, "p.yaml"));
        check.rejects("local reviewer cannot claim vision either",
                "does not support capabilities.vision",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: http://127.0.0.1:11434/v1/chat/completions
                        capabilities:
                          vision: { delivery: workspace_file, verification: required }
                        """, "p.yaml"));

        Profile parsed = Profile.parse("""
                version: 1
                profile: local-gemma
                role: reviewer
                vendor: ollama
                command: ollama
                model: gemma4:e4b
                runner: local
                endpoint: http://127.0.0.1:11434/v1/chat/completions
                api_key_env: LOCAL_WARDEN_KEY
                """, "local-gemma.yaml");
        check.eq("endpoint is kept", "http://127.0.0.1:11434/v1/chat/completions", parsed.endpoint());
        check.eq("api_key_env is the variable name, not a token", "LOCAL_WARDEN_KEY", parsed.apiKeyEnv());
        check.eq("https endpoints parse", "https://127.0.0.1:11434/v1/chat/completions",
                Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: https://127.0.0.1:11434/v1/chat/completions
                        """, "p.yaml").endpoint());

        // A local profile that had to invent a command would become a Direct CLI dispatch of
        // that invention the moment the `runner` line is deleted or misspelt.
        Profile commandless = Profile.parse("""
                version: 1
                profile: local-gemma
                role: reviewer
                vendor: ollama
                model: gemma4:e4b
                runner: local
                endpoint: http://127.0.0.1:11434/v1/chat/completions
                """, "local-gemma.yaml");
        check.that("a local profile needs no command", commandless.command() == null);
        check.rejects("and every other runner still does", "command",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: grok
                        """, "p.yaml"));
        check.rejects("including one that only looks local", "command",
                () -> Profile.parse("""
                        version: 1
                        profile: p
                        role: reviewer
                        vendor: ollama
                        runner: direct
                        endpoint: http://127.0.0.1:11434/v1/chat/completions
                        """, "p.yaml"));
    }

    private static void wellFormedAnswer(Check check, Path promptFile) throws Exception {
        try (Stub stub = new Stub(200, completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a well-formed completion is ok", result.ok());
            check.eq("well-formed code", "ok", result.code());
            check.that("an answer carrying an artifact produces one", result.artifact() != null);
            check.eq("the verdict is the artifact's", "pass", result.artifact().get("verdict"));
            check.eq("and it reaches the evidence the ledger copies",
                    "pass", result.evidence().get("verdict"));
            check.eq("the role is defaulted in when the model omits it",
                    "reviewer", result.artifact().get("role"));
            check.that("the call is timed", result.evidence().get("duration_millis") instanceof Long);

            // Without this the run showed `ok` with nothing on disk to argue with.
            Object rawStdout = result.evidence().get("raw_stdout");
            check.that("the transcript is named in evidence", rawStdout instanceof String);
            Path transcript = promptFile.getParent().resolve(String.valueOf(rawStdout));
            check.that("and the named file exists", Files.isRegularFile(transcript));
            check.contains("and holds what the server actually answered",
                    Files.readString(transcript, StandardCharsets.UTF_8), "verdict");
        }
    }

    /**
     * The defect this slice closes. A local role used to answer prose and be counted as a
     * passed stage, because the adapter returned ok with a null artifact. Prose is now a
     * failure with a name, and the tail of it is kept so the failure can be read.
     */
    private static void answerWithoutArtifactIsUnparseable(Check check, Path promptFile)
            throws Exception {
        try (Stub stub = new Stub(200, completion("hello from local, no JSON here"))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("prose is not a completed role", !result.ok());
            check.eq("prose is role_artifact_unparseable",
                    "role_artifact_unparseable", result.code());
            check.that("and no artifact is invented", result.artifact() == null);
            check.contains("the answer is kept so it can be read",
                    String.valueOf(result.evidence().get("answer_tail")), "no JSON here");
        }
    }

    private static void missingRequiredFieldsAreNamed(Check check, Path promptFile) throws Exception {
        String withoutFindings = Json.write(Map.of(
                "status", "completed", "verdict", "pass", "summary", "looks fine"));
        try (Stub stub = new Stub(200, completion(withoutFindings))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    demandingProfile(stub.url), promptFile));
            check.that("an artifact missing a required field is not ok", !result.ok());
            check.eq("and says which kind of failure it is",
                    "role_artifact_incomplete", result.code());
            check.contains("the missing field is named, not counted",
                    String.valueOf(result.evidence().get("missing_fields")), "findings");
            check.contains("and what did arrive is listed",
                    String.valueOf(result.evidence().get("received_keys")), "verdict");
        }
    }

    /**
     * A local model is as able to report that it could not do the work as a vendor CLI is, and
     * that is not a pass. Same code as `direct` produces, because it is the same function.
     */
    private static void blockedStatusIsNotAPass(Check check, Path promptFile) throws Exception {
        try (Stub stub = new Stub(200, completion(artifact("pass", "blocked")))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a blocked role is not ok even with a pass verdict", !result.ok());
            check.eq("blocked is role_reported_blocked", "role_reported_blocked", result.code());
            check.eq("the claimed status is recorded", "blocked",
                    result.evidence().get("artifact_status"));
        }
    }

    /**
     * The transcript is new ground for the token: redaction that only covered evidence would
     * now leave the secret in a file RoleRunner keeps beside the run.
     */
    private static void rawTranscriptIsRedactedOnDisk(Check check, Path scratch) throws Exception {
        Path promptFile = scratch.resolve("keyed-prompt.md");
        Files.writeString(promptFile, "review this change", StandardCharsets.UTF_8);
        Path runDirectory = Files.createDirectories(scratch.resolve("keyed-run"));
        try (Stub stub = Stub.echoingAuthorizationInsideACompletion()) {
            RoleExecutor.Result result = keyedExecutor().execute(
                    request(keyedProfile(stub.url), promptFile, runDirectory, null));
            check.eq("the Authorization header was actually sent",
                    "Bearer s3cr3t", stub.lastAuthorization);
            Path transcript = runDirectory.resolve(String.valueOf(result.evidence().get("raw_stdout")));
            String onDisk = Files.readString(transcript, StandardCharsets.UTF_8);
            check.that("the token is not written to the transcript", !onDisk.contains("s3cr3t"));
            check.contains("but the reflection is still diagnosable", onDisk, "<redacted>");
        }
    }

    /**
     * read_only is a claim about what a role may leave behind, and a local model reached over
     * HTTP can be backed by a tool-calling server that writes files. The stub here does exactly
     * that before answering, and the fingerprint must catch it whatever the answer claimed.
     */
    private static void readOnlyIsCheckedForLocalRolesToo(Check check, Path scratch) throws Exception {
        Path repository = Files.createDirectories(scratch.resolve("repo"));
        ProcessRunner processes = new ProcessRunner();
        Files.writeString(repository.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        // As production has it: the run directory lives under an ignored `.warden`, so the
        // transcript the adapter writes is not itself mistaken for the role editing the tree.
        // The disclosure in read_only_check says gitignored paths are uncovered; this is the
        // case that depends on it, and putting the run anywhere else would test a fiction.
        Files.writeString(repository.resolve(".gitignore"), ".warden/\n", StandardCharsets.UTF_8);
        Path runDirectory = Files.createDirectories(repository.resolve(".warden/runs/live"));
        for (List<String> command : List.of(
                List.of("git", "init", "-q", "-b", "main", "."),
                List.of("git", "config", "user.email", "test@example.invalid"),
                List.of("git", "config", "user.name", "test"),
                List.of("git", "add", "-A"),
                List.of("git", "commit", "-qm", "base"))) {
            processes.run(command, repository, Duration.ofSeconds(30));
        }
        String head = processes.run(List.of("git", "rev-parse", "HEAD"), repository,
                Duration.ofSeconds(30)).stdout().trim();
        GitRepository git = new GitRepository(repository, processes);
        Path promptFile = runDirectory.resolve("prompt.md");
        Files.writeString(promptFile, "review this change", StandardCharsets.UTF_8);

        try (Stub honest = new Stub(200, completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor(null, null, git).execute(
                    request(localProfile(honest.url), promptFile, runDirectory, head));
            check.that("a local role that touched nothing passes", result.ok());
            check.contains("and the check is disclosed, uncovered cases included",
                    String.valueOf(result.evidence().get("read_only_check")), "gitignore");
        }

        Path evidenceOfEditing = repository.resolve("touched-by-the-server.txt");
        try (Stub meddling = Stub.writingBeforeAnswering(evidenceOfEditing,
                completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor(null, null, git).execute(
                    request(localProfile(meddling.url), promptFile, runDirectory, head));
            check.that("a local role that edited the tree fails", !result.ok());
            check.eq("and says exactly why", "role_violated_read_only", result.code());
            check.that("its artifact is discarded whatever it claimed", result.artifact() == null);
        }
    }

    /**
     * The parity claim that had no test on this adapter: every earlier case passes
     * {@code schemaFile: null}, so {@code schemaErrors} returned empty before it read
     * anything. It matters most here — the first live run was answered by a 4B model that
     * copied `type`, `required` and `properties` out of the schema it had been handed, and
     * the only thing standing between that habit and a passed stage is this check.
     */
    private static void schemaIsEnforcedOnLocalAnswersToo(Check check, Path scratch) throws Exception {
        Path schemaFile = scratch.resolve("reviewer.schema.json");
        Files.writeString(schemaFile, Json.write(Map.of(
                "type", "object",
                "required", List.of("verdict"),
                "properties", Map.of("verdict", Map.of("enum", List.of("pass", "fail"))))),
                StandardCharsets.UTF_8);
        Path promptFile = scratch.resolve("schema-prompt.md");
        Files.writeString(promptFile, "review this change", StandardCharsets.UTF_8);

        // Well-formed JSON, every required field present: nothing before the schema check
        // has any reason to stop it.
        String offSchema = Json.write(Map.of(
                "status", "completed", "verdict", "probably-fine",
                "summary", "read the diff", "findings", List.of()));
        try (Stub stub = new Stub(200, completion(offSchema))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile, scratch, null, schemaFile));
            check.that("an artifact the schema rejects is not a pass", !result.ok());
            check.eq("and it is named as a schema violation",
                    "role_artifact_schema_violation", result.code());
            check.contains("the failing field is named rather than counted",
                    String.valueOf(result.evidence().get("schema_errors")), "verdict");
            check.that("no artifact is written from an answer that failed the schema",
                    result.artifact() == null);
        }

        try (Stub stub = new Stub(200, completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile, scratch, null, schemaFile));
            check.that("and the same schema still admits a valid answer", result.ok());
        }
    }

    /**
     * Ollama reports tokens in the OpenAI `usage` shape and reports no price. Both halves are
     * the claim: the tokens reach evidence, and no cost is invented, so the ledger counts the
     * call as unpriced instead of as one that was free.
     */
    private static void reportedUsageBecomesTokensAndNothingBecomesCost(Check check, Path promptFile)
            throws Exception {
        String withUsage = Json.write(Map.of(
                "choices", List.of(Map.of("message", Map.of(
                        "content", artifact("pass", "completed")))),
                "usage", Map.of("prompt_tokens", 2418, "completion_tokens", 1276,
                        "total_tokens", 3694)));
        try (Stub stub = new Stub(200, withUsage)) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a completion carrying usage still passes", result.ok());
            check.eq("input tokens are the server's prompt_tokens",
                    Map.of("input", 2418L, "output", 1276L), result.evidence().get("tokens"));
            check.that("and no cost is invented for a call nobody priced",
                    !result.evidence().containsKey("cost_usd"));
        }
        // A server that reports nothing must not grow a zero-token reading either.
        try (Stub stub = new Stub(200, completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a server that reports no usage records no tokens",
                    !result.evidence().containsKey("tokens"));
        }
    }

    /**
     * The operator configured a secret channel. Sending the request anyway with the header
     * dropped turns a variable nobody exported into a 401 from the server, and sends them to
     * read server logs instead of to their own shell.
     */
    private static void declaredApiKeyWithNoValueFailsClosed(Check check, Path promptFile)
            throws Exception {
        try (Stub stub = new Stub(200, completion(artifact("pass", "completed")))) {
            LocalHttpExecutor unset = new LocalHttpExecutor(name -> null);
            RoleExecutor.Result result = unset.execute(request(keyedProfile(stub.url), promptFile));
            check.that("a declared api_key_env with nothing behind it is not ok", !result.ok());
            check.eq("and it is a configuration fault with its own name",
                    "role_local_api_key_missing", result.code());
            check.eq("the variable is named so the operator can export it",
                    "LOCAL_WARDEN_KEY", result.evidence().get("api_key_env"));
            check.that("and nothing was sent to the endpoint", stub.lastBody.isEmpty());

            LocalHttpExecutor blank = new LocalHttpExecutor(name -> "   ");
            check.eq("a blank variable is the same fault, not a token", "role_local_api_key_missing",
                    blank.execute(request(keyedProfile(stub.url), promptFile)).code());

            // The other honest configuration: no api_key_env at all is no auth, on purpose.
            RoleExecutor.Result unauthenticated = new LocalHttpExecutor(name -> null)
                    .execute(request(localProfile(stub.url), promptFile));
            check.that("a profile that declares no key still dispatches", unauthenticated.ok());
            check.eq("with no Authorization header", "", stub.lastAuthorization);
        }
    }

    private static void httpErrorNamesStatus(Check check, Path promptFile) throws Exception {
        try (Stub stub = new Stub(500, "internal boom from the local server")) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a 500 is not ok", !result.ok());
            check.eq("a 500 is role_local_http_error", "role_local_http_error", result.code());
            check.eq("the status is named", 500L, result.evidence().get("http_status"));
            check.contains("the status appears in the message",
                    String.valueOf(result.evidence().get("message")), "500");
            check.contains("the body head is kept",
                    String.valueOf(result.evidence().get("body_head")), "internal boom");
        }
    }

    private static void bodyWithoutChoices(Check check, Path promptFile) throws Exception {
        try (Stub stub = new Stub(200, "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\"}")) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("a 2xx without choices is not ok", !result.ok());
            check.eq("missing choices is role_local_response_unreadable",
                    "role_local_response_unreadable", result.code());
            check.contains("what was found is named",
                    String.valueOf(result.evidence().get("found")), "choices");
        }
    }

    private static void refusedConnection(Check check, Path promptFile) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = socket.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + port + "/v1/chat/completions";
        RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                localProfile(endpoint), promptFile));
        check.that("a dead port is not ok", !result.ok());
        check.eq("refused connection is role_local_endpoint_unreachable",
                "role_local_endpoint_unreachable", result.code());
        check.contains("the unreachable endpoint is named",
                String.valueOf(result.evidence()), endpoint);
    }

    /**
     * HttpRequest.timeout() is satisfied by headers. A 200 that never finishes its body must
     * still become role_local_endpoint_unreachable, or the wall-clock limit is a lie. The
     * injected duration is sub-minute because the profile floor is one minute.
     */
    private static void stalledBodyTimesOut(Check check, Path promptFile) throws Exception {
        try (Stub stub = Stub.stallingAfterHeaders()) {
            LocalHttpExecutor executor = new LocalHttpExecutor(null, Duration.ofSeconds(1));
            long started = System.nanoTime();
            RoleExecutor.Result result;
            CompletableFuture<RoleExecutor.Result> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return executor.execute(request(localProfile(stub.url), promptFile));
                } catch (Exception thrown) {
                    throw new RuntimeException(thrown);
                }
            });
            try {
                result = pending.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException hung) {
                pending.cancel(true);
                check.that("a stalled body returned within the wall-clock bound", false);
                return;
            }
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            check.that("headers had already been sent when the bound fired", stub.headersSent);
            check.that("a stalled body is not ok", !result.ok());
            check.eq("a stalled body is role_local_endpoint_unreachable",
                    "role_local_endpoint_unreachable", result.code());
            check.contains("the stalled endpoint is named",
                    String.valueOf(result.evidence()), stub.url);
            check.that("the bound fires in seconds, not after waiting for a body that never comes",
                    elapsedMs >= 400 && elapsedMs < 8_000);
        }
    }

    /**
     * The defect: an endpoint that echoes Authorization puts the secret in body_head, and
     * RoleRunner copies evidence onto the report and the ledger. The token must not survive
     * that copy, on either the HTTP-error path or the unreadable-2xx path.
     */
    private static void reflectedBearerIsRedactedFromHttpError(Check check, Path promptFile)
            throws Exception {
        try (Stub stub = Stub.reflectingAuthorization(500, false)) {
            RoleExecutor.Result result = keyedExecutor().execute(request(
                    keyedProfile(stub.url), promptFile));
            check.eq("reflected 500 is still role_local_http_error",
                    "role_local_http_error", result.code());
            check.eq("the Authorization header was actually sent",
                    "Bearer s3cr3t", stub.lastAuthorization);
            assertTokenAbsent(check, "HTTP 500 evidence", result);
            check.contains("the reflection is still diagnosable",
                    String.valueOf(result.evidence().get("body_head")), "reflected");
            check.contains("the placeholder marks the secret",
                    String.valueOf(result.evidence().get("body_head")), "<redacted>");
        }
    }

    private static void reflectedBearerIsRedactedFromUnreadableBody(Check check, Path promptFile)
            throws Exception {
        try (Stub stub = Stub.reflectingAuthorization(200, true)) {
            RoleExecutor.Result result = keyedExecutor().execute(request(
                    keyedProfile(stub.url), promptFile));
            check.eq("reflected 2xx without choices is still unreadable",
                    "role_local_response_unreadable", result.code());
            check.eq("the Authorization header was actually sent on 2xx",
                    "Bearer s3cr3t", stub.lastAuthorization);
            assertTokenAbsent(check, "unreadable 2xx evidence", result);
            check.contains("the unreadable body head still names the reflection",
                    String.valueOf(result.evidence().get("body_head")), "reflected");
        }
    }

    private static void assertTokenAbsent(Check check, String where, RoleExecutor.Result result) {
        String evidence = String.valueOf(result.evidence());
        String raw = result.rawOutput() == null ? "" : result.rawOutput();
        check.that(where + " does not contain the bearer token", !evidence.contains("s3cr3t"));
        check.that(where + " does not contain Bearer plus the token",
                !evidence.contains("Bearer s3cr3t"));
        check.that(where + " raw output does not contain the bearer token", !raw.contains("s3cr3t"));
    }

    private static LocalHttpExecutor keyedExecutor() {
        return new LocalHttpExecutor(name -> "LOCAL_WARDEN_KEY".equals(name) ? "s3cr3t" : null);
    }

    private static Profile keyedProfile(String endpoint) {
        return Profile.parse("""
                version: 1
                profile: local-gemma
                role: reviewer
                vendor: ollama
                command: ollama
                model: gemma4:e4b
                runner: local
                endpoint: %s
                api_key_env: LOCAL_WARDEN_KEY
                limits: { wall_clock_minutes: 1 }
                """.formatted(endpoint), "local-gemma.yaml");
    }

    private static void quotedPromptSurvives(Check check, Path scratch) throws Exception {
        String prompt = "say \"hello\"\nand goodbye";
        Path promptFile = scratch.resolve("quoted.md");
        Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
        try (Stub stub = new Stub(200, completion(artifact("pass", "completed")))) {
            RoleExecutor.Result result = new LocalHttpExecutor().execute(request(
                    localProfile(stub.url), promptFile));
            check.that("quoted prompt still produces an answer", result.ok());
            Map<String, Object> sent = Json.parseObject(stub.lastBody);
            check.eq("model is the profile's model", "gemma4:e4b", sent.get("model"));
            check.eq("stream is the JSON boolean false", Boolean.FALSE, sent.get("stream"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) sent.get("messages");
            check.eq("a prompt with a quote and a newline arrives intact",
                    prompt, messages.get(0).get("content"));
        }
    }

    /** No `command`: the runner starts nothing, and the whole path must hold without one. */
    private static Profile localProfile(String endpoint) {
        return Profile.parse("""
                version: 1
                profile: local-gemma
                role: reviewer
                vendor: ollama
                model: gemma4:e4b
                runner: local
                endpoint: %s
                limits: { wall_clock_minutes: 1 }
                """.formatted(endpoint), "local-gemma.yaml");
    }

    /** Required fields are empty by default, so demanding them takes a profile that says so. */
    private static Profile demandingProfile(String endpoint) {
        return Profile.parse("""
                version: 1
                profile: local-gemma
                role: reviewer
                vendor: ollama
                command: ollama
                model: gemma4:e4b
                runner: local
                endpoint: %s
                limits: { wall_clock_minutes: 1 }
                artifact:
                  required_fields: [role, task_id, status, verdict, summary, findings]
                """.formatted(endpoint), "local-gemma.yaml");
    }

    private static RoleExecutor.Request request(Profile profile, Path promptFile) {
        return request(profile, promptFile, promptFile.getParent(), null);
    }

    private static RoleExecutor.Request request(Profile profile, Path promptFile,
                                                Path runDirectory, String diffBaseCommit) {
        return request(profile, promptFile, runDirectory, diffBaseCommit, null);
    }

    private static RoleExecutor.Request request(Profile profile, Path promptFile,
                                                Path runDirectory, String diffBaseCommit,
                                                Path schemaFile) {
        return new RoleExecutor.Request(
                "run-1", "wf-1", "reviewer", profile, null, diffBaseCommit,
                runDirectory, runDirectory, promptFile, schemaFile,
                "", "reviewer", List.of());
    }

    private static String completion(String text) {
        return Json.write(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", text)))));
    }

    /** What a reviewer is supposed to answer, minus the fields the adapter fills in. */
    private static String artifact(String verdict, String status) {
        return Json.write(Map.of(
                "status", status,
                "verdict", verdict,
                "summary", "read the diff",
                "findings", List.of()));
    }

    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        final String url;
        volatile String lastBody = "";
        volatile String lastAuthorization = "";
        volatile boolean headersSent;
        final int status;
        final String response;
        final boolean reflectAuthorization;
        final boolean wrapReflectionAsJson;
        final boolean stallAfterHeaders;
        final Path writeBeforeAnswering;
        final boolean reflectInsideCompletion;
        final CountDownLatch stallRelease = new CountDownLatch(1);

        Stub(int status, String response) throws IOException {
            this(status, response, false, false, false, null, false);
        }

        static Stub reflectingAuthorization(int status, boolean asJsonWithoutChoices)
                throws IOException {
            return new Stub(status, "", true, asJsonWithoutChoices, false, null, false);
        }

        static Stub stallingAfterHeaders() throws IOException {
            return new Stub(200, "", false, false, true, null, false);
        }

        /** A 200 whose assistant text quotes the Authorization header back at the caller. */
        static Stub echoingAuthorizationInsideACompletion() throws IOException {
            return new Stub(200, "", true, false, false, null, true);
        }

        /** Stands in for a tool-calling server that edits the tree before it answers. */
        static Stub writingBeforeAnswering(Path file, String response) throws IOException {
            return new Stub(200, response, false, false, false, file, false);
        }

        private Stub(int status, String response, boolean reflectAuthorization,
                     boolean wrapReflectionAsJson, boolean stallAfterHeaders,
                     Path writeBeforeAnswering, boolean reflectInsideCompletion) throws IOException {
            this.status = status;
            this.response = response;
            this.reflectAuthorization = reflectAuthorization;
            this.wrapReflectionAsJson = wrapReflectionAsJson;
            this.stallAfterHeaders = stallAfterHeaders;
            this.writeBeforeAnswering = writeBeforeAnswering;
            this.reflectInsideCompletion = reflectInsideCompletion;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastAuthorization = authorization == null ? "" : authorization;
                if (stallAfterHeaders) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    // A positive Content-Length with no bytes written leaves the client
                    // waiting for a body that will never arrive.
                    exchange.sendResponseHeaders(200, 1024);
                    headersSent = true;
                    try {
                        stallRelease.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                if (writeBeforeAnswering != null) {
                    Files.writeString(writeBeforeAnswering, "the server was here\n",
                            StandardCharsets.UTF_8);
                }
                String payload;
                if (reflectInsideCompletion) {
                    payload = completion("reflected " + lastAuthorization + " "
                            + artifact("pass", "completed"));
                } else if (reflectAuthorization) {
                    String reflected = "reflected " + lastAuthorization;
                    payload = wrapReflectionAsJson
                            ? Json.write(Map.of("id", "cmpl-1", "error", reflected))
                            : reflected;
                } else {
                    payload = response;
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                if (!stallAfterHeaders) exchange.close();
            }
        }

        @Override
        public void close() {
            stallRelease.countDown();
            server.stop(0);
        }
    }
}
