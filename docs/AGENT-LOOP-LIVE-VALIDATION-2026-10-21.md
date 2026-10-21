# Проверка четырёх вендоров и Orca на Windows

Дата: 2026-10-21. Продолжение [настроек и аналитики](AGENT-LOOP-UI-SETTINGS-ANALYTICS-2026-10-20.md).
Исправления probe: `94f0c34`. Публичная проверка ветки: [PR #54](https://github.com/tolboy/warden-agentic-sdlc/pull/54).
Машиночитаемый результат: [sanitized evidence](evidence/agent-loop-live-2026-10-21.json).
Полные локальные журналы находятся в gitignored `out/` и `.warden/runs/` тестовых деревьев;
они не входят в клон. Ключи, личные настройки, account balance и сырые vendor prompts не публикуются.

Оставшиеся этапы практических evals и интеграционной проверки выполнены на двух
отдельных Orca worktrees проекта `hello-world-test`. Исходное приложение пользователя
не изменялось. Это проверка небольших disposable fixtures, а не оценка качества
моделей на производственных задачах.

## Рабочие настройки

| Роль | Профиль | Запрошенная модель | Effort / канал |
| --- | --- | --- | --- |
| Основной исполнитель | grok-implement | grok-4.7 | high / direct |
| Запасной исполнитель | codex-implement | gpt-6.1-sol | medium / direct |
| Первое ревью | codex-review | gpt-6.1-sol | medium / direct |
| Второе ревью, ротация | deepseek-review | deepseek/deepseek-v4.1-flash-1017 | medium / Codex + OpenRouter |
| Второе ревью, ротация | agy-review | gemini-3.8-flash-high | high / AGY |
| Планирование | deepseek-planner, затем codex-planner | DeepSeek, затем GPT-6.1 Sol | medium |
| Независимое ревью плана | codex-plan-review | gpt-6.1-sol | medium |
| Визуальная проверка | orca-codex-visual | gpt-6.1-sol | medium / Orca |

Все выбранные профили прошли реальные проверки чтения файла/изображения и запуска
2026-10-21; writable Codex отдельно создал контрольный файл. Неиспользуемые профили
Claude архивированы вне активного inventory, политика их не выбирает. Резервирование
по квоте и ошибке вендора включено с максимумом двух замен на стадию.

В личный `~/.codex/config.toml` добавлен provider OpenRouter с Responses endpoint и
command-backed authentication: ключ читается из указанного владельцем файла, без
копирования в TOML, аргументы процесса или репозиторий. Именованный профиль
`~/.codex/deepseek.config.toml` запускается командой `codex --profile deepseek`.
Основная модель Codex сохранена. Для worker-профилей отключены ненужные optional
интеграции; OpenAI-профили явно выбирают `model_provider = "openai"`.

ID [deepseek/deepseek-v4.1-flash-1017](https://openrouter.ai/deepseek/deepseek-v4.1-flash-1017)
проверен по актуальному каталогу и настоящими Responses-запросами. Это закреплённый
октябрьский snapshot; более короткий alias указывал на другую версию. Источники:
[каталог](https://openrouter.ai/api/v1/models),
[Responses](https://openrouter.ai/docs/api_reference/responses/overview),
[профили и authentication](https://learn.chatgpt.com/docs/config-file/config-advanced).
Модель, запрошенная профилем, отделена от сообщённой CLI: Grok сообщил
`grok-4.7-build`. Orca launch receipt подтверждает настройки запуска, но не является
независимой проверкой backend identity провайдера.

## Три парных повторения

Воспроизводимый [fixture и driver](../examples/evals/four-vendor/README.md) сравнивают
второго reader: AGY против DeepSeek после одинаковых Grok writer и Codex reader.
Фиксированы base commit `191a06780e62caad0d69b90ddf1933cae6c86d5c`, task, policy,
budgets, profile contents и сборка Warden. Порядок: A/B, B/A, A/B.

| Повтор | AGY: секунд / вызовов / outcome | DeepSeek: секунд / вызовов / outcome |
| --- | --- | --- |
| 1 | 269 / 3 / fixture accepted | 251 / 3 / fixture accepted |
| 2 | 292 / 3 / fixture accepted | 222 / 3 / fixture accepted |
| 3 | 217 / 3 / fixture accepted | 246 / 3 / fixture accepted |

Все шесть запусков прошли неизменённые Node assertions и оба независимых vendor
reviews без repair rounds. Это **18 реальных вызовов и 6 автоматических fixture
acceptance**, **0 оценок конечного пользователя**. Actor — test operator; его
решение ничего не коммитит и не публикует. Operator minutes неизвестны.

`paired_evaluation`: 3 complete pairs, issues пуст, incomplete false. Разница
средних elapsed — около 19.7 секунды в пользу DeepSeek на этом fixture; calls и
acceptance одинаковы. Это описательные числа трёх повторений. Статистическая
значимость, quality ranking и escaped-defect rate не установлены.

Total cost остаётся unknown: часть CLI не сообщает цену. Неизвестные поля не
заменены нулями; account usage OpenRouter проверяется отдельно и не подставляется
в per-run cost. Цена Grok сохранена как reported, не как итог всех вендоров.

Сохранены и исключены два setup attempts: отказ preflight до первого вызова и
запуск, где параллельный image probe изменил fingerprint во время AGY review.
Второй случай не доказывает запись со стороны AGY. Он выявил отсутствие общего
lease у probe; оно исправлено, повторения затем выполнены без конкурирующих probes.

## Планирование и сквозная цепочка

DeepSeek составил план небольшой функции retry delay. Независимое ревью Codex
выявило P1: project check set был пуст, поэтому compiled acceptance ничего не
проверял. Первый draft остановлен и явно abort-нут. В disposable project добавлены
конкретные Node commands, затем новый DeepSeek draft и Codex plan review прошли.
Это два дополнительных реальных вызова в успешном draft; отрицательный результат
сохранён отдельно, не записан как успешная подготовка.

Отдельная полная цепочка `live-four-vendor-chain-r2`: синтетическая typed quota
error → Grok writer → Node gates → Codex reader через Orca → DeepSeek reader → AGY
reader. Все стадии прошли, три review verdict — pass. Дополнительно выполнены
**92 независимые Node assertions**. Orca gate разрешён как `accept`, затем
`warden approve --from-orca` сохранил решение с actor тестового оператора;
`lands: false`. Этот конкретный запуск Orca не потребовал ручного Enter.

Всего учтено 5 attempt calls: 4 настоящих vendor calls и 1 явно синтетический
отказ без provider request. Первую попытку нельзя интерпретировать как расход
подписки или доказательство реального исчерпания квоты. Fixture оставил дочерний
процесс с heartbeat; после cleanup он отсутствует, heartbeat больше не меняется,
затем выполнена запасная попытка Grok. Journal содержит один failover с
`cause: role_quota_exhausted`, `authorized_by: policy_auto`. Orca worker освобождён,
delivery ACK принят, coordinator закрыт; read-only fingerprints всех readers
сохранены. Полная цена unknown, поскольку не все участники сообщают стоимость.

До этого был отдельный zero-call отказ preflight из-за неверного YAML stage pin;
он явно abort-нут и не включён в успешный запуск. Повтор использовал
документированные CLI `--use <stage>=<profile>` вместо неподдерживаемого YAML.

## Аварийное восстановление и probe

Проверен настоящий Windows hard kill только собственного Java controller после
сохранения Dispatch receipt. Уже запущенный Orca worker продолжил работу; повторный
probe был отвергнут с `worktree_recovery_required`. Recovery без подтверждения
остановки не снял защиту. Получен `worker_done` исходного Dispatch, затем worker
освобождён, delivery подтверждён и coordinator закрыт. После явного recovery новый
image probe прошёл. Это проверка аварии controller, не буквального нажатия Ctrl+C.
Shutdown hook и различение vendor descendants / build daemons покрыты native tests.

Probe теперь использует общий checkout lease, сохраняет атомарный recovery journal,
Task/Dispatch/start request и receipt, показывает typed JSON errors. Потеря wait или
неопределённая очистка сохраняет контекст и препятствует verification stamp.
Освобождение допустимо после принятого settlement. Timeout не останавливает живого
агента; повторная отправка текста не применяется как восстановление.

## Dashboard и автоматические проверки

Настоящий Dashboard открыт в Orca browser: четыре вендора, model/effort и preview
команды отображены; console errors отсутствуют, на проверенной ширине около 737 px
горизонтального переполнения нет. При изменении rotation во время preview получен
ожидаемый `config_conflict`; после обновления состояния preview прошёл. Apply в
живом эксперименте не нажимался; атомарные изменения и конфликты проверены tests.

Native Windows full registry: **4928 passed, 0 failed** на замороженной сборке до
последней небольшой доработки probe. На текущем production source целевые Orca,
profile verifier и recovery checks: **247 passed, 0 failed**. Это отдельные прогоны,
их counts не складываются. Linux CI для `94f0c34`: **4930 passed, 0 failed**.
Актуальная Linux/Windows JDK 21/25 matrix, demo и hygiene доступны в
[проверках PR](https://github.com/tolboy/warden-agentic-sdlc/pull/54/checks).

Первый hygiene job выявил ссылки из прошлых отчётов на gitignored `out/`. Они
заменены явными локальными путями: ссылки в чистом клоне больше не обещают
несуществующих artifacts. Сам gate не ослаблен; raw logs не добавлены в Git.

## Проверенные границы Orca 1.4.233

- Grok adapter отказывает launch-time `--model`; pinned Grok запускается direct.
  Отказ сохранён, модель не подменяется запуском без флага.
- В одном nonce probe Orca сообщил `input_accepted`, но prompt остался в строке
  ввода Codex. Warden сохранил worker и recovery fence. После просмотра именно
  этого owned prompt оператор отправил только Enter; исходный Dispatch завершился.
  Новый nonce probe прошёл автоматически. Это наблюдаемое ограничение unattended
  submission в Orca, требующее исправления на его стороне.
- Accepted settlement, release и recovery подтверждены реальными receipts. Live
  quota exhaustion у оплаченных провайдеров, неизвестные цены и работа на больших
  производственных задачах этой проверкой не подтверждаются.
