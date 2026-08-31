# Как этим пользоваться: разбор на живом прогоне

Это не описание намерений, а стенограмма. Каждый блок ниже реально выполнялся; вывод
приведён как есть. Прогон делался на подставных «вендорах» — обычных shell-скриптах, — чтобы
показать всю схему, ничего не потратив. Реальный вендор подставляется заменой одной строки
`command:` в профиле.

---

## Шаг 0. Три места, которые надо различать

```
~/.warden/            КТО запускает        — профили вендоров и политика ролей
<любой проект>/.warden/  ЧТО значит «сделано» — команды проверки и задачи
warden                КАК                  — гейты, роли, петля, улики
```

Вендор — свойство того, кто запускает, а не проекта. Поэтому у проекта в git не лежит ни
одного имени модели, и тот же `project.yaml` работает у коллеги с другими подписками.

---

## Шаг 1. Настроить себя (один раз)

```
warden setup
```

```json
{"ok":true,"home":"~/.warden",
 "created":["policy.yaml","profiles/grok-review.yaml","profiles/claude-review.yaml",
            "profiles/codex-implement.yaml","prompts/reviewer.md","prompts/implementer.md",
            "schemas/reviewer.json","schemas/implementer.json"]}
```

Существующие файлы **не перезаписываются** — повторный `setup` только сообщит, что оставил
без изменений.

> Каталог настроек переопределяется переменной `WARDEN_CONFIG_HOME`. Именно `WARDEN_CONFIG_HOME`,
> а не `WARDEN_HOME`: последнюю launcher `bin\warden.cmd` использует под каталог установки, и
> на первом же живом прогоне под Windows это столкновение имён увело стартовую конфигурацию
> внутрь репозитория вместо домашнего каталога. Поймал это не тест, а то, что `setup` печатает
> `home` — поэтому он его и печатает.

```
warden profiles
```

```
  claude-review      role=reviewer     vendor=claude  verified=False  profile is unverified…
  codex-implement    role=implementer  vendor=codex   verified=False  profile is unverified…
  grok-review        role=reviewer     vendor=grok    verified=True
```

Только профиль Grok помечен проверенным, потому что только его флаги были **установлены
запуском**, а не вычитаны из документации. У остальных вместо даты лежит точная команда
проверки, и резолвер их не выпустит, пока дата не проставлена. Угадывание флагов Grok уже
стоило трёх неудачных прогонов; теперь это состояние видно, а не выясняется в середине петли.

Пробу не надо переписывать руками — она запускается своей же командой:

```
warden profiles --verify claude-review
```

```json
{"ok":true,"code":"probe_passed","exit_code":0,"stamped":false,
 "transcript":"~/.warden/verification/claude-review.txt",
 "what_to_check":["exits 0 without opening an interactive session",
                  "stdout is a JSON envelope; note which key carries the answer",
                  "--allowedTools is accepted and the run still completes",
                  "whether a cost figure is reported, and under which key"],
 "next":"read the transcript against what_to_check above. If every point holds, run:
         warden profiles --verify claude-review --confirm"}
```

Обратите внимание на `stamped: false`. Проба прошла — дата не проставлена. `verified_on` —
единственное поле во всей конфигурации, которое записывает **суждение человека**, а не факт,
установленный машиной, и стоит оно перед ролью с правом записи в репозиторий. Проставлять его
по нулевому коду возврата значило бы заменить «человек посмотрел и подтвердил четыре пункта»
на «бинарник запустился» — то есть ровно ту догадку, ради предотвращения которой поле и
заведено. Убрана перепечатка команды и ручная правка YAML, а не чтение.

```
warden profiles --verify claude-review --confirm
→ {"ok":true,"code":"verified","verified_on":"2026-08-27","stamped":true}
```

Дата дописывается в конец блока `verification:`, комментарии оператора остаются на месте: в
этих профилях они и есть самое ценное — это флаги, установленные запуском.

---

## Шаг 2. Подключить проект

Один файл. Для SvelteKit:

```yaml
# .warden/project.yaml
version: 1
project: demo
base_ref: HEAD
checks:
  fast: ["npm run check"]
scopes:
  app: ["src"]
defaults:
  checks: fast
  risk: medium
```

Для JVM-проекта меняется только содержимое `checks` — `./gradlew build`. Больше ничего.

