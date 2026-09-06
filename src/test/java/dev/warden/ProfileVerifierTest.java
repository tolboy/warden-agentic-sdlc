package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.UserConfig;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Verifying a vendor profile.
 *
 * `verified_on` is the only field in the configuration that records a human judgement rather
 * than a fact a machine established, and it is the gate in front of a role that writes to the
 * repository. These pin that the command removes the retyping and the hand-edited YAML without
 * removing the judgement: a probe that exits 0 is not a verification, and nothing is stamped
 * unless it is asked for separately.
 */
public final class ProfileVerifierTest implements Suite {

    @Override public String name() { return "profile verifier"; }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static String succeeds() { return WINDOWS ? "echo probe-ran" : "echo probe-ran"; }
    private static String fails() { return WINDOWS ? "exit /b 3" : "exit 3"; }

    @Override public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-verify-test-");
        try {
            Files.createDirectories(home.resolve("profiles"));
            ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner());

            // What an operator is told to go and do. The generic line sent one to check args
            // and credentials that were both already right: the vendor had refused the model
            // because the installed CLI was too old, and had said so in its own words.
            check.contains("a vendor that refuses the model names the CLI, not the profile",
                    ProfileVerifier.nextStep(new ProfileVerifier.Probe(false, "codex exec", 1,
                            false, "The 'gpt-6-astra' model requires a newer version of Codex.",
                            "", home, List.of())),
                    "Upgrade the vendor CLI");
            check.contains("and a refused credential is named as one",
                    ProfileVerifier.nextStep(new ProfileVerifier.Probe(false, "codex exec", 1,
                            false, "", "error: not logged in", home, List.of())),
                    "Sign the CLI in");
            check.contains("a probe killed by the wall clock names the wall clock",
                    ProfileVerifier.nextStep(new ProfileVerifier.Probe(false, "codex exec", 1,
                            true, "", "", home, List.of())),
                    "wall_clock_minutes");
            check.contains("and anything else keeps the answer that was always there",
                    ProfileVerifier.nextStep(new ProfileVerifier.Probe(false, "codex exec", 1,
                            false, "boom", "", home, List.of())),
                    "fix the profile's args");

            // --- a probe that works: reported, transcript kept, nothing stamped -------
            Path good = write(home, "good", succeeds(), null);
            Profile profile = load(good);
            ProfileVerifier.Probe probe = verifier.run(profile, home, Duration.ofSeconds(60));
            check.that("a probe that exits 0 is reported as passing", probe.ok());
            check.eq("with its exit code", 0, probe.exitCode());
            check.that("the transcript is kept beside the configuration",
                    Files.isRegularFile(probe.transcript()));
            check.contains("and holds what the vendor actually printed",
                    Files.readString(probe.transcript()), "probe-ran");
            check.contains("the transcript names the command it ran",
                    Files.readString(probe.transcript()), succeeds());
            check.that("the checklist the operator must read is carried through",
                    probe.whatToCheck().contains("that it exits without asking for approval"));
            check.that("running the probe alone stamps nothing", !load(good).verified());

            // --- stamping is a separate act, and then the resolver accepts it ---------
            ProfileVerifier.Stamp stamp = verifier.stamp(good, load(good), LocalDate.of(2026, 8, 27));
            check.that("confirming writes the date", stamp.written());
            check.eq("and says which date", "2026-08-27", stamp.date());
            Profile stamped = load(good);
            check.that("the profile now loads as verified", stamped.verified());
            check.eq("and nothing else about it changed", "somevendor", stamped.vendor());
            String text = Files.readString(good);
            check.contains("the operator's own comments survive the edit", text,
                    "# established by running it, not by reading the docs");
            check.contains("and so does the probe", text, succeeds());
            check.that("the date is appended to the block, not wedged above someone's comment",
                    text.indexOf("verified_on") > text.indexOf("what_to_check"));

            // --- a second confirm is refused rather than duplicating the key ----------
            ProfileVerifier.Stamp again = verifier.stamp(good, load(good), LocalDate.of(2026, 9, 1));
            check.that("an already verified profile is not stamped twice", !again.written());
            check.eq("and says why", "already_verified", again.code());

            // --- a failing probe is reported, and the caller must not stamp it --------
            Path bad = write(home, "bad", fails(), null);
            ProfileVerifier.Probe failed = verifier.run(load(bad), home, Duration.ofSeconds(60));
            check.that("a probe that exits non-zero is not a pass", !failed.ok());
            check.eq("and the exit code is kept", 3, failed.exitCode());
            check.that("its transcript exists too, because a failure is the diagnosable case",
                    Files.isRegularFile(failed.transcript()));

            // --- shapes the command refuses rather than guessing at --------------------
            Path noProbe = write(home, "noprobe", null, null);
            check.rejects("a profile with no probe has nothing to verify", "no verification.probe",
                    () -> verifier.run(load(noProbe), home, Duration.ofSeconds(30)));

            Path noBlock = home.resolve("profiles/noblock.yaml");
            Files.writeString(noBlock, """
                    version: 1
                    profile: noblock
                    role: reviewer
                    vendor: somevendor
                    command: echo
                    """);
            ProfileVerifier.Stamp missing = verifier.stamp(noBlock, load(noBlock), LocalDate.now());
            check.that("a profile with no verification block is not given one", !missing.written());
            check.eq("and the refusal is named", "no_verification_block", missing.code());
            check.contains("the operator is told to write the probe first",
                    missing.message(), "add one with a probe");
        } finally {
            deleteTree(home);
        }
    }

    private static Path write(Path home, String name, String probe, String verifiedOn) throws IOException {
        StringBuilder builder = new StringBuilder("""
                version: 1
                profile: %s
                role: reviewer
                vendor: somevendor
                # established by running it, not by reading the docs
                command: %s
                read_only: true

                verification:
                """.formatted(name, WINDOWS ? "cmd.exe" : "echo"));
        if (probe != null) {
            builder.append("  probe: '").append(probe).append("'\n");
            builder.append("""
                      what_to_check:
                        - "that it exits without asking for approval"
                        - "which envelope key carries the answer"
                    """);
        } else {
            builder.append("  note: \"nothing to run yet\"\n");
        }
        if (verifiedOn != null) builder.append("  verified_on: \"").append(verifiedOn).append("\"\n");
        Path file = home.resolve("profiles").resolve(name + ".yaml");
        Files.writeString(file, builder.toString());
        return file;
    }

    private static Profile load(Path file) throws IOException {
        return Profile.parse(Files.readString(file), file.toString());
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
