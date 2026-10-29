package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * `~/.warden/profiles/<name>.yaml` — one vendor filling one role.
 *
 * Lives in the user's home, not the repository: which model reviews your code is a property
 * of your subscriptions, and putting it in a project's git history serves nobody else.
 *
 * `readOnly` is a declaration of intent, never a guarantee. The guarantee comes from the
 * worktree fingerprint taken around the run. Two vendor permission flags were tried and both
 * silently ended the run at the first tool call; a flag is a claim, a check is a fact.
 *
 * `command` is the executable for the runners that start one, and is null for `runner: local`
 * and for `harness: opencode`, which the adapter launches itself. Anything that reaches for
 * it must know which runner it is holding.
 *
 * `harness` names an external coding CLI. It is not a verified implementer: routing stays
 * closed until a real tool loop proves the contract.
 */
public record Profile(
        String name,
        String role,
        String vendor,
        String model,
        String effort,
        String command,
        List<String> args,
        boolean readOnly,
        long wallClockMinutes,
        Double maxCostUsd,
        String promptTemplate,
        String jsonSchema,
        boolean enforceSchema,
        List<String> requiredArtifactFields,
        List<String> quotaSignatures,
        String promptDelivery,
        String attachmentFlag,
        VisionCapability vision,
        String runner,
        String endpoint,
        String apiKeyEnv,
        String verificationProbe,
        List<String> verificationChecks,
        boolean verified,
        String mcpConfig,
        String verificationExpect,
        String harness,
        ApiSettings apiSettings,
        CliService cliService) {

    /**
     * The service and account a CLI profile authenticates against. Null for legacy files that
     * predate it; {@link ServiceBinding} decides whether such a profile can be inferred or needs
     * an explicit mapping. Never derived from the model developer.
     */
    public record CliService(String service, String account) {}

    /** Source compatibility for callers written before service bindings. */
    public Profile(String name, String role, String vendor, String model, String effort,
                   String command, List<String> args, boolean readOnly, long wallClockMinutes,
                   Double maxCostUsd, String promptTemplate, String jsonSchema, boolean enforceSchema,
                   List<String> requiredArtifactFields, List<String> quotaSignatures,
                   String promptDelivery, String attachmentFlag, VisionCapability vision,
                   String runner, String endpoint, String apiKeyEnv, String verificationProbe,
                   List<String> verificationChecks, boolean verified, String mcpConfig,
                   String verificationExpect, String harness, ApiSettings apiSettings) {
        this(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, mcpConfig, verificationExpect, harness, apiSettings, null);
    }

    /** Connection metadata contains references only; never a credential value. */
    public record ApiSettings(String connection, String modelVendor, int maxOutputTokens,
                              String verificationFingerprint, Connection resolved, java.nio.file.Path home,
                              java.nio.file.Path harnessExecutable, Long declaredWallClockMinutes,
                              Integer maxRequests) {
        public ApiSettings(String connection, String modelVendor, int maxOutputTokens,
                           String verificationFingerprint, Connection resolved, java.nio.file.Path home,
                           java.nio.file.Path harnessExecutable, Long declaredWallClockMinutes) {
            this(connection, modelVendor, maxOutputTokens, verificationFingerprint, resolved, home,
                    harnessExecutable, declaredWallClockMinutes, null);
        }
        public ApiSettings(String connection, String modelVendor, int maxOutputTokens,
                           String verificationFingerprint, Connection resolved, java.nio.file.Path home,
                           java.nio.file.Path harnessExecutable) {
            this(connection, modelVendor, maxOutputTokens, verificationFingerprint, resolved, home, harnessExecutable, null);
        }
        public ApiSettings(String connection, String modelVendor, int maxOutputTokens,
                           String verificationFingerprint, Connection resolved, java.nio.file.Path home) {
            this(connection, modelVendor, maxOutputTokens, verificationFingerprint, resolved, home, null, null);
        }
    }

    /** Source compatibility for existing CLI/local callers. */
    public Profile(String name, String role, String vendor, String model, String effort,
                   String command, List<String> args, boolean readOnly, long wallClockMinutes,
                   Double maxCostUsd, String promptTemplate, String jsonSchema, boolean enforceSchema,
                   List<String> requiredArtifactFields, List<String> quotaSignatures,
                   String promptDelivery, String attachmentFlag, VisionCapability vision,
                   String runner, String endpoint, String apiKeyEnv, String verificationProbe,
                   List<String> verificationChecks, boolean verified, String mcpConfig,
                   String verificationExpect) {
        this(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, mcpConfig, verificationExpect, null, null);
    }

    public String connectionRef() { return apiSettings == null ? null : apiSettings.connection(); }
    public String modelVendor() { return apiSettings == null ? vendor : apiSettings.modelVendor(); }
    public int maxOutputTokens() { return apiSettings == null ? 2048 : apiSettings.maxOutputTokens(); }
    public Connection apiConnection() { return apiSettings == null ? null : apiSettings.resolved(); }
    /** Model turns one OpenCode dispatch may take through the relay. */
    public int maxRequests() {
        return apiSettings == null || apiSettings.maxRequests() == null
                ? CodingAttestation.MAX_REQUESTS : apiSettings.maxRequests();
    }
    public java.nio.file.Path harnessExecutable() { return apiSettings == null ? null : apiSettings.harnessExecutable(); }
    /** Configuration identity, separate from a tighter deadline for one dispatch. */
    public long declaredWallClockMinutes() {
        return apiSettings == null || apiSettings.declaredWallClockMinutes() == null
                ? wallClockMinutes : apiSettings.declaredWallClockMinutes();
    }
    @Override public boolean verified() {
        // A hand-written stamp cannot make a coding harness eligible. Tool-loop proof is separate.
        if (harness != null) return verified && apiSettings != null
                && java.util.Objects.equals(apiSettings.verificationFingerprint(), apiFingerprint())
                && CodingAttestation.valid(this);
        return verified && (apiSettings == null || (apiConnection() != null
                && java.util.Objects.equals(apiSettings.verificationFingerprint(), apiFingerprint())));
    }

    /** An explicit native probe may test a stale or absent stamp without authorizing routing. */
    public Profile forApiProbe() {
        if (apiConnection() == null) throw new IllegalArgumentException("API probe requires a bound connection");
        ApiSettings probe = new ApiSettings(connectionRef(), modelVendor(), maxOutputTokens(), null,
                apiConnection(), apiSettings.home(), harnessExecutable(), declaredWallClockMinutes(),
                apiSettings.maxRequests());
        return new Profile(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, false, mcpConfig, verificationExpect, harness, probe, cliService);
    }

    /** Effective identity for independence; the legacy label remains in historical evidence. */
    public static String canonicalVendor(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "codex", "openai" -> "openai";
            case "claude", "anthropic" -> "anthropic";
            case "grok", "xai", "x-ai" -> "x-ai";
            case "gemini", "google" -> "google";
            case "zai", "zai-org", "z-ai" -> "z-ai";
            case "deepseek-ai", "deepseek" -> "deepseek";
            case "minimaxai", "minimax" -> "minimax";
            default -> value;
        };
    }

    public String independenceVendor() { return canonicalVendor(modelVendor()); }

    /** Bind a snapshot of the user's connection before routing, not a secret. */
    public Profile withConnection(Connection connection) {
        return withConnection(connection, java.nio.file.Path.of("."));
    }

    public Profile withConnection(Connection connection, java.nio.file.Path home) {
        if (apiSettings == null || (!"api".equals(runner) && harness == null)
                || !connection.id().equals(connectionRef())) {
            throw new IllegalArgumentException("connection does not match API profile");
        }
        // The HTTP adapter posts to chat/completions. A harness needs the API root instead.
        String boundEndpoint = harness == null ? connection.endpoint() : connection.baseUrl();
        ApiSettings settings = new ApiSettings(connectionRef(), modelVendor(), maxOutputTokens(),
                apiSettings.verificationFingerprint(), connection, home.toAbsolutePath().normalize(), harnessExecutable(), declaredWallClockMinutes(),
                apiSettings.maxRequests());
        Profile bound = new Profile(name, role, vendor, model, effort, command, args, readOnly,
                wallClockMinutes, maxCostUsd, promptTemplate, jsonSchema, enforceSchema,
                requiredArtifactFields, quotaSignatures, promptDelivery, attachmentFlag, vision,
                runner, boundEndpoint, connection.apiKeyEnv(), verificationProbe,
                verificationChecks, verified, mcpConfig, verificationExpect, harness, settings, cliService);
        boolean checked = verified
                && bound.apiFingerprint().equals(settings.verificationFingerprint());
        return new Profile(name, role, vendor, model, effort, command, args, readOnly,
                wallClockMinutes, maxCostUsd, promptTemplate, jsonSchema, enforceSchema,
                requiredArtifactFields, quotaSignatures, promptDelivery, attachmentFlag, vision,
                runner, boundEndpoint, connection.apiKeyEnv(), verificationProbe,
                verificationChecks, checked, mcpConfig, verificationExpect, harness, settings, cliService);
    }

    /** Changes to model, endpoint, adapter or output bound invalidate API verification. */
    public String apiFingerprint() {
        if (apiConnection() == null) return null;
        String contract = String.join("\n", harness == null ? "api-chat-v1" : "harness-" + harness + "-v1",
                "api-context-v1", apiConnection().fingerprint(),
                role, model, independenceVendor(), Integer.toString(maxOutputTokens()),
                String.valueOf(promptTemplate), String.valueOf(jsonSchema),
                Boolean.toString(enforceSchema), requiredArtifactFields.toString(),
                apiResourceDigest(promptTemplate), apiResourceDigest(jsonSchema));
        if (harness != null) {
            // Tool policy is part of the contract. A read-only change must drop an old stamp.
            contract = String.join("\n", contract, harness, Boolean.toString(readOnly), "opencode-tools-v1");
            if (harnessExecutable() != null) {
                contract = String.join("\n", contract, CodingAttestation.ADAPTER,
                        harnessExecutable().toAbsolutePath().normalize().toString(),
                        Long.toString(declaredWallClockMinutes()), Integer.toString(CodingAttestation.requestLimit(this)));
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(contract.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String apiResourceDigest(String reference) {
        if (reference == null) return "none";
        java.nio.file.Path path = java.nio.file.Path.of(reference);
        if (!path.isAbsolute()) path = apiSettings.home().resolve(path);
        try { return dev.warden.git.GitRepository.sha256(path); }
        catch (java.io.IOException unavailable) { return "unavailable"; }
    }

    /** A profile with an MCP configuration and no expected probe answer. */
    public Profile(String name, String role, String vendor, String model, String effort,
                   String command, List<String> args, boolean readOnly, long wallClockMinutes,
                   Double maxCostUsd, String promptTemplate, String jsonSchema, boolean enforceSchema,
                   List<String> requiredArtifactFields, List<String> quotaSignatures,
                   String promptDelivery, String attachmentFlag, VisionCapability vision,
                   String runner, String endpoint, String apiKeyEnv, String verificationProbe,
                   List<String> verificationChecks, boolean verified, String mcpConfig) {
        this(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, mcpConfig, null);
    }

    /** Every earlier caller: a profile with no MCP configuration. */
    public Profile(String name, String role, String vendor, String model, String effort,
                   String command, List<String> args, boolean readOnly, long wallClockMinutes,
                   Double maxCostUsd, String promptTemplate, String jsonSchema, boolean enforceSchema,
                   List<String> requiredArtifactFields, List<String> quotaSignatures,
                   String promptDelivery, String attachmentFlag, VisionCapability vision,
                   String runner, String endpoint, String apiKeyEnv, String verificationProbe,
                   List<String> verificationChecks, boolean verified) {
        this(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, null);
    }

    /** The same profile with its MCP configuration resolved to an absolute path for one dispatch. */
    public Profile withMcpConfig(String resolved) {
        return new Profile(name, role, vendor, model, effort, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, resolved, verificationExpect, harness, apiSettings, cliService);
    }

    /** Effort for one dispatch. Does not rewrite the home file or drop {@code verified_on}. */
    public Profile withEffort(String next) {
        return new Profile(name, role, vendor, model, next, command, args, readOnly, wallClockMinutes,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, mcpConfig, verificationExpect, harness, apiSettings, cliService);
    }

    /**
     * Host this profile as an Orca worker for one dispatch so the Agent Dashboard can show it.
     *
     * Only ever called on a profile with nothing that could be lost on the way — no args, no
     * MCP configuration, no vision delivery — because Orca's {@code worker-start} forwards
     * agent, model and effort and not one thing more. {@link RunOverride} is where
     * that is decided, and it sends everything else to the operator's declared
     * {@code runner: orca} twin or refuses before a vendor is paid. Read-only is still the
     * worktree fingerprint's guarantee, as on every other channel.
     */
    public Profile hostedOnOrca() {
        if (harness != null) throw new IllegalArgumentException("harness profiles cannot be hosted on Orca");
        if ("api".equals(runner)) throw new IllegalArgumentException("API connections cannot be hosted on Orca");
        if ("orca".equals(runner)) return this;
        return new Profile(name, role, vendor, model, effort, command, List.of(), readOnly,
                wallClockMinutes, maxCostUsd, promptTemplate, jsonSchema, enforceSchema,
                requiredArtifactFields, quotaSignatures, "workspace_file", attachmentFlag,
                vision, "orca", endpoint, apiKeyEnv, verificationProbe, verificationChecks,
                verified, mcpConfig, verificationExpect, null, null, cliService);
    }

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "profile", "role", "vendor", "model", "effort", "command", "args", "read_only",
            "limits", "prompt_template", "json_schema", "enforce_schema", "artifact", "quota",
            "prompt_delivery", "attachments", "capabilities", "runner", "endpoint", "api_key_env",
            "verification", "notes", "mcp", "connection", "model_vendor", "harness", "harness_executable",
            "service", "account");
    private static final Set<String> MCP = Set.of("config", "note");
    private static final Set<String> LIMITS = Set.of("wall_clock_minutes", "max_cost_usd", "max_output_tokens",
            "max_requests");
    private static final Set<String> ARTIFACT = Set.of("required_fields");
    private static final Set<String> QUOTA = Set.of("signatures");
    private static final Set<String> ATTACHMENTS = Set.of("flag");
    private static final Set<String> CAPABILITIES = Set.of("vision");
    private static final Set<String> VISION = Set.of("delivery", "verification", "acquires");
    private static final Set<String> VERIFICATION = Set.of(
            "verified_on", "status", "probe", "what_to_check", "note", "expect", "connection_fingerprint");

    public static final Set<String> ROLES = Set.of(
            "implementer", "reviewer", "architect", "visual_qa", "planner", "plan_reviewer");

    /**
     * How the prompt reaches the vendor.
     *
     * `argv` renders it into an argument via `{{prompt}}`, or passes a path via
     * `{{prompt_file}}`. `stdin` writes it to the child's standard input, which is the only
     * channel that survives a Windows `.cmd` shim: cmd.exe truncates a multi-line argument at
     * its first newline and silently discards every argument after it, so an inline prompt
     * reaches an npm-installed vendor as one line with the flags stripped off.
     */
    public static final Set<String> PROMPT_DELIVERY = Set.of("argv", "stdin", "workspace_file");

    /**
     * How the role is launched. {@code direct} is a vendor CLI in this process. {@code orca}
     * is a supervised worker inside the current Orca worktree — Warden never creates that
     * worktree. {@code local} POSTs an OpenAI-compatible chat completion to {@code endpoint};
     * {@code api_key_env} names an environment variable holding a bearer token, read at
     * dispatch and never written to evidence.
     */
    public static final Set<String> RUNNERS = Set.of("direct", "orca", "local", "api");
    public static final Set<String> VISION_DELIVERIES = Set.of("cli_attachment", "workspace_file");
    public static final Set<String> CAPABILITY_VERIFICATION = Set.of("required");

    /**
     * A declared and human-probed way for the model to inspect pixels, not just filenames.
     *
     * @param acquires the role takes its own screenshots with its own tools — a browser or
     *                 game-engine MCP server, a desktop driver — and lists the files it wrote
     *                 in {@code screenshots_taken}. Warden then verifies that each listed file
     *                 exists inside the run's evidence directory and hashes it; it does not
     *                 verify what the file is a picture of. A workflow stage that declares
     *                 {@code evidence: agent} refuses a profile without this.
     */
    public record VisionCapability(String delivery, boolean verificationRequired, boolean acquires) {
        public VisionCapability(String delivery, boolean verificationRequired) {
            this(delivery, verificationRequired, false);
        }
    }

    /**
     * A profile-level verification stamp proves vision only when the profile declared that its
     * vision path was part of the probe. This keeps the existing verify/stamp workflow usable:
     * an unverified profile must still parse before {@code warden profiles --verify} can run it.
     */
    /**
     * The same profile with a shorter wall clock, for one call that a chain deadline bounds.
     * Nothing else moves: the verdict the call reaches is judged under the same contract.
     */
    public Profile withWallClockMinutes(long minutes) {
        ApiSettings scoped = apiSettings;
        if (scoped != null && scoped.declaredWallClockMinutes() == null) {
            scoped = new ApiSettings(scoped.connection(), scoped.modelVendor(), scoped.maxOutputTokens(),
                    scoped.verificationFingerprint(), scoped.resolved(), scoped.home(), scoped.harnessExecutable(), wallClockMinutes,
                    scoped.maxRequests());
        }
        // A per-dispatch override can only tighten a harness's verified declaration.
        long bounded = harness == null ? minutes : Math.min(minutes, Math.min(wallClockMinutes, declaredWallClockMinutes()));
        return new Profile(name, role, vendor, model, effort, command, args, readOnly, bounded,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredArtifactFields,
                quotaSignatures, promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                verificationProbe, verificationChecks, verified, mcpConfig, verificationExpect, harness, scoped, cliService);
    }

    public boolean hasVerifiedVision() {
        return vision != null && vision.verificationRequired() && verified;
    }

    public static Profile parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        String name = root.requireString("profile");
        String role = root.requireEnum("role", ROLES, null);
        if (role == null) root.collector().add("role is required and must be one of " + ROLES);
        String modelVendor = root.optString("model_vendor", null);
        String vendor = root.optString("vendor", modelVendor);
        if (vendor == null) root.collector().add("vendor or model_vendor is required");

        // Read before `command`, because it decides whether there is a command at all.
        String runner = root.requireEnum("runner", RUNNERS, "direct");
        String harness = root.optString("harness", null);
        if (harness != null && harness.isBlank()) harness = null;
        if (harness != null && !"opencode".equals(harness)) {
            root.collector().add("harness must be opencode");
        }
        boolean opencode = "opencode".equals(harness);
        String executableReference = root.optString("harness_executable", null);
        java.nio.file.Path harnessExecutable = null;
        if (executableReference != null) {
            try {
                harnessExecutable = java.nio.file.Path.of(executableReference);
                if (!opencode || !harnessExecutable.isAbsolute()) {
                    root.collector().add("harness_executable requires harness: opencode and an absolute executable path");
                } else harnessExecutable = harnessExecutable.normalize();
            } catch (java.nio.file.InvalidPathException invalid) {
                root.collector().add("harness_executable must be an absolute executable path");
            }
        }
        if (opencode && !"direct".equals(runner)) {
            root.collector().add("harness: opencode requires runner: direct");
        }

        // `local` starts no process, so there is no executable to name. Requiring one would
        // make every local profile invent a dummy — and the moment the `runner` line is
        // deleted or misspelt, Warden would try to spawn that dummy as a Direct CLI vendor.
        // A harness names no command either: the adapter owns that process.
        boolean http = "local".equals(runner) || "api".equals(runner);
        String command = http || opencode
                ? root.optString("command", null)
                : root.requireString("command");
        List<String> args = root.optStringList("args", List.of());
        String model = root.optString("model", null);
        String effort = root.optString("effort", null);
        if (effort != null) {
            if (!effort.matches("[a-z][a-z0-9_-]*")) {
                root.collector().add("effort must be a provider effort level, not a command or blank value");
            }
            if ("local".equals(runner)) {
                root.collector().add("runner: " + runner + " does not support effort; it cannot silently drop it");
            }
            if ("orca".equals(runner) && (model == null || model.isBlank())) {
                root.collector().add("runner: orca effort requires an explicit model");
            }
            if ("direct".equals(runner) && !opencode && args.stream().noneMatch(a -> a.contains("{{effort}}"))) {
                root.collector().add("runner: direct effort requires {{effort}} in args; replace the literal effort value");
            }
        } else if (args.stream().anyMatch(a -> a.contains("{{effort}}"))) {
            root.collector().add("{{effort}} in args requires effort");
        }
        if ("orca".equals(runner) && !args.isEmpty()) {
            root.collector().add("runner: orca cannot forward args (including tool grants, sandbox and turn limits); "
                    + "keep runner: direct for those constraints instead of dropping them");
        }
        // The MCP servers a role may reach — a browser, a game engine, a desktop driver — are
        // declared by the operator in the vendor's own configuration file and handed to the
        // vendor's own flag through {{mcp_config}}. Warden does not read the file's format; it
        // checks the file exists at dispatch and records its digest as a term of the reading,
        // because a reviewer with different tools is a different reviewer.
        Values mcp = root.optMap("mcp").rejectUnknownKeys(MCP);
        String mcpConfig = mcp.optString("config", null);
        if (mcpConfig != null && mcpConfig.isBlank()) mcpConfig = null;
        boolean usesMcp = args.stream().anyMatch(a -> a.contains("{{mcp_config}}"));
        if (usesMcp && mcpConfig == null) {
            root.collector().add("{{mcp_config}} in args requires mcp.config naming the configuration file");
        }
        if (mcpConfig != null && !usesMcp && "direct".equals(runner) && !opencode) {
            root.collector().add("mcp.config is declared but no args entry passes {{mcp_config}} to the "
                    + "vendor, so the servers would never be reached");
        }

        // Default true: a role is read-only unless it explicitly says otherwise, so a typo
        // in this key cannot silently grant write access.
        boolean readOnly = root.optBool("read_only", true);

        Values limits = root.optMap("limits").rejectUnknownKeys(LIMITS);
        long wallClock = limits.optInt("wall_clock_minutes", 20, 1, 240);
        // A proven upper bound on what one call of this profile can cost, for tasks that
        // declare a strict money cap. Absent means unknown, never zero: a vendor that reports
        // no price cannot honestly declare one, and a strict cap then refuses the roster.
        Double maxCostUsd = limits.has("max_cost_usd")
                ? limits.optDouble("max_cost_usd", 1.0, 0.0, 10000.0) : null;
        int maxOutputTokens = (int) limits.optInt("max_output_tokens", 2048, 1, 16384);
        // An omitted cap is not a promise of 2048. API keeps its historical default.
        if (opencode && !limits.has("max_output_tokens")) maxOutputTokens = 0;
        // Relay turns for one OpenCode dispatch. Only a coding harness has turns to count.
        Integer maxRequests = null;
        if (limits.has("max_requests")) {
            if (!opencode) root.collector().add("limits.max_requests applies only to harness: opencode");
            else maxRequests = (int) limits.optInt("max_requests", CodingAttestation.MAX_REQUESTS,
                    1, CodingAttestation.REQUEST_CEILING);
        }
        if (harnessExecutable != null && maxOutputTokens == 0) {
            root.collector().add("harness_executable requires an explicit limits.max_output_tokens");
        }

        String promptTemplate = root.optString("prompt_template", null);
        String jsonSchema = root.optString("json_schema", null);
        boolean enforceSchema = root.optBool("enforce_schema", true);

        Values artifact = root.optMap("artifact").rejectUnknownKeys(ARTIFACT);
        List<String> requiredFields = artifact.optStringList("required_fields", List.of());

        // Phrases this vendor uses when the subscription is spent. Added to the built-in
        // defaults, never substituted for them: a vendor-specific wording learned here must
        // not silently disable the wordings learned from another vendor.
        Values quota = root.optMap("quota").rejectUnknownKeys(QUOTA);
        List<String> quotaSignatures = quota.optStringList("signatures", List.of());

        String promptDelivery = root.requireEnum("prompt_delivery", PROMPT_DELIVERY,
                "orca".equals(runner) ? "workspace_file" : "argv");
        if ("workspace_file".equals(promptDelivery) && !"orca".equals(runner)) {
            root.collector().add("prompt_delivery: workspace_file requires runner: orca");
        }
        if ("orca".equals(runner) && !"workspace_file".equals(promptDelivery)) {
            root.collector().add("runner: orca requires prompt_delivery: workspace_file; stdin/argv are not forwarded");
        }
        if (opencode && !"argv".equals(promptDelivery)) {
            root.collector().add("harness: opencode reads the prompt from the dispatch file; prompt_delivery cannot change that");
        }

        // How a file the role must LOOK at reaches the vendor. Screenshots are the case that
        // forced this: a visual reviewer that only receives paths is reading a filename, not
        // an image. `flag` is repeated once per file (codex: `-i shot.png -i after.png`).
        // A vendor without such a flag still gets the paths in its prompt and can open them
        // with its own read tool, so the field is optional rather than required.
        Values attachments = root.optMap("attachments").rejectUnknownKeys(ATTACHMENTS);
        String attachmentFlag = attachments.optString("flag", null);
        String endpoint = root.optString("endpoint", null);
        String apiKeyEnv = root.optString("api_key_env", null);
        String connection = root.optString("connection", null);
        if ("api".equals(runner)) {
            if (connection == null || !connection.matches("[a-z][a-z0-9_-]{0,63}")) {
                root.collector().add("runner: api requires a valid connection id");
            }
            if (model == null) root.collector().add("runner: api requires an explicit model");
            if (modelVendor == null || !modelVendor.matches("[a-z][a-z0-9_-]{0,63}")
                    || Set.of("openrouter", "gonkarouter", "joingonka", "codex", "claude", "opencode").contains(modelVendor)) {
                root.collector().add("runner: api requires model_vendor naming the model developer");
            }
            if (!readOnly || "implementer".equals(role)) {
                root.collector().add("runner: api supports read-only text roles only; coding tools are not connected");
            }
            if (endpoint != null || apiKeyEnv != null) {
                root.collector().add("runner: api uses connection; endpoint/api_key_env belong to runner: local");
            }
            if (command != null || !args.isEmpty() || mcpConfig != null || attachmentFlag != null) {
                root.collector().add("runner: api cannot execute a command, args, MCP or attachments");
            }
            if (maxCostUsd != null) root.collector().add("runner: api cannot prove a strict max_cost_usd bound");
            // Legacy vendor is used throughout writer accounting; API routes must name the developer.
            if (modelVendor != null && vendor != null
                    && !canonicalVendor(modelVendor).equals(canonicalVendor(vendor))) {
                root.collector().add("API vendor must match model_vendor, not the router");
            }
            if (model != null && model.contains("/") && modelVendor != null) {
                String prefix = canonicalVendor(model.substring(0, model.indexOf('/')));
                if (Set.of("openai", "anthropic", "x-ai", "google", "deepseek", "z-ai", "minimax").contains(prefix)
                        && !prefix.equals(canonicalVendor(modelVendor))) {
                    root.collector().add("model_vendor does not match the known model id prefix");
                }
            }
            vendor = canonicalVendor(modelVendor);
        } else if (opencode) {
            if (connection == null || !connection.matches("[a-z][a-z0-9_-]{0,63}")) {
                root.collector().add("harness: opencode requires a valid connection id");
            }
            if (model == null) root.collector().add("harness: opencode requires an explicit model");
            if (modelVendor == null || !modelVendor.matches("[a-z][a-z0-9_-]{0,63}")
                    || Set.of("openrouter", "gonkarouter", "joingonka", "codex", "claude", "opencode").contains(modelVendor)) {
                root.collector().add("harness: opencode requires model_vendor naming the model developer");
            }
            if (command != null || !args.isEmpty() || mcpConfig != null) {
                root.collector().add("harness: opencode cannot take command, args or MCP; the adapter owns the process");
            }
            if (endpoint != null || apiKeyEnv != null) {
                root.collector().add("harness: opencode uses connection; endpoint/api_key_env belong to runner: local");
            }
            if (maxCostUsd != null) {
                root.collector().add("harness: opencode cannot prove a strict max_cost_usd bound");
            }
            if (!"implementer".equals(role) && !readOnly) {
                root.collector().add("harness: opencode allows writes only for role implementer");
            }
            if (modelVendor != null && vendor != null
                    && !canonicalVendor(modelVendor).equals(canonicalVendor(vendor))) {
                root.collector().add("harness vendor must match model_vendor, not the router");
            }
            if (model != null && model.contains("/") && modelVendor != null) {
                String prefix = canonicalVendor(model.substring(0, model.indexOf('/')));
                if (Set.of("openai", "anthropic", "x-ai", "google", "deepseek", "z-ai", "minimax").contains(prefix)
                        && !prefix.equals(canonicalVendor(modelVendor))) {
                    root.collector().add("model_vendor does not match the known model id prefix");
                }
            }
            if (modelVendor != null) vendor = canonicalVendor(modelVendor);
        } else if (connection != null || modelVendor != null || limits.has("max_output_tokens")) {
            root.collector().add("connection, model_vendor and max_output_tokens require runner: api or harness: opencode");
        }
        String service = root.optString("service", null);
        String account = root.optString("account", null);
        CliService cliService = null;
        if (service != null || account != null) {
            if ("api".equals(runner) || opencode || "local".equals(runner)) {
                root.collector().add("service and account apply to CLI profiles; API and OpenCode use connection");
            } else if (service == null) {
                root.collector().add("account requires service");
            } else if (!service.matches("[a-z][a-z0-9-]{0,31}") || !service.equals(canonicalVendor(service))
                    || ServiceBinding.CLIENTS.contains(service)) {
                root.collector().add("service must name the authenticating service (openai, x-ai, anthropic, google, "
                        + "antigravity, ...), not the CLI client or an alias");
            } else if (account != null && !account.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                root.collector().add("account must be a short label such as default or work");
            } else {
                cliService = new CliService(service, account == null ? "default" : account);
            }
        }
        if ("local".equals(runner) && endpoint == null) {
            root.collector().add("endpoint is required when runner is local");
        }
        if (endpoint != null && !absoluteHttpUrl(endpoint)) {
            root.collector().add("endpoint must be an absolute http:// or https:// URL, got '"
                    + endpoint + "'");
        }

        Values capabilities = root.optMap("capabilities").rejectUnknownKeys(CAPABILITIES);
        boolean declaresVision = capabilities.has("vision");
        VisionCapability vision = null;
        if (declaresVision) {
            Values configuredVision = capabilities.optMap("vision").rejectUnknownKeys(VISION);
            String delivery = configuredVision.requireEnum("delivery", VISION_DELIVERIES, null);
            String verificationRequirement = configuredVision.requireEnum(
                    "verification", CAPABILITY_VERIFICATION, null);
            boolean acquires = configuredVision.optBool("acquires", false);
            if (delivery == null) {
                root.collector().add("capabilities.vision.delivery is required and must be one of "
                        + VISION_DELIVERIES);
            }
            if (verificationRequirement == null) {
                root.collector().add("capabilities.vision.verification is required and must be 'required'");
            }
            if (delivery != null && verificationRequirement != null) {
                vision = new VisionCapability(delivery, true, acquires);
            }
        } else if (attachmentFlag != null && "direct".equals(runner)) {
            // Compatibility for pre-capability profiles: an already verified direct `-i`
            // profile is exactly the cli_attachment capability, just in the old spelling.
            vision = new VisionCapability("cli_attachment", true);
        }

        if ("visual_qa".equals(role) && vision == null) {
            root.collector().add("visual_qa requires capabilities.vision with delivery and verification");
        }
        if (attachmentFlag != null && (vision == null || !"cli_attachment".equals(vision.delivery()))) {
            root.collector().add("attachments.flag requires capabilities.vision.delivery: cli_attachment");
        }
        if (vision != null && "cli_attachment".equals(vision.delivery())) {
            if (!"direct".equals(runner)) {
                root.collector().add("capabilities.vision.delivery cli_attachment requires runner: direct");
            }
            if (attachmentFlag == null) {
                root.collector().add("capabilities.vision.delivery cli_attachment requires attachments.flag");
            }
        }
        if (vision != null && "orca".equals(runner) && !"workspace_file".equals(vision.delivery())) {
            root.collector().add("runner: orca supports vision only with delivery: workspace_file");
        }
        // The local adapter POSTs text. A vision claim would let visual QA pass without
        // pixels ever leaving the workspace, so refuse it here rather than at dispatch.
        if (http && declaresVision) {
            root.collector().add("runner: " + runner + " does not support capabilities.vision; "
                    + "the HTTP adapter sends only text");
        }
        if (opencode && (declaresVision || vision != null || attachmentFlag != null)) {
            root.collector().add("harness: opencode does not support vision or attachments");
        }

        Values verification = root.optMap("verification").rejectUnknownKeys(VERIFICATION);
        boolean verified = verification.has("verified_on");
        // Kept, not discarded: "why is this profile not eligible" is answered by the exact
        // command that would settle it, and `warden profiles --verify` runs that command
        // rather than making the operator retype it from a comment.
        String probe = verification.optString("probe", null);
        List<String> whatToCheck = verification.optStringList("what_to_check", List.of());
        // A substring the probe's stdout has to contain before the date is stamped. Without
        // it the stamp is exit code alone — and a headless CLI whose tool call was refused
        // exited 0 with an empty answer and was stamped verified. Measured 2026-09-18.
        String expect = verification.optString("expect", null);
        String connectionFingerprint = verification.optString("connection_fingerprint", null);
        if (expect != null && expect.isBlank()) {
            root.collector().add("verification.expect must be a non-empty string when present");
            expect = null;
        }

        root.throwIfAny();
        boolean connects = "api".equals(runner) || opencode;
        return new Profile(name, role, vendor, model, effort, command, args, readOnly, wallClock,
                maxCostUsd, promptTemplate, jsonSchema, enforceSchema, requiredFields, quotaSignatures,
                promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                probe, whatToCheck, verified, mcpConfig, expect, harness,
                connects ? new ApiSettings(connection, canonicalVendor(modelVendor),
                        maxOutputTokens, connectionFingerprint, null, null, harnessExecutable, wallClock,
                        maxRequests) : null,
                cliService);
    }

    /**
     * {@code http://} or {@code https://} with a host. A path-only value or another scheme
     * would send the POST somewhere other than an HTTP server, and fail later as an
     * unreachable endpoint — which is the wrong code for a profile the operator can fix now.
     */
    private static boolean absoluteHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }
}
