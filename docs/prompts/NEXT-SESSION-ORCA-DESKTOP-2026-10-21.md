# Следующая сессия: автоматическое наблюдение Warden в desktop Orca

Реализуй согласованный ниже сценарий до проверяемого результата. Это задача на
реализацию, а не на ещё один план. Desktop Orca — основной интерфейс; Android
вторичен. Если Orca отсутствует или недоступна, Warden работает обычным CLI.
Самостоятельно выполняй необходимые обратимые изменения и проверки в рамках
этой задачи; не останавливайся на предложении продолжить.

## Контекст и исходная точка

- PR #54 уже слит пользователем в `main` 2026-10-21. Проверенный merge commit —
  `99e1dc7f8a0b2378e31002dfcb65f0dd2fe474ef`. Старую ветку
  `codex/next-run-form` и закрытый PR #54 для новой реализации не продолжай.
- Для desktop-этапа подготовлена отдельная ветка `codex/orca-desktop-visibility`
  от этого `origin/main`. Найди её worktree через `git worktree list`: путь к
  checkout мог сохранить старое имя каталога. Если ветки ещё нет в текущей среде,
  создай отдельную feature branch от актуального `origin/main`.
- До изменений прочитай status/log, обнови remote refs и проверь состояние base/CI.
  Сохрани чужие изменения; не делай reset/rebase поверх неизвестной работы.
  Документационный commit с этим промптом может быть новее merge commit.
- Прочитай [сравнение интерфейсов](../UI-SURFACES-COMPARISON-2026-10-21.md),
  [live validation](../AGENT-LOOP-LIVE-VALIDATION-2026-10-21.md) и
  [описание настроек/аналитики](../AGENT-LOOP-UI-SETTINGS-ANALYTICS-2026-10-20.md).
- Сравнение официальных проектов уже сделано: у Microsoft Conductor есть web UI;
  Paseo поддерживает Windows, Android, AGY и Grok ACP. Явно более подходящей готовой
  замены текущей интеграции не найдено. Не начинай миграцию, новый глобальный
  dashboard или новый оркестратор. Существующие settings/report страницы сохрани
  как вспомогательные инструменты Warden.

