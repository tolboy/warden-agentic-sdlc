package dev.warden.execution;

import dev.warden.config.Connection;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Resolves only the explicitly named environment credential, immediately before dispatch. */
public final class CredentialResolver {
    private final Function<String, String> environment;

    public CredentialResolver() { this(System::getenv); }

    public CredentialResolver(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    /** The result is transient dispatch data: never put it in a command, record, or log. */
    public String resolve(Connection connection) {
        if (!connection.enabled()) throw new IllegalStateException("connection is disabled");
        if (connection.apiKeyEnv() == null) return null;
        String value = read(connection.apiKeyEnv());
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("connection credential is missing; set its referenced environment variable");
        }
        if (!isHeaderSafe(value)) {
            throw new IllegalStateException("connection credential contains unsupported characters");
        }
        return value;
    }

    /** Safe for settings/doctor output. No credential value is retained or returned here. */
    public Map<String, Object> status(Connection connection) {
        boolean present = false;
        String state;
        if (connection.apiKeyEnv() == null) state = "not_required";
        else {
            try {
                String value = read(connection.apiKeyEnv());
                present = value != null && !value.isBlank();
                state = !present ? "missing" : isHeaderSafe(value) ? "ready" : "invalid";
            } catch (IllegalStateException failure) {
                state = "unavailable";
            }
        }
        if (!connection.enabled()) state = "disabled";
        return Map.of("credential_present", present,
                "source", connection.credentialRef() == null ? "none" : connection.credentialRef(),
                "state", state);
    }

    private String read(String name) {
        try {
            return environment.apply(name);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("connection credential source could not be read");
        }
    }

    private static boolean isHeaderSafe(String value) {
        // Reject before an HTTP library can include an invalid header value in its exception.
        return value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }
}
