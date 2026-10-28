package dev.warden.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which service and account a profile's calls authenticate against.
 *
 * The model developer is not a service: a Codex CLI pointed at a DeepSeek model through a
 * `--profile` is still whatever that Codex configuration authenticates with, and Warden cannot
 * see which. Such a profile is {@code needs_mapping} until the operator names the service.
 * Nothing here reads another program's configuration or credentials.
 *
 * @param source    {@code explicit} ({@code service:} in the file), {@code inferred} (legacy file,
 *                  single-service client, no provider selector), {@code connection} (API/OpenCode),
 *                  {@code endpoint} ({@code runner: local}) or {@code needs_mapping}
 * @param reason    why a profile needs a mapping, otherwise null
 * @param suggested the client's own service when that is plausible; applied only by an explicit mapping
 */
public record ServiceBinding(String service, String account, String source, String reason, String suggested) {

    public static final String EXPLICIT = "explicit", INFERRED = "inferred", CONNECTION = "connection",
            ENDPOINT = "endpoint", NEEDS_MAPPING = "needs_mapping";

    /** CLI clients and orchestrators. They execute roles; they never name a service. */
    public static final Set<String> CLIENTS = Set.of("codex", "claude", "grok", "gemini", "agy", "opencode", "orca", "cli");

    /** The one service a client authenticates with when nothing redirects it. */
    private static final Map<String, String> NATIVE = Map.of(
            "codex", "openai", "grok", "x-ai", "claude", "anthropic", "gemini", "google", "agy", "antigravity");

    /** Flags that can point any CLI at another provider or account. */
    private static final Set<String> SELECTORS = Set.of("--profile", "--config", "--api-key", "--base-url",
            "--api-base", "--endpoint", "--provider", "--oss", "--local-provider", "--auth-file", "--credential",
            "--auth-mode", "--organization", "--project");

    public boolean needsMapping() { return NEEDS_MAPPING.equals(source); }

    public static ServiceBinding of(Profile profile) {
        if (profile.connectionRef() != null) {
            String provider = profile.apiConnection() == null ? null : profile.apiConnection().provider();
            return new ServiceBinding(provider, profile.connectionRef(), CONNECTION, null, null);
        }
        if ("local".equals(profile.runner())) return new ServiceBinding("local", "default", ENDPOINT, null, null);
        if (profile.cliService() != null) {
            return new ServiceBinding(profile.cliService().service(), profile.cliService().account(), EXPLICIT, null, null);
        }
        String client = client(profile.command());
        String nativeService = NATIVE.get(client);
        String developer = Profile.canonicalVendor(profile.modelVendor());
        if (nativeService == null) return new ServiceBinding(null, null, NEEDS_MAPPING, "unknown_client", null);
        // Antigravity serves several developers' models under one login; the others serve their own.
        boolean developerFits = "agy".equals(client) || nativeService.equals(developer);
        if (!developerFits) {
            return new ServiceBinding(null, null, NEEDS_MAPPING, "model_developer_differs_from_client", null);
        }
        if (hasProviderSelector(client, profile.args())) {
            return new ServiceBinding(null, null, NEEDS_MAPPING, "provider_selector_in_args", nativeService);
        }
        return new ServiceBinding(nativeService, "default", INFERRED, null, null);
    }

    /** Executable base name without directory or Windows launcher extension. */
    public static String client(String command) {
        if (command == null) return "cli";
        String name = command.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.replaceFirst("(?i)\\.(exe|cmd|bat|ps1)$", "").toLowerCase(Locale.ROOT);
    }

    static boolean hasProviderSelector(String client, List<String> args) {
        boolean codex = "codex".equals(client);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            int equals = arg.indexOf('=');
            String flag = arg.startsWith("-") && equals > 0 ? arg.substring(0, equals) : arg;
            if (SELECTORS.contains(flag)) return true;
            if (codex && "-p".equals(flag)) return true;
            if (codex && "-c".equals(flag)) {
                String value = equals > 0 && arg.startsWith("-c=") ? arg.substring(equals + 1)
                        : i + 1 < args.size() ? args.get(i + 1) : "";
                String key = value.strip().toLowerCase(Locale.ROOT);
                if (key.startsWith("model_provider") || key.startsWith("profile") || key.contains("base_url")) return true;
            }
        }
        return false;
    }
}
