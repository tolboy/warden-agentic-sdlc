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

            occupiedPortChecks(check, project);
        } finally {
            deleteTree(sandbox);
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
    private void occupiedPortChecks(Check check, Path project) throws Exception {
        try (java.net.ServerSocket squatter = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = squatter.getLocalPort();
            Thread answering = Thread.ofVirtual().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (java.net.Socket client = squatter.accept();
                         var out = client.getOutputStream()) {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi")
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
