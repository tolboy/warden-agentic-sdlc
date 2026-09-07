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
 * `command` is the executable for the runners that start one, and is null for `runner: local`,
 * which starts nothing. Anything that reaches for it must know which runner it is holding.
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
        boolean verified) {

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "profile", "role", "vendor", "model", "effort", "command", "args", "read_only",
            "limits", "prompt_template", "json_schema", "enforce_schema", "artifact", "quota",
            "prompt_delivery", "attachments", "capabilities", "runner", "endpoint", "api_key_env",
            "verification", "notes");
    private static final Set<String> LIMITS = Set.of("wall_clock_minutes");
    private static final Set<String> ARTIFACT = Set.of("required_fields");
    private static final Set<String> QUOTA = Set.of("signatures");
    private static final Set<String> ATTACHMENTS = Set.of("flag");
    private static final Set<String> CAPABILITIES = Set.of("vision");
    private static final Set<String> VISION = Set.of("delivery", "verification");
    private static final Set<String> VERIFICATION = Set.of("verified_on", "status", "probe", "what_to_check", "note");

    public static final Set<String> ROLES = Set.of("implementer", "reviewer", "architect", "visual_qa");

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
    public static final Set<String> RUNNERS = Set.of("direct", "orca", "local");
    public static final Set<String> VISION_DELIVERIES = Set.of("cli_attachment", "workspace_file");
    public static final Set<String> CAPABILITY_VERIFICATION = Set.of("required");

    /** A declared and human-probed way for the model to inspect pixels, not just filenames. */
    public record VisionCapability(String delivery, boolean verificationRequired) {}

    /**
     * A profile-level verification stamp proves vision only when the profile declared that its
     * vision path was part of the probe. This keeps the existing verify/stamp workflow usable:
     * an unverified profile must still parse before {@code warden profiles --verify} can run it.
     */
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
        String vendor = root.requireString("vendor");

        // Read before `command`, because it decides whether there is a command at all.
        String runner = root.requireEnum("runner", RUNNERS, "direct");

        // `local` starts no process, so there is no executable to name. Requiring one would
        // make every local profile invent a dummy — and the moment the `runner` line is
        // deleted or misspelt, Warden would try to spawn that dummy as a Direct CLI vendor.
        String command = "local".equals(runner)
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
                root.collector().add("runner: local does not support effort; it cannot silently drop it");
            }
            if ("orca".equals(runner) && (model == null || model.isBlank())) {
                root.collector().add("runner: orca effort requires an explicit model");
            }
            if ("direct".equals(runner) && args.stream().noneMatch(a -> a.contains("{{effort}}"))) {
                root.collector().add("runner: direct effort requires {{effort}} in args; replace the literal effort value");
            }
        } else if (args.stream().anyMatch(a -> a.contains("{{effort}}"))) {
            root.collector().add("{{effort}} in args requires effort");
        }
        if ("orca".equals(runner) && !args.isEmpty()) {
            root.collector().add("runner: orca cannot forward args (including tool grants, sandbox and turn limits); "
                    + "keep runner: direct for those constraints instead of dropping them");
        }

        // Default true: a role is read-only unless it explicitly says otherwise, so a typo
        // in this key cannot silently grant write access.
        boolean readOnly = root.optBool("read_only", true);

        Values limits = root.optMap("limits").rejectUnknownKeys(LIMITS);
        long wallClock = limits.optInt("wall_clock_minutes", 20, 1, 240);

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

        // How a file the role must LOOK at reaches the vendor. Screenshots are the case that
        // forced this: a visual reviewer that only receives paths is reading a filename, not
        // an image. `flag` is repeated once per file (codex: `-i shot.png -i after.png`).
        // A vendor without such a flag still gets the paths in its prompt and can open them
        // with its own read tool, so the field is optional rather than required.
        Values attachments = root.optMap("attachments").rejectUnknownKeys(ATTACHMENTS);
        String attachmentFlag = attachments.optString("flag", null);
        String endpoint = root.optString("endpoint", null);
        String apiKeyEnv = root.optString("api_key_env", null);
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
            if (delivery == null) {
                root.collector().add("capabilities.vision.delivery is required and must be one of "
                        + VISION_DELIVERIES);
            }
            if (verificationRequirement == null) {
                root.collector().add("capabilities.vision.verification is required and must be 'required'");
            }
            if (delivery != null && verificationRequirement != null) {
                vision = new VisionCapability(delivery, true);
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
        if ("local".equals(runner) && declaresVision) {
            root.collector().add("runner: local does not support capabilities.vision; "
                    + "the HTTP adapter sends only text");
        }

        Values verification = root.optMap("verification").rejectUnknownKeys(VERIFICATION);
        boolean verified = verification.has("verified_on");
        // Kept, not discarded: "why is this profile not eligible" is answered by the exact
        // command that would settle it, and `warden profiles --verify` runs that command
        // rather than making the operator retype it from a comment.
        String probe = verification.optString("probe", null);
        List<String> whatToCheck = verification.optStringList("what_to_check", List.of());

        root.throwIfAny();
        return new Profile(name, role, vendor, model, effort, command, args, readOnly, wallClock,
                promptTemplate, jsonSchema, enforceSchema, requiredFields, quotaSignatures,
                promptDelivery, attachmentFlag, vision, runner, endpoint, apiKeyEnv,
                probe, whatToCheck, verified);
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
