package dev.warden.config;

import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a profile's own verification probe and stamps the date it passed.
 *
 * `verified_on` was once described as a human judgement — that somebody read the transcript
 * against `what_to_check` before stamping. The tool never enforced that and could not: the
 * stamp was a command, and whoever typed it was trusted to have read. A field whose stated
 * meaning cannot be held is worse than a plain fact, because everyone downstream relies on a
 * guarantee that is not there.
 *
 * It now records what it can check: this probe ran on this date and passed. That is also what
 * the resolver needs — nothing is dispatched whose flags have never been executed — and it
 * makes changing the vendor behind a role an edit and one command rather than a ceremony.
 *
 * `what_to_check` and the kept transcript are still printed, because reading them is still
 * how an operator learns which envelope key carries the answer and whether a cost appears at
 * all. What is gone is the claim that the date proves they did.
 */
public final class ProfileVerifier {

    public record Probe(boolean ok, String command, int exitCode, boolean timedOut,
                        String stdoutTail, String stderrTail, Path transcript,
                        List<String> whatToCheck) {}

    /** Why a stamp was refused, or the file it was written to. */
    public record Stamp(boolean written, String code, String message, Path profileFile, String date) {}

    private final ProcessRunner processes;

    public ProfileVerifier(ProcessRunner processes) { this.processes = processes; }

    public Probe run(Profile profile, Path home, Duration timeout) throws IOException, InterruptedException {
        String command = profile.verificationProbe();
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("profile '" + profile.name() + "' declares no "
                    + "verification.probe, so there is nothing to run. Add the exact command that "
                    + "would settle whether this vendor works, then verify it.");
        }
        ProcessRunner.Result result = processes.run(shell(command), home, timeout, 256 * 1024);

        Path directory = home.resolve("verification");
        Files.createDirectories(directory);
        Path transcript = directory.resolve(profile.name() + ".txt");
        Files.writeString(transcript, """
                probe: %s
                at: %s
                exit_code: %d
                timed_out: %s

                --- stdout ---
                %s
                --- stderr ---
                %s
                """.formatted(command, java.time.Instant.now(), result.exitCode(), result.timedOut(),
                result.stdout(), result.stderr()), StandardCharsets.UTF_8);

        return new Probe(result.ok(), command, result.exitCode(), result.timedOut(),
                tail(result.stdout()), tail(result.stderr()), transcript, profile.verificationChecks());
    }

    /**
     * Writes `verified_on` into the profile file.
     *
     * Text editing rather than a YAML round trip on purpose: Warden reads a strict subset of
     * YAML and does not write it, and re-emitting a file the operator wrote by hand would
     * silently drop their comments — which in these profiles carry the flags that were
     * established by running them, and are the most valuable thing in the file.
     */
    public Stamp stamp(Path profileFile, Profile profile, LocalDate date) throws IOException {
        if (profile.verified()) {
            return new Stamp(false, "already_verified",
                    "profile '" + profile.name() + "' already carries verification.verified_on",
                    profileFile, null);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(profileFile, StandardCharsets.UTF_8));
        int header = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).stripTrailing().equals("verification:")) { header = index; break; }
        }
        if (header < 0) {
            return new Stamp(false, "no_verification_block",
                    "no `verification:` block at the start of a line in " + profileFile
                            + "; add one with a probe rather than having Warden invent its shape",
                    profileFile, null);
        }
        // Match the indentation the operator already used inside the block, and append at its
        // end rather than at its top: a comment sits above the key it explains, so inserting
        // after the header would file the date under somebody else's explanation.
        String indent = "  ";
        int insertAt = header + 1;
        for (int index = header + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            int width = line.length() - line.stripLeading().length();
            if (width == 0) break;
            if (insertAt == header + 1) indent = line.substring(0, width);
            insertAt = index + 1;
        }
        String stamped = date.toString();
        lines.add(insertAt, indent + "verified_on: \"" + stamped + "\"");
        Files.writeString(profileFile, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return new Stamp(true, "verified", "verification.verified_on set to " + stamped,
                profileFile, stamped);
    }

    /** The report both paths print, so the evidence looks the same whether or not it stamped. */
    public static Map<String, Object> report(Profile profile, Probe probe) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile.name());
        result.put("vendor", profile.vendor());
        result.put("role", profile.role());
        result.put("probe", probe.command());
        result.put("exit_code", (long) probe.exitCode());
        result.put("timed_out", probe.timedOut());
        result.put("probe_ok", probe.ok());
        result.put("transcript", probe.transcript().toString());
        result.put("stdout_tail", probe.stdoutTail());
        result.put("stderr_tail", probe.stderrTail());
        result.put("what_to_check", probe.whatToCheck());
        return result;
    }

    private static List<String> shell(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    private static String tail(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= 1500 ? trimmed : trimmed.substring(trimmed.length() - 1500);
    }
}
