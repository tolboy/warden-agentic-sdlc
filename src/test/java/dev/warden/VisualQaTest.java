package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.gate.VisualQaRunner;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;

public final class VisualQaTest implements Suite {
    @Override public String name() { return "visual qa"; }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-visual-qa-");
        try {
            Path project = sandbox.resolve("app");
            Files.createDirectories(project.resolve("src"));
            Files.createDirectories(project.resolve(".warden/tasks"));
            Files.writeString(project.resolve(".warden/project.yaml"), """
                    version: 1
                    project: app
                    base_ref: HEAD
                    checks:
                      fast: ["echo ok"]
                    scopes:
                      code: ["src"]
                    defaults:
                      checks: fast
                    """);
            Files.writeString(project.resolve(".warden/tasks/button.yaml"), """
                    version: 1
                    id: button
                    goal: Fix the Create button
                    scope: code
                    visual_qa:
                      required: true
                      scenarios: ["700x400:Create visible"]
                    """);
            Files.writeString(project.resolve("src/app.txt"), "x\n");
            git(project);
            ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "button");
            VisualQaRunner.Outcome missing = new VisualQaRunner(new ProcessRunner(),
                    Path.of("definitely-missing-visual-qa.mjs")).run(loaded, "vq-missing");
            check.eq("missing adapter fails closed", "visual_qa_unavailable", missing.code());
            check.that("and does not pass", !missing.ok());

