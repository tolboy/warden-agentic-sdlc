package dev.warden;

import dev.warden.config.CodingAttestation;
import dev.warden.config.Connection;
import dev.warden.config.ConnectionStore;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.config.Workflow;
import dev.warden.json.Json;
import dev.warden.role.RoleContract;
import dev.warden.role.RoleResolver;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evidence must track the inputs again, even for a cached bound profile. No native/provider calls.
 */
public final class CodingAttestationTest implements Suite {
    @Override
    public String name() {
        return "coding-attestation";
    }

    @Override
    public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-coding-attestation-");
        try {
            Path binary = home.resolve("native-opencode.exe");
            Files.writeString(binary, "synthetic pinned native executable");
            Files.createDirectories(home.resolve("prompts"));
            Files.createDirectories(home.resolve("schemas"));
            Files.writeString(
                    home.resolve("prompts/implementer.md"),
                    "Implement the fixture and write its artifact.");
            Files.writeString(home.resolve("schemas/implementer.json"), "{\"type\":\"object\"}");
            Connection connection =
                    new Connection(
                            "local-api",
                            "custom",
                            "openai_chat",
                            "http://127.0.0.1:9/v1",
                            null,
                            true);
            new ConnectionStore(home).add(connection);
            String yaml = yaml(binary);
            Profile draft = Profile.parse(yaml, "coding").withConnection(connection, home);
            String stamped = yaml.replace("pending", draft.apiFingerprint());
            Profile profile = Profile.parse(stamped, "coding").withConnection(connection, home);
            check.that(
                    "a hand-written date and fingerprint cannot enable a coding profile",
                    !profile.verified());
            check.eq(
                    "the explicit harness path survives connection binding",
                    binary,
                    profile.harnessExecutable());
            check.eq(
                    "an explicit native probe retains the executable without authorizing routing",
                    binary,
                    profile.forApiProbe().harnessExecutable());
            check.that(
                    "an explicit native probe remains unverified",
                    !profile.forApiProbe().verified());
            Policy policy =
                    Policy.parse(
                            "version: 1\n"
                                    + "roles:\n"
                                    + "  implementer: {profiles: [live-coding], strategy: first,"
                                    + " require_independent_vendor: true}\n",
                            "policy");
            check.eq(
                    "resolver rejects an unproven profile",
                    "harness_coding_unproven",
                    verdict(profile, policy));

            Map<String, Object> evidence = proof(profile);
            Map<String, Object> incomplete = new LinkedHashMap<>(evidence);
            incomplete.put("artifact_schema_verified", false);
            check.rejects(
                    "an unvalidated role artifact cannot create capability evidence",
                    "incomplete",
                    () -> CodingAttestation.write(profile, incomplete));
            Map<String, Object> wrongBinary = new LinkedHashMap<>(evidence);
            wrongBinary.put("binary_sha256", "0".repeat(64));
            check.rejects(
                    "verification must describe the current executable",
                    "executable changed",
                    () -> CodingAttestation.write(profile, wrongBinary));

            Path receipt = CodingAttestation.write(profile, evidence);
            check.that(
                    "complete native proof enables its exact stamped profile", profile.verified());
            check.eq(
                    "the verified coding implementer can enter role resolution",
                    null,
                    verdict(profile, policy));
            Profile shorter = profile.withWallClockMinutes(1);
            check.that("a tighter dispatch deadline preserves admission", shorter.verified());
            check.eq(
                    "the dispatch retains its shorter effective deadline",
                    1L,
                    shorter.wallClockMinutes());
            check.eq(
                    "the configured deadline remains the attested identity",
                    3L,
                    shorter.declaredWallClockMinutes());
            check.eq(
                    "a tighter call keeps the configured verification time cap",
                    180L,
                    CodingAttestation.timeLimitSeconds(shorter));
            check.eq(
                    "a tighter call retains the profile fingerprint",
                    profile.apiFingerprint(),
                    shorter.apiFingerprint());
            check.eq(
                    "rebinding a shorter call retains its declared identity",
                    profile.apiFingerprint(),
                    shorter.withConnection(connection, home).apiFingerprint());
            check.eq(
                    "a repeated override cannot widen the effective harness deadline",
                    1L,
                    shorter.withWallClockMinutes(10).wallClockMinutes());
            String receiptText = Files.readString(receipt);
            UserConfig user =
                    new UserConfig(
                            home,
                            policy,
                            Map.of(profile.name(), profile),
                            Map.of(),
                            true,
                            Map.of(connection.id(), connection));
            Workflow.Stage stage =
                    new Workflow.Stage(
                            "implement",
                            Workflow.Kind.ROLE,
                            "implementer",
                            java.util.List.of("always"),
                            "stop",
                            "stop",
                            false,
                            "implementer",
                            null);
            Map<String, Object> originalContract =
                    RoleContract.of(
                            stage.name(),
                            stage.role(),
                            policy.roles().get("implementer"),
                            profile,
                            user);
            originalContract.putAll(RoleContract.routingOf(stage));
            check.that(
                    "a receipt names executable identity and profile contract",
                    receiptText.contains("binary_sha256")
                            && receiptText.contains(profile.apiFingerprint()));

            Files.writeString(binary, "a different binary, retaining the cached Profile object");
            check.that("binary replacement invalidates a cached profile", !profile.verified());
            check.eq(
                    "binary replacement immediately removes routing eligibility",
                    "harness_coding_unproven",
                    verdict(profile, policy));
            CodingAttestation.write(profile, proof(profile));
            check.that(
                    "explicit verification can admit the replacement at the same path",
                    profile.verified());
            Map<String, Object> replacementContract =
                    RoleContract.of(
                            stage.name(),
                            stage.role(),
                            policy.roles().get("implementer"),
                            profile,
                            user);
            check.that(
                    "continuation snapshots retain a distinct executable digest after"
                        + " reverification",
                    !java.util.Objects.equals(
                            originalContract.get("harness_executable_sha256"),
                            replacementContract.get("harness_executable_sha256")));
            check.contains(
                    "reverification cannot reuse the prior binary's continuation contract",
                    RoleContract.differenceFrom(originalContract, stage, user),
                    "harness_executable_sha256 changed");
            Files.writeString(receipt, receiptText);
            Files.writeString(binary, "synthetic pinned native executable");
            check.that(
                    "restored identical binary preserves the original proof", profile.verified());
            Files.writeString(home.resolve("prompts/implementer.md"), "Changed role instructions");
            check.that("prompt content changes invalidate a cached profile", !profile.verified());
            check.rejects(
                    "old runtime proof cannot attest changed resources",
                    "incomplete",
                    () -> CodingAttestation.write(profile, evidence));
            Files.writeString(
                    home.resolve("prompts/implementer.md"),
                    "Implement the fixture and write its artifact.");
            Files.writeString(home.resolve("schemas/implementer.json"), "{\"type\":\"array\"}");
            check.that("schema content changes invalidate a cached profile", !profile.verified());
            Files.writeString(home.resolve("schemas/implementer.json"), "{\"type\":\"object\"}");
            Files.delete(home.resolve("schemas/implementer.json"));
            check.that(
                    "a missing schema cannot match unavailable resource sentinels",
                    !profile.verified());
            Files.writeString(home.resolve("schemas/implementer.json"), "{\"type\":\"object\"}");
            Files.writeString(
                    home.resolve("connections/local-api.yaml"),
                    connection.withEnabled(false).toYaml());
            check.that("disabling a connection invalidates a cached profile", !profile.verified());
            Files.writeString(home.resolve("connections/local-api.yaml"), connection.toYaml());
            check.that(
                    "unchanged restored connection can use its existing proof", profile.verified());

            Profile changedCap =
                    Profile.parse(
                                    stamped.replace(
                                            "max_output_tokens: 2048", "max_output_tokens: 1024"),
                                    "changed-cap")
                            .withConnection(connection, home);
            check.that("changing the output cap requires verification", !changedCap.verified());
            Profile changedDeadline =
                    Profile.parse(
                                    stamped.replace(
                                            "wall_clock_minutes: 3", "wall_clock_minutes: 4"),
                                    "changed-time")
                            .withConnection(connection, home);
            check.that(
                    "changing the declared time bound requires verification",
                    !changedDeadline.verified());
            Profile reader =
                    Profile.parse(
                                    stamped.replace("role: implementer", "role: reviewer")
                                            .replace("read_only: false", "read_only: true"),
                                    "reader")
                            .withConnection(connection, home);
            check.that("implementer proof cannot authorize a read-only judge", !reader.verified());

            Map<String, Object> changed = Json.parseObject(receiptText);
            changed.put("adapter", "other-adapter");
            Files.writeString(receipt, Json.write(changed));
            check.that(
                    "a different adapter receipt cannot authorize this profile",
                    !profile.verified());
            Files.writeString(receipt, receiptText);
            check.that(
                    "a receipt without YAML verification date still cannot authorize routing",
                    !Profile.parse(
                                    stamped.replace("  verified_on: '2026-10-27'\n", ""),
                                    "unstamped")
                            .withConnection(connection, home)
                            .verified());
            Files.delete(receipt);
            check.that("removing the receipt revokes the cached profile", !profile.verified());

            check.rejects(
                    "an executable path is not accepted on an ordinary CLI profile",
                    "requires harness",
                    () ->
                            Profile.parse(
                                    yaml.replace("harness: opencode\n", "")
                                            .replace("connection: local-api\n", "")
                                            .replace(
                                                    "runner: direct\n",
                                                    "runner: direct\ncommand: synthetic\n"),
                                    "wrong-runner"));
            check.rejects(
                    "native coding profile requires an explicit output cap",
                    "explicit limits.max_output_tokens",
                    () ->
                            Profile.parse(
                                    yaml.replace("  max_output_tokens: 2048\n", ""),
                                    "missing-cap"));
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static String verdict(Profile profile, Policy policy) {
        return new RoleResolver()
                .candidates(
                        "implementer",
                        policy,
                        Map.of(profile.name(), profile),
                        RoleResolver.Writers.NONE,
                        ignored -> true,
                        Set.of())
                .getFirst()
                .rejected();
    }

    private static Map<String, Object> proof(Profile profile) throws Exception {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("adapter", CodingAttestation.ADAPTER);
        proof.put("binary_sha256", CodingAttestation.binarySha256(profile.harnessExecutable()));
        proof.put("opencode_version", CodingAttestation.SUPPORTED_VERSION);
        proof.put("model_served", profile.model());
        proof.put("profile_fingerprint", profile.apiFingerprint());
        proof.put("connection_fingerprint", profile.apiConnection().fingerprint());
        proof.put("max_requests", CodingAttestation.MAX_REQUESTS);
        proof.put("max_output_tokens", CodingAttestation.outputLimit(profile));
        proof.put("time_budget_seconds", CodingAttestation.timeLimitSeconds(profile));
        for (String name :
                Set.of(
                        "ok",
                        "coding_tools_verified",
                        "artifact_schema_verified",
                        "independent_gate_passed",
                        "artifact_valid",
                        "process_tree_settled")) proof.put(name, true);
        return proof;
    }

    private static String yaml(Path binary) {
        return """
        version: 1
        profile: live-coding
        role: implementer
        runner: direct
        harness: opencode
        harness_executable: '%s'
        connection: local-api
        model_vendor: z-ai
        model: zai-org/GLM-5.3-Flash
        read_only: false
        prompt_template: prompts/implementer.md
        json_schema: schemas/implementer.json
        enforce_schema: true
        limits:
          wall_clock_minutes: 3
          max_output_tokens: 2048
        verification:
          verified_on: '2026-10-27'
          connection_fingerprint: pending
        """
                .formatted(binary.toString().replace("'", "''"));
    }
}
