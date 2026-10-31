package dev.warden.config;

import dev.warden.execution.ApiConnectionCheck;
import dev.warden.execution.CredentialResolver;
import dev.warden.execution.OpenRouterLogin;
import dev.warden.execution.OsCredentialStore;
import dev.warden.execution.OsCredentialStores;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** User-owned connections; no command argument accepts a credential value. */
public final class ConnectionCommand {
    private final Function<String, String> environment;
    private final OsCredentialStore credentials;
    private final OpenRouterLogin.Exchanger exchanger;

    public ConnectionCommand() { this(System::getenv, OsCredentialStores.platform(), null); }

    /** Callers that only inject an environment do not probe the OS vault. */
    public ConnectionCommand(Function<String, String> environment) {
        this(environment, OsCredentialStores.unavailable(), null);
    }

    public ConnectionCommand(Function<String, String> environment, OsCredentialStore credentials) {
        this(environment, credentials, null);
    }

    /** A null exchanger uses the documented OpenRouter token endpoint. */
    public ConnectionCommand(Function<String, String> environment, OsCredentialStore credentials,
                             OpenRouterLogin.Exchanger exchanger) {
        this.environment = environment;
        this.credentials = credentials == null ? OsCredentialStores.unavailable() : credentials;
        this.exchanger = exchanger == null ? OpenRouterLogin::exchangeLive : exchanger;
    }

    public Map<String, Object> run(Path home, String[] argv) throws IOException {
        return run(home, argv, InputStream.nullInputStream());
    }

    public Map<String, Object> run(Path home, String[] argv, InputStream stdin) throws IOException {
        return runWithInput(home, argv, () -> stdin);
    }

    /** Open hidden console input only when the validated operation is ready to consume it. */
    public Map<String, Object> runWithInput(Path home, String[] argv, Supplier<InputStream> input) throws IOException {
        String operation = argv.length < 2 ? "list" : argv[1];
        ConnectionStore store = new ConnectionStore(home);
        if ("list".equals(operation)) {
            if (argv.length > 2) throw new IllegalArgumentException("connections list accepts no options");
            Map<String, Object> result = new LinkedHashMap<>(new ConnectionService(home, environment, credentials).list());
            result.put("home", home.toString());
            return result;
        }
        if (argv.length < 3) throw new IllegalArgumentException("connections requires an operation and connection id");
        String id = argv[2];
        Connection.requireId(id);
        if ("coding-check".equals(operation)) {
            return dev.warden.execution.CodingProbe.run(home, id, argv, resolver());
        }
        if ("add".equals(operation)) {
            Map<String, String> options = new LinkedHashMap<>();
            boolean noAuth = false;
            for (int i = 3; i < argv.length; i++) {
                String option = argv[i];
                if ("--no-auth".equals(option)) {
                    if (noAuth) throw new IllegalArgumentException("duplicate --no-auth");
                    noAuth = true;
                } else if ("--os-store".equals(option)) {
                    if (options.put(option, "true") != null) throw new IllegalArgumentException("duplicate connection option");
                } else {
                    if (!Set.of("--provider", "--base-url", "--env").contains(option)
                            || i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                        throw new IllegalArgumentException("connections add accepts --provider, --base-url, --env, --os-store or --no-auth; key values are not accepted");
                    }
                    if (options.put(option, argv[++i]) != null) throw new IllegalArgumentException("duplicate connection option");
                }
            }
            String provider = options.getOrDefault("--provider", "openrouter");
            String base = options.get("--base-url");
            if (base == null) base = switch (provider) {
                case "openrouter" -> "https://openrouter.ai/api/v1";
                case "gonkarouter" -> "https://api.gonkarouter.io/v1";
                case "joingonka" -> "https://gate.joingonka.ai/v1";
                default -> throw new IllegalArgumentException("custom connections require --base-url");
            };
            String variable = options.get("--env");
            boolean osStore = options.containsKey("--os-store");
            int modes = (noAuth ? 1 : 0) + (variable != null ? 1 : 0) + (osStore ? 1 : 0);
            if (modes != 1) throw new IllegalArgumentException("choose --env NAME, --os-store or --no-auth explicitly");
            if (osStore && !credentials.available()) {
                throw new IllegalArgumentException("OS credential store is unavailable; use --env NAME");
            }
            String reference = noAuth ? null : osStore ? "os-store:warden/" + id : "env:" + variable;
            Connection connection = new Connection(id, provider, "openai_chat", base, reference, true);
            Path file = store.add(connection);
            return Map.of("ok", true, "code", "connection_added", "file", file.toString(),
                    "connection", view(connection, resolver()), "profiles_activated", false);
        }
        if ("auth-login".equals(operation)) {
            boolean headless = false;
            for (int i = 3; i < argv.length; i++) {
                if (!"--headless".equals(argv[i]) || headless) {
                    throw new IllegalArgumentException("connections auth-login accepts only an id and optional --headless");
                }
                headless = true;
            }
            return login(home, id, headless, input);
        }
        if (argv.length != 3) throw new IllegalArgumentException("this connection operation accepts only an id");
        if ("remove".equals(operation)) {
            store.remove(id);
            return Map.of("ok", true, "code", "connection_removed", "connection", id);
        }
        if ("enable".equals(operation) || "disable".equals(operation)) {
            Connection connection = store.setEnabled(id, "enable".equals(operation));
            return Map.of("ok", true, "code", "connection_updated", "connection", view(connection, resolver()));
        }
        if ("check".equals(operation)) {
            Connection connection = store.load().connections().get(id);
            if (connection == null) return Map.of("ok", false, "code", "connection_not_found", "connection", id);
            return ApiConnectionCheck.check(connection, resolver());
        }
        if ("auth-set".equals(operation) || "auth-remove".equals(operation)) {
            if (argv.length != 3) throw new IllegalArgumentException("this connection operation accepts only an id");
            ConnectionStore.Versioned current = store.versioned(id);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("revision", current.revision());
            if ("auth-set".equals(operation)) payload.put("secret", readSecret(input.get()));
            return new ConnectionService(home, environment, credentials).apply(operation, payload).report();
        }
        throw new IllegalArgumentException(
                "connections supports list, add, check, coding-check, enable, disable, remove, auth-set, auth-remove and auth-login");
    }

