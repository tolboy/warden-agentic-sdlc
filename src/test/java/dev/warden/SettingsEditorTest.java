package dev.warden;

import dev.warden.config.ConfigFileStore;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.dashboard.Dashboard;
import dev.warden.dashboard.NextRun;
import dev.warden.dashboard.SettingsEditor;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SettingsEditorTest implements Suite {
    public String name() { return "settings-editor"; }
    public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-settings-");
        Path root = sandbox.resolve("project"), home = sandbox.resolve("home");
        try {
            NextRunTest.fixture(home, root);
            Path taskFile = root.resolve(".warden/tasks/hello.yaml"), policyFile = home.resolve("policy.yaml");
            String original = Files.readString(taskFile);
            String commented = "# top-level comment\r\n" + original.replace("\n", "\r\n")
                    + "# tail comment\r\n";
            Files.writeString(taskFile, commented);
            NextRun next = new NextRun(root, home, p -> true);
            SettingsEditor editor = new SettingsEditor(next);
            Map<String, Object> limits = Map.of("max_role_runs", 5L, "max_cost_usd", 4.5, "cost_cap", "strict",
                    "max_elapsed_minutes", 8L, "max_fix_attempts", 1L, "timeout_minutes", 2L, "gate_ttl_hours", 3L);
            Map<String, Object> changes = Map.of("use", Map.of("profile", Map.of("review", "reader-c"),
                    "effort", Map.of("review", "high")), "limits", limits);
            Map<String, Object> request = request(next, "task", changes);
            var preview = editor.preview(request);
            check.eq("a draft budget is checked without a write", commented, Files.readString(taskFile));
            check.eq("admitted task can be applied", true, preview.get("apply_allowed"));
            check.that("reviewable diff shows budgets and use", preview.get("diff").toString().contains("+budgets:")
                    && preview.get("diff").toString().contains("reader-c"));
            check.that("unsaved budgets cannot export a misleading command", !preview.containsKey("exports"));
            var badHash = applying(request, preview); badHash.put("preview_sha256", "not-reviewed");
            check.rejects("Apply must match the reviewed candidate", "Settings changed", () -> editor.apply(badHash));
            var editedRequest = new LinkedHashMap<>(applying(request, preview));
            editedRequest.put("changes", Map.of("limits", Map.of("max_role_runs", 6L)));
            check.rejects("reusing a diff hash for different changes fails", "Settings changed", () -> editor.apply(editedRequest));
            var saved = editor.apply(applying(request, preview));
            check.eq("Apply reports success", true, saved.get("ok"));
            check.eq("backup is the exact predecessor", commented, Files.readString(Path.of((String) saved.get("backup"))));
            String after = Files.readString(taskFile);
            check.that("file newline style is retained", after.contains("\r\n") && !after.replace("\r\n", "").contains("\n"));
            check.that("comments survive edited sections", after.contains("# top-level comment") && after.contains("# tail comment"));
            TaskSpec parsed = TaskSpec.parse(after, taskFile.toString());
            check.eq("saved call cap reaches runtime parser", 5L, parsed.budget().maxRoleRuns());
            check.eq("saved cost cap reaches runtime parser", "strict", parsed.budget().costCap());
            check.eq("saved task overlay replaces prior effort", "high", ((Map<?, ?>) parsed.use().toMap().get("effort")).get("review"));
            check.rejects("double Apply cannot reuse the old version", "Settings changed", () -> editor.apply(applying(request, preview)));
            // Compare the actual CLI path with the preview of the modified task.
            List<String> narration = new ArrayList<>();
            var runtime = new TaskLoop(new ProcessRunner()).withAvailability(p -> true).withProgress(narration::add)
                    .run(new ConfigLoader().load(root, "hello"), UserConfig.load(home), "settings-dry", true);
            check.eq("saved limits work in CLI dry-run", "dry_run", runtime.reason());
            check.that("CLI explains the saved strict cap without calling it a threshold", narration.stream()
                    .anyMatch(line -> line.contains("strict cap reserved")) && narration.stream().noneMatch(line -> line.contains("not a strict cap")));
            check.eq("edited budget and runtime plan agree", Json.write(((Map<?, ?>) preview.get("preview")).get("budget_plan")),
                    Json.write(runtime.summaryReport().get("budget_plan")));
            var unchanged = editor.preview(request(next, "task", Map.of()));
            check.eq("no-op has no Apply", false, unchanged.get("apply_allowed"));
            check.that("saved settings have CLI export", unchanged.containsKey("exports"));
            check.rejects("bad numeric type is refused", "numeric", () -> editor.preview(request(next, "task", Map.of("limits", Map.of("max_role_runs", "5")))));
            check.rejects("fractional call cap is refused", "numeric", () -> editor.preview(request(next, "task", Map.of("limits", Map.of("max_role_runs", 3.5)))));
            check.rejects("unknown limits cannot write arbitrary task keys", "Unknown", () -> editor.preview(request(next, "task", Map.of("limits", Map.of("authority", true)))));
            var tooSmall = editor.preview(request(next, "task", Map.of("limits", Map.of("max_role_runs", 1L))));
            check.eq("a cap below required calls blocks Apply", false, tooSmall.get("apply_allowed"));
            var tooCheap = editor.preview(request(next, "task", Map.of("limits", Map.of("max_cost_usd", 0.1))));
            check.eq("strict cap preflight blocks underfunded settings", false, tooCheap.get("apply_allowed"));
            var sameVendor = editor.preview(request(next, "task", Map.of("use", Map.of("profile", Map.of("review", "reader-a")))));
            check.eq("task Apply cannot waive reviewer independence", false, sameVendor.get("apply_allowed"));
            check.rejects("unknown profile does not silently persist", "Resolve preview", () -> {
                var r = request(next, "task", Map.of("use", Map.of("profile", Map.of("review", "ghost"))));
                editor.apply(applying(r, editor.preview(r)));
            });
            var deleting = new LinkedHashMap<String, Object>();
            deleting.put("max_elapsed_minutes", null); deleting.put("max_fix_attempts", null);
            var remove = request(next, "task", Map.of("use", Map.of(), "limits", deleting));
            editor.apply(applying(remove, editor.preview(remove)));
            var resolved = new ConfigLoader().load(root, "hello").resolved();
            check.eq("removing optional time cap stays unbounded", null, resolved.budget().maxElapsedMinutes());
            check.eq("removing task fixes inherits project", 2L, resolved.maxFixAttempts());
            check.eq("saved use can be removed", true, new ConfigLoader().load(root, "hello").task().use().isEmpty());
            var describe = editor.describe("hello");
            check.eq("personal UI sees current quota policy", "confirm", describe.get("on_quota_exhausted"));
            Map<String, Object> personalChanges = Map.of("roles", Map.of("reviewer", Map.of("profiles", List.of("reader-c", "reader-b"), "strategy", "first")),
                    "on_quota_exhausted", "auto", "on_vendor_failure", "confirm", "max_per_stage", 8L,
                    "max_attempts", 2L, "backoff_seconds", 12L, "repair_reserve", "partial");
            String profileBefore = Files.readString(home.resolve("profiles/reader-c.yaml"));
            var personal = request(next, "personal", personalChanges);
            var personalPreview = editor.preview(personal);
            check.eq("personal policy preview is valid", true, personalPreview.get("apply_allowed"));
            editor.apply(applying(personal, personalPreview));
            Policy policy = Policy.parse(Files.readString(policyFile), policyFile.toString());
            check.eq("personal fallback order saved", List.of("reader-c", "reader-b"), policy.roles().get("reviewer").profiles());
            check.eq("policy retry is saved", 2L, policy.rateLimitRetry().maxAttempts());
            check.eq("policy failover count uses parser bound", 8, policy.maxVendorFailovers());
            check.eq("saving policy does not restamp profiles", profileBefore, Files.readString(home.resolve("profiles/reader-c.yaml")));
            check.rejects("profile role mismatch is refused", "does not fill", () -> editor.preview(request(next, "personal", Map.of("roles", Map.of("reviewer", Map.of("profiles", List.of("writer")))))));
            check.rejects("duplicate fallback is refused", "unique", () -> editor.preview(request(next, "personal", Map.of("roles", Map.of("reviewer", Map.of("profiles", List.of("reader-c", "reader-c")))))));
            check.rejects("fractional retry setting is refused", "Integer", () -> editor.preview(request(next, "personal", Map.of("max_attempts", 1.5))));
            check.rejects("role assurance cannot be relaxed via this editor", "Unknown", () -> editor.preview(request(next, "personal", Map.of("roles", Map.of("reviewer", Map.of("require_independent_vendor", false))))));
            var stale = request(next, "task", Map.of("limits", Map.of("max_role_runs", 6L)));
            var stalePreview = editor.preview(stale);
            Files.writeString(home.resolve("profiles/reader-c.yaml"), profileBefore + "\n# external edit\n");
            check.rejects("profile edit invalidates a reviewed task diff", "Settings changed", () -> editor.apply(applying(stale, stalePreview)));
            Files.writeString(home.resolve("profiles/reader-c.yaml"), profileBefore);
            String policyBefore = Files.readString(policyFile);
            Files.writeString(policyFile, policyBefore + "\n# someone else changed policy\n");
            check.rejects("personal policy edit also invalidates task diff", "Settings changed", () -> editor.apply(applying(stale, stalePreview)));
            Files.writeString(policyFile, policyBefore);
            var concurrent = request(next, "task", Map.of("limits", Map.of("max_role_runs", 8L)));
            var concurrentApply = applying(concurrent, editor.preview(concurrent));
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> contender = () -> {
                    start.await();
                    try { return Boolean.TRUE.equals(editor.apply(concurrentApply).get("ok")); }
                    catch (NextRun.Conflict | ConfigFileStore.Refused refused) { return false; }
                };
                var one = pool.submit(contender); var two = pool.submit(contender); start.countDown();
                check.eq("only one concurrent Apply of the same reviewed diff commits", 1, (one.get() ? 1 : 0) + (two.get() ? 1 : 0));
            }
            served(check, root, next);
        } finally {
            try (var files = Files.walk(sandbox)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) { file.toFile().setWritable(true); Files.deleteIfExists(file); }
            }
        }
    }
    private static Map<String, Object> request(NextRun next, String target, Map<String, Object> changes) throws Exception {
        return Map.of("task", "hello", "revision", next.describe("hello").get("revision"), "target", target, "changes", changes);
    }
    private static Map<String, Object> applying(Map<String, Object> request, Map<String, Object> preview) {
        Map<String, Object> result = new LinkedHashMap<>(request); result.put("preview_sha256", preview.get("preview_sha256")); return result;
    }
    private static void served(Check check, Path root, NextRun next) throws Exception {
        try (var dashboard = new Dashboard(root, 0, null, next); var client = HttpClient.newHttpClient()) {
            dashboard.start(); URI base = URI.create(dashboard.url());
            String html = client.send(HttpRequest.newBuilder(base).build(), HttpResponse.BodyHandlers.ofString()).body();
            String token = html.split("<meta name=\"warden-token\" content=\"", 2)[1].split("\"", 2)[0];
            var request = request(next, "task", Map.of("limits", Map.of("max_role_runs", 7L)));
            var preview = post(client, base, "preview", Json.write(request), token, null);
            check.eq("HTTP preview has no write", 200, preview.statusCode());
            String body = Json.write(applying(request, Json.parseObject(preview.body())));
            check.eq("tokenless settings Apply is refused", 403, post(client, base, "apply", body, "", null).statusCode());
            check.eq("cross-origin settings Apply is refused", 403, post(client, base, "apply", body, token, "http://outside.invalid").statusCode());
            check.eq("oversized settings Apply is refused", 413, post(client, base, "apply", " ".repeat(32769), token, null).statusCode());
            check.eq("Apply cannot receive a filepath", 400, post(client, base, "apply", "{\"file\":\"outside\"}", token, null).statusCode());
            check.eq("same-page reviewed Apply works", 200, post(client, base, "apply", body, token, null).statusCode());
            check.eq("HTTP stale Apply is a conflict", 409, post(client, base, "apply", body, token, null).statusCode());
            check.eq("personal settings describe works", 200, client.send(HttpRequest.newBuilder(base.resolve("api/settings?task=hello")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    private static HttpResponse<String> post(HttpClient client, URI base, String action, String body, String token, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve("api/settings/" + action)).header("X-Warden-Token", token);
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
