package dev.warden.execution;

import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Offline contract for an OpenCode coding CLI. This does not make the profile eligible.
 *
 * The generated config names the credential as {@code {env:NAME}} and never contains the
 * value. An OS-store credential is issued only as {@code WARDEN_CONNECTION_KEY} on the
 * child that runs. One connection's variable is restored after every connection variable
 * has been removed; other connections stay removed. {@code opencode debug config} must echo the
 * connection base URL and the requested model before {@code run} starts. GonkaRouter is
 * refused before any process because no documented provider option can send its no-fallback
 * header. A permission flag is only a claim: read-only is the worktree fingerprint.
 * Harness-reported cost is not billing and is never written as {@code cost_usd}.
 */
public final class OpenCodeExecutor implements RoleExecutor {
    private static final int PREVIEW_BYTES = 256 * 1024;
    /** Child-only name for a vault credential. It is not a user environment reference. */
    static final String ISSUED_ENV = "WARDEN_CONNECTION_KEY";
    private static final String MESSAGE =
            "Follow the Warden prompt file. Write the Warden artifact. Do not expect the prompt or a credential in argv.";

    private final ProcessRunner processes;
    private final GitRepository git;
    private final Function<String, String> environment;
    private final List<String> commandPrefix;
    private final Duration timeoutOverride;

    public OpenCodeExecutor(ProcessRunner processes, GitRepository git) {
        this(processes, git, System::getenv, List.of("opencode"), null);
    }

