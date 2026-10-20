**Аудит Warden: петля агентов, ограничения, настройки и измерение результата**

Дата: 2026-10-19. Срез: `7c7f4083eb5c0a99df891bac39897afb02092039`, ветка `fix/keep-pinned-contract-copy`.

Вывод: у Warden уже есть содержательная основа для управляемой петли: машинные gates, независимость проверяющих, ограниченные исправления, подтверждённое завершение Orca, наследование бюджета и durable evidence. Первый приоритет — исключить два одновременных direct CLI workflow в одном рабочем дереве: такой допуск воспроизведён. Затем нужно закрыть потерю protocol output и deadline backoff, исправить данные измерений и сравнения, после этого расширить существующий локальный dashboard конструктором следующего запуска. Измеренных оснований объявлять текущий набор моделей/effort оптимальным пока нет.

Проверялись актуальные исходники, тесты, CLI и локальный dashboard. Вендорам задачи не отправлялись; платные probes и live dispatch Orca не запускались. Production-код и личная конфигурация не изменялись. Три исходных untracked документа про Orca UI сохранены.


Обновление 2026-10-20 после [независимого ревью Grok Bot](AUDIT-AGENT-LOOP-2026-10-19-REVIEW.md): исходный срез был на пять коммитов старше GitHub. После fetch актуальная база — `origin/main` (`106f2a3`). PR #30/#50 уже реализовали failover при vendor failure и 403; F04 нужно исправлять с учётом `cause` и `role_failovers`. Локальный `7c7f408` заменён upstream PR #53 и не включён в новую ветку `codex/agent-loop-priority`. Ниже сохранены исходные доказательства с историческими номерами строк; состояние исправлений описано отдельно в [отчёте реализации](AGENT-LOOP-PRIORITY-FIXES-2026-10-20.md).

**Что уже работает на уровне реализации**

Продолжение F03–F06 описано в [отчёте исправления измерений](AGENT-LOOP-METRICS-FIXES-2026-10-20.md).
F07 и сопоставимые evals — в [отчёте сравнения запусков](AGENT-LOOP-EVALS-FIXES-2026-10-20.md).
F08, атомарные настройки и защита verification stamp — в
[отчёте сохранения настроек](AGENT-LOOP-SETTINGS-FIXES-2026-10-20.md).

| Область | Реализованные механизмы | Практический смысл |
|---|---|---|
| Петля | Детерминированный workflow, schema-validated artifacts, baseline/reproduction, machine gates, ограничение fix rounds, finding registry | Переход между ролями не зависит от заявления агента «готово» |
| Взаимная проверка | Read-only fingerprint, writer provenance, independent vendor, явно маркированный same-vendor peer | Читающий не должен незаметно становиться автором или проверять собственную работу |
| Повтор и замена | Разделены rate limit и исчерпание quota; bounded retry; failover `auto/confirm/stop`; перенос незавершённой работы | Смена исполнителя уже является частью контроллера |
| Бюджет | Проверка перед попыткой, settlement до следующей попытки, chain accounting, CallPlan и reserve на исправление с последующими проверками | Резерв учитывает стоимость продолжения цепочки в вызовах |
| Стоимость | Unknown cost отдельно; threshold и strict reservation по объявленным границам профилей | Неизвестная цена не должна считаться бесплатной; strict опирается на корректность объявленной верхней границы |
| Продолжение | Контракт и fingerprint закреплены; verdict reuse ограничен; human decision durable и привязан к кандидату | Повторный запуск может использовать доказанное, сохраняя условия его действительности |
| Orca | Settlement/ACK/recovery/fencing, отдельная обработка не начавшегося vendor turn | Текст терминала не считается квитанцией завершения |
| Настройки | `roster`, `roster set/model`, `--use`, `--effort`, `--host`, task `use:`, dry-run, effective cast | Для GUI уже есть resolver и модель данных |
| Наблюдение | Progress stderr, `report`, local/global ledger, home corpus, `ledger --compare`, dashboard и decision page | Есть общая основа для диагностики и последующей аналитики |

