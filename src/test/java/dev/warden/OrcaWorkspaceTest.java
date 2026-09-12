package dev.warden;

import dev.warden.execution.orca.OrcaWorkspace;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import dev.warden.process.ProcessRunner;

/**
 * The window Warden opens on a run's narration.
 *
 * It is a courtesy and never a reason to fail a run, which is why nothing here touches Orca:
 * the script is a pure function of a path, and what it says about encoding is the difference
 * between an operator reading their own run and reading mojibake.
 */
public final class OrcaWorkspaceTest implements Suite {

    @Override public String name() { return "orca-workspace"; }

    @Override public void run(Check check) throws Exception {
        Path narration = Path.of("C:", "runs", "r1", "narration.log");
        String windows = OrcaWorkspace.followScript(narration, true);

        // Warden writes the narration as UTF-8. Windows PowerShell reads with the ANSI code
        // page unless told otherwise, and an em dash then arrives as three characters.
        check.contains("the followed file is read as UTF-8", windows, "-Encoding utf8");
        check.contains("and the console it is printed to encodes as UTF-8", windows,
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8");
        check.contains("the window opens on the end of the file, not its beginning", windows,
                "-Tail 200");
        check.contains("and keeps following it", windows, "-Wait");

        String quoted = OrcaWorkspace.followScript(
                Path.of("C:", "runs", "it's here", "narration.log"), true);
        check.contains("a quote in the path is doubled rather than ending the argument",
                quoted, "it''s here");

        // The contract is finished when it is shown, so the window prints it and stays. A
        // window that keeps following a file nobody is writing reads as a run that never began.
        String shown = OrcaWorkspace.showScript(Path.of("C:", "runs", "r1", "hello.yaml"), true);
        check.contains("a shown file is read as UTF-8 too", shown, "-Encoding utf8");
        check.that("and is not followed", !shown.contains("-Wait"));
        String shownPosix = OrcaWorkspace.showScript(Path.of("/runs/r1/hello.yaml"), false);
        check.contains("the POSIX script prints it once", shownPosix, "cat ");
        check.that("and does not tail it", !shownPosix.contains("tail"));

        String posix = OrcaWorkspace.followScript(narration, false);
        check.contains("the POSIX script tails the same file", posix, "tail -n 200 -f");
        check.contains("and leaves a shell behind when the tail is interrupted", posix,
                "exec ${SHELL:-sh}");
        check.that("the POSIX script asks for no encoding, because UTF-8 is the locale's",
                !posix.contains("Encoding"));

        Path worktree = Files.createTempDirectory("warden-contract-window-");
        Path contract = worktree.resolve(".warden/tasks/задача ' one.yaml");
        Files.createDirectories(contract.getParent());
        String content = "goal: Проверить — контракт\n";
        Files.writeString(contract, content);
        boolean onWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = OrcaWorkspace.showCommand(worktree, contract, "r1", onWindows);
        String extension = onWindows ? "ps1" : "sh";
        Path script = worktree.resolve(".warden/runs/r1/show-contract." + extension);
        check.that("helper is created under this run's evidence", Files.isRegularFile(script));
        try (var files = Files.list(contract.getParent())) {
            check.eq("presentation leaves only the task in the tasks directory", 1L, files.count());
        }
        check.eq("presentation does not change the contract", content, Files.readString(contract));
        check.that("command uses a safe relative helper path",
                command.endsWith(".warden/runs/r1/show-contract." + extension));
        check.that("a non-ASCII task path never enters the terminal command", !command.contains("задача"));
        if (onWindows) {
            byte[] bytes = Files.readAllBytes(script);
            check.that("PowerShell 5.1 decodes the script as UTF-8 via BOM",
                    bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
            var result = new ProcessRunner().run(List.of("powershell.exe", "-NoLogo", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString()), worktree, Duration.ofSeconds(15));
            check.eq("the actual PowerShell script opens a Cyrillic/apostrophe path", 0, result.exitCode());
            check.contains("PowerShell preserves UTF-8 contract text", result.stdout(), "Проверить — контракт");
            check.eq("PowerShell reports no file lookup error", "", result.stderr().strip());
        } else {
            var result = new ProcessRunner().run(List.of("sh", script.toString()), worktree, Duration.ofSeconds(15));
            check.eq("the actual POSIX script opens the quoted path", 0, result.exitCode());
            check.contains("POSIX preserves contract text", result.stdout(), "Проверить — контракт");
        }
        boolean invalid = false;
        try { OrcaWorkspace.showCommand(worktree, contract, "../other", onWindows); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check.that("helper path cannot escape the run root", invalid);
        try (var paths = Files.walk(worktree)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
