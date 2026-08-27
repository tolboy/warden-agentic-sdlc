package dev.warden.config;

import dev.warden.yaml.Yaml;

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
 */
public record Profile(
        String name,
        String role,
        String vendor,
        String model,
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
        String runner,
        boolean verified) {

    private static final Set<String> TOP_LEVEL = Set.of(
            "version", "profile", "role", "vendor", "model", "command", "args", "read_only",
            "limits", "prompt_template", "json_schema", "enforce_schema", "artifact", "quota",
            "prompt_delivery", "attachments", "runner", "verification", "notes");
    private static final Set<String> LIMITS = Set.of("wall_clock_minutes");
    private static final Set<String> ARTIFACT = Set.of("required_fields");
    private static final Set<String> QUOTA = Set.of("signatures");
    private static final Set<String> ATTACHMENTS = Set.of("flag");
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
    public static final Set<String> PROMPT_DELIVERY = Set.of("argv", "stdin");

    /**
     * How the role is launched. {@code direct} is a vendor CLI in this process. {@code orca}
     * is a supervised worker inside the current Orca worktree — Warden never creates that
     * worktree. {@code local} is a named intent for a future in-process runner and is refused
     * at resolve time until it exists.
     */
    public static final Set<String> RUNNERS = Set.of("direct", "orca", "local");

    public static Profile parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        String name = root.requireString("profile");
        String role = root.requireEnum("role", ROLES, null);
        if (role == null) root.collector().add("role is required and must be one of " + ROLES);
        String vendor = root.requireString("vendor");
        String command = root.requireString("command");
        List<String> args = root.optStringList("args", List.of());
        String model = root.optString("model", null);

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

        String promptDelivery = root.requireEnum("prompt_delivery", PROMPT_DELIVERY, "argv");

        // How a file the role must LOOK at reaches the vendor. Screenshots are the case that
        // forced this: a visual reviewer that only receives paths is reading a filename, not
        // an image. `flag` is repeated once per file (codex: `-i shot.png -i after.png`).
        // A vendor without such a flag still gets the paths in its prompt and can open them
        // with its own read tool, so the field is optional rather than required.
        Values attachments = root.optMap("attachments").rejectUnknownKeys(ATTACHMENTS);
        String attachmentFlag = attachments.optString("flag", null);
        String runner = root.requireEnum("runner", RUNNERS, "direct");

        Values verification = root.optMap("verification").rejectUnknownKeys(VERIFICATION);
        boolean verified = verification.has("verified_on");

        root.throwIfAny();
        return new Profile(name, role, vendor, model, command, args, readOnly, wallClock,
                promptTemplate, jsonSchema, enforceSchema, requiredFields, quotaSignatures,
                promptDelivery, attachmentFlag, runner, verified);
    }
}