            waitBudgetChecks(check);
            upgradeHostileServerChecks(check);
            occupiedPortChecks(check, project);
            contractPreflightChecks(check, project);
        } finally {
            deleteTree(sandbox);
        }
    }

    /**
     * The scenario grammar, asked before anything is paid.
     *
     * Run torch-1 spent a green implementer and a green independent review, then refused at the
     * browser stage because one scenario stated no assertion. That is a contract fault and the
     * operator's to fix; discovering it after the vendors have been billed is the defect.
     */
    private void contractPreflightChecks(Check check, Path project) throws Exception {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "button");

        // A missing adapter must not stop a run at preflight. Whether node and a browser exist
        // is the browser stage's business, and it has its own codes for saying so; refusing
        // here would trade one premature stop for another.
        check.eq("a missing adapter says nothing at preflight", null,
                new VisualQaRunner(new ProcessRunner(), Path.of("definitely-missing-visual-qa.mjs"))
                        .contractProblem(loaded));

        // Node is not a build dependency — `./build.sh && ./test.sh` must work on a bare JDK —
        // so the half of this that needs it is skipped rather than failed when it is absent.
        Path adapter = Path.of("scripts", "visual-qa.mjs").toAbsolutePath();
        if (!Files.isRegularFile(adapter) || !nodeAvailable()) return;

        check.eq("a contract that states an assertion passes preflight", null,
                new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(loaded));

        Files.writeString(project.resolve(".warden/tasks/silent.yaml"), """
                version: 1
                id: silent
                goal: Fix the Create button
                scope: code
                visual_qa:
                  required: true
                  scenarios: ["1280x720: wait 8"]
                """);
        ConfigLoader.Loaded silent = new ConfigLoader().load(project, "silent");
        String problem = new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(silent);
        check.that("a scenario that only waits is caught before a vendor is paid", problem != null);
        check.contains("and the message says what to write instead", String.valueOf(problem),
                "only waits");
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessRunner().run(
                    java.util.List.of(System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "node.exe" : "node", "--version"),
                    Path.of("."), java.time.Duration.ofSeconds(20)).ok();
        } catch (Exception noNode) {
            return false;
        }
    }

    /**
     * A task that declares its own start command, run while something else already answers at
     * its URL.
     *
     * This is not hypothetical. A live run of this harness against 127.0.0.1:4173 was served
     * by an unrelated SvelteKit app left running from another project, and the report was a
     * confident set of assertions about the wrong application. It failed that time, which was
     * luck. Warden now refuses to guess whose server it found.
     */
    /**
     * A server that behaves the way a Node dev server with a WebSocket on the same port
     * behaves: an ordinary HTTP/1.1 request is answered, and one carrying `Connection:
     * Upgrade` is swallowed, because the WebSocket layer claimed it and then found it was
     * not a WebSocket.
     *
     * Java's HttpClient defaults to HTTP/2, and over cleartext that means it sends exactly
     * that upgrade. Measured against Vite 8: three timeouts out of three, while the same
     * request pinned to HTTP/1.1 came back 200 in 7-23 ms. A live run lost its whole visual
     * stage to it — `visual_qa_unavailable`, which stops with no fix round — over a server
     * whose own log said "ready in 1784 ms".
     */
    private void upgradeHostileServerChecks(Check check) throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 4,
                java.net.InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (java.net.Socket client = socket.accept()) {
                        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                                client.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                        boolean upgrade = false;
                        for (String line = reader.readLine();
                             line != null && !line.isEmpty(); line = reader.readLine()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("connection:")
                                    && line.toLowerCase(java.util.Locale.ROOT).contains("upgrade")) {
                                upgrade = true;
                            }
                        }
                        if (upgrade) {
                            // Claimed by the WebSocket layer and never answered.
                            Thread.sleep(8_000);
                            continue;
                        }
                        client.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                    } catch (Exception stop) {
                        return;
                    }
                }
            });
            server.setDaemon(true);
            server.start();
            String url = "http://127.0.0.1:" + socket.getLocalPort() + "/";
            check.that("a server that swallows an h2c upgrade still answers the probe",
                    VisualQaRunner.httpOk(url));
        }
    }

    /**
     * A `wait` in a scenario is time the operator asked the page for. Charging it against
     * the fixed two-minute adapter ceiling turns a legal contract into
     * `visual_qa_unavailable` — a stop with no fix round, blaming the browser for a
     * contract that was doing exactly what it said.
     */
    private void waitBudgetChecks(Check check) {
        check.eq("no wait declared leaves the plain ceiling", 120L,
                VisualQaRunner.adapterBudget(java.util.List.of(
                        "1280x720: text=Create visible")).toSeconds());
        check.eq("declared waits are added on top", 120L + 30 + 45,
                VisualQaRunner.adapterBudget(java.util.List.of(
                        "1280x720: testid=stage click -> wait 30 -> css=.fire visible",
                        "700x400: testid=stage click -> wait 45s -> css=.fire visible")).toSeconds());
        check.eq("and are bounded, so a contract cannot ask for forever", 120L + 15 * 60,
                VisualQaRunner.adapterBudget(java.util.Collections.nCopies(400,
                        "1280x720: testid=stage click -> wait 100 -> css=.fire visible")).toSeconds());
    }

    private void occupiedPortChecks(Check check, Path project) throws Exception {
        try (java.net.ServerSocket squatter = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = squatter.getLocalPort();
            Thread answering = Thread.ofVirtual().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (java.net.Socket client = squatter.accept();
                         var out = client.getOutputStream()) {
                        // Read the request before answering it, the way a real server does.
                        // Writing a response and closing at once leaves the client request
                        // bytes unread in the receive buffer, and Windows answers a close
                        // with unread data by sending RST. That reset can reach the client
                        // before it has finished reading the response, and the probe then
                        // reports "nothing is serving this port" about a server that had
                        // already replied 200. About one run in three failed that way; the
                        // defect was in this double, not in the probe it was testing.
                        var request = new java.io.BufferedReader(new java.io.InputStreamReader(
                                client.getInputStream(),
                                java.nio.charset.StandardCharsets.US_ASCII));
                        for (String line = request.readLine();
                             line != null && !line.isEmpty(); line = request.readLine()) {
                            // the request head, drained
                        }
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (Exception done) {
                        return;
                    }
                }
            });
            try {
                Files.writeString(project.resolve(".warden/tasks/occupied.yaml"), """
                        version: 1
                        id: occupied
                        goal: Check a page that something else is already serving
                        scope: code
                        visual_qa:
                          required: true
                          start: "echo this project would start its own server"
                          url: "http://127.0.0.1:%d/"
                          scenarios: ["1280x720: text=Save visible"]
                        """.formatted(port));
                ConfigLoader.Loaded loaded = new ConfigLoader().load(project, "occupied");
                VisualQaRunner.Outcome occupied = new VisualQaRunner(new ProcessRunner())
                        .run(loaded, "vq-occupied");
                check.eq("the probe sees the stranger", Boolean.TRUE,
                        occupied.data().get("already_answering"));
                check.eq("a stranger on the port is a refusal, not a silent substitution",
                        "visual_qa_port_occupied", occupied.code());
                check.that("and the run does not pass", !occupied.ok());
                check.contains("the report says what to do about it",
                        String.valueOf(occupied.data().get("message")), "stop it");
                check.eq("and records that the task asked to start its own server",
                        Boolean.TRUE, occupied.data().get("start_declared_by_task"));
            } finally {
                answering.interrupt();
            }
        }
    }

    private static void git(Path project) throws Exception {
        ProcessRunner runner = new ProcessRunner();
        for (String[] command : new String[][] {
                {"git", "init", "-q", "-b", "main", "."},
                {"git", "config", "user.email", "test@example.invalid"},
                {"git", "config", "user.name", "test"},
                {"git", "add", "-A"},
                {"git", "commit", "-qm", "base"}
        }) {
            runner.run(java.util.Arrays.asList(command), project, java.time.Duration.ofSeconds(30));
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
