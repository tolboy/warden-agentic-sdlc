package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.approval.StatusCommand;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Operator-facing truth for {@code warden status} and {@code warden approve}: the wrong
 * directory is not "the run does not exist", and a pending decision in a sibling worktree
 * is findable without already knowing where it lives.
 */
public final class StatusCommandTest implements Suite {
    @Override public String name() { return "status-command"; }

    @Override public void run(Check check) throws Exception {
        // toRealPath, because these cases compare a path this test built against a path Git
        // printed. On a Windows account whose name is longer than eight characters the temp
        // directory arrives as an 8.3 alias — `RUNNER~1` where the account is `runneradmin` —
        // while `git worktree list` prints the long form, so every such comparison fails on
        // a CI runner and passes on a developer's machine. The alias is this test's, not the
        // product's: nothing in Warden builds a path that way.
        Path sandbox = Files.createTempDirectory("warden-status-").toRealPath();
        try {
            notAWardenProject(check, sandbox);
            unknownRunNamesTheProject(check, sandbox);
            worktreesFindsSiblingPending(check, sandbox);
            worktreesReportsMalformedInCurrentCheckout(check, sandbox);
            approveInstructionIsPasteable(check, sandbox);
            porcelainIgnoresBare(check);
        } finally {
            deleteTree(sandbox);
        }
    }

    private void notAWardenProject(Check check, Path sandbox) throws Exception {
        Path outside = sandbox.resolve("not-a-project");
        Files.createDirectories(outside);

        StatusCommand.Outcome status = new StatusCommand(new ProcessRunner())
                .run(outside, new String[] {"status"});
        check.that("status outside a project is not ok", !status.ok());
        check.eq("status names the distinct code", "not_a_warden_project",
                status.report().get("code"));
        check.eq("status names the directory that was searched from",
                outside.toAbsolutePath().normalize().toString(),
                status.report().get("directory"));
        check.contains("status says the run may exist elsewhere",
                String.valueOf(status.report().get("message")), "may exist elsewhere");

        ProcessRunner.Result statusCli = cli(outside, "status");
        check.eq("status CLI outside a project exits 1", 1, statusCli.exitCode());
        Map<String, Object> statusBody = parseCli(statusCli);
        check.eq("status CLI names the distinct code", "not_a_warden_project",
                statusBody.get("code"));
        check.contains("status CLI says the run may exist elsewhere",
                String.valueOf(statusBody.get("message")), "may exist elsewhere");

        ProcessRunner.Result approve = cli(outside, "approve", "torch-2", "--decision", "accept");
        check.eq("approve outside a project exits 1", 1, approve.exitCode());
        Map<String, Object> body = parseCli(approve);
        check.eq("approve names the distinct code", "not_a_warden_project", body.get("code"));
        check.eq("approve names the directory that was searched from",
                outside.toAbsolutePath().normalize().toString(), body.get("directory"));
        check.contains("approve says the run may exist elsewhere",
                String.valueOf(body.get("message")), "may exist elsewhere");
        check.eq("approve still does not land", Boolean.FALSE, body.get("lands"));
        check.that("approve does not pretend the run is unknown",
                !"unknown_run".equals(body.get("code")));
    }

    private void unknownRunNamesTheProject(Check check, Path sandbox) throws Exception {
        Path project = sandbox.resolve("a-project");
        Files.createDirectories(project.resolve(".warden"));
        Files.writeString(project.resolve(".warden/project.yaml"),
                "version: 1\nproject: fixture\n");

        StatusCommand.Outcome status = new StatusCommand(new ProcessRunner())
                .run(project, new String[] {"status", "torch-2"});
        check.that("status of an unknown run is not ok", !status.ok());
        check.eq("status keeps unknown_run when the project exists",
                "unknown_run", status.report().get("code"));
        check.eq("status names the project that was consulted",
                project.toAbsolutePath().normalize().toString(),
                status.report().get("project"));
        check.contains("status message names the project",
                String.valueOf(status.report().get("message")),
                project.toAbsolutePath().normalize().toString());

        ProcessRunner.Result statusCli = cli(project, "status", "torch-2");
        check.eq("status CLI of an unknown run exits 1", 1, statusCli.exitCode());
        Map<String, Object> statusBody = parseCli(statusCli);
        check.eq("status CLI keeps unknown_run", "unknown_run", statusBody.get("code"));
        check.contains("status CLI message names the project",
                String.valueOf(statusBody.get("message")),
                project.toAbsolutePath().normalize().toString());

        ProcessRunner.Result approve = cli(project, "approve", "torch-2", "--decision", "accept");
        check.eq("approve of an unknown run exits 1", 1, approve.exitCode());
        Map<String, Object> body = parseCli(approve);
        check.eq("approve keeps unknown_run when the project exists",
                "unknown_run", body.get("code"));
        check.eq("approve names the project that was consulted",
                project.toAbsolutePath().normalize().toString(), body.get("project"));
        check.contains("approve message names the project",
                String.valueOf(body.get("message")),
                project.toAbsolutePath().normalize().toString());
    }

