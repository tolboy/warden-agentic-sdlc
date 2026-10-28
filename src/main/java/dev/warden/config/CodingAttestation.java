package dev.warden.config;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Local capability evidence, tied to the exact profile, connection and native executable. */
public final class CodingAttestation {
    public static final String ADAPTER = "opencode-relay-v1";
    public static final String SUPPORTED_VERSION = "1.19.4";
    public static final int MAX_REQUESTS = 8;
    private static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final Set<String> PROOFS =
            Set.of(
                    "ok",
                    "coding_tools_verified",
                    "artifact_schema_verified",
                    "independent_gate_passed",
                    "artifact_valid",
                    "process_tree_settled");

    private CodingAttestation() {}

    public static Path receiptPath(Profile profile) {
        if (profile.apiSettings() == null || profile.apiSettings().home() == null) {
            throw new IllegalArgumentException(
                    "coding verification requires a user configuration home");
        }
        Path directory =
                profile.apiSettings().home().resolve("verification").toAbsolutePath().normalize();
        Path destination = directory.resolve(profile.name() + ".coding.json").normalize();
        if (!directory.equals(destination.getParent())) {
            throw new IllegalArgumentException(
                    "coding verification profile name must stay within its verification directory");
        }
        return destination;
    }

    /**
     * The declared profile remains part of the fingerprint even when execution uses a tighter cap.
     */
    public static int outputLimit(Profile profile) {
        return Math.min(profile.maxOutputTokens(), 2048);
    }

    public static long timeLimitSeconds(Profile profile) {
        return Math.min(profile.declaredWallClockMinutes() * 60, 240);
    }

    public static boolean valid(Profile profile) {
        try {
            if (!eligibleShape(profile)) return false;
            Path receipt = receiptPath(profile);
            if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) return false;
            byte[] bytes;
            try (var input = Files.newInputStream(receipt)) {
                bytes = input.readNBytes(MAX_RECEIPT_BYTES + 1);
            }
            if (bytes.length > MAX_RECEIPT_BYTES) return false;
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            Map<String, Object> saved = Json.parseObject(json);
            return matches(profile, saved) && evidenceMatches(profile, saved);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Called only after native verification ran the same executor and independently checked its
     * fixture.
     */
    public static Path write(Profile profile, Map<String, Object> evidence) throws IOException {
        if (!eligibleShape(profile) || !evidenceMatches(profile, evidence)) {
            throw new IOException(
                    "coding capability proof is incomplete or does not match the profile");
        }
        String binaryDigest = binarySha256(profile.harnessExecutable());
        if (!binaryDigest.equals(evidence.get("binary_sha256"))) {
            throw new IOException(
                    "coding executable changed before capability evidence could be saved");
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("version", 1L);
        receipt.put("adapter", ADAPTER);
        receipt.put("profile", profile.name());
        receipt.put("role", profile.role());
        // Retain the contract actually observed by the executor. Never adopt a newer
        // resource digest between proof validation and atomic persistence.
        receipt.put("profile_fingerprint", evidence.get("profile_fingerprint"));
        receipt.put("connection_fingerprint", evidence.get("connection_fingerprint"));
        receipt.put("executable", profile.harnessExecutable().toRealPath().toString());
        receipt.put("binary_sha256", binaryDigest);
        receipt.put("opencode_version", SUPPORTED_VERSION);
        receipt.put("model_served", profile.model());
        receipt.put("max_requests", (long) MAX_REQUESTS);
        receipt.put("max_output_tokens", (long) outputLimit(profile));
        receipt.put("time_budget_seconds", timeLimitSeconds(profile));
        for (String proof : PROOFS) receipt.put(proof, true);
        Path destination = receiptPath(profile);
        Files.createDirectories(destination.getParent());
        return JsonFile.underExclusiveLock(
                destination,
                ".coding-verification.lock",
                () -> {
                    if (!matches(profile, receipt))
                        throw new IOException(
                                "coding inputs changed before capability evidence could be saved");
                    JsonFile.writeAtomically(destination, receipt);
                    return destination;
                });
    }

    private static boolean eligibleShape(Profile profile) throws IOException {
        if (!"opencode".equals(profile.harness())
                || !"implementer".equals(profile.role())
                || profile.readOnly()
                || !"direct".equals(profile.runner())
                || profile.harnessExecutable() == null
                || profile.apiConnection() == null
                || !profile.apiConnection().enabled()
                || profile.apiSettings().home() == null
                || !profile.enforceSchema()
                || profile.maxOutputTokens() <= 0
                || profile.promptTemplate() == null
                || profile.jsonSchema() == null) return false;
        Path binary = profile.harnessExecutable();
        if (!binary.isAbsolute() || !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS))
            return false;
        for (String reference : new String[] {profile.promptTemplate(), profile.jsonSchema()}) {
            Path resource = Path.of(reference);
            if (!resource.isAbsolute()) resource = profile.apiSettings().home().resolve(resource);
            if (!Files.isRegularFile(resource, LinkOption.NOFOLLOW_LINKS)) return false;
            // A regular but unreadable resource must not authorize an "unavailable" digest.
            dev.warden.git.GitRepository.sha256(resource);
        }
        Connection current =
                new ConnectionStore(profile.apiSettings().home())
                        .versioned(profile.connectionRef())
                        .connection();
        return current.enabled()
                && current.fingerprint().equals(profile.apiConnection().fingerprint());
    }

    private static boolean matches(Profile profile, Map<String, Object> receipt)
            throws IOException {
        return eligibleShape(profile)
                && number(receipt.get("version"), 1L)
                && profile.name().equals(receipt.get("profile"))
                && profile.role().equals(receipt.get("role"))
                && profile.apiFingerprint().equals(receipt.get("profile_fingerprint"))
                && profile.apiConnection()
                        .fingerprint()
                        .equals(receipt.get("connection_fingerprint"))
                && profile.harnessExecutable()
                        .toRealPath()
                        .toString()
                        .equals(receipt.get("executable"))
                && binarySha256(profile.harnessExecutable()).equals(receipt.get("binary_sha256"));
    }

    private static boolean evidenceMatches(Profile profile, Map<String, Object> evidence) {
        if (evidence == null
                || !ADAPTER.equals(evidence.get("adapter"))
                || !SUPPORTED_VERSION.equals(evidence.get("opencode_version"))
                || !profile.model().equals(evidence.get("model_served"))
                || !profile.apiFingerprint().equals(evidence.get("profile_fingerprint"))
                || !profile.apiConnection()
                        .fingerprint()
                        .equals(evidence.get("connection_fingerprint"))
                || !number(evidence.get("max_requests"), MAX_REQUESTS)
                || !number(evidence.get("max_output_tokens"), outputLimit(profile))
                || !number(evidence.get("time_budget_seconds"), timeLimitSeconds(profile)))
            return false;
        return PROOFS.stream().allMatch(proof -> Boolean.TRUE.equals(evidence.get(proof)));
    }

    private static boolean number(Object value, long expected) {
        return value instanceof Number number && number.doubleValue() == expected;
    }

    public static String binarySha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0)
                    if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
