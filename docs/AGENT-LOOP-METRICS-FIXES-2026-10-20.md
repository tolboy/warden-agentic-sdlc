# Исправления измерений agent loop

Пути `out/` ниже обозначают локальные gitignored-артефакты проверки; в клоне репозитория их нет.

База: `7d7b4f4`, завершённый этап F10/F02/F01. Ветка: `codex/agent-loop-metrics`.
Работа продолжена в отдельном чистом checkout: три ранее существовавших документа Orca
в исходном рабочем дереве сохранены без изменений.

## Результат

| Дефект | Поведение после исправления |
|---|---|
| F03 | Локальный ledger учитывает завершённый `vendor_attempt` до появления `role_run`. Журнал и вложенные attempts сверяются по `vendor_attempt_id`; частичный журнал не теряет остальные вызовы, поздняя запись не удваивает стоимость |
| F04 | A→A даёт ноль failover, A→B и A→A→B — один. Upstream `role_failover`/`role_failovers` служат источником переходов. Частичный журнал дополняется из summary; подтверждённое продолжение с одной новой попыткой учитывается; повторяющиеся записанные пары переходов сохраняются |
| F05 | В joined report каждое token-поле имеет собственный счётчик отсутствующих значений. Input-only usage не превращает output/total в ноль; сообщённый ноль остаётся известным |
| F06 | `model_requested` отделён от `model_reported` и сохраняется в home corpus. Ledger `by_model`, vendor rows и stage table используют только reported identity; `by_requested_model` доступен отдельно |

Если идентичность старых записей не позволяет доказать совпадение или различие вызовов,
локальный ledger, как и corpus, помечает `ambiguous_coverage`/`incomplete` и не утверждает
точную стоимость. Исторический `model` без provenance остаётся в исходном evidence,
но не объявляется доказанной requested/reported моделью.

`schema_version` сохранена; добавлены поля requested model и coverage. Неподтверждённый
`model` больше не заполняется из request в новых attempt-записях, а joined stage table
показывает reported model. Значение `tokens_unknown_calls` уточнено: это вызовы с хотя бы
одним неизвестным token-полем. Подробный контракт и границы чтения описаны в
[METRICS.md](METRICS.md). Run verdict и число видимых локальных событий сохраняют
прежнюю семантику; отдельная paid attempt не означает завершение task run.

## Проверка

Сборка `build.cmd`: JDK 25, `--release 21`, production и tests.
На Windows выполнены `ledger`, `measurement-accounting`, `ledger-compare`, `home-corpus`,
`corpus-reader` и `role runner`: **696 passed, 0 failed**, exit 0.
Лог Windows (`out/metrics-windows-verified.log`).

Полный финальный Linux registry: **4578 passed, 0 failed**, 52 suites, exit 0.
Лог Linux (`out/metrics-linux-verified.log`). Прогон выполнен на замороженных исходниках.
Bytecode собран
на Windows; отдельной Linux-компиляции нет. Тестовый контейнер использует JRE 25 и Git,
сеть отключена, hostname сопоставлен loopback. Progress wrapper повторяет актуальный
registry TestMain и добавляет только BEGIN/END suite.

Тестовый контейнер остановлен и удалён после проверки. Manifest всех 147 Java-исходников:
metrics-tested-source-sha256.json (`out/metrics-tested-source-sha256.json`).
Manifest и логи находятся в gitignored `out/` этого checkout. `git diff --check` и
обе hygiene-проверки (`paths`, `uuids`) проходят.
Машинное резюме: metrics-validation-summary.json (`out/metrics-validation-summary.json`).

## Следующая очередь

Остаются paired evals и comparison contract: model/effort в ключе условий,
`task_pair_id`, достаточность сопоставимых измерений. Затем multi-writer preflight F07.
Для чтения попыток после аварии без task summary используется ledger: joined report
по-прежнему требует `task-run.json`. Live vendors, реальные subscription limits,
Orca/Conductor и Windows CI в этой очереди не проверяются. UI остаётся после
исправления сравнимости измерений; атомарный Apply настроек — его предпосылка.
