package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** The same bounded runtime serves explicit verification and admitted implementer dispatches. */
public final class VerifiedOpenCodeExecutor implements RoleExecutor {
    private final ProcessRunner processes;
    private final boolean verification;
    private final java.util.function.Function<String, String> environment;
    private final List<String> commandPrefix;
    private final boolean sandbox;

    public VerifiedOpenCodeExecutor(ProcessRunner processes, GitRepository git) {
        this(processes, false, System::getenv, null);
    }

    VerifiedOpenCodeExecutor(
            ProcessRunner processes,
            boolean verification,
            java.util.function.Function<String, String> environment,
            List<String> commandPrefix) {
        this(processes, verification, environment, commandPrefix, true);
    }

    VerifiedOpenCodeExecutor(
            ProcessRunner processes,
            boolean verification,
            java.util.function.Function<String, String> environment,
            List<String> commandPrefix,
            boolean sandbox) {
        this.sandbox = sandbox;
        this.processes = processes;
        this.verification = verification;
        this.environment = environment;
        this.commandPrefix = commandPrefix == null ? null : List.copyOf(commandPrefix);
    }

    public static VerifiedOpenCodeExecutor verification(ProcessRunner processes) {
        return new VerifiedOpenCodeExecutor(processes, true, System::getenv, null);
    }