Задача — минимум четыре строки:

```yaml
# .warden/tasks/hello.yaml
version: 1
id: hello
goal: Create src/result.txt containing the word ok
risk: medium
scope: app
authority:
  workspace_write: true
```

---

## Шаг 3. Проверить настройку, ничего не потратив

```
warden role reviewer hello --dry-run
```

На машине без установленного Grok это отвечает так:

```json
{"ok":false,"code":"role_unresolved",
 "details":{"message":"no eligible profile for role reviewer:
   {grok-review=executable_not_found, claude-review=profile_unverified}"}}
```

Причина у каждого профиля своя и названа буквально тем, что было проверено. `executable_not_found`
раньше назывался `unavailable_or_quota_exhausted` — и это было враньём: проба выясняет ровно
одно, есть ли исполняемый файл. Про исчерпанную подписку до запуска не знает никто, поэтому
она теперь отдельная причина и появляется только после того, как вендор сам об этом сказал.

Вот это и есть ответ на «почему ничего не запустилось»: по каждому профилю названа причина.
Ни одного токена не потрачено.

Когда профиль подходит, `--dry-run` резолвит вендора и **пишет на диск точный промпт**,
который был бы отправлен, но вендора не вызывает:

```
.warden/runs/dry/prompts/reviewer.md      ← есть
.warden/runs/dry/raw/                     ← нет, вендор не запускался
```

---

## Шаг 4. Красная половина: гейты падают до работы

```
warden gates hello --run-id red
→ {"ok":false,"code":"command_failed"}
```

Так и должно быть: `src/result.txt` ещё нет. Это доказывает, что гейт не «подыгрывает».

## Шаг 5. Реализатор делает работу

```
warden role implementer hello --run-id live
→ {"ok":true,"profile":"stub-impl","vendor":"stubvendor"}
```

## Шаг 6. Зелёная половина

```
warden gates hello --run-id green
→ {"ok":true,"code":"passed"}
```

## Шаг 7. Независимое ревью

```
warden role reviewer hello --run-id live --implementer-vendor stubvendor
→ {"ok":true,"profile":"stub-review","vendor":"othervendor",
   "rejected_profiles":{"stub-sneaky":"same_vendor_as_implementer"}}
```

Ревьюер того же вендора, что писал код, **отклонён механически**. Это единственное
защитимое обоснование мультивендорности: модель, ревьюящая собственный вывод, делит с ним
слепые зоны. Ограничение жёсткое — если независимого вендора нет, резолвер падает, а не
тихо отдаёт код на ревью его же автору.

---

## Шаг 8. Что происходит, если read-only роль всё-таки пишет

Подставной ревьюер, который дописывает строку в файл и при этом возвращает `verdict: pass`:

```
warden role reviewer hello --run-id trap
→ {"ok":false,"code":"role_violated_read_only"}
```

```json
"read_only_check": {
  "method": "worktree_content_fingerprint",
  "covers": "tracked edits, deletes, renames and untracked file contents",
  "does_not_cover": "paths ignored by .gitignore",
  "matched": false
}
```

Артефакт **не записан**, что бы он ни утверждал. Сравнение идёт по отпечатку содержимого, а
не по списку путей: в уже грязном дереве — а это норма после работы реализатора — правка
существующего файла список путей не меняет, и проверка по именам упала бы открытой.

Непокрытый случай назван прямо в отчёте каждого прогона, а не спрятан в документации: пути
из `.gitignore` git не видит, значит и проверка не видит.

---

## Шаг 9. Что осталось в качестве улик

```
.warden/runs/live/
  prompts/implementer.md            точный отправленный промпт
  raw/implementer.stdout.txt        сырой вывод вендора, всегда
  artifacts/implementer.json        разобранный артефакт
  role-implementer.json             ledger
  evidence.jsonl                    append-only лента событий
```

В ledger попадает то, что не требует эксперимента — оно накапливается само:

```
role            "implementer"      cost_usd         0.012
profile         "stub-impl"        num_turns        3
vendor          "stubvendor"       duration_millis  3
model_reported  "stub-impl-model"  prompt_sha256    37c3698c…
                                   artifact_sha256  94016435…
```

---

## Шаг 10. Что происходит, когда кончилась подписка

