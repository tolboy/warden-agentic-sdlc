# Приоритетные исправления agent loop

Пути `out/` ниже обозначают локальные gitignored-артефакты проверки; в клоне репозитория их нет.

База: `106f2a3` (`origin/main` после fetch 2026-10-20). Рабочая ветка:
`codex/agent-loop-priority`. Учтено [независимое ревью Grok Bot](AUDIT-AGENT-LOOP-2026-10-19-REVIEW.md).
Старый `7c7f408` не переносился: его заменяет upstream PR #53. Исходная ветка и stash
с рабочими изменениями сохранены; история старой ветки не переписывалась.

## Результат

| Область | Изменение |
|---|---|
| F10: один контроллер на checkout | OS lock удерживается через workflow, подготовку, отдельную роль и machine gates. Разные run ID и подкаталоги проекта используют общий lock; linked worktrees независимы |
| Восстановление | До vendor spawn сохраняется launch intent, затем PID и время старта родителя/наблюдаемых потомков. `warden recover` проверяет квитанции под тем же OS lock. Неполные квитанции требуют `--confirm-stopped` после проверки оператором; известный живой процесс нельзя обойти подтверждением |
| Завершение | Shutdown hook останавливает известные vendor-процессы. После жёсткого уничтожения JVM остаётся durable fence. Процессы обычных gates/build daemons не попадают в vendor census |
| Failover | Vendor adapter завершает известных потомков до возврата. Неподтверждённое завершение/capture получает отдельный код и блокирует retry/failover, сохраняя попытку и расходы. Это также защищает добавленный upstream vendor-failure failover |
| F02: большой вывод | Raw stdout/stderr сохраняются побайтово; head/tail preview ограничен 256 КиБ. Streaming parser читает весь spool и удерживает итоговый artifact, usage и диагностические quota/403/turn-limit события |
| Ограничение диска | Максимум 64 МиБ на поток одного direct-вызова. Превышение останавливает процесс с `role_output_limit_exceeded`; неполный transcript не становится успешным artifact и не запускает автоматический failover |
| Границы protocol | Один JSON-объект ограничен 4 Ми символов; пропущенные большие объекты явно посчитаны. Следующие корректные события всё равно читаются |
| Классификация | Вложенный pretty-printed пример ошибки канонизируется как внешний JSON-объект и не становится отдельным quota event. Typed tool events и произвольные JSON-поля стоимости не заменяют provider receipt; JSON, процитированный внутри обычной прозы, не становится quota/telemetry event |
| F01: backoff | До ожидания проверяются pause и минимальное время следующего вызова. При отказе уже завершённая попытка, её цена и budget settlement остаются в evidence; дополнительный dispatch не резервируется |
| Действие оператора | `worktree_busy` предлагает ожидание, recovery fence — `warden recover`, проблемы raw capture — просмотр отчёта |

Gates/baseline по-прежнему используют собственный `timeout_minutes`. Изменение этого
договора не входит в исправление deadline backoff. Короткие команды Git не требуют
перечисления всех процессов системы: census выполняется для vendor CLI и при остановке
обычной команды по timeout.

Orca worker может оставаться активным во время ожидания человека. Workflow с Orca route
доходит до Orca adapter для его штатной сверки identity/launch contract и census;
local/direct роли и machine gates проверяют retained lifecycle непосредственно перед
выполнением. Команда `recover` не подменяет Orca reconciliation.

## Проверка

Проверяются реальные локальные дочерние JVM и pipes, без платных вызовов. Полный
Linux registry выполнен в отдельном контейнере: JRE 25, Git, сеть отключена, hostname
сопоставлен loopback. Bytecode собран на Windows командой `build.cmd` с `--release 21`;
отдельной Linux-компиляции не было. Progress wrapper в `out/` использует тот же registry
из TestMain и выводит начало/конец каждой suite.

| Проверка | Результат |
|---|---|
| Сборка production/tests | `built (release 21)` |
| Полный Linux registry, 51 suite | **4517 passed, 0 failed**, exit 0; лог (`out/priority-linux-verified.log`) |
| Windows: lease, capture, роли, next-step | **404 passed, 0 failed**, exit 0; лог (`out/priority-windows-release-focused.log`) |
| Windows: последний capture/parser, quota и 403 | **172 passed, 0 failed**, exit 0; включает дополнительный inline-prose regression |
| Windows: replay chain budget и resume | **49 + 26 passed, 0 failed**; лог (`out/priority-taskloop-probe.log`) |
| Windows: raw после прежнего cap и настоящий timeout | **16 passed, 0 failed**, exit 0; лог (`out/priority-windows-taskloop-replay.log`) |
| CLI recover | Успешное восстановление fixture и повторный идемпотентный вызов |
| Исходники после тестового snapshot | SHA-256 совпадают; manifest (`out/priority-tested-source-sha256.json`) |

Числа целевых Windows-прогонов пересекаются: их нельзя суммировать как независимые тесты.
Ранний полный Windows-прогон завершился за 2636 секунд с пятью падениями: цена terminal
error и четыре проверки прежнего усечения raw. Они исправлены и перепроверены целевыми
сценариями. Полный Windows registry на окончательном снимке и Windows CI не повторялись.

Проверены коллизии workflow/role и linked worktrees, subdirectory admission, durable PID
receipts, жёсткое уничтожение controller, JVM shutdown hook, recovery, gate daemon,
vendor descendants, вывод больше 4 МиБ, byte hashes, malformed UTF-8, вложенные и inline
примеры quota, подмена telemetry, raw ceiling и deadline backoff. Отказ по process settlement
нормализуется до записи attempt: итог роли и journal не расходятся в коде ошибки.

Повтор на локальной машине: `build.cmd`, затем
`java -cp out/test-classes dev.warden.testing.TestMain`. Машинное резюме этого прогона:
priority-validation-summary.json (`out/priority-validation-summary.json`).

Не проверены live vendor CLI, фактическая стоимость/лимиты подписок, live Orca,
реальная Conductor handoff и Windows Console Ctrl+C. Shutdown hook проверяется через
завершение JVM, жёсткая авария — через уничтожение отдельного процесса. ProcessHandle census
учитывает наблюдаемых потомков; это не OS Job Object/sandbox и не контроль отвязавшегося
процесса, который успел исчезнуть из дерева до наблюдения.

## Следующая очередь

Сначала F03–F06: единый attempt ledger, разделение retry/failover на базе upstream
`cause`/`role_failovers`, unknown по каждому полю и requested/reported model. Затем условия
paired evals, model/effort в comparison key и multi-writer preflight F07. F08/F09 остаются
P3; атомарная запись настроек — обязательная предпосылка постоянного Apply в web UI.
UI строится после исправления данных измерений и сравнения.

## Временные артефакты

Тестовый Docker-контейнер остановлен и удалён после проверки. Два диагностических
fixture-каталога вне репозитория оставлены: автоматическая проверка отклонила обе попытки
их удаления с причиной `blocked by policy`. Точные пути записаны в машинном резюме выше.
Исходные временные файлы независимого ревью не изменялись.
