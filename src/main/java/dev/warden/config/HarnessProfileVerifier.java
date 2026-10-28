package dev.warden.config;

import dev.warden.execution.RoleExecutor;
import dev.warden.execution.VerifiedOpenCodeExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Native coding capability verification through exactly the executor used by the loop. */
public final class HarnessProfileVerifier {
    public ProfileVerifier.Probe run(Profile profile, Path home, Duration timeout, Path worktree)
            throws IOException, InterruptedException {
        if (profile.harnessExecutable() == null
                || !"implementer".equals(profile.role())
                || profile.readOnly()
                || !profile.enforceSchema())
            throw new IllegalStateException(
                    "harness coding verification requires an explicit executable and implementer"
                            + " schema");
        String fingerprint = profile.apiFingerprint();
        Path fixture =
                Files.createTempDirectory(
                        worktree.toAbsolutePath().normalize(), "warden-profile-probe-");
        Path run = Files.createDirectories(fixture.resolve(".warden/verification"));
        Path source = fixture.resolve("greeting.mjs"), test = fixture.resolve("greeting.test.mjs");
        Files.writeString(source, "export function greet(name) { return 'Hello'; }\n");
        String sourceHash = CodingAttestation.binarySha256(source);
        Files.writeString(
                test,
                "import assert from 'node:assert/strict';\n"
                        + "import {greet} from './greeting.mjs';\n"
                        + "assert.equal(greet('Gonka'), 'Hello, Gonka!');\n"
                        + "assert.equal(greet('Warden'), 'Hello, Warden!');\n"
                        + "console.log('WARDEN_CODING_GATE_OK');\n");
        String testHash = CodingAttestation.binarySha256(test);
        ProcessRunner process = new ProcessRunner();
        var initial =
                process.run(
                        List.of("node", "greeting.test.mjs"),
                        fixture,
                        Duration.ofSeconds(15),
                        8192);
        if (initial.ok()) throw new IOException("native coding fixture was not initially failing");
        Path prompt = run.resolve("prompt.md");
        Files.writeString(
                prompt,
                "This is a Warden native coding probe. Task ID coding-probe, run ID coding-probe."
                    + " Use the read tool to read greeting.mjs and greeting.test.mjs before"
                    + " editing. Fix only greeting.mjs so greet(name) returns Hello, NAME! Do not"
                    + " change greeting.test.mjs. Run node greeting.test.mjs with the bash tool. Do"
                    + " not commit or edit any other source. Use the write tool to save the final"
                    + " JSON artifact required by the dispatch instructions and the profile schema."
                    + " Set role implementer, task_id coding-probe, run_id coding-probe, status"
                    + " completed, files_changed [greeting.mjs], commands_run [node"
                    + " greeting.test.mjs], remaining_risks [].\n");
        Path schema = Path.of(profile.jsonSchema());
        if (!schema.isAbsolute()) schema = home.resolve(schema);
        RoleExecutor.Result result;
        try {
            result =
                    VerifiedOpenCodeExecutor.verification(process)
                            .execute(
                                    new RoleExecutor.Request(
                                            "coding-probe",
                                            "coding-probe",
                                            "implementer",
                                            profile.forApiProbe(),
                                            null,
                                            null,
                                            fixture,
                                            run,
                                            prompt,
                                            schema,
                                            "",
                                            "probe",
                                            List.of()));
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failed) {
            throw new IOException("native coding runtime failed before recording a safe result");
        }
        Map<String, Object> evidence = new LinkedHashMap<>(result.evidence());
        boolean intact = testHash.equals(CodingAttestation.binarySha256(test));
        var gate =
                process.run(
                        List.of("node", "greeting.test.mjs"),
                        fixture,
                        Duration.ofSeconds(15),
                        8192);
        boolean gatePassed = intact && gate.ok() && gate.stdout().contains("WARDEN_CODING_GATE_OK");
        evidence.put("independent_gate_passed", gatePassed);
        evidence.put("test_unchanged", intact);
        evidence.put("fixture", fixture.toString());
        boolean edited = !sourceHash.equals(CodingAttestation.binarySha256(source));
        evidence.put("source_changed", edited);
        boolean tools = false;
        if (evidence.get("relay") instanceof Map<?, ?> relay
                && relay.get("observed_tool_results") instanceof List<?> observed)
            tools = observed.contains("read") && observed.contains("bash") && edited;
        evidence.put("coding_tools_verified", tools);
        boolean unchanged = fingerprint.equals(profile.apiFingerprint());
        Profile current = UserConfig.load(home).profiles().get(profile.name());
        unchanged = unchanged && current != null && fingerprint.equals(current.apiFingerprint());
        boolean passed = result.ok() && gatePassed && tools && unchanged;
        evidence.put("ok", passed);
        evidence.put("profile_fingerprint", fingerprint);
        evidence.put(
                "code",
                passed
                        ? "coding_capability_verified"
                        : !unchanged
                                ? "role_harness_profile_changed"
                                : result.ok() ? "coding_fixture_unproven" : result.code());
        evidence.put("verification_scope", "implementer_tools_artifact");
        if (passed) CodingAttestation.write(current, evidence);
        Path transcript =
                home.resolve("verification").resolve(profile.name() + "-coding-probe.json");
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, Json.write(evidence), StandardCharsets.UTF_8);
        return new ProfileVerifier.Probe(
                passed,
                "warden profiles --verify " + profile.name(),
                passed ? 0 : 1,
                "role_timeout".equals(result.code()),
                Json.write(evidence),
                "",
                transcript,
                List.of(
                        "Same executor, bounded relay, read/edit/bash tools, unchanged test and"
                                + " schema-valid artifact"));
    }
}
