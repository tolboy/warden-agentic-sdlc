package dev.warden.dashboard;

/** All data is inserted as text; only deliberate POST actions can start diagnostic inference. */
public final class ModelSettingsUi {
    private ModelSettingsUi() {}
    public static String html() { return HTML; }
    private static final String HTML = """
        <section id="model-settings" aria-labelledby="model-settings-title">
        <style>
        #model-settings form{padding:18px;background:#151f28;border:1px solid #405363;border-radius:10px;margin:16px 0}
        #model-settings label{display:flex;flex-direction:column;gap:5px;margin:12px 0}
        #model-settings input,#model-settings select{padding:8px;background:#19232c;color:inherit;border:1px solid #405363;border-radius:5px;font:inherit}
        #model-settings .fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:14px}
        #model-settings .inline{display:flex;align-items:center;flex-direction:row;gap:10px}
        #model-settings .actions{display:flex;gap:8px;flex-wrap:wrap}#model-settings [hidden]{display:none!important}
        #model-settings button:disabled{opacity:.5;cursor:wait}#model-feedback{white-space:pre-wrap}
        #model-scroll{max-height:640px;overflow:auto}#model-scroll thead{position:sticky;top:0;background:#151f28}
        #model-scroll table{table-layout:fixed;width:100%}#model-scroll th,#model-scroll td{white-space:normal;overflow-wrap:anywhere}
        #model-scroll th:nth-child(1){width:20%}#model-scroll th:nth-child(2){width:26%}#model-scroll th:nth-child(3){width:14%}
        #model-scroll th:nth-child(4){width:22%}#model-scroll th:nth-child(5){width:18%}#model-scroll .actions button{width:100%}
        </style>
        <h2 id="model-settings-title">Модели и проверка перед запуском</h2>
        <p class="muted">Здесь собраны CLI-подписки и API-профили из тех же личных настроек, что использует петля. Pong подтверждает ответ сейчас; допуск к роли и проверка coding-инструментов выдаются отдельно. Проверка может расходовать квоту подписки или API-токены.</p>
        <div class="actions"><button id="models-check-all" type="button">Проверить все модели</button><button id="models-reload" type="button">Перечитать</button><button id="model-add" type="button">Добавить API-модель</button></div>
        <p id="model-feedback" role="status" aria-live="polite"></p>
        <label>Найти модель или профиль<input id="model-search" type="search" placeholder="Например, GLM, Codex, reviewer"></label>
        <div class="scroll" id="model-scroll"><table><thead><tr><th>Профиль · роль</th><th>Модель · подключение</th><th>Допуск к роли</th><th>Последний ответ</th><th>Действия</th></tr></thead><tbody id="model-rows"></tbody></table></div>
        <form id="check-settings-form"><h3>Проверка перед прогоном</h3>
        <label class="inline"><input id="check-before-run" type="checkbox">Проверять выбранные модели и настроенный резерв перед каждым прогоном</label>
        <div class="fields"><label>Ожидание ответа, секунд<input id="check-timeout" type="number" min="15" max="180" required></label>
        <label>Максимум выходных API-токенов<input id="check-tokens" type="number" min="128" max="2048" required></label></div>
        <p class="muted">Если модель не ответит, Warden остановит прогон до рабочих вызовов и покажет причину. Проверка повторяется перед каждым прогоном.</p>
        <details><summary>Время и стоимость проверки</summary><p class="muted">Диагностические вызовы учитываются отдельно от бюджета рабочих ролей. При строгом денежном лимите платная проверка пока недоступна. Для Orca проверяется его собственный канал запуска и завершения работника; у запуска и уборки есть отдельные ограничения времени.</p></details>
        <button type="submit">Сохранить проверку</button></form>
        <form id="model-form" hidden><h3 id="model-form-title">API-модель</h3><div class="fields">
        <label>Имя профиля<input id="model-name" required pattern="[a-zA-Z0-9][a-zA-Z0-9_\\-]{0,63}"></label>
        <label id="model-kind-label">Назначение<select id="model-kind"><option value="reviewer">Текстовое ревью через API</option><option value="implementer">Исполнитель через OpenCode</option><option value="classifier">Классификатор Jev через OpenRouter</option></select></label>
        <label>Точное имя модели<input id="model-id" required maxlength="256"></label>
        <label id="model-effort-label">Уровень усилия CLI<input id="model-effort" maxlength="64" placeholder="Оставить текущий"></label>
        <label id="model-connection-label">Подключение<select id="model-connection"></select></label>
        <label id="model-vendor-label">Разработчик модели<input id="model-vendor" maxlength="64" placeholder="z-ai, deepseek, openai…"></label>
        <label id="model-reported-label" hidden>Закреплённая версия Jev<input id="model-reported" maxlength="128" placeholder="typesafe/jev-1.14-20261014"></label>
        <label id="model-executable-label">Полный путь к OpenCode 1.19.4<input id="model-executable" maxlength="512" placeholder="C:/Tools/opencode-1.19.4/opencode.exe"></label>
        </div><p class="muted">Сохранение сбросит подтверждение профиля. Оно не назначает модель на роль и не включает платный резерв. После сохранения выполните показанную команду проверки профиля. Для нового профиля нужны стандартные prompts/schemas от warden setup.</p>
        <div class="actions"><button type="submit">Сохранить профиль</button><button id="model-cancel" type="button">Отменить</button></div></form>
        <h3>Состав ролей</h3><p class="muted">Порядок задаёт основной профиль и альтернативы. Стратегия first выбирает первый допустимый; rotate чередует. Правила независимости и разрешения переключений сохраняются. Назначить можно только проверенные профили соответствующей роли.</p>
        <div id="model-routes"></div>
        <form id="routing-settings-form"><h3>Подбор усилия через Jev</h3>
        <label class="inline"><input id="routing-enabled" type="checkbox">Классифицировать задачу перед запуском и подбирать effort</label>
        <div class="fields"><label>Профиль классификатора<select id="routing-classifier"></select></label>
        <label>Порог вероятности уровня усилия<input id="routing-confidence" type="number" min="0" max="1" step="0.05" required></label></div>
        <p class="muted">Jev предлагает роли и effort для текущей задачи. Warden сохраняет обязательные этапы и явные настройки пользователя. Сейчас автоматически меняется effort поддерживаемых GPT-6 CLI/Orca-профилей; остальные модели сохраняют свои настройки. При ошибке или низкой уверенности используются обычные настройки. Рекомендации по добавлению ролей записываются в отчёт.</p>
        <button type="submit">Сохранить подбор усилия</button></form>
        </section>
        <script>
        (()=>{'use strict';const el=id=>document.getElementById(id);let state=null,editing=null,busy=false,connections=[];
        const feedback=(text,bad=false)=>{el('model-feedback').textContent=text;el('model-feedback').className=bad?'bad':'muted'};
        const message=code=>({settings_changed_reload:'Настройки изменились. Перечитайте страницу и повторите действие.',settings_invalid:'Проверьте поля, подтверждение профилей и наличие ресурсов warden setup.',cli_model_binding_required:'В CLI-профиле модель и effort должны передаваться через {{model}} и {{effort}} в args и verification.probe. Исправьте привязку в файле профиля перед сменой параметров.',settings_busy:'Настройки сейчас изменяются. Повторите действие.',check_already_running:'Проверка уже идёт.'}[code]||code||'Не удалось выполнить действие');
        async function post(action,payload){const response=await fetch('/api/models/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-Warden-Token':document.querySelector('meta[name="warden-settings-token"]').content},body:JSON.stringify(payload)});const result=await response.json();if(!response.ok)throw Error(message(result.code));return result}
        function cell(tr,text){const td=document.createElement('td');td.textContent=text??'—';tr.append(td);return td}
        function button(parent,text,action){const b=document.createElement('button');b.type='button';b.textContent=text;b.disabled=busy;b.addEventListener('click',action);parent.append(b)}
        function draw(reset=true){const rows=el('model-rows');rows.replaceChildren();const results=state.check_states?Object.values(state.check_states):(state.latest_check?.results||[]);const query=el('model-search').value.toLowerCase();
        for(const p of state.profiles){if(![p.name,p.model,p.connection,p.role,p.runner].filter(Boolean).join(' ').toLowerCase().includes(query))continue;const tr=document.createElement('tr');cell(tr,p.name+' · '+p.role);cell(tr,(p.model||'По умолчанию')+' · '+(p.connection||p.runner));cell(tr,p.kind==='classifier'?'Классификатор · не исполнитель':p.verified?'Подтверждён':'Нужна проверка профиля');
        const last=results.find(r=>(r.source_profile===p.name||r.profile===p.name)&&r.configuration===p.configuration);
        cell(tr,last?(last.ok?'pong':'ошибка: '+last.code)+' · '+new Date(last.checked_at).toLocaleString():'Не проверялся / настройки изменились');
        const actionsCell=cell(tr,'');const actions=document.createElement('div');actions.className='actions';actionsCell.append(actions);button(actions,'Проверить ответ',()=>check([p.name]));if(p.kind!=='classifier')button(actions,'Проверить допуск',()=>verify(p));button(actions,'Изменить',()=>edit(p));rows.append(tr)}
        if(!reset)return;el('check-before-run').checked=state.check.before_run;el('check-timeout').value=state.check.timeout_seconds;el('check-tokens').value=state.check.max_output_tokens;
        el('routing-enabled').checked=state.routing?.enabled===true;el('routing-confidence').value=state.routing?.min_confidence??0.6;const classifiers=el('routing-classifier');classifiers.replaceChildren();for(const p of state.profiles.filter(p=>p.kind==='classifier')){const option=document.createElement('option');option.value=p.name;option.textContent=p.name+' · '+p.model;classifiers.append(option)}if(state.routing?.classifier)classifiers.value=state.routing.classifier;
        const routes=el('model-routes');routes.replaceChildren();for(const r of state.routes){const form=document.createElement('form');const title=document.createElement('h4');title.textContent=r.role;form.append(title);
        const label=document.createElement('label');label.textContent='Профили по порядку, через запятую';const input=document.createElement('input');input.value=r.profiles.join(', ');input.required=true;label.append(input);form.append(label);
        const strategy=document.createElement('select');strategy.setAttribute('aria-label','Стратегия '+r.role);for(const key of ['first','rotate']){const opt=document.createElement('option');opt.value=key;opt.textContent=key;strategy.append(opt)}strategy.value=r.strategy;form.append(strategy);
        const save=document.createElement('button');save.textContent='Сохранить состав';save.type='submit';form.append(save);form.addEventListener('submit',event=>{event.preventDefault();mutate('save-route',{revision:state.policy_revision,role:r.role,profiles:input.value.split(',').map(x=>x.trim()).filter(Boolean),strategy:strategy.value})});routes.append(form)}}
        async function load(){try{const responses=await Promise.all([fetch('/api/models',{cache:'no-store'}),fetch('/api/connections',{cache:'no-store'})]);if(responses.some(r=>!r.ok))throw Error('Настройки недоступны');state=await responses[0].json();connections=(await responses[1].json()).connections||[];draw()}catch(error){feedback(error.message,true)}}
        async function check(names){if(busy)return;busy=true;draw();el('models-check-all').disabled=true;feedback('Ожидаю ответы моделей. Может расходоваться квота или API-токены…');try{const result=await post('check',{profiles:names});feedback(result.ok?'Все выбранные модели ответили pong.':(result.results||[]).filter(r=>!r.ok).map(r=>r.profile+': '+r.code+' — '+r.next).join('\\n'),!result.ok)}catch(error){feedback(error.message,true)}finally{busy=false;el('models-check-all').disabled=false;await load()}}
        async function verify(profile){if(busy)return;busy=true;draw();feedback('Проверяю допуск '+profile.name+'. Это отдельный платный запрос или coding-проба во временном проекте; может занять несколько минут…');try{const result=await post('verify',{name:profile.name,revision:profile.revision});feedback(result.ok?'Допуск профиля подтверждён.':message(result.code)+(result.transcript?' · журнал: '+result.transcript:''),!result.ok)}catch(error){feedback(error.message,true)}finally{busy=false;await load()}}
        async function mutate(action,payload){try{await post(action,payload);feedback('Сохранено.');el('model-form').hidden=true;await load();if(typeof loadConfig==='function')loadConfig()}catch(error){feedback(error.message,true)}}
        function edit(profile){editing=profile;el('model-form').hidden=false;el('model-form-title').textContent=profile?'Профиль '+profile.name:'Новая API-модель';el('model-name').value=profile?.name||'';el('model-name').readOnly=!!profile;el('model-id').value=profile?.model||'';el('model-effort').value=profile?.effort||'';el('model-vendor').value=profile?.vendor||'';
        const select=el('model-connection');select.replaceChildren();for(const c of connections){const option=document.createElement('option');option.value=c.id;option.textContent=c.id+' · '+c.provider;select.append(option)}if(profile?.connection)select.value=profile.connection;
        el('model-kind').value=profile?.kind==='classifier'?'classifier':profile?.role==='implementer'?'implementer':'reviewer';el('model-reported').value=profile?.reported_model||'';
        el('model-kind-label').hidden=!!profile;const api=!profile||!!profile.connection;el('model-connection-label').hidden=!api;el('model-effort-label').hidden=api;kindFields();el('model-form').scrollIntoView({block:'nearest'})}
        function kindFields(){const classifier=el('model-kind').value==='classifier';el('model-reported-label').hidden=!classifier;el('model-vendor-label').hidden=classifier||(editing&&!editing.connection);el('model-executable-label').hidden=!!editing||el('model-kind').value!=='implementer';if(classifier&&!el('model-id').value)el('model-id').value='typesafe/jev-1.14'}
        el('model-kind').addEventListener('change',kindFields);
        el('model-form').addEventListener('submit',async event=>{event.preventDefault();const name=el('model-name').value;const kind=el('model-kind').value;const payload=kind==='classifier'?{name,revision:editing?.revision||'absent',model:el('model-id').value,connection:el('model-connection').value,reported_model:el('model-reported').value}:{name,revision:editing?.revision||'absent',model:el('model-id').value,effort:el('model-effort').value,connection:el('model-connection').value,vendor:el('model-vendor').value,kind,harness_executable:el('model-executable').value};await mutate(kind==='classifier'?'save-classifier':'save-profile',payload)});
        el('check-settings-form').addEventListener('submit',event=>{event.preventDefault();mutate('save-check',{revision:state.settings_revision,before_run:el('check-before-run').checked,timeout_seconds:Number(el('check-timeout').value),max_output_tokens:Number(el('check-tokens').value)})});
        el('routing-settings-form').addEventListener('submit',event=>{event.preventDefault();mutate('save-routing',{revision:state.settings_revision,enabled:el('routing-enabled').checked,classifier:el('routing-classifier').value||'jev-classifier',min_confidence:Number(el('routing-confidence').value)})});
        el('model-search').addEventListener('input',()=>{if(state)draw(false)});el('models-check-all').addEventListener('click',()=>check(state.profiles.map(p=>p.name)));el('models-reload').addEventListener('click',load);el('model-add').addEventListener('click',()=>edit(null));el('model-cancel').addEventListener('click',()=>{el('model-form').hidden=true});load();})();
        </script>
        """;
}