    /**
     * PKCE into the connection's existing OS-store target. The connection file and policy
     * stay unchanged. A key that cannot be saved is not written anywhere else.
     */
    private Map<String, Object> login(Path home, String id, boolean headless, Supplier<InputStream> input) throws IOException {
        ConnectionStore.Versioned current = new ConnectionStore(home).versioned(id);
        Connection connection = current.connection();
        if (!openRouterApi(connection)) {
            throw new IllegalArgumentException("OpenRouter login is only available for the OpenRouter HTTPS connection");
        }
        if (connection.osStoreTarget() == null) {
            throw new IllegalArgumentException("OpenRouter login stores a key only in the OS credential store");
        }
        if (!credentials.available()) {
            throw new IllegalArgumentException("OS credential store is unavailable; no authorization was requested");
        }
        String verifier = OpenRouterLogin.verifier();
        String challenge = OpenRouterLogin.challenge(verifier);
        if (headless) {
            System.err.println("Open the URL, approve a Warden key, and paste the authorization code.");
            System.err.println(OpenRouterLogin.authorizeUrl(null, challenge, null));
            System.err.flush();
            return storeIssued(home, id, current.revision(), exchanger.exchange(readAuthorizationCode(input.get()), verifier));
        }
        String state = OpenRouterLogin.verifier();
        try (OpenRouterLogin.Callback callback = OpenRouterLogin.Callback.bind(state)) {
            String url = OpenRouterLogin.authorizeUrl(callback.port(), challenge, state);
            System.err.println("Open the URL and approve a Warden key. Waiting up to 3 minutes.");
            System.err.println(url);
            OpenRouterLogin.open(url);
            return storeIssued(home, id, current.revision(),
                    exchanger.exchange(callback.await(Duration.ofMinutes(3)), verifier));
        }
    }

    private Map<String, Object> storeIssued(Path home, String id, String revision, String key) {
        if (!CredentialResolver.acceptable(key)) {
            throw new IllegalArgumentException(
                    "OpenRouter returned a key Warden will not store. Revoke it in the OpenRouter account.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("revision", revision);
        payload.put("secret", key);
        Map<String, Object> report = new LinkedHashMap<>(
                new ConnectionService(home, environment, credentials).apply("auth-set", payload).report());
        if (!Boolean.TRUE.equals(report.get("ok"))) {
            report.put("message",
                    "OpenRouter issued a key, but it was not saved. Revoke it in the OpenRouter account if you do not retry.");
            report.put("profiles_activated", false);
            report.put("service_key_revoked", false);
        }
        return report;
    }

    private static boolean openRouterApi(Connection connection) {
        if (!"openrouter".equals(connection.provider())) return false;
        URI uri = URI.create(connection.baseUrl());
        return "https".equalsIgnoreCase(uri.getScheme())
                && "openrouter.ai".equalsIgnoreCase(uri.getHost())
                && "/api/v1".equals(uri.getPath())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static String readAuthorizationCode(InputStream stdin) throws IOException {
        String code = readInputLine(stdin, 256,
                "OpenRouter authorization code was rejected. Nothing was stored.");
        if (!OpenRouterLogin.authorizationCode(code)) {
            throw new IllegalArgumentException("OpenRouter authorization code was rejected. Nothing was stored.");
        }
        return code;
    }

    private CredentialResolver resolver() { return new CredentialResolver(environment, credentials); }

    /** One bounded stdin line. The value is never an argument and does not require EOF. */
    private static String readSecret(InputStream stdin) throws IOException {
        String secret = readInputLine(stdin, 2048, "credential input was rejected");
        if (!CredentialResolver.acceptable(secret)) throw new IllegalArgumentException("credential input was rejected");
        return secret;
    }

    private static String readInputLine(InputStream stdin, int limit, String failure) throws IOException {
        if (stdin == null) throw new IllegalArgumentException(failure);
        byte[] buffered = new byte[limit];
        int end = 0;
        try {
            while (true) {
                int next = stdin.read();
                if (next < 0 || next == '\n' || next == '\r') break;
                if (end == limit) throw new IllegalArgumentException(failure);
                buffered[end++] = (byte) next;
            }
            return new String(buffered, 0, end, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(buffered, (byte) 0);
        }
    }

    public static Map<String, Object> view(Connection connection, Function<String, String> environment) {
        return view(connection, new CredentialResolver(environment));
    }

    public static Map<String, Object> view(Connection connection, CredentialResolver resolver) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", connection.id());
        row.put("provider", connection.provider());
        row.put("protocol", connection.protocol());
        row.put("base_url", connection.baseUrl());
        row.put("enabled", connection.enabled());
        row.put("fingerprint", connection.fingerprint());
        row.put("auth", resolver.status(connection));
        row.put("auth_verified", false);
        row.put("execution", "text_completion_only");
        return row;
    }
}
