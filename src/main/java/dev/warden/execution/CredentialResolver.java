package dev.warden.execution;

import dev.warden.config.Connection;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves the connection's own credential immediately before dispatch.
 * An OS-store reference is never satisfied from the environment, and an env reference
 * is never read from the vault.
 */
public final class CredentialResolver {
    private final Function<String, String> environment;
    private final OsCredentialStore store;

    public CredentialResolver() { this(System::getenv); }

    public CredentialResolver(Function<String, String> environment) {
        this(environment, OsCredentialStores.platform());
    }

    public CredentialResolver(Function<String, String> environment, OsCredentialStore store) {
        this.environment = Objects.requireNonNull(environment);
        this.store = store == null ? OsCredentialStores.unavailable() : store;
    }

    /** The result is transient dispatch data: never put it in a command, record, or log. */
    public String resolve(Connection connection) {
        if (!connection.enabled()) throw new IllegalStateException("connection is disabled");
        if (connection.credentialRef() == null) return null;
        String value = read(connection);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("connection credential is missing; set its referenced environment variable or OS store entry");
        }
        if (!acceptable(value)) {
            throw new IllegalStateException("connection credential contains unsupported characters");
        }
        return value;
    }

    /** Safe for settings/doctor output. No credential value is retained or returned here. */
    public Map<String, Object> status(Connection connection) {
        boolean present = false;
        String state;
        if (connection.credentialRef() == null) state = "not_required";
        else {
            try {
                String value = read(connection);
                present = value != null && !value.isBlank();
                state = !present ? "missing" : acceptable(value) ? "ready" : "invalid";
            } catch (IllegalStateException failure) {
                state = "unavailable";
            }
        }
        if (!connection.enabled()) state = "disabled";
        return Map.of("credential_present", present,
                "source", connection.credentialRef() == null ? "none" : connection.credentialRef(),
                "state", state);
    }

    /** Printable ASCII, short enough for a Credential Manager blob and an HTTP header. */
    public static boolean acceptable(String value) {
        return value != null && !value.isEmpty() && value.length() <= 2048
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    private String read(Connection connection) {
        if (connection.apiKeyEnv() != null) {
            try {
                return environment.apply(connection.apiKeyEnv());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("connection credential source could not be read");
            }
        }
        String target = connection.osStoreTarget();
        if (target == null || !store.available()) {
            throw new IllegalStateException("connection credential source is unavailable");
        }
        try {
            return store.read(target);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("connection credential source could not be read");
        }
    }
}
