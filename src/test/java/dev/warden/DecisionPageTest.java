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
        rejectWithANoteGoesBackToTheWriter(check);
        onlyTheLastAttemptIsShown(check);
        aDecisionOutlivesItsPage(check);
        anAnswerDoesNotClaimTheNextRunStarted(check);
        oneSupervisorPerRunAcrossProcesses(check);
        anAnswerStartsOneContinuation(check);
    }

    /**
     * The lease was read and then written, two steps, and the only lock around them was a
     * monitor inside one JVM. Two `warden decide` processes, or the CLI and the Dashboard,
     * could each find no lease and each supervise the run: amend its contract, wait, and start
     * a continuation. Here the first supervisor is another process, as it would be.
     */
    private void oneSupervisorPerRunAcrossProcesses(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, Object> summary = new LinkedHashMap<>(Json.parseObject(Files.readString(
                root.resolve(".warden/runs/run-1/task-run.json"))));
        summary.put("decision_kind", "failure");
        summary.put("reason", dev.warden.run.TaskLoop.CONTRACT_AMENDMENT);
        Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis, millis -> { },
                (path, gate) -> { throw new IllegalStateException("no gate"); }, home);
        String[] args = {"--no-workspace-status", "--quiet"};

        try (var here = DecisionPage.supervise(root, "run-1")) {
            check.that("a run can be supervised", here != null);
            check.that("but not twice in one process", DecisionPage.supervise(root, "run-1") == null);
        }

        String launcher = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process other = new ProcessBuilder(launcher, "-cp", System.getProperty("java.class.path"),
                "dev.warden.testing.HoldsSupervision", root.toString(), "run-1")
                .redirectErrorStream(true).start();
        try {
            String first = new java.io.BufferedReader(new java.io.InputStreamReader(
                    other.getInputStream(), StandardCharsets.UTF_8)).readLine();
            check.eq("another process supervises the run", "held", first);
            check.that("so this one cannot", DecisionPage.supervise(root, "run-1") == null);
            int[] calls = {0};
            Map<String, Object> result = Main.superviseGates(root, "run-1", summary, args, env,
                    (path, decision, passed, environment) -> {
                        calls[0]++;
                        return Map.of("started", true);
                    },
                    (path, runId, shown) -> {
                        calls[0]++;
                        return Map.of();
                    });
            check.eq("a second supervisor steps aside", "run-1", result.get("supervised_elsewhere"));
            check.eq("without waiting or starting anything", 0, calls[0]);
            check.that("and without paying for an amendment", !Files.exists(root.resolve(".warden/runs/run-1")
                    .resolve(dev.warden.run.TaskLoop.AMENDMENT_RECEIPT)));
        } finally {
            other.getOutputStream().close();
            if (!other.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) other.destroyForcibly().waitFor();
        }
        try (var after = DecisionPage.supervise(root, "run-1")) {
            check.that("the run is free again once that process has gone", after != null);
        }
    }

    /**
     * The same `retry` or `apply` could be acted on by a supervisor and by `warden approve`
     * without `--no-start`, and each started a continuation under its own unused run id.
     */
    private void anAnswerStartsOneContinuation(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        Path claim = root.resolve(".warden/runs/run-1").resolve(Main.CONTINUATION_CLAIM);
        check.eq("the first start of a continuation claims it", null,
                Main.claimContinuation(root, "run-1", "run-5"));
        Map<String, Object> second = Main.claimContinuation(root, "run-1", "run-6");
        check.eq("a second, while the first is still starting, is refused", "run-5",
                second == null ? null : second.get("run_id"));

        // A claim is a reservation, not a run. The first claim above was written by this
        // process, which is alive; one whose process has gone and whose run never reserved
        // its evidence — a crash between the claim and the run — is taken over.
        Map<String, Object> stranded = new LinkedHashMap<>(Json.parseObject(Files.readString(claim)));
        stranded.put("process_started_at", "1970-01-01T00:00:00Z");
        Files.writeString(claim, Json.write(stranded));
        check.eq("a claim whose process is gone and whose run never started is taken over", null,
                Main.claimContinuation(root, "run-1", "run-7"));
        check.eq("and names the run that took it", "run-7", Json.parseObject(Files.readString(claim)).get("run_id"));

        // Once the run has reserved its evidence it may have paid for something, and its claim
        // stands whoever made it.
        Files.createDirectories(root.resolve(".warden/runs/run-7"));
        Files.writeString(root.resolve(".warden/runs/run-7/run.json"), "{}");
        Map<String, Object> afterRun = new LinkedHashMap<>(Json.parseObject(Files.readString(claim)));
        afterRun.put("process_started_at", "1970-01-01T00:00:00Z");
        Files.writeString(claim, Json.write(afterRun));
        Map<String, Object> protectedClaim = Main.claimContinuation(root, "run-1", "run-8");
        check.eq("a claim whose run exists stands after its process has gone", "run-7",
                protectedClaim == null ? null : protectedClaim.get("run_id"));
        Main.releaseContinuation(root, "run-1", "run-7");
        check.that("and is not given back by a failed start either", Files.exists(claim));

        // Measured in the review of 40024ac: a contract that failed to load after the claim
        // left it standing, and after the contract was fixed `warden decide` answered
        // continuation_already_started for a run that never existed.
        Path broken = project("Toggle the greeting");
        Path brokenSummary = broken.resolve(".warden/runs/run-1/task-run.json");
        HumanDecision pending = new ApprovalStore(broken).createFailure("run-1", "hello",
                "orca_worker_not_started", brokenSummary, null);
        HumanDecision retry = new ApprovalStore(broken).resolve("run-1", pending.updatedAt().toString(),
                "retry", "tester", "");
        Files.writeString(broken.resolve(".warden/tasks/hello.yaml"), "version: 1\nid: hello\n");
        Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis, millis -> { },
                (path, gate) -> { throw new IllegalStateException("no gate"); },
                Files.createDirectories(broken.resolve("home")));
        Map<String, Object> failed = Main.startAdvance(broken, retry,
                new String[] {"--no-workspace-status", "--quiet"}, env);
        check.eq("a continuation whose contract does not load does not start", "advance_start_failed",
                failed.get("code"));
        check.that("and does not use up the answer", !Files.exists(
                broken.resolve(".warden/runs/run-1").resolve(Main.CONTINUATION_CLAIM)));
        check.eq("which can be acted on again once the contract is fixed", null,
                Main.claimContinuation(broken, "run-1", "run-3"));
        Main.releaseContinuation(broken, "run-1", "run-3");
        check.that("and a live process gives back a claim whose run never came to exist",
                !Files.exists(broken.resolve(".warden/runs/run-1").resolve(Main.CONTINUATION_CLAIM)));
    }

    /**
     * The page lived exactly as long as the `warden do` that asked. When that stopped waiting,
     * the page went with it and the decision stayed pending, reachable only by `warden
     * decide` typed in the worktree. The Dashboard now lists it and puts the page back up —
     * or goes to the one already up, rather than starting a second supervisor.
     */
    private void aDecisionOutlivesItsPage(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        Path home = Files.createDirectories(root.resolve("home"));
        Path summaryFile = root.resolve(".warden/runs/run-1/task-run.json");
        Map<String, Object> summary = new LinkedHashMap<>(Json.parseObject(Files.readString(summaryFile)));
        summary.put("decision_kind", "success");
        Files.writeString(summaryFile, Json.write(summary));
        new ApprovalStore(root).createSuccess("run-1", "hello", "every stage that ran passed",
                summaryFile, "fingerprint-shown");
        long[] now = {1_800_000_000_000L};
        Main.ApproveEnv hurried = new Main.ApproveEnv(() -> now[0], millis -> now[0] += millis,
                (path, gate) -> { throw new IllegalStateException("no gate"); }, home);
        String[] args = {"--no-workspace-status", "--quiet"};

        // The wait that asked the question ends unanswered, as it does after --wait-minutes.
        String[] leased = {null};
        Map<String, Object> waited = Main.awaitDecision(root, "run-1", args, 1, hurried, url -> {
            var lease = DecisionPage.liveLease(root, "run-1");
            leased[0] = lease == null ? null : lease.url();
            check.eq("while the page is up its lease names it", url, leased[0]);
            return true;
        });
        check.eq("the first wait ended without an answer", Boolean.TRUE,
                ((Map<?, ?>) waited.get("decision_page")).get("timed_out"));
        check.that("and took its page and its lease with it", DecisionPage.liveLease(root, "run-1") == null);

        Main.ApproveEnv patient = new Main.ApproveEnv(System::currentTimeMillis,
                millis -> Thread.sleep(Math.min(millis, 20)),
                (path, gate) -> { throw new IllegalStateException("no gate"); }, home);
        List<String> previews = new java.util.ArrayList<>();
        try (var dashboard = new dev.warden.dashboard.Dashboard(root, 0,
                runId -> Main.openDecision(root, runId, args, patient, url -> previews.add(url)))) {
            dashboard.start();
            Map<String, Object> state = Json.parseObject(get(dashboard.url() + "api/state").body());
            List<?> pending = (List<?>) state.get("pending_decisions");
            check.eq("the Dashboard lists the open question", 1, pending.size());
            check.eq("naming its run", "run-1", ((Map<?, ?>) pending.get(0)).get("run_id"));
            check.eq("and says nobody is serving its page now", false, ((Map<?, ?>) pending.get(0)).get("page_open"));

            String html = get(dashboard.url()).body();
            java.util.regex.Matcher meta = java.util.regex.Pattern
                    .compile("name=\"warden-token\" content=\"([^\"]+)\"").matcher(html);
            check.that("the page it serves carries the action token", meta.find());
            String token = meta.group(1);

            check.eq("a request without the token is refused", 403,
                    postForm(dashboard.url() + "decide/run-1", "token=wrong", null).statusCode());
            check.eq("and so is one from another origin", 403,
                    postForm(dashboard.url() + "decide/run-1", "token=" + token, "http://evil.example").statusCode());
            check.that("neither started a page", DecisionPage.liveLease(root, "run-1") == null);

            HttpResponse<String> opened = postForm(dashboard.url() + "decide/run-1", "token=" + token, null);
            check.eq("the button puts the page back up and goes to it", 303, opened.statusCode());
            String page = opened.headers().firstValue("Location").orElse("");
            check.contains("a decision page on this machine", page, "http://127.0.0.1:");
            check.contains("that asks the question again", get(page).body(), "Нужно ваше решение");
            var lease = DecisionPage.liveLease(root, "run-1");
            check.eq("and is leased, so the next click finds it", page, lease == null ? null : lease.url());
            check.eq("a second click goes to the same page", page,
                    postForm(dashboard.url() + "decide/run-1", "token=" + token, null)
                            .headers().firstValue("Location").orElse(""));
            check.eq("and the list says it is open", true, ((Map<?, ?>) ((List<?>) Json.parseObject(
                    get(dashboard.url() + "api/state").body()).get("pending_decisions")).get(0)).get("page_open"));

            HumanDecision current = new ApprovalStore(root).read("run-1");
            HttpResponse<String> answered = post(page, "reject", "", current.updatedAt().toString());
            check.contains("the answer goes through approve", answered.body(), "Записано");
            long until = System.currentTimeMillis() + 10_000L;
            while (DecisionPage.liveLease(root, "run-1") != null && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            check.that("the page's supervisor ends with the answer", DecisionPage.liveLease(root, "run-1") == null);
            check.eq("a decided run is not reopened", 409,
                    postForm(dashboard.url() + "decide/run-1", "token=" + token, null).statusCode());
            check.eq("and leaves the list", 0, ((List<?>) Json.parseObject(
                    get(dashboard.url() + "api/state").body()).get("pending_decisions")).size());
        }
        check.eq("nothing was opened besides the page", List.of(), previews);
    }

    /**
     * `warden approve` moved the card to "running" for every answer that expects another run,
     * before any run had started, and a continuation that was then refused left it there. The
     * tab it could not find went on saying NEEDS YOU. The card now moves when the next run
     * starts; the answer renames the tab and leaves the column alone.
     */
    private void anAnswerDoesNotClaimTheNextRunStarted(Check check) throws Exception {
        List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        dev.warden.run.Workspace recording = new dev.warden.run.Workspace() {
            @Override public void note(String text) { told.add("note"); }
            @Override public void state(State state) { told.add("state " + state); }
            @Override public void adopt(String runId) { told.add("adopt " + runId); }
            @Override public void answered(String decision) { told.add("answered " + decision); }
        };
        var before = Main.boards;
        Main.boards = worktree -> recording;
        try {
            for (String[] answer : List.of(new String[] {"reject", "the second press misses"},
                    new String[] {"reject", ""})) {
                told.clear();
                Path root = project("Toggle the greeting");
                Path home = Files.createDirectories(root.resolve("home"));
                HumanDecision pending = new ApprovalStore(root).createSuccess("run-1", "hello",
                        "every stage that ran passed", root.resolve(".warden/runs/run-1/task-run.json"),
                        "fingerprint-shown");
                Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis, millis -> { },
                        (path, gate) -> { throw new IllegalStateException("no gate"); }, home);
                var recorded = Main.approveDecision(root, new String[] {"approve", "run-1",
                        "--decision", answer[0], "--note", answer[1], "--expected-updated-at",
                        pending.updatedAt().toString(), "--no-start", "--quiet"}, env);
                String which = answer[0] + (answer[1].isEmpty() ? " without a note" : " with a note");
                check.that("the answer is recorded: " + which, recorded.ok());
                check.that("the board takes over the run's tab first: " + which,
                        !told.isEmpty() && told.get(0).equals("adopt run-1"));
                if (answer[1].isEmpty()) {
                    check.that("an answer that closes the run settles the card",
                            told.contains("state " + dev.warden.run.Workspace.State.SETTLED));
                } else {
                    check.that("an answer that expects another run renames the tab",
                            told.contains("answered reject"));
                    check.that("and does not say running before that run starts",
                            told.stream().noneMatch(line -> line.startsWith("state ")));
                }
                String narration = Files.exists(root.resolve(".warden/runs/run-1/narration.log"))
                        ? Files.readString(root.resolve(".warden/runs/run-1/narration.log")) : "";
                check.that("no narration is invented for a run that never had one: " + which,
                        narration.isEmpty());
            }
        } finally {
            Main.boards = before;
        }
    }

    private HttpResponse<String> postForm(String url, String body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The page says "Отклонить" with a note returns the work to the writer. The supervisor
     * treated reject as an ending and stopped, so the note — the one thing the operator wrote
     * — went nowhere until someone typed `warden run --continue`. Without a note there is
     * nothing to hand over, and the decision closes the work.
     */
    private void rejectWithANoteGoesBackToTheWriter(Check check) throws Exception {
        for (String note : List.of("the second press misses the button", "")) {
            Path root = project("Toggle the greeting");
            Path home = Files.createDirectories(root.resolve("home"));
            Path summaryFile = root.resolve(".warden/runs/run-1/task-run.json");
            Map<String, Object> summary = new LinkedHashMap<>(Json.parseObject(Files.readString(summaryFile)));
            summary.put("decision_kind", "success");
            summary.put("orca_gate", Map.of("published", true));
            Files.writeString(summaryFile, Json.write(summary));
            HumanDecision pending = new ApprovalStore(root).createSuccess("run-1", "hello",
                    "every stage that ran passed", summaryFile, "fingerprint-shown");
            Main.ApproveEnv env = new Main.ApproveEnv(System::currentTimeMillis,
                    millis -> Thread.sleep(Math.min(millis, 20)),
                    (path, gate) -> { throw new IllegalStateException("the page answered first"); }, home);
            String[] args = {"--no-workspace-status", "--quiet"};
            List<String> carried = new java.util.ArrayList<>();
            Main.superviseGates(root, "run-1", summary, args, env,
                    (path, decision, passed, environment) -> {
                        carried.add(decision.decision() + ": " + decision.note());
                        return Map.of("started", true, "run_id", "run-2", "ok", true,
                                "summary_report", Map.of("run_id", "run-2"));
                    },
                    (path, runId, shown) -> Main.awaitDecision(path, runId, args, 1, env, url -> {
                        HttpResponse<String> pressed = post(url, "reject", note, pending.updatedAt().toString());
                        check.contains("the page records the rejection", pressed.body(), "Записано");
                        if (!note.isEmpty()) {
                            check.contains("and says the work goes back with the note", pressed.body(),
                                    "возвращает работу исполнителю");
                        }
                        return true;
                    }));
            if (note.isEmpty()) {
                check.eq("a rejection without a note starts nothing", List.of(), carried);
            } else {
                check.eq("a rejection with a note starts the writer's next run, carrying it",
                        List.of("reject: " + note), carried);
            }
        }
    }

    /**
     * A browser pass that failed and was repaired leaves two attempt directories. The page
     * listed both, so the failed frames sat under a passing verdict, and eight frames from the
     * first attempt could push the final ones off the page.
     */
    private void onlyTheLastAttemptIsShown(Check check) throws Exception {
        Path root = project("Toggle the greeting");
        new ApprovalStore(root).createSuccess("run-1", "hello", "every stage that ran passed",
                root.resolve(".warden/runs/run-1/task-run.json"), "fingerprint-shown");
        for (int attempt : List.of(0, 1)) {
            Path shots = root.resolve(".warden/runs/run-1--visual-qa-" + attempt + "/screenshots");
            Files.createDirectories(shots);
            String picture = attempt == 0 ? "1280x720-failed.png" : "1280x720.png";
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "png", shots.resolve(picture).toFile());
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("raw", "1280x720: testid=toggle click -> testid=greeting hidden");
            scenario.put("ok", attempt == 1);
            scenario.put("viewport", Map.of("width", 1280, "height", 720, "mobile", false));
            scenario.put("screenshot", shots.resolve(picture).toString());
            if (attempt == 0) scenario.put("why", "the greeting stayed on the page");
            scenario.put("steps", List.of(
                    Map.of("matcher", "testid=toggle", "assertion", "click", "ok", true),
                    Map.of("matcher", "testid=greeting", "assertion", "hidden", "ok", attempt == 1)));
            Files.writeString(shots.resolve("visual-qa.json"),
                    Json.write(Map.of("ok", attempt == 1, "scenarios", List.of(scenario))));
        }
        try (DecisionPage page = new DecisionPage(root, "run-1", (choice, note, expected) -> Map.of("ok", false))) {
            page.start();
            String body = get(page.url()).body();
            check.contains("the repaired pass is shown", body, "✓ 1280×720");
            check.that("the failed attempt it replaced is not", !body.contains("the greeting stayed on the page"));
            check.that("nor its verdict", !body.contains("✗ 1280×720"));
            check.eq("and only the final attempt's frame is on the page", 1,
                    body.split("src='shot/", -1).length - 1);
        }
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