Три дефекта из прежнего аудита повторно сверены с текущим кодом и не включены в открытые: приоритет spent quota между stdout/stderr, распознавание deadline timeout Orca и учёт завершённой попытки до отказа в следующем dispatch.

**Подтверждённые дефекты**

Приоритеты после независимого ревью: F10/F02 — P1; F01/F03/F04/F05/F06/F07 — P2; F08/F09 — P3. F08 остаётся обязательным условием для постоянного Apply из будущего UI. «Воспроизведено» означает локальный fixture/probe либо браузерную проверку, а не обращение к реальному вендору. «По коду» означает трассировку актуального production path без исполнения описанного сценария.

| ID | Категория | Проблема | Доказательство |
|---|---|---|---|
| F10, P1 | Concurrency | Разные run ID допускают двух direct writers в одно рабочее дерево | Воспроизведено через TaskLoop + DirectCliExecutor |
| F01 | Deadline | Rate-limit backoff может превысить остаток срока цепочки | По коду |
| F02, P1 | CLI protocol | После первых 4 MiB stdout теряются итоговый artifact и usage | Воспроизведено |
| F03 | Accounting | Локальный ledger скрывает завершённый вызов до summary роли | Воспроизведено |
| F04 | Failover metrics | Retry той же модели считается переключением | Воспроизведено |
| F05 | Telemetry | Частично неизвестные tokens становятся нулями в vendor summary | Воспроизведено |
| F06 | Provenance | `by_model` может подставить requested вместо reported | Воспроизведено |
| F07 | Preflight | Проверка независимости заранее учитывает только первого writer | Воспроизведено preview/preflight |
| F08, P3 | Configuration | Запись roster/profile неатомарна и не защищена от lost update | По коду |
| F09, P3 | Dashboard | Заголовки состава стадий смещены относительно значений | Проверено в браузере |

**F10, P1 — разные run ID обходят защиту от одновременной работы в одном checkout.**

