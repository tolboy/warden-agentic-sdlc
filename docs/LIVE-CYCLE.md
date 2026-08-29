# Живой цикл: что реально прогонялось, на чём и чем закончилось

Стенограмма, а не заявление о намерениях. Проект — `Living Horizon` (SvelteKit),
worktree создавала Orca 1.4.190, вендоры настоящие, Windows 11, Java 25, `--release 21`.

Дата прогонов: 2026-08-28.

---

## 1. Вход оператора: `warden do` создаёт изоляцию сам

```text
warden do --project C:\Users\anato\Living-horizon --scope code --dry-run \
          --task-id warden-e2e-probe "Probe run: confirm the Create button stays visible"
```

```json
{"ok":true,"code":"dry_run",
 "worktree":"C:\\Users\\anato\\orca\\workspaces\\Living-horizon\\w-warden-e2e-probe",
 "isolated":true,"isolation":"created","worktree_start_ref":"main",
 "steps":[{"step":"implementer",...},{"step":"gates",...},
          {"step":"reviewer",...},{"step":"visual_qa",...}]}
```

Проверено этим прогоном:

* Orca создала worktree по запросу Warden; Warden не выполнял ни `git branch`, ни
  `git worktree add`;
* `worktree_start_ref: main` — `HEAD` разрешается в **фактическую текущую ветку**, а не
  подставляется константой. На `master`, `develop` или feature-ветке worktree возьмётся
  оттуда же; при detached HEAD команда отказывает (`detached_head`), а не угадывает;
* в новом worktree создан контракт проекта, составлен черновик задачи, и вся цепочка
  стадий разрешилась — до единого вызова вендора.

---

## 2. Полный цикл на существующей задаче: кнопка Create

Задача была поставлена раньше и сделана: в `src/routes/layout.css` девять строк,
возвращающих `.mode-switch` на ширинах 640–759.98px в ландшафтной ориентации. Телефон,
повёрнутый набок, достаточно широк для студии, но мобильная шапка прятала единственный
элемент, через который в неё можно войти. Явного подтверждения, что задача закрыта, не было.

Прогон выполнялся цепочкой **без стадии `implement`** — работа уже в worktree, реализовывать
нечего. Именно для этого цепочка объявляется, а не зашита:

```yaml
workflow:
  stages:
    - { stage: gates,   run: machine_gates, on_fail: fix, recheck_after_fix: true }
    - { stage: review,  run: role, role: reviewer, when: [review_required],
        on_fail: stop, on_findings: fix }
    - { stage: browser, run: visual_harness, when: [visual_qa_required],
        on_fail: fix, recheck_after_fix: true }
```

```text
warden run fix-the-create-button-so-it-works-again --run-id lh-verify-1
warden report lh-verify-1 --text
```

```text
run      lh-verify-1
task     fix-the-create-button-so-it-works-again  risk=medium
goal     Fix the Create button so it works again
outcome  ok  ready_for_human  next=human_gate
human    pending  options=[accept, reject]

stage           attempt  ok  vendor/model                    cost      tokens        ms
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.0835    104042/13006   337546
visual_qa             0  ok  -                               ?         ?/?                 ?

vendor/model                     calls  fail  cost      in/out tokens        ms
grok/grok-4.6                        1     0  0.0835    104042/13006         337546

browser  passed  5 screenshot(s)
  ok    700x400: text=Create visible
  ok    700x400: text=Create click -> css=main.editing visible
  ok    1280x720: text=Create visible
  ok    1280x720: text=Create click -> css=main.editing visible
  ok    400x800: text=Create hidden
  ok    700x400: no-console-errors

changed  1 source file(s) since 5c42b7bcbdb…
  src/routes/layout.css
         plus 3 Warden contract file(s), unchanged since the run snapshot

totals   1 vendor call(s), 0 fix round(s), $0.0835 of $6.0000 budget
```

Что здесь доказано, по слоям:

| Слой | Чем именно |
|---|---|
| Машинные гейты | `npm run check` и `npm run build` прошли в worktree; ни одного пути вне `src`/`scripts` |
| Целостность конфигурации | весь `.warden` снят слепком до первой стадии и сверен после каждой; отличий нет |
| Независимый ревьюер | Grok 4.6, `verdict: pass`, 0 находок, 104 042 / 13 006 токенов, $0.0835, 337 с |
| Браузер | шесть сценариев на трёх виюпортах, включая **клик** и переход в `main.editing` |
| Человеческий гейт | `decision.json` в состоянии `pending`, варианты `accept`/`reject` |

Ревьюер о сути изменения, дословно:

> The Create/View switch was already in the DOM for landscape viewports at 640–759.98px
> (MOBILE_VIEW_ONLY_QUERY admits the studio there) but `@media (max-width: 759.98px)` set
> `.mode-switch` to `display:none`. The added later rule restores `display:flex` for that band
> in landscape only. Production CSS keeps hide-then-restore order.

Сценарий `400x800: text=Create hidden` стоит в контракте намеренно: без него самый дешёвый
способ пройти проверку выше — показать переключатель везде, а это ломает продукт на
портретном телефоне. Проверка запрещает не только провал, но и подделку.

Скриншот `700x400-after-click.png` показывает открытую студию с палитрой инструментов —
кнопка не просто видна, она работает.

**Решение не записано.** Приёмка — единственный шаг, который считает вывод агента
доверенным, и он остаётся за человеком:

```text
warden approve lh-verify-1 --decision accept --expected-updated-at 2026-08-28T11:53:39.586362700Z
```

Accept отклоняется, если отпечаток worktree изменился с момента показа. Reject, abort и retry
остаются доступны всегда — они ничего не разрешают.

---

## 3. Полный цикл с реализатором: остановлен исчерпанной подпиской

```text
warden run create-button-testid --run-id e2e-1
```

```text
outcome  STOPPED  quota_exhausted  next=human_escalation
human    pending  options=[retry, abort]

stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  NO  codex/gpt-5.6-terra             ?         ?/?             16519
```

Сообщение вендора сохранено дословно: «You've hit your usage limit… or try again at
10:55 AM». Warden не разбирает из него время: в формулировке нет ни даты, ни часового пояса.

Это не дефект прогона, а тот самый путь, ради которого квота отделена от обычной ошибки:

* `role_quota_exhausted`, а не `implementer_failed` — оператора не отправляют читать
  транскрипт, в котором нет дефекта;
* профиль исключён из этого процесса, повторно не вызывается;
* failover не сработал только потому, что в политике на роль `implementer` назначен ровно
  один профиль;
* `?` вместо `0.0000` в стоимости и токенах: вендор их не сообщил, и отчёт этого не скрывает.

Задача и worktree остались готовыми к повтору, когда окно квоты откроется:

```text
cd C:\Users\anato\orca\workspaces\Living-horizon\w-warden-e2e-probe
warden run create-button-testid --run-id e2e-2
```

Убрать за собой, если повтор не нужен: `orca worktree remove --worktree name:w-warden-e2e-probe`.

---

## 4. Conductor как внешний контроллер

```text
conductor run conductor\do.yaml --skip-gates --no-interactive
    -i task=fix-the-create-button-so-it-works-again
    -i project_dir=<worktree>
    -i run_id=lh-conductor-1
    -i actor=warden-integration-test
```

```text
┌─ Agent: run_task [iter 1]
└─ ✓ run_task  (21.64s)
   → next: human_approval

┌─ Agent: human_approval [iter 1]
Auto-selecting: Reject (--skip-gates)

┌─ Agent: record_reject [iter 1]
  Script: … dev.warden.Main approve lh-conductor-1 --decision reject
          --expected-updated-at 2026-08-28T12:10:59.410123200Z --actor warden-integration-test
└─ ✓ record_reject  (0.19s)

Workflow terminated at 'rejected': Human rejected the candidate. No changes were landed.
```

