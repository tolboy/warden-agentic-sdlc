# Исправление таблиц dashboard — 2026-10-20

Продолжение [аудита](AUDIT-AGENT-LOOP-2026-10-19.md) и
[безопасного сохранения настроек](AGENT-LOOP-SETTINGS-FIXES-2026-10-20.md).
Ветка: `codex/agent-loop-dashboard`, база: `7552da6`.
Этап закрывает F09 и дополняет preview следующего запуска.

## Поведение

Таблица состава стадий теперь содержит пять заголовков для пяти ячеек кандидата:
профиль и vendor, model/effort/runner, verification, допуск, стоимость.
Название стадии остаётся в отдельной строке, охватывающей все пять колонок.
Отказ независимости и неизвестная стоимость Orca отображаются под своими
заголовками. Vendor виден рядом с профилем.

В столбце задач «Запустится» каждая стадия занимает отдельную строку и показывает
профиль, vendor, model, effort и runner из существующего config preview.
Сохраняются причины отсутствия исполнителя, пропуски стадий, overlay и отказ
override. Неуказанные значения обозначены явно; для Orca без model показывается
модель агента по умолчанию. Это запрошенные параметры, а не подтверждение
фактической модели inference.

Переносы ограничены ячейкой preview; широкие таблицы прокручиваются внутри своих
блоков. Схема API и разрешения dashboard не менялись.

## Проверки

Свежая сборка: `build.cmd`, `javac --release 21`, host JDK 25.
Native Windows: **388 passed, 0 failed** в шести suites: `dashboard`, `config-view`,
`launch-settings`, `roster`, `profile verifier`, `config-file-store`.
[Лог](../out/dashboard-windows-verified.log).

Production `Main dashboard` запущен на loopback с изолированными fixture home и
проектом. Fixture включает независимого reviewer, отказ того же vendor, Orca с
неуказанной моделью, task overlay и отсутствующий профиль; vendor CLI не запускались.
После проверки закрыта созданная тестовая вкладка и остановлен fixture dashboard.

В живом встроенном браузере Orca выполнены **45 DOM-проверок**: по 15 при viewport
1400×1000, 700×900 и 390×844. Проверены число и смысл колонок, их выравнивание,
содержимое preview, причины отказа, неизвестные значения, переносы и локальный
horizontal overflow. Скриншоты Orca содержали повторяющиеся фрагменты и не
использовались как визуальное доказательство.

Тот же URL дополнительно проверен внешним headless Edge через bundled Playwright:
**45 DOM-проверок**, те же три viewport, JavaScript page errors отсутствуют.
Просмотрены корректные снимки широкой таблицы, обоих краёв таблицы при 700 px и
preview задач:

- [Состав стадий, 1400 px](../out/dashboard-f09-1400-left.png).
- [Левый край, 700 px](../out/dashboard-f09-700-left.png).
- [Правый край, 700 px](../out/dashboard-f09-700-right.png).
- [Preview задач](../out/dashboard-f09-preview.png).

[Результаты Edge](../out/dashboard-f09-edge-validation.json) и
[машинное резюме](../out/dashboard-validation-summary.json) сохранены в gitignored
`out/` этого checkout вместе с логами и снимками. Все 152 Java-исходника зафиксированы
в [manifest SHA-256](../out/dashboard-tested-source-sha256.json); относительно
проверенного F08 изменён только `Dashboard.java`.

Полный Linux registry проходил на базе `7552da6`: **4688 passed, 0 failed**.
После изменения отображения F09 он не повторялся; это результат предыдущего этапа,
а не полная проверка нового commit. `git diff --check` и repository hygiene
(`paths`, `uuids`) проходят.

## Границы и продолжение

Проверена работа dashboard в браузере Orca; dispatch, settlement и фактический
запуск vendor через Orca этим не проверены. Платных probes не было. Dashboard
по-прежнему показывает настройки и решения, без новой формы редактирования.

Следующий этап UI — форма следующего запуска с выбором профилей по ролям,
model/effort/runner и проверкой preview до запуска. Постоянный Apply должен
показывать diff и передавать digest прочитанного файла в versioned writer F08.