Этот шаг сделан не на подставных вендорах. У аккаунта Codex к этому моменту была исчерпана
квота, и это оказался самый удобный из возможных стендов: настоящий отказ, который не надо
изображать.

Политика — два ревьюера подряд, Codex первым **намеренно**:

```yaml
roles:
  reviewer:
    profiles: [codex-review, grok-review]
    strategy: first
```

```
warden role reviewer create-result --run-id live-stdin
→ {"ok":true,"profile":"grok-review","vendor":"grok",
   "rejected_profiles":{"codex-review":"quota_exhausted_this_run"}}
```

Роль выполнена, хотя первый вендор до неё не дошёл. В отчёте лежат обе попытки:

```json
"vendor_attempts": [
  {"attempt":1,"profile":"codex-review","vendor":"codex","code":"role_quota_exhausted",
   "quota":{"matched_signature":"usage limit","detected_by":"structured_error_event",
            "vendor_message":"You've hit your usage limit. … or try again at 9:21 PM."}},
  {"attempt":2,"profile":"grok-review","vendor":"grok","code":"ok","cost_usd":0.0259}
]
"failed_over_from": ["codex-review"]
```

Почему это отдельный код, а не просто «упал». По коду выхода отличить нельзя: Codex отдаёт
`1` и на исчерпанную квоту, и на опечатку во флаге. Разница в том, что делать дальше.
Обычную ошибку правильно вернуть тому же вендору вместе с текстом падения; исчерпанную
подписку — нельзя, следующий вызов откажет ровно так же, и петля потратит остаток бюджета,
чтобы это выяснить. Квота — единственное падение, на которое правильный ответ это **другой
вендор**.

Три решения, которые видно в этом выводе:

- **Срок ретрая записан дословно и не разобран.** Вендор сказал «try again at 9:21 PM»: ни
  даты, ни зоны. Любой timestamp, который Warden отсюда вывел бы, был бы догадкой в виде
  факта.
- **Исключение живёт в памяти процесса, не в файле.** Внутри одной петли вендор, кончившийся
  на реализации, не будет вызван на ревью. Новый запуск начинает с чистого листа: квота,
  сбросившаяся ночью, не должна оставаться выключенной вчерашним файлом.
- **Бюджет считает вызовы вендора, а не роли.** Failover добавляет вызов; если бы бюджет его
  не видел, одна задача с двумя переключениями заплатила бы втрое от разрешённого.

Распознавание идёт по формулировкам вендора — другого сигнала нет, — поэтому источники
ранжированы. Структурное событие (`{"type":"error"}`) авторитетно; stderr принимается как
более слабое `stderr_text`; **свободный текст stdout не рассматривается вообще**. Там живут
слова самой модели, и ревью, которое разбирает код про rate limiting и при этом падает, не
должно быть принято за ревью, упёршееся в лимит. Цена этого решения — ложноотрицательные:
вендор, который сообщает о квоте только прозой на stdout, будет отмечен как обычное падение.
Поэтому в отчёт о падении теперь попадает хвост **обоих** потоков: даже нераспознанный отказ
оператор прочитает своими глазами.

Когда падать больше не на кого:

```
→ {"ok":false,"code":"role_quota_exhausted",
   "resolution":"every profile able to fill this role reported a spent subscription;
                 add a profile from another vendor, or wait for the quota window …"}
```

`warden run` останавливается с `reason: quota_exhausted`, а не `implementer_failed`. Это
разные события: одно зовёт читать транскрипт, в котором ничего не сломано, второе —
подождать окно.

---

## Шаг 11. Как первый живой запуск нашёл молчаливую порчу промпта

Тот же прогон вскрыл дефект, который тесты на заглушках увидеть не могли, потому что заглушка
— это `.exe`, а настоящий вендор из npm — это `.cmd`.

Windows не умеет запускать `.cmd` напрямую, он отдаёт его в cmd.exe, а у cmd.exe командная
строка построчная. Замер на этой машине:

```
передано:  [exec, "# Reviewer line one\nsecond line\nthird line", --json]
получено:  ARG1[exec]  ARG2[# Reviewer line one]  COUNT=2
```

Аргумент обрезан на первом переводе строки, **и все следующие аргументы молча выброшены**.
Тот же argv доходит до `.exe` целиком — поэтому Grok, у которого один `grok.exe`, ничего не
показывал, а Codex получил однострочный промпт с оторванным `--json` и выглядел работающим.
Заплатить за такой ответ и потом его судить хуже, чем не запускаться.

