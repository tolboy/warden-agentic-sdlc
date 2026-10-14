package dev.warden;

import dev.warden.config.WardenTree;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class RestoreContractTest implements Suite {
    @Override public String name() { return "restore-contract"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-restore-");
        try {
            Path modified = root.resolve(".warden/project.yaml");
            Path deleted = root.resolve(".warden/tasks/hello.yaml");
            Path added = root.resolve(".warden/tasks/added.yaml");
            Files.createDirectories(deleted.getParent());
            byte[] original = "# café\r\nproject: fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] other = new byte[] {0, 13, 10, (byte) 255};
            Files.write(modified, original);
            Files.write(deleted, other);
            var pins = WardenTree.snapshot(root, "pin-1");
            var manifest = Json.parseObject(Files.readString(
                    root.resolve(".warden/runs/pin-1/contract/manifest.json")));
            check.eq("manifest persists per-file hashes", pins, manifest);
            Files.writeString(modified, "reverted");
            Files.delete(deleted);
            Files.writeString(added, "operator work");
            var result = cli(root, "pin-1");
            check.eq("restore CLI succeeds", 0, result.exitCode());
            var report = Json.parseObject(result.stdout());
            check.eq("restore reports success", true, report.get("ok"));
            check.that("modified restored byte-exact", Arrays.equals(original, Files.readAllBytes(modified)));
            check.that("deleted restored byte-exact", Arrays.equals(other, Files.readAllBytes(deleted)));
            for (var pin : pins.entrySet()) {
                check.eq("restored SHA-256 matches " + pin.getKey(), pin.getValue(),
                        GitRepository.contentSha256(root.resolve(pin.getKey())));
            }
            check.eq("added file untouched", "operator work", Files.readString(added));
            check.eq("added file reported", List.of(".warden/tasks/added.yaml"), report.get("added_files"));
            check.contains("added file needs manual decision", String.valueOf(report.get("message")), "manual decision");
            for (String missing : List.of("unknown-run", "no-contract")) {
                if (missing.equals("no-contract")) Files.createDirectories(root.resolve(".warden/runs/" + missing));
                var failed = cli(root, missing);
                check.eq("missing contract exits non-zero: " + missing, 1, failed.exitCode());
                check.contains("missing contract gives clear error", failed.stdout(), "No pinned contract manifest");
            }
            Files.writeString(root.resolve(".warden/runs/pin-1/contract/.warden/project.yaml"), "corrupt");
            Files.writeString(modified, "leave unchanged on corrupt backup");
            var corrupt = cli(root, "pin-1");
            check.eq("corrupt backup exits non-zero", 1, corrupt.exitCode());
            check.contains("corrupt backup explains mismatch", corrupt.stdout(), "SHA-256 mismatch");
            check.eq("bad copies checked before restoring", "leave unchanged on corrupt backup", Files.readString(modified));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static ProcessRunner.Result cli(Path root, String runId) throws Exception {
        String separator = System.getProperty("path.separator");
        String classPath = String.join(separator, Arrays.stream(System.getProperty("java.class.path").split(separator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).toList());
        return new ProcessRunner().run(List.of(ProcessHandle.current().info().command().orElse("java"),
                "-cp", classPath, "dev.warden.Main", "restore-contract", runId), root, Duration.ofSeconds(30));
    }
}
