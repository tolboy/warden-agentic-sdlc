package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** A user's endpoint and credential reference. Credential values never belong in this record. */
public record Connection(String id, String provider, String protocol, String baseUrl,
                         String credentialRef, boolean enabled) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> PROVIDERS = Set.of("openrouter", "gonkarouter", "custom");
    private static final Set<String> FIELDS = Set.of(
            "version", "connection", "provider", "protocol", "base_url", "auth", "enabled");

    public Connection {
        requireId(id);
        if (!PROVIDERS.contains(provider == null ? "" : provider)) {
            throw invalid("provider must be openrouter, gonkarouter, or custom");
        }
        if (!"openai_chat".equals(protocol)) throw invalid("protocol must be openai_chat");
        if (baseUrl == null || baseUrl.isBlank()) throw invalid("base_url must be a non-empty URL");
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException failure) {
            throw invalid("base_url must be a valid URL without credentials, query, or fragment");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.isOpaque()) {
            throw invalid("base_url must have a host and no credentials, query, or fragment");
        }
        boolean loopback = isLoopback(host);
        if (!"https".equalsIgnoreCase(scheme)
                && !("http".equalsIgnoreCase(scheme) && loopback)) {
            throw invalid("base_url requires HTTPS; HTTP is allowed only for a loopback host");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw invalid("base_url port must be between 1 and 65535");
        }
        if (!"custom".equals(provider)) {
            String expectedHost = "openrouter".equals(provider) ? "openrouter.ai" : "api.gonkarouter.io";
            String expectedPath = "openrouter".equals(provider) ? "/api/v1" : "/v1";
            if (!"https".equalsIgnoreCase(scheme) || !expectedHost.equalsIgnoreCase(host)
                    || !expectedPath.equals(uri.getRawPath())
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw invalid("base_url must match the selected provider's HTTPS API endpoint");
            }
        }
        if (credentialRef == null) {
            if (!"custom".equals(provider) || !loopback) {
                throw invalid("auth none is allowed only for a custom loopback connection");
            }
        } else if (credentialRef.startsWith("env:")) {
            if (!ENV.matcher(credentialRef.substring(4)).matches()) {
                throw invalid("auth.credential_ref must be env: followed by a valid environment variable name");
            }
        } else if (!credentialRef.equals("os-store:warden/" + id)) {
            throw invalid("auth.credential_ref must be env:NAME or os-store:warden/<id> for this connection");
        }
    }

    public static Connection parse(String yaml, String source) {
        Object parsed;
        try {
            parsed = Yaml.parse(yaml);
        } catch (RuntimeException failure) {
            // YAML diagnostics can quote malformed scalar values; a pasted key must stay private.
            throw new ConfigException(source, "invalid connection YAML");
        }
        Values values = Values.of(parsed, source).rejectUnknownKeys(FIELDS);
        values.requireVersion(1);
        String id = values.requireString("connection");
        String provider = values.requireString("provider");
        String protocol = values.requireString("protocol");
        String baseUrl = values.requireString("base_url");
        boolean enabled = values.optBool("enabled", true);
        Values auth = values.optMap("auth").rejectUnknownKeys(Set.of("type", "credential_ref"));
        String type = auth.requireString("type");
        String reference = auth.optString("credential_ref", null);
        if (!"api_key".equals(type) && !"none".equals(type)) {
            values.collector().add("auth.type must be api_key or none");
        } else if ("api_key".equals(type) && reference == null) {
            values.collector().add("auth.credential_ref is required for api_key authentication");
        } else if ("none".equals(type) && auth.has("credential_ref")) {
            values.collector().add("auth.credential_ref must be absent for auth none");
        }
        values.throwIfAny();
        try {
            return new Connection(id, provider, protocol, baseUrl, reference, enabled);
        } catch (ConfigException failure) {
            throw new ConfigException(source, failure.issues());
        }
    }

    /** The only protocol supported by the first connection implementation. */
    public String endpoint() { return baseUrl + "/chat/completions"; }

    /** Environment name for an {@code env:} reference. An OS-store connection has none. */
    public String apiKeyEnv() {
        return credentialRef != null && credentialRef.startsWith("env:") ? credentialRef.substring(4) : null;
    }

    /** Vault target {@code warden/<id>} when the reference is {@code os-store:warden/<id>}. */
    public String osStoreTarget() {
        if (credentialRef == null || !credentialRef.startsWith("os-store:")) return null;
        return credentialRef.substring("os-store:".length());
    }

    /** A secret rotation does not change the configuration fingerprint. */
    public String fingerprint() {
        String material = String.join("\n", "connection-v1", id, provider, protocol, baseUrl,
                credentialRef == null ? "none" : credentialRef, Boolean.toString(enabled));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public Connection withEnabled(boolean value) {
        return new Connection(id, provider, protocol, baseUrl, credentialRef, value);
    }

    /** All serialized values have been validated; quote the URL to keep YAML unambiguous. */
    public String toYaml() {
        return "version: 1\nconnection: " + id + "\nprovider: " + provider
                + "\nprotocol: " + protocol + "\nbase_url: '" + baseUrl.replace("'", "''")
                + "'\nauth:\n  type: " + (credentialRef == null ? "none" : "api_key")
                + (credentialRef == null ? "" : "\n  credential_ref: " + credentialRef)
                + "\nenabled: " + enabled + "\n";
    }

    static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid("connection id must start with a lowercase letter and contain only lowercase letters, digits, _ or - (maximum 64 characters)");
        }
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || "::1".equals(host)) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static ConfigException invalid(String issue) { return new ConfigException("connection", issue); }
}