Сначала Warden вообще не доходил до вендора: `where.exe codex` первой строкой отдаёт
безрасширенный npm-шим, который Java запустить не может (`CreateProcess error=193`). Теперь
исполняемый файл выбирается по расширению, а не по порядку вывода `where.exe`.

Дальше — проверка перед запуском:

```json
{"ok":false,"code":"role_prompt_undeliverable",
 "argument_delivery_check":{
   "executable_is_batch_shim":true,
   "covers":"multi-line arguments passed through cmd.exe, which truncates them at the
             first newline and drops every argument after it",
   "does_not_cover":"cmd.exe expansion of %NAME% inside an argument",
   "undeliverable_arguments":["argv[2] (3660 chars)"],
   "resolution":"set prompt_delivery: stdin on this profile, or pass {{prompt_file}} …"}}
```

Ничего не потрачено, причина названа, и непокрытый случай — раскрытие `%NAME%` внутри
аргумента — назван там же, а не спрятан в документации.

Рабочий канал для такого вендора один:

```yaml
args: ["exec", "-", "--json", "--skip-git-repo-check"]
prompt_delivery: stdin
```

stdin проходит через `.cmd`-шим неповреждённым — это проверено запуском, а не выведено.

---

## Шаг 12. Тестировщик, у которого есть глаза

Тестировщик здесь двухслойный, и слои отвечают на разные вопросы.

**Нижний слой — харнесс.** Настоящий headless-браузер по CDP, ноль npm-зависимостей.
Сценарий пишется в контракте задачи:

```yaml
visual_qa:
  required: true
  start: "npm run preview -- --host 127.0.0.1 --port 4173"
  url: "http://127.0.0.1:4173/"
  scenarios:
    - "1280x720: text=Save visible"
    - "1280x720: testid=save-button click -> css=.panel.open visible"
    - "700x400: css=#wide-only hidden"
    - "700x400: no-console-errors"
```

Матчеры: `text=` (по видимому тексту), `css=`, `testid=`, `role=`. Утверждения: `visible`,
`hidden`, `click`. Голый `click` проверяет, что нажатие вообще что-то меняет в DOM; всё
более конкретное пишется после `->`, а не угадывается.

Живой прогон на фикстуре:

```
warden visual-qa looks-right --run-id green
→ {"ok":true,"code":"passed"}

.warden/runs/green/screenshots/
  1280x720.png
  1280x720-after-click.png
  700x400.png
  visual-qa.json
```

**Верхний слой — роль `visual_qa`.** Модель, которой скриншоты приходят **вложениями**:

```yaml
attachments:
  flag: "-i"          # codex: `-i shot.png -i after-click.png`
```

Это и есть разница между «модели прислали имена файлов» и «модель посмотрела». Warden
записывает в отчёт, какой из двух случаев произошёл: профиль без `attachments.flag`
получает пометку `attachments_not_passed_to_vendor`, а не тихое повышение до «посмотрел».

Роль запускается только после того, как харнесс уже согласился, и отвечает лишь на то, что
машина измерить не может: обрезанный текст, наложение, развалившаяся раскладка. Её находки
имеют ту же форму, что у ревьюера, поэтому возвращаются реализатору тем же механизмом.

По умолчанию роль **выключена** в политике. Харнесс — пол, и он бесплатный; взгляд модели
стоит вызова вендора, и это решение оператора, а не петли.

---

## Шаг 13. Три вещи, которые харнесс перестал делать молча

Каждая найдена запуском, не рассуждением.

**Он тестировал чужое приложение.** На `127.0.0.1:4173` уже висело постороннее
SvelteKit-приложение из другого проекта. Харнесс увидел, что порт отвечает, свой сервер не
поднял и снял скриншоты чужой страницы. В тот раз проверка упала — но с тем же успехом могла
пройти.

```
→ {"ok":false,"code":"visual_qa_port_occupied"}
   "something is already answering at http://127.0.0.1:4173/, and this task declares its own
    start command. Warden will not guess whether that server is this project or a stale one
    from another: stop it, change visual_qa.url to a free port, or drop visual_qa.start."
```

