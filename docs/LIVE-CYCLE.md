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

## Чего этот файл **не** утверждает

* Реализатор на живом вендоре внутри полного цикла не прогнан до зелёного — упёрся в квоту.
* Роль `visual_qa` (модель, которой скриншоты приходят вложениями) на живом вендоре не
  прогонялась; профиль `codex-visual-qa` не верифицирован.
* Orca-адаптер (`runner: orca`) как исполнитель роли живьём не подтверждён. Orca в этих
  прогонах создавала worktree — это `OrcaIsolation`, другой путь кода.
* `warden do --conductor` (Conductor, запущенный самим Warden через `ConductorBridge`) не
  прогонялся: проверялся тот же workflow, запущенный напрямую из CLI Conductor.
