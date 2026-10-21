# Dashboard: Apply, бюджеты и аналитика

Итоговая live-проверка 2026-10-21: [четыре вендора, paired evals и Orca recovery](AGENT-LOOP-LIVE-VALIDATION-2026-10-21.md).

Пути `out/` ниже обозначают локальные gitignored-артефакты проверки; в клоне репозитория их нет.

Дата: 2026-10-20. Продолжение `c1711d7`, ветка `codex/next-run-form`.
Закрывает этапы 1–3 оставшейся очереди после исправления F01–F10.

## Сохранение настроек

`SettingsEditor` даёт два отдельных Apply: файл выбранной задачи и личный
`policy.yaml`. Запрос содержит типизированные изменения, revision входов и hash
просмотренного кандидата; пути, команды и произвольный YAML принять нельзя.
Preview не пишет файлы и не запускает вендора.

Revision включает project/task YAML, policy, inventory и содержимое профилей,
canonical paths и rotation. Apply заново готовит кандидата, проверяет его hash,
разбор и resolver preview. `ConfigFileStore` проверяет контекст под lock целевого
файла перед backup и заменой, затем атомарно заменяет файл. Stale/busy отказ — HTTP
409. Token панели и проверки Host/Origin защищают оба POST; размер запроса — 32 KiB.

Это одна атомарная запись файла. Раздельные Apply не образуют multi-file transaction.
Некооперирующий редактор всё ещё может попасть между optimistic check и rename.
Неизменённые YAML-разделы остаются дословно, изменённые получают flow mapping;
их комментарии сохраняются перед разделом и видны в diff. Backup содержит точную
предыдущую версию. Profile files и verification stamps не меняются.

Личная политика редактирует порядок fallback, first/rotate, vendor failure для роли,
общие quota/vendor failure режимы, число переключений, rate-limit retries/backoff и
repair reserve. Независимость и обязательные readers этим интерфейсом не снимаются.
Проверка относится к выбранной задаче; остальные проекты с этим home требуют
собственного preflight. Сохранённый task use: продолжает иметь приоритет.

UI очищает diff/экспорт при редактировании, игнорирует поздние ответы preview,
блокирует повторную проверку во время Apply и ждёт перечитывания формы.

## Бюджеты

Редактируются calls, USD и threshold/strict, execution minutes цепочки, fix rounds,
timeout вызова и срок вопроса человеку. Пределы совпадают с `TaskSpec`/`Policy`;
числовые строки и дробные целые лимиты отвергаются сервером. Пустые optional поля
снимают соответствующее значение задачи. Ноль USD отключает денежный лимит.

Task use: и бюджеты сохраняются вместе. Общий `TaskLoop.settingsPreview` проверяет
состав, минимум вызовов и repair reserve. Для strict cap также отвергается заведомо
невозможный ceiling: ниже суммы даже минимальных объявленных границ обязательных
вызовов. Это необходимое условие; actual dispatch отдельно резервирует выбранные
и оставшиеся worst-case bounds, поэтому будущий retry/failover ещё может не вместиться.
Исправлено и CLI narration: strict cap больше не называется threshold.

Изменённые бюджеты или удаление сохранённого pin требуют Apply перед экспортом CLI,
поскольку существующие invocation flags этого не выражают. Одноразовый выбор
profile/effort по-прежнему можно экспортировать без сохранения, когда он воспроизводим.
Команда читает актуальные файлы; preview/dry-run нужно повторять после их изменения.

## Аналитика

Новый read-only `/api/analytics` читает локальные task summaries, decisions и JSONL
evidence. Task/run filters и ручное обновление отделены от двухсекундного live state.

Повторно используется reconciliation из `LedgerReader`: journal attempts и вложенные
role summaries не становятся двумя платными вызовами. Попытки с явным
`budget_charged: false` считаются отдельно: они не увеличивают vendor calls,
ошибки вендора или missing price coverage. Флаг сохраняется и в home corpus;
старый corpus без этого флага не позволяет восстановить факт возврата бюджета.
UI показывает:

- Vendor success, workflow ready, review passed и human accepted отдельно.
- Известную стоимость с known/unknown coverage, tokens и call/human-wait p50/p95.
- Failover, ошибки вендора, наблюдаемые same-profile rate retries и запрошенный backoff.
- Vendor/model/effort/runner с отдельными requested, launch и reported параметрами.
- Finding ID transitions, blocker counts и хронологию стадий.
- Per-run измерения и накопительные chain counters отдельно, без сложения continuation
  totals. Вызов, успевший попасть в journal до task summary, тоже остаётся видимым.

Чтение ограничено runs directory, 2 000 directories, 20 000 events, 8 MiB на файл и
64 MiB всего. Symlinks наружу не читаются. Malformed/oversized evidence, пропущенные
входы и отсутствующие измерения оплаченных вызовов помечают данные incomplete.
Панель показывает известную часть расходов, а не приписывает ей полный охват.
Raw output, commands и private decision notes не публикуются; значения выводятся как текст.

## Проверки

- Build: JDK 25, `javac --release 21`, без новых зависимостей.
- Windows: **415 passed, 0 failed**, девять suites: settings-editor, run-analytics,
  next-run, dashboard, config-file-store, measurement-accounting, config-view,
  run-override, launch-settings.
- Полный Linux registry: **4836 passed, 0 failed**, 57 suites, JRE 25,
  init и отключённая сеть; bytecode собран на Windows.
- Edge: **38 сценариев**, без JavaScript errors, ширины 1400/700/390; сохранение и
  reload обоих файлов, stale diff, отсутствие ложного CLI export, late response,
  independence/strict cap refusals, неизвестная цена, incomplete evidence и filters.
  Все три раздела сняты на каждой ширине, визуально проверены.
- Экспорт PowerShell исполнен настоящим CLI dry-run: выбранные параметры и сохранённые
  budgets совпадают, cwd/config home восстанавливаются, dispatch отсутствует.

Итог полного Linux-прогона и машинное резюме находятся в
validation summary (`out/settings-analytics-validation-summary.json`).
Логи и screenshots сохранены в gitignored `out/settings-analytics-*`.

## Практические границы

Аналитика описательная: качество моделей, statistical significance, escaped defects,
фактическая длительность backoff и неизмеренные infrastructure waits не выводятся из
косвенных чисел. Closed blocking transitions не равны доказанному исправлению каждого
дефекта. Custom effort не получает подтверждения поддержки моделью.

Живые vendor tasks, оплаченные probes, Orca worker lifecycle и Windows CI в этой поставке
не запускались. Следующие два этапа — практические paired evals и интеграционный цикл
Orca/recovery/CI. Новый UI проверен в Edge; отдельная повторная проверка Orca webview
остаётся в интеграционном этапе.