Conductor 0.1.33. Проверено:

* маршрут по `run_task.output.exit_code` сработал;
* токен оптимистичной блокировки `decision_updated_at` прошёл из вывода Warden в аргументы
  `warden approve` через шаблон Conductor — единственное место, где эти две программы обязаны
  договориться;
* решение записано durable: `state: resolved`, `decision: reject`, `actor` — тот, что передан;
* повторное решение по тому же прогону отклонено (`duplicate_decision`);
* `--skip-gates` выбирает **первый** вариант, и в обоих гейтах первым стоит отказ (`Reject`,
  `Abort`). Автоматика может отказать за человека, принять — нет. Порядок вариантов в
  `do.yaml` держит этот инвариант, и об этом там написано.

Прогон намеренно машинный: цепочка из одной стадии `machine_gates`, ни одного вызова вендора.
Проверялся мост, а не мнение модели.

---

## 5. Проект с нуля

```text
warden do --project <пустой каталог> --in-place --init-repo --dry-run \
          "Build a landing page with a Create button"
```

`git init` + базовый коммит, контракт с пустым `checks.fast: []` и областью `<repository>`,
черновик задачи с браузерными сценариями как определением «сделано», вся цепочка разрешилась.
Подробности — в README, раздел «Первый запуск».

---

## 6. Три вендора в одном прогоне, и что при этом сломалось

Второй заход, когда квоты открылись у всех троих. Состав ролей: реализатор — Codex
(`gpt-5.6-terra`), ревьюер — Grok 4.6, глаза — Claude Opus через `capabilities.vision`
с доставкой `workspace_file`: у Claude нет флага для картинок, зато есть инструмент
чтения, который показывает изображение, а не имя файла.

Верификация профиля `claude-visual-qa` — не «вышел с нулём», а сверка по пикселям:

```text
probe: открой этот скриншот и назови две кнопки в пилюле сверху и число иконок справа
ответ: VIEW, CREATE
       8
```

Метки прочитаны верно, и ни в одном имени файла по этому пути нет слова VIEW. Счёт
иконок при повторном прогоне дал 7 вместо 8 — это записано в `verification.note`
профиля, чтобы дата верификации не читалась как обещание точности, которого проба не
дала. Этот глаз — для «читаемо, налезло, развалилось», а не для пересчёта мелочи.

### Что нашёл живой прогон

Пять дефектов, каждый — в коде, а не в рассуждении о коде.

**Промпт с JSON не доходил до вендора.** Промпт визуального QA включает отчёт
харнесса; отчёт — JSON; JSON — кавычки. На Windows JVM оборачивает аргумент в кавычки,
если в нём есть пробел, но не экранирует кавычку внутри значения — принимающий процесс
разрезает аргумент с этого места. До `claude.exe` дошло `error: unknown option '->'` за
620 мс. Старая проверка искала только переводы строк через batch-shim, а `claude` —
настоящий `.exe`. Теперь отказ до отправки, оба Claude-профиля на `prompt_delivery: stdin`.
Обе половины правила нужны: `-c model_reasoning_effort="high"` кавычку содержит, пробела
нет, JVM его не оборачивает, и он ходит исправно — первая версия правила его отклонила.

**Ревьюер не знал о браузерном слое.** Grok выписал P1 «ни одна команда не проверяет
этот атрибут» — а сценарий строкой ниже проверял его по имени. Реализатора отправили
писать тест для уже проверенного. Промпт ревьюера не упоминал браузерные сценарии
вообще; теперь они стоят рядом с командами приёмки.

**Гейт брал деньги за проблему оператора.** `package-lock.json` тронул setup самой Orca,
до появления агента. Проверка области срабатывала *после* реализатора и возвращалась ему
же фикс-раундом — а он физически не может это починить: путь вне его области изменений.
Теперь проверка идёт до первой отправки, а `preflight_outside_scope`,
`base_ref_unresolvable` и `contract_mutated` терминальны по той же причине, по какой ею
уже был `visual_qa_unavailable`.