    @Override
    public Result execute(Request request) throws Exception {
        long started = System.nanoTime();
        Profile profile = request.profile();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("adapter", CodingAttestation.ADAPTER);
        evidence.put("harness", "opencode");
        evidence.put("vendor_turn_started", false);
        evidence.put("process_tree_settled", true);
        evidence.put("coding_tools_verified", false);
        evidence.put("strict_budget", false);
        evidence.put("cost_status", "unknown");
        evidence.put("billing_confirmed", false);
        if (!"implementer".equals(profile.role())
                || profile.readOnly()
                || !profile.enforceSchema()
                || profile.harnessExecutable() == null
                || profile.maxOutputTokens() < 1)
            return fail("harness_coding_unproven", started, evidence, "");
        if (!verification && !profile.verified())
            return fail("harness_coding_unproven", started, evidence, "");
        Connection connection = profile.apiConnection();
        if (connection == null) return fail("role_api_connection_missing", started, evidence, "");
        Connection current;
        try {
            current =
                    new ConnectionStore(profile.apiSettings().home())
                            .versioned(connection.id())
                            .connection();
        } catch (Exception unavailable) {
            return fail("role_api_connection_missing", started, evidence, "");
        }
        if (!current.enabled()) return fail("role_api_connection_disabled", started, evidence, "");
        if (!current.fingerprint().equals(connection.fingerprint()))
            return fail("role_api_connection_changed", started, evidence, "");
        if (request.schemaFile() == null
                || !Files.isRegularFile(request.schemaFile(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(request.promptFile(), LinkOption.NOFOLLOW_LINKS))
            return fail("role_harness_contract_missing", started, evidence, "");
        if (Files.size(request.promptFile()) > 256 * 1024
                || Files.size(request.schemaFile()) > 64 * 1024)
            return fail("role_harness_contract_too_large", started, evidence, "");
        String fingerprint = profile.apiFingerprint();
        Path binary = profile.harnessExecutable().toRealPath();
        String binaryHash = CodingAttestation.binarySha256(binary);
        evidence.put("binary_sha256", binaryHash);
        if (!verification && !profile.verified())
            return fail("role_harness_profile_changed", started, evidence, "");
        evidence.put("connection", connection.id());
        evidence.put("connection_fingerprint", connection.fingerprint());
        evidence.put("profile_fingerprint", fingerprint);
        evidence.put("model_requested", profile.model());
        int outputLimit = CodingAttestation.outputLimit(profile);
        int requestLimit = CodingAttestation.requestLimit(profile);
        Duration limit = CodingRelay.budget(profile.wallClockMinutes());
        evidence.put("max_requests", requestLimit);
        evidence.put("max_output_tokens", outputLimit);
        evidence.put("time_budget_seconds", limit.toSeconds());
        String secret;
        try {
            secret = new CredentialResolver(environment).resolve(connection);
        } catch (RuntimeException missing) {
            return fail("role_api_key_missing", started, evidence, "");
        }
        Files.createDirectories(request.runDirectory());
        // Everything this dispatch shares with OpenCode lives in one exchange directory. The
        // rest of the run tree is hidden from the implementer (RunArtifactShield).
        Path exchange = Files.createTempDirectory(request.runDirectory(), "opencode-io-");
        Path state = Files.createDirectories(exchange.resolve("state"));
        RunArtifactShield shield =
                RunArtifactShield.plan(
                        request.projectRoot(),
                        profile.apiSettings().home(),
                        exchange,
                        List.of(binary),
                        sandbox);
        Map<String, Object> permission =
                RunArtifactShield.permissions(
                        request.projectRoot(), exchange, profile.apiSettings().home());
        evidence.put("run_artifacts_hidden", shield.mode());
        evidence.put("hidden_paths", shield.hiddenPaths());
        Map<String, String> env = isolatedEnvironment(state);
        Set<String> removals = new HashSet<>();
        for (String name : System.getenv().keySet()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith("OPENCODE_")
                    || upper.startsWith("XDG_")
                    || upper.startsWith("OTEL_")
                    || upper.endsWith("_API_KEY")
                    || upper.endsWith("_TOKEN")
                    || upper.endsWith("_SECRET")
                    || upper.endsWith("_PASSWORD")) removals.add(name);
        }
        for (Connection registered :
                new ConnectionStore(profile.apiSettings().home()).load().connections().values())
            if (registered.apiKeyEnv() != null) removals.add(registered.apiKeyEnv());
        ProcessRunner clean = processes.withoutEnvironmentAndOverrides(removals);
        var version =
                clean.withEnvironment(env)
                        .runVendor(
                                command(binary, "--version"),
                                request.projectRoot(),
                                Duration.ofSeconds(20),
                                8192,
                                null);
        if (!version.ok() || !CodingAttestation.SUPPORTED_VERSION.equals(version.stdout().strip()))
            return fail("role_harness_version_unproven", started, evidence, "");
        evidence.put("opencode_version", version.stdout().strip());
        Path artifact = exchange.resolve("harness-artifact.json").toAbsolutePath().normalize();
        Path kept = request.runDirectory().resolve("harness-artifact.json");
        Files.deleteIfExists(kept);
        try (CodingRelay relay =
                new CodingRelay(
                        connection,
                        profile.model(),
                        secret,
                        limit,
                        requestLimit,
                        outputLimit)) {
            env.put("WARDEN_PROBE_RELAY_KEY", relay.bearer());
            env.put(
                    "OPENCODE_CONFIG_CONTENT",
                    CodingProbe.config(relay.baseUrl(), profile.model(), outputLimit, permission));
            var scoped = clean.withEnvironment(env);
            var debug =
                    scoped.runVendor(
                            shield.wrap(command(binary, "debug", "config", "--pure")),
                            request.projectRoot(),
                            Duration.ofSeconds(25),
                            256 * 1024,
                            null);
            persist(request, "config", debug, secret, relay.bearer());
            if (!debug.ok()
                    || debug.stdoutTruncated()
                    || debug.stderrTruncated()
                    || !CodingProbe.validConfig(
                            debug.stdout(), relay.baseUrl(), profile.model(), permission))
                return fail("role_harness_config_unproven", started, evidence, "");
            evidence.put("effective_config_checked", true);
            // Paths and schema are in a run-owned file. The shell never transports JSON quotes.
            Path instruction =
                    Files.createTempFile(exchange, "opencode-dispatch-", ".md");
            String contract = Files.readString(request.promptFile(), StandardCharsets.UTF_8);
            String schema = Files.readString(request.schemaFile(), StandardCharsets.UTF_8);
            if (contract.length() > 256 * 1024 || schema.length() > 64 * 1024)
                return fail("role_harness_contract_too_large", started, evidence, "");
            Files.writeString(
                    instruction,
                    "Follow the Warden task contract below. Use tools to read project files,"
                            + " implement the task, and run its acceptance checks. The final result"
                            + " MUST be written using the write tool to this exact file: "
                            + artifact
                            + "\n"
                            + "Return one JSON object matching the schema below. Write the JSON"
                            + " file directly; do not build it through shell quoting. This file"
                            + " destination overrides any instruction to print only to stdout. Do"
                            + " not edit any task contract or checks. Do not commit. Warden's"
                            + " run records (.warden/runs) are not part of the task and are not"
                            + " readable.\n\n"
                            + contract
                            + "\n\n## Required final JSON schema\n"
                            + schema,
                    StandardCharsets.UTF_8);
            long remaining = limit.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) return fail("role_timeout", started, evidence, "");
            evidence.put("vendor_turn_started", true);
            ProcessRunner.Result run;
            try {
                run =
                        scoped.runVendor(
                                shield.wrap(command(
                                        binary,
                                        "run",
                                        "Read the attached Warden dispatch file and execute its"
                                                + " task. Write the required JSON artifact file.",
                                        "--pure",
                                        "--format",
                                        "json",
                                        "--file",
                                        instruction.toAbsolutePath().toString())),
                                request.projectRoot(),
                                Duration.ofNanos(remaining),
                                256 * 1024,
                                null);
            } catch (ProcessRunner.CaptureException unsettled) {
                evidence.put("process_tree_settled", unsettled.settled());
                return fail(
                        unsettled.settled()
                                ? "role_output_capture_failed"
                                : "role_process_unsettled",
                        started,
                        evidence,
                        "");
            }
            // Stop any incomplete local body before taking the relay's synchronized snapshot.
            relay.close();
            String safe = persist(request, "run", run, secret, relay.bearer());
            Map<String, Object> observed = relay.evidence();
            evidence.put("relay", observed);
            evidence.put("process_exit", run.exitCode());
            evidence.put("timed_out", run.timedOut());
            evidence.put("tokens", usage(observed));
            if (run.timedOut()) return fail("role_timeout", started, evidence, safe);
            if (!run.ok() || run.stdoutTruncated() || run.stderrTruncated())
                return fail(processFailure(observed), started, evidence, safe);
            if (!Boolean.TRUE.equals(observed.get("identity_confirmed")))
                return fail("role_harness_identity_unproven", started, evidence, safe);
            evidence.put("model_served", profile.model());
            if (!binaryHash.equals(CodingAttestation.binarySha256(binary))
                    || !fingerprint.equals(profile.apiFingerprint()))
                return fail("role_harness_profile_changed", started, evidence, safe);
            if (!verification && !profile.verified())
                return fail("role_harness_profile_changed", started, evidence, safe);
            if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(artifact) > 256 * 1024)
                return fail("role_harness_artifact_missing", started, evidence, safe);
            String text = Files.readString(artifact, StandardCharsets.UTF_8);
            if (secret != null && text.contains(secret)) {
                Files.writeString(artifact, redact(text, secret, relay.bearer()));
                Files.writeString(kept, redact(text, secret, relay.bearer()));
                return fail("role_harness_credential_reflected", started, evidence, safe);
            }
            // The run keeps its copy beside the other evidence.
            Files.writeString(kept, text, StandardCharsets.UTF_8);
            Map<String, Object> result;
            try {
                result = Json.parseObject(text);
            } catch (RuntimeException invalid) {
                return fail("role_artifact_unparseable", started, evidence, safe);
            }
            if (!"implementer".equals(result.get("role"))
                    || (request.task() != null
                            && !request.task().id().equals(result.get("task_id"))))
                return fail("role_artifact_schema_violation", started, evidence, safe);
            for (String field : profile.requiredArtifactFields())
                if (!result.containsKey(field))
                    return fail("role_artifact_incomplete", started, evidence, safe);
            List<String> problems = DirectCliExecutor.schemaErrors(profile, request, result);
            if (!problems.isEmpty()) {
                evidence.put("schema_errors", problems);
                return fail("role_artifact_schema_violation", started, evidence, safe);
            }
            evidence.put("artifact_schema_verified", true);
            evidence.put("artifact_valid", true);
            evidence.put("coding_tools_verified", true);
            String semantic = DirectCliExecutor.semanticFailure(request.role(), result);
            if (semantic != null)
                return new Result(false, semantic, elapsed(started), safe, result, evidence);
            evidence.put("ok", true);
            return new Result(true, "ok", elapsed(started), safe, result, evidence);
        }
    }

    static Map<String, String> isolatedEnvironment(Path state) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : List.of("CONFIG", "DATA", "CACHE", "STATE"))
            env.put(
                    "XDG_" + name + "_HOME",
                    Files.createDirectories(state.resolve(name.toLowerCase(Locale.ROOT)))
                            .toString());
        env.put(
                "OPENCODE_CONFIG_DIR",
                Files.createDirectories(state.resolve("config-dir")).toString());
        env.put("OPENCODE_DISABLE_PROJECT_CONFIG", "1");
        env.put("OPENCODE_DISABLE_MODELS_FETCH", "1");
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        env.put("OPENCODE_PURE", "1");
        env.put("OPENCODE_DB", state.resolve("opencode.db").toString());
        return env;
    }

    static Map<String, Object> usage(Map<String, Object> relay) {
        long input = 0, output = 0;
        if (!(relay.get("responses") instanceof List<?> rows)
                || rows.isEmpty()
                || !(relay.get("requests") instanceof Number count)
                || count.longValue() != rows.size()) return Map.of("status", "unknown");
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> response)
                    || !(response.get("usage") instanceof Map<?, ?> usage)
                    || !(usage.get("prompt_tokens") instanceof Number in)
                    || !(usage.get("completion_tokens") instanceof Number out)
                    || !Double.isFinite(in.doubleValue())
                    || !Double.isFinite(out.doubleValue())
                    || in.doubleValue() < 0
                    || out.doubleValue() < 0
                    || in.doubleValue() != in.longValue()
                    || out.doubleValue() != out.longValue()) return Map.of("status", "unknown");
            try {
                input = Math.addExact(input, in.longValue());
                output = Math.addExact(output, out.longValue());
            } catch (ArithmeticException overflow) {
                return Map.of("status", "unknown");
            }
        }
        return Map.of("input", input, "output", output, "status", "provider_reported");
    }

    private List<String> command(Path binary, String... args) {
        List<String> command =
                new ArrayList<>(commandPrefix == null ? List.of(binary.toString()) : commandPrefix);
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    private static String persist(
            Request request,
            String suffix,
            ProcessRunner.Result result,
            String secret,
            String bearer)
            throws IOException {
        Path raw = Files.createDirectories(request.runDirectory().resolve("raw"));
        String out =
                result.stdoutTruncated()
                        ? "truncated output discarded"
                        : redact(result.stdout(), secret, bearer);
        String err =
                result.stderrTruncated()
                        ? "truncated output discarded"
                        : redact(result.stderr(), secret, bearer);
        Files.writeString(
                raw.resolve(request.evidenceName() + "-" + suffix + ".stdout.txt"),
                out,
                StandardCharsets.UTF_8);
        Files.writeString(
                raw.resolve(request.evidenceName() + "-" + suffix + ".stderr.txt"),
                err,
                StandardCharsets.UTF_8);
        return out;
    }

    private static String redact(String text, String secret, String bearer) {
        if (secret != null) text = text.replace(secret, "[REDACTED]");
        return text.replace(bearer, "[REDACTED]");
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    /**
     * Why OpenCode exited unsuccessfully. When the relay stopped it because the upstream
     * service timed out, throttled or failed, that is the reason: on 2026-10-28 a JoinGonka
     * turn that sent no headers for 120 s read as a harness failure, which hides that the
     * stop was the provider's and that nothing about the work was judged.
     */
    static String processFailure(Map<String, Object> relay) {
        Object failure = relay == null ? null : relay.get("failure");
        if (failure instanceof String code) {
            if (code.startsWith("role_api_")) return code;
            if (code.equals("probe_upstream_http_429")) return "role_api_rate_limited";
            if (code.startsWith("probe_upstream_http_5")) return "role_api_provider_error";
            // The relay's own bounds, not a broken harness: say which one ran out.
            if (code.equals("probe_turn_limit")) return "role_harness_turn_limit";
            if (code.equals("probe_time_limit")) return "role_harness_time_limit";
        }
        return "role_harness_process_failed";
    }

    private static Result fail(
            String code, long started, Map<String, Object> evidence, String raw) {
        evidence.put("ok", false);
        evidence.put("failure", code);
        return new Result(false, code, elapsed(started), raw, null, evidence);
    }
}
