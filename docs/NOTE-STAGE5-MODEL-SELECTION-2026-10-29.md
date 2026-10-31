# Stage 5 — bounded Jev model selection — 2026-10-29

## Scope and controls

Model selection is an independent opt-in (`routing.model_selection_enabled`),
separate from effort routing (`routing.enabled`). Both default to off. Existing
settings that only name the effort flag keep their previous behavior. Saving one
control preserves the other setting.

Candidates come from the operator's declared role roster. The classifier receives
opaque `candidate_N` choices; it cannot name an undeclared model or connection.
Warden remains responsible for capability admission, availability, tool/context
requirements, explicit task/run choices and reviewer independence. Model selection
happens before execution; later attempts do not reclassify or expand the workflow.
Continuations retain chosen profiles separately from operator overrides while the
profiles remain in the role roster. Explicit new choices still take priority.
Role recommendations stay advisory, and mandatory reviews remain mandatory.

An explicit profile pin has priority. If no model answer meets the configured
confidence rule, the classifier fails, or a returned combination fails joint
validation, Warden retains the configured order. If no admitted candidate exists,
normal preflight produces an explainable refusal. Unknown model cost is retained as
unknown; it neither implies a free call nor independently excludes a candidate.
An explicit strict cost cap continues to govern admission and classifier spending.

Effort routing applies to the selected model's supported levels, rather than levels
from the model that happened to be first in the role's roster. Preparatory planning
keeps its own configured model and effort; this stage changes execution selection.

## Audit record and portable measurements

The local task-routing receipt contains `model_selection` with policy and candidate
snapshot hashes, per-stage decisions, alternatives, typed rejection reasons, typed
classifier distributions and joint-validation status. `suggested_profiles`,
`applied_profiles` and `effective_profiles` distinguish advice from what the final
explicit overlay allows to execute.

Each decision identifies the configured profile, selected profile/model, fixed
Warden selection reason and whether the profile was fixed by explicit settings or continuation. Alternatives
identify their opaque option, profile/model/vendor/runner, read-only capability,
effort, capability hash and cost status. A snapshot is identity evidence, not a
claim that an unavailable model was tested live.

Portable measurements retain these structured identities, hashes, typed choice
probabilities and outcomes. They exclude task text, prompts, generated explanations,
provider bodies, credential fields, private paths and environment/command content.
Unknown fields remain excluded. Dynamic probability maps admit only `candidate_N`
keys with finite values in [0, 1]; profile maps and rejected-candidate maps admit
only identifiers and typed codes. Free-form model rationale cannot substitute for a
fixed selection reason. The local receipt remains the detailed audit source.

## Live acceptance

Used isolated settings under `out/stage5-live/home`; personal configuration and
credentials were not changed. Candidate profiles were copied from the existing
verified local CLI configuration. Role wall clocks were narrowed to four minutes;
the task allowed two role calls, no fix rounds and six minutes of execution.

A manual `warden classify version --quiet` returned a valid typed choice between
`grok-implement` (`grok-4.7`) and `codex-implement` (`gpt-6.1-sol`). It chose Grok with
probabilities 0.8/0.2. The only reviewer candidate was `agy-review`
(`gemini-3.8-flash-high`). One classifier call reported 1,124 input / 39 output tokens
and $0.000047208. This manual recommendation dispatched no role or availability ping.

Then the actual Warden loop ran with model selection enabled and effort routing off:

1. All three candidate profiles passed fresh pongs.
2. Jev chose Grok again, probability 0.79 versus 0.21. The auxiliary concentration
   was 0.58; selection uses the maximum option probability against the configured
   0.6 threshold. The typed model/version matched. This separate launch made one
   classifier call: 1,148 input / 39 output tokens, $0.000048216.
3. Final readiness checked the selected Grok/AGY cast. Grok implemented the version
   function, syntax and the fixed Node test passed, and AGY returned `pass` with
   independent review assurance. No candidate failed checks or review.
4. `stage5-live-accepted-cast` reached **ready_for_human**, pending accept/reject,
   with exactly two role calls and 181 reported elapsed seconds. Nothing was accepted
   or landed. Grok reported $0.02700688; AGY supplied no price. Classifier and ping
   usage is separate from the role-call total; unknown prices are not zero.

The live selection stayed within the operator's configured roster and happened before
execution. It does not establish a general model-ranking quality or cost optimum.
The source refinements after this live run only tighten failed-check handling and
explicit host retention; the final regression covers them.

Local receipts: `out/stage5-live/selection.log`, `live-loop.log`, `result.json`,
`project/.warden/runs/stage5-live-accepted-cast/task-run.json`, `task-routing.json`,
role receipts and the pending decision. These ignored artifacts include no copied key
values. Two earlier zero-role fixture attempts stopped because the isolated settings
file omitted the required `check` mapping. The fixture was corrected; candidate-check
exceptions now produce a durable `model_selection_unavailable` stop without a classifier
or worker dispatch, covered by the local integration test.

## Regression and review

Production-loop tests exercise actual dispatch selection, fresh-check exclusion,
explicit pin priority, final rejection of dependent advice, classifier failure with
configured-order fallback, durable no-candidate/check-failure stops, mandatory gates
and review, and continuation reuse without another implementation dispatch.
Loopback Decisions API tests cover opaque choices, uncertainty/ties, malformed or
foreign labels, provider failure, joint independence and changed configuration.
Settings tests cover separate consent, legacy defaults, partial updates, revision
conflicts and absence of inference during save. Projection tests cover typed evidence
and exclusion of prompts, private paths and arbitrary generated explanations.

Final validation (Windows, Java release 21):

- Clean `cmd /c build.cmd`: exit 0.
- Model-selection, production-loop, settings, effort routing, overrides and workflow
  suites: **431 passed, 0 failed**, `out/stage5-routing-final.log`.
- Safe corpus projection, role runner and resolver suites: **379 passed, 0 failed**,
  `out/stage5-admission-final.log`.
- Generated model-settings JavaScript: `node --check` exit 0.
- Live model classification and the complete bounded worker/gates/review loop passed.
- Local document links and `git diff --check` passed. The full repository suite was not repeated.

The 810 reported checks are targeted executions for this change. The local loop
fixtures use injected role/readiness transports with real machine gates; the live
acceptance above separately exercised real model subscriptions and tool execution.

Read-only review found and fixed two admission issues: rejected alternatives were being
added back to final readiness, and pinned profiles needed the same effort admission as
ordinary candidates. The updated integration test refuses any appearance of the missing
fixture executable in readiness. Re-review found no remaining actionable issue in the
inspected paths. The full repository suite is not implied by these targeted checks.