**Отпечаток кандидата включал улики Warden.** Прогон, зелёный на каждой стадии, отказал
в приёмке с `candidate_changed` — из-за собственного лог-файла, лежавшего в `.warden/`.
Правило верное, набор файлов неверный: человек принимает изменение исходников. Два
вопроса разведены — «трогала ли что-нибудь read-only роль» по-прежнему включает
`.warden`, «тот ли это кандидат» больше нет. Целостность контракта не слабеет: любое
изменение в `.warden` во время прогона и так даёт `contract_mutated` по снимку.

**Потолок ходов — не провал работы.** Ревью диффа на 126 строк израсходовало все 16
разрешённых ходов на чтение и вышло с кодом 1 без артефакта; прогон записал
`role_command_failed`. Ничего не сломалось: вендора прервал предел, который выставил
оператор, и он к тому моменту уже нашёл два настоящих дефекта. Отдельный код
`role_turns_exhausted` с указанием, что поднять. Failover не делается — другой вендор
упрётся в тот же потолок на том же диффе.

### Отказ, который система обязана была не проглотить

Реализация маяка прошла обе команды приёмки, и Codex вернул `status: blocked`:

> The scoped lighthouse implementation is present and both acceptance commands pass.
> Production persistence remains blocked: Supabase frame validation does not allow the
> new lighthouse kind, and the required migration is outside the permitted src/scripts
> paths.

То же самое до него нашёл Grok. Агент отказался и молча выпустить то, что сломается при
публикации, и выйти за выданную ему область. Синтаксически корректный артефакт со
статусом `blocked` — это не успех, и `semanticFailure` его таким не считает.

Ответ оператора — не уговорить агента, а расширить область до `supabase/migrations`,
где лежит прямой прецедент: `20260712060000_boat_element.sql` добавлял ровно так же вид
`boat`.

### Полный цикл, три вендора, зелёный

```text
run      lighthouse-5
task     lighthouse-element  risk=medium
outcome  ok  ready_for_human  next=human_gate
human    pending  options=[accept, reject]

stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             ?         549584/3549    143451
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.2584    148206/34219  1019773
visual_qa             0  ok  -                               ?         ?/?                 ?
visual_qa             0  ok  claude/opus                     1.0181    10/6578        113959

browser  passed  2 screenshot(s)
  ok    1280x720: text=Create click -> testid=tool-lighthouse visible
  ok    ... -> css=.palette button.active[data-testid=tool-lighthouse] visible
  ok    ... -> css=canvas click@0.5,0.12 -> text=water visible
  ok    ... -> css=canvas click@0.5,0.82
  ok    1280x720: no-console-errors

changed  8 source file(s) since 5c42b7bcbdb…
totals   3 vendor call(s), 0 fix round(s), $1.2765 of $14.0000 budget
```

Ревьюер, дословно:

> The lighthouse is a permanent Create-palette kind (`data-testid=tool-lighthouse`), allowed
> only in the central share of a deep water band, drawn with a sand-and-stone islet under the
> tower, and admitted by a new `frame_elements_valid` migration that copies the current
> allowlist and adds lighthouse. Existing kinds' placement and draw helpers are unchanged.

Роль с глазами, дословно:

> the canvas shows a lighthouse standing in the middle of the lake on a small sandy islet of
> its own, with a lit lamp and glow — mid-water, well clear of both shores, exactly what the
> task asks for.

Две находки P3, обе косметические и обе — не про эту задачу: строка состояния пишет
«1 DETAILS» рядом с «1 DETAIL», и подпись LIGHTHOUSE заполняет свою плитку почти вплотную,
тогда как у соседей есть поля.

`css=canvas click@0.5,0.82` — тот самый клик в точку внутри элемента. Без него canvas во всё
окно достижим только по центру, и поставить что-либо в воду сценарием нельзя.

