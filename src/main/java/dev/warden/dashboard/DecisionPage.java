package dev.warden.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.warden.approval.ApprovalStore;
import dev.warden.approval.HumanDecision;
import dev.warden.config.TaskSpec;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One pending human decision as a page in Orca's browser, with a button per option.
 *
 * The decision used to be answerable only by typing: `warden approve …` in the worktree, or
 * `orca orchestration run-use` then `gate-resolve --from <handle>` in an Orca terminal. An
 * operator who does not know those commands could see the run was waiting and could not answer
 * it. Measured on the first all-Orca loop, 2026-09-22.
 *
 * The page decides nothing itself. A button hands the choice to {@link Decider}, which is the
 * same path as `warden approve`: the version token, the candidate fingerprint for an
 * acceptance, the gate's expiry and the store's duplicate protection all still apply, and a
 * refusal is shown on the page rather than swallowed.
 *
 * It listens on 127.0.0.1 only, under a random path token, so another local page cannot find
 * it, and it serves only the screenshots of this run.
 */
public final class DecisionPage implements AutoCloseable {

    /** Records one choice the way `warden approve` does, and reports what happened. */
    @FunctionalInterface
    public interface Decider {
        Map<String, Object> decide(String choice, String note, String expectedUpdatedAt) throws Exception;
    }

    /** Starts the candidate where the operator can use it, and says what happened. */
    @FunctionalInterface
    public interface Previewer {
        String open() throws Exception;
    }

    /**
     * One picture and what it shows, in the order the harness took it.
     *
     * @param caption when it was taken, short enough to sit under the picture
     * @param checked what the browser checked on the page it shows, one per line; may be empty
     */
    public record Shot(Path file, String caption, String checked) {}

    /** Below the buttons since 2026-09-24, so a whole traffic-light pass (nine frames) fits. */
    private static final int MAX_SHOTS = 12;
    private static final String HTML = "text/html; charset=utf-8";

    private final HttpServer server;
    private final String token;
    private final Path root;
    private final String runId;
    private final Decider decider;
    private final Previewer previewer;

    public DecisionPage(Path root, String runId, Decider decider) throws IOException {
        this(root, runId, decider, null);
    }