Источники: [Main.java:433](../src/main/java/dev/warden/Main.java#L433), [TaskLoop.java:599](../src/main/java/dev/warden/run/TaskLoop.java#L599), [EvidenceLedger.java:133](../src/main/java/dev/warden/ledger/EvidenceLedger.java#L133).

Сценарий: два `warden run` с разными run ID направлены в одно рабочее дерево, профили direct с правом записи. Ожидается `worktree_busy` либо изоляция второго workflow. Фактически резервируется только `.warden/runs/<runId>/run.json`; общего execution lease нет. Synthetic probe запустил два настоящих TaskLoop и DirectCliExecutor: оба дочерних Java CLI одновременно дошли до барьера, `simultaneousDirectWriters=2`; после release оба workflow завершились `ready_for_human`. Вендоров не было; столкновения исходников намеренно не устраивались. Подтверждён одновременный допуск, а смешение изменений/ошибочная атрибуция fingerprint — возможные последствия реальных writers.

Исправление: cross-process lease канонического рабочего дерева на всю цепочку, включая gates и readers; аналогичная защита standalone writable `role`. Разные изолированные worktrees должны продолжать работать параллельно. После падения контроллера recovery должен подтвердить остановку прежнего worker до нового writer. Проверки: два разных run ID из двух JVM в одном root; standalone role против workflow; другой worktree того же репозитория; crash/осиротевший child; освобождение lease после безопасного завершения. [Probe](../out/audit-loop-2026-10-19/ConcurrencyProbe.java), [результат](../out/audit-loop-2026-10-19/concurrency-result.json).

1. **F01 — backoff не ограничен остатком deadline.**

   Источники: [TaskLoop.java:984](../src/main/java/dev/warden/run/TaskLoop.java#L984), [TaskLoop.java:1055](../src/main/java/dev/warden/run/TaskLoop.java#L1055), [TaskLoop.java:5268](../src/main/java/dev/warden/run/TaskLoop.java#L5268), [GateRunner.java:139](../src/main/java/dev/warden/gate/GateRunner.java#L139), [RoleRunner.java:897](../src/main/java/dev/warden/role/RoleRunner.java#L897).

   Сценарий: после rate limit у цепочки осталось 61 секунду, сервер просит retry через 900 секунд. `hasRoom()` допускает ожидание, `sleeper.accept(pause)` получает все 900 секунд. Проверка перед следующим dispatch остановит вызов, но сам срок уже превышен. Ожидается отказ от retry, который не помещается вместе с минимальным следующим вызовом, либо ограниченная остатком пауза. Фактически deadline проверяется только перед паузой и следующей попыткой.

   Отдельная граница текущего дизайна: baseline, reproduction и machine gates получают `timeout_minutes`, без остатка chain deadline. При `max_elapsed_minutes: 2` и timeout=20 минут проверка может работать 20 минут; последний gate может закончиться после deadline. Это прямо оговорено в `budget_plan.time_reserve` ([TaskLoop.java:773](../src/main/java/dev/warden/run/TaskLoop.java#L773)), поэтому здесь это отмечено как ограничение общего лимита, а не скрытая регрессия. Для жёсткого общего wall-clock нужен абсолютный monotonic deadline во всех адаптерах и проверка перед terminal success. Проверки: retry wait длиннее остатка; для расширения семантики также gate длиннее остатка и последний gate после deadline. Остановка должна сохранить корректный reason и сведения о кандидате.

2. **F02, P1 — усечение stdout уничтожает хвост протокола.**

   Источники: [DirectCliExecutor.java:135](../src/main/java/dev/warden/execution/DirectCliExecutor.java#L135), [DirectCliExecutor.java:148](../src/main/java/dev/warden/execution/DirectCliExecutor.java#L148), [DirectCliExecutor.java:169](../src/main/java/dev/warden/execution/DirectCliExecutor.java#L169), [ProcessRunner.java:150](../src/main/java/dev/warden/process/ProcessRunner.java#L150).

   Сценарий: CLI выводит больше 4 MiB tool events, затем финальные `item.completed` и `turn.completed`. Probe: `exit=0`, `stdoutLength=4194304`, `truncated=true`, `hasFinalArtifact=false`, `hasUsage=false`. Ожидается: конечный artifact и usage доступны независимо от объёма предшествующего журнала. Фактически сохраняется prefix, из него же пишется raw transcript и извлекаются artifact/telemetry; executor не выводит флаг усечения в отчёт. Это может превратить успешную оплаченную работу в `role_artifact_unparseable` с неизвестной стоимостью.

   Исправление: потоковая запись полного raw в файл и инкрементальный разбор JSONL/финальных envelope; отдельно ограничить память и размер отображаемого текста. Если полный capture ограничивается политикой, явно фиксировать truncation и сохранять protocol tail. Проверки: artifact/usage после 4 MiB; большой stderr; завершение по timeout после промежуточного usage. [Probe и результат](../out/audit-loop-2026-10-19/README.md).

3. **F03 — crash между attempt journal и role summary скрывает локальные расходы.**

   Источники: [LedgerReader.java:41](../src/main/java/dev/warden/ledger/LedgerReader.java#L41), [RoleRunner.java:887](../src/main/java/dev/warden/role/RoleRunner.java#L887).

   Сценарий: vendor вернул результат; `vendor_attempt` сохранён; процесс оборвался до `role_run`. На одном fixture локальный ledger показывает calls=0 и cost=null без `incomplete`, global corpus показывает calls=1 и cost=$1.25. Ожидается: завершённая попытка учитывается в обоих представлениях либо локальный отчёт явно сообщает неполноту. Фактически localView безусловно удаляет `vendor_attempt`.

   Исправление: локальный и global reader должны использовать одну per-attempt accounting модель; summary дедуплицировать по `vendor_attempt_id`. Проверки: crash до summary, последующее появление summary, повторное чтение без изменения totals. Это подтверждённый дефект отчётности; обход budget/resume этим probe не проверялся. [Результат](../out/audit-metrics-2026-10-19/results.json).

4. **F04 — retry A → A отображается как failover.**

   Источники: [RoleRunner.java:1002](../src/main/java/dev/warden/role/RoleRunner.java#L1002), [LedgerReader.java:333](../src/main/java/dev/warden/ledger/LedgerReader.java#L333), [RunReport.java:417](../src/main/java/dev/warden/ledger/RunReport.java#L417), [Dashboard.java:298](../src/main/java/dev/warden/dashboard/Dashboard.java#L298).

   Сценарий: A получает rate limit, повтор A успешен. Ожидается: 2 attempts, 1 retry, 0 failover. Fixture даёт `failovers=1`, переход `same-profile → same-profile`, dashboard `kind=switched`. Все дополнительные attempts трактуются как замена.

   Исправление на актуальной базе: использовать существующие `cause`/`role_failovers`, отдельно обозначать rate-limit retry и считать смену профиля/модели/вендора; не вводить параллельную конкурирующую схему событий. Проверки: A→A; A→B; A→A→B. [Результат](../out/audit-metrics-2026-10-19/results.json).

5. **F05 — partial token telemetry нарушает правило unknown.**

   Источник: [RunReport.java:326](../src/main/java/dev/warden/ledger/RunReport.java#L326).

   Сценарий: две попытки сообщают только `tokens: {input: 100}`. Ожидается: input=200, output и total неизвестны. Fixture получает input=200, output=0, total=0, `tokens_unknown_calls=0`. Наличие любого поля делает весь объект «известным».

   Исправление: отдельные known/unknown counters для input/output/total, как в LedgerReader. Проверки: input-only, output-only, без total, смесь полных/неполных ответов. [Результат](../out/audit-metrics-2026-10-19/results.json).

6. **F06 — requested model подменяет неизвестную reported model.**

   Источники: [LedgerReader.java:546](../src/main/java/dev/warden/ledger/LedgerReader.java#L546), [RoleRunner.java:1147](../src/main/java/dev/warden/role/RoleRunner.java#L1147), обещание в [METRICS.md:49](METRICS.md#L49).

   Сценарий: CLI не сообщает inference model. Ожидается: reported неизвестна, requested видна отдельно. Фактически `attempt.model` заполняется из профиля, а `by_model` использует это значение при отсутствии `model_reported`. Fixture с requested=`declared-model-only` и effective/reported=`unknown` даёт local/global `by_model: {declared-model-only: 1}`. Для сравнения эффективности такая группа не доказывает, какая модель исполняла запрос. [Результат](../out/audit-metrics-2026-10-19/compare-conditions-results.json).

   Исправление: отдельные dimensions requested/effective/reported со source; использовать уже существующий MeasurementContext. Проверки: declared-only, receipt-only, vendor-reported и mismatch. Без live-подтверждения нельзя выдавать stamp профиля или launch receipt за доказательство inference.

7. **F07 — preflight независимости расходится с runtime при нескольких writers.**

   Источники: [TaskLoop.java:1346](../src/main/java/dev/warden/run/TaskLoop.java#L1346), [TaskLoop.java:1643](../src/main/java/dev/warden/run/TaskLoop.java#L1643), [TaskLoop.java:2220](../src/main/java/dev/warden/run/TaskLoop.java#L2220), [Workflow.java:377](../src/main/java/dev/warden/config/Workflow.java#L377).

   Сценарий: write-A/vendor-A → write-B/vendor-B → review/vendor-B, требуется independent vendor. Несколько implementer stages разрешены. Preview учитывает только A и допускает reader B; runtime накапливает обоих writers и отказывает reader уже после оплаченных стадий. Ожидается бесплатный ранний отказ. Runtime не ослабляет независимость; ошибка относится к preflight и бесполезному расходу. Offline probe подтвердил, что preview допускает reader B против `[writer-a]`, а реальные методы `overlayGap`, `judgeGap`, `toolingGap`, `readerGap` возвращают null. Тот же resolver против обоих writers возвращает `same_vendor_as_writer`. Полный workflow с оплатой не запускался. [Результат](../out/audit-settings-2026-10-19/probe-result.json).

   Исправление: последовательная симуляция всех write-capable stages теми же правилами writer provenance, которые использует runtime. Проверки: два writers, writer после review, task pins, rotate, failover с новым writer vendor.

8. **F08, P3 — backup не заменяет транзакционную запись настройки.**

   Источник: [RosterCommand.java:63](../src/main/java/dev/warden/config/RosterCommand.java#L63).

   Сценарий: редактор/второй CLI меняет YAML между чтением и записью, либо процесс прекращается после truncate. Ожидается конфликт устаревшей версии или целый предыдущий/следующий файл. Фактически `Files.writeString` меняет активный файл до parse; нет lock/expected digest/atomic replace. Можно потерять чужую правку либо получить усечённую конфигурацию; backup остаётся способом ручного восстановления.

   Исправление: parse кандидата в памяти → exclusive lock → expected digest → backup актуальной версии → temp+atomic replace. Проверки: конкурентные записи, stale digest, авария во время записи, сохранение комментариев. Это обязательная предпосылка web Apply.

9. **F09, P3 — таблица настроек вводит в заблуждение.**

   Источники: [Dashboard.java:438](../src/main/java/dev/warden/dashboard/Dashboard.java#L438), [Dashboard.java:477](../src/main/java/dev/warden/dashboard/Dashboard.java#L477).

   В шапке 6 колонок, в строке кандидата 5 ячеек. В браузере: профиль находится под «Этап», модель под «Кандидат», «да» под «Модель», допуск под «Проверен», сведения о цене под «Допуск», «Стоимость» пуста. Ожидается совпадение заголовков и значений.

   Исправление: согласовать структуру и смысл колонок. Проверка: DOM/header mapping плюс narrow viewport. [Снимок](../out/audit-settings-2026-10-19.png).

**Ограничения лимитов и fallback, которые интерфейс должен объяснять**

`max_role_runs` ограничивает попытки, включая retry/failover, а не только число разных ролей. `CallPlan` оценивает чистое прохождение и ветви ремонта; запас на сбои вендора следует показывать отдельно от минимума успешного пути. При threshold `max_cost_usd` ограничивает известные цены между вызовами: неизвестные цены остаются unknown, а один допущенный вызов может превысить остаток. Strict reservation полезен при достоверных upper bounds профилей; сам Warden не обеспечивает достоверность произвольно введённой границы и не останавливает удалённый биллинг по долларовой величине.

Профиль с исчерпанной quota исключается по имени в пределах RoleRunner. Это не общая модель лимита аккаунта: два профиля могут разделять подписку, а две модели одного вендора могут иметь разные окна. Нужны явные необязательные `quota_group/account_scope`, источник наблюдения, время наблюдения и cooldown/reset только при известном timestamp. Стоит различать «выбранная quota исчерпана», «остаток неизвестен» и «профиль ещё не проверялся». Нельзя выводить процент оставшейся подписки из числа вызовов Warden.

Для удобного fallback важно видеть приоритет кандидатов, причину допуска, independence после каждого writer и ожидаемую цену следующего шага. Переключение после сломанного флага/схемы не следует автоматически маскировать как quota recovery. В актуальном main такой policy уже есть: `failover.on_vendor_failure` и `failover.max_per_stage` (PR #30/#50), с явным `cause`. Для него требуется доказанное завершение предыдущего writer: повтор без fencing рискует запустить двух редакторов; текущая поставка добавляет эту защиту для direct CLI.

Передача контекста устроена содержательно: writer получает literal goal/non-goals, registry и закрытые finding IDs, предыдущий repair receipt, сведения об изменении кандидата и остаток бюджета; reviewer — registry и правила closure/reopen. Raw/tool logs автоматически не копируются во всю историю: gate feedback ссылается на полный report, failover handover перечисляет profile/vendor/code. Однако context file читается целиком ([RoleRunner.java:1217](../src/main/java/dev/warden/role/RoleRunner.java#L1217)); byte/token budget для контекста нет, cumulative registry растёт по уникальным finding IDs, schema и changed_files также передаются полностью. Полезно измерять prompt/context/schema bytes и размер registry на attempt, provider-reported input/cache usage с известной семантикой и долю повторного контекста. Следующий шаг — детерминированный компактный handoff с evidence references и явной обработкой превышения размера; goal, открытые blockers и ограничения нельзя молча обрезать. По одному большому input-token числу без понимания vendor accounting нельзя доказать расточительность.

**Минимальный web UI**

Отдельный web app и второй runtime не требуются. Уже есть Java HttpServer, plain HTML/JS, `/api/config`, resolver-backed preview, защита локальных POST и decision page. Разумный scope — добавить к Dashboard форму «Следующий запуск», затем аналитику.

| Часть формы | Что показывать и менять |
|---|---|
| Задача | Project/task, цель, risk, scope, contract readiness; обязательные проверки видны с причиной |
| Стадии | Role, enabled где допускает контракт, profile, vendor, model, effort, runner, fallback order |
| Ограничения | Calls, time, fix rounds, threshold/strict, coverage цены, repair reserve, retry attempts/backoff, failover mode |
| Preview | Effective cast, источник каждого значения, read-only/independence, недоступные кандидаты и причины, минимум calls и резерв на repair |
| Действия | Проверить без dispatch, скопировать CLI, экспортировать task `use:`, сбросить overlay; отдельно сохранить personal defaults |
| Проверка профиля | Stamp/date/config hash, отличается ли текущая настройка от проверенной; запуск потенциально оплаченного probe отдельным явным действием |

Сначала форма может выпускать команду и task overlay без постоянной записи. Model/effort должны иметь проверенные варианты профиля и custom/unknown, поскольку syntactic validity и доставка флага не доказывают поддержку конкретной моделью. Механическим стадиям выбор модели не нужен. Presets «экономный», «обычный», «тщательная проверка» должны раскрываться в обычные видимые настройки; выбор конкретных моделей определяется evals. Обязательные по risk/contract readers нельзя скрыто снимать чекбоксом.

Критерий согласованности: одинаковый вход в GUI preview и CLI dry-run даёт одинаковые cast, budget plan и причины отказа, без vendor call. Для постоянного Apply нужны F08, version/digest conflict и diff. API preview уже отдаёт model/effort/runner задачи ([TaskLoop.java:1627](../src/main/java/dev/warden/run/TaskLoop.java#L1627)), но текущий Dashboard выводит в «Запустится» только профиль ([Dashboard.java:479](../src/main/java/dev/warden/dashboard/Dashboard.java#L479)); это следует исправить в первой UI-поставке.

**Что показывать после задачи**

Первый экран должен отвечать: достигнута ли acceptance, какая версия дерева оценена, что осталось блокером, что остановило цепочку, сколько попыток/времени потрачено и насколько полна цена. У vendor success, workflow ready и human accept должны остаться разные статусы.

Рекомендуемый состав: timeline вызовов и gates; requested/effective/reported model/effort; retry и fallback с причиной; найденные/исправленные/reopened findings; coverage независимых review; diff и evidence; расход текущего run и всей continuation chain; calls/time/fix остаток; known spend рядом с unpriced calls; action для продолжения с точной причиной. Отдельно показать active execution, backoff, infrastructure wait и human wait.

Аналитическая вкладка может читать существующий corpus и общий aggregator: accepted chains per call; known cost per accepted chain с coverage; p50/p95 времени; infrastructure-stop rate; first-pass acceptance; repair yield; повторные findings; успешность восстановления после quota; overhead повторов. Не сводить качество к числу findings или зелёному ответу reviewer. Данные о цене без coverage недостаточны для ранжирования вариантов.

**Evals: протокол есть, измерительная система пока неполна**

[HYPOTHESIS.md](HYPOTHESIS.md) уже задаёт полезный paired protocol: одна база, одинаковая задача, чередование порядка, общие checks, независимое чтение и дефекты после применения. Это хорошая отправная точка. Текущий `ledger --compare` недостаточен для вывода «эта модель/effort лучше»:

- [LedgerCompare.java:538](../src/main/java/dev/warden/ledger/LedgerCompare.java#L538) группирует по prepare/risk/workflow/profile names. Замена model/effort под тем же именем профиля смешивает условия; лимиты, prompt/schema hashes и build не входят в condition key. Roster — множество имён фактически исполненных профилей, поэтому также теряется распределение по ролям, а выборка зависит от пройденного пути.
- [LedgerCompare.java:453](../src/main/java/dev/warden/ledger/LedgerCompare.java#L453) ставит `comparable=true` по одному условию `units >= 3`. Это размер выборки, а не доказательство одинаковых условий, сложности задач или статистической надёжности.
- Baseline schema ([LedgerCompare.java:45](../src/main/java/dev/warden/ledger/LedgerCompare.java#L45)) не связывает пары через `task_pair_id` и не хранит Warden operator effort/post-accept defects. Основная гипотеза о снижении труда оператора автоматически не проверяется.

Offline compare probe это подтвердил: два запуска `model-a/low` и `model-b/high` под `stable-profile`, с различиями в cast, role contract и measurement context, объединяются в одну группу `units=2`, `incomplete=false`. [Результат](../out/audit-metrics-2026-10-19/compare-conditions-results.json).

Нужно два независимых уровня evals:

| Уровень | Набор | Что считать |
|---|---|---|
| Надёжность контроллера, offline | Scripted/stub outcomes: quota, rate limit, timeout, malformed artifact, crash journal/summary, long stdout, blocked writer, restart, stale approval, failed delivery | Нет превышения calls/deadline, потери/двойного учёта, двух активных writers, ложного success; предсказуемый stop и recovery |
| Эффективность работы, реальные задачи | Замороженные пары bugfix/refactor/feature/UI из одинаковой базы, независимые acceptance и adjudication, несколько повторов | Acceptance при фиксированном бюджете, operator minutes, calls/time, известная цена и coverage, reviewer precision/recall на размеченных дефектах, escaped defects |

Минимальная запись experiment: `experiment_id`, `task_pair_id`, base commit, task kind/risk, frozen `condition_id`, build/policy/contract/prompt/schema hashes, stage→profile/model/effort/runner, grants/limits, requested/effective/reported с source, repeat/order, outcome, operator minutes, defect follow-up. Основная часть контекста уже есть в MeasurementContext; её стоит использовать, сохраняя неизвестные поля. Fallback и escalation — части варианта эксперимента, их расходы остаются в его знаменателе.

Начать можно с 3–5 пар на тип задачи для пилота и поиска крупных проблем. Выбор победителя по одной удачной задаче или флагу n>=3 не обоснован. Для дальнейшего сравнения нужны распределения/интервалы, одинаковая приёмка, учёт остановленных цепочек и скрытый от writer набор проверок. LLM verdict можно использовать как дополнительный сигнал с adjudication, а не как единственную метку качества.

**Рекомендуемый порядок работ**

| Очередь | Результат | Условие завершения |
|---|---|---|
| 0 | Worktree execution lease: F10 | Два процесса не допускают writers в одно дерево; параллельность разных worktrees сохраняется |
| 1 | Deadline backoff и полный protocol capture: F01/F02 | Backoff не выходит за остаток срока; artifact/usage переживают большой stdout. Gates/baseline сохраняют отдельный timeout_minutes — это договорённость, а не исправляемый дефект |
| 2 | Единый учёт попыток и provenance: F03–F06 | Local/global/report согласуются; retry отделён от failover; unknown сохраняется по каждому полю |
| 3 | Условия сравнения/evals и независимость: F07 | Model/effort и paired task учитываются; unknown/несопоставимые условия видны; multi-writer отказ бесплатный |
| 4 | Надёжные настройки: F08/F09, затем UI next-run form | Запись атомарная с digest conflict; таблицы и CLI показывают один состав; preview совпадает с dry-run |
| 5 | Расширение evals и качественных метрик | Запуски имеют заранее определённые критерии; результаты разделяют качество, стоимость и инфраструктурные сбои |

Архитектурно полезно выделить общий deadline, accounting attempts и evaluation condition из больших контроллеров в отдельные проверяемые компоненты. Это уменьшит вероятность уже видимого расхождения между dispatch, gates, local/global reader, report и dashboard. Переписывание всей петли не является предварительным условием этих поставок.

**Проверка и её границы**

| Проверка | Результат |
|---|---|
| Build | `build.cmd`: passed, release 21; host Java 25.0.2 |
| Полный набор Java suites | Запущен и остановлен аудитором через 1083 секунды (18 минут 3 секунды), без итоговой сводки. Это не установленный test failure и не полный green. Последний stack показывает продвижение TaskLoopTest по Git-backed fixtures; зависание не установлено. [Статус](../out/audit-full-suite-status-2026-10-19.json) |
| Focused suites: config-view, dashboard, ledger, ledger-compare, corpus-reader, roster, run-override, orca-settlement | `650 passed, 0 failed`, exit 0; эти suites пока не ловят найденные probe-сценарии |
| Routing suites: quota signal, config, workflow, role-resolver, role runner | `386 passed, 0 failed`, exit 0 |
| Offline metrics probes | 3 сценария воспроизведены, exit 0; [JSON](../out/audit-metrics-2026-10-19/results.json), [исходник](../out/audit-metrics-2026-10-19/AuditMetricsProbe.java) |
| Offline capture probe | Потеря final artifact/usage после 4 MiB воспроизведена; [результаты](../out/audit-loop-2026-10-19/probe-results.json) |
| Offline settings/compare probes | Подтверждены multi-writer preflight gap, смешение model/effort и declared-only by_model; все probes exit 0 |
| Offline concurrency probe | Два TaskLoop + реальные DirectCliExecutor одновременно допустили два writable Java CLI в один checkout; оба `ready_for_human` |
| CLI doctor | Gates/config ready; 21 загруженный профиль, 16 со stamp; проблем конфигурации/dangling references не заявлено. Это не live-проверка моделей или подписок |
| Orca readiness | `available=false`, `runtime_reachable=false`, `runtime_state=not_running`; текущий live lifecycle не проверен |
| Windows goals | `sun.jnu.encoding=Cp1252`, non-ASCII argv unsafe; для русской цели нужен `--goal-file` |
| Roster | Доступны текущие состав/порядок стадий; CLI read-only ответил успешно |
| Compare текущего проекта | 2 stopped units с unknown conditions; `not comparable`. По ним нельзя оценить эффективность моделей или всей системы |
| Global ledger текущего project identity | 12 attempts: 7 с известной ценой суммарно $13.8473, 5 без цены; 3 известных workflow outcomes, 2 human decisions. Это неоднородные исторические измерения, не paired eval dataset; полная стоимость неизвестна |
| Dashboard | Запущен локально, прочитаны API/страница; F09 подтверждён screenshot/AX. Решения и настройки не менялись |
| Live vendor/failover/evals | Не запускались; финансовая эффективность, актуальная quota и качество выбранных моделей этим аудитом не установлены |

Логи текущих проверок: [tests](../out/audit-tests-2026-10-19.log), [focused tests](../out/audit-focused-tests-2026-10-19.log), [routing tests](../out/audit-routing-tests-2026-10-19.log), [doctor](../out/audit-doctor-2026-10-19.json), [roster](../out/audit-roster-2026-10-19.txt), [compare](../out/audit-compare-2026-10-19.txt), [global ledger](../out/audit-global-ledger-2026-10-19.json). Артефакты под `out/` локальные и gitignored.

Дополнительные риски без достаточного подтверждения для дефекта: удержание исторического orchestration question при истечении ожидания и очистка descendants после выхода родителя в ProcessRunner. Native `agent_wait_checked_clear` сам по себе не доказывает reply на threaded question; QuestionProbe показывает ветку, но не устанавливает неправильность её применения в реальном lifecycle. Эти случаи требуют отдельных fake/runtime сценариев и платформенного process-tree теста.
