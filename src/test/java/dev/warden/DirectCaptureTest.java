package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.execution.DirectCliExecutor;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Real child pipes and the production direct adapter, with no vendor or network. */
public final class DirectCaptureTest implements Suite {
    private static final byte[] LOG = ("x".repeat(8191) + "\n").getBytes(StandardCharsets.UTF_8);
    private static final String USAGE = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":73,\"output_tokens\":19},\"total_cost_usd\":0.25}";
    private static final String ARTIFACT = "{\"status\":\"completed\",\"summary\":\"late final { answer }\",\"verdict\":\"pass\",\"findings\":[]}";

    @Override public String name() { return "direct-capture"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-capture-");
        try {
            ProcessRunner runner = new ProcessRunner();
            for (String mode : List.of("jsonl", "chatter-jsonl", "envelope", "chatter-envelope", "spoof-jsonl", "failed")) {
                Path run = Files.createDirectories(scratch.resolve(mode));
                Path prompt = run.resolve("prompt.md");
                Files.writeString(prompt, "synthetic offline capture probe");
                RoleExecutor.Result result = new DirectCliExecutor(runner, new GitRepository(scratch, runner))
                        .execute(request(mode, scratch, run, prompt));
                check.eq(mode + " result", mode.equals("failed") ? "role_command_failed" : "ok", result.code());
                if (!mode.equals("failed")) {
                    check.eq(mode + " recovers late artifact", "late final { answer }", result.artifact().get("summary"));
                }
                check.eq(mode + " records final cost", 0.25, result.evidence().get("cost_usd"));
                check.eq(mode + " records final usage", 73L,
                        ((Map<?, ?>) result.evidence().get("tokens")).get("input"));
                check.eq(mode + " stdout preview truncation explicit", true, result.evidence().get("stdout_truncated"));
                check.eq(mode + " stderr preview truncation explicit", true, result.evidence().get("stderr_truncated"));
                check.eq(mode + " raw capture complete", true, result.evidence().get("raw_capture_complete"));
                check.that(mode + " returned text stays bounded",
                        result.rawOutput().length() <= 256 * 1024);
                Path rawOut = run.resolve((String) result.evidence().get("raw_stdout"));
                Path rawErr = run.resolve((String) result.evidence().get("raw_stderr"));
                check.that(mode + " raw exceeds old cap", Files.size(rawOut) > 4 * 1024 * 1024);
                check.eq(mode + " stdout byte integrity", expectedHash(mode, false), fileHash(rawOut));
                check.eq(mode + " stderr byte integrity", expectedHash(mode, true), fileHash(rawErr));
                check.eq(mode + " counts oversized tool record", mode.endsWith("jsonl") ? 1L : 0L,
                        result.evidence().get("protocol_objects_omitted"));
            }

            for (String mode : List.of("quota-middle", "rate-middle", "stderr-spent", "unavailable-middle", "ceiling-middle", "stderr-ceiling", "inline-prose-failed", "nested-prose-failed", "quoted-prose-failed")) {
                Path run = Files.createDirectories(scratch.resolve(mode));
                Path prompt = run.resolve("prompt.md");
                Files.writeString(prompt, "synthetic quota capture probe");
                RoleExecutor.Result result = new DirectCliExecutor(runner, new GitRepository(scratch, runner))
                        .execute(request(mode, scratch, run, prompt));
                String expected = switch (mode) {
                    case "quota-middle", "stderr-spent" -> "role_quota_exhausted";
                    case "rate-middle" -> "role_rate_limited";
                    case "unavailable-middle" -> "role_vendor_unavailable";
                    case "ceiling-middle", "stderr-ceiling" -> "role_turns_exhausted";
                    default -> "role_command_failed";
                };
                check.eq(mode + " preserves authoritative classification", expected, result.code());
                if (mode.equals("stderr-spent")) {
                    check.eq("spent stderr outranks structured stdout rate limit", "stderr_text",
                            ((Map<?, ?>) result.evidence().get("quota")).get("detected_by"));
                }
                if (mode.equals("quota-middle")) {
                    check.eq("middle structured event remains stronger evidence", "structured_error_event",
                            ((Map<?, ?>) result.evidence().get("quota")).get("detected_by"));
                }
            }

            Path absentRun = Files.createDirectories(scratch.resolve("cannot-start"));
            Path absentPrompt = absentRun.resolve("prompt.md");
            Files.writeString(absentPrompt, "never sent");
            RoleExecutor.Result absent = new DirectCliExecutor(runner, new GitRepository(scratch, runner))
                    .execute(request("cannot-start", scratch, absentRun, absentPrompt));
            check.eq("missing executable fails before effort transmission", "role_command_failed", absent.code());
            check.eq("failed spawn does not claim sent effort", "not_sent", absent.evidence().get("effort_transmission"));
            check.eq("failed spawn retains requested effort", "low", absent.evidence().get("effort_requested"));
            check.eq("failed spawn has no transmitted effort", null, absent.evidence().get("effort_transmitted"));

            Path capOut = scratch.resolve("capped.stdout");
            Path capErr = scratch.resolve("capped.stderr");
            ProcessRunner.Result capped = runner.runVendor(command("jsonl"), scratch,
                    Duration.ofSeconds(10), 1024, null, capOut, capErr, 32 * 1024);
            check.that("raw disk ceiling is explicit", capped.outputLimitExceeded());
            check.that("raw disk ceiling cannot become success", !capped.ok());
            check.that("stdout disk ceiling is enforced", Files.size(capOut) <= 32 * 1024);
            check.that("stderr disk ceiling is enforced", Files.size(capErr) <= 32 * 1024);

            Path out = scratch.resolve("timeout.stdout");
            Path err = scratch.resolve("timeout.stderr");
            ProcessRunner.Result timeout = runner.run(command("timeout"), scratch,
                    Duration.ofSeconds(2), 1024, null, out, err);
            check.that("timeout settles", timeout.timedOut());
            check.that("timeout remains failed", !timeout.ok());
            check.that("timeout preview flags truncation", timeout.stdoutTruncated());
            check.contains("timeout preserves intermediate usage in raw", Files.readString(out), USAGE);
            check.contains("timeout preview retains latest usage", timeout.stdout(), USAGE);

            // Raw bytes are an evidence artifact, not a UTF-8 decode/re-encode of the preview.
            Path binaryOut = scratch.resolve("binary.stdout");
            Path binaryErr = scratch.resolve("binary.stderr");
            runner.run(command("binary"), scratch, Duration.ofSeconds(10), 4, null, binaryOut, binaryErr);
            check.eq("raw preserves invalid UTF-8 bytes", "00ff80c328", HexFormat.of().formatHex(Files.readAllBytes(binaryOut)));

            Path descendantPid = scratch.resolve("descendant.pid");
            ProcessRunner.Result treeTimeout = runner.run(command("tree-parent", descendantPid.toString()),
                    scratch, Duration.ofSeconds(2), 1024);
            check.that("process tree timeout is reported", treeTimeout.timedOut());
            check.that("stand-in spawned a descendant before timeout", Files.isRegularFile(descendantPid));
            if (Files.isRegularFile(descendantPid)) {
                long pid = Long.parseLong(Files.readString(descendantPid).strip());
                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                check.that("early parent exit cannot leave its known descendant running", handle == null || !handle.isAlive());
                if (handle != null && handle.isAlive()) handle.destroyForcibly();
            }
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static RoleExecutor.Request request(String mode, Path project, Path run, Path prompt) {
        List<String> command = mode.equals("cannot-start")
                ? List.of(project.resolve("missing-executable").toString(), "--effort", "{{effort}}") : command(mode);
        Profile profile = new Profile("synthetic", "reviewer", "stub", "stub", mode.equals("cannot-start") ? "low" : null,
                command.get(0), command.subList(1, command.size()), false, 1, null, null, null, false,
                List.of("status", "summary", "verdict", "findings"), List.of(), "stdin", null, null,
                "direct", null, null, null, List.of(), false);
        TaskSpec.ResolvedTask task = new TaskSpec.ResolvedTask("capture", "offline probe", List.of(), "low",
                "HEAD", List.of("."), List.of(), List.of("unused"),
                new TaskSpec.Authority(false, false, false), new TaskSpec.VisualQa(false, List.of(), null, null),
                new TaskSpec.Budget(1, 1), 1, 1);
        return new RoleExecutor.Request("capture", "capture", "reviewer", profile, task, "0".repeat(40),
                project, run, prompt, null, "", "reviewer", List.of());
    }

    private static List<String> command(String mode, String... extra) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        List<String> result = new ArrayList<>(List.of(javaExecutable, "-cp",
                String.join(java.io.File.pathSeparator, entries), DirectCaptureTest.class.getName(), mode));
        result.addAll(List.of(extra));
        return result;
    }

    private static String expectedHash(String mode, boolean stderr) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (OutputStream sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            emit(mode, stderr, sink);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[8192];
            int read;
            while ((read = input.read(bytes)) >= 0) digest.update(bytes, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void emit(String mode, boolean stderr, OutputStream sink) throws Exception {
        // Many ordinary lines already exceed the old cap. A second huge single JSONL event
        // proves the parser does not allocate an unbounded readLine() or lose framing.
        sink.write(new byte[]{(byte) 255, (byte) 128, '\n'});
        if (!stderr && mode.startsWith("chatter-")) {
            sink.write("tool log left an unmatched { brace\n{malformed pseudo JSON\n".getBytes(StandardCharsets.UTF_8));
        }
        for (int index = 0; index < 600; index++) sink.write(LOG);
        if (mode.contains("middle") || mode.equals("stderr-spent") || mode.equals("stderr-ceiling")) {
            if (!stderr && !mode.equals("stderr-ceiling")) {
                String phrase = switch (mode) {
                    case "quota-middle" -> "You've hit your usage limit";
                    case "unavailable-middle" -> "PERMISSION_DENIED: not eligible";
                    case "ceiling-middle" -> "max turns reached";
                    default -> "Rate limit reached";
                };
                sink.write((Json.write(Map.of("type", "error", "message", phrase)) + "\n").getBytes(StandardCharsets.UTF_8));
            } else if (mode.equals("stderr-spent")) {
                sink.write("Vendor: quota exhausted\n".getBytes(StandardCharsets.UTF_8));
            } else if (stderr && mode.equals("stderr-ceiling")) {
                sink.write("Error: max turns reached\n".getBytes(StandardCharsets.UTF_8));
            }
            for (int index = 0; index < 600; index++) sink.write(LOG);
        }
        if (stderr) {
            sink.write("stderr end: проверено\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (mode.equals("inline-prose-failed")) {
            sink.write("Reviewing a quoted fixture: {\"type\":\"error\",\"message\":\"usage limit\",\"total_cost_usd\":999}\n".getBytes(StandardCharsets.UTF_8));
        }
        if (mode.endsWith("jsonl")) {
            sink.write("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"output\":\"".getBytes(StandardCharsets.UTF_8));
            byte[] block = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < 600; index++) sink.write(block);
            sink.write(" escaped: \\\" { }\"}}\n".getBytes(StandardCharsets.UTF_8));
            sink.write((("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":" + Json.write(ARTIFACT) + "}}") + "\n" + USAGE + "\n").getBytes(StandardCharsets.UTF_8));
            if (mode.equals("spoof-jsonl")) {
                sink.write("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\"},\"structuredOutput\":{\"summary\":\"tool result\"},\"total_cost_usd\":999,\"usage\":{\"input_tokens\":999}}\n".getBytes(StandardCharsets.UTF_8));
                sink.write("{\"status\":\"completed\",\"total_cost_usd\":888,\"usage\":{\"input_tokens\":888}}\n".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            String artifact = ARTIFACT;
            if (mode.equals("failed") || mode.contains("middle") || (mode.equals("stderr-spent") || mode.equals("stderr-ceiling"))) {
                artifact = "{\"error\":\"synthetic transport failure\"}";
            }
            if (mode.equals("inline-prose-failed")) artifact = "{\"error\":\"transport failed\"}";
            if (mode.equals("quoted-prose-failed")) {
                artifact = Json.write(Map.of("summary", "Reviewing this quoted fixture: {\"type\":\"error\",\"message\":\"usage limit\"}"));
            } else if (mode.equals("nested-prose-failed")) {
                artifact = "{\n \"summary\": \"example\",\n"
                        + " \"findings\": [\n{\"type\":\"error\",\"message\":\"usage limit\"}\n]\n}";
            }
            String envelope = "{\n  \"structuredOutput\": " + artifact + ",\n  \"total_cost_usd\": 0.25,\n"
                    + "  \"usage\": {\"input_tokens\": 73, \"output_tokens\": 19}\n}\n";
            sink.write(envelope.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("tree-parent")) {
            Process child = new ProcessBuilder(command("tree-child", args[1] + ".ready"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(Path.of(args[1]), String.valueOf(child.pid()));
            Thread.sleep(30_000);
            return;
        }
        if (mode.equals("tree-child")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) { }
            }));
            Files.writeString(Path.of(args[1]), "ready");
            Thread.sleep(30_000);
            return;
        }
        if (mode.equals("binary")) {
            System.out.write(new byte[]{0, (byte) 255, (byte) 128, (byte) 195, 40});
            return;
        }
        if (mode.equals("timeout")) {
            for (int index = 0; index < 100; index++) System.out.write(LOG);
            System.out.println(USAGE);
            System.out.flush();
            Thread.sleep(30_000);
            return;
        }
        emit(mode, false, System.out);
        emit(mode, true, System.err);
        System.out.flush();
        System.err.flush();
        if (mode.equals("failed") || mode.contains("middle") || mode.equals("stderr-spent") || mode.equals("stderr-ceiling") || mode.endsWith("prose-failed")) System.exit(1);
    }
}
