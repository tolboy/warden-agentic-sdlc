package dev.warden.gate;

import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskSpec;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine visual QA: start the project's preview if needed, drive a real browser via CDP,
 * assert a control is visible at a viewport, keep the screenshot. A pass without a
 * screenshot is refused. This is not a model looking at a diff.
 */
public final class VisualQaRunner {
    public record Outcome(boolean ok, String code, Path report, Map<String, Object> data) {}

    private final ProcessRunner processes;
    private final Path script;

    public VisualQaRunner(ProcessRunner processes) { this(processes, locateScript()); }

    public VisualQaRunner(ProcessRunner processes, Path script) {
        this.processes = processes;
        this.script = script;
    }

    public Outcome run(ConfigLoader.Loaded loaded, String runId) throws IOException, InterruptedException {
        EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1L);
        report.put("task_id", loaded.resolved().id());
        report.put("run_id", runId);
        TaskSpec.VisualQa visual = loaded.resolved().visualQa();
        report.put("scenarios", visual.scenarios());
        Path shots = ledger.runDirectory().resolve("screenshots");
        Files.createDirectories(shots);
        report.put("screenshots_dir", shots.toString());

        if (!visual.required()) {
            return finish(ledger, true, "skipped", report, "visual QA is not required for this task");
        }
        if (visual.scenarios().isEmpty()) {
            return finish(ledger, false, "visual_qa_unavailable", report, "visual_qa.required but no scenarios");
        }
        if (script == null || !Files.isRegularFile(script)) {
            return finish(ledger, false, "visual_qa_unavailable", report,
                    "browser visual-qa adapter not found (expected scripts/visual-qa.mjs next to Warden)");
        }

        String url = visual.url() != null && !visual.url().isBlank()
                ? visual.url() : "http://127.0.0.1:4173/";
        boolean startDeclared = visual.start() != null && !visual.start().isBlank();
        String start = startDeclared ? visual.start() : defaultStart(loaded.root());
        report.put("url", url);
        report.put("start", start);
        report.put("start_declared_by_task", startDeclared);

        Process server = null;
        try {
            boolean alreadyAnswering = httpOk(url);
            report.put("already_answering", alreadyAnswering);
            if (alreadyAnswering && startDeclared) {
                // Measured, not imagined: a run of this fixture at 127.0.0.1:4173 was answered
                // by an unrelated SvelteKit app left running from another project, and the
                // harness screenshotted that instead. It failed, which was luck — it could as
                // easily have passed. A check that silently measures the wrong application is
                // worse than one that refuses.
                return finish(ledger, false, "visual_qa_port_occupied", report,
                        "something is already answering at " + url + ", and this task declares its own "
                                + "start command. Warden will not guess whether that server is this "
                                + "project or a stale one from another: stop it, change visual_qa.url to "
                                + "a free port, or drop visual_qa.start to test what is already there.");
            }
            if (!alreadyAnswering) {
                if (start == null) {
                    return finish(ledger, false, "visual_qa_unavailable", report,
                            "nothing is listening at " + url + " and no start command was configured");
                }
                server = startServer(loaded.root(), start);
                if (!waitForHttp(url, Duration.ofSeconds(45))) {
                    return finish(ledger, false, "visual_qa_unavailable", report,
                            "started `" + start + "` but " + url + " never answered");
                }
            } else {
                // No start in the contract: pointing at a running app is the operator's choice.
                // Recorded anyway, because "which server answered" belongs in the evidence.
                report.put("used_existing_server", true);
            }

            List<String> command = new ArrayList<>();
            command.add(nodeExecutable());
            command.add(script.toString());
            command.add("--url");
            command.add(url);
            command.add("--out");
            command.add(shots.toString());
            for (String scenario : visual.scenarios()) {
                command.add("--scenario");
                command.add(scenario);
            }
            ProcessRunner.Result result = processes.run(command, loaded.root(), Duration.ofMinutes(2), 256 * 1024);
            report.put("exit_code", (long) result.exitCode());
            report.put("timed_out", result.timedOut());
            report.put("stdout", result.stdout());
            report.put("stderr", result.stderr());
            Path adapterReport = shots.resolve("visual-qa.json");
            if (Files.isRegularFile(adapterReport)) {
                Object parsed = Json.parse(Files.readString(adapterReport));
                if (parsed instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) map;
                    report.put("adapter", body);
                    boolean ok = Boolean.TRUE.equals(body.get("ok"));
                    String code = String.valueOf(body.getOrDefault("code", ok ? "passed" : "visual_qa_failed"));
                    if (ok && !hasScreenshot(body)) {
                        return finish(ledger, false, "visual_qa_no_evidence", report,
                                "adapter claimed pass without a screenshot");
                    }
                    return finish(ledger, ok, code, report, null);
                }
            }
            if (result.timedOut()) {
                return finish(ledger, false, "visual_qa_unavailable", report, "browser visual QA timed out");
            }
            return finish(ledger, false, "visual_qa_unavailable", report,
                    "adapter wrote no visual-qa.json (is node and Edge/Chrome installed?)");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception failure) {
            return finish(ledger, false, "visual_qa_unavailable", report,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            if (server != null) {
                server.descendants().forEach(ProcessHandle::destroy);
                server.destroy();
            }
        }
    }

    private Outcome finish(EvidenceLedger ledger, boolean ok, String code, Map<String, Object> report,
                           String message) throws IOException {
        if (message != null) report.put("message", message);
        report.put("ok", ok);
        report.put("code", code);
        report.put("finished_at", Instant.now().toString());
        Path path = ledger.writeReport("visual-qa", report);
        ledger.append("visual_qa", Map.of("ok", ok, "code", code, "report", path.toString()));
        return new Outcome(ok, code, path, report);
    }

    private static boolean hasScreenshot(Map<String, Object> body) {
        Object scenarios = body.get("scenarios");
        if (!(scenarios instanceof List<?> list) || list.isEmpty()) return false;
        for (Object item : list) {
            if (item instanceof Map<?, ?> row && row.get("screenshot") instanceof String path
                    && Files.isRegularFile(Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    private static String defaultStart(Path root) {
        Path pkg = root.resolve("package.json");
        if (!Files.isRegularFile(pkg)) return null;
        try {
            String text = Files.readString(pkg);
            if (text.contains("\"preview\"")) {
                return "npm run preview -- --host 127.0.0.1 --port 4173";
            }
            if (text.contains("\"dev\"")) {
                return "npm run dev -- --host 127.0.0.1 --port 5173";
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static Process startServer(Path root, String command) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> shell = windows ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
        return new ProcessBuilder(shell).directory(root.toFile()).redirectErrorStream(true).start();
    }

    private static boolean waitForHttp(String url, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (httpOk(url)) return true;
            Thread.sleep(300);
        }
        return httpOk(url);
    }

    private static boolean httpOk(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET()
                    .timeout(Duration.ofSeconds(2)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String nodeExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "node.exe" : "node";
    }

    static Path locateScript() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("WARDEN_HOME");
        if (home != null && !home.isBlank()) candidates.add(Path.of(home, "scripts", "visual-qa.mjs"));
        candidates.add(Path.of("scripts", "visual-qa.mjs").toAbsolutePath());
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve("scripts/visual-qa.mjs"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
