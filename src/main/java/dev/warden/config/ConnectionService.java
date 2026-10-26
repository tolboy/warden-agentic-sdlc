package dev.warden.config;

import dev.warden.execution.ApiConnectionCheck;
import dev.warden.execution.CredentialResolver;
import dev.warden.execution.OsCredentialStore;
import dev.warden.execution.OsCredentialStores;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Settings operations accept nonsecret references, never a credential value or shell command. */
public final class ConnectionService {
    public record Outcome(int status, Map<String, Object> report) {}

    private static final Set<String> SETTINGS = Set.of("id", "provider", "base_url", "credential_ref", "enabled");
    private static final Set<String> UPDATE = Set.of("id", "provider", "base_url", "credential_ref", "enabled", "revision");
    private static final Set<String> ACTION = Set.of("id", "revision");
    private final ConnectionStore store;
    private final Function<String, String> environment;
    private final OsCredentialStore credentials;

    public ConnectionService(Path home) { this(home, System::getenv, OsCredentialStores.platform()); }

    /** Tests and embedded callers that do not pass a vault keep env mode and do not probe the OS. */
    public ConnectionService(Path home, Function<String, String> environment) {
        this(home, environment, OsCredentialStores.unavailable());
    }

    public ConnectionService(Path home, Function<String, String> environment, OsCredentialStore credentials) {
        store = new ConnectionStore(Objects.requireNonNull(home));
        this.environment = Objects.requireNonNull(environment);
        this.credentials = credentials == null ? OsCredentialStores.unavailable() : credentials;
    }

