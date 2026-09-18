package dev.warden;

import dev.warden.json.Json;
import dev.warden.ledger.LedgerCompare;
import dev.warden.ledger.LedgerReader;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class LedgerCompareTest implements Suite {
    @Override public String name() { return "ledger-compare"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-ledger-compare-");
        try {
            Path project = root.resolve("project");
            Files.createDirectories(project.resolve(".warden"));
            Files.writeString(project.resolve(".warden/project.yaml"), """
                    version: 1
                    project: compare-fixture
                    """, StandardCharsets.UTF_8);

            List<Map<String, Object>> workflow = workflow();
            String workflowSha = sha256(Json.write(workflow));
            List<Map<String, Object>> rosterSteps = List.of(
                    step("implementer", "codex"),
                    step("reviewer", "grok"),
                    step("gates", null));

            Map<String, Object> first = baseSummary("chain-a", workflow, rosterSteps);
            first.put("reason", "ready_for_human");
            first.put("ok", true);
            first.put("attempts_used", 1L);
            first.put("chain", chain(List.of("chain-a"), 2L, 1.0d, 0L, 1L, 40L, true));
            first.put("open_blocking_findings", 0L);
            first.put("candidate_review_passed", true);
            first.put("goal", "SECRET_GOAL_TEXT should never reach the compare output");
            writeRun(project, "chain-a", first);

            Map<String, Object> last = baseSummary("chain-b", workflow, rosterSteps);
            last.put("reason", "ready_for_human");
            last.put("ok", true);
            last.put("attempts_used", 2L);
            last.put("chain", chain(List.of("chain-a", "chain-b"), 6L, 3.5d, 1L, 3L, 100L, true));
            last.put("open_blocking_findings", 0L);
            last.put("candidate_review_passed", true);
            last.put("finding_history", List.of(Map.of("message", "SECRET_FINDING_TEXT")));
            writeRun(project, "chain-b", last);

            Map<String, Object> solo = baseSummary("solo", workflow, rosterSteps);
            solo.put("reason", "ready_for_human");
            solo.put("ok", true);
            solo.put("attempts_used", 1L);
            solo.put("chain", chain(List.of("solo"), 4L, 1.25d, 0L, 1L, 50L, false));
            solo.put("open_blocking_findings", 0L);
            solo.put("candidate_review_passed", true);
            writeRun(project, "solo", solo);
            writeDecision(project, "solo", "accept",
                    "2026-09-01T00:00:00Z", "2026-09-01T00:10:00Z");

            Map<String, Object> stopped = baseSummary("stopped", workflow, rosterSteps);
            stopped.put("reason", "budget_exhausted");
            stopped.put("ok", false);
            stopped.put("attempts_used", 2L);
            stopped.put("chain", chain(List.of("stopped"), null, null, 0L, 2L, 80L, null));
            stopped.put("open_blocking_findings", 2L);
            stopped.put("candidate_review_passed", false);
            writeRun(project, "stopped", stopped);

            Map<String, Object> dry = baseSummary("preview", workflow, rosterSteps);
            dry.put("dry_run", true);
            dry.put("reason", "dry_run");
            dry.put("ok", true);
            dry.put("attempts_used", 0L);
            dry.put("chain", chain(List.of("preview"), 99L, 99.0d, 0L, 0L, 1L, true));
            writeRun(project, "preview", dry);

            Path corruptDir = project.resolve(".warden/runs/corrupt");
            Files.createDirectories(corruptDir);
            Files.writeString(corruptDir.resolve("task-run.json"), "{not json", StandardCharsets.UTF_8);

            Files.createDirectories(project.resolve(".warden/runs/stage-only"));
            Files.writeString(project.resolve(".warden/runs/stage-only/machine-gate.json"),
                    "{\"ok\":true}\n", StandardCharsets.UTF_8);

            Path baseline = root.resolve("baseline.json");
            Map<String, Object> complete = baselineRow("hermes+grok", "fix", "medium", true,
                    35L, 50L, 2.5d, 8L);
            Map<String, Object> incomplete = baselineRow("hermes+grok", "fix", "medium", false,
                    null, 20L, null, null);
            Files.writeString(baseline, Json.write(Map.of("rows", List.of(complete, incomplete))),
                    StandardCharsets.UTF_8);

            Map<String, Object> report = LedgerCompare.compare(project, baseline);
            String rendered = LedgerCompare.render(report);
            String blob = Json.write(report);

            check.eq("schema version is 1", 1L, report.get("schema_version"));
            check.eq("corrupt file marks the report incomplete", true, report.get("incomplete"));
            check.that("the note refuses to treat identical conditions as equal difficulty",
                    String.valueOf(report.get("note")).contains(
                            "Identical conditions do not prove equal task difficulty"));
            check.that("the note refuses to count a stopped task as a success",
                    String.valueOf(report.get("note")).contains("A stopped task is not a success"));
            check.that("goal text never reaches the output", !blob.contains("SECRET_GOAL_TEXT"));
            check.that("finding text never reaches the output", !blob.contains("SECRET_FINDING_TEXT"));
            String absolute = project.toAbsolutePath().normalize().toString();
            check.that("absolute paths do not leak",
                    !blob.contains(absolute) && !blob.contains(absolute.replace('\\', '/')));

            List<?> skipped = (List<?>) report.get("skipped");
            check.eq("one corrupt file is skipped", 1, skipped.size());
            Map<String, Object> skip = object(skipped.get(0));
            check.eq("skipped run id is the directory name", "corrupt", skip.get("run_id"));
            check.eq("skipped path is relative and includes the run id",
                    ".warden/runs/corrupt/task-run.json", skip.get("path"));

            List<?> groups = (List<?>) report.get("groups");
            check.eq("one warden group: chain, standalone and stopped share conditions",
                    1, groups.size());
            Map<String, Object> group = object(groups.get(0));
            check.eq("group source is warden", "warden", group.get("source"));
            check.eq("prepare is recorded on the group", "off", group.get("prepare"));
            check.eq("risk is recorded on the group", "medium", group.get("risk"));
            check.eq("workflow hash is SHA-256 of the workflow JSON", workflowSha,
                    group.get("workflow_sha256"));
            check.eq("roster is sorted distinct profiles", List.of("codex", "grok"),
                    group.get("roster"));
            check.eq("a chain is one unit", 3L, group.get("units"));
            check.eq("physical runs count the folded first link", 4L, group.get("runs"));
            check.eq("sample_size follows units", 3L, group.get("sample_size"));
            check.eq("three units are comparable", true, group.get("comparable"));

            Map<String, Object> outcomes = object(group.get("outcomes"));
            check.eq("accepted is the resolved accept", 1L, outcomes.get("accepted"));
            check.eq("ready_for_human is the pending last-of-chain", 1L,
                    outcomes.get("ready_for_human"));
            check.eq("stopped is the exhausted run, not the folded first link", 1L,
                    outcomes.get("stopped"));
            check.eq("rejected is none", 0L, outcomes.get("rejected"));
            check.eq("ready_for_human reason is counted twice (chain last and solo)", 2L,
                    object(outcomes.get("reasons")).get("ready_for_human"));
            check.eq("budget_exhausted is counted once", 1L,
                    object(outcomes.get("reasons")).get("budget_exhausted"));

            Map<String, Object> fix = object(group.get("fix_rounds"));
            check.eq("fix rounds known on every unit", 3L, fix.get("known_count"));
            check.eq("fix rounds unknown is zero", 0L, fix.get("unknown_count"));
            check.eq("fix rounds total uses chain.fix_attempts then attempts_used", 6L,
                    fix.get("total"));

            Map<String, Object> calls = object(group.get("role_runs"));
            check.eq("role_runs known when the chain recorded them", 2L, calls.get("known_count"));
            check.eq("stopped run without chain.role_runs is unknown, not zero", 1L,
                    calls.get("unknown_count"));
            check.eq("role_runs total is the known chain sums", 10L, calls.get("total"));

            Map<String, Object> cost = object(group.get("cost_usd"));
            check.eq("priced chain with unpriced_calls is unknown", 1L, cost.get("known_count"));
            check.eq("unpriced chain and missing cost are unknown", 2L, cost.get("unknown_count"));
            check.eq("known cost is the fully priced unit only", 1.25, cost.get("total"));
            check.eq("unpriced call count is reported beside the known sum", 1L,
                    cost.get("unpriced_calls"));

            Map<String, Object> elapsed = object(group.get("elapsed_seconds"));
            check.eq("elapsed_known false is unknown even when seconds are present", 1L,
                    elapsed.get("unknown_count"));
            check.eq("elapsed known counts the chain and the stopped run", 2L,
                    elapsed.get("known_count"));
            check.eq("elapsed total is the known seconds", 180L, elapsed.get("total"));

            Map<String, Object> wait = object(group.get("human_wait_millis"));
            check.eq("resolved accept contributes one wait", 1L, wait.get("known_count"));
            check.eq("pending and missing decisions stay unknown", 2L, wait.get("unknown_count"));
            check.eq("wait is updated_at minus created_at", 600_000L, wait.get("total"));

            Map<String, Object> blockers = object(group.get("open_blocking_findings"));
            check.eq("open blocking findings total the recorded counts", 2L, blockers.get("total"));
            Map<String, Object> passed = object(group.get("candidate_review_passed"));
            check.eq("candidate_review_passed total is the true count plus zeros for false",
                    2L, passed.get("total"));
            check.eq("false candidate_review_passed is known, not unknown", 0L,
                    passed.get("unknown_count"));

            List<?> baselineGroups = (List<?>) report.get("baseline_groups");
            check.eq("baseline rows with the same label form one group", 1, baselineGroups.size());
            Map<String, Object> baselineGroup = object(baselineGroups.get(0));
            check.eq("baseline source is manual_baseline", "manual_baseline",
                    baselineGroup.get("source"));
            check.eq("baseline label is recorded", "hermes+grok", baselineGroup.get("label"));
            check.eq("baseline task kind is recorded", "fix", baselineGroup.get("task_kind"));
            check.eq("baseline risk is recorded", "medium", baselineGroup.get("risk"));
            check.eq("two baseline rows are two units", 2L, baselineGroup.get("units"));
            check.eq("a two-row baseline is not comparable", false, baselineGroup.get("comparable"));
            check.eq("incomparable groups name the sample size", "fewer than 3 units",
                    baselineGroup.get("comparable_reason"));
            Map<String, Object> baselineOutcomes = object(baselineGroup.get("outcomes"));
            check.eq("completed baseline row maps to accepted", 1L, baselineOutcomes.get("accepted"));
            check.eq("incomplete baseline row maps to stopped", 1L, baselineOutcomes.get("stopped"));
            Map<String, Object> baselineCost = object(baselineGroup.get("cost_usd"));
            check.eq("one baseline cost is known", 1L, baselineCost.get("known_count"));
            check.eq("null baseline cost is unknown, not zero", 1L, baselineCost.get("unknown_count"));
            check.eq("known baseline cost is the complete row", 2.5, baselineCost.get("total"));
            Map<String, Object> baselineCalls = object(baselineGroup.get("role_runs"));
            check.eq("null baseline calls are unknown", 1L, baselineCalls.get("unknown_count"));
            Map<String, Object> baselineElapsed = object(baselineGroup.get("elapsed_seconds"));
            check.eq("baseline minutes become seconds", 4200L, baselineElapsed.get("total"));

            check.contains("text names the prepare condition", rendered, "off");
            check.contains("text names accepted/ready/stopped", rendered, "1/1/1");
            check.contains("text marks the warden group comparable", rendered, "yes");
            check.contains("text renders the baseline label", rendered, "hermes+grok");
            check.contains("text marks the baseline incomparable", rendered, "fewer than 3 units");
            check.contains("text keeps the stopped-is-not-success note", rendered,
                    "A stopped task is not a success");
            check.contains("text names the skipped corrupt run", rendered, "corrupt");
            check.that("text does not print goal text", !rendered.contains("SECRET_GOAL_TEXT"));

            check.rejects("unknown baseline keys are refused", "unknown key 'vendor'",
                    () -> LedgerCompare.compare(project, writeJson(root.resolve("bad-key.json"),
                            Map.of("rows", List.of(Map.of(
                                    "label", "x", "task_kind", "fix", "risk", "low",
                                    "completed", true, "vendor", "hermes"))))));
            check.rejects("completed must be a boolean", "completed must be a boolean",
                    () -> LedgerCompare.compare(project, writeJson(root.resolve("bad-completed.json"),
                            Map.of("rows", List.of(Map.of(
                                    "label", "x", "completed", "yes"))))));
            check.rejects("cost must be a number or null", "cost_usd must be a number or null",
                    () -> LedgerCompare.compare(project, writeJson(root.resolve("bad-cost.json"),
                            Map.of("rows", List.of(Map.of(
                                    "label", "x", "completed", true, "cost_usd", "$1"))))));

            Path home = root.resolve("home");
            Files.createDirectories(home);
            Map<String, Object> local = new LedgerReader().summarize(project);
            ProcessRunnerResult plain = cli(project, home, "ledger");
            check.eq("plain ledger without --compare still exits 0", 0, plain.exit);
            check.eq("plain ledger JSON is unchanged when --compare is omitted",
                    Json.write(local) + System.lineSeparator(), plain.stdout);

            ProcessRunnerResult compared = cli(project, home, "ledger", "--compare",
                    "--baseline-file", baseline.toString());
            check.eq("--compare exits 0", 0, compared.exit);
            Map<String, Object> cliReport = Json.parseObject(compared.stdout.strip());
            check.eq("CLI --compare reports the same units", 3L,
                    object(((List<?>) cliReport.get("groups")).get(0)).get("units"));

            ProcessRunnerResult text = cli(project, home, "ledger", "--compare", "--text",
                    "--baseline-file", baseline.toString());
            check.eq("--text exits 0", 0, text.exit);
            check.contains("CLI --text renders the table", text.stdout, "acc/rdy/stp");
            check.contains("CLI --text keeps the note", text.stdout, "A stopped task is not a success");

            ProcessRunnerResult refused = cli(project, home, "ledger", "--compare", "--import",
                    project.toString());
            check.that("--compare with --import is refused", refused.exit != 0);
            check.contains("the refusal names --import", refused.stderr, "--import");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Map<String, Object> baseSummary(String runId, List<Map<String, Object>> workflow,
                                                   List<Map<String, Object>> steps) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", "compare-task");
        summary.put("risk", "medium");
        summary.put("prepare", "off");
        summary.put("dry_run", false);
        summary.put("workflow", workflow);
        summary.put("steps", steps);
        return summary;
    }

    private static List<Map<String, Object>> workflow() {
        Map<String, Object> implement = new LinkedHashMap<>();
        implement.put("stage", "implement");
        implement.put("run", "role");
        implement.put("role", "implementer");
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("stage", "review");
        review.put("run", "role");
        review.put("role", "reviewer");
        List<Map<String, Object>> workflow = new ArrayList<>();
        workflow.add(implement);
        workflow.add(review);
        return workflow;
    }

    private static Map<String, Object> step(String name, String profile) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("step", name);
        row.put("ok", true);
        if (profile != null) row.put("profile", profile);
        return row;
    }

    private static Map<String, Object> chain(List<String> runs, Long roleRuns, Double cost,
                                             Long unpriced, Long fixAttempts, Long elapsed,
                                             Boolean elapsedKnown) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("runs", runs);
        if (roleRuns != null) chain.put("role_runs", roleRuns);
        if (cost != null) chain.put("cost_usd", cost);
        if (unpriced != null) chain.put("unpriced_calls", unpriced);
        if (fixAttempts != null) chain.put("fix_attempts", fixAttempts);
        if (elapsed != null) chain.put("elapsed_seconds", elapsed);
        if (elapsedKnown != null) chain.put("elapsed_known", elapsedKnown);
        return chain;
    }

    private static Map<String, Object> baselineRow(String label, String kind, String risk,
                                                   boolean completed, Long operatorMinutes,
                                                   Long elapsedMinutes, Double cost, Long calls) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("task_kind", kind);
        row.put("risk", risk);
        row.put("completed", completed);
        row.put("operator_minutes", operatorMinutes);
        row.put("elapsed_minutes", elapsedMinutes);
        row.put("cost_usd", cost);
        row.put("calls", calls);
        row.put("note", "manual observation");
        return row;
    }

    private static void writeRun(Path project, String runId, Map<String, Object> summary)
            throws IOException {
        Path directory = project.resolve(".warden/runs").resolve(runId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("task-run.json"),
                Json.writePretty(summary) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void writeDecision(Path project, String runId, String decision,
                                      String created, String updated) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", 1L);
        body.put("run_id", runId);
        body.put("task_id", "compare-task");
        body.put("state", "resolved");
        body.put("kind", "success");
        body.put("reason", "ready");
        body.put("options", List.of("accept", "reject"));
        body.put("created_at", created);
        body.put("updated_at", updated);
        body.put("summary_path", "task-run.json");
        body.put("candidate_fingerprint", "sha256:abc");
        body.put("decision", decision);
        body.put("actor", "operator");
        Files.writeString(project.resolve(".warden/runs").resolve(runId).resolve("decision.json"),
                Json.writePretty(body) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Path writeJson(Path file, Map<String, Object> body) throws IOException {
        Files.writeString(file, Json.write(body), StandardCharsets.UTF_8);
        return file;
    }

    private static String sha256(String material) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private record ProcessRunnerResult(int exit, String stdout, String stderr) {}

    private static ProcessRunnerResult cli(Path cwd, Path home, String... args) throws Exception {
        Files.createDirectories(cwd);
        Files.createDirectories(home);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add("dev.warden.Main");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.environment().put("WARDEN_CONFIG_HOME", home.toAbsolutePath().normalize().toString());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("cli timed out");
        }
        return new ProcessRunnerResult(process.exitValue(), stdout, stderr);
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        StringBuilder builder = new StringBuilder();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }
}
