package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.UserConfig;
import dev.warden.execution.orca.OrcaProbe;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.FakeOrca;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

            // --- the answer is checked, not only the exit code ------------------------
            // A headless CLI whose tool call was auto-denied exited 0 with an empty response
            // and was stamped verified. With `expect`, the stamp needs the answer.
            Path answering = write(home, "answering", succeeds(), null, "probe-ran");
            ProfileVerifier.Probe answered = verifier.run(load(answering), home, Duration.ofSeconds(60));
            check.that("a probe whose output carries the expected answer passes", answered.ok());
            check.that("and says the answer was found", answered.answerFound());
            check.eq("the report names what was expected", "probe-ran",
                    ProfileVerifier.report(load(answering), answered).get("expected"));
            Path silent = write(home, "silent", succeeds(), null, "the-value-in-the-file");
            ProfileVerifier.Probe unanswered = verifier.run(load(silent), home, Duration.ofSeconds(60));
            check.that("a probe that exits 0 without the expected answer is not a pass", !unanswered.ok());
            check.eq("even though its exit code was 0", 0, unanswered.exitCode());
            check.that("and the report says the answer was missing", !unanswered.answerFound());
            check.contains("the operator is told the tool call was refused or the model guessed",
                    ProfileVerifier.nextStep(unanswered), "does not contain the expected answer");
            check.rejects("an empty expectation is refused rather than matching everything",
                    "verification.expect must be a non-empty string",
                    () -> load(write(home, "blank", succeeds(), null, "")));

            // --- the probe asks for the model the profile declares ----------------------
            // A probe that spelled `--model opus` kept testing the old model after `roster
            // model`, and the stamp went on the new one. {{model}} and {{effort}} are filled
            // from the profile, as the role's own args are.
            Path placeholder = home.resolve("profiles/placeholder.yaml");
            Files.writeString(placeholder, """
                    version: 1
                    profile: placeholder
                    role: reviewer
                    vendor: somevendor
                    model: m-55
                    command: echo
                    read_only: true
                    verification:
                      probe: 'echo asked-for-{{model}}-at-{{effort}}'
                      expect: "asked-for-m-55-at-"
                    """);
            ProfileVerifier.Probe filled = verifier.run(load(placeholder), home, Duration.ofSeconds(60));
            check.that("the probe is given the declared model", filled.ok());
            check.contains("and the report shows the command that ran",
                    filled.command(), "asked-for-m-55-at-");

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

            orcaChannelProbe(check, home);
        } finally {
            deleteTree(home);
        }
    }

    /**
     * The shipped Orca examples carried `what_to_check` and no probe, and Warden advised
     * `warden profiles --verify <twin> --confirm` for them, which refuses a profile with nothing
     * to run. They now name `warden probe orca`, which goes through worker-start and asks the
     * worker for a word Warden has just written into the worktree.
     */
    private void orcaChannelProbe(Check check, Path home) throws Exception {
        Path worktree = Files.createDirectories(home.resolve("orca worktree"));
        for (String example : List.of("claude-review.yaml", "codex-review-astra.yaml")) {
            Path shipped = Path.of("examples/orca").resolve(example);
            Profile profile = Profile.parse(Files.readString(shipped), shipped.toString());
            check.contains("the Orca example names the Orca channel probe: " + example,
                    String.valueOf(profile.verificationProbe()), "warden probe orca --agent " + profile.command());
            check.eq("and the answer it has to print: " + example, "probe-answer: matched",
                    profile.verificationExpect());

            FakeOrca orca = new FakeOrca(worktree);
            String[] asked = {null};
            orca.answering(args -> {
                String word = readProbeWord(worktree, orca);
                asked[0] = word;
                return FakeOrca.workerDone(orca.lastTask(), orca.lastDispatch(), Map.of("answer", word));
            });
            List<List<String>> ran = new java.util.ArrayList<>();
            ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner(), (argv, timeout) -> {
                ran.add(argv);
                var outcome = new OrcaProbe(orca.client()).run(OrcaProbe.parse(argv.toArray(String[]::new)));
                return new ProcessRunner.Result(argv, outcome.exitCode(), false, 0L,
                        String.join("\n", outcome.lines()), "", false, false);
            });
            ProfileVerifier.Probe probe = verifier.run(profile, home, Duration.ofSeconds(60), worktree);
            check.that("the example passes --verify through the Orca channel: " + example, probe.ok());
            check.that("and was run by Warden itself, not a shell looking for `warden` on PATH",
                    ran.size() == 1 && ran.get(0).get(0).equals("probe"));
            check.eq("with the directory --verify was asked from, spaces and all", worktree.toString(),
                    FakeOrca.option(ran.get(0), "--worktree"));
            List<String> start = orca.calls("orchestration worker-start").getFirst();
            check.eq("the worker gets the profile's agent", profile.command(), FakeOrca.option(start, "--agent"));
            check.eq("model", profile.model(), FakeOrca.option(start, "--model"));
            check.eq("and effort", profile.effort(), FakeOrca.option(start, "--effort"));
            check.that("the worker was asked a word, not told one",
                    !FakeOrca.option(orca.calls("orchestration task-create").getFirst(), "--spec")
                            .contains(String.valueOf(asked[0])));
            check.eq("it is released afterwards", 1, orca.calls("orchestration worker-release").size());
            check.eq("and its coordinator closed", 1, orca.calls("terminal close").size());
            try (var left = Files.list(worktree)) {
                check.eq("the word file is gone", 0L, left.count());
            }
        }

        FakeOrca guessing = new FakeOrca(worktree);
        guessing.answering(args -> FakeOrca.workerDone(guessing.lastTask(), guessing.lastDispatch(),
                Map.of("answer", "WARDEN-DEMO")));
        OrcaProbe.Outcome wrong = new OrcaProbe(guessing.client())
                .run(OrcaProbe.parse(new String[] {"probe", "orca", "--agent", "claude", "--worktree",
                        worktree.toString()}));
        check.eq("an answer that is not the word fails", 1, wrong.exitCode());
        check.that("and says so rather than matched",
                wrong.lines().stream().anyMatch(line -> line.startsWith("probe-answer: mismatched")));
        check.eq("the worker that guessed is stopped", 1, guessing.calls("orchestration worker-stop").size());

        FakeOrca elsewhere = new FakeOrca(worktree).notAWorktree();
        OrcaProbe.Outcome outside = new OrcaProbe(elsewhere.client())
                .run(OrcaProbe.parse(new String[] {"probe", "orca", "--agent", "codex", "--worktree",
                        worktree.toString()}));
        check.eq("outside an Orca worktree nothing starts", 0, elsewhere.calls("orchestration worker-start").size());
        check.that("and the reason is printed",
                outside.lines().stream().anyMatch(line -> line.contains("not a worktree Orca manages")));

        check.eq("an empty model stays an empty argument, not the next flag's name",
                List.of("probe", "orca", "--agent", "grok", "--model", "", "--effort", "", "--worktree", "w"),
                ProfileVerifier.ownProbe("warden probe orca --agent grok --model {{model}} --effort {{effort}}"
                        + " --worktree {{worktree}}", Profile.parse("""
                                version: 1
                                profile: g
                                role: implementer
                                vendor: grok
                                command: grok
                                runner: orca
                                read_only: false
                                """, "g.yaml"), "w"));
        check.eq("any other probe still goes to the shell", null,
                ProfileVerifier.ownProbe("claude -p ok", Profile.parse("""
                        version: 1
                        profile: c
                        role: reviewer
                        vendor: claude
                        command: claude
                        """, "c.yaml"), "w"));
    }

    /** What the probe wrote for the worker: the one word in its answer file. */
    private static String readProbeWord(Path worktree, FakeOrca orca) {
        try (var entries = Files.list(worktree)) {
            Path fixture = entries.filter(path -> path.getFileName().toString().startsWith(".warden-probe-"))
                    .findFirst().orElseThrow();
            String text = Files.readString(fixture.resolve("answer.txt"));
            return text.replace("The probe word is ", "").replace(".", "").strip();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static Path write(Path home, String name, String probe, String verifiedOn) throws IOException {
        return write(home, name, probe, verifiedOn, null);
    }

    private static Path write(Path home, String name, String probe, String verifiedOn, String expect)
            throws IOException {
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
        if (expect != null) builder.append("  expect: \"").append(expect).append("\"\n");
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