    /** Tests supply a fake prefix and a short deadline. Production uses {@code opencode}. */
    public OpenCodeExecutor(ProcessRunner processes, GitRepository git,
                            Function<String, String> environment, List<String> commandPrefix,
                            Duration timeoutOverride) {
        this.processes = processes;
        this.git = git;
        this.environment = environment == null ? System::getenv : environment;
        if (commandPrefix == null || commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("harness command prefix is required");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeoutOverride = timeoutOverride;
    }

    @Override public Result execute(Request request) throws Exception {
        long started = System.nanoTime();
        Profile profile = request.profile();
        Map<String, Object> evidence = baseEvidence(profile);
        Connection connection = profile.apiConnection();
        if (connection == null) return failure("role_api_connection_missing", started, "", evidence);
        String stale = recheck(profile, connection);
        if (stale != null) return failure(stale, started, "", evidence);
        evidence.put("connection", connection.id());
        evidence.put("connection_fingerprint", connection.fingerprint());
        evidence.put("provider", connection.provider());
        evidence.put("model_requested", profile.model());
        if (!connection.enabled()) return failure("role_api_connection_disabled", started, "", evidence);
        if ("gonkarouter".equals(connection.provider())) {
            evidence.put("process_started", false);
            evidence.put("reason", "no documented provider option sends X-Gonka-No-Fallback");
            return failure("role_harness_provider_unproven", started, "", evidence);
        }
        String secret;
        try {
            secret = new CredentialResolver(environment).resolve(connection);
        } catch (RuntimeException unavailable) {
            return failure("role_api_key_missing", started, "", evidence);
        }
        String config = configJson(connection, profile);
        if (containsSecret(config, secret) || containsSecret(String.join("\n", commandPrefix), secret)
                || containsSecret(MESSAGE, secret)) {
            return failure("role_harness_config_unproven", started, "", evidence);
        }
        Duration runLimit = timeoutOverride != null ? timeoutOverride
                : Duration.ofMinutes(Math.max(1, profile.wallClockMinutes()));
        // Debug config is not the coding turn. Keep it short even when a test bounds the run.
        Duration preflightLimit = Duration.ofSeconds(20);
        Map<String, String> preflightEnv = new LinkedHashMap<>();
        preflightEnv.put("OPENCODE_CONFIG_CONTENT", config);
        ProcessRunner.Result preflight;
        try {
            evidence.put("preflight_started", true);
            preflight = launch(command("debug", "config"), request.projectRoot(), preflightLimit, preflightEnv);
        } catch (ProcessRunner.CaptureException incomplete) {
            evidence.put("process_tree_settled", incomplete.settled());
            return failure(incomplete.settled() ? "role_harness_config_unproven" : "role_process_unsettled",
                    started, "", evidence);
        } catch (IOException cannotStart) {
            evidence.put("process_tree_settled", true);
            return failure("role_command_failed", started, "", evidence);
        }
        boolean preflightTruncated = preflight.stdoutTruncated() || preflight.stderrTruncated();
        boolean preflightLeaked = containsSecret(preflight.stdout(), secret)
                || containsSecret(preflight.stderr(), secret);
        String preflightOut = persist(request, request.evidenceName() + ".preflight",
                preflight.stdout(), preflight.stderr(), preflightTruncated, secret, evidence, "preflight");
        if (preflightLeaked) {
            evidence.put("process_tree_settled", true);
            return failure("role_harness_config_unproven", started, preflightOut, evidence);
        }
        if (preflight.timedOut() || !preflight.ok()
                || !preflightOut.contains(connection.baseUrl())
                || !preflightOut.contains(profile.model())) {
            evidence.put("process_tree_settled", true);
            evidence.put("timed_out", preflight.timedOut());
            return failure("role_harness_config_unproven", started, preflightOut, evidence);
        }
        Path artifact = request.runDirectory().resolve("harness-artifact.json");
        Files.createDirectories(request.runDirectory());
        Files.deleteIfExists(artifact);
        String before = null;
        if (profile.readOnly()) {
            if (git == null || request.diffBaseCommit() == null) {
                return failure("role_read_only_uncheckable", started, "", evidence);
            }
            before = git.fingerprint(request.diffBaseCommit());
        }
        Map<String, String> runEnv = new LinkedHashMap<>();
        runEnv.put("OPENCODE_CONFIG_CONTENT", config);
        runEnv.put("WARDEN_HARNESS_PROMPT", request.promptFile().toAbsolutePath().toString());
        runEnv.put("WARDEN_HARNESS_ARTIFACT", artifact.toAbsolutePath().toString());
        String variable = credentialVariable(connection);
        if (variable != null && secret != null) runEnv.put(variable, secret);
        List<String> runCommand = command("run", "--format", "json", MESSAGE);
        evidence.put("command_preview", runCommand);
        ProcessRunner.Result process;
        try {
            evidence.put("vendor_turn_started", true);
            evidence.put("process_started", true);
            process = launch(runCommand, request.projectRoot(), runLimit, runEnv);
        } catch (ProcessRunner.CaptureException incomplete) {
            evidence.put("process_tree_settled", incomplete.settled());
            return failure(incomplete.settled() ? "role_output_capture_failed" : "role_process_unsettled",
                    started, "", evidence);
        } catch (IOException cannotStart) {
            evidence.put("vendor_turn_started", false);
            evidence.put("process_started", false);
            evidence.put("process_tree_settled", true);
            return failure("role_command_failed", started, "", evidence);
        }
        boolean truncated = process.stdoutTruncated() || process.stderrTruncated();
        String safeOut = persist(request, request.evidenceName(), process.stdout(), process.stderr(),
                truncated, secret, evidence, "run");
        evidence.put("process_tree_settled", true);
        evidence.put("exit_code", (long) process.exitCode());
        evidence.put("timed_out", process.timedOut());
        evidence.put("stdout_truncated", process.stdoutTruncated());
        evidence.put("stderr_truncated", process.stderrTruncated());
        noteCost(safeOut, evidence);
        if (process.timedOut()) return failure("role_timeout", started, safeOut, evidence);
        if (truncated) return failure("role_output_limit_exceeded", started, safeOut, evidence);
        boolean artifactMissing = !Files.isRegularFile(artifact);
        if (artifactMissing) {
            String code = process.exitCode() == 0 ? "role_harness_artifact_missing" : "role_command_failed";
            return failure(code, started, safeOut, evidence);
        }
        Map<String, Object> artifactMap = readArtifact(artifact, secret, evidence);
        if (artifactMap == null) return failure("role_harness_artifact_missing", started, safeOut, evidence);
        if (!identityProven(safeOut, profile.model(), evidence)) {
            return failure("role_harness_identity_unproven", started, safeOut, evidence);
        }
        if (profile.readOnly()) {
            String after = git.fingerprint(request.diffBaseCommit());
            boolean unchanged = before.equals(after);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("method", "worktree_content_fingerprint");
            check.put("matched", unchanged);
            evidence.put("read_only_check", check);
            if (!unchanged) return failure("role_violated_read_only", started, safeOut, evidence);
        }
        List<String> missing = new ArrayList<>();
        for (String field : profile.requiredArtifactFields()) {
            if (!artifactMap.containsKey(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            evidence.put("missing_fields", missing);
            return failure("role_artifact_incomplete", started, safeOut, evidence);
        }
        List<String> schemaProblems = DirectCliExecutor.schemaErrors(profile, request, artifactMap);
        if (!schemaProblems.isEmpty()) {
            evidence.put("schema_errors", schemaProblems.stream().limit(40).toList());
            return failure("role_artifact_schema_violation", started, safeOut, evidence);
        }
        String semantic = DirectCliExecutor.semanticFailure(request.role(), artifactMap);
        if (semantic != null) {
            evidence.put("failure", semantic);
            return new Result(false, semantic, elapsed(started), safeOut, artifactMap, evidence);
        }
        evidence.put("verdict", artifactMap.get("verdict"));
        return new Result(true, "ok", elapsed(started), safeOut, artifactMap, evidence);
    }

    private ProcessRunner.Result launch(List<String> command, Path directory, Duration timeout,
                                        Map<String, String> overlay) throws IOException, InterruptedException {
        return processes.withoutEnvironment(Set.of(ISSUED_ENV)).withEnvironment(overlay)
                .runVendor(command, directory, timeout, PREVIEW_BYTES, null);
    }

    private List<String> command(String... extra) {
        List<String> command = new ArrayList<>(commandPrefix);
        command.addAll(List.of(extra));
        return List.copyOf(command);
    }

    private static Map<String, Object> baseEvidence(Profile profile) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runner", profile.runner());
        evidence.put("harness", "opencode");
        evidence.put("adapter", "opencode-offline-v1");
        evidence.put("vendor_turn_started", false);
        evidence.put("process_started", false);
        evidence.put("process_tree_settled", true);
        evidence.put("coding_tools_verified", false);
        evidence.put("eligible_implementer", false);
        evidence.put("live_smoke", false);
        evidence.put("config_isolation", "override_merged_not_exclusive");
        evidence.put("managed_config", "unproven");
        evidence.put("cost_status", "unknown");
        evidence.put("billing_confirmed", false);
        evidence.put("strict_budget", false);
        return evidence;
    }

    /** The saved connection is read again before dispatch. An in-flight process is not rewritten. */
    private static String recheck(Profile profile, Connection connection) {
        if (profile.apiSettings() == null || profile.apiSettings().home() == null) return null;
        try {
            dev.warden.config.ConnectionStore.Snapshot snapshot =
                    new dev.warden.config.ConnectionStore(profile.apiSettings().home()).load();
            Connection current = snapshot.connections().get(connection.id());
            if (current == null) return "role_api_connection_missing";
            if (!current.enabled()) return "role_api_connection_disabled";
            if (!current.fingerprint().equals(connection.fingerprint())) return "role_api_connection_changed";
            return null;
        } catch (IOException | RuntimeException unavailable) {
            return "role_api_connection_missing";
        }
    }

    /** Env references keep their own name. A vault credential is issued under {@link #ISSUED_ENV}. */
    static String credentialVariable(Connection connection) {
        if (connection.apiKeyEnv() != null) return connection.apiKeyEnv();
        if (connection.osStoreTarget() != null) return ISSUED_ENV;
        return null;
    }

    static String configJson(Connection connection, Profile profile) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", profile.model());
        Map<String, Object> models = new LinkedHashMap<>();
        models.put(profile.model(), model);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseURL", connection.baseUrl());
        String variable = credentialVariable(connection);
        if (variable != null) options.put("apiKey", "{env:" + variable + "}");
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("npm", "@ai-sdk/openai-compatible");
        provider.put("name", "Warden " + connection.id());
        provider.put("options", options);
        provider.put("models", models);
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("warden", provider);
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("edit", profile.readOnly() ? "deny" : "allow");
        permission.put("bash", profile.readOnly() ? "deny" : "allow");
        permission.put("webfetch", "deny");
        permission.put("external_directory", "deny");
        Map<String, Object> root = new LinkedHashMap<>();
        // OpenCode selects provider/model at the first slash. The model id itself may contain
        // slashes, so debug output must still contain the requested id or the run is refused.
        root.put("model", "warden/" + profile.model());
        root.put("provider", providers);
        root.put("permission", permission);
        if (profile.readOnly()) {
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("write", false);
            tools.put("edit", false);
            root.put("tools", tools);
        }
        return Json.write(root);
    }

    /** A reported figure stays out of {@code cost_usd}, so a printed zero is not a free call. */
    private static void noteCost(String stdout, Map<String, Object> evidence) {
        Double reported = null;
        if (stdout != null) {
            for (Map<String, Object> event : events(stdout)) {
                if (!"step_finish".equals(event.get("type"))) continue;
                if (event.get("cost") instanceof Number number) reported = number.doubleValue();
            }
        }
        if (reported != null) {
            evidence.put("cost_status", "harness_reported");
            evidence.put("harness_reported_cost_usd", reported);
            evidence.put("cost_source", "harness");
        }
        evidence.put("billing_confirmed", false);
    }

    private static boolean identityProven(String stdout, String requested, Map<String, Object> evidence) {
        String served = null;
        boolean saw = false;
        boolean mismatch = false;
        for (Map<String, Object> event : events(stdout)) {
            if (!"step_finish".equals(event.get("type"))) continue;
            saw = true;
            Object model = event.get("model");
            if (!(model instanceof String text) || text.isBlank()) {
                mismatch = true;
                continue;
            }
            if (served != null && !served.equals(text)) mismatch = true;
            served = text;
            if (!text.equals(requested)) mismatch = true;
        }
        if (served != null) evidence.put("model_served", served);
        return saw && served != null && !mismatch;
    }

    private static List<Map<String, Object>> events(String stdout) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{")) continue;
            try {
                Map<String, Object> object = Json.parseObject(trimmed);
                if (object != null) found.add(object);
            } catch (RuntimeException ignored) {
                // Chatter around the event stream is not an identity receipt.
            }
        }
        return found;
    }

