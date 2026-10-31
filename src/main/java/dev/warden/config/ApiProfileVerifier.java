package dev.warden.config;

import dev.warden.execution.ApiExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** An explicit bounded inference probe. It proves text/artifact transport, never coding tools. */
public final class ApiProfileVerifier {
    private final Function<String, String> environment;
    public ApiProfileVerifier() { this(System::getenv); }
    public ApiProfileVerifier(Function<String, String> environment) { this.environment = environment; }

    public ProfileVerifier.Probe run(Profile profile, Path home, Duration timeout) throws IOException {
        if (profile.apiConnection() == null) throw new IllegalArgumentException("API profile has no resolved connection");
        String checkedFingerprint = profile.apiFingerprint();
        Path directory = home.resolve("verification").resolve(profile.name() + "-api");
        Files.createDirectories(directory);
        Path schema = profile.jsonSchema() == null ? null : home.resolve(profile.jsonSchema()).normalize();
        String schemaText = schema == null ? "No additional schema."
                : Files.readString(schema, StandardCharsets.UTF_8);
        if (schemaText.length() > 64 * 1024) throw new IOException("API verification schema exceeds 64 KiB");
        String prompt = "This is a synthetic transport probe, not a review of a repository. Do not call tools. "
                + "Return only one valid JSON role artifact matching the schema below. "
                + "Use role=" + profile.role() + ", task_id=api-probe, run_id=api-probe. "
                + "Use a successful verdict (PASS), empty findings, and no claims of executed checks. "
                + "This only verifies text and structured artifact handling.\n" + schemaText;
        Path promptFile = directory.resolve("prompt.txt");
        Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
        Duration profileLimit = Duration.ofMinutes(profile.wallClockMinutes());
        Duration limit = timeout.compareTo(profileLimit) < 0 ? timeout : profileLimit;
        RoleExecutor.Result result;
        try {
            result = new ApiExecutor(environment, limit, null).execute(new RoleExecutor.Request(
                    "api-probe", "api-probe", profile.role(), profile.forApiProbe(), null, null,
                    directory, directory, promptFile, schema, "", "probe", List.of()));
        } catch (Exception failure) {
            // Transport code owns detailed sanitized diagnostics. No arbitrary exception text here.
            throw new IOException("API profile verification failed before a result was recorded");
        }
        Path transcript = directory.resolve("receipt.json");
        boolean unchanged = checkedFingerprint.equals(profile.apiFingerprint());
        boolean passed = result.ok() && unchanged;
        var receipt = new java.util.LinkedHashMap<String, Object>(result.evidence());
        receipt.put("ok", passed);
        receipt.put("code", unchanged ? result.code() : "role_api_profile_changed");
        receipt.put("profile_fingerprint", checkedFingerprint);
        receipt.put("verification_scope", "text_artifact_only");
        Files.writeString(transcript, Json.write(receipt), StandardCharsets.UTF_8);
        return new ProfileVerifier.Probe(passed, "warden profiles --verify " + profile.name(),
                passed ? 0 : 1, "role_api_timeout".equals(result.code()),
                Json.write(receipt), "", transcript, List.of("Text artifact transport only; no coding tools were verified"));
    }
}
