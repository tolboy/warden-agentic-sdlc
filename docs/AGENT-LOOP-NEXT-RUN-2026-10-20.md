# Форма следующего запуска — 2026-10-20

Продолжение [аудита](AUDIT-AGENT-LOOP-2026-10-19.md),
[F08](AGENT-LOOP-SETTINGS-FIXES-2026-10-20.md) и
[F09](AGENT-LOOP-DASHBOARD-FIXES-2026-10-20.md).
Ветка: `codex/next-run-form`, база: `9c426c8`.

## Реализовано

В существующий Java dashboard добавлена форма «Следующий запуск» на plain HTML/JS.
Для выбранной задачи видны цель, risk, scope, acceptance commands, visual QA,
review assurance, ограничения calls/cost/time/fix и исходный task `use:`.
Каждая роль получает выбор объявленного профиля с vendor/model/effort/runner и
verification, фильтр vendor, custom effort и размещение через объявленный Orca twin.
Пустой выбор наследует task/policy. Механические стадии показаны без controls;
обязательные readers остаются под управлением контракта. Рядом виден порядок policy.

Preview использует `TaskLoop.dispatchPreview`, общий `budgetPreview` и существующие
проверки overlay, writable judge, tooling, независимости и strict cost bounds.
Показаны эффективный состав, причины отказа, минимум вызовов и резервы исправлений.
Применяется тот же порядок task overlay → invocation overlay, что в CLI; выбор
writer наследуют его fix rounds. Новый resolver или отдельный runtime не добавлены.

Форма выдаёт CLI dry-run; при согласованных настройках — команду запуска и полный
объединённый task `use:`. Экспорт `use:` заменяет существующий блок, не добавляется
вторым блоком. YAML keys/values цитируются, CLI передаёт параметры отдельными argv.
Команда для платформы сервера цитирует shell arguments, задаёт project/config home
и восстанавливает окружение оболочки после выполнения. Windows использует PowerShell,
остальные платформы — POSIX shell.

## Согласованность и HTTP

Версия выбора включает canonical paths и SHA-256 policy, всех YAML profiles,
project/task YAML и rotation state, включая отсутствие optional files. Прочитанные
snapshots проверяются повторно; изменение во время availability lookup также
отклоняется. Preview со старой версией получает HTTP 409 / `config_conflict`.
Изменение любого control немедленно очищает команды; поздний ответ предыдущего
preview не может вернуть их после редактирования или переключения задачи.

`GET /api/next-run?task=ID` ограничен задачами данного проекта; произвольные paths
и project settings, ведущие за пределы проекта, отвергаются.
`POST /api/next-run/preview` использует существующие loopback Host/Origin restrictions
и отдельный header с token страницы. Размер запроса ограничен 32 KiB; malformed JSON
и неизвестные поля отвергаются. Содержимое конфигурации выводится через text nodes.
Операции формы не пишут настройки, run artifacts и rotation и не выполняют vendor call.

## Проверки

Сборка `build.cmd`, release 21, host JDK 25. Native Windows: **225 passed, 0 failed**,
пять suites: `next-run`, `dashboard`, `config-view`, `run-override`, `launch-settings`.
Из них 47 проверок новой suite: fingerprint до/после preview, merged YAML и CLI argv,
независимость, недоставляемый effort, механическая/неизвестная стадия, revision
conflicts до и во время lookup, HTTP token/origin/body/schema и сравнение с настоящим
CLI dry-run. GUI и CLI дают одинаковые profile/vendor/model/effort/runner и полный
budget plan, включая deadline note. [Лог Windows](../out/next-run-windows.log).

Внешний Edge через bundled Playwright: **27 проверок**, page errors отсутствуют.
Проверены успешный preview, наследование task overlay и Orca defaults, отказ reader,
stale files, reset и поздний ответ после редактирования. На 1400×1000, 700×900 и
390×844 нет горизонтального overflow страницы; просмотрены снимки формы на всех
трёх размерах. [Результаты](../out/next-run-edge-validation.json),
[1400 px](../out/next-run-1400.png), [700 px](../out/next-run-700.png),
[390 px](../out/next-run-390.png).

В живом браузере Orca: **9 проверок** выбора модели/effort, экспорта, бюджета,
text-only goal, очистки при редактировании и отказа того же vendor.
[Результаты](../out/next-run-orca-validation.json). Orca screenshots не использовались
для visual acceptance; снимки выше получены Edge с того же URL.

Production smoke выполнил именно экспортированную PowerShell-команду как CLI dry-run
через production `Main`: exit 0, состав совпал, исходные cwd и config home восстановлены.
Fixture path содержал апостроф, чтобы проверить shell quoting.
[Результаты](../out/next-run-cli-validation.json), [лог](../out/next-run-cli.log).

Полный Linux registry: **4735 passed, 0 failed**, 55 suites, exit 0.
Использовались JRE 25, Git и init для orphan reaping, сеть на время тестов отключена;
bytecode собран на Windows. Отдельной Linux-компиляции, полного native Windows
registry и Windows CI не было. [Лог Linux](../out/next-run-linux-full.log).

Локальные логи, снимки, [manifest 154 Java sources](../out/next-run-tested-source-sha256.json)
и [машинное резюме](../out/next-run-validation-summary.json) находятся в gitignored
`out/` этого checkout. Все source hashes совпадают с проверенным состоянием.
Тестовый контейнер удалён, созданная вкладка Orca закрыта, fixture dashboard
остановлен. `git diff --check` и repository hygiene (`paths`, `uuids`) проходят.

## Границы

Это preview настроек нового запуска, без continuation/preparation. Проверки состояния
рабочего дерева, template/schema files и live adapters остаются в CLI dry-run и run.
Экспортированная команда читает актуальные файлы и не является разрешением исполнить
замороженный snapshot; после изменения файлов нужно повторить preview/dry-run.
Custom effort не подтверждает поддержку моделью и не получает новый verification stamp.

Проверен браузер Orca; фактический vendor worker dispatch и оплаченные probes не
запускались. POSIX shell execution экспорта отдельно от Java tests не проверялось.
Постоянный Apply defaults с diff и digest F08, task budgets и аналитика реализованы
следующей поставкой: [этапы 1–3](AGENT-LOOP-UI-SETTINGS-ANALYTICS-2026-10-20.md).