    /** @param previewer null when the page has no candidate to start (no button is shown) */
    public DecisionPage(Path root, String runId, Decider decider, Previewer previewer) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.runId = runId;
        this.decider = decider;
        this.previewer = previewer;
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (Exception failed) {
                send(exchange, 500, HTML, page("Warden · ошибка страницы решения",
                        "<p class=bad>" + escape(String.valueOf(failed.getMessage())) + "</p>"));
            }
        });
    }

    public void start() { server.start(); }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + token + "/";
    }

    /**
     * Waits for an answer still being written before the server goes. The button's own POST
     * records the decision, and the supervisor that sees it closes the page; closing with no
     * grace dropped that POST's response, and the tab showed a broken connection instead of
     * "recorded".
     */
    @Override public void close() { server.stop(2); }

    // ------------------------------------------------------------------ lease

    /** Beside the run's evidence: which process serves the page for this run, and where. */
    public static final String LEASE_FILE = "decision-page.json";

    /**
     * A page some process is keeping up for one run.
     *
     * The page lives as long as the process that serves it, and that process used to be the
     * only one that knew its address. Once it stopped waiting, the decision stayed pending on
     * disk with no way back to it but `warden decide`; while it waited, a second `decide`
     * would have put up a second page and a second supervisor, and each would have started
     * the continuation. The lease is how the Dashboard and `decide` find the page that is
     * already up, and tell a live one from one whose process is gone.
     *
     * It says where the page is, not who owns the run: reading it and writing it are two
     * steps, so two processes could both find none. {@link #supervise} is the ownership.
     */
    public record Lease(String url, long pid, String processStartedAt) {}

    /** Beside the lease: locked by the one process that supervises the run's decision. */
    public static final String SUPERVISOR_LOCK = "supervisor.lock";

    /**
     * One process's ownership of a run's decision: the amendment, the page, the wait and the
     * continuation it may start. Held as an operating-system lock on {@link #SUPERVISOR_LOCK},
     * so it ends with its process however that process ends; closing releases it.
     *
     * The lease could not do this. Two `warden decide` processes, or the CLI and the
     * Dashboard, could both find no lease, both put up a page, both see the same retry and
     * both start a continuation, each under a run id of its own; the monitor that serialised
     * the Dashboard's clicks protected nothing across processes, and an amendment was paid
     * before any lease existed. Whoever holds this lock supervises; everyone else goes to the
     * page it serves.
     */
    public static final class Supervision implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private Supervision(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override public void close() {
            try {
                lock.release();
            } catch (IOException alreadyGone) {
                // The channel closing below releases it as well.
            }
            try {
                channel.close();
            } catch (IOException alreadyClosed) {
                // Nothing is left to release.
            }
        }
    }

    /**
     * Take ownership of {@code runId}'s decision for this process, or null when another
     * process, or another supervisor in this one, already holds it. Never waits.
     */
    public static Supervision supervise(Path root, String runId) throws IOException {
        Path file = runFile(root, runId, SUPERVISOR_LOCK);
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return new Supervision(channel, lock);
        } catch (OverlappingFileLockException heldInThisProcess) {
            // Another supervisor of this JVM holds it: the same answer as another process.
        } catch (IOException | RuntimeException failed) {
            channel.close();
            throw failed;
        }
        channel.close();
        return null;
    }

    /** Record that this process serves {@code url} for {@code runId}. */
    public static void lease(Path root, String runId, String url) throws IOException {
        Path file = leaseFile(root, runId);
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("run_id", runId);
        lease.put("url", url);
        lease.put("pid", ProcessHandle.current().pid());
        lease.put("process_started_at", startOf(ProcessHandle.current()));
        dev.warden.json.JsonFile.writeAtomically(file, lease);
    }

    /** The lease for {@code runId} when its process is still the one that wrote it, else null. */
    public static Lease liveLease(Path root, String runId) {
        try {
            Path file = leaseFile(root, runId);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            Map<String, Object> lease = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!(lease.get("url") instanceof String url) || !(lease.get("pid") instanceof Number pid)) {
                return null;
            }
            Object started = lease.get("process_started_at");
            boolean alive = ProcessHandle.of(pid.longValue())
                    .filter(ProcessHandle::isAlive)
                    .filter(process -> started == null || String.valueOf(started).equals(startOf(process)))
                    .isPresent();
            return alive ? new Lease(url, pid.longValue(), started == null ? null : String.valueOf(started))
                    : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Drop the lease, if it is still the one for {@code url}. */
    public static void release(Path root, String runId, String url) {
        try {
            Path file = leaseFile(root, runId);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
            Map<String, Object> lease = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (url.equals(lease.get("url"))) Files.deleteIfExists(file);
        } catch (Exception notOurs) {
            // A lease left behind names a process that is gone, and liveLease says so.
        }
    }

    private static Path leaseFile(Path root, String runId) {
        return runFile(root, runId, LEASE_FILE);
    }

    private static Path runFile(Path root, String runId, String name) {
        if (runId == null || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("unsafe run id: " + runId);
        }
        return root.toAbsolutePath().normalize().resolve(".warden/runs").resolve(runId).resolve(name);
    }

    private static String startOf(ProcessHandle process) {
        return process.info().startInstant().map(Object::toString).orElse(null);
    }

    private void handle(HttpExchange exchange) throws Exception {
        String authority = "127.0.0.1:" + server.getAddress().getPort();
        String prefix = "/" + token + "/";
        String path = exchange.getRequestURI().getPath();
        if (!authority.equals(exchange.getRequestHeaders().getFirst("Host")) || !path.startsWith(prefix)) {
            send(exchange, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        String rest = path.substring(prefix.length());
        String method = exchange.getRequestMethod();
        if (rest.isEmpty() && "GET".equals(method)) {
            send(exchange, 200, HTML, render(null, null));
        } else if (rest.startsWith("shot/") && "GET".equals(method)) {
            List<Shot> shots = shots();
            int index;
            try {
                index = Integer.parseInt(rest.substring("shot/".length()));
            } catch (NumberFormatException notANumber) {
                index = -1;
            }
            if (index < 0 || index >= shots.size()) {
                send(exchange, 404, "text/plain; charset=utf-8", "not found");
                return;
            }
            send(exchange, 200, "image/png", Files.readAllBytes(shots.get(index).file()));
        } else if ("try".equals(rest) && "POST".equals(method) && previewer != null) {
            if (!sameOrigin(exchange, authority)) {
                send(exchange, 403, "text/plain; charset=utf-8", "forbidden");
                return;
            }
            String notice;
            try {
                notice = previewer.open();
            } catch (Exception failed) {
                notice = "не удалось запустить кандидата: " + failed.getMessage();
            }
            send(exchange, 200, HTML, render(null, notice));
        } else if ("decide".equals(rest) && "POST".equals(method)) {
            if (!sameOrigin(exchange, authority)) {
                send(exchange, 403, "text/plain; charset=utf-8", "forbidden");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(64 * 1024);
            Map<String, String> form = form(new String(body, StandardCharsets.UTF_8));
            Map<String, Object> result = decider.decide(form.getOrDefault("choice", ""),
                    form.getOrDefault("note", ""), form.getOrDefault("expected", ""));
            send(exchange, 200, HTML, render(result, null));
        } else {
            send(exchange, 404, "text/plain; charset=utf-8", "not found");
        }
    }

    /**
     * The page as it stands now, with the outcome of a button just pressed when there is one.
     *
     * What the decision is made on comes first and ends at the buttons; the goal, the changed
     * files and the pictures follow them. Measured on the traffic-light run of 2026-09-24: with
     * the 5 KB contract goal, eleven scenarios and eight screenshots above them, the buttons
     * sat 5,588 px down, seven screens of a 960×800 viewport.
     */
    String render(Map<String, Object> result, String notice) {
        HumanDecision decision = null;
        try {
            decision = new ApprovalStore(root).read(runId);
        } catch (Exception unreadable) {
            // Rendered as "no decision" below; the buttons need a readable one.
        }
        Map<String, Object> summary = readSummary();
        StringBuilder html = new StringBuilder();
        boolean pending = decision != null && decision.state() == HumanDecision.State.PENDING;
        html.append("<h1>").append(pending ? "Нужно ваше решение" : "Решение").append("</h1>");
        html.append("<p class=muted>run <code>").append(escape(runId)).append("</code>");
        Object taskId = summary.get("task_id");
        if (taskId != null) html.append(" · задача <code>").append(escape(String.valueOf(taskId))).append("</code>");
        html.append("</p>");

        if (notice != null) html.append("<div class=note>").append(escape(notice)).append("</div>");
        if (result != null) {
            if (Boolean.TRUE.equals(result.get("ok"))) {
                Object recorded = result.get("decision") instanceof Map<?, ?> map ? map.get("decision") : null;
                Object note = result.get("decision") instanceof Map<?, ?> map ? map.get("note") : null;
                html.append("<div class=ok>Записано: <b>").append(escape(String.valueOf(recorded)))
                        .append("</b>. Ничего не слито").append(". ")
                        .append(escape(afterDecision(String.valueOf(recorded),
                                note != null && !String.valueOf(note).isBlank()))).append("</div>");
            } else {
                html.append("<div class=bad>Не записано: <b>").append(escape(String.valueOf(result.get("code"))))
                        .append("</b> — ").append(escape(String.valueOf(result.get("message")))).append("</div>");
            }
        }

        Object reason = decision != null && decision.reason() != null ? decision.reason() : summary.get("reason");
        Object why = summary.get("decision_reason");
        html.append("<h2>Итог прогона</h2><p><b>").append(escape(String.valueOf(reason))).append("</b>");
        if (why != null && !String.valueOf(why).equals(String.valueOf(reason))) {
            html.append("<br><span class=muted>").append(escape(String.valueOf(why))).append("</span>");
        }
        html.append("</p>");

        html.append(findings(summary));
        html.append(stages(summary));

        List<Map<String, Object>> scenarios = harnessScenarios();
        List<Shot> shots = shots(scenarios);
        html.append(browserChecks(scenarios, !shots.isEmpty()));

        if (previewer != null && pending) {
            html.append("<h2>Проверить самому</h2><form method=post action=try>")
                    .append("<button>Открыть кандидата в браузере Orca<small>запустит его той же командой, "
                            + "что и проверка, и откроет вкладкой в этом worktree; остановится вместе "
                            + "с этой страницей</small></button></form>");
        }

        if (pending) {
            html.append("<h2>Ваше решение</h2><form method=post action=decide>")
                    .append("<input type=hidden name=expected value='")
                    .append(escape(decision.updatedAt().toString())).append("'>")
                    .append("<label>Заметка (для «Отклонить» — что исправить; она уйдёт исполнителю)"
                        + "<textarea name=note rows=3></textarea></label>")
                    .append("<div class=buttons>");
            for (String option : decision.options()) {
                html.append("<button name=choice value='").append(escape(option)).append("' class='")
                        .append("accept".equals(option) ? "primary" : "").append("'>")
                        .append(escape(label(option))).append("<small>").append(escape(hint(option)))
                        .append("</small></button>");
            }
            html.append("</div></form>");
        } else if (decision != null) {
            html.append("<h2>Решение принято</h2><p><b>").append(escape(String.valueOf(decision.decision())))
                    .append("</b> · ").append(escape(String.valueOf(decision.actor()))).append(" · ")
                    .append(escape(String.valueOf(decision.updatedAt()))).append("</p>");
        } else {
            html.append("<p class=bad>У этого прогона нет ожидающего решения.</p>");
        }

        String goal = goal(summary);
        if (goal != null) html.append(goalSection(goal, operatorGoal(summary, goal)));

        List<String> changed = changedFiles(summary);
        if (!changed.isEmpty()) {
            html.append("<h2>Изменённые файлы</h2><ul>");
            for (String file : changed) html.append("<li><code>").append(escape(file)).append("</code></li>");
            html.append("</ul>");
        }

        if (!shots.isEmpty()) {
            html.append("<h2 id=shots>Скриншоты</h2><p class=muted>В том порядке, в каком их снял браузер. "
                    + "Что на снимке проверено — во всплывающей подсказке.</p><div class=shots>");
            for (int index = 0; index < shots.size(); index++) {
                Shot shot = shots.get(index);
                html.append("<figure");
                if (!shot.checked().isEmpty()) html.append(" title='").append(escape(shot.checked())).append("'");
                html.append("><img alt='' src='shot/").append(index).append("'><figcaption>")
                        .append(escape(shot.caption())).append("</figcaption></figure>");
            }
            html.append("</div>");
        }
        return page("Warden · решение", html.toString());
    }

    /**
     * Every finding a reader left open on the candidate being decided, whatever its severity.
     *
     * Only P1 stops the loop, so a P2 or P3 reaches the gate as a pass. Measured on the first
     * all-Orca run: the visual reader filed that hiding the greeting moves the button 30 px, so
     * a second press in the same place misses it, and the second reader filed that the
     * acceptance never checks the text; both were P3, the page said "every stage passed", and
     * the operator asked the very question the first finding answers. A decision is made on
     * what the readers said, not on whether it was loud enough to stop the loop.
     */
    static String findings(Map<String, Object> summary) {
        List<Map<String, Object>> open = openFindings(summary);
        if (open.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<h2>Замечания проверяющих (").append(open.size())
                .append(")</h2><div class=note>Проверки прошли, но проверяющие оставили замечания, "
                        + "которые петлю не остановили (останавливает только P1). Прочитайте их до решения: "
                        + "«Отклонить» с заметкой вернёт работу исполнителю вместе с ними.</div>");
        for (Map<String, Object> finding : open) {
            html.append("<div class=finding><div><span class=sev>").append(escape(String.valueOf(finding.get("severity"))))
                    .append("</span> ").append(escape(category(finding.get("category")))).append(" · ")
                    .append(escape(String.valueOf(finding.get("_stage")))).append("</div><p>")
                    .append(escape(String.valueOf(finding.get("message")))).append("</p>");
            for (String[] field : List.of(new String[] {"expected", "Ожидалось"}, new String[] {"actual", "На деле"},
                    new String[] {"suggestion", "Предложение"})) {
                Object value = finding.get(field[0]);
                if (value instanceof String text && !text.isBlank()) {
                    html.append("<p class=muted><b>").append(field[1]).append(":</b> ").append(escape(text)).append("</p>");
                }
            }
            html.append("</div>");
        }
        return html.toString();
    }

    /** The open findings of each stage's last reading, in stage order. */
    public static List<Map<String, Object>> openFindings(Map<String, Object> summary) {
        Map<String, List<Map<String, Object>>> latest = new LinkedHashMap<>();
        if (summary.get("finding_history") instanceof List<?> history) {
            for (Object item : history) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("findings") instanceof List<?> found)) continue;
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object raw : found) {
                    if (!(raw instanceof Map<?, ?> map)) continue;
                    String status = String.valueOf(map.get("status"));
                    if ("closed".equals(status) || "resolved".equals(status) || "superseded".equals(status)) continue;
                    Map<String, Object> row = new LinkedHashMap<>(cast(map));
                    row.put("_stage", String.valueOf(entry.get("stage")));
                    rows.add(row);
                }
                latest.put(String.valueOf(entry.get("stage")), rows);
            }
        }
        List<Map<String, Object>> open = new ArrayList<>();
        latest.values().forEach(open::addAll);
        return open;
    }

    private static String category(Object category) {
        return switch (String.valueOf(category)) {
            case "product_defect" -> "дефект продукта";
            case "contract_gap" -> "пробел в приёмке задачи";
            case "evidence_gap" -> "не хватает доказательств";
            default -> String.valueOf(category);
        };
    }

    private String stages(Map<String, Object> summary) {
        if (!(summary.get("steps") instanceof List<?> steps) || steps.isEmpty()) return "";
        StringBuilder rows = new StringBuilder("<h2>Этапы</h2><table><tr><th>Этап</th><th>Кто</th><th>Итог</th></tr>");
        for (Object item : steps) {
            if (!(item instanceof Map<?, ?> step)) continue;
            Object stage = step.get("stage") != null ? step.get("stage") : step.get("step");
            String who = step.get("profile") == null ? "—"
                    : step.get("profile") + (step.get("vendor") == null ? "" : " · " + step.get("vendor"));
            String outcome = Boolean.TRUE.equals(step.get("ok")) ? "ok" : Boolean.FALSE.equals(step.get("ok")) ? "не прошёл" : "—";
            if (step.get("code") != null && !"ok".equals(step.get("code"))) outcome += " · " + step.get("code");
            if (step.get("reused_from") != null) outcome += " · взято из " + step.get("reused_from");
            if (step.get("blocking_findings") instanceof Number count && count.longValue() > 0) {
                outcome += " · блокирующих замечаний: " + count;
            }
            rows.append("<tr><td>").append(escape(String.valueOf(stage))).append("</td><td>")
                    .append(escape(who)).append("</td><td>").append(escape(outcome)).append("</td></tr>");
        }
        return rows.append("</table>").toString();
    }

    private Map<String, Object> readSummary() {
        Path file = root.resolve(".warden/runs").resolve(runId).resolve("task-run.json");
        try {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 4L * 1024 * 1024) {
                return Json.parseObject(Files.readString(file));
            }
        } catch (Exception unreadable) {
            // An empty summary renders as a page with fewer sections, not as an error.
        }
        return Map.of();
    }

    private String goal(Map<String, Object> summary) {
        if (!(summary.get("task_id") instanceof String id) || !dev.warden.config.RepoPath.isSlug(id)) return null;
        Path file = root.resolve(".warden/tasks").resolve(id + ".yaml");
        try {
            return TaskSpec.parse(Files.readString(file), file.toString()).goal();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * The operator's own words, as the planner that prepared this chain recorded them, or null
     * when no run of the chain was prepared by one.
     *
     * The planner's draft is refused unless its `operator_goal` equals the goal Warden handed
     * it, and the contract goal is that goal with the additions after it; so when the contract
     * still starts with it, it is exactly where the operator's text ends, blank lines and all.
     */
    private String operatorGoal(Map<String, Object> summary, String goal) {
        if (!(summary.get("chain") instanceof Map<?, ?> chain) || !(chain.get("runs") instanceof List<?> runs)) {
            return null;
        }
        // Re-preparation may extend the operator's text. Prefer the latest compatible draft;
        // an amendment's unrelated prompt must not hide an earlier matching operator goal.
        for (int index = runs.size() - 1; index >= 0; index--) {
            Object run = runs.get(index);
            try {
                Path file = runFile(root, String.valueOf(run), "artifacts").resolve("planner.json");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) continue;
                if (Json.parseObject(Files.readString(file)).get("operator_goal") instanceof String said
                        && startsWithGoal(goal.strip(), said.strip())) {
                    return said;
                }
            } catch (Exception unreadable) {
                // Not a planner's run, or not a readable one: the goal splits at its first blank line.
            }
        }
        return null;
    }

    /**
     * The goal as the operator wrote it, with what preparation added to it folded away.
     *
     * With `--prepare auto` the contract goal is the operator's text, the planner's addition
     * and the deliverable, joined by blank lines. The traffic-light contract of 2026-09-24
     * came to 5 KB — six numbered clauses of probe thresholds — and the page printed it as one
     * paragraph; the operator read it as a wall nobody deciding needs to climb.
     *
     * @param operatorGoal the operator's recorded text, or null to take the first paragraph
     */
    static String goalSection(String goal, String operatorGoal) {
        String whole = goal.strip();
        String own;
        String added;
        String said = operatorGoal == null ? "" : operatorGoal.strip();
        if (startsWithGoal(whole, said)) {
            own = said;
            added = whole.substring(own.length()).strip();
        } else {
            String[] split = whole.split("\\R\\s*\\R", 2);
            own = split[0].strip();
            added = split.length > 1 ? split[1].strip() : "";
        }
        StringBuilder html = new StringBuilder("<h2>Цель</h2><p>").append(lines(own)).append("</p>");
        if (added.isEmpty()) return html.toString();
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : added.split("\\R\\s*\\R")) {
            if (!paragraph.isBlank()) paragraphs.add(paragraph.strip());
        }
        boolean deliverable = paragraphs.stream().anyMatch(paragraph -> paragraph.startsWith("Deliverable:"));
        html.append("<details><summary>")
                .append(deliverable ? "Уточнения к цели и ожидаемый результат" : "Подробнее о цели")
                .append("</summary>");
        for (String paragraph : paragraphs) {
            if (paragraph.startsWith("Deliverable:")) {
                html.append("<p><b>Результат:</b> ")
                        .append(lines(paragraph.substring("Deliverable:".length()).strip())).append("</p>");
            } else {
                html.append(clauses(paragraph));
            }
        }
        return html.append("</details>").toString();
    }

    private static boolean startsWithGoal(String whole, String said) {
        return !said.isEmpty() && whole.startsWith(said)
                && (whole.length() == said.length() || Character.isWhitespace(whole.charAt(said.length())));
    }

    /** A paragraph, with its (1) … (n) clauses as a list when it has at least two, in order. */
    private static String clauses(String text) {
        List<int[]> marks = new ArrayList<>();
        int from = 0;
        for (int number = 1; number < 100; number++) {
            String marker = "(" + number + ")";
            int at = text.indexOf(marker, from);
            while (at >= 0 && !standsAlone(text, at, marker.length())) at = text.indexOf(marker, at + 1);
            if (at < 0) break;
            marks.add(new int[] {at, at + marker.length()});
            from = at + marker.length();
        }
        if (marks.size() < 2) return "<p>" + lines(text) + "</p>";
        StringBuilder html = new StringBuilder();
        String lead = text.substring(0, marks.get(0)[0]).strip();
        if (!lead.isEmpty()) html.append("<p>").append(lines(lead)).append("</p>");
        html.append("<ol>");
        for (int index = 0; index < marks.size(); index++) {
            int end = index + 1 < marks.size() ? marks.get(index + 1)[0] : text.length();
            String clause = text.substring(marks.get(index)[1], end).strip();
            // "Lit state: each lamp carries …" — the clause's own short name, when it has one.
            int colon = clause.indexOf(": ");
            if (colon > 0 && colon <= 40 && clause.substring(0, colon).indexOf('.') < 0) {
                html.append("<li><b>").append(escape(clause.substring(0, colon))).append("</b>: ")
                        .append(lines(clause.substring(colon + 2))).append("</li>");
            } else {
                html.append("<li>").append(lines(clause)).append("</li>");
            }
        }
        return html.append("</ol>").toString();
    }

    private static boolean standsAlone(String text, int at, int length) {
        return (at == 0 || Character.isWhitespace(text.charAt(at - 1)))
                && (at + length == text.length() || Character.isWhitespace(text.charAt(at + length)));
    }

    /** Escaped, with the line breaks the text had. */
    private static String lines(String text) {
        return escape(text).replaceAll("\\R", "<br>");
    }

    private List<String> changedFiles(Map<String, Object> summary) {
        if (!(summary.get("diff_base_commit") instanceof String base)) return List.of();
        try {
            Set<String> sorted = new TreeSet<>();
            for (String path : new GitRepository(root, new ProcessRunner()).changedPaths(base)) {
                if (!path.startsWith(".warden/")) sorted.add(path);
            }
            return new ArrayList<>(sorted).subList(0, Math.min(sorted.size(), 50));
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /**
     * This run's screenshots, captioned from the harness's own report and in the order it took
     * them; nothing outside this run's evidence is served.
     *
     * A file name is not a caption. On the first all-Orca run the page listed
     * `1280x720-after-click-3.png` beside `1280x720.png`, the two looked the same, and the
     * operator could not tell that the second was the page after the button's second press,
     * checked visible, rather than a duplicate of the first.
     *
     * Nor is the list of checks one. Captions that carried every assertion made after the press
     * ran to a line of selectors under each picture on the traffic-light run (2026-09-24); the
     * caption now says when the picture was taken, and what was checked on it is the tooltip.
     */
    List<Shot> shots() {
        return shots(harnessScenarios());
    }

    private List<Shot> shots(List<Map<String, Object>> scenarios) {
        List<Path> files = pictureFiles();
        Map<Path, Frame> frames = new LinkedHashMap<>();
        for (Map<String, Object> scenario : scenarios) {
            String viewport = viewport(scenario);
            Path base = inRun(scenario.get("screenshot"), files);
            Frame frame = base == null ? null
                    : frames.computeIfAbsent(base, key -> new Frame()).taken(viewport + " · страница открыта");
            int clicks = 0;
            for (Map<String, Object> step : steps(scenario)) {
                boolean failed = Boolean.FALSE.equals(step.get("ok"));
                if (!"click".equals(step.get("assertion"))) {
                    if (frame != null) frame.checked(stepText(step), failed);
                    continue;
                }
                clicks++;
                // The harness names the file after the step's place in the scenario, not after
                // the press, so every scenario that presses first at step 1 shares one picture.
                Path shot = inRun(step.get("screenshot_after"), files);
                frame = shot == null ? null : frames.computeIfAbsent(shot, key -> new Frame())
                        .taken(viewport + " · после " + clicks + "-го нажатия " + target(step));
                if (frame != null && failed) frame.checked(stepText(step), true);
            }
        }
        List<Shot> shots = new ArrayList<>();
        for (Map.Entry<Path, Frame> entry : frames.entrySet()) {
            if (shots.size() >= MAX_SHOTS) return shots;
            Frame frame = entry.getValue();
            shots.add(new Shot(entry.getKey(), String.join("; ", frame.taken)
                    + (frame.failed ? " · ✗ проверка не прошла" : ""),
                    frame.checked.isEmpty() ? "" : "Проверено на снимке:\n" + String.join("\n", frame.checked)));
        }
        for (Path file : files) {
            if (shots.size() >= MAX_SHOTS) break;
            if (!frames.containsKey(file)) shots.add(new Shot(file, file.getFileName().toString(), ""));
        }
        return shots;
    }

    /** One picture as the scenarios saw it: when it was taken, and what was checked on it. */
    private static final class Frame {
        final Set<String> taken = new java.util.LinkedHashSet<>();
        final Set<String> checked = new java.util.LinkedHashSet<>();
        boolean failed;

        Frame taken(String when) {
            taken.add(when);
            return this;
        }

        void checked(String what, boolean failedHere) {
            checked.add(what);
            failed |= failedHere;
        }
    }

    /**
     * What the browser checked: one line to read, each failure spelled out, and every step of
     * every scenario folded away.
     *
     * Every scenario used to be its own line of selectors above the buttons. The traffic-light
     * run's eleven filled a screen with `css=.housing[data-probe-glow=green] виден ✓ → …`, and
     * the operator read it as detail a person deciding does not need (2026-09-24).
     */
    static String browserChecks(List<Map<String, Object>> scenarios, boolean shotsBelow) {
        if (scenarios.isEmpty()) return "";
        List<Map<String, Object>> failed = new ArrayList<>();
        Set<String> viewports = new java.util.LinkedHashSet<>();
        for (Map<String, Object> scenario : scenarios) {
            if (!Boolean.TRUE.equals(scenario.get("ok"))) failed.add(scenario);
            viewports.add(viewport(scenario));
        }
        int total = scenarios.size();
        StringBuilder html = new StringBuilder("<h2>Что проверил браузер</h2><p>");
        if (failed.isEmpty()) {
            html.append("<b class=pass>✓ ").append(total).append(' ')
                    .append(plural(total, "проверка", "проверки", "проверок"))
                    .append(total == 1 ? ": прошла" : ": все прошли").append("</b>");
        } else {
            html.append("<b class=bad-inline>✗ ").append(failed.size()).append(" из ").append(total).append(' ')
                    .append(plural(total, "проверки", "проверок", "проверок"))
                    .append(plural(failed.size(), " не прошла", " не прошли", " не прошли")).append("</b>");
        }
        html.append(" <span class=muted>· ").append(escape(String.join(", ", viewports)));
        if (shotsBelow) html.append(" · <a href='#shots'>скриншоты ниже</a>");
        html.append("</span></p>");
        if (!failed.isEmpty()) {
            html.append("<ul>");
            for (Map<String, Object> scenario : failed) html.append("<li>").append(scenarioLine(scenario)).append("</li>");
            html.append("</ul>");
        }
        html.append("<details><summary>Все проверки по шагам</summary><ul>");
        for (Map<String, Object> scenario : scenarios) html.append("<li>").append(scenarioLine(scenario)).append("</li>");
        return html.append("</ul></details>").toString();
    }

    /** One scenario as a person would say it, with why it failed when it did. */
    private static String scenarioLine(Map<String, Object> scenario) {
        boolean ok = Boolean.TRUE.equals(scenario.get("ok"));
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> step : steps(scenario)) parts.add(stepText(step));
        String raw = String.valueOf(scenario.get("raw"));
        if (parts.isEmpty() && raw.contains("no-console-errors")) parts.add("в консоли нет ошибок");
        StringBuilder line = new StringBuilder(ok ? "✓ " : "✗ ").append(escape(viewport(scenario))).append(": ")
                .append(escape(String.join(" → ", parts)));
        if (!ok && scenario.get("why") != null) {
            line.append(" <span class=bad-inline>").append(escape(String.valueOf(scenario.get("why")))).append("</span>");
        }
        return line.toString();
    }

    /** The Russian noun form for {@code count}: one, few (2–4) or many. */
    static String plural(long count, String one, String few, String many) {
        long tens = count % 100;
        long units = count % 10;
        if (tens >= 11 && tens <= 14) return many;
        if (units == 1) return one;
        return units >= 2 && units <= 4 ? few : many;
    }

    private List<Map<String, Object>> harnessScenarios() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Path directory : runDirectories()) {
            for (Path report : List.of(directory.resolve("screenshots").resolve("visual-qa.json"),
                    directory.resolve("visual-qa.json"))) {
                try {
                    if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.size(report) > 8L * 1024 * 1024) {
                        continue;
                    }
                    Object parsed = Json.parse(Files.readString(report));
                    Object body = parsed instanceof Map<?, ?> map && map.get("adapter") instanceof Map<?, ?> adapter
                            ? adapter : parsed;
                    if (!(body instanceof Map<?, ?> map) || !(map.get("scenarios") instanceof List<?> list)) continue;
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> scenario) scenarios.add(cast(scenario));
                    }
                    break;
                } catch (Exception unreadable) {
                    // A report that cannot be read captions nothing; the pictures still show.
                }
            }
        }
        return scenarios;
    }

    private static List<Map<String, Object>> steps(Map<String, Object> scenario) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (scenario.get("steps") instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> step) steps.add(cast(step));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String viewport(Map<String, Object> scenario) {
        if (scenario.get("viewport") instanceof Map<?, ?> view && view.get("width") instanceof Number width
                && view.get("height") instanceof Number height) {
            return width.longValue() + "×" + height.longValue()
                    + (Boolean.TRUE.equals(view.get("mobile")) ? " mobile" : "");
        }
        String raw = String.valueOf(scenario.get("raw"));
        int colon = raw.indexOf(':');
        return colon > 0 ? raw.substring(0, colon) : raw;
    }

    /**
     * The element a step names, by its test id where it has one:
     * `css=[data-testid=lamp-green][data-lit=true]` reads as `lamp-green (data-lit=true)`.
     * A selector that says more than which element and which attributes stays as written.
     */
    static String target(Map<String, Object> step) {
        String matcher = String.valueOf(step.get("matcher"));
        if (matcher.startsWith("testid=")) return matcher.substring("testid=".length());
        if (matcher.startsWith("text=")) return "«" + matcher.substring("text=".length()) + "»";
        if (!matcher.startsWith("css=")) return matcher;
        java.util.regex.Matcher simple = SIMPLE_SELECTOR.matcher(matcher.substring("css=".length()));
        if (!simple.matches()) return matcher;
        String element = simple.group(1) != null ? simple.group(1) : "." + simple.group(2);
        String filters = simple.group(3);
        if (filters.isEmpty()) return element;
        return element + " (" + filters.substring(1, filters.length() - 1).replace("][", ", ") + ")";
    }

    /** `[data-testid=x]` or `.class`, then attribute filters and nothing else. */
    private static final java.util.regex.Pattern SIMPLE_SELECTOR = java.util.regex.Pattern.compile(
            "(?:\\[data-testid=['\"]?([\\w-]+)['\"]?]|\\.([\\w-]+))((?:\\[[^\\]\\[]+])*)");

    static String stepText(Map<String, Object> step) {
        String assertion = String.valueOf(step.get("assertion"));
        String text = switch (assertion) {
            case "click" -> "клик по " + target(step);
            case "visible" -> target(step) + " виден";
            case "hidden" -> target(step) + " скрыт";
            default -> target(step) + " " + assertion;
        };
        return text + (Boolean.TRUE.equals(step.get("ok")) ? " ✓" : Boolean.FALSE.equals(step.get("ok")) ? " ✗" : "");
    }

    /** A picture the report names, when it is one of this run's own. */
    private static Path inRun(Object named, List<Path> files) {
        if (!(named instanceof String text) || text.isBlank()) return null;
        String wanted = text.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        for (Path file : files) {
            String candidate = file.toAbsolutePath().normalize().toString().replace('\\', '/')
                    .toLowerCase(java.util.Locale.ROOT);
            if (candidate.equals(wanted)) return file;
            // The report may have been written from another spelling of the same worktree.
            String tail = "/" + file.getParent().getParent().getFileName() + "/screenshots/" + file.getFileName();
            if (wanted.endsWith(tail.toLowerCase(java.util.Locale.ROOT))) return file;
        }
        return null;
    }

    /**
     * This run's evidence directories, keeping only the newest attempt of each stage.
     *
     * A stage writes `<run>--<stage>-<attempt>` once per fix round. Every attempt but the last
     * judged a candidate a repair has since replaced, and listing them all put the failed
     * frames of the first browser pass beside the passing verdict of the last one — or, with
     * eight frames from the first, left the final ones off the page altogether.
     */
    private List<Path> runDirectories() {
        Path runs = root.resolve(".warden/runs");
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> all;
        try (var listing = Files.list(runs)) {
            all = listing.filter(dir -> {
                String name = dir.getFileName().toString();
                return (name.equals(runId) || name.startsWith(runId + "--"))
                        && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
            }).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
        Map<String, Path> newest = new LinkedHashMap<>();
        Map<String, Long> newestAttempt = new LinkedHashMap<>();
        for (Path dir : all) {
            String name = dir.getFileName().toString();
            String stage = name;
            long attempt = 0;
            if (name.startsWith(runId + "--")) {
                String tail = name.substring(runId.length() + 2);
                int dash = tail.lastIndexOf('-');
                if (dash > 0 && tail.substring(dash + 1).matches("\\d{1,9}")) {
                    stage = tail.substring(0, dash);
                    attempt = Long.parseLong(tail.substring(dash + 1));
                }
            }
            Long seen = newestAttempt.get(stage);
            if (seen == null || attempt > seen) {
                newestAttempt.put(stage, attempt);
                newest.put(stage, dir);
            }
        }
        return newest.values().stream().sorted().toList();
    }

    /** Every PNG in this run's evidence, in a stable order. */
    private List<Path> pictureFiles() {
        List<Path> files = new ArrayList<>();
        try {
            Path runs = root.resolve(".warden/runs");
            if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return files;
            Path runsRoot = runs.toRealPath();
            for (Path directory : runDirectories()) {
                Path pictures = directory.resolve("screenshots");
                if (!Files.isDirectory(pictures, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var listing = Files.list(pictures)) {
                    for (Path file : listing.sorted().toList()) {
                        if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!file.toRealPath().startsWith(runsRoot)) continue;
                        if (Files.size(file) > 16L * 1024 * 1024) continue;
                        files.add(file);
                    }
                }
            }
        } catch (IOException unreadable) {
            // Fewer pictures, not a broken page.
        }
        return files;
    }

    private static boolean sameOrigin(HttpExchange exchange, String authority) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://" + authority);
    }

    static String label(String option) {
        return switch (option) {
            case "accept" -> "Принять";
            case "reject" -> "Отклонить";
            case "retry" -> "Повторить";
            case "abort" -> "Прекратить";
            case "advance" -> "Дальше";
            case "switch" -> "Переключить вендора";
            case "apply" -> "Применить правку";
            default -> option;
        };
    }

    static String hint(String option) {
        return switch (option) {
            case "accept" -> "кандидат принят; ничего не сливается, слияние — отдельный шаг";
            case "reject" -> "с заметкой — вернуть исполнителю на доработку; без заметки — закрыть без продолжения";
            case "retry" -> "запустить снова; вердикты по неизменному дереву сохранятся";
            case "abort" -> "закрыть задачу без продолжения";
            case "advance" -> "причина устранена, начать следующий прогон";
            case "switch" -> "перейти на предложенного вендора и продолжить";
            case "apply" -> "применить предложенную правку контракта и продолжить";
            default -> "";
        };
    }

    private static String afterDecision(String choice, boolean withNote) {
        if ("reject".equals(choice) && withNote) {
            return "Warden возвращает работу исполнителю с вашей заметкой; ход виден во вкладке нарратива.";
        }
        return switch (choice) {
            case "retry", "advance", "switch", "apply" -> "Warden продолжает петлю; ход виден во вкладке нарратива.";
            case "accept" -> "Дальше — проверить diff и при желании `warden land`.";
            default -> "Вкладку можно закрыть.";
        };
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.putIfAbsent(key, value);
        }
        return values;
    }

    static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=ru><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>" + escape(title) + "</title>"
                + "<style>:root{color-scheme:dark;font:15px/1.5 system-ui;background:#11171d;color:#e5edf4}"
                + "body{max-width:1100px;margin:28px auto;padding:0 20px}h1{font-size:28px;margin:0 0 4px}"
                + "h2{font-size:18px;margin:26px 0 8px}.muted{color:#9caebd}code{font-size:13px}"
                + "table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #263541}"
                + "th{color:#9fb4c6;font-weight:500}.shots{display:flex;flex-wrap:wrap;gap:14px}"
                + "figure{margin:0;max-width:440px}img{max-width:100%;border:1px solid #30404d;border-radius:8px;background:#fff}"
                + "details{margin:8px 0}summary{cursor:pointer;color:#9fb4c6}details>:not(summary){color:#c9d6e2}"
                + "ol{padding-left:24px}li{margin:4px 0}a{color:#8cc4ff}.pass{color:#8fdcb4}"
                + "figcaption{color:#9caebd;font-size:12px}textarea{width:100%;background:#19232c;color:inherit;"
                + "border:1px solid #405363;border-radius:6px;padding:8px;margin-top:6px}label{display:block}"
                + ".buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}button{background:#233240;color:#e5edf4;"
                + "border:1px solid #405363;border-radius:8px;padding:10px 16px;font:inherit;cursor:pointer;text-align:left}"
                + "button small{display:block;color:#9caebd;font-size:12px}button.primary{background:#1d5c43;border-color:#2f8a64}"
                + ".note{background:#1f2e3d;border:1px solid #3d6a91;border-radius:8px;padding:10px 14px;margin:14px 0}"
                + ".bad-inline{color:#ffae94}.finding{border:1px solid #5a4a2a;background:#2a2518;border-radius:8px;padding:8px 14px;margin:10px 0}"
                + ".finding p{margin:6px 0}.sev{background:#6b4f1d;color:#ffd79a;border-radius:4px;padding:1px 6px;font-weight:600}"
                + ".ok{background:#1b3a2e;border:1px solid #2f8a64;border-radius:8px;padding:10px 14px;margin:14px 0}"
                + ".bad{background:#3d2320;border:1px solid #a4574a;border-radius:8px;padding:10px 14px;margin:14px 0}"
                + "</style>" + body + "</html>";
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
