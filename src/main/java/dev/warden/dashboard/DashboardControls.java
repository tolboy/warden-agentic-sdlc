package dev.warden.dashboard;

/** Same-origin, text-only controls; requests cannot carry filenames, commands or raw YAML. */
final class DashboardControls {
    private DashboardControls() {}
    static final String BUDGET_HTML = """
            <fieldset><legend>Бюджеты задачи</legend><div class="next-fields">
            <label>Вызовы · 1–50<input id="limit-max_role_runs" type="number" min="1" max="50" step="1" required></label>
            <label>Стоимость USD · 0 = без денежного лимита<input id="limit-max_cost_usd" type="number" min="0" max="10000" step="any" required></label>
            <label>Режим стоимости<select id="limit-cost_cap"><option value="threshold">Порог по известным расходам</option><option value="strict">Строгий cap с резервированием</option></select></label>
            <label>Время цепочки, минуты · 1–1440<input id="limit-max_elapsed_minutes" type="number" min="1" max="1440" step="1" placeholder="Не ограничено"></label>
            <label>Исправления · 0–10<input id="limit-max_fix_attempts" type="number" min="0" max="10" step="1"></label>
            <label>Timeout вызова, минуты · 1–240<input id="limit-timeout_minutes" type="number" min="1" max="240" step="1"></label>
            <label>Срок вопроса человеку, часы · 1–720<input id="limit-gate_ttl_hours" type="number" min="1" max="720" step="1" placeholder="Без срока"></label>
            </div><p class="muted">Стоимость без цены остаётся неизвестной. Строгий cap требует верхней границы цены у всех возможных платных профилей. Время цепочки включает работу и backoff; ожидание человека учитывается отдельно. Пустой timeout или число исправлений наследует проект.</p></fieldset>
            """;
    static final String HTML = """
            <section id="personal-settings" class="editor" aria-labelledby="personal-title">
            <p id="settings-receipt" role="status" aria-live="polite"></p>
            <h2 id="personal-title">Личные настройки по умолчанию</h2>
            <p class="muted">Сохраняются в личный policy.yaml и действуют для всех проектов с этим config home. Task use: и CLI могут закрепить другой выбор. Профиль задаёт вендора, модель, effort и runner; его проверка здесь не меняется.</p>
            <p id="personal-status" role="status" aria-live="polite">Выберите задачу для проверки политики.</p>
            <div id="personal-roles"></div>
            <fieldset><legend>Повторы и переключения</legend><div class="next-fields">
            <label>При исчерпании квоты<select id="personal-on_quota_exhausted"><option value="confirm">Спросить</option><option value="auto">Автоматически</option><option value="stop">Остановиться</option></select></label>
            <label>При ошибке вендора<select id="personal-on_vendor_failure"><option value="stop">Остановиться</option><option value="confirm">Спросить</option><option value="auto">Автоматически</option></select></label>
            <label>Переключений на стадию · 0–8<input id="personal-max_per_stage" type="number" min="0" max="8" step="1" required></label>
            <label>Повторов rate limit · 0–5<input id="personal-max_attempts" type="number" min="0" max="5" step="1" required></label>
            <label>Начальный backoff, секунды · 1–900<input id="personal-backoff_seconds" type="number" min="1" max="900" step="1" required></label>
            <label>Резерв исправления<select id="personal-repair_reserve"><option value="full">Полный оставшийся цикл</option><option value="partial">Исправление и один проверяющий</option></select></label>
            </div><p class="muted">Порядок профилей определяет доступные fallback. Ответ с замечаниями не считается ошибкой вендора. Каждый повтор расходует лимит вызовов; backoff ограничен оставшимся временем цепочки.</p></fieldset>
            <button id="personal-preview" type="button" disabled>Проверить изменения политики</button>
            <button id="personal-reload" type="button">Перечитать политику</button>
            <div id="personal-result" hidden><p id="personal-file" class="muted"></p><pre id="personal-diff"></pre>
            <pre id="personal-cast"></pre><pre id="personal-problems" class="bad"></pre>
            <button id="personal-apply" type="button" disabled>Apply личной политики</button></div>
            </section>
            <section id="analytics" class="editor" aria-labelledby="analytics-title">
            <h2 id="analytics-title">Аналитика результатов</h2>
            <p class="muted">Локальные журналы попыток и summaries. Счётчики отдельных запусков складываются; накопительные итоги цепочек показаны отдельно. Dry-run исключены. Это описательная статистика, сравнение качества моделей требует парных evals.</p>
            <div class="next-fields"><label>Task ID (пусто — все)<input id="analytics-task" maxlength="160"></label><label>Run ID (пусто — все)<input id="analytics-run" maxlength="160"></label></div>
            <button id="analytics-reload" type="button">Обновить аналитику</button><p id="analytics-status" role="status" aria-live="polite"></p>
            <p id="analytics-warning" class="bad"></p><div id="analytics-cards" class="cards"></div>
            <h3>Измерения и покрытие</h3><div class="scroll"><table><thead><tr><th>Показатель</th><th>Известная сумма</th><th>Известно / неизвестно</th><th>p50 / p95</th></tr></thead><tbody id="analytics-metrics"></tbody></table></div>
            <h3>Параметры фактических попыток</h3><div class="scroll"><table><thead><tr><th>Вендор</th><th>Модель запрошена</th><th>Модель сообщена</th><th>Effort запрошен · runner</th><th>Вызовы / ошибки</th><th>Известные USD · покрытие</th><th>Время p50 / p95, мс</th></tr></thead><tbody id="analytics-settings"></tbody></table></div>
            <h3>Результаты запусков</h3><div class="scroll"><table><thead><tr><th>Run / задача</th><th>Workflow / review / человек</th><th>Вызовы · известные USD</th><th>Исправления / failover / backoff</th><th>История блокеров</th><th>Цепочка</th></tr></thead><tbody id="analytics-runs"></tbody></table></div>
            <div id="analytics-details"></div>
            <p class="muted">Время вызовов — измеренные duration попыток, backoff — запрошенные паузы, ожидание человека — записанные wait events. Неизмеренное ожидание инфраструктуры и дефекты после принятия неизвестны. Closed transitions описывает изменения блокирующих IDs, а не доказанное исправление каждого дефекта. Запрошенная модель/effort не подтверждают inference.</p>
            </section>
            """;

