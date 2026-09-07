package dev.warden;

import dev.warden.config.UserConfig;
import dev.warden.dashboard.Dashboard;
import dev.warden.dashboard.RoleView;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DashboardTest implements Suite {
    public String name() { return "dashboard"; }
    public void run(Check check) throws Exception {
        var root = Files.createTempDirectory("warden-dashboard-");
        var run = root.resolve(".warden/runs/run");
        var record = new LinkedHashMap<String, Object>();
        record.put("stage", "review-second"); record.put("profile", "review-profile");
        record.put("role", "reviewer"); record.put("view_state", "running");
        record.put("controller_pid", -1L); record.put("raw_stdout", "secret transcript");
        record.put("command", "secret argv");
        record.put("launch", Map.of("status", "matched", "effective", Map.of("model", "opus", "effort", "max", "secret", "hidden")));
        RoleView.update(run, "reviewer", record);
        RoleView.update(run, "reviewer", Map.of("orca_dispatch_id", "ctx"));
        var rows = (java.util.List<?>) Dashboard.snapshot(root, UserConfig.load(root.resolve("empty-home"))).get("attempts");
        check.eq("one stable record after update", 1, rows.size());
        var row = (Map<?, ?>) rows.get(0);
        check.eq("title-free stage identity survives", "review-second", row.get("stage"));
        check.eq("launch update keeps role identity", "reviewer", row.get("role"));
        check.eq("controller death is unknown, not completed", "unknown_controller_stopped", row.get("view_state"));
        check.that("public data excludes raw output and arbitrary launch keys", !Json.write(rows).contains("secret"));
        check.eq("projection is not evidence", false, Json.parseObject(Files.readString(run.resolve("view-reviewer.json"))).get("evidence"));
        RoleView.update(run, "../escape", Map.of("value", "bad"));
        check.that("view names cannot escape directory", !Files.exists(root.resolve("escape.json")));
        try (var dashboard = new Dashboard(root, 0); var client = HttpClient.newHttpClient()) {
            dashboard.start();
            var page = client.send(HttpRequest.newBuilder(URI.create(dashboard.url())).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("dashboard reachable over loopback", 200, page.statusCode());
            check.contains("UI treats configured fields as text", page.body(), "td.textContent");
            var state = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/state")).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("state endpoint readable", 200, state.statusCode());
            check.that("API publishes no raw transcripts", !state.body().contains("secret transcript"));
            var crossOrigin = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/state"))
                    .header("Origin", "https://example.invalid").build(), HttpResponse.BodyHandlers.ofString());
            check.eq("other web origins cannot read state", 403, crossOrigin.statusCode());
            var write = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/state"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("dashboard has no mutation API", 405, write.statusCode());
            var file = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "profiles/test.yaml")).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("dashboard cannot serve arbitrary config files", 404, file.statusCode());
        }
    }
}
