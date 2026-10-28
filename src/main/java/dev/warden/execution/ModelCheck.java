package dev.warden.execution;

import dev.warden.config.CheckSettings;
import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.execution.orca.OrcaProbe;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.PromptRenderer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** A fresh inference challenge. It grants no role, capability stamp, or automatic fallback. */
public final class ModelCheck {
    @FunctionalInterface public interface Probe {
        Map<String, Object> run(Profile profile, UserConfig user, Path project, CheckSettings settings) throws Exception;
    }
    private final ProcessRunner processes;
    private final Function<String, String> environment;
    private final Probe probe;

    public ModelCheck(ProcessRunner processes) { this(processes, System::getenv, null); }
    public ModelCheck(ProcessRunner processes, Function<String, String> environment, Probe probe) {
        this.processes = processes; this.environment = environment;
        this.probe = probe == null ? this::ping : probe;
    }

    public Map<String, Object> run(UserConfig user, Path project, Set<String> names, Consumer<String> progress)
            throws Exception {
        CheckSettings settings = CheckSettings.load(user.home());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            Profile profile = user.profiles().get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profile", name);
            if (profile == null) {
                row.put("ok", false); row.put("code", "check_profile_unavailable");
            } else {
                row.put("model", profile.model()); row.put("vendor", profile.vendor());
                row.put("runner", profile.harness() == null ? profile.runner() : profile.harness());
                row.put("connection", profile.connectionRef());
                row.put("source_profile", profile.name());
                row.put("configuration", configuration(profile, user));
                progress.accept("check " + name + " · " + profile.model() + " …");
                long start = System.nanoTime();
                try { row.putAll(probe.run(profile, user, project, settings)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                catch (Exception failed) {
                    // Vendor output and exception messages may contain credentials. Never project them.
                    row.put("ok", false); row.put("code", "check_failed");
                }
                if (!row.get("configuration").equals(configuration(profile, user))) {
                    row.put("ok", false); row.put("code", "check_configuration_changed");
                }
                row.put("duration_millis", Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
            row.put("checked_at", Instant.now().toString());
            row.put("next", next(String.valueOf(row.get("code"))));
            rows.add(row);
            progress.accept("check " + name + " · " + (Boolean.TRUE.equals(row.get("ok")) ? "pong" : row.get("code")));
        }
        boolean ok = !rows.isEmpty() && rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("ok")));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", ok); report.put("code", ok ? "check_passed" : "model_check_failed");
        report.put("checked_at", Instant.now().toString()); report.put("results", rows);
        report.put("settings_file", CheckSettings.file(user.home()).toString());
        report.put("scope", "availability_now_not_coding_verification");
        report.put("billing", "diagnostic_inference_separate_from_task_role_budget");
        report.put("diagnostic_calls", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("vendor_turn_started"))).count());
        report.put("unpriced_calls", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("vendor_turn_started"))
                && !(row.get("cost_usd") instanceof Number)).count());
        report.put("reported_cost_usd", rows.stream().filter(row -> row.get("cost_usd") instanceof Number)
                .mapToDouble(row -> ((Number) row.get("cost_usd")).doubleValue()).sum());
        Path directory = user.home().resolve("checks");
        Files.createDirectories(directory);
        Path receipt = directory.resolve("check-" + UUID.randomUUID() + ".json");
        Files.writeString(receipt, Json.write(report));
        report.put("receipt", receipt.toString());
        atomicWrite(directory.resolve("latest.json"), Json.write(report));
        saveStates(directory, rows);
        return report;
    }

    private Map<String, Object> ping(Profile profile, UserConfig user, Path project, CheckSettings settings) throws Exception {
        if (profile.harness() != null && (profile.harnessExecutable() == null
                || !Files.isRegularFile(profile.harnessExecutable()))) return failure("check_harness_missing");
        if (profile.apiConnection() != null || "api".equals(profile.runner()))
            return api(profile, settings);
        if ("orca".equals(profile.runner())) {
            // A direct CLI login does not prove the worker channel. Reuse its native settled probe.
            var outcome = new OrcaProbe(cleanProcesses(user)).run(new OrcaProbe.Options(profile.command(),
                    profile.model(), profile.effort(), false, project, Math.max(1, (settings.timeoutSeconds() + 59) / 60)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", outcome.ok()); result.put("code", outcome.ok() ? "pong" : "check_orca_failed");
            result.put("transport", "orca_worker_probe"); result.put("cost_status", "unknown");
            result.put("vendor_turn_started", true);
            return result;
        }
        if ("local".equals(profile.runner())) {
            String endpoint = profile.endpoint();
            String suffix = "/chat/completions";
            if (endpoint == null) return failure("check_local_endpoint_unsupported");
            Connection local = new Connection("local-check", "custom", "openai_chat",
                    endpoint.endsWith(suffix) ? endpoint.substring(0, endpoint.length() - suffix.length()) : endpoint,
                    profile.apiKeyEnv() == null ? null : "env:" + profile.apiKeyEnv(), true);
            return api(profile, local, settings, URI.create(endpoint));
        }
        return cli(profile, user, settings);
    }

    private Map<String, Object> api(Profile profile, CheckSettings settings) throws Exception {
        return api(profile, profile.apiConnection(), settings);
    }

    private Map<String, Object> api(Profile profile, Connection connection, CheckSettings settings) throws Exception {
        return api(profile, connection, settings, connection == null ? null : URI.create(connection.endpoint()));
    }

    private Map<String, Object> api(Profile profile, Connection connection, CheckSettings settings, URI endpoint) throws Exception {
        if (connection == null) return failure("check_connection_missing");
        if (!connection.enabled()) return failure("check_connection_disabled");
        String token;
        try { token = new CredentialResolver(environment).resolve(connection); }
        catch (RuntimeException missing) { return failure("check_auth_missing"); }
        String nonce = UUID.randomUUID().toString();
        int outputLimit = Math.min(settings.maxOutputTokens(), profile.maxOutputTokens());
        if (outputLimit < 1) return failure("check_output_limit_missing");
        var body = ApiExecutor.completionBody(connection, profile.model(), prompt(nonce), outputLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vendor_turn_started", true); result.put("cost_status", "unknown");
        ApiExecutor.HttpReply reply;
        try { reply = ApiExecutor.call(connection, "POST", endpoint, Json.write(body), token,
                limit(profile, settings)); }
        catch (ApiExecutor.CallFailure failed) { result.putAll(failure(failed.code)); return result; }
        Map<String, Object> envelope = ApiExecutor.object(reply.body());
        Map<String, Object> telemetry = new LinkedHashMap<>();
        ApiExecutor.recordReceipt(connection, envelope, reply.headers(), telemetry, token);
        for (String key : List.of("tokens", "cost_usd", "cost_status"))
            if (telemetry.containsKey(key)) result.put(key, telemetry.get(key));
        String error = ApiExecutor.errorCode(reply.status(), envelope);
        if (error == null && (envelope == null || !profile.model().equals(envelope.get("model"))))
            error = "check_model_mismatch";
        boolean pong = false;
        if (error == null && envelope.get("choices") instanceof List<?> choices && choices.size() == 1
                && choices.get(0) instanceof Map<?, ?> choice
                && "stop".equals(choice.get("finish_reason"))
                && choice.get("message") instanceof Map<?, ?> message
                && !message.containsKey("tool_calls") && message.get("content") instanceof String content) {
            try { pong = nonce.equals(Json.parseObject(content.strip()).get("pong")); }
            catch (RuntimeException invalid) { /* A banner, auth page or echo is not a model answer. */ }
        }
        result.put("ok", error == null && pong);
        result.put("code", error != null ? error : pong ? "pong" : "check_no_pong");
        result.put("identity", "provider_response_exact_model");
        return result;
    }

    private Map<String, Object> cli(Profile profile, UserConfig user, CheckSettings settings) throws Exception {
        Path fixture = Files.createTempDirectory("warden-check-").toAbsolutePath().normalize();
        try {
            ProcessRunner clean = cleanProcesses(user);
            if (!clean.run(List.of("git", "init", "--quiet"), fixture, Duration.ofSeconds(15)).ok())
                return failure("check_fixture_failed");
            String nonce = UUID.randomUUID().toString();
            String prompt = prompt(nonce);
            Path promptFile = fixture.resolve("ping.txt");
            Files.writeString(promptFile, prompt);
            Path noMcp = fixture.resolve("mcp.json"); Files.writeString(noMcp, "{\"mcpServers\":{}}");
            Path evidence = fixture.resolve("evidence"); Files.createDirectories(evidence);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("prompt", prompt); values.put("prompt_file", promptFile.toString());
            values.put("schema_json", Json.write(Map.of("type", "object", "properties", Map.of("pong", Map.of("type", "string")),
                    "required", List.of("pong"), "additionalProperties", false)));
            values.put("repo_root", fixture.toString()); values.put("model", profile.model() == null ? "" : profile.model());
            values.put("effort", profile.effort() == null ? "" : profile.effort());
            values.put("run_id", "check"); values.put("task_id", "check");
            values.put("mcp_config", noMcp.toString()); values.put("evidence_dir", evidence.toString());
            List<String> command = new ArrayList<>();
            command.add(DirectCliExecutor.resolveExecutable(profile.command(), fixture));
            for (String argument : profile.args()) command.add(PromptRenderer.render(argument, values, "check args"));
            if (Boolean.FALSE.equals(DirectCliExecutor.deliverabilityCheck(command).get("deliverable")))
                return failure("check_prompt_undeliverable");
            ProcessRunner.Result response;
            try {
                response = clean.runVendor(command, fixture, limit(profile, settings), 1024 * 1024,
                        "stdin".equals(profile.promptDelivery()) ? prompt : null);
            } catch (ProcessRunner.CaptureException unsettled) {
                return Map.of("ok", false, "code", unsettled.settled() ? "check_capture_failed" : "check_process_unsettled",
                        "vendor_turn_started", true, "cost_status", "unknown");
            } catch (IOException unavailable) { return failure("check_command_unavailable"); }
            Map<String, Object> answer = DirectCliExecutor.extractJsonlAgentMessage(response.stdout());
            if (answer == null) {
                try { answer = DirectCliExecutor.unwrap(Json.parseObject(response.stdout().strip())); }
                catch (RuntimeException invalid) { /* no assistant JSON */ }
            }
            boolean pong = response.ok() && !response.stdoutTruncated() && !response.stderrTruncated()
                    && !cliProtocolFailed(response.stdout()) && answer != null && nonce.equals(answer.get("pong"));
            String code = pong ? "pong" : response.timedOut() ? "check_timeout" : cliFailure(response);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", pong); result.put("code", code); result.put("vendor_turn_started", true);
            result.put("identity", "configured_cli_launch_not_provider_attestation");
            result.put("cost_status", "unknown");
            Map<String, Object> telemetry = new LinkedHashMap<>();
            try { DirectCliExecutor.collectTelemetry(Json.parseObject(response.stdout().strip()), telemetry); }
            catch (RuntimeException ignored) { }
            for (String line : response.stdout().split("\\R")) {
                try { DirectCliExecutor.collectTelemetry(Json.parseObject(line.strip()), telemetry); }
                catch (RuntimeException ignored) { }
            }
            if (telemetry.get("cost_usd") instanceof Number cost && Double.isFinite(cost.doubleValue()) && cost.doubleValue() >= 0) {
                result.put("cost_usd", cost);
                result.put("cost_status", "reported"); result.put("cost_source", "cli_envelope");
            }
            if (telemetry.get("tokens") instanceof Map<?, ?> reported) {
                Map<String, Object> tokens = new LinkedHashMap<>();
                for (String key : List.of("input", "output", "total"))
                    if (reported.get(key) instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0)
                        tokens.put(key, number.longValue());
                if (!tokens.isEmpty()) result.put("tokens", tokens);
            }
            return result;
        } finally {
            // This is our freshly allocated fixture, never a user-selected project or config directory.
            try (var entries = Files.walk(fixture)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private ProcessRunner cleanProcesses(UserConfig user) {
        Set<String> names = new LinkedHashSet<>();
        user.connections().values().stream().map(Connection::apiKeyEnv).filter(java.util.Objects::nonNull).forEach(names::add);
        user.profiles().values().stream().map(Profile::apiKeyEnv).filter(java.util.Objects::nonNull).forEach(names::add);
        return processes.withoutEnvironmentAndOverrides(names);
    }

    private static String cliFailure(ProcessRunner.Result result) {
        String text = (result.stdout() + "\n" + result.stderr()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("not logged in") || text.contains("unauthorized") || text.contains("authentication")
                || text.contains("login") || text.contains("sign in") || text.contains("log in")
                || text.contains("not authenticated") || text.contains("token expired") || text.contains("session expired")) return "check_auth_required";
        if (text.contains("usage limit") || text.contains("quota") || text.contains("credit balance")) return "check_quota_exhausted";
        if (result.outputLimitExceeded() || result.stdoutTruncated()) return "check_output_limit";
        return result.exitCode() == 0 ? "check_no_pong" : "check_command_failed";
    }

    private static boolean cliProtocolFailed(String stdout) {
        for (String line : stdout.split("\\R")) {
            try {
                Map<String, Object> event = Json.parseObject(line.strip());
                if ("error".equals(event.get("type")) || "turn.failed".equals(event.get("type"))
                        || Boolean.TRUE.equals(event.get("is_error")) || event.get("error") != null) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private static String prompt(String nonce) {
        return "Warden availability check. Do not use tools, read files, edit files, or explain. "
                + "Respond with exactly one JSON object containing one field named pong whose string value is " + nonce + ".";
    }

    private static Duration limit(Profile profile, CheckSettings settings) {
        return Duration.ofSeconds(Math.min(settings.timeoutSeconds(), profile.wallClockMinutes() * 60));
    }

    public static Map<String, Object> failure(String code) { return Map.of("ok", false, "code", code); }
    public static String configuration(Profile profile, UserConfig user) throws Exception {
        String file = Files.isRegularFile(user.home().resolve("profiles").resolve(profile.name() + ".yaml"))
                ? Files.readString(user.home().resolve("profiles").resolve(profile.name() + ".yaml")) : "";
        String value = file + "\n" + profile.model() + "\n" + profile.effort() + "\n" + profile.args()
                + "\n" + (profile.apiConnection() == null ? "" : profile.apiConnection().fingerprint());
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static String next(String code) {
        if ("pong".equals(code)) return "Available at check time; role and coding verification are separate.";
        if (code.contains("auth") || code.contains("key") || code.contains("401"))
            return "Sign in with this vendor CLI, or replace the API credential in Warden settings, then run warden check again.";
        if (code.contains("quota") || code.contains("credit")) return "Restore the subscription quota or API balance, then check again.";
        if (code.contains("model")) return "Check the exact model ID and its availability on this connection.";
        if ("check_process_unsettled".equals(code)) return "Run warden recover before retrying; the diagnostic process could not be confirmed stopped.";
        return "Check the configured program, connection and network, then run warden check again. No role was dispatched.";
    }

    public static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".check-", ".tmp");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static synchronized void saveStates(Path directory, List<Map<String, Object>> rows) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve(".states.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE); var lock = channel.lock()) {
            Path path = directory.resolve("states.json");
            Map<String, Object> states = new LinkedHashMap<>();
            if (Files.isRegularFile(path) && Files.size(path) <= 1024 * 1024) {
                try { states.putAll(Json.parseObject(Files.readString(path))); }
                catch (RuntimeException invalid) { /* Replace an unreadable historical projection. */ }
            }
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.getOrDefault("source_profile", row.get("profile")));
                states.remove(name); states.put(name, row);
            }
            while (states.size() > 256) states.remove(states.keySet().iterator().next());
            atomicWrite(path, Json.write(states));
        }
    }
}
