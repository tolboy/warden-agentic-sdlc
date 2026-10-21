# Безопасное сохранение настроек — 2026-10-20

Пути `out/` ниже обозначают локальные gitignored-артефакты проверки; в клоне репозитория их нет.

Продолжение [аудита](AUDIT-AGENT-LOOP-2026-10-19.md) и
[этапа измерений/evals](AGENT-LOOP-EVALS-FIXES-2026-10-20.md).
Ветка: `codex/agent-loop-settings`, база: `7e5e8e3`.
Этап закрывает F08 для изменения roster/model/effort и verification stamp.

## Поведение

`ConfigFileStore` принимает исходный snapshot и проверенный кандидат. Запись проходит
под блокировкой внутри JVM и OS file lock, с проверкой SHA-256 исходного файла.
Кандидат и точная копия предшественника записываются с `force(true)`, затем кандидат
атомарно заменяет активный YAML. Неатомарного fallback нет. Ошибка валидации не
меняет активный файл и не создаёт backup. Копируются POSIX mode или Windows ACL;
Windows read-only файл не перезаписывается.

Постоянный sidecar `*.warden-write.lock` не удаляется после операции: все процессы
должны блокировать один и тот же файл. OS освобождает lock после выхода/аварии.
Локальный конкурент отклоняется до открытия второго дескриптора: на POSIX его
закрытие могло бы снять блокировку первого писателя. Нельзя удалять sidecar во время
работы писателей.

На Windows открытый читателем handle иногда препятствует rename. Запись повторяет
только `AccessDeniedException` с паузой 25 ms, не дольше примерно одной секунды;
перед каждой попыткой заново проверяет digest. Постоянная ошибка возвращается
вызывающему коду, активный YAML остаётся целым.

`warden roster` возвращает `file_versions` для policy и всех прочитанных profiles.
Digest вычисляется из тех же байтов, что использованы для построения отчёта.
`roster set` и `roster model` принимают `--expected-sha256 HASH`; устаревший preview
получает `config_conflict`. Без флага CLI всё равно контролирует snapshot собственной
операции. Занятый lock даёт `config_busy`, отсутствие atomic move —
`atomic_replace_unsupported`. Успех возвращает `before_sha256`, `sha256` и backup.
Имя backup остаётся совместимым: `*.before-roster-<timestamp>`, с суффиксом при коллизии.

`profiles --verify` удерживает digest профиля, из которого был построен probe.
Даже изменение комментария во время успешной проверки препятствует stamp.
Модель, vendor или args, изменённые после probe, не наследуют его результат.
Stamp проходит через тот же writer и сохраняет `*.before-verify-*`.
Редактирование строк сохраняет комментарии и LF/CRLF; YAML не сериализуется заново.
Команды и ограничения описаны в [SETUP](SETUP.md#changing-the-roster).

## Проверки

Сборка: `build.cmd`, `javac --release 21`, host JDK 25.
Финальные native Windows suites: **577 passed, 0 failed**, девять suites:
`config-file-store`, `roster`, `profile verifier`, `config`, `config-view`,
`role-resolver`, `run-override`, `launch-settings`, `dashboard`.
Лог Windows (`out/settings-windows-verified.log`).

Новые проверки покрывают конкуренцию двух писателей с одним digest, lock между
разными JVM, сохранение OS lock после локального отказа, аварийный halt после
staging/backup, отказ atomic move, ошибку rename, неизменность исходного файла,
целые версии при конкурентном чтении, уникальные backup и права доступа.
На Windows также проверены read-only и повтор sharing denial с изменением digest
между попытками. Сквозной CLI probe сам меняет свой YAML, успешно отвечает и получает
`config_conflict` вместо stamp.

Отдельный smoke на production `out/classes`: `setup` и `roster` в изолированном home,
успешный `set --expected-sha256`, независимая сверка SHA-256 файла и backup,
повтор со старым digest → exit 1 / `config_conflict`, новые байты не изменены.
Лог CLI (`out/settings-cli-production.log`).

Полный Linux registry: **4688 passed, 0 failed**, 54 suites, exit 0.
Лог Linux (`out/settings-linux-verified.log`). Bytecode собран на Windows;
контейнер использовал JRE 25, Git, отключённую сеть и loopback hostname.
Отдельной Linux-компиляции и полного native Windows registry нет.

Все 152 Java-исходника совпадают с замороженным
manifest SHA-256 (`out/settings-tested-source-sha256.json`); после сборки изменений
исходников не было. Тестовый контейнер остановлен и удалён.
Машинное резюме: settings-validation-summary.json (`out/settings-validation-summary.json`).
Логи и manifest находятся в gitignored `out/` этого checkout.
`git diff --check` и repository hygiene (`paths`, `uuids`) проходят.

## Границы

Это транзакция одного существующего файла для писателей, использующих данный
протокол. Внешний редактор, игнорирующий sidecar, может попасть между последней
проверкой digest и rename. Общая транзакция policy + profiles, Power-loss durability
directory metadata и полная миграция путей создания project/task artifacts сюда
не входят. После kill до rename может остаться `.warden-config-*.tmp`; активный
YAML остаётся целым, следующая операция не требует удаления lock вручную.

Платные vendor probes, live Orca/Conductor и Windows CI не запускались. Для этого
этапа работающая Orca не требуется; новые сквозные probes используют локальный shell.
Следующая очередь — F09 и форма следующего запуска поверх config/preview;
постоянный Apply должен передавать показанный digest и показывать diff.