    private void worktreesFindsSiblingPending(Check check, Path sandbox) throws Exception {
        Path repo = sandbox.resolve("repo");
        Path sibling = sandbox.resolve("sibling");
        Path other = sandbox.resolve("other");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "warden@example.invalid");
        git(repo, "config", "user.name", "Warden Tests");
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "base");
        git(repo, "worktree", "add", "--detach", sibling.toString());
        git(repo, "worktree", "add", "--detach", other.toString());

        writeProject(repo);
        writeProject(sibling);
        new ApprovalStore(sibling).createFailure("torch-2", "status-tells-the-truth",
                "waiting for a person",
                sibling.resolve(".warden/runs/torch-2/summary.json"), null);
        new ApprovalStore(sibling).createSuccess("already-done", "other-task",
                "accepted earlier",
                sibling.resolve(".warden/runs/already-done/summary.json"), "sha256:done");
        HumanDecision resolved = new ApprovalStore(sibling).read("already-done");
        new ApprovalStore(sibling).resolve("already-done", resolved.updatedAt().toString(),
                "accept", "operator", "");
        Files.createDirectories(sibling.resolve(".warden/runs/broken"));
        Files.writeString(sibling.resolve(".warden/runs/broken/decision.json"), "{");

        StatusCommand.Outcome fromRepo = new StatusCommand(new ProcessRunner())
                .run(repo, new String[] {"status", "--worktrees"});
        check.that("status --worktrees from the current checkout is ok", fromRepo.ok());
        check.that("does not replace decisions", fromRepo.report().containsKey("decisions"));
        check.that("adds a worktrees key", fromRepo.report().containsKey("worktrees"));
        check.eq("current checkout has no decisions of its own",
                List.of(), fromRepo.report().get("decisions"));

        List<Map<String, Object>> rows = maps(fromRepo.report().get("worktrees"));
        Map<String, Object> pending = byRunId(rows, "torch-2");
        check.that("finds the pending decision in the sibling worktree", pending != null);
        check.eq("worktree path is the sibling",
                sibling.toAbsolutePath().normalize().toString(), pending.get("worktree"));
        check.eq("run id", "torch-2", pending.get("run_id"));
        check.eq("task id", "status-tells-the-truth", pending.get("task_id"));
        check.eq("kind", "failure", pending.get("kind"));
        check.eq("options", List.of("retry", "abort"), pending.get("options"));
        List<Map<String, Object>> approve = maps(pending.get("approve"));
        check.eq("one approve command per listed option", 2, approve.size());
        check.eq("and the operator is offered both rather than handed one",
                List.of("retry", "abort"), approve.stream().map(row -> row.get("decision")).toList());
        String first = String.valueOf(approve.get(0).get("command"));
        check.contains("approve command names the run", first, "warden approve torch-2 --decision");
        check.contains("approve command includes the sibling directory",
                first, sibling.toAbsolutePath().normalize().toString());
        check.contains("approve command uses a concrete listed option", first, "--decision retry");
        check.that("approve command has no angle-bracket placeholder",
                !first.contains("<") && !first.contains(">") && !first.contains("|"));

        check.that("resolved decisions are not listed as waiting",
                byRunId(rows, "already-done") == null);

        Map<String, Object> broken = byPathSuffix(rows, "broken" + java.io.File.separator + "decision.json");
        if (broken == null) {
            broken = byPathSuffix(rows, "broken/decision.json");
        }
        check.that("an unreadable decision.json is reported rather than dropped", broken != null);
        check.that("and names the parse problem",
                broken != null && broken.get("error") instanceof String text && !text.isBlank());

        StatusCommand.Outcome fromOther = new StatusCommand(new ProcessRunner())
                .run(other, new String[] {"status", "--worktrees"});
        check.that("--worktrees works from a checkout that is not a Warden project",
                fromOther.ok());
        check.that("and still finds the sibling pending decision",
                byRunId(maps(fromOther.report().get("worktrees")), "torch-2") != null);

        StatusCommand.Outcome withoutFlag = new StatusCommand(new ProcessRunner())
                .run(repo, new String[] {"status"});
        check.that("plain status keeps today's shape", withoutFlag.ok());
        check.that("plain status has no worktrees key",
                !withoutFlag.report().containsKey("worktrees"));
        check.that("plain status still has decisions",
                withoutFlag.report().containsKey("decisions"));

        ProcessRunner.Result worktreesCli = cli(repo, "status", "--worktrees");
        check.eq("status --worktrees CLI exits 0", 0, worktreesCli.exitCode());
        Map<String, Object> cliBody = parseCli(worktreesCli);
        check.that("status --worktrees CLI still has decisions",
                cliBody.containsKey("decisions"));
        check.that("status --worktrees CLI finds the sibling pending run",
                byRunId(maps(cliBody.get("worktrees")), "torch-2") != null);
    }

    /**
     * The reviewer's second case: a malformed decision.json in the checkout the operator is
     * standing in must still produce one successful --worktrees object that names the file.
     */
    private void worktreesReportsMalformedInCurrentCheckout(Check check, Path sandbox) throws Exception {
        Path repo = sandbox.resolve("current-broken");
        Path sibling = sandbox.resolve("current-broken-sibling");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "warden@example.invalid");
        git(repo, "config", "user.name", "Warden Tests");
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "base");
        git(repo, "worktree", "add", "--detach", sibling.toString());

        writeProject(repo);
        writeProject(sibling);
        new ApprovalStore(sibling).createFailure("torch-2", "status-tells-the-truth",
                "waiting for a person",
                sibling.resolve(".warden/runs/torch-2/summary.json"), null);
        Path broken = repo.resolve(".warden/runs/broken/decision.json");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{");

        StatusCommand.Outcome outcome = new StatusCommand(new ProcessRunner())
                .run(repo, new String[] {"status", "--worktrees"});
        check.that("--worktrees is ok when the current checkout has a malformed decision",
                outcome.ok());
        check.that("stdout still has a decisions list", outcome.report().containsKey("decisions"));
        List<Map<String, Object>> rows = maps(outcome.report().get("worktrees"));
        check.that("still finds the sibling pending run", byRunId(rows, "torch-2") != null);
        Map<String, Object> reported = byPathSuffix(rows, broken.toAbsolutePath().normalize().toString());
        check.that("worktrees reports the malformed file in the current checkout", reported != null);
        check.that("and names the parse problem",
                reported != null && reported.get("error") instanceof String text && !text.isBlank());

        ProcessRunner.Result cli = cli(repo, "status", "--worktrees");
        check.eq("--worktrees CLI with a current-checkout malformed file exits 0", 0, cli.exitCode());
        Map<String, Object> body = parseCli(cli);
        check.eq("CLI stdout is a successful status object", Boolean.TRUE, body.get("ok"));
        check.that("CLI worktrees reports the malformed current-checkout file",
                byPathSuffix(maps(body.get("worktrees")),
                        broken.toAbsolutePath().normalize().toString()) != null);
        check.that("CLI does not fall back to a generic warden_error on stderr",
                cli.stderr() == null || !cli.stderr().contains("warden_error"));
    }

    /**
     * The returned instruction must be pasteable into cmd.exe and PowerShell and must record
     * one of the listed decisions from the named worktree.
     */
    private void approveInstructionIsPasteable(Check check, Path sandbox) throws Exception {
        HumanDecision sample = new HumanDecision(
                HumanDecision.SCHEMA_VERSION, "torch-2", "status-tells-the-truth",
                HumanDecision.State.PENDING, HumanDecision.Kind.FAILURE, "waiting",
                List.of("retry", "abort"),
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                ".warden/runs/torch-2/summary.json", null, null, null, null);
        Path otherDrive = Path.of("D:\\warden-worktrees\\torch-2");
        List<java.util.Map<String, Object>> instructions =
                StatusCommand.approveInstructions(otherDrive, sample);
        check.eq("one command per option, and no more", 2, instructions.size());
        check.eq("in the order the decision declares them", List.of("retry", "abort"),
                instructions.stream().map(row -> row.get("decision")).toList());

        String instruction = String.valueOf(instructions.get(0).get("command"));
        check.contains("each names the run and its own decision", instruction,
                "warden approve torch-2 --decision retry");
        check.contains("and the second names the other", String.valueOf(instructions.get(1).get("command")),
                "warden approve torch-2 --decision abort");
        check.that("no redirection metacharacters survive a paste",
                !instruction.contains("<") && !instruction.contains(">") && !instruction.contains("|"));
        check.contains("instruction names the worktree", instruction, otherDrive.toString());
        if (windows()) {
            check.that("Windows instruction changes drive (cd /d or pushd)",
                    instruction.contains("cd /d ") || instruction.contains("pushd "));
        }

        // The defect this shape exists to prevent: a success declares [accept, reject], and
        // emitting only the first would hand the operator a pre-typed acceptance.
        HumanDecision success = new HumanDecision(
                HumanDecision.SCHEMA_VERSION, "torch-3", "status-tells-the-truth",
                HumanDecision.State.PENDING, HumanDecision.Kind.SUCCESS, "ready",
                List.of("accept", "reject"),
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                ".warden/runs/torch-3/summary.json", null, null, null, null);
        List<java.util.Map<String, Object>> both =
                StatusCommand.approveInstructions(otherDrive, success);
        check.eq("a successful run offers both choices rather than choosing", 2, both.size());
        check.eq("and neither is picked for the operator", List.of("accept", "reject"),
                both.stream().map(row -> row.get("decision")).toList());

        Path repo = sandbox.resolve("paste-repo");
        Path sibling = sandbox.resolve("paste-sibling");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "warden@example.invalid");
        git(repo, "config", "user.name", "Warden Tests");
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "base");
        git(repo, "worktree", "add", "--detach", sibling.toString());
        writeProject(sibling);
        new ApprovalStore(sibling).createFailure("paste-cmd", "status-tells-the-truth",
                "waiting for a person",
                sibling.resolve(".warden/runs/paste-cmd/summary.json"), null);
        new ApprovalStore(sibling).createFailure("paste-pwsh", "status-tells-the-truth",
                "waiting for a person",
                sibling.resolve(".warden/runs/paste-pwsh/summary.json"), null);

        StatusCommand.Outcome outcome = new StatusCommand(new ProcessRunner())
                .run(repo, new String[] {"status", "--worktrees"});
        Map<String, Object> forCmd = byRunId(maps(outcome.report().get("worktrees")), "paste-cmd");
        Map<String, Object> forPwsh = byRunId(maps(outcome.report().get("worktrees")), "paste-pwsh");
        check.that("paste-cmd pending row exists", forCmd != null);
        check.that("paste-pwsh pending row exists", forPwsh != null);
        if (forCmd == null || forPwsh == null) return;

        ProcessRunner.Result cmd = pasteIntoCmd(repo, sandbox.resolve("paste.cmd"),
                firstCommand(forCmd));
        check.eq("pasting into cmd.exe exits 0 (stdout=" + cmd.stdout() + " stderr=" + cmd.stderr() + ")",
                0, cmd.exitCode());
        if (cmd.exitCode() == 0) {
            check.eq("cmd.exe paste code is decision_recorded",
                    "decision_recorded", parseCli(cmd).get("code"));
        }

        if (windows()) {
            ProcessRunner.Result pwsh = pasteIntoPwsh(repo, sandbox.resolve("paste.ps1"),
                    firstCommand(forPwsh));
            check.eq("pasting into PowerShell exits 0 (stdout=" + pwsh.stdout()
                    + " stderr=" + pwsh.stderr() + ")", 0, pwsh.exitCode());
            if (pwsh.exitCode() == 0) {
                check.eq("PowerShell paste code is decision_recorded",
                        "decision_recorded", parseCli(pwsh).get("code"));
            }
        }
    }

    private void porcelainIgnoresBare(Check check) {
        String porcelain = """
                worktree /tmp/repo
                HEAD abcdef
                branch refs/heads/main

                worktree /tmp/bare.git
                bare

                worktree /tmp/feature
                HEAD 123456
                detached
                """;
        List<Path> listed = StatusCommand.parseWorktreePorcelain(porcelain);
        check.eq("porcelain parser skips a bare repo", 2, listed.size());
        check.eq("first checkout", Path.of("/tmp/repo").toAbsolutePath().normalize(), listed.get(0));
        check.eq("second checkout", Path.of("/tmp/feature").toAbsolutePath().normalize(), listed.get(1));
    }

    private static void writeProject(Path root) throws Exception {
        Files.createDirectories(root.resolve(".warden"));
        Files.writeString(root.resolve(".warden/project.yaml"),
                "version: 1\nproject: fixture\n");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path wardenBin() {
        return Path.of("bin").toAbsolutePath().normalize();
    }

    private static ProcessRunner.Result pasteIntoCmd(Path cwd, Path script, String approve)
            throws Exception {
        String bin = wardenBin().toString();
        String body;
        Path home = isolatedHome(cwd);
        if (windows()) {
            body = "@echo off\r\nset \"WARDEN_CONFIG_HOME=" + home + "\"\r\nset \"PATH="
                    + bin + ";%PATH%\"\r\n" + approve + "\r\n";
        } else {
            body = "export WARDEN_CONFIG_HOME=\"" + home + "\"\nexport PATH=\"" + bin
                    + ":$PATH\"\n" + approve + "\n";
        }
        Files.writeString(script, body);
        List<String> shell = windows()
                ? List.of("cmd.exe", "/d", "/c", script.toAbsolutePath().toString())
                : List.of("/bin/sh", script.toAbsolutePath().toString());
        return new ProcessRunner().run(shell, cwd, Duration.ofSeconds(30));
    }

    private static Path isolatedHome(Path cwd) throws Exception {
        Path home = cwd.resolve(".warden-test-home");
        Files.createDirectories(home);
        return home.toAbsolutePath().normalize();
    }

    private static ProcessRunner.Result pasteIntoPwsh(Path cwd, Path script, String approve)
            throws Exception {
        String bin = wardenBin().toString().replace("'", "''");
        Path home = isolatedHome(cwd);
        Files.writeString(script, "$env:WARDEN_CONFIG_HOME = '"
                + home.toString().replace("'", "''") + "'\n$env:PATH = '"
                + bin + ";' + $env:PATH\n" + approve + "\n");
        return new ProcessRunner().run(
                List.of("pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                        script.toAbsolutePath().toString()),
                cwd, Duration.ofSeconds(30));
    }

    private static ProcessRunner.Result cli(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add("dev.warden.Main");
        command.addAll(List.of(args));
        return new ProcessRunner().run(command, cwd, Duration.ofSeconds(30));
    }

    private static String absoluteClassPath() {
        String separator = System.getProperty("path.separator");
        StringBuilder absolute = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(separator, -1)) {
            if (entry.isEmpty()) continue;
            if (absolute.length() > 0) absolute.append(separator);
            absolute.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return absolute.toString();
    }

    private static Map<String, Object> parseCli(ProcessRunner.Result result) {
        String stdout = result.stdout() == null ? "" : result.stdout().strip();
        if (stdout.isEmpty()) {
            throw new IllegalStateException("empty stdout (exit " + result.exitCode()
                    + "): " + result.stderr());
        }
        return Json.parseObject(stdout);
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.Result result = new ProcessRunner().run(command, cwd, Duration.ofSeconds(30));
        if (!result.ok()) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) rows.add((Map<String, Object>) map);
        }
        return rows;
    }

    /**
     * The command for the first listed option. `approve` is one row per option now, because a
     * single emitted command meant a successful run handed the operator `--decision accept`
     * ready to paste — the human gate answering itself.
     */
    private static String firstCommand(Map<String, Object> row) {
        return String.valueOf(maps(row.get("approve")).get(0).get("command"));
    }

    private static Map<String, Object> byRunId(List<Map<String, Object>> rows, String runId) {
        for (Map<String, Object> row : rows) {
            if (runId.equals(row.get("run_id"))) return row;
        }
        return null;
    }

    private static Map<String, Object> byPathSuffix(List<Map<String, Object>> rows, String suffix) {
        for (Map<String, Object> row : rows) {
            Object path = row.get("path");
            if (path instanceof String text && text.endsWith(suffix)) return row;
        }
        return null;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
