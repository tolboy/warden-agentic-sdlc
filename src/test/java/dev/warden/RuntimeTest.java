package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.gate.GateRunner;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class RuntimeTest implements Suite {
    @Override public String name() { return "runtime"; }

    @Override public void run(Check check) throws Exception {
        ProcessRunner runner = new ProcessRunner();
        ProcessRunner.Result captured = runner.run(shell("echo hello"), Path.of("."), Duration.ofSeconds(10), 1024);
        check.that("process succeeds", captured.ok());
        check.contains("stdout captured", captured.stdout(), "hello");

        ProcessRunner.Result truncated = runner.run(shell(longOutput()), Path.of("."), Duration.ofSeconds(10), 32);
        check.that("output is capped", truncated.stdoutTruncated());
        check.that("capture limit is respected", truncated.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32);
        ProcessRunner.Result timedOut = runner.run(shell(slowCommand()), Path.of("."), Duration.ofMillis(100), 1024);
        check.that("timeout settles", timedOut.timedOut());
        check.that("timed-out process is no longer successful", !timedOut.ok());
        stdinChecks(check, runner);

        Path repository = fixture();
        try {
            GitRepository git = new GitRepository(repository, runner);
            check.eq("merge-base resolves", 40, git.mergeBase("HEAD").length());
            String originalBase = git.mergeBase("HEAD");
            Files.writeString(repository.resolve("committed-outside.txt"), "committed outside\n");
            command(repository, "git", "add", "committed-outside.txt");
            command(repository, "git", "-c", "user.name=Warden Tests", "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "outside change");
            check.that("committed diff since merge-base is visible",
                    git.changedPaths(originalBase).contains("committed-outside.txt"));
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");
            check.eq("changed path found", Set.of("src/value.txt"), git.changedPaths());
            Files.writeString(repository.resolve("notes-keep.md"), "keep\n");
            GitRepository.WorkingTreeSnapshot snap = git.snapshotWorkingTree();
            check.that("a snapshot matches the tree it was taken from", git.matchesSnapshot(snap));
            Files.writeString(repository.resolve("src/value.txt"), "planner-clobber\n");
            check.that("and does not match after a later write", !git.matchesSnapshot(snap));
            Files.writeString(repository.resolve("src/planner-new.txt"), "new\n");
            Files.delete(repository.resolve("notes-keep.md"));
            git.restoreWorkingTree(snap);
            check.eq("a surgical restore keeps the operator's uncommitted edit",
                    "changed\n", Files.readString(repository.resolve("src/value.txt")));
            check.eq("and the untracked file that predates the snapshot",
                    "keep\n", Files.readString(repository.resolve("notes-keep.md")));
            check.that("and drops the file the later writer created",
                    !Files.exists(repository.resolve("src/planner-new.txt")));
            String headBeforeCommit = git.mergeBase("HEAD");
            GitRepository.WorkingTreeSnapshot beforePlannerCommit = git.snapshotWorkingTree();
            Files.writeString(repository.resolve("src/planner-committed.txt"), "from a commit\n");
            command(repository, "git", "add", "--", "src/planner-committed.txt");
            command(repository, "git", "-c", "user.name=Warden Tests", "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "wip");
            git.restoreWorkingTree(beforePlannerCommit);
            check.eq("a surgical restore undoes a commit the later writer made",
                    headBeforeCommit, git.mergeBase("HEAD"));
            check.that("and drops the file that commit added",
                    !Files.exists(repository.resolve("src/planner-committed.txt")));
            check.eq("without losing the operator's uncommitted edit",
                    "changed\n", Files.readString(repository.resolve("src/value.txt")));
            GitRepository.WorkingTreeSnapshot beforeStaged = git.snapshotWorkingTree();
            Files.writeString(repository.resolve("src/planner-staged.txt"), "staged only\n");
            command(repository, "git", "add", "--", "src/planner-staged.txt");
            git.restoreWorkingTree(beforeStaged);
            check.that("a surgical restore drops a staged-but-uncommitted addition",
                    !Files.exists(repository.resolve("src/planner-staged.txt")));
            ProcessRunner.Result stillStaged = runner.run(
                    List.of("git", "diff", "--cached", "--name-only", "--", "src/planner-staged.txt"),
                    repository, Duration.ofSeconds(30));
            check.that("and clears it from the index", stillStaged.stdout().isBlank());
            check.eq("still without losing the operator's uncommitted edit",
                    "changed\n", Files.readString(repository.resolve("src/value.txt")));
            Files.delete(repository.resolve("notes-keep.md"));
            check.eq("scope accepts real child", List.of(), git.outsideScope(git.changedPaths(), List.of("src")));
            check.eq("scope rejects prefix collision", List.of("src/value.txt"),
                    git.outsideScope(git.changedPaths(), List.of("sr")));

            ConfigLoader.Loaded loaded = new ConfigLoader().load(repository, "smoke");
            GateRunner.Outcome baseline = new GateRunner(runner)
                    .runBaseline(loaded, "test-baseline", null, null);
            check.that("the pre-agent baseline runs through the real gate process", baseline.ok());
            check.eq("baseline reports its distinct phase", "baseline", baseline.data().get("phase"));
            check.eq("baseline runs only its project-health command", "echo baseline-ok",
                    ((java.util.Map<?, ?>) ((java.util.List<?>) baseline.data().get("commands")).get(0))
                            .get("command"));
            check.eq("baseline does not run the task command", 1,
                    ((java.util.List<?>) baseline.data().get("commands")).size());
            check.eq("baseline evidence has its own report name", "baseline-gate.json",
                    baseline.report().getFileName().toString());
            check.contains("and its own append-only ledger event",
                    Files.readString(repository.resolve(".warden/runs/test-baseline/evidence.jsonl")),
                    "\"type\":\"baseline_gate\"");

            Path projectContract = repository.resolve(".warden/project.yaml");
            Path source = repository.resolve("src/value.txt");
            String originalContract = Files.readString(projectContract);
            String originalSource = Files.readString(source);
            try {
                Files.writeString(projectContract, originalContract.replace(
                        "\"echo baseline-ok\"",
                        "\"echo baseline-mutated >> src/value.txt\""));
                GateRunner.Outcome mutatingBaseline = new GateRunner(runner).runBaseline(
                        new ConfigLoader().load(repository, "smoke"),
                        "test-mutating-baseline", null, null);
                check.eq("a baseline command may not alter even in-scope project source",
                        "baseline_mutated_source", mutatingBaseline.code());
                check.that("the mutating baseline still leaves a failure report",
                        Files.isRegularFile(mutatingBaseline.report()));
            } finally {
                Files.writeString(source, originalSource);
                Files.writeString(projectContract, originalContract);
            }
            GateRunner.Outcome passed = new GateRunner(runner).run(loaded, "test-pass");
            check.that("machine gate passes in-scope change", passed.ok());
            check.that("machine gate report written", Files.isRegularFile(passed.report()));
            check.that("ignored-path limitation disclosed", passed.data().containsKey("does_not_cover"));
            check.that("managed run evidence does not poison later blast radius",
                    git.changedPaths().stream().noneMatch(path -> path.startsWith(".warden/runs/")));
            Files.writeString(repository.resolve(".warden/tasks/extra.yaml"), "version: 1\nid: extra\ngoal: g\nscope: code\n");
            check.that("untracked Warden contract files remain observable",
                    git.changedPaths().contains(".warden/tasks/extra.yaml"));

            // A project whose `.warden` is not committed — which is every project the day
            // `warden do` created it — must not fail its own next task because a sibling task
            // contract exists on disk and is outside the task's source scope.
            java.util.Map<String, String> pinned =
                    dev.warden.config.WardenTree.snapshot(repository);
            GateRunner.Outcome withSibling = new GateRunner(runner)
                    .run(loaded, "test-sibling-contract", pinned, null);
            check.that("an unchanged sibling contract is not a blast-radius violation",
                    withSibling.ok());

            // Changed, though, and it is the terms of the run moving under it.
            Files.writeString(repository.resolve(".warden/tasks/extra.yaml"),
                    "version: 1\nid: extra\ngoal: something else\nscope: code\n");
            GateRunner.Outcome mutated = new GateRunner(runner)
                    .run(loaded, "test-config-mutated", pinned, null);
            check.eq("editing any Warden config mid-run fails closed",
                    "contract_mutated", mutated.code());
            check.contains("and the report names the exact file and what happened to it",
                    String.valueOf(mutated.data().get("configuration_changed")),
                    ".warden/tasks/extra.yaml (modified)");

            Files.delete(repository.resolve(".warden/tasks/extra.yaml"));
            GateRunner.Outcome removed = new GateRunner(runner)
                    .run(loaded, "test-config-removed", pinned, null);
            check.eq("so does deleting one", "contract_mutated", removed.code());
            check.contains("and that is reported as a removal, not a modification",
                    String.valueOf(removed.data().get("configuration_changed")), "(removed)");

            // Two questions, two scopes. A read-only role writing a task file is a violation,
            // so `.warden` is in that fingerprint. A human accepting a candidate is accepting
            // a source change, so it is not in that one — a run whose own log lived under
            // `.warden` invalidated its own acceptance the moment the log was written.
            String base = git.mergeBase("HEAD");
            String beforeSource = git.sourceFingerprint(base);
            String beforeAll = git.fingerprint(base);
            Files.writeString(repository.resolve(".warden/operator-note.txt"), "written mid-run\n");
            check.eq("a change under .warden leaves the candidate fingerprint alone",
                    beforeSource, git.sourceFingerprint(base));
            check.that("but the read-only fingerprint still sees it",
                    !beforeAll.equals(git.fingerprint(base)));
            Files.writeString(repository.resolve("src/value.txt"), "changed again\n");
            check.that("and a real source edit moves the candidate fingerprint",
                    !beforeSource.equals(git.sourceFingerprint(base)));
            Files.delete(repository.resolve(".warden/operator-note.txt"));
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");

            // The same question for a *tracked* file under `.warden`, which is the case that
            // actually broke. The check above passed for the wrong reason: an untracked file
            // never appears in `git diff --raw` at all, so filtering the path list was enough
            // for it. A tracked one does appear, the raw shape was not filtered, and so adding
            // the `land:` block that `warden land` itself asks for when it has nowhere to push
            // made that same command refuse the accepted run as `candidate_changed`.
            Files.writeString(repository.resolve(".warden/land-note.txt"), "committed config\n");
            command(repository, "git", "add", "--", ".warden/land-note.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "a tracked file under .warden");
            String beforeTracked = git.sourceFingerprint(base);
            String beforeTrackedAll = git.fingerprint(base);
            Files.writeString(repository.resolve(".warden/land-note.txt"),
                    "land:\n  remote: origin\n  base: main\n");
            check.eq("editing a tracked file under .warden leaves the candidate fingerprint alone",
                    beforeTracked, git.sourceFingerprint(base));
            check.that("while the read-only fingerprint still sees that edit",
                    !beforeTrackedAll.equals(git.fingerprint(base)));
            Files.writeString(repository.resolve(".warden/land-note.txt"), "committed config\n");

            // `.warden/runs` is the exception inside that exception: it is not configuration,
            // it is what Warden writes while the call it is guarding is still running - the
            // rotation counter when the profile was resolved, the corpus delivery status, the
            // run directory itself. Tracking it is the documented default (the .gitignore
            // `warden init` writes un-ignores *.json under runs/), and until this held, every
            // read-only role in such a project was convicted of mutating the worktree.
            // Measured on run bakery-2 of the Crumb Raiders trial, 2026-09-19.
            Files.createDirectories(repository.resolve(".warden/runs/some-run"));
            Files.writeString(repository.resolve(".warden/runs/rotation.json"), "{}\n");
            command(repository, "git", "add", "--", ".warden/runs/rotation.json");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "tracked run evidence");
            String beforeEvidence = git.fingerprint(base);
            String beforeEvidenceSource = git.sourceFingerprint(base);
            Files.writeString(repository.resolve(".warden/runs/rotation.json"), "{\"reviewer\": 2}\n");
            Files.writeString(repository.resolve(".warden/runs/some-run/role-planner.json"), "{}\n");
            check.eq("Warden's own evidence does not move the read-only fingerprint",
                    beforeEvidence, git.fingerprint(base));
            check.eq("nor the candidate fingerprint",
                    beforeEvidenceSource, git.sourceFingerprint(base));
            Files.writeString(repository.resolve(".warden/config-note.txt"), "a real config edit\n");
            check.that("while a change to configuration under .warden still moves it",
                    !beforeEvidence.equals(git.fingerprint(base)));
            Files.delete(repository.resolve(".warden/config-note.txt"));
            Files.writeString(repository.resolve(".warden/runs/rotation.json"), "{}\n");
            Files.delete(repository.resolve(".warden/runs/some-run/role-planner.json"));

            // Committing the accepted change must not invalidate the acceptance. The candidate
            // is the content, not where it is sitting: measured on run torch-2, where
            // `warden land --commit` made the commit and `warden land --push` then refused it
            // as `candidate_changed`, because `git diff --raw` reports a zeroed destination
            // blob while a change is uncommitted and a real one once it is not.
            String beforeCommit = git.sourceFingerprint(base);
            command(repository, "git", "add", "--", "src/value.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid",
                    "commit", "-m", "land the accepted change");
            check.eq("committing the accepted change leaves the candidate fingerprint alone",
                    beforeCommit, git.sourceFingerprint(base));

            // The raw line is still there for a reason: a deletion has no bytes to hash, so
            // only the status letter can carry it.
            Files.delete(repository.resolve("src/value.txt"));
            check.that("while deleting the file still moves it",
                    !beforeCommit.equals(git.sourceFingerprint(base)));
            // Put it back exactly as committed, so the fingerprint returns to where it was —
            // which is the other half of the property: content decides, nothing else.
            Files.writeString(repository.resolve("src/value.txt"), "changed\n");
            check.eq("and restoring the same bytes brings it back",
                    beforeCommit, git.sourceFingerprint(base));

            ConfigLoader.Loaded visual = new ConfigLoader().load(repository, "needs-eyes");
            GateRunner.Outcome eyes = new GateRunner(runner).run(visual, "test-visual");
            check.that("machine gates no longer stand in for visual QA",
                    !"visual_qa_unavailable".equals(eyes.code()));

            Files.writeString(repository.resolve("outside.txt"), "outside\n");
            GateRunner.Outcome refused = new GateRunner(runner).run(loaded, "test-refuse");
            check.eq("preflight fails closed outside scope", "preflight_outside_scope", refused.code());
            check.that("failure report is still written", Files.isRegularFile(refused.report()));
        } finally {
            deleteTree(repository);
        }
        committedCandidateChecks(check, runner);
        untrackedExecutableChecks(check, runner);
    }

    /**
     * A candidate that adds and moves files, before and after `warden land --commit`.
     *
     * The committed-change check above edits a file that was already tracked, which is the
     * one case the hash stripping reached. A file the candidate adds has no raw record at all
     * while it is untracked and an `A` record once committed; a file moved on disk is `D old`
     * plus an untracked `new` until the commit lets git pair them as `R100 old new`. Either one
     * made `warden land --push` refuse the run `warden land --commit` had just committed.
     * Reproduced 2026-09-23 on an added file.
     */
    private static void committedCandidateChecks(Check check, ProcessRunner runner) throws Exception {
        Path repository = Files.createTempDirectory("warden-fingerprint-");
        try {
            Files.createDirectories(repository.resolve("src"));
            Files.writeString(repository.resolve("src/kept.txt"),
                    "a file long enough\nfor git to pair\nits rename\nonce both sides\nare tracked\n");
            Files.writeString(repository.resolve("src/edited.txt"), "before\n");
            Files.writeString(repository.resolve("src/removed.txt"), "going\n");
            command(repository, "git", "init", "-b", "main");
            // The index's mode, not the file system's, on every platform, so that the
            // mode-change check below means the same thing on Linux as on Windows.
            command(repository, "git", "config", "core.fileMode", "false");
            command(repository, "git", "add", ".");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "base");
            GitRepository git = new GitRepository(repository, runner);
            String base = git.mergeBase("HEAD");

            // The candidate as it is judged and accepted: an edit, a deletion, a move made on
            // disk and a new file, the last two untracked.
            Files.writeString(repository.resolve("src/edited.txt"), "after\n");
            Files.delete(repository.resolve("src/removed.txt"));
            Files.move(repository.resolve("src/kept.txt"), repository.resolve("src/moved.txt"));
            Files.writeString(repository.resolve("src/added.txt"), "new in this candidate\n");
            String accepted = git.sourceFingerprint(base);
            // Pinned, because a decision stores this value and compares it later. It is what the
            // definition before the fix produced for this tree; a change to it is a change to
            // every uncommitted candidate a pending or accepted decision was recorded against,
            // and the CHANGELOG has to say so.
            check.eq("an uncommitted candidate keeps the fingerprint it was accepted under",
                    UNCOMMITTED_CANDIDATE, accepted);

            // What `warden land --commit` does with it.
            command(repository, "git", "add", "--", "src/added.txt", "src/edited.txt",
                    "src/kept.txt", "src/moved.txt", "src/removed.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "land");
            check.eq("committing a candidate that adds and moves files leaves its fingerprint alone",
                    accepted, git.sourceFingerprint(base));

            // Everything the fingerprint is for still moves it once the candidate is committed.
            Path added = repository.resolve("src/added.txt");
            Files.writeString(added, "edited after the yes\n");
            check.that("an edit to the added file moves it", !accepted.equals(git.sourceFingerprint(base)));
            Files.writeString(added, "new in this candidate\n");
            Files.delete(added);
            check.that("so does deleting the added file", !accepted.equals(git.sourceFingerprint(base)));
            Files.writeString(added, "new in this candidate\n");
            Path moved = repository.resolve("src/moved.txt");
            Path movedAgain = repository.resolve("src/moved-again.txt");
            Files.move(moved, movedAgain);
            check.that("and renaming it", !accepted.equals(git.sourceFingerprint(base)));
            Files.move(movedAgain, moved);
            command(repository, "git", "update-index", "--chmod=+x", "--", "src/edited.txt");
            check.that("and a mode change on a file the base already had",
                    !accepted.equals(git.sourceFingerprint(base)));
            command(repository, "git", "update-index", "--chmod=-x", "--", "src/edited.txt");
            check.eq("and the same tree brings it back", accepted, git.sourceFingerprint(base));

            // The mode an added file lands with. Its raw record is left out of the shape, and
            // with it went the one thing that record held beyond the file itself: an added
            // script committed as 100755 and then as 100644, the same bytes, kept its
            // fingerprint, so land and acceptance could not tell the two apart.
            command(repository, "git", "update-index", "--chmod=+x", "--", "src/added.txt");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "executable");
            String executable = git.sourceFingerprint(base);
            check.that("an added file committed executable is a different candidate",
                    !accepted.equals(executable));
            command(repository, "git", "update-index", "--chmod=-x", "--", "src/added.txt");
            check.that("and staging it back as a plain file with the same bytes moves it",
                    !executable.equals(git.sourceFingerprint(base)));
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "plain again");
            check.that("as does committing that", !executable.equals(git.sourceFingerprint(base)));
            check.eq("which is the plain candidate that was accepted", accepted, git.sourceFingerprint(base));
        } finally {
            deleteTree(repository);
        }
    }

    /**
     * An added executable before and after it is tracked, where the file system has the bit.
     *
     * With {@code core.fileMode} on, git takes an added file's mode from its executable bit,
     * so an untracked script already has the mode it will land with; the fingerprint has to
     * read it from the file then and from the raw record after, and get the same answer.
     * With the setting off, git ignores the bit and adds the file as a plain one, and so must
     * the fingerprint. Windows has no such bit to set, so there is nothing to show there.
     */
    private static void untrackedExecutableChecks(Check check, ProcessRunner runner) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) return;
        Path repository = Files.createTempDirectory("warden-fingerprint-mode-");
        try {
            Files.writeString(repository.resolve("base.txt"), "base\n");
            command(repository, "git", "init", "-b", "main");
            command(repository, "git", "config", "core.fileMode", "true");
            command(repository, "git", "add", ".");
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "base");
            GitRepository git = new GitRepository(repository, runner);
            String base = git.mergeBase("HEAD");

            Path script = repository.resolve("run.sh");
            Files.writeString(script, "#!/bin/sh\necho ran\n");
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            String plain = git.sourceFingerprint(base);
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            String untracked = git.sourceFingerprint(base);
            check.that("an untracked script's executable bit is part of the candidate", !plain.equals(untracked));
            command(repository, "git", "add", "--", "run.sh");
            check.eq("and staging it does not move the fingerprint", untracked, git.sourceFingerprint(base));
            command(repository, "git", "-c", "user.name=Warden Tests",
                    "-c", "user.email=warden@example.invalid", "commit", "-m", "script");
            check.eq("nor does committing it", untracked, git.sourceFingerprint(base));
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            check.eq("dropping the bit on the committed script is the plain candidate",
                    plain, git.sourceFingerprint(base));

            command(repository, "git", "reset", "-q", "--hard", base);
            Files.writeString(script, "#!/bin/sh\necho ran\n");
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            command(repository, "git", "config", "core.fileMode", "false");
            check.eq("with core.fileMode off an untracked executable is added, and fingerprinted, "
                    + "as a plain file", plain, git.sourceFingerprint(base));
        } finally {
            deleteTree(repository);
        }
    }

    /** Computed by the definition as of 1eec6a9, before additions left the shape. */
    private static final String UNCOMMITTED_CANDIDATE =
            "216f6b71efd4570167635df7c7f70d0c8f01a96b1dea355c32bc54ede55edd7d";

    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("warden-runtime-");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.writeString(root.resolve("src/value.txt"), "initial\n");
        Files.writeString(root.resolve(".warden/project.yaml"), """
                version: 1
                project: fixture
                base_ref: HEAD
                checks:
                  baseline: ["echo baseline-ok"]
                  fast: ["echo gate-ok"]
                scopes:
                  code: ["src"]
                defaults:
                  checks: fast
                  baseline_checks: baseline
                  risk: medium
                  max_fix_attempts: 2
                  timeout_minutes: 1
                """);
        Files.writeString(root.resolve(".warden/tasks/smoke.yaml"), """
                version: 1
                id: smoke
                goal: exercise machine gates
                non_goals: ["no external effects"]
                scope: code
                authority:
                  workspace_write: true
                  network: false
                  land: false
                visual_qa:
                  required: false
                budgets:
                  max_role_runs: 3
                  max_cost_usd: 1.0
                """);
        Files.writeString(root.resolve(".warden/tasks/needs-eyes.yaml"), """
                version: 1
                id: needs-eyes
                goal: a task that requires visual QA
                scope: code
                visual_qa:
                  required: true
                  scenarios: ["1280x720: testid=main-menu visible"]
                """);
        command(root, "git", "init", "-b", "main");
        command(root, "git", "add", ".");
        command(root, "git", "-c", "user.name=Warden Tests", "-c", "user.email=warden@example.invalid",
                "commit", "-m", "fixture");
        return root;
    }

    private static void command(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + ": " + output);
    }

    /**
     * A prompt delivered on stdin must not be able to stop the controller.
     *
     * The runner wrote the whole prompt before it started reading the child's output and
     * before it began timing the child. A vendor that never read its stdin, handed a prompt
     * larger than a pipe holds, blocked that write for as long as the vendor lived — no
     * timeout, wall-clock cap or chain deadline applied — and one that printed before reading
     * deadlocked outright: it waited for its stdout to be read, the runner waited for its stdin
     * to be. Each call is bounded here, so a regression fails the check instead of the suite.
     */
    private static void stdinChecks(Check check, ProcessRunner runner) throws Exception {
        String prompt = "x".repeat(8 * 1024 * 1024);

        long started = System.nanoTime();
        ProcessRunner.Result ignored = bounded(() -> runner.run(stub("stdin-ignored"), Path.of("."),
                Duration.ofSeconds(2), 1024, prompt), Duration.ofSeconds(60));
        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        check.that("a child that never reads a prompt larger than its pipe is stopped by the "
                + "timeout rather than waited on", ignored != null && ignored.timedOut());
        check.that("and the call returns about when the timeout says (" + seconds + " s)", seconds < 20);

        ProcessRunner.Result printed = bounded(() -> runner.run(stub("stdout-before-stdin"), Path.of("."),
                Duration.ofSeconds(60), 1024, prompt), Duration.ofSeconds(90));
        check.that("a child that prints before it reads does not deadlock with the runner",
                printed != null && printed.ok());
        check.contains("and it reads the whole prompt", printed == null ? "" : printed.stderr(),
                "read " + prompt.length());
        check.that("while its output is still capped", printed != null && printed.stdoutTruncated());

        // Unix only: there Process.destroy closes stdin after the signal, and that close waits
        // for the lock the blocked prompt write holds. A child that ignores SIGTERM and never
        // reads kept the stop inside destroy until it exited by itself, here after 60 s.
        // Windows terminates without touching the streams, so it has nothing to show.
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            started = System.nanoTime();
            ProcessRunner.Result stubborn = bounded(() -> runner.run(
                    List.of("sh", "-c", "trap '' TERM; exec sleep 60"), Path.of("."),
                    Duration.ofSeconds(1), 1024, prompt), Duration.ofSeconds(45));
            seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            check.that("a child that ignores SIGTERM and never reads its prompt is killed after "
                    + "the grace period", stubborn != null && stubborn.timedOut());
            check.that("and the stop does not wait for it to exit by itself (" + seconds + " s)",
                    seconds < 20);
        }
    }

    /** The call's result, or null when it had not returned within {@code limit}. */
    private static <T> T bounded(java.util.concurrent.Callable<T> call, Duration limit) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                result.set(call.call());
            } catch (Exception failed) {
                failure.set(failed);
            }
        });
        caller.join(limit);
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    /** The stand-in vendor on the JVM already running this suite, identical on every platform. */
    private static List<String> stub(String mode) {
        String separator = java.io.File.pathSeparator;
        StringBuilder classPath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            if (classPath.length() > 0) classPath.append(separator);
            classPath.append(Path.of(entry).toAbsolutePath().normalize());
        }
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", classPath.toString(), "dev.warden.testing.StubVendor", mode);
    }

    private static List<String> shell(String command) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    private static String longOutput() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "for /L %i in (1,1,100) do @echo 0123456789"
                : "yes 0123456789 | head -100";
    }

    private static String slowCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "ping -n 5 127.0.0.1 >nul"
                : "sleep 5";
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                Files.deleteIfExists(path);
            }
        }
    }
}
