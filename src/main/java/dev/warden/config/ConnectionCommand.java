package dev.warden.config;

import dev.warden.execution.ApiConnectionCheck;
import dev.warden.execution.CredentialResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** User-owned connections; no command argument accepts a credential value. */
public final class ConnectionCommand {
    private final Function<String, String> environment;
    public ConnectionCommand() { this(System::getenv); }
    public ConnectionCommand(Function<String, String> environment) { this.environment = environment; }

    public Map<String, Object> run(Path home, String[] argv) throws IOException {
        String operation = argv.length < 2 ? "list" : argv[1];
        ConnectionStore store = new ConnectionStore(home);
        if ("list".equals(operation)) {
            if (argv.length > 2) throw new IllegalArgumentException("connections list accepts no options");
            ConnectionStore.Snapshot snapshot = store.load();
            List<Map<String, Object>> rows = new ArrayList<>();
            snapshot.connections().values().forEach(connection -> rows.add(view(connection, environment)));
            return Map.of("ok", snapshot.problems().isEmpty(), "home", home.toString(),
                    "connections", rows, "problems", snapshot.problems());
        }
        if (argv.length < 3) throw new IllegalArgumentException("connections requires an operation and connection id");
        String id = argv[2];
        Connection.requireId(id);
        if ("add".equals(operation)) {
            Map<String, String> options = new LinkedHashMap<>();
            boolean noAuth = false;
            for (int i = 3; i < argv.length; i++) {
                String option = argv[i];
                if ("--no-auth".equals(option)) {
                    if (noAuth) throw new IllegalArgumentException("duplicate --no-auth");
                    noAuth = true;
                } else {
                    if (!Set.of("--provider", "--base-url", "--env").contains(option)
                            || i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                        throw new IllegalArgumentException("connections add accepts --provider, --base-url, --env or --no-auth; key values are not accepted");
                    }
                    if (options.put(option, argv[++i]) != null) throw new IllegalArgumentException("duplicate connection option");
                }
            }
            String provider = options.getOrDefault("--provider", "openrouter");
            String base = options.get("--base-url");
            if (base == null) base = switch (provider) {
                case "openrouter" -> "https://openrouter.ai/api/v1";
                case "gonkarouter" -> "https://api.gonkarouter.io/v1";
                default -> throw new IllegalArgumentException("custom connections require --base-url");
            };
            String variable = options.get("--env");
            if (noAuth == (variable != null)) throw new IllegalArgumentException("choose --env NAME or --no-auth explicitly");
            Connection connection = new Connection(id, provider, "openai_chat", base,
                    variable == null ? null : "env:" + variable, true);
            Path file = store.add(connection);
            return Map.of("ok", true, "code", "connection_added", "file", file.toString(),
                    "connection", view(connection, environment), "profiles_activated", false);
        }
        if (argv.length != 3) throw new IllegalArgumentException("this connection operation accepts only an id");
        if ("remove".equals(operation)) {
            store.remove(id);
            return Map.of("ok", true, "code", "connection_removed", "connection", id);
        }
        if ("enable".equals(operation) || "disable".equals(operation)) {
            Connection connection = store.setEnabled(id, "enable".equals(operation));
            return Map.of("ok", true, "code", "connection_updated", "connection", view(connection, environment));
        }
        if ("check".equals(operation)) {
            Connection connection = store.load().connections().get(id);
            if (connection == null) return Map.of("ok", false, "code", "connection_not_found", "connection", id);
            return ApiConnectionCheck.check(connection, environment);
        }
        throw new IllegalArgumentException("connections supports list, add, check, enable, disable and remove");
    }

    public static Map<String, Object> view(Connection connection, Function<String, String> environment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", connection.id());
        row.put("provider", connection.provider());
        row.put("protocol", connection.protocol());
        row.put("base_url", connection.baseUrl());
        row.put("enabled", connection.enabled());
        row.put("fingerprint", connection.fingerprint());
        row.put("auth", new CredentialResolver(environment).status(connection));
        row.put("auth_verified", false);
        row.put("execution", "text_completion_only");
        return row;
    }
}
