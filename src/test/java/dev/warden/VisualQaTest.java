package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.gate.VisualQaRunner;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                      scenarios: ["700x400: testid=create visible"]
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
            a11yRankingChecks(check, sandbox);
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

        Files.writeString(project.resolve(".warden/tasks/until.yaml"), """
                version: 1
                id: until
                goal: Fix the Create button
                scope: code
                visual_qa:
                  required: true
                  scenarios: ["1280x720: testid=stage click -> wait-for=css=.hearth"]
                """);
        check.eq("wait-for is a sayable scenario", null,
                new VisualQaRunner(new ProcessRunner(), adapter)
                        .contractProblem(new ConfigLoader().load(project, "until")));

        // The rest of the grammar reads the last word as the assertion, so this is what an
        // operator writes — and what this module's own usage block used to document. Kept
        // inside the value it is the selector ".hearth visible", which no page matches: the
        // step would poll for its full 15 s and then report a control that is on the screen
        // as missing.
        check.eq("a wait-for that spells out `visible` means the same thing", null,
                new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                        taskWith(project, "spelled",
                                "1280x720: testid=stage click -> wait-for=css=.hearth visible")));
        String wrongWord = new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                taskWith(project, "clicky", "1280x720: wait-for=css=.hearth click"));
        check.contains("but a wait-for cannot end in an assertion it does not make",
                String.valueOf(wrongWord), "cannot end in");

        String weak = new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                taskWith(project, "copy", "1280x720: text=Save visible"));
        check.contains("a scenario that names its control only by copy is caught at preflight",
                String.valueOf(weak), "only by the copy on it");

        // The mixed contract is the whole point of asking per scenario: one anchored line
        // used to vouch for every other line in the file.
        check.contains("and one anchored scenario does not vouch for its neighbour",
                String.valueOf(new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                        taskWith(project, "mixed", "1280x720: css=.panel visible",
                                "1280x720: text=Save visible"))),
                "text=Save visible");
        check.eq("a text= step alongside an anchored one is still fine", null,
                new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                        taskWith(project, "chained",
                                "1280x720: testid=save click -> text=Saved visible")));
        check.eq("and a console-only contract needs no locator at all", null,
                new VisualQaRunner(new ProcessRunner(), adapter).contractProblem(
                        taskWith(project, "quiet", "1280x720: no-console-errors")));
    }

    /**
     * Ranking the accessibility snapshot, asked the same way as `--validate-only`:
     * node, no browser, no output directory. Document order on a real page is the skip
     * link and the banner; the property to hold is that two hundred nodes of chrome
     * above the control still leave the control in the snapshot, and early.
     */
    @SuppressWarnings("unchecked")
    private void a11yRankingChecks(Check check, Path sandbox) throws Exception {
        Path adapter = Path.of("scripts", "visual-qa.mjs").toAbsolutePath();
        if (!Files.isRegularFile(adapter) || !nodeAvailable()) return;

        List<Object> nodes = new ArrayList<>();
        nodes.add(axNode("button", "Ghost", true, box(0, 0, 40, 12)));
        for (int i = 0; i < 200; i++) {
            nodes.add(axNode("heading", "Chrome " + i, false, box(0, i, 40, 12)));
        }
        nodes.add(axNode("button", "", false, box(400, 10, 80, 24)));
        Map<String, Object> saveBox = box(10, 400, 80, 32);
        nodes.add(axNode("button", "Save", false, saveBox));

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("nodes", nodes);
        tree.put("probes", List.of(Map.of(
                "kind", "testid",
                "value", "save",
                "found", Map.of("x", 10, "y", 400, "width", 80, "height", 32, "text", "Save"))));
        Path treeFile = sandbox.resolve("a11y-tree.json");
        Files.writeString(treeFile, Json.write(tree));

        ProcessRunner.Result ranked = new ProcessRunner().run(
                List.of(nodeExecutable(), adapter.toString(),
                        "--rank-a11y",
                        "--scenario", "1280x720: testid=save visible",
                        "--tree", treeFile.toAbsolutePath().toString()),
                sandbox, Duration.ofSeconds(20));
        check.that("ranking answers without a browser", ranked.ok());
        Map<String, Object> body = Json.findLastObject(ranked.stdout());
        check.eq("and names itself as a ranking, not a contract check", "a11y_ranked",
                body == null ? null : body.get("code"));
        List<Map<String, Object>> a11y = listOfMaps(body == null ? null : body.get("a11y"));
        check.that("the snapshot stays bounded", a11y.size() <= 60);
        check.that("and is not empty", !a11y.isEmpty());
        check.eq("the control the scenario named is first, not lost under 200 chrome nodes",
                "Save", a11y.isEmpty() ? null : a11y.get(0).get("name"));
        check.eq("and it is a button", "button", a11y.isEmpty() ? null : a11y.get(0).get("role"));

        boolean unnamed = false;
        boolean ghost = false;
        boolean boxed = true;
        for (Map<String, Object> node : a11y) {
            if (!(node.get("box") instanceof Map<?, ?> box)
                    || !box.containsKey("x") || !box.containsKey("y")
                    || !box.containsKey("width") || !box.containsKey("height")) {
                boxed = false;
            }
            if ("button".equals(node.get("role")) && "".equals(node.get("name"))
                    && !Boolean.TRUE.equals(node.get("ignored"))) {
                unnamed = true;
            }
            if ("Ghost".equals(node.get("name"))) ghost = true;
        }
        check.that("every node in the snapshot carries a bounding box", boxed);
        check.that("an interactive node with no accessible name is kept", unnamed);
        check.that("an ignored node the scenario did not name is dropped", !ghost);

        Map<String, Object> ignoredSave = new LinkedHashMap<>();
        ignoredSave.put("nodes", List.of(axNode("button", "Save", true, saveBox)));
        ignoredSave.put("probes", List.of(Map.of(
                "kind", "testid", "value", "save",
                "found", Map.of("x", 10, "y", 400, "width", 80, "height", 32, "text", "Save"))));
        Path ignoredFile = sandbox.resolve("a11y-ignored.json");
        Files.writeString(ignoredFile, Json.write(ignoredSave));
        ProcessRunner.Result ignoredRank = new ProcessRunner().run(
                List.of(nodeExecutable(), adapter.toString(),
                        "--rank-a11y",
                        "--scenario", "1280x720: testid=save visible",
                        "--tree", ignoredFile.toAbsolutePath().toString()),
                sandbox, Duration.ofSeconds(20));
        Map<String, Object> ignoredBody = Json.findLastObject(ignoredRank.stdout());
        List<Map<String, Object>> ignoredA11y = listOfMaps(
                ignoredBody == null ? null : ignoredBody.get("a11y"));
        check.that("an ignored control the scenario named is kept as a finding",
                !ignoredA11y.isEmpty() && "Save".equals(ignoredA11y.get(0).get("name")));
        check.eq("and the ignored flag is still on it", Boolean.TRUE,
                ignoredA11y.isEmpty() ? null : ignoredA11y.get(0).get("ignored"));
    }

    private static Map<String, Object> axNode(String role, String name, boolean ignored,
                                              Map<String, Object> box) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("role", Map.of("value", role));
        node.put("name", Map.of("value", name));
        node.put("ignored", ignored);
        node.put("box", box);
        return node;
    }

    private static Map<String, Object> box(int x, int y, int width, int height) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("x", x);
        box.put("y", y);
        box.put("width", width);
        box.put("height", height);
        return box;
    }

    private static String nodeExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "node.exe" : "node";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : rows) {
            if (item instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
        }
        return out;
    }

    /** A task whose only interesting part is its scenarios. */
    private static ConfigLoader.Loaded taskWith(Path project, String id, String... scenarios)
            throws Exception {
        StringBuilder yaml = new StringBuilder("""
                version: 1
                id: %s
                goal: Fix the Create button
                scope: code
                visual_qa:
                  required: true
                  scenarios:
                """.formatted(id));
        for (String scenario : scenarios) yaml.append("    - \"").append(scenario).append("\"\n");
        Files.writeString(project.resolve(".warden/tasks/" + id + ".yaml"), yaml.toString());
        return new ConfigLoader().load(project, id);
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
        check.eq("wait-for adds its poll ceiling", 120L + 15,
                VisualQaRunner.adapterBudget(java.util.List.of(
                        "1280x720: testid=stage click -> wait-for=css=.hearth")).toSeconds());
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
                          scenarios: ["1280x720: testid=save visible"]
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