---

## 8. Полный цикл до человеческого гейта, и починка, которую заказали глаза

2026-08-29. Задача не про вёрстку и не про кнопку: у главы `stone-upon-stone` в
`src/lib/story.ts` не было смысла — путник нёс камень к куче камней, клал его и
останавливался. Цель дала направление (очаг на камнях вместо ещё одного камня) и оставила
дизайн реализатору, а обязательным сделала то, что можно проверить: id шаблона, вид
жеста-потребности, `data-testid` на кнопках стенда и «читается с одного взгляда на 1280x720».

Контракт задачи опирается на шаг `wait`, без которого этот проект нельзя проверить вообще:
панель стенда появляется в DOM секунд через шесть после загрузки, а расплата главы —
секунд через двадцать восемь после постановки.

```yaml
- "1280x720: wait 7 -> testid=lab-chapter-stone-upon-stone click -> wait 20
   -> css=canvas visible -> wait 14 -> css=canvas visible -> no-console-errors"
```

```text
stage           attempt  ok  vendor/model                    cost      tokens        ms
implementer           0  ok  codex/gpt-5.6-terra             ?         1103531/4842   166805
gates                 0  ok  -                               ?         ?/?                 ?
reviewer              0  ok  grok/grok-4.6                   0.3397    543998/23179   712582
visual_qa             0  ok  -                               ?         ?/?                 ?
visual_qa             0  ok  claude/opus                     1.8399    34/15152       243463
implementer           1  ok  codex/gpt-5.6-terra             ?         851760/5597    164552
gates                 1  ok  -                               ?         ?/?                 ?
reviewer              1  ok  grok/grok-4.6                   0.3099    187337/45156   990086
visual_qa             1  ok  -                               ?         ?/?                 ?
visual_qa             1  ok  claude/opus                     1.4202    26/9485        156206

totals   6 vendor call(s), 1 fix round(s), $3.9098 of $40.0000 budget
outcome  ok  ready_for_human  next=human_gate
```

Раунд починки заказала **не машина**. Гейты были зелёные, ревью Grok — `pass`, харнесс —
`passed`. P1 выставила роль с глазами, дословно:

> the whole tableau is standing on water … at wait-5 the fire's base sits at ~y 578, above
> the lake's near edge at y 590, i.e. mid-basin. A hearth burning on a lake reads as a
> rendering fault, not as a reason, and it directly contradicts the chapter's own name,
> 'the mark on the open ground'.

Реализатор починил постановку стенда, ревьюер перечитал дерево (`recheck_after_fix`), на
втором заходе роль дала `pass`: путник подходит к двум камням на равнине, через четырнадцать
секунд там горит очаг с дымом. `stale_judgements` в `task-run.json` пусто — ни одна стадия не
судила о дереве, которого больше нет.

Что этот прогон стоил инструменту: четыре дефекта Warden, найденные по дороге и починенные в
самом Warden, — слепота харнесса к интерфейсу со своими часами, `text=chapter visible` в
черновике контракта из слова перед «button», реализатор, которому никогда не показывали
браузерные сценарии, и проба готовности, которая говорила Vite по HTTP/2 и не слышала ответа.
Последняя стоила отдельного прогона: `visual_qa_unavailable` — это остановка без раунда
починки, и она выбросила зелёные реализатора и ревью.

---

## 7. Чего этот файл всё ещё не утверждает

* Orca-адаптер (`runner: orca`) как исполнитель роли живьём не подтверждён. Orca в этих
  прогонах создавала worktree — это `OrcaIsolation`, другой путь кода.
* `warden do --conductor` (Conductor, запущенный самим Warden) не прогонялся: проверялся тот
  же workflow, запущенный напрямую из CLI Conductor.
* Подтверждение failover по решению человека (`--continue`) покрыто тестами, но на живой
  исчерпанной квоте не прогонялось: во второй заход квоты были у всех троих.