    private static Map<String, Object> readArtifact(Path file, String secret, Map<String, Object> evidence)
            throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (containsSecret(text, secret)) {
            Files.writeString(file, redact(text, secret), StandardCharsets.UTF_8);
            evidence.put("artifact_refused", "credential_reflected");
            return null;
        }
        Map<String, Object> artifact;
        try {
            artifact = Json.parseObject(text);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (artifact == null) return null;
        if (containsSecret(Json.write(artifact), secret)) {
            evidence.put("artifact_refused", "credential_reflected");
            return null;
        }
        return artifact;
    }

    /**
     * @return the redacted stdout that callers may keep. Truncation with a live credential
     *         discards the preview: a cut can leave an unredacted prefix of the secret.
     */
    private static String persist(Request request, String baseName, String stdout, String stderr,
                                  boolean truncated, String secret, Map<String, Object> evidence,
                                  String prefix) throws IOException {
        Path directory = request.runDirectory().resolve("raw");
        Files.createDirectories(directory);
        String safeOut;
        String safeErr;
        if (truncated && secret != null) {
            safeOut = "output truncated; preview discarded because a credential was in the child environment\n";
            safeErr = safeOut;
            evidence.put(prefix + "_preview_discarded", true);
        } else {
            safeOut = redact(stdout, secret);
            safeErr = redact(stderr, secret);
        }
        Path out = directory.resolve(baseName + ".stdout.txt");
        Path err = directory.resolve(baseName + ".stderr.txt");
        Files.writeString(out, safeOut, StandardCharsets.UTF_8);
        Files.writeString(err, safeErr, StandardCharsets.UTF_8);
        String relativeOut = request.runDirectory().relativize(out).toString().replace('\\', '/');
        String relativeErr = request.runDirectory().relativize(err).toString().replace('\\', '/');
        if ("preflight".equals(prefix)) {
            evidence.put("raw_preflight_stdout", relativeOut);
            evidence.put("raw_preflight_stderr", relativeErr);
        } else {
            evidence.put("raw_stdout", relativeOut);
            evidence.put("raw_stderr", relativeErr);
            evidence.put("raw_capture_complete", !truncated);
        }
        return safeOut;
    }

    private static boolean containsSecret(String text, String secret) {
        return secret != null && !secret.isEmpty() && text != null && text.contains(secret);
    }

    static String redact(String text, String secret) {
        if (text == null) return "";
        if (secret == null || secret.isEmpty()) return text;
        return text.replace(secret, "[redacted]");
    }

    private static Result failure(String code, long started, String raw, Map<String, Object> evidence) {
        evidence.put("failure", code);
        evidence.put("duration_millis", elapsed(started).toMillis());
        return new Result(false, code, elapsed(started), raw == null ? "" : raw, null, evidence);
    }

    private static Duration elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started); }
}
