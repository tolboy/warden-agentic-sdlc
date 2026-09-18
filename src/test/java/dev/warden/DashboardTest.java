package dev.warden;

import dev.warden.approval.ApprovalStore;
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
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

        Path liveSummary = writeSummary(root, "run-live", liveSummary());
        new ApprovalStore(root).createSuccess("run-live", "demo-task", "VERDICT_PROSE_MARKER",
                liveSummary, "sha256:candidate");
        Path oldSummary = writeSummary(root, "run-old", oldSummary());
        writeSummary(root, "run-dry", drySummary());
        Files.setLastModifiedTime(liveSummary, FileTime.from(Instant.parse("2026-09-18T12:00:00Z")));
        Files.setLastModifiedTime(oldSummary, FileTime.from(Instant.parse("2026-09-17T12:00:00Z")));

        var snapshot = Dashboard.snapshot(root, UserConfig.load(root.resolve("empty-home")));
        var rows = (List<?>) snapshot.get("attempts");
        check.eq("one stable record after update", 1, rows.size());
        var row = (Map<?, ?>) rows.get(0);
        check.eq("title-free stage identity survives", "review-second", row.get("stage"));
        check.eq("launch update keeps role identity", "reviewer", row.get("role"));
        check.eq("controller death is unknown, not completed", "unknown_controller_stopped", row.get("view_state"));
        check.that("public data excludes raw output and arbitrary launch keys", !Json.write(rows).contains("secret"));
        check.eq("projection is not evidence", false, Json.parseObject(Files.readString(run.resolve("view-reviewer.json"))).get("evidence"));
        RoleView.update(run, "../escape", Map.of("value", "bad"));
        check.that("view names cannot escape directory", !Files.exists(root.resolve("escape.json")));

        var runs = (List<?>) snapshot.get("runs");
        check.eq("dry-run summaries are omitted from the run list", 2, runs.size());
        check.eq("hidden dry-run count is kept", 1L, snapshot.get("dry_runs_hidden"));
        var newest = (Map<?, ?>) runs.get(0);
        var older = (Map<?, ?>) runs.get(1);
        check.eq("newest run is first", "run-live", newest.get("run_id"));
        check.eq("older run follows", "run-old", older.get("run_id"));
        check.eq("task identity is copied", "demo-task", newest.get("task_id"));
        check.eq("stop reason is copied", "ready_for_human", newest.get("reason"));
        check.eq("review passed is copied", true, newest.get("candidate_review_passed"));
        check.eq("open blockers are copied", 1L, newest.get("open_blocking_findings"));
        check.eq("writer vendors are copied", List.of("codex"), newest.get("writer_vendors"));
        var chain = (Map<?, ?>) newest.get("chain");
        check.eq("chain runs are copied", List.of("run-prior", "run-live"), chain.get("runs"));
        check.eq("chain role runs are copied", 4L, chain.get("role_runs"));
        check.eq("chain cost is copied", 1.25, chain.get("cost_usd"));
        check.eq("chain unpriced calls are copied", 1L, chain.get("unpriced_calls"));
        check.eq("chain fix attempts are copied", 2L, chain.get("fix_attempts"));
        check.eq("chain elapsed is copied", 90L, chain.get("elapsed_seconds"));
        check.eq("chain elapsed known is copied", true, chain.get("elapsed_known"));
        check.eq("chain calls remaining are copied", 2L, chain.get("calls_remaining"));
        check.that("chain omits maxima that are not in the projection", !chain.containsKey("max_role_runs"));
        var steps = (List<?>) newest.get("steps");
        check.eq("steps keep order", 2, steps.size());
        check.eq("reused step names the source run", "run-prior", ((Map<?, ?>) steps.get(0)).get("reused_from"));
        check.eq("fix step names the stage it repairs", "review", ((Map<?, ?>) steps.get(1)).get("fix_for"));
        check.that("steps omit report paths", !Json.write(steps).contains("ABS_PATH_MARKER"));
        var coverage = (List<?>) newest.get("review_coverage");
        check.eq("coverage copies assurance", "independent", ((Map<?, ?>) coverage.get(0)).get("assurance"));
        check.eq("coverage copies blocking count", 1L, ((Map<?, ?>) coverage.get(0)).get("blocking_findings"));
        var pending = (List<?>) newest.get("pending_stages");
        check.eq("pending stage is copied", "look", ((Map<?, ?>) pending.get(0)).get("stage"));
        var decision = (Map<?, ?>) newest.get("decision");
        check.eq("decision state is pending", "pending", decision.get("state"));
        check.eq("decision kind is success", "success", decision.get("kind"));
        check.eq("decision options are the success pair", List.of("accept", "reject"), decision.get("options"));
        check.that("decision omits verdict prose", !decision.containsKey("reason"));
        var gate = (Map<?, ?>) newest.get("orca_gate");
        check.eq("gate id is copied", "gate-1", gate.get("gate_id"));
        check.eq("gate published is copied", true, gate.get("published"));
        check.that("gate omits unpublished fields", !gate.containsKey("reason"));
        var next = (Map<?, ?>) newest.get("next_step");
        check.eq("structured next step kind wins over the sentence", "accept_or_reject", next.get("kind"));
        check.eq("structured next step summary is copied", "accept or reject the candidate", next.get("summary"));
        var budget = (Map<?, ?>) newest.get("budget_plan");
        check.eq("budget cap is copied", 6L, budget.get("requested_cap"));
        check.eq("budget floor is copied", 3L, budget.get("minimum_success_calls"));
        check.that("budget omits paying stage names", !budget.containsKey("paying_stages"));
        check.that("run list does not include the dry-run id",
                runs.stream().noneMatch(item -> "run-dry".equals(((Map<?, ?>) item).get("run_id"))));
        String projected = Json.write(snapshot);
        check.that("summary goal marker is absent from the snapshot", !projected.contains("SECRET_GOAL_MARKER"));
        check.that("finding message marker is absent from the snapshot", !projected.contains("SECRET_FINDING_MARKER"));
        check.that("decision verdict prose is absent from the snapshot", !projected.contains("VERDICT_PROSE_MARKER"));

        try (var dashboard = new Dashboard(root, 0); var client = HttpClient.newHttpClient()) {
            dashboard.start();
            var page = client.send(HttpRequest.newBuilder(URI.create(dashboard.url())).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("dashboard reachable over loopback", 200, page.statusCode());
            check.contains("UI treats configured fields as text", page.body(), "td.textContent");
            check.contains("runs section is on the page", page.body(), "Запуски");
            check.contains("run timeline is a details element", page.body(), "createElement('details')");
            check.that("page script never assigns innerHTML from data", !page.body().contains("innerHTML"));
            var state = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/state")).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("state endpoint readable", 200, state.statusCode());
            check.that("API publishes no raw transcripts", !state.body().contains("secret transcript"));
            check.that("API publishes no summary goal", !state.body().contains("SECRET_GOAL_MARKER"));
            check.that("API publishes no finding message", !state.body().contains("SECRET_FINDING_MARKER"));
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

    private static Path writeSummary(Path root, String runId, Map<String, Object> body) throws Exception {
        Path file = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(body));
        return file;
    }

    private static Map<String, Object> liveSummary() {
        Map<String, Object> reused = new LinkedHashMap<>();
        reused.put("step", "implementer");
        reused.put("stage", "implement");
        reused.put("attempt", 0L);
        reused.put("ok", true);
        reused.put("code", "ok");
        reused.put("profile", "codex-implement");
        reused.put("vendor", "codex");
        reused.put("cost_usd", 0.4);
        reused.put("reused_from", "run-prior");
        reused.put("report", "ABS_PATH_MARKER");
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("step", "implementer");
        fix.put("stage", "implement");
        fix.put("attempt", 1L);
        fix.put("ok", true);
        fix.put("code", "ok");
        fix.put("profile", "codex-implement");
        fix.put("vendor", "codex");
        fix.put("cost_usd", 0.85);
        fix.put("fix_for", "review");
        fix.put("blocking_findings", 0L);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("stage", "review");
        coverage.put("role", "reviewer");
        coverage.put("source", "executed");
        coverage.put("ok", true);
        coverage.put("blocking_findings", 1L);
        coverage.put("profile", "grok-review");
        coverage.put("vendor", "grok");
        coverage.put("assurance", "independent");
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", "F1");
        finding.put("severity", "P1");
        finding.put("message", "SECRET_FINDING_MARKER");
        finding.put("expected", "green");
        finding.put("actual", "red");
        Map<String, Object> round = new LinkedHashMap<>();
        round.put("stage", "review");
        round.put("findings", List.of(finding));
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("runs", List.of("run-prior", "run-live"));
        chain.put("role_runs", 4L);
        chain.put("cost_usd", 1.25);
        chain.put("unpriced_calls", 1L);
        chain.put("fix_attempts", 2L);
        chain.put("elapsed_seconds", 90L);
        chain.put("elapsed_known", true);
        chain.put("calls_remaining", 2L);
        chain.put("max_role_runs", 6L);
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("kind", "accept_or_reject");
        next.put("summary", "accept or reject the candidate");
        next.put("commands", List.of("warden approve run-live --decision accept"));
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("requested_cap", 6L);
        budget.put("minimum_success_calls", 3L);
        budget.put("paying_stages", List.of("implement", "review"));
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("published", true);
        gate.put("gate_id", "gate-1");
        gate.put("reason", "published");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", "run-live");
        body.put("task_id", "demo-task");
        body.put("ok", true);
        body.put("reason", "ready_for_human");
        body.put("next_action", "human_gate");
        body.put("prepare", "off");
        body.put("risk", "medium");
        body.put("dry_run", false);
        body.put("candidate_review_passed", true);
        body.put("open_blocking_findings", 1L);
        body.put("role_runs", 3L);
        body.put("total_cost_usd", 1.25);
        body.put("unpriced_calls", 1L);
        body.put("attempts_used", 1L);
        body.put("continued_from", "run-prior");
        body.put("writer_vendors", List.of("codex"));
        body.put("goal", "SECRET_GOAL_MARKER ship the button");
        body.put("finding_history", List.of(round));
        body.put("chain", chain);
        body.put("steps", List.of(reused, fix));
        body.put("review_coverage", List.of(coverage));
        body.put("pending_stages", List.of(Map.of("stage", "look", "reason", "not_reached")));
        body.put("next_step", next);
        body.put("safe_next_step", "warden approve run-live --decision accept|reject");
        body.put("budget_plan", budget);
        body.put("orca_gate", gate);
        return body;
    }

    private static Map<String, Object> oldSummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", "run-old");
        body.put("task_id", "older-task");
        body.put("ok", false);
        body.put("reason", "budget_exhausted");
        body.put("safe_next_step", "raise the call ceiling");
        return body;
    }

    private static Map<String, Object> drySummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", "run-dry");
        body.put("task_id", "demo-task");
        body.put("ok", true);
        body.put("dry_run", true);
        body.put("goal", "SECRET_GOAL_MARKER should stay hidden even from a dry run");
        return body;
    }
}