CI перед слиянием PR #54 полностью прошёл на `e961638`:
[run 39650511355](https://github.com/tolboy/warden-agentic-sdlc/actions/runs/39650511355).
Linux JDK 21/25 — 4930 passed / 0 failed; Windows JDK 21/25 — по 4936 passed / 0
failed; demo и hygiene также зелёные. Ошибка сравнения Windows short-path и
canonical long-path уже исправлена в `RosterTest` через `policy.toRealPath()`.
Не повторяй закрытое исправление и не считай старый failed run текущим baseline.
Перепроверь актуальный main CI; при новом отказе исследуй причину, сохраняя
containment, atomic writes и проверки backup. Быстрый gate после build:
`java -cp out/test-classes dev.warden.testing.TestMain roster`.
После реализации должен пройти текущий Linux/Windows JDK 21/25 CI.

## Что пользователь хочет видеть

Обычный `warden do` или `warden run` без `--watch` показывает работу в Orca, если
Orca доступна. Нет обязательного ручного открытия дополнительного dashboard.
В одном понятном месте видны текущая стадия, роль, профиль, вендор, model/effort,
номер попытки, длительность, состояние и причина ожидания, использованный бюджет,
результаты проверок и работа CLI по мере выполнения. После прогона старые вкладки
Warden не накапливаются; отчёт и история доступны для повторного открытия.
Пользовательские вкладки сохраняются.

## Поведение по умолчанию и fallback

1. Orca не установлена: существующие terminal progress, JSON, logs и exit codes
   работают как раньше; отсутствие UI не является ошибкой задачи.
2. Orca установлена и runtime готов: автоматически подключи UI к фактическому
   проекту/worktree выполнения, а не к каталогу, из которого был вызван `warden do`.
3. Orca установлена, но закрыта: для интерактивного desktop-вызова разрешена одна
   ограниченная по времени попытка запуска через поддерживаемую команду Orca.
   При отказе/неготовности показывай понятную причину fallback в обычный CLI.
   Не зацикливай запуск, не зависай на долгом обнаружении, не блокируй paid work.
4. CI/headless не должны насильно открывать desktop. Сохрани явный отказ от UI
   и совместимость старого `--watch`. `--quiet`, stdout JSON и piping нельзя ломать
   дополнительными строками состояния.
5. Если исходный checkout ещё не зарегистрирован в Orca, исследуй поддерживаемый
   способ показать существующую папку. Не создавай ради UI дополнительный Git
   worktree, не меняй branch/HEAD. Если путь не поддержан — ясный fallback и
   конкретное ограничение; не выдавай молчаливый no-op за подключённый UI.
6. Наличие UI и выбранный execution runner — разные решения. Потеря Orca UI не
   разрешает повторять уже запущенную работу или менять runner существующего worker.

## Проверить актуальный контракт Orca

Используй `orca-cli` skill, для supervised lifecycle — `orchestration`; их полные
version-matched guides получает выбранный CLI. Computer Use нужен только для тех
элементов native app UI, которые нельзя проверить CLI/API. Не меняй незаметно
production CLI на dev build.

У Orca открытые исходники: https://github.com/stablyai/orca. При подготовке был
установлен CLI `1.4.233`, tag commit `c60a9b0a2a8d1aff2da94c2185a6dba238e65755`.
Определи текущие version, runtime capabilities, release/tag и сопоставь с исходниками.
Не считай README main доказательством поддержки конкретного флага установленным CLI.
Не обновляй приложение и не форкай Orca автоматически ради удобного API.

Исследуй доступные native session/run/task/terminal surfaces и возможность показать
external/direct activity. Выбери минимальный поддерживаемый вариант без повторного
запуска агента. Не придумывай команды и не имитируй supervised worker/worker_done
для процесса, который Orca не запускала. Если нужен именно отсутствующий контракт
Orca, покажи точный blocker и минимальное предложение upstream изменения.

## Точки входа Warden и известные причины

- `src/main/java/dev/warden/Main.java`: watch сейчас передаётся лишь при флаге.
- `src/main/java/dev/warden/run/DoCommand.java`, `run/TaskLoop.java`, `run/Preparation.java`:
  фактическое дерево, reservation, подготовка, продолжения, watch и stage events.
- `src/main/java/dev/warden/run/Workspace.java`: интерфейс наблюдения.
- `src/main/java/dev/warden/execution/orca/OrcaWorkspace.java`: `stage/ensureRun`
  зависят от watch terminal; без него прямые стадии не публикуются. `show` создаёт
  дополнительные терминалы, а final state в основном переименовывает watch tab.
- `execution/orca/OrcaLifecycle.java`, `OrcaExecutor.java`, `OrcaRecovery.java`,
  `OrcaDecisionGate.java`: durable bindings, release и решения. Сохрани их гарантии.
- `role/RoleRunner.java`, `execution/DirectCliExecutor.java`, `process/ProcessRunner.java`:
  direct attempt events, raw spool, streaming parser, process settlement и quota handling.

UI — проекция Warden evidence. Budgets, transitions, retry/failover и окончательные
решения остаются в Warden. Не добавляй второй scheduler. Ошибка UI не должна
обрывать оплачиваемую работу; при этом отсутствие подключения/cleanup должно быть
видно оператору и записано, а не скрыто blanket catch без диагностики.

## Стабильное отображение

- Одна overview-вкладка на активную цепочку; retry/advance/replan обновляют её и
  сохраняют связь с предыдущими попытками. Не открывай новый обзор на heartbeat.
- Нативную вкладку/чат Orca worker используй повторно для его текущего вызова.
  Отдельный служебный терминал на каждый контракт/результат не нужен; показывай
  документы по запросу или через подходящее существующее представление.
- Direct Grok/Codex/AGY/DeepSeek должны быть видны: живой поток опубликованных
  сообщений, tool calls/commands, коротких результатов, ошибок и статусов. Сводки
  только после завершения недостаточно. Нет tool events у CLI — честно покажи
  доступный текст/прогресс, без вымышленных сообщений и скрытых рассуждений.
- Сначала исследуй native API. Если нужен observer поверх уже захваченного stdout,
  используй incremental bounded обработку и существующий spool/parser. Не запускай
  второго агента для красивой вкладки, не переключай direct профиль на Orca ради UI,
  не ломай model/effort flags, stdin delivery, schema parsing или process-tree fencing.
- Ограничь retained memory, частоту UI updates и размер отображаемых результатов;
  не перечитывай целый растущий лог на каждом heartbeat. Секреты, auth output,
  ключи и содержимое environment не должны попадать в представление.
- Сохрани unknown prices/tokens и requested/launch/reported model distinction.
  UI events не являются дополнительными платными vendor calls в ledger.
- Закрытие overview оператором не останавливает loop и не создаёт её заново
  непрерывно. Разрыв UI-соединения не создаёт дублирующую попытку исполнения.

## Владение и уборка

Веди атомарный durable journal только ресурсов, созданных данным Warden run/chain:
тип поверхности, точный handle/ID, host/runtime identity где требуется, назначение,
owner, lifecycle state и результат cleanup. Титул вкладки не доказывает владение.
Учитывай user takeover, transferred/external ownership и повторное использование
терминала. Даже созданная Warden вкладка после перехода пользователю не закрывается.

Правила завершения:

- После accepted settlement освобождай supervised worker через `worker-release`.
  Не заменяй `release_pending`/`release_unknown` на `terminal close` и не выдавай их
  за завершённую очистку. Следуй durable recovery action текущего runtime.
- Собственные observer/helper tabs закрывай адресно по доказанному текущему
  владению. Не используй `--all`, массовое закрытие worktree или name-prefix match.
- Пока ожидается человек, завершённые agent tabs убраны; остаётся один обзор с
  вопросом/ссылкой на gate. Ожидание человека не равно окончательному завершению.
- После окончательного accept/reject без продолжения/abort убери принадлежащие
  цепочке рабочие и служебные вкладки. История, итоговый статус, reports и raw logs
  сохраняются; их можно открыть снова. Не оставляй фиктивное состояние WORKING.
- При crash/Ctrl+C/timeout cleanup не останавливает неизвестного живого writer.
  Сохрани journal, fence и recovery path. После подтверждённого завершения cleanup
  можно безопасно повторить. UI cleanup и отмена работы — разные операции.
- При новом запуске можно reconcile доказанно завершённые старые ресурсы Warden,
  но нельзя закрывать ещё живую/неопределённую соседнюю цепочку или пользовательские вкладки.

## Настоящие вендоры и тестовый проект

Сохрани выбранные пользователем профили: Grok `grok-4.7` high — основной writer;
Codex `gpt-6.1-sol` medium — review/reserve; AGY `gemini-3.8-flash-high` high —
дополнительные проверки; DeepSeek `deepseek/deepseek-v4.1-flash-1017` medium через
именованный Codex/OpenRouter profile — planning/second review. Claude не использовать.
Перепроверь актуальную доступность без молчаливой подмены ID. Не переписывай личные
конфиги и не извлекай ключ из безопасного auth helper в task/argv/log.

Пользователь разрешил небольшие live tests в Orca `hello-world-test`. Используй
отдельное disposable дерево, не меняй существующее приложение пользователя.
Ранее были `warden-loop-live-20261021` и `warden-recovery-live-20261021`; не считай их
чистыми fixtures и не удаляй имеющиеся evidence. Платные live calls нужны для
проверки отображения, а не для повторения всех предыдущих paired evals.

Известные ограничения прошлого прогона: Orca Grok launch-time model pin был
отвергнут, поэтому использован direct; один Codex probe потребовал Enter-only
recovery после input_accepted. Свежий probe и полная цепочка затем прошли сами.
Перепроверь эти границы, не обещай unattended стабильность по одному успешному run.

## Проверки и критерий готовности

Сначала meaningful focused tests на auto-detection, managed/unmanaged paths,
fallback, bounded UI failures, durable ownership/takeover, idempotent cleanup,
continuations, crash/reconnect и отсутствие повторного dispatch. Используй
зарегистрированные suite names из `dev.warden.testing.TestMain`, а не display labels.

Desktop acceptance с реальным Orca:

1. Два последовательных коротких Warden прогона без `--watch`: стадия, vendor,
   model/effort и хотя бы один реальный CLI tool/output event видны до окончания.
2. Между прогонами старые завершённые Warden tabs не накапливаются. Заранее
   созданная оператором вкладка остаётся; отдельно проверен user takeover.
3. Контролируемый typed vendor failure вызывает одно разрешённое переключение,
   обе попытки видны, первый процесс завершён, цена не выдумана. Injection явно
   помечен synthetic и не называется реальным исчерпанием оплаченной квоты.
4. Human gate виден, ответ импортирован один раз, после ответа цикл продолжен
   один раз; решённый gate не продолжает спрашивать пользователя.
5. Потеря Orca/UI во время direct attempt не запускает второго writer. Hard kill
   controller сохраняет receipts; recovery показывает исходный worker, не дубликат.
6. При отсутствии Orca обычный CLI полностью проходит тот же сценарий. Это можно
   проверить изолированным fake runtime/PATH, не удаляя Orca и не ломая систему пользователя.

Сохрани настоящие receipts, before/after terminal inventories и screenshots desktop
представления; API/мок-тест сам по себе не доказывает, что интерфейс понятен и виден.
Android не блокирует desktop closeout; не записывай его как проверенный без устройства.

Затем полный build/tests, repository hygiene и Linux/Windows JDK 21/25 CI. Не складывай
counts разных suites как уникальные тесты. Не называй pending/failed CI зелёным.
Не добавляй ссылки в docs на gitignored artifacts; публикуй sanitized summaries.

## Результат

Доведи desktop-этап до reviewable commits и отдельного PR в `main`. Если для этого
этапа уже открыт PR, обнови его без дубликата. Закрытый PR #54 не переоткрывай.
Conventional subjects, подробная мотивация,
совместимость и проверка в body; `Co-Authored-By` с точной активной моделью Codex.
Без merge/публикации продукта и без массовой уборки чужих ресурсов.

Финально дай: что видит пользователь при обычном запуске, как работает CLI fallback,
что именно закрывается и остаётся, результаты desktop acceptance/CI и реальные
ограничения. Отдельно перечисли непроверенное, включая Android. Не заканчивай
только планом или предложением продолжить уже разрешённую работу.
