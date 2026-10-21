package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.dashboard.Dashboard;
import dev.warden.dashboard.NextRun;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exercise the HTTP builder, real resolver and real CLI dry run on one contract. */
public final class NextRunTest implements Suite {
    @Override public String name() { return "next-run"; }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-next-run-");
        try {
            Path home = sandbox.resolve("home ' quoted"), root = sandbox.resolve("project ' quoted");
            fixture(home, root);
            if (UserConfig.load(home).policy() == null)
                throw new IllegalStateException("Fixture policy: " + UserConfig.load(home).problems());
            NextRun next = new NextRun(root, home, p -> true);
            Map<String, String> before = fingerprint(sandbox);
            Map<String, Object> description = next.describe("hello");
            check.eq("task goal is available before any preview", "Offline next-run test", description.get("goal"));
            check.eq("contract keeps review assurance", "independent", description.get("review_assurance"));
            check.eq("all workflow stages remain visible", 3, ((List<?>) description.get("stages")).size());
            Map<String, Object> override = Map.of("profile", Map.of("review", "reader-c"),
                    "effort", Map.of("review", "xhigh"));
            Map<String, Object> preview = next.preview(request(description, override));
            check.eq("declared independent variant is admitted", true, preview.get("settings_ok"));
            check.eq("settings preview creates no runs or writes", before, fingerprint(sandbox));
            check.eq("export keeps the explicit CLI choices", override, preview.get("override"));
            TaskSpec exported = TaskSpec.parse(TASK + preview.get("task_overlay"), "export.yaml");
            check.eq("exported task overlay round trips with the same meaning",
                    override, exported.use().toMap());
            check.that("generated command quotes an apostrophe in paths",
                    String.valueOf(preview.get("run_command")).contains(
                            System.getProperty("os.name", "").toLowerCase().contains("win") ? "''" : "'\"'\"'"));
            check.contains("export pins the preview config home", String.valueOf(preview.get("run_command")),
                    "WARDEN_CONFIG_HOME");
            check.eq("dry command is marked dry", "--dry-run", ((List<?>) preview.get("dry_run_argv")).getLast());
            check.eq("exported argv survives the actual CLI overlay parser",
                    override, RunOverride.fromArgs(((List<?>) preview.get("run_argv")).stream()
                            .map(String::valueOf).toArray(String[]::new)).toMap());

            Map<String, Object> blocked = next.preview(request(description,
                    Map.of("profile", Map.of("review", "reader-a"))));
            check.eq("same-vendor reader is refused before any writer", false, blocked.get("settings_ok"));
            check.eq("refused settings have no launch command", null, blocked.get("run_command"));
            check.eq("refused settings have no export", null, blocked.get("task_overlay"));
            check.contains("resolver explains the refused reader", Json.write(blocked.get("would_run")),
                    "same_vendor_as_writer");
            Map<String, Object> mechanical = next.preview(request(description,
                    Map.of("effort", Map.of("gates", "high"))));
            check.contains("mechanical stages cannot take an overlay", Json.write(mechanical.get("problems")),
                    "run_override_not_a_role_stage");
            Map<String, Object> missingStage = next.preview(request(description,
                    Map.of("profile", Map.of("unknown", "reader-c"))));
            check.contains("unknown stages cannot silently disappear", Json.write(missingStage.get("problems")),
                    "run_override_unknown_stage");
            Map<String, Object> undeliverable = next.preview(request(description,
                    Map.of("effort", Map.of("review", "not an effort"))));
            check.contains("effort must reach the selected vendor", Json.write(undeliverable.get("problems")),
                    "run_override_undeliverable");
            check.rejects("arbitrary task paths are refused", "task ID",
                    () -> next.describe("../outside"));
            check.rejects("unknown preview fields are refused", "Unknown preview field",
                    () -> next.preview(Map.of("task", "hello", "revision", description.get("revision"), "execute", true)));

            Path profile = home.resolve("profiles/reader-c.yaml");
            String original = Files.readString(profile);
            Files.writeString(profile, original + "\n# external edit\n");
            check.rejects("even a comment edit invalidates a preview", "Settings changed",
                    () -> next.preview(request(description, override)));
            Files.writeString(profile, original);
            Files.createDirectories(root.resolve(".warden/runs"));
            Files.writeString(root.resolve(".warden/runs/rotation.json"), "{\"reviewer\":1}\n");
            check.rejects("rotation changes invalidate the selection", "Settings changed",
                    () -> next.preview(request(description, override)));
            Files.delete(root.resolve(".warden/runs/rotation.json"));
            final boolean[] changedDuringProbe = { false };
            NextRun racing = new NextRun(root, home, p -> {
                if (!changedDuringProbe[0]) {
                    changedDuringProbe[0] = true;
                    try { Files.writeString(profile, original + "\n# changed during lookup\n"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                return true;
            });
            check.rejects("a change during readiness lookup cannot export a mixed preview", "Settings changed",
                    () -> racing.preview(request(description, override)));
            Files.writeString(profile, original);
            served(check, root, next, description, override);

            // This is the real dry-run path, not another call to the preview helper.
            var outcome = new TaskLoop(new ProcessRunner()).withAvailability(p -> true)
                    .withOverride(RunOverride.fromMap(override))
                    .run(new ConfigLoader().load(root, "hello"), UserConfig.load(home), "next-check", true);
            check.eq("real CLI dry run finishes without a vendor", "dry_run", outcome.reason());
            if (!"dry_run".equals(outcome.reason()))
                throw new IllegalStateException(String.valueOf(outcome.summaryReport().get("unexpected_error")));
            check.eq("GUI and CLI compute the exact same budget plan",
                    Json.write(preview.get("budget_plan")), Json.write(outcome.summaryReport().get("budget_plan")));
            List<?> gui = (List<?>) preview.get("would_run");
            List<?> cli = (List<?>) outcome.summaryReport().get("cast");
            for (String stage : List.of("implement", "review")) for (String key : List.of("profile", "vendor", "model", "effort", "runner")) {
                check.eq("GUI and CLI agree on " + stage + "." + key,
                        row(gui, stage).get(key), row(cli, stage).get(key));
            }
            var refused = new TaskLoop(new ProcessRunner()).withAvailability(p -> true)
                    .withOverride(RunOverride.fromMap(Map.of("profile", Map.of("review", "reader-a"))))
                    .run(new ConfigLoader().load(root, "hello"), UserConfig.load(home), "next-refused", true);
            check.eq("CLI names the same independence preflight", "independent_review_unavailable",
                    refused.summaryReport().get("would_stop"));
        } finally {
            try (var files = Files.walk(sandbox)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    file.toFile().setWritable(true);
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void served(Check check, Path root, NextRun next, Map<String, Object> description,
                               Map<String, Object> override) throws Exception {
        try (var dashboard = new Dashboard(root, 0, null, next); var client = HttpClient.newHttpClient()) {
            dashboard.start();
            URI base = URI.create(dashboard.url());
            String html = client.send(HttpRequest.newBuilder(base).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            String token = html.split("<meta name=\"warden-token\" content=\"", 2)[1].split("\"", 2)[0];
            var get = client.send(HttpRequest.newBuilder(base.resolve("api/next-run?task=hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check.eq("task builder is available on loopback", 200, get.statusCode());
            check.eq("HTTP task snapshot is the snapshot selected in the form", description.get("revision"),
                    Json.parseObject(get.body()).get("revision"));
            String body = Json.write(request(description, override));
            check.eq("a tokenless preview is refused", 403, post(client, base, body, "", null).statusCode());
            check.eq("cross-origin preview is refused", 403,
                    post(client, base, body, token, "http://outside.invalid").statusCode());
            check.eq("oversized preview is refused", 413,
                    post(client, base, " ".repeat(32769), token, null).statusCode());
            check.eq("malformed JSON is refused", 400,
                    post(client, base, "{", token, null).statusCode());
            check.eq("unknown fields are refused over HTTP", 400,
                    post(client, base, "{\"task\":\"hello\",\"revision\":\"old\",\"execute\":true}", token, null).statusCode());
            var good = post(client, base, body, token, null);
            check.eq("same-panel preview succeeds", 200, good.statusCode());
            check.eq("HTTP preview reports admitted settings", true, Json.parseObject(good.body()).get("settings_ok"));
            check.eq("old revision produces a conflict", 409,
                    post(client, base, body.replace(String.valueOf(description.get("revision")), "old"), token, null).statusCode());
            check.eq("preview has no dispatch endpoint", 405,
                    client.send(HttpRequest.newBuilder(base.resolve("api/next-run/run"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private static HttpResponse<String> post(HttpClient client, URI base, String body, String token,
                                              String origin) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("api/next-run/preview"))
                .header("X-Warden-Token", token).header("Content-Type", "application/json");
        if (origin != null) request.header("Origin", origin);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> request(Map<String, Object> description, Map<String, Object> override) {
        return Map.of("task", "hello", "revision", description.get("revision"), "override", override);
    }

    private static Map<?, ?> row(List<?> rows, String stage) {
        return rows.stream().map(r -> (Map<?, ?>) r).filter(r -> stage.equals(r.get("stage")))
                .findFirst().orElseThrow();
    }

    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) result.put(root.relativize(file).toString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }

    private static final String TASK = """
            version: 1
            id: hello
            goal: Offline next-run test
            risk: medium
            scope: app
            budgets: { max_role_runs: 3, max_cost_usd: 20, max_elapsed_minutes: 5 }
            """;

    static void fixture(Path home, Path root) throws Exception {
        Files.createDirectories(home.resolve("profiles"));
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(home.resolve("prompt.txt"), "Perform the task described in the context.\n");
        Files.writeString(root.resolve("src/hello.txt"), "hello\n");
        Files.writeString(root.resolve(".warden/project.yaml"), """
                version: 1
                project: next-run-test
                checks: { fast: ["git diff --check"] }
                scopes: { app: [src] }
                defaults: { checks: fast, risk: medium }
                """);
        Files.writeString(root.resolve(".warden/tasks/hello.yaml"), TASK
                + "use:\n  review: { effort: medium }\n");
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [writer], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [reader-b, reader-c, reader-a], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix }
                    - { stage: review, run: role, role: reviewer, when: [review_required], on_fail: stop, on_findings: fix }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        for (String name : List.of("writer", "reader-a", "reader-b", "reader-c")) {
            String vendor = name.equals("writer") || name.equals("reader-a") ? "a" : name.substring(name.length() - 1);
            Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                    version: 1
                    profile: %s
                    role: %s
                    vendor: %s
                    model: model-%s
                    effort: high
                    command: %s
                    args: ["--effort", "{{effort}}"]
                    prompt_template: prompt.txt
                    read_only: %s
                    limits: { max_cost_usd: 0.5 }
                    verification: { verified_on: 2026-10-20 }
                    """.formatted(name, name.equals("writer") ? "implementer" : "reviewer", vendor,
                    vendor, Json.write(java), !name.equals("writer")));
        }
        git(root, "init", "-q");
        git(root, "add", ".");
        git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "-qm", "fixture");
        git(root, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private static void git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
