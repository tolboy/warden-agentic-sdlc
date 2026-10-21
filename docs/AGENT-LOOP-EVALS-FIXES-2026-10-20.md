# Исправления comparison contract и multi-writer preflight

Пути `out/` ниже обозначают локальные gitignored-артефакты проверки; в клоне репозитория их нет.

База: `58f810a`, завершённая очередь F03–F06. Ветка: `codex/agent-loop-evals`.
Продолжение выполнено в том же отдельном checkout; ранее существовавшие документы
Orca в исходном рабочем дереве сохранены.

## Результат

| Область | Поведение после исправления |
|---|---|
| Условия сравнения | Перед workflow dispatch сохраняется frozen requested configuration: model/effort, runner, rosters, grants, limits, routing, build/runtime и hashes. Даже остановка до первого вызова остаётся в предназначенной группе |
| История и продолжения | Ключ включает все разные конфигурации цепочки. Отсутствующие links, invalid chain и исторические неполные условия видны; текущие profile-файлы не переписывают измерения |
| Comparability | Schema v2 отделяет sample size от сравнимости. Три несвязанные задачи больше не дают `comparable: true` |
| Paired evals | В `--baseline-file` добавлены experiments и run annotations. Явный ключ пары: experiment, task_pair_id, repeat; все variants должны присутствовать ровно по одному разу |
| Метрики evals | Outcome, spend, elapsed, calls и operator effort имеют отдельные paired denominators. Неизвестные значения и stopped/rejected outcomes сохраняются; pending human outcomes блокируют полную пару |
| F07 | Preflight, cast и settings preview учитывают всех предшествующих writable-авторов, порядок стадий, rotation, pins и declared prior writers. Заведомо невозможный независимый review останавливается до оплаты |

Пример входа: [paired-baseline.json](../examples/evals/paired-baseline.json).
Контракт, команды, schema migration и значения флагов: [METRICS.md](METRICS.md).
Legacy baseline-файлы `{"rows":[...]}` продолжают читаться; отрицательные и
нечисловые измерения отказываются. Условия requested не объявляются доказанными
effective/reported settings. Декларации оператора о manual outcomes, matching tasks
и frozen baseline label остаются декларациями.

## Проверка

Сборка `build.cmd`: JDK 25, `--release 21`, production и tests.
Windows registry subset: `ledger-compare`, `paired-evaluation`, `config`, `workflow`,
`role-resolver`, `config-view`, `run-override`: **457 passed, 0 failed**, exit 0.
Лог Windows (`out/evals-windows-verified.log`).

Отдельно на Windows выполнены реальные методы `multiWriterPreflight` и
`independenceChecks` из `TaskLoopTest`: **33 passed, 0 failed**, exit 0.
Лог preflight (`out/evals-preflight-windows.log`). Проверены два автора разных
вендоров, отказ до первого вызова, более раннее ревью, третий независимый вендор,
task pins, rotation и writable planner.

Production build fingerprint проверен отдельно для class directory и JAR;
digest JAR совпадает с независимым SHA-256. Лог (`out/evals-build-probe.log`).
CLI прочитал новый example manifest на fixture project, показал отсутствующий run,
`0/1 complete pairs` и `comparable=false`, exit 0; synthetic template не принят
за успешный эксперимент.

Полный финальный Linux registry: **4632 passed, 0 failed**, 53 suites, exit 0.
Лог Linux (`out/evals-linux-verified.log`). Прогон выполнен на замороженных исходниках.
Manifest всех 150 Java-исходников:
evals-tested-source-sha256.json (`out/evals-tested-source-sha256.json`).
Bytecode собран на Windows; Linux использует JRE 25 и Git в тестовом контейнере
с отключённой сетью и loopback hostname. Отдельной Linux-компиляции нет.
Логи и manifest находятся в gitignored `out/` этого checkout.
Все 150 hashes повторно сверены с текущим `src/`; изменений после сборки нет.
Тестовый контейнер остановлен и удалён. `git diff --check` и repository hygiene
(`paths`, `uuids`) проходят.
Машинное резюме: evals-validation-summary.json (`out/evals-validation-summary.json`).

## Границы и следующая очередь

Comparability означает достаточность явно сопоставленных данных для описательного
сравнения. Statistical significance, автоматическое ранжирование, enforcement
randomization и post-acceptance defect follow-up не реализованы. Новые поля не
меняют home corpus schema: comparison читает локальные summaries.

Preflight прогнозирует текущую выбранную ветку; будущий vendor failover, исправления
и восстановленная runtime provenance проверяются существующими dispatch guards.
Фактическая независимость по-прежнему определяется runtime writer set.
Live vendors, реальные subscription limits, Orca/Conductor и Windows CI здесь не
проверяются. Следующий этап — атомарное применение настроек; после этого можно
строить лёгкий UI поверх проверяемых config/preview и measurement contracts.
