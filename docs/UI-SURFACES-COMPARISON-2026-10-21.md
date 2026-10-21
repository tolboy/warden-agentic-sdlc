# Выбор интерфейса для Warden: Orca и альтернативы

Проверено 2026-10-21 по официальным репозиториям, документации, исходникам и доступному
локальному Orca CLI. Это проверка соответствия требованиям; альтернативы не
устанавливались и не проходили сравнительный live benchmark.

Решение: продолжать интеграцию desktop Orca. Явно более подходящей готовой замены для
текущего Warden не выявлено. Paseo — полноценный запасной кандидат, а не отвергнутый
из-за отсутствия Windows или Grok. Переход имеет смысл пересмотреть, если нужный
сценарий окажется невозможен через поддерживаемый контракт Orca.

## Сравнение

| Вариант | Подтверждённые возможности | Вывод для текущего проекта |
| --- | --- | --- |
| [Orca](https://github.com/stablyai/orca) | Windows/macOS/Linux, worktrees, терминалы, CLI, мобильный клиент; управляемое освобождение workers | Уже есть Warden adapter и live receipts. Доработать автоматическое наблюдение и жизненный цикл принадлежащих Warden вкладок |
| [Microsoft Conductor](https://github.com/microsoft/conductor) | Встроенный web DAG, поток событий агентов, human gates, фоновый запуск; Fleet TUI | Веб-интерфейс уже существует. Текущий Warden вызывается одним script-шагом, поэтому внутренним ролям всё равно нужен отдельный adapter |
| [Paseo](https://github.com/getpaseo/paseo) | Windows desktop, Android, web, SDK, плагины UI/provider; AGY и Grok ACP | Серьёзная альтернатива. Готового adapter для нашего журнала, gates и cleanup не проверено; миграция сейчас добавляет новую интеграцию |
| [Superset](https://github.com/superset-sh/superset) | CLI agents, worktrees, терминалы, diff/browser | В текущем README Windows помечен как ещё недоступный. Для основной Windows-среды сейчас не подходит |
| [Vibe Kanban](https://github.com/BloopAI/vibe-kanban) | Канбан, workspaces, coding agents, diff/browser | Проект объявил sunsetting с продолжением community maintenance. Для новой основной зависимости менее привлекателен |

Отсутствие проверенного adapter не доказывает, что его невозможно сделать. Эта
таблица не устанавливает превосходство Orca по скорости, потреблению памяти или
стабильности. Выбор основан на требованиях и уже проверенной интеграции Warden.

## Уточнение по Microsoft Conductor

Официальные [README](https://github.com/microsoft/conductor#web-dashboard) и
[CLI reference](https://github.com/microsoft/conductor/blob/main/docs/cli-reference.md)
описывают `--web`, `--web-bg`, интерактивный граф и ответы на human gates в браузере.
Есть также [Fleet Manager](https://github.com/microsoft/conductor/blob/main/docs/fleet.md).
Последний опубликованный релиз при проверке — `v0.1.42`; просмотренный main —
`49af6de0d21e1125faa22bc5a607500e045e29cd`. Возможности main и релиза следует различать.

В нашем [workflow](../conductor/do.yaml) `run_task` запускает Java/Warden целиком.
Даже если shell stdout показывается в UI Conductor, это не превращает внутренние
Warden attempts в отдельные agent nodes. Понадобится мост событий либо перенос
внутреннего workflow. Второй вариант дублирует уже существующие budgets, failover,
recovery и durable decisions. Для текущего этапа такой перенос не предлагается.

## Paseo без искусственного исключения

На [странице загрузки](https://paseo.sh/download) есть Windows x64/ARM64 и Android.
В релизе `v0.12.0` от 2026-10-19 проверено наличие Windows installer и Android APK.
[Список providers](https://paseo.sh/docs/supported-providers) включает Antigravity
через установленный `agy` и Grok через ACP catalog. Поэтому утверждать, что Paseo
не поддерживает выбранных агентов, нельзя.

[Plugin SDK](https://paseo.sh/docs/plugins) позволяет добавлять панели, экраны,
provider adapters и daemon RPC; клиентские расширения доступны и на mobile.
Это делает Paseo перспективным вариантом, особенно для нового проекта. Однако
совместимость наших точных model/effort flags, процессного восстановления и
финального Warden artifact с Paseo в этой сессии не испытывалась.

## Почему Superset и Vibe Kanban сейчас не выбраны

У [Superset README](https://github.com/superset-sh/superset#install) Windows указан
как недоступный, Linux — experimental. Это конкретный барьер для текущей среды,
а не оценка качества интерфейса.

[Vibe Kanban объявил](https://www.vibekanban.com/blog/shutdown) переход от поддержки
командой к сопровождению сообществом. Код остаётся открытым; из объявления не
следует, что продукт перестал работать. При выборе новой основной зависимости
это дополнительная неопределённость.

## Проверенные исходники Orca

Репозиторий: [stablyai/orca](https://github.com/stablyai/orca), MIT.
Локальный CLI — `1.4.233`, runtime reachable/ready. Для него найден tag `v1.4.233`,
разыменованный в commit `c60a9b0a2a8d1aff2da94c2185a6dba238e65755`.
Также прочитан main `8174208436fba75b4c579b04906f4149b5c4e6ec`. Пять перечисленных
ниже файлов совпали по тексту между этими снимками. Это не означает совместимость
всего main с установленным приложением. На момент проверки опубликован более
новый релиз `v1.4.237`; приложение в этой сессии не обновлялось.

Проверены:

- [Terminal close CLI spec](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/cli/specs/terminal-close.ts): отдельный terminal или tab можно закрыть адресно.
- [Worker release](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/orchestration/worker/worker-release.ts) и [completion](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/orchestration/worker/worker-release-completion.ts): проверка identity, архивирование и результаты `retained`, `release_pending`, `release_unknown`.
- [Ownership tests](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/src/main/runtime/rpc/methods/orchestration/worker/worker-release-ownership-guard.spec.ts): terminal после user takeover нельзя закрывать автоматически, даже если процесс уже вышел.
- [Recovery guide](https://github.com/stablyai/orca/blob/c60a9b0a2a8d1aff2da94c2185a6dba238e65755/skill-guides/orchestration/references/recovery-and-cleanup.md): release по принятому settlement, без подмены на `terminal close` при неизвестном результате.

Общее условие «закрыть всё отработанное» должно означать «освободить доказанно
завершённые ресурсы, которыми Warden всё ещё владеет». Название вкладки и факт
её создания в прошлом не доказывают текущее владение. Пользовательские,
перехваченные пользователем, transferred/external и неопределённые ресурсы сохраняются.

## Перед реализацией

Ветка Warden при проверке: `codex/next-run-form`, HEAD `08f197f`,
[draft PR #54](https://github.com/tolboy/warden-agentic-sdlc/pull/54).
В [CI](https://github.com/tolboy/warden-agentic-sdlc/actions/runs/39617900271)
Linux JDK 21/25, demo и hygiene прошли. Оба Windows job: 4935 passed, 1 failed.
Единственный отказ: `[roster] file path is policy.yaml`,
[RosterTest.java](../src/test/java/dev/warden/RosterTest.java), проверка сравнивает
строки Windows short path и canonical long path одного файла. После исследования
пользователь разрешил подготовить PR к слиянию: ожидаемый путь в тесте приведён
к `policy.toRealPath()`. Production containment, canonicalization, atomic writes
и backup checks не изменены. Актуальный результат повторного CI смотри в PR.

Готовый [промпт следующей сессии](prompts/NEXT-SESSION-ORCA-DESKTOP-2026-10-21.md)
включает проверку CI baseline до основной UI-работы. Android — дополнительная проверка после
desktop acceptance. Нового интерфейса управления, установки альтернатив и live vendor
вызовов в этой исследовательской сессии не было.

Результат следующего desktop-этапа: [частичная реализация и точный blocker
Orca](ORCA-DESKTOP-CONTRACT-2026-10-21.md). Автоматическая карточка и явный CLI
fallback реализованы; overview с безопасным takeover/release пока заблокирован
контрактом установленной версии. Полная desktop acceptance не объявлена пройденной.
