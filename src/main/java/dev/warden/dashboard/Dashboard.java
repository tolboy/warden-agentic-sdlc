package dev.warden.dashboard;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local read-only projection for Orca's browser. No dispatch, approval, or terminal input. */
public final class Dashboard implements AutoCloseable {
    private final HttpServer server;

    public Dashboard(Path project, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String authority = "127.0.0.1:" + server.getAddress().getPort();
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String contentType = "application/json; charset=utf-8";
            String body;
            if (!authority.equals(host) || (origin != null && !origin.equals("http://" + authority))) {
                status = 403; body = "{}";
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405; body = "{}";
            } else if ("/".equals(path)) {
                contentType = "text/html; charset=utf-8"; body = HTML;
            } else if ("/api/state".equals(path)) {
                try { body = Json.write(snapshot(project, UserConfig.load())); }
                catch (Exception failed) { status = 503; body = Json.write(Map.of("error", "State unavailable; inspect Warden configuration")); }
            } else { status = 404; body = "{}"; }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    public void start() { server.start(); }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    @Override public void close() { server.stop(0); }

    public static Map<String, Object> snapshot(Path project, UserConfig user) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("project", project.toAbsolutePath().normalize().toString());
        state.put("config_home", user.home().toString());
        state.put("observed_at", Instant.now().toString());
        state.put("problems", user.problems());
        List<Map<String, Object>> plan = new ArrayList<>();
        if (user.policy() != null) for (var stage : user.policy().workflow().stages()) {
            if (stage.role() == null) continue;
            var role = user.policy().roles().get(stage.role());
            if (role == null) continue;
            for (String name : role.profiles()) {
                Profile profile = user.profiles().get(name);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage.name()); row.put("role", stage.role()); row.put("profile", name);
                row.put("strategy", role.strategy()); row.put("when", stage.when());
                row.put("position", user.policy().workflow().rotationPositionOf(stage));
                if (profile != null) {
                    row.put("model", profile.model()); row.put("effort", profile.effort());
                    row.put("runner", profile.runner()); row.put("verified", profile.verified());
                    row.put("read_only", profile.readOnly());
                }
                plan.add(row);
            }
        }
        state.put("plan", plan);
        List<Map<String, Object>> attempts = new ArrayList<>();
        List<Map<String, Object>> runRows = new ArrayList<>();
        long dryRunsHidden = 0L;
        Path runs = project.toAbsolutePath().normalize().resolve(".warden/runs");
        // Never follow project-controlled links out of the evidence directory.
        if (Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)
                && runs.toRealPath().startsWith(project.toRealPath())) {
            Path runsRoot = runs.toRealPath();
            try (var directories = Files.list(runs)) {
                for (Path directory : directories.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (!directory.toRealPath().startsWith(runsRoot)) continue;
                    Path summaryFile = directory.resolve("task-run.json");
                    if (Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            if (Files.size(summaryFile) <= 2L * 1024 * 1024
                                    && summaryFile.toRealPath().startsWith(runsRoot)) {
                                Map<String, Object> source = Json.parseObject(Files.readString(summaryFile));
                                if (Boolean.TRUE.equals(source.get("dry_run"))) dryRunsHidden++;
                                else runRows.add(publicRun(source, summaryFile, directory, runsRoot));
                            }
                        } catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                    }
                    try (var files = Files.list(directory)) {
                        for (Path file : files.filter(p -> p.getFileName().toString().startsWith("view-")
                                && p.toString().endsWith(".json") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            if (Files.size(file) > 1024 * 1024) continue;
                            if (!file.toRealPath().startsWith(runsRoot)) continue;
                            try { attempts.add(publicAttempt(Json.parseObject(Files.readString(file)))); }
                            catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                        }
                    }
                }
            }
        }
        runRows.sort(Comparator.comparing(row -> String.valueOf(row.get("updated_at")), Comparator.reverseOrder()));
        attempts.sort(Comparator.comparing(row -> String.valueOf(row.get("updated_at")), Comparator.reverseOrder()));
        state.put("runs", runRows.stream().limit(100).toList());
        state.put("dry_runs_hidden", dryRunsHidden);
        state.put("attempts", attempts.stream().limit(200).toList());
        return state;
    }

    static Map<String, Object> publicRun(Map<String, Object> source, Path summaryFile, Path directory,
                                         Path runsRoot) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("run_id", "task_id", "ok", "reason", "next_action", "prepare", "risk",
                "dry_run", "candidate_review_passed", "open_blocking_findings", "role_runs",
                "total_cost_usd", "unpriced_calls", "attempts_used", "continued_from")) {
            copyScalar(row, source, key);
        }
        List<String> writers = stringList(source.get("writer_vendors"));
        if (writers != null) row.put("writer_vendors", writers);
        if (source.get("chain") instanceof Map<?, ?> chain) {
            Map<String, Object> visible = new LinkedHashMap<>();
            List<String> chainRuns = stringList(chain.get("runs"));
            if (chainRuns != null) visible.put("runs", chainRuns);
            for (String key : List.of("role_runs", "cost_usd", "unpriced_calls", "fix_attempts",
                    "elapsed_seconds", "elapsed_known", "calls_remaining")) {
                copyScalar(visible, chain, key);
            }
            row.put("chain", visible);
        }
        List<Map<String, Object>> coverage = objectList(source.get("review_coverage"),
                List.of("stage", "role", "source", "ok", "blocking_findings", "profile", "vendor", "assurance"));
        if (coverage != null) row.put("review_coverage", coverage);
        List<Map<String, Object>> steps = objectList(source.get("steps"),
                List.of("step", "stage", "attempt", "ok", "code", "profile", "vendor", "cost_usd",
                        "reused_from", "fix_for", "blocking_findings"));
        if (steps != null) row.put("steps", steps);
        List<Map<String, Object>> pending = objectList(source.get("pending_stages"), List.of("stage", "reason"));
        if (pending != null) row.put("pending_stages", pending);
        Map<String, Object> decision = readDecision(directory, runsRoot);
        if (decision != null) row.put("decision", decision);
        if (source.get("orca_gate") instanceof Map<?, ?> gate) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, gate, "published");
            copyScalar(visible, gate, "gate_id");
            row.put("orca_gate", visible);
        }
        if (source.get("next_step") instanceof Map<?, ?> step) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, step, "kind");
            copyScalar(visible, step, "summary");
            row.put("next_step", visible);
        } else if (source.get("safe_next_step") instanceof String summary) {
            row.put("next_step", Map.of("summary", summary));
        }
        if (source.get("budget_plan") instanceof Map<?, ?> plan) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, plan, "requested_cap");
            copyScalar(visible, plan, "minimum_success_calls");
            row.put("budget_plan", visible);
        }
        row.put("updated_at", Files.getLastModifiedTime(summaryFile).toInstant().toString());
        return row;
    }

    private static Map<String, Object> readDecision(Path directory, Path runsRoot) {
        Path file = directory.resolve("decision.json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 2L * 1024 * 1024
                    || !file.toRealPath().startsWith(runsRoot)) return null;
            Map<String, Object> source = Json.parseObject(Files.readString(file));
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("state", "kind", "decision", "actor", "updated_at")) {
                copyScalar(visible, source, key);
            }
            List<String> options = stringList(source.get("options"));
            if (options != null) visible.put("options", options);
            return visible;
        } catch (RuntimeException | IOException invalid) {
            return null;
        }
    }

    private static void copyScalar(Map<String, Object> dest, Map<?, ?> source, String key) {
        if (!source.containsKey(key)) return;
        Object value = source.get(key);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            dest.put(key, value);
        }
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> values = new ArrayList<>();
        for (Object item : list) if (item instanceof String text) values.add(text);
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> objectList(Object raw, List<String> keys) {
        if (!(raw instanceof List<?> list)) return null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : keys) copyScalar(row, map, key);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    public static Map<String, Object> publicAttempt(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("workflow_run_id", "run_id", "task_id", "stage", "role", "profile", "vendor",
                "model", "effort_requested", "runner", "read_only", "view_state", "updated_at", "code",
                "orca_dispatch_id", "orca_task_id", "vendor_attempt")) {
            Object value = source.get(key);
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) row.put(key, value);
        }
        if (source.get("launch") instanceof Map<?, ?> launch) {
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("status", "source", "effort_source")) {
                if (launch.get(key) instanceof String value) visible.put(key, value);
            }
            Map<String, Object> effective = new LinkedHashMap<>();
            if (launch.get("effective") instanceof Map<?, ?> options) {
                for (String key : List.of("agent", "model", "effort")) {
                    if (options.get(key) instanceof String value) effective.put(key, value);
                }
            }
            visible.put("effective", effective); row.put("launch", visible);
        }
        if ("running".equals(row.get("view_state"))) {
            boolean controllerAlive = source.get("controller_pid") instanceof Number pid
                    && ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive)
                    .flatMap(p -> p.info().startInstant()).map(Instant::toString)
                    .filter(start -> start.equals(source.get("controller_started_at"))).isPresent();
            if (!controllerAlive) row.put("view_state", "unknown_controller_stopped");
        }
        return row;
    }

    private static final String HTML = """
            <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Warden · Agents</title><style>
            :root{color-scheme:dark;font:14px/1.5 system-ui;background:#11171d;color:#e5edf4}body{max-width:1500px;margin:32px auto;padding:0 24px}
            h1{font-size:30px;margin-bottom:4px}h2{font-size:19px;margin-top:32px}.muted{color:#9caebd}#connection{color:#79d7ac}
            .scroll{overflow:auto;border:1px solid #30404d;border-radius:12px}table{border-collapse:collapse;width:100%;white-space:nowrap}
            th,td{text-align:left;padding:12px 14px;border-bottom:1px solid #263541}th{color:#9fb4c6;font-weight:500;background:#19232c}
            tr:last-child td{border:0}td:first-child{font-weight:600}code{font-size:12px}input{background:#19232c;border:1px solid #405363;border-radius:6px;padding:8px 12px;color:inherit;min-width:240px}
            .badge{background:#233a35;color:#9ee9c6;border-radius:5px;padding:3px 7px}.bad{color:#ffae94}#path{overflow-wrap:anywhere}.empty{padding:28px;color:#9caebd}
            details.timeline{white-space:normal;font-weight:400;max-width:480px}details.timeline summary{cursor:pointer;color:#9fb4c6}details.timeline div{padding:2px 0}
            </style><h1>Warden <span class="muted">/ Agents</span></h1>
            <div id="connection" role="status">Подключение…</div><p id="path" class="muted"></p>
            <p class="muted">Workflow и роли задаёт Warden. Orca подтверждает параметры запуска. Эти данные не подтверждают фактическую модель inference и не заменяют результат проверок.</p>
            <h2>Запуски</h2><p class="muted">Каждый запуск со своей хронологией этапов, вердиктами, стоимостью и решением. Dry-run в этот список не входят.</p>
            <p id="dry-hidden" class="muted"></p>
            <div class="scroll"><table><thead><tr><th>Run / задача</th><th>Исход</th><th>Review</th><th>Блокеры</th><th>Вызовы</th><th>Стоимость</th><th>Фикс</th><th>Решение</th><th>Gate</th><th>Хронология</th></tr></thead><tbody id="runs"></tbody></table><div id="runs-empty" class="empty" hidden>Запусков пока нет.</div></div>
            <h2>Попытки исполнения</h2><input id="filter" aria-label="Фильтр запусков" placeholder="Поиск по run, роли или профилю">
            <p class="muted">Обновление каждые 2 секунды. После остановки контроллера работа агента может продолжаться; статус становится неизвестным.</p>
            <div class="scroll"><table><thead><tr><th>Run / этап</th><th>Роль / профиль</th><th>Runner</th><th>Запрошено</th><th>Подтверждено Orca</th><th>Состояние / dispatch</th></tr></thead><tbody id="attempts"></tbody></table><div id="empty" class="empty" hidden>Попыток пока нет. Запуск агентов из этой панели не выполняется.</div></div>
            <h2>Настроенный workflow</h2><p class="muted">Перечислены кандидаты для каждого этапа. Это конфигурация, не доказательство запуска; выбор зависит от проверки профиля, стратегии и доступности.</p>
            <div class="scroll"><table><thead><tr><th>Этап</th><th>Роль</th><th>Профиль-кандидат</th><th>Модель</th><th>Effort</th><th>Runner</th><th>Ограничения / проверка</th></tr></thead><tbody id="plan"></tbody></table></div><p id="problems" class="bad"></p>
            <script>
            const $=id=>document.getElementById(id); let data;
            function cell(tr,value){const td=document.createElement('td');td.textContent=value??'—';tr.append(td)}
            function table(id,rows){const body=$(id);body.replaceChildren();for(const values of rows){const tr=document.createElement('tr');values.forEach(v=>cell(tr,v));body.append(tr)}}
            function line(parent,text){const div=document.createElement('div');div.textContent=text;parent.append(div)}
            function timeline(run){const details=document.createElement('details');details.className='timeline';const summary=document.createElement('summary');summary.textContent='Хронология';details.append(summary);
            for(const s of run.steps||[]){const who=[s.profile,s.vendor].filter(Boolean).join('/');let text=(s.stage||s.step||'—')+(who?' · '+who:'');text+=' · '+(s.ok===true?'ok':s.ok===false?'fail':'—')+(s.code?' / '+s.code:'');if(s.cost_usd!=null)text+=' · '+s.cost_usd;if(s.reused_from)text+=' · reused from '+s.reused_from;if(s.fix_for)text+=' · fix for '+s.fix_for;line(details,text)}
            for(const c of run.review_coverage||[]){line(details,'coverage '+(c.stage??'')+' · '+(c.role??'')+(c.assurance!=null?' · '+c.assurance:''))}
            for(const p of run.pending_stages||[]){line(details,'pending '+(p.stage??'')+' · '+(p.reason??''))}
            if(run.next_step?.summary)line(details,run.next_step.summary);
            const options=run.decision?.options;if(options&&options.length)line(details,'warden approve '+(run.run_id??'')+' --decision '+options.join('|'));
            return details}
            function drawRuns(){const body=$('runs');body.replaceChildren();const runs=data.runs||[];
            for(const r of runs){const tr=document.createElement('tr');const chain=r.chain||{},decision=r.decision||{},gate=r.orca_gate||{};
            const cost=chain.cost_usd??r.total_cost_usd,unpriced=chain.unpriced_calls??r.unpriced_calls;
            let costText=cost==null?'—':String(cost);if(unpriced)costText+=' · '+unpriced+' без цены';
            const outcome=(r.ok===true?'ok':r.ok===false?'нет':'—')+(r.reason?' · '+r.reason:'');
            [r.run_id+' / '+(r.task_id??'—'),outcome,r.candidate_review_passed===true?'да':r.candidate_review_passed===false?'нет':'—',r.open_blocking_findings,chain.role_runs??r.role_runs,costText,chain.fix_attempts??r.attempts_used,decision.state??'—',gate.gate_id??'—'].forEach(v=>cell(tr,v));
            const td=document.createElement('td');td.append(timeline(r));tr.append(td);body.append(tr)}
            $('runs-empty').hidden=runs.length>0;$('dry-hidden').textContent=data.dry_runs_hidden?('Скрыто dry-run: '+data.dry_runs_hidden):''}
            const states={running:'В работе',completed:'Завершено',failed:'Ошибка',needs_you:'Нужен ответ',dry_run:'Dry run',unknown_controller_stopped:'Контроллер остановлен · агент не проверен'};
            function draw(){if(!data)return;drawRuns();const q=$('filter').value.toLowerCase();const rows=data.attempts.filter(r=>JSON.stringify(r).toLowerCase().includes(q));
            table('attempts',rows.map(r=>{const l=r.launch,e=l?.effective;return [r.workflow_run_id+' / '+r.stage,r.role+' / '+r.profile,r.runner,(r.model??'по умолчанию')+' · '+(r.effort_requested??'effort не задан'),l?.status==='matched'?(e?.model??'не задана')+' · '+(e?.effort??'effort неизвестен'):(l?.status??'не подтверждено'),(states[r.view_state]??r.view_state)+' / '+(r.orca_dispatch_id??'—')]}));$('empty').hidden=rows.length>0;
            table('plan',data.plan.map(r=>[r.stage,r.role,r.profile,r.model,r.effort??'не задан',r.runner,(r.read_only?'read-only':'запись')+' / '+(r.verified?'профиль проверен':'требуется проверка')]));
            $('path').textContent=data.project+' · '+data.config_home;$('problems').textContent=Object.entries(data.problems).map(([k,v])=>k+': '+v).join('; ')}
            $('filter').addEventListener('input',draw);
            async function refresh(){try{const response=await fetch('/api/state',{cache:'no-store'});if(!response.ok)throw Error('unavailable');data=await response.json();draw();$('connection').textContent='Данные обновлены · '+new Date(data.observed_at).toLocaleTimeString();$('connection').className=''}catch(e){$('connection').textContent='Нет связи · показаны последние полученные данные';$('connection').className='bad'}finally{setTimeout(refresh,2000)}}refresh();
            </script></html>
            """;
}