    /** One coherent file read supplies each displayed object and its optimistic revision. */
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> problems = new LinkedHashMap<>();
        try {
            ConnectionStore.Snapshot snapshot = store.load();
            snapshot.problems().keySet().forEach(id -> problems.put(id, "Connection configuration is invalid; inspect its local file"));
            for (String id : snapshot.connections().keySet()) {
                try { rows.add(view(store.versioned(id))); }
                catch (IOException | ConfigException changed) {
                    problems.put(id, "Connection changed or could not be read; reload settings");
                }
            }
        } catch (IOException unavailable) {
            problems.put("directory", "Connections directory could not be read");
        }
        return Map.of("ok", problems.isEmpty(), "connections", rows, "problems", problems,
                "os_store_available", credentials.available());
    }

    public Outcome apply(String operation, Map<String, Object> payload) {
        try {
            if (!Set.of("add", "update", "enable", "disable", "remove", "check", "auth-set", "auth-remove")
                    .contains(operation == null ? "" : operation)) {
                throw new Invalid();
            }
            Set<String> allowed = switch (operation) {
                case "add" -> SETTINGS;
                case "update" -> UPDATE;
                case "auth-set" -> Set.of("id", "revision", "secret");
                default -> ACTION;
            };
            if (payload == null || !allowed.containsAll(payload.keySet())) throw new Invalid();
            if ("auth-set".equals(operation) && !payload.keySet().containsAll(Set.of("id", "revision", "secret"))) {
                throw new Invalid();
            }
            String id = string(payload, "id");
            Connection.requireId(id);
            if ("add".equals(operation) || "update".equals(operation)) {
                String provider = string(payload, "provider");
                String base = string(payload, "base_url");
                if (!payload.containsKey("credential_ref")) throw new Invalid();
                Object reference = payload.get("credential_ref");
                if (reference != null && !(reference instanceof String)) throw new Invalid();
                boolean enabled = true;
                if (payload.containsKey("enabled")) {
                    if (!(payload.get("enabled") instanceof Boolean value)) throw new Invalid();
                    enabled = value;
                } else if ("update".equals(operation)) throw new Invalid();
                Connection next = new Connection(id, provider, "openai_chat", base, (String) reference, enabled);
                ConnectionStore.Versioned saved;
                if ("add".equals(operation)) {
                    store.add(next);
                    saved = store.versioned(id);
                } else saved = store.update(next, revision(payload));
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("ok", true);
                report.put("code", "add".equals(operation) ? "connection_added" : "connection_updated");
                report.put("connection", view(saved));
                report.put("profiles_activated", false);
                return new Outcome("add".equals(operation) ? 201 : 200, report);
            }
            String revision = revision(payload);
            if ("remove".equals(operation)) {
                store.remove(id, revision);
                return new Outcome(200, Map.of("ok", true, "code", "connection_removed", "connection", id));
            }
            if ("enable".equals(operation) || "disable".equals(operation)) {
                ConnectionStore.Versioned updated = store.setEnabled(id, "enable".equals(operation), revision);
                return new Outcome(200, Map.of("ok", true, "code", "connection_updated", "connection", view(updated),
                        "profiles_activated", false));
            }
            if ("auth-set".equals(operation) || "auth-remove".equals(operation)) {
                return credential(operation, id, revision, payload.get("secret"));
            }
            ConnectionStore.Versioned current = store.versioned(id);
            if (!revision.equals(current.revision())) throw new ConnectionStore.Conflict();
            Map<String, Object> check = new LinkedHashMap<>(ApiConnectionCheck.check(current.connection(), environment));
            if (!revision.equals(store.versioned(id).revision())) throw new ConnectionStore.Conflict();
            check.put("revision", current.revision());
            return new Outcome(200, check);
        } catch (ConnectionStore.AlreadyExists exists) {
            return error(409, "connection_exists", "A connection with this id already exists");
        } catch (ConnectionStore.Referenced referenced) {
            return error(409, "connection_in_use", "A profile references this connection, or profile references could not be checked");
        } catch (ConnectionStore.Conflict conflict) {
            return error(409, "connection_revision_conflict", "Connection changed in another window; reload before saving");
        } catch (ConnectionStore.Missing missing) {
            return error(404, "connection_not_found", "Connection no longer exists; reload settings");
        } catch (ConfigException invalid) {
            if (invalid.issues().stream().anyMatch(issue -> issue.contains("unsupported YAML layout"))) {
                return error(400, "connection_layout_unsupported", "This YAML layout cannot be edited safely; edit the local file directly");
            }
            return error(400, "connection_invalid", "Invalid connection settings; use a permitted endpoint and environment reference");
        } catch (Invalid invalid) {
            return error(400, "connection_invalid", "Invalid connection settings or unsupported fields");
        } catch (IOException unavailable) {
            return error(503, "connection_store_unavailable", "Connection settings could not be read or saved");
        }
    }

    private Outcome credential(String operation, String id, String revision, Object supplied) throws IOException {
        if ("auth-set".equals(operation)) {
            if (!(supplied instanceof String secret) || !CredentialResolver.acceptable(secret)) {
                return error(400, "credential_rejected", "The credential was rejected. It was not stored.");
            }
            ConnectionStore.Versioned current = versioned(id, revision);
            String target = current.connection().osStoreTarget();
            if (target == null) {
                return error(400, "credential_store_not_selected",
                        "This connection does not use the OS credential store. The key was not stored.");
            }
            if (!credentials.available()) {
                return error(503, "credential_store_unavailable",
                        "The OS credential store is unavailable. The key was not stored.");
            }
            try {
                credentials.write(target, secret);
            } catch (RuntimeException failure) {
                return error(503, "credential_store_unavailable",
                        "The OS credential store did not accept the key. It was not stored.");
            }
            return new Outcome(200, Map.of("ok", true, "code", "credential_stored",
                    "profiles_activated", false, "source", current.connection().credentialRef(),
                    "service_key_revoked", false));
        }
        ConnectionStore.Versioned current = versioned(id, revision);
        String target = current.connection().osStoreTarget();
        if (target == null) {
            return error(400, "credential_store_not_selected",
                    "This connection does not use the OS credential store. Nothing was deleted.");
        }
        if (!credentials.available()) {
            return error(503, "credential_store_unavailable",
                    "The OS credential store is unavailable. The local key was not deleted.");
        }
        try {
            credentials.delete(target);
        } catch (RuntimeException failure) {
            return error(503, "credential_store_unavailable",
                    "The OS credential store did not delete the key.");
        }
        return new Outcome(200, Map.of("ok", true, "code", "credential_removed",
                "profiles_activated", false, "service_key_revoked", false));
    }

    private ConnectionStore.Versioned versioned(String id, String revision) throws IOException {
        ConnectionStore.Versioned current = store.versioned(id);
        if (!revision.equals(current.revision())) throw new ConnectionStore.Conflict();
        return current;
    }

    private Map<String, Object> view(ConnectionStore.Versioned value) {
        Map<String, Object> row = new LinkedHashMap<>(ConnectionCommand.view(value.connection(),
                new CredentialResolver(environment, credentials)));
        row.put("revision", value.revision());
        row.put("credential_ref", value.connection().credentialRef());
        return row;
    }

    private static String string(Map<String, Object> values, String name) {
        if (!(values.get(name) instanceof String value) || value.isBlank()) throw new Invalid();
        return value;
    }

    private static String revision(Map<String, Object> values) {
        String value = string(values, "revision");
        if (!value.matches("[a-f0-9]{64}")) throw new Invalid();
        return value;
    }

    private static Outcome error(int status, String code, String message) {
        return new Outcome(status, Map.of("ok", false, "code", code, "message", message));
    }

    @SuppressWarnings("serial")
    private static final class Invalid extends RuntimeException {}
}