    static final String SCRIPT = """
            const limitKeys=['max_role_runs','max_cost_usd','cost_cap','max_elapsed_minutes','max_fix_attempts','timeout_minutes','gate_ttl_hours'];
            const personalKeys=['on_quota_exhausted','on_vendor_failure','max_per_stage','max_attempts','backoff_seconds','repair_reserve'];
            let taskEdit=null,personalEdit=null,personalData=null,personalGeneration=0,analyticsGeneration=0;
            async function settingsPost(path,request){const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Warden-Token':token},body:JSON.stringify(request)});const result=await response.json();
            if(!response.ok)throw Error(response.status===409?'Конфликт настроек ('+(result.error??'config_conflict')+'). Перечитайте и заново проверьте diff.':result.message??result.error);return result}
            function clearTaskEdit(){taskEdit=null;$('task-apply').disabled=true;$('task-edit-diff').textContent='';$('task-edit-file').textContent='';$('task-save-status').textContent=''}
            function fillLimits(d){for(const key of limitKeys)$('limit-'+key).value=d[key]??''}
            for(const key of limitKeys)$('limit-'+key).addEventListener('input',()=>{clearNext();$('next-status').textContent='Бюджет изменён. Повторите preview перед сохранением.'});
            function taskChanges(){const use=Object.create(null),limits=Object.create(null);
            for(const field of $('next-stages').querySelectorAll('[data-next-stage]'))for(const control of field.querySelectorAll('[data-next-kind]')){const value=control.value.trim();if(value)(use[control.dataset.nextKind]??=Object.create(null))[field.dataset.nextStage]=value}
            for(const key of limitKeys){const input=$('limit-'+key);if(!input.reportValidity())throw Error('Проверьте бюджет: '+key);const v=input.value===''?null:key==='cost_cap'?input.value:Number(input.value);if(v!==nextData[key])limits[key]=v}
            return {use,limits}}
            function previewText(p){const plan=p.budget_plan;return 'Минимум вызовов: '+plan.minimum_success_calls+' · cap: '+plan.requested_cap+' · резерв: '+plan.repair_reserve+
            (plan.recovery_branches.length?'\\nИсправления:\\n'+plan.recovery_branches.map(b=>b.stage+': ещё '+b.calls_required_here+' вызовов · '+(b.repair_allowed_under_cap?'помещается':'не помещается')).join('\\n'):'')}
            function problemText(p){return (p.problems||[]).map(p=>(p.stage?p.stage+': ':'')+(p.code??p.stop_reason??'отказ')+' · '+(p.message??'')).join('\\n')}
            async function previewTask(){if(!nextData)return;clearNext();const generation=nextGeneration;$('next-preview').disabled=true;
            try{const request={task:nextData.task,revision:nextData.revision,target:'task',changes:taskChanges()},result=await settingsPost('/api/settings/preview',request);if(generation!==nextGeneration)return;
            taskEdit={...request,preview_sha256:result.preview_sha256};const p=result.preview,e=result.exports;
            $('next-cast').textContent=p.would_run.map(plannedDispatch).join('\\n');$('next-budget').textContent=previewText(p);$('next-problems').textContent=problemText(p);
            $('task-edit-file').textContent=result.file;$('task-edit-diff').textContent=result.diff;$('task-apply').disabled=!result.apply_allowed;
            $('next-dry-command').value=e?.dry_run_command??'';$('next-run-command').value=e?.run_command??'';$('next-yaml').value=e?.task_overlay??'';
            for(const button of document.querySelectorAll('[data-next-copy]'))button.disabled=!$(button.dataset.nextCopy).value;
            $('next-result').hidden=false;$('next-status').textContent=!p.settings_ok?'Есть причины отказа. Apply и запуск недоступны.':e?'Состав и бюджет согласованы. Можно экспортировать команду или сохранить diff.':'Состав и бюджет согласованы. Сохраните изменения задачи через Apply, затем повторите preview для CLI-команд.';
            }catch(e){if(generation===nextGeneration)$('next-status').textContent=e.message}finally{if(nextData)$('next-preview').disabled=false}}
            $('task-apply').addEventListener('click',async()=>{if(!taskEdit)return;const edit=taskEdit;clearTaskEdit();$('next-preview').disabled=true;$('task-save-status').textContent='Сохраняю…';
            try{const result=await settingsPost('/api/settings/apply',edit);$('settings-receipt').textContent='Сохранена задача: '+result.file+' · backup: '+result.backup;await loadConfig();$('next-status').textContent='Настройки задачи сохранены. Повторите preview для актуальных команд.'}catch(e){$('task-save-status').textContent=e.message;clearNext();$('next-status').textContent=e.message}});
            function clearPersonal(){personalGeneration++;personalEdit=null;$('personal-result').hidden=true;$('personal-apply').disabled=true}
            async function loadPersonal(task){clearPersonal();personalData=null;$('personal-preview').disabled=true;$('personal-roles').replaceChildren();const generation=personalGeneration;
            if(!task){$('personal-status').textContent='Выберите задачу.';return}$('personal-status').textContent='Читаю личную политику…';
            try{const response=await fetch('/api/settings?task='+encodeURIComponent(task),{cache:'no-store'}),d=await response.json();if(generation!==personalGeneration)return;if(!response.ok)throw Error(d.message??d.error);personalData={...d,task};
            for(const r of d.roles){const field=document.createElement('fieldset');field.dataset.personalRole=r.role;const legend=document.createElement('legend');legend.textContent=r.role+(r.independent?' · независимый вендор обязателен':'');field.append(legend);
            const grid=document.createElement('div');grid.className='next-fields';field.append(grid);const names=document.createElement('input');names.dataset.roleKind='profiles';names.value=r.profiles.join(', ');names.maxLength=2000;labelled(grid,'Профили по порядку (через запятую)',names);
            const strategy=document.createElement('select');strategy.dataset.roleKind='strategy';option(strategy,'first','Первый доступный');option(strategy,'rotate','Ротация');strategy.value=r.strategy;labelled(grid,'Стратегия',strategy);
            const failure=document.createElement('select');failure.dataset.roleKind='on_vendor_failure';option(failure,'','Наследовать общую политику');for(const value of ['stop','confirm','auto'])option(failure,value,value);failure.value=r.on_vendor_failure??'';labelled(grid,'Ошибка вендора для роли',failure);
            const choices=document.createElement('p');choices.className='muted';choices.textContent='Доступны: '+r.choices.map(p=>p.label).join('; ');field.append(choices);for(const input of [names,strategy,failure])input.addEventListener('input',()=>{clearPersonal();$('personal-status').textContent='Политика изменена. Повторите preview.'});$('personal-roles').append(field)}
            for(const key of personalKeys)$('personal-'+key).value=d[key];$('personal-preview').disabled=false;$('personal-status').textContent='Личная политика прочитана. Preview проверит её на выбранной задаче '+task+'.';
            }catch(e){if(generation===personalGeneration)$('personal-status').textContent=e.message}}
            for(const key of personalKeys)$('personal-'+key).addEventListener('input',()=>{clearPersonal();$('personal-status').textContent='Политика изменена. Повторите preview.'});
            $('personal-reload').addEventListener('click',()=>loadPersonal($('next-task').value));
            function personalChanges(){const changes={roles:Object.create(null)};for(const field of $('personal-roles').querySelectorAll('[data-personal-role]')){const row=Object.create(null);for(const control of field.querySelectorAll('[data-role-kind]'))row[control.dataset.roleKind]=control.dataset.roleKind==='profiles'?control.value.split(',').map(p=>p.trim()).filter(Boolean):control.value||null;changes.roles[field.dataset.personalRole]=row}
            for(const key of personalKeys){const input=$('personal-'+key);if(!input.reportValidity())throw Error('Проверьте '+key);changes[key]=input.type==='number'?Number(input.value):input.value}return changes}
            $('personal-preview').addEventListener('click',async()=>{if(!personalData)return;clearPersonal();const generation=personalGeneration;$('personal-preview').disabled=true;
            try{const request={task:personalData.task,target:'personal',revision:personalData.revision,changes:personalChanges()},d=await settingsPost('/api/settings/preview',request);if(generation!==personalGeneration)return;
            personalEdit={...request,preview_sha256:d.preview_sha256};$('personal-file').textContent=d.file;$('personal-diff').textContent=d.diff;$('personal-cast').textContent=d.preview.would_run.map(plannedDispatch).join('\\n')+'\\n'+previewText(d.preview);$('personal-problems').textContent=problemText(d.preview);$('personal-apply').disabled=!d.apply_allowed;$('personal-result').hidden=false;$('personal-status').textContent=d.changed?'Проверьте diff личной политики. Настройки задачи имеют приоритет.':'Изменений нет.';
            }catch(e){if(generation===personalGeneration)$('personal-status').textContent=e.message}finally{if(personalData)$('personal-preview').disabled=false}});
            $('personal-apply').addEventListener('click',async()=>{if(!personalEdit)return;const edit=personalEdit;clearPersonal();personalData=null;$('personal-preview').disabled=true;$('personal-status').textContent='Сохраняю личную политику…';
            try{const result=await settingsPost('/api/settings/apply',edit);$('settings-receipt').textContent='Сохранена личная политика: '+result.file+' · backup: '+result.backup;await loadConfig()}catch(e){$('personal-status').textContent=e.message}});
            function metricValue(v){return v==null?'неизвестно':typeof v==='number'?new Intl.NumberFormat('ru',{maximumFractionDigits:4}).format(v):String(v)}
            function metricCoverage(m){return (m?.known_count??0)+' / '+(m?.unknown_count??0)}
            function statsRow(title,m,percentiles=false){return [title,metricValue(m?.total),metricCoverage(m),percentiles?metricValue(m?.p50)+' / '+metricValue(m?.p95):'—']}
            function card(title,value){const div=document.createElement('div');div.className='card';const strong=document.createElement('strong');strong.textContent=metricValue(value);div.append(strong,document.createTextNode(title));$('analytics-cards').append(div)}
            async function loadAnalytics(){const generation=++analyticsGeneration;$('analytics-status').textContent='Читаю журналы…';
            try{const q=new URLSearchParams({task:$('analytics-task').value.trim(),run:$('analytics-run').value.trim()}),response=await fetch('/api/analytics?'+q,{cache:'no-store'}),d=await response.json();if(generation!==analyticsGeneration)return;if(!response.ok)throw Error(d.error);
            const m=d.metrics,t=m.telemetry,cost=t.cost_usd;$('analytics-warning').textContent=(d.incomplete?'Неполные данные: '+(d.skipped||[]).join(', ')+' · точный общий расход не установлен. ':'')+(cost.unknown_count?'Без цены: '+cost.unknown_count+' попыток. ':'');
            $('analytics-cards').replaceChildren();card('Запусков',d.outcomes.runs);card('Готовы к решению человека',d.outcomes.ready_for_human);card('Review пройден',d.outcomes.review_passed);card('Приняты человеком',d.outcomes.human_accepted);card('Vendor-вызовов',m.role_runs.total);card('Успешных vendor-вызовов',d.outcomes.vendor_ok);card('Попыток с возвращённым бюджетом',m.role_runs.uncharged_attempts);card(d.exact_cost_available?'Расход USD':'Известная часть расходов USD',cost.total);
            table('analytics-metrics',[statsRow('Стоимость USD',cost),statsRow('Input tokens',t.tokens.input),statsRow('Output tokens',t.tokens.output),statsRow('Total tokens',t.tokens.total),statsRow('Время вызовов, мс',t.duration_millis,true),statsRow('Ожидание человека, мс',m.human.wait_millis,true),['Failover / ошибки вендора',m.iterations.failovers+' / '+m.iterations.vendor_failure_failovers,'—','—'],['Наблюдаемые повторы rate limit',d.retry.observed_rate_limit_retries,'По парам попыток одного профиля','—'],['Запрошенные backoff, мс',d.retry.planned_backoff_millis,d.retry.planned_pauses+' пауз','—'],['Ошибки quota / gates / config',m.failures.quota+' / '+m.failures.machine_gate+' / '+m.failures.configuration,'—','—']]);
            table('analytics-settings',(d.settings||[]).map(s=>[s.vendor,s.model_requested??'неизвестна',s.model_reported??'не сообщена',(s.effort_requested??'неизвестен')+' · '+(s.runner??'неизвестен'),s.calls+' / '+s.failed_calls,metricValue(s.cost_usd.total)+' · '+metricCoverage(s.cost_usd),metricValue(s.duration_millis.p50)+' / '+metricValue(s.duration_millis.p95)]));
            table('analytics-runs',d.runs.map(r=>{const rm=r.metrics,c=rm.telemetry.cost_usd,f=r.findings,ch=r.chain;return [r.run_id+' / '+(r.task_id??'—'),(r.reason??'—')+' / '+(r.candidate_review_passed===true?'review ok':r.candidate_review_passed===false?'review fail':'review ?')+' / '+(r.decision.decision??r.decision.state??'нет решения'),rm.role_runs.total+' · '+metricValue(c.total)+' USD · '+metricCoverage(c),rm.iterations.fix_rounds+' / '+rm.iterations.failovers+' / '+r.retry.planned_pauses,f.observed_ids+' ID · closed '+f.closed_transitions+' · persisted '+f.persisted_transitions,(ch.runs||[]).join(' → ')+(ch.role_runs!=null?' · '+ch.role_runs+' вызовов · '+metricValue(ch.cost_usd)+' USD · без цены '+(ch.unpriced_calls??'?')+' · '+(ch.elapsed_known===false?'время неизвестно':metricValue(ch.elapsed_seconds)+' с'):'')]}));
            $('analytics-details').replaceChildren();for(const r of d.runs){const details=document.createElement('details'),summary=document.createElement('summary');summary.textContent=r.run_id+' · хронология';details.append(summary);for(const s of r.timeline)line(details,(s.stage??s.step??'—')+' · '+(s.profile??'')+' · '+(s.vendor??'')+' · '+(s.code??(s.ok===true?'ok':s.ok===false?'fail':'?'))+(s.reused_from?' · reused '+s.reused_from:''));$('analytics-details').append(details)}
            const callDetails=document.createElement('details'),callSummary=document.createElement('summary');callSummary.textContent='Попытки: запрошено / параметры запуска / сообщено вендором';callDetails.append(callSummary);
            for(const c of d.calls||[])line(callDetails,(c.workflow_run_id??'run ?')+' / '+(c.stage??c.role??'—')+' · '+(c.profile??'профиль ?')+' · '+(c.code??'код ?')+' · requested '+(c.model_requested??'?')+' / '+(c.effort_requested??'?')+' · launch '+(c.launch_status??'?')+' '+(c.launch_effective?.model??'?')+' / '+(c.launch_effective?.effort??'?')+' · reported '+(c.model_reported??'?')+' · '+metricValue(c.cost_usd)+' USD');$('analytics-details').append(callDetails);
            $('analytics-status').textContent='Прочитано '+new Date(d.observed_at).toLocaleTimeString()+'. Известно / неизвестно — число измерений. Обновление по кнопке.';
            }catch(e){if(generation===analyticsGeneration)$('analytics-status').textContent='Аналитика недоступна: '+e.message}}
            for(const id of ['analytics-task','analytics-run'])$(id).addEventListener('input',()=>{analyticsGeneration++;$('analytics-status').textContent='Фильтр изменён. Нажмите «Обновить аналитику»; ниже предыдущие данные.'});
            $('analytics-reload').addEventListener('click',loadAnalytics);loadAnalytics();
            """;
}
