package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Explicit paid, bounded coding experiment. Never stamps a profile or changes routing policy. */
public final class CodingProbe {
    private CodingProbe() {}

    public static Map<String, Object> run(
            Path home, String id, String[] argv, CredentialResolver resolver) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 3; i < argv.length; i++) {
            String name = argv[i];
            if (!Set.of("--project", "--opencode", "--model").contains(name)
                    || i + 1 >= argv.length
                    || options.put(name, argv[++i]) != null)
                throw new IllegalArgumentException(
                        "coding-check requires --project PATH --opencode EXE --model ID");
        }
        if (options.size() != 3)
            throw new IllegalArgumentException(
                    "coding-check requires --project PATH --opencode EXE --model ID");
        Path project = Path.of(options.get("--project")).toAbsolutePath().normalize();
        Path binary = Path.of(options.get("--opencode")).toAbsolutePath().normalize();
        if (!Files.isDirectory(project) || !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException(
                    "coding probe requires an existing project and executable");
        String model = options.get("--model");
        if (!model.matches("[A-Za-z0-9._/-]{1,160}"))
            throw new IllegalArgumentException("invalid model id");
        ConnectionStore store = new ConnectionStore(home);
        var snapshot = store.versioned(id);
        Connection connection = snapshot.connection();
        if (!connection.enabled()) throw new IllegalArgumentException("connection is disabled");
        String secret = resolver.resolve(connection);
        // Always create a new fixture, never rewrite a user's source or previous probe.
        Path fixture =
                Files.createDirectory(
                        project.resolve(
                                "warden-coding-probe-"
                                        + UUID.randomUUID().toString().substring(0, 8)));
        Path state = Files.createDirectories(fixture.resolve(".probe-state"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("connection", id);
        report.put("model", model);
        report.put("fixture", fixture.toString());
        report.put("profiles_activated", false);
        report.put("strict_budget", false);
        report.put("binary_sha256", digest(binary));
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
        for (Connection registered : store.load().connections().values())
            if (registered.apiKeyEnv() != null) removals.add(registered.apiKeyEnv());
        ProcessRunner process = new ProcessRunner().withoutEnvironment(removals);
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
        try {
            var version =
                    process.withEnvironment(env)
                            .runVendor(
                                    List.of(binary.toString(), "--version"),
                                    fixture,
                                    Duration.ofSeconds(20),
                                    8192,
                                    null);
            if (!version.ok() || !"1.19.4".equals(version.stdout().strip()))
                return finish(fixture, report, false, "opencode_version_unproven", secret, null);
            report.put("opencode_version", version.stdout().strip());
            Files.writeString(
                    fixture.resolve("greeting.mjs"),
                    "export function greet(name) { return 'Hello'; }\n");
            Files.writeString(
                    fixture.resolve("greeting.test.mjs"),
                    "import assert from 'node:assert/strict';\n"
                            + "import {greet} from './greeting.mjs';\n"
                            + "assert.equal(greet('Gonka'), 'Hello, Gonka!');\n"
                            + "assert.equal(greet('Warden'), 'Hello, Warden!');\n"
                            + "console.log('WARDEN_CODING_GATE_OK');\n");
            String testDigest = digest(fixture.resolve("greeting.test.mjs"));
            var before =
                    process.runVendor(
                            List.of("node", "greeting.test.mjs"),
                            fixture,
                            Duration.ofSeconds(15),
                            8192,
                            null);
            if (before.ok()) return finish(fixture, report, false, "fixture_not_red", secret, null);
            report.put("initial_gate_failed", true);
            try (CodingRelay relay =
                    new CodingRelay(connection, model, secret, Duration.ofMinutes(4))) {
                env.put("WARDEN_PROBE_RELAY_KEY", relay.bearer());
                env.put("OPENCODE_CONFIG_CONTENT", config(relay.baseUrl(), model));
                var scoped = process.withEnvironment(env);
                var debug =
                        scoped.runVendor(
                                List.of(binary.toString(), "debug", "config", "--pure"),
                                fixture,
                                Duration.ofSeconds(25),
                                256 * 1024,
                                null);
                Files.writeString(
                        fixture.resolve("opencode-config-check.json"),
                        redact(debug.stdout(), secret, relay.bearer()));
                Files.writeString(
                        fixture.resolve("opencode-config-stderr.txt"),
                        redact(debug.stderr(), secret, relay.bearer()));
                if (!debug.ok() || !validConfig(debug.stdout(), relay.baseUrl(), model))
                    return finish(
                            fixture,
                            report,
                            false,
                            "opencode_config_unproven",
                            secret,
                            relay.bearer());
                report.put("effective_config_checked", true);
                String prompt =
                        "This is an isolated Warden coding test. You MUST use the read tool on"
                            + " greeting.mjs and greeting.test.mjs before editing. Reading via bash"
                            + " does not satisfy this probe. Fix only greeting.mjs so greet(name)"
                            + " returns Hello, NAME! using the supplied name. Do not change the"
                            + " tests. Run node greeting.test.mjs using the bash tool. Write"
                            + " artifact.json containing exactly"
                            + " {\"status\":\"done\",\"summary\":\"greeting fixed\"}. Stop when"
                            + " complete. Do not inspect .probe-state or any files outside this"
                            + " directory. Use read, edit and bash tools, not only a text answer.";
                var result =
                        scoped.runVendor(
                                List.of(
                                        binary.toString(),
                                        "run",
                                        "--pure",
                                        "--format",
                                        "json",
                                        prompt),
                                fixture,
                                Duration.ofMinutes(4),
                                256 * 1024,
                                null);
                // Close sockets before taking the synchronized evidence snapshot. A partial
                // local request must not keep the completed process waiting past its deadline.
                relay.close();
                Files.writeString(
                        fixture.resolve("opencode-events.jsonl"),
                        redact(result.stdout(), secret, relay.bearer()),
                        StandardCharsets.UTF_8);
                Files.writeString(
                        fixture.resolve("opencode-stderr.txt"),
                        redact(result.stderr(), secret, relay.bearer()),
                        StandardCharsets.UTF_8);
                report.put("process_exit", result.exitCode());
                report.put("timed_out", result.timedOut());
                report.put("relay", relay.evidence());
                if (!result.ok() || result.stdoutTruncated() || result.stderrTruncated())
                    return finish(
                            fixture,
                            report,
                            false,
                            "opencode_process_failed",
                            secret,
                            relay.bearer());
                if (!Boolean.TRUE.equals(relay.evidence().get("identity_confirmed")))
                    return finish(
                            fixture,
                            report,
                            false,
                            "served_model_unproven",
                            secret,
                            relay.bearer());
                var tools = (List<?>) relay.evidence().get("observed_tool_results");
                if (!tools.contains("read")
                        || !tools.contains("bash")
                        || Collections.disjoint(tools, List.of("edit", "write", "apply_patch")))
                    return finish(
                            fixture,
                            report,
                            false,
                            "coding_tools_unproven",
                            secret,
                            relay.bearer());
                if (!testDigest.equals(digest(fixture.resolve("greeting.test.mjs"))))
                    return finish(
                            fixture,
                            report,
                            false,
                            "fixture_tests_changed",
                            secret,
                            relay.bearer());
                var gate =
                        process.runVendor(
                                List.of("node", "greeting.test.mjs"),
                                fixture,
                                Duration.ofSeconds(15),
                                8192,
                                null);
                report.put(
                        "independent_gate_passed",
                        gate.ok() && gate.stdout().contains("WARDEN_CODING_GATE_OK"));
                Path artifact = fixture.resolve("artifact.json");
                boolean validArtifact = validArtifact(artifact);
                report.put("artifact_valid", validArtifact);
                if (!snapshot.revision().equals(store.versioned(id).revision()))
                    return finish(
                            fixture,
                            report,
                            false,
                            "connection_changed_during_probe",
                            secret,
                            relay.bearer());
                if (!report.get("binary_sha256").equals(digest(binary)))
                    return finish(
                            fixture,
                            report,
                            false,
                            "binary_changed_during_probe",
                            secret,
                            relay.bearer());
                boolean ok =
                        Boolean.TRUE.equals(report.get("independent_gate_passed")) && validArtifact;
                return finish(
                        fixture,
                        report,
                        ok,
                        ok ? "coding_probe_passed" : "coding_fixture_failed",
                        secret,
                        relay.bearer());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return finish(fixture, report, false, "coding_probe_interrupted", secret, null);
        } catch (Exception failed) {
            return finish(fixture, report, false, "coding_probe_failed", secret, null);
        }
    }

    static String config(String base, String model) {
        Map<String, Object> definition =
                Map.of("name", model, "limit", Map.of("context", 32000, "output", 2048));
        Map<String, Object> provider =
                Map.of(
                        "npm",
                        "@ai-sdk/openai-compatible",
                        "name",
                        "Warden probe",
                        "options",
                        Map.of("baseURL", base, "apiKey", "{env:WARDEN_PROBE_RELAY_KEY}"),
                        "models",
                        Map.of(model, definition));
        return Json.write(
                Map.of(
                        "model",
                        "warden/" + model,
                        "small_model",
                        "warden/" + model,
                        "enabled_providers",
                        List.of("warden"),
                        "provider",
                        Map.of("warden", provider),
                        "plugin",
                        List.of(),
                        "mcp",
                        Map.of(),
                        "permission",
                        permissions()));
    }

    private static Map<String, Object> permissions() {
        // OpenCode applies the last matching rule. Never use Map.of for this sequence.
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("*", "deny");
        for (String tool : List.of("read", "edit", "write", "apply_patch", "bash"))
            rules.put(tool, "allow");
        return rules;
    }

    static boolean validArtifact(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) >= 16384)
                return false;
            Map<String, Object> value = Json.parseObject(Files.readString(file));
            return Set.of("status", "summary").equals(value.keySet())
                    && "done".equals(value.get("status"))
                    && value.get("summary") instanceof String summary
                    && !summary.isBlank();
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    static boolean validConfig(String json, String base, String model) {
        try {
            Map<String, Object> value = Json.parseObject(json);
            if (!("warden/" + model).equals(value.get("model"))
                    || !List.of("warden").equals(value.get("enabled_providers"))) return false;
            if (!("warden/" + model).equals(value.get("small_model"))) return false;
            if (!(value.get("permission") instanceof Map<?, ?> rules)
                    || !permissions().equals(rules)
                    || !"*".equals(rules.keySet().iterator().next())) return false;
            for (String name : List.of("agent", "mode", "command"))
                if (value.get(name) instanceof Map<?, ?> entries && !entries.isEmpty())
                    return false;
            if (value.get("instructions") instanceof List<?> instructions
                    && !instructions.isEmpty()) return false;
            if (!(value.get("provider") instanceof Map<?, ?> providers)
                    || providers.size() != 1
                    || !(providers.get("warden") instanceof Map<?, ?> provider)
                    || !(provider.get("options") instanceof Map<?, ?> options)
                    || !base.equals(options.get("baseURL"))) return false;
            if (!"@ai-sdk/openai-compatible".equals(provider.get("npm"))
                    || !(provider.get("models") instanceof Map<?, ?> models)
                    || models.size() != 1
                    || !models.containsKey(model)) return false;
            if (value.get("mcp") instanceof Map<?, ?> mcp && !mcp.isEmpty()) return false;
            return !(value.get("plugin") instanceof List<?> plugins) || plugins.isEmpty();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String digest(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String redact(String text, String secret, String bearer) {
        if (secret != null) text = text.replace(secret, "[REDACTED]");
        if (bearer != null) text = text.replace(bearer, "[REDACTED]");
        return text;
    }

    private static Map<String, Object> finish(
            Path fixture,
            Map<String, Object> report,
            boolean ok,
            String code,
            String secret,
            String bearer)
            throws IOException {
        report.put("ok", ok);
        report.put("code", code);
        report.put("profile_eligible", false);
        String safe = redact(Json.write(report), secret, bearer);
        Files.writeString(fixture.resolve("receipt.json"), safe, StandardCharsets.UTF_8);
        return Json.parseObject(safe);
    }
}