**Он отчитывался о виюпорте, которого не было.** Мобильная эмуляция включалась сама при
ширине меньше 800, а страница без `<meta name="viewport">` раскладывается в мобильном режиме
в 980px. Сценарий `700x400` рендерился при 980 и назывался `700x400`. Замер:

```
asked for 700  →  viewport_effective: {"width": 980}  →  viewport_honoured: false
```

Теперь `mobile` включается явно (`"700x400 mobile: ..."`), а несовпадение объявленной и
фактической ширины — провал сценария с объяснением, а не тихая подмена.

**Он знал название чужой кнопки.** В коде харнесса жили `.mode-switch`, `.mobile-view-note`
и `/growing/i` — приватный DOM одного приложения внутри инструмента, который называется
общим. А `warden do` для любой UI-цели без явного имени контрола подставлял сценарий с
кнопкой `Create` — той самой, из того же приложения. Угаданный критерий приёмки — та же
ошибка, что угаданный blast radius, только полем правее. Теперь, если цель не называет
контрол, пишется то, что верно для любой страницы:

```yaml
  scenarios:
    # Warden does not invent visual assertions. These two hold for any page:
    #   - "1280x720: text=Settings visible"
    - "1280x720: no-console-errors"
    - "700x400: no-console-errors"
```

Слабая честная проверка, которая всё равно снимает скриншот для роли `visual_qa`, лучше
уверенной неправильной.

---

## Шаг 14. Цель по-русски

```
warden do "почини вёрстку экрана настроек" --scope ui
→ {"ok":false,"code":"goal_mangled_by_console_encoding",
   "message":"the goal arrived as \"?????? ??????? ?????? ????????\" …"}
```

JVM декодирует аргументы командной строки через `sun.jnu.encoding`; на этой машине это
`Cp1252`, и кириллица становится `?` до входа в `main`. Проверено: `-Dsun.jnu.encoding=UTF-8`,
`JDK_JAVA_OPTIONS` и `chcp 65001` не меняют ничего. Поэтому испорченная цель отклоняется, а не
записывается в контракт, и есть канал, который работает:

```
warden do --goal-file goal.txt --scope ui
→ goal: "почини вёрстку экрана настроек"
   visual_qa: required: true
```

`warden doctor` печатает это состояние в `argument_encoding`, чтобы узнавать о нём до того,
как контракт окажется в вопросительных знаках, а не после.

---

## Как подставить настоящего вендора

Заменить в профиле одну строку:

```yaml
command: /tmp/vendors/review.sh     →     command: grok
```

и, для непроверенного профиля, выполнить его `verification.probe`, убедиться в четырёх
пунктах из `what_to_check` и проставить `verified_on`. До этого резолвер профиль не выпустит.

**Порядок для Codex важен отдельно.** Это единственная роль с `read_only: false`, то есть
непроверенный флаг автоаппрува у неё — самое рискованное место во всём пайплайне. Сначала
прогнать пробу standalone, потом `--dry-run`, и только потом дать петле им управлять.

---

## Чего этот разбор не показывает

Всё выше сделано на заглушках и паре мелких живых проб. С тех пор вся цепочка была прогнана
end-to-end на настоящих вендорах и настоящем проекте: пишущий реализатор, машинные гейты,
независимый ревьюер на втором вендоре, браузерный харнесс и роль `visual_qa`, которая
выставила P1, недоступный машине, и заказала раунд починки. Это другой документ —
[`LIVE-CYCLE.md`](LIVE-CYCLE.md), со стоимостями, токенами и вердиктами.

Чего по-прежнему нет нигде, потому что это не сделано:

- **Живого цикла роли через `runner: orca`.** Адаптер написан по контракту Orca CLI: worktree
  не создаёт, completion только из `worker_done` / dispatch settlement. Пока это не прогнано
  на живом worker, это не доказанный путь. Orca в живых прогонах worktree *создавала* — но это
  `OrcaIsolation`, другой путь кода.
- **Визуального pixel-diff и baseline.** Харнесс проверяет утверждения, а не сравнивает
  изображения; задача с `visual_qa.required: true` и без браузера падает
  `visual_qa_unavailable`, а не проходит молча.
- **Распознавания квоты по свободному тексту stdout.** Сделано намеренно (Шаг 10): ложное
  срабатывание там стоит денег, ложный пропуск — нет.
