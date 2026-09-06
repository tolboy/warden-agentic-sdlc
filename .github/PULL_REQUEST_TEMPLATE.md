<!--
The history here is meant to read as a record of what was learned. Say what the change
claims, not which files moved — the diff already says that.
-->

## What this claims

<!-- One or two sentences. The insight, in the form you would say it to a colleague. -->

## Why it is true

<!--
What you observed, not what you expect. A run id, an outcome code, a transcript line, a
failing test before and after. "A claim someone else can check" is the standard the tool
holds its own reviewers to and the same one applies here.
-->

## What it costs

<!--
What gets slower, wider, or harder to explain. What a person could now do that they could
not before, including by mistake. A change with no stated cost usually has an unstated one.
-->

## Checklist

- [ ] `./build.sh && ./test.sh` (or `build.cmd && test.cmd`) passes locally.
- [ ] A test describes the defect this prevents, and fails without the change.
- [ ] No new dependency. There is no build system, no test framework, no JSON library and no
      YAML library, and that is a product claim rather than a preference.
- [ ] A new way to fail has a named outcome in the table in `docs/ARCHITECTURE.md`, and the
      entry says why the other plausible response is wrong.
- [ ] Where a check could not run, the result is "refused" and not "passed".
- [ ] Behaviour change: the English document that describes it is updated in the same commit.
      If you could not update the Russian translation too, say so here.
- [ ] An invariant in section 8 of `spec/SPEC.md` that this breaks is named out loud, with the
      argument for why the defect it was written against no longer applies.
