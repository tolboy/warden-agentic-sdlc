package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.dashboard.DecisionPage;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision as a page in Orca's browser. Measured 2026-09-22: a run waiting on its human
 * gate could be answered only by typing `warden approve` or two `orca orchestration` commands,
 * which an operator who does not know them cannot do. The page must show what is being
 * decided, answer only through the same path as `warden approve`, and serve nothing else.
 */
public final class DecisionPageTest implements Suite {

    @Override public String name() { return "decision-page"; }

    private final HttpClient http = HttpClient.newHttpClient();

    @Override public void run(Check check) throws Exception {
        page(check);
        evidenceAPersonCanRead(check);
        retryOnThePageContinuesTheLoop(check);
    }

    /**
     * The first all-Orca run's page listed `1280x720-after-click-3.png` by name beside
     * `1280x720.png`; the two looked alike and the operator could not tell a second press
     * had been checked. The same run's readers left two P3 findings — the button moves 30 px
     * when the text hides, and the acceptance never checks the text — and the page said only
     * that every stage passed. Both are the loop's account, and the page has to carry it.
     */
    private void evidenceAPersonCanRead(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        new ApprovalStore(root).createSuccess("run-1", "hello", "every stage that ran passed",
                root.resolve(".warden/runs/run-1/task-run.json"), "fingerprint-shown");
        Path shots = root.resolve(".warden/runs/run-1--visual-qa-0/screenshots");
        Files.createDirectories(shots);
        for (String name : List.of("1280x720.png", "1280x720-after-click-1.png", "1280x720-after-click-3.png")) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "png", shots.resolve(name).toFile());
        }
        Files.writeString(shots.resolve("visual-qa.json"), Json.write(Map.of("ok", true, "scenarios", List.of(
                Map.of("raw", "1280x720: testid=toggle click -> testid=greeting hidden -> testid=toggle click -> testid=greeting visible",
                        "ok", true, "viewport", Map.of("width", 1280, "height", 720, "mobile", false),
                        "screenshot", shots.resolve("1280x720.png").toString(),
                        "steps", List.of(
                                Map.of("matcher", "testid=toggle", "assertion", "click", "ok", true,
                                        "screenshot_after", shots.resolve("1280x720-after-click-1.png").toString()),
                                Map.of("matcher", "testid=greeting", "assertion", "hidden", "ok", true),
                                Map.of("matcher", "testid=toggle", "assertion", "click", "ok", true,
                                        "screenshot_after", shots.resolve("1280x720-after-click-3.png").toString()),
                                Map.of("matcher", "testid=greeting", "assertion", "visible", "ok", true)))))));
        Path summaryFile = root.resolve(".warden/runs/run-1/task-run.json");
        Map<String, Object> summary = new LinkedHashMap<>(Json.parseObject(Files.readString(summaryFile)));
        summary.put("finding_history", List.of(
                Map.of("stage", "look", "attempt", 0, "findings", List.of(Map.of("id", "f-1", "severity", "P3",
                        "category", "product_defect", "status", "open",
                        "message", "Hiding the greeting moves the toggle button up 30px.",
                        "suggestion", "Reserve the greeting's space when it is hidden.")))));
        Files.writeString(summaryFile, Json.write(summary));

        String[] opened = {null};
        try (DecisionPage page = new DecisionPage(root, "run-1", (choice, note, expected) -> Map.of("ok", false),
                () -> { opened[0] = "yes"; return "кандидат открыт во вкладке браузера Orca: http://127.0.0.1:4173/"; })) {
            page.start();
            String body = get(page.url()).body();
            check.contains("the first picture says what it is", body, "1280×720 · страница открыта");
            check.contains("the first press is named and its check shown", body,
                    "1280×720 · клик №1 по toggle → greeting скрыт ✓");
            check.contains("and the second press, which a file name hid", body,
                    "1280×720 · клик №2 по toggle → greeting виден ✓");
            check.that("in the order they were taken",
                    body.indexOf("клик №1") < body.indexOf("клик №2") && body.indexOf("страница открыта") < body.indexOf("клик №1"));
            check.contains("the scenario reads as a person would say it", body,
                    "клик по toggle ✓ → greeting скрыт ✓ → клик по toggle ✓ → greeting виден ✓");
            check.contains("a finding that did not stop the loop is on the page", body, "Замечания проверяющих (1)");
            check.contains("with what the reader said", body, "moves the toggle button up 30px");
            check.contains("and what it suggested", body, "Reserve the greeting");
            check.contains("the candidate can be tried", body, "Открыть кандидата в браузере Orca");
            HttpResponse<String> tried = http.send(HttpRequest.newBuilder(URI.create(page.url() + "try"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("the try button asks the previewer", "yes", opened[0]);
            check.contains("and says where the candidate is", tried.body(), "http://127.0.0.1:4173/");
        }
    }

    private void page(Check check) throws Exception {
        Path root = project("Show <b>Hello</b> & toggle it");
        HumanDecision pending = new ApprovalStore(root).createSuccess("run-1", "hello",
                "every stage that ran passed", root.resolve(".warden/runs/run-1/task-run.json"),
                "fingerprint-shown");
        Path shot = root.resolve(".warden/runs/run-1--visual-qa-0/screenshots/1280x720.png");
        Files.createDirectories(shot.getParent());
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", shot.toFile());
        Path stranger = root.resolve(".warden/runs/run-10/screenshots/other.png");
        Files.createDirectories(stranger.getParent());
        Files.copy(shot, stranger);

        List<String> asked = new java.util.ArrayList<>();
        try (DecisionPage page = new DecisionPage(root, "run-1", (choice, note, expected) -> {
            asked.add(choice + "|" + note + "|" + expected);
            HumanDecision resolved = new ApprovalStore(root).resolve("run-1", expected, choice,
                    "orca-decision-page", note);
            return Map.of("ok", true, "decision", resolved.toMap());
        })) {
            page.start();
            HttpResponse<String> shown = get(page.url());
            check.eq("the page answers", 200, shown.statusCode());
            check.contains("it asks for a decision", shown.body(), "Нужно ваше решение");
            check.contains("with the goal, escaped", shown.body(), "Show &lt;b&gt;Hello&lt;/b&gt; &amp; toggle it");
            check.contains("a button for acceptance", shown.body(), "value='accept'");
            check.contains("and one for rejection", shown.body(), "value='reject'");
            check.contains("labelled for a person", shown.body(), "Принять");
            check.contains("the stages that ran", shown.body(), "orca-claude-review");
            check.contains("and this run's screenshot", shown.body(), "1280x720.png");
            check.that("but not another run's with a longer id", !shown.body().contains("other.png"));

            HttpResponse<byte[]> image = http.send(HttpRequest.newBuilder(URI.create(page.url() + "shot/0")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check.eq("the screenshot is served", 200, image.statusCode());
            check.that("byte for byte", java.util.Arrays.equals(Files.readAllBytes(shot), image.body()));
            check.eq("an index past the list is not", 404, get(page.url() + "shot/1").statusCode());
            check.eq("nor is anything without the token", 404,
                    get(page.url().replaceAll("/[^/]+/$", "/guess/")).statusCode());

            HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(page.url() + "decide"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "http://127.0.0.1:1")
                    .POST(HttpRequest.BodyPublishers.ofString("choice=accept&expected=" + pending.updatedAt()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check.eq("a post from another origin is refused", 403, foreign.statusCode());
            check.eq("and decides nothing", HumanDecision.State.PENDING,
                    new ApprovalStore(root).read("run-1").state());

            HttpResponse<String> accepted = post(page.url(), "accept", "looks right", pending.updatedAt().toString());
            check.eq("a button press is answered", 200, accepted.statusCode());
            check.eq("through the decider, with the version the page showed",
                    List.of("accept|looks right|" + pending.updatedAt()), asked);
            check.contains("the page says it was recorded", accepted.body(), "Записано");
            check.contains("and that nothing was landed", accepted.body(), "Ничего не слито");
            check.that("the buttons are gone once it is decided", !accepted.body().contains("value='reject'"));
            check.eq("the store holds the answer", "accept", new ApprovalStore(root).read("run-1").decision());
        }
    }

    /**
     * The whole path an operator takes: the page opens, they press Retry, `warden approve`
     * records it, and the supervisor starts the continuation — no command typed anywhere.
     */
    private void retryOnThePageContinuesTheLoop(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        Path home = Files.createDirectories(root.resolve("home"));
        Path summaryFile = root.resolve(".warden/runs/run-2/task-run.json");
        Map<String, Object> summary = new LinkedHashMap<>(Json.parseObject(Files.readString(summaryFile)));
        summary.put("run_id", "run-2");
        summary.put("decision_kind", "failure");
        summary.put("orca_gate", Map.of("published", true));
        Files.writeString(summaryFile, Json.write(summary));
        HumanDecision pending = new ApprovalStore(root).createFailure("run-2", "hello",
                "orca_worker_not_started", summaryFile, null);

        Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis,
                millis -> Thread.sleep(Math.min(millis, 20)),
                (path, gate) -> { throw new IllegalStateException("the page answered first"); }, home);
        String[] args = {"--no-workspace-status", "--quiet"};
        List<String> opened = new java.util.ArrayList<>();
        int[] starts = {0};
        Map<String, Object> result = Main.superviseGates(root, "run-2", summary, args, env,
                (path, decision, passed, environment) -> {
                    starts[0]++;
                    check.eq("the continuation is for the answer given on the page", "retry",
                            decision.decision());
                    return Map.of("started", true, "run_id", "run-3", "ok", true,
                            "summary_report", Map.of("run_id", "run-3"));
                },
                (path, runId, shown) -> Main.awaitDecision(path, runId, args, 1, env, url -> {
                    opened.add(url);
                    HttpResponse<String> pressed = post(url, "retry", "", pending.updatedAt().toString());
                    check.contains("the page records the retry", pressed.body(), "Записано");
                    return true;
                }));
        check.eq("the page was opened once", 1, opened.size());
        check.eq("the retry is recorded as the page's", "orca-decision-page",
                new ApprovalStore(root).read("run-2").actor());
        check.eq("and starts exactly one continuation", 1, starts[0]);
        check.eq("which the supervisor reports", "run-3", result.get("continued_run_id"));
    }

    private Path project(String goal) throws Exception {
        Path root = Files.createTempDirectory("warden-decision-page-");
        Files.createDirectories(root.resolve(".warden/tasks"));
        Files.writeString(root.resolve(".warden/project.yaml"),
                "version: 1\nproject: fixture\nchecks: { fast: [check] }\n");
        Files.writeString(root.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: "%s"
                risk: low
                scope: [src]
                acceptance: [check]
                """.formatted(goal));
        for (String run : List.of("run-1", "run-2")) {
            Path summary = root.resolve(".warden/runs").resolve(run).resolve("task-run.json");
            Files.createDirectories(summary.getParent());
            Files.writeString(summary, Json.write(Map.of("run_id", run, "task_id", "hello",
                    "reason", "ready_for_human",
                    "steps", List.of(Map.of("step", "reviewer", "stage", "review-second", "ok", true,
                            "code", "ok", "profile", "orca-claude-review", "vendor", "claude")))));
        }
        return root;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String pageUrl, String choice, String note, String expected)
            throws Exception {
        String body = "choice=" + choice + "&note=" + java.net.URLEncoder.encode(note, StandardCharsets.UTF_8)
                + "&expected=" + java.net.URLEncoder.encode(expected, StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(URI.create(pageUrl + "decide"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
