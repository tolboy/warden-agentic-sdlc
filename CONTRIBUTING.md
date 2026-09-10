# Contributing to Warden

Thanks for looking. This file says what the project will and will not accept, so you can
find out before you write the patch rather than after.

## The one rule that shapes everything else

**Warden has zero runtime and build dependencies, and it stays that way.**

A tool that hands a language model write access to someone's repository should not drag a
hundred-artifact supply chain in behind it, and it should build with no network. That is why
there is no Gradle, no Maven, no test framework, no JSON library and no YAML library — there
is a `javac` invocation and a `main`. A pull request that adds a dependency will be declined
regardless of how good it is, unless it first argues successfully that the rule should change.

The same rule is why the YAML and JSON parsers here are deliberately small: they accept a
strict subset and reject the rest loudly. Widening the subset is welcome; replacing it with
a library is not.

## Build and test

```bash
./build.sh && ./test.sh
```

```text
build.cmd && test.cmd
```

Both produce `out/classes` and `out/test-classes` and run `dev.warden.testing.TestMain`.
There is no watch mode and no incremental build; a full compile takes a couple of seconds.

Compilation targets `--release 21` — a floor, not a ceiling. The bytecode runs on any newer
JVM, and CI builds it on JDK 21 and 25 across Linux and Windows. Override the floor with
`WARDEN_JAVA_RELEASE` if you are testing something specific, but do not raise it in a PR
without a reason in the description.

The hygiene gate is a script rather than a block of CI, so you can run it before you push:

```bash
scripts/hygiene.sh tip paths
scripts/hygiene.sh range paths "$(git merge-base origin/main HEAD)" HEAD
```

`tip` reads the tree you have; `range` reads the lines each commit in that range *added*, which
is the check that catches a personal path introduced by one commit and reworded away by the
next. Substitute `uuids` for `paths` to run the other check. CI runs all four.

`scripts/hygiene-selftest.sh` checks the gate itself against throwaway repositories that
contain the things it is supposed to reject. Run it after changing a pattern. Note that it has
to write the strings the gate rejects while being scanned by that gate, so its fixtures are
assembled from fragments; a new one has to keep doing that.

## Tests

The suite is hand-rolled: `src/test/java/dev/warden/testing/` gives you `Suite` and `Check`,
and `TestMain` runs everything. To add tests, add a class next to the existing ones and
register it in `TestMain`.

Two conventions matter more than coverage numbers:

- **A test should describe a defect that could plausibly ship**, not a method that exists.
  Read the existing test names; they read like the bug they prevent.
- **Never test a vendor by calling one.** `StubVendor` exists so the loop can be exercised
  end to end with no network, no credentials and no cost. Everything in `src/test` must run
  offline on a bare JDK.

## What a good change looks like

Warden's failure modes are its interface. Every failure resolves to exactly one named
outcome, and which one it resolves to is a decision made in code — see *What a failure is
allowed to do* in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). If your change introduces a
new way for something to go wrong:

1. Give it a name in that table, and say why the *other* plausible response is wrong.
2. Make it fail closed. Where a check cannot run, the answer is "refused", never "passed".
3. Leave evidence. Anything a human would need to understand the outcome belongs in the
   ledger, not only in the terminal.

Section 8 of [`spec/SPEC.md`](spec/SPEC.md) lists the invariants the implementation must hold.
Each one is a defect that was found by running the previous JavaScript version, not a
principle someone liked the sound of. A change that breaks one needs to say so out loud.

## Commit messages

The history here is meant to be readable as a record of what was learned. The form is
`type(scope): a claim`, where the claim states the *insight*, not the file touched:

```text
fix(visual-qa): a liveness probe that leaks a connection pool can lie about the port
feat(execution): a turn ceiling is not a failure at the work
fix(gate): a human accepts a source change, not Warden's own evidence
```

`chore: update deps` conveys nothing and there are no deps. Write the sentence you would say
to a colleague who asked why the commit exists.

## Documentation

English is the source of truth. `README.ru.md` and `spec/ru/` are translations,
kept because a lot of the phrasing was worked out in Russian first.

If you change behaviour, update the English document that describes it in the same commit.
If you can also update the Russian one, do; if you cannot, say so in the PR and it will be
handled — an out-of-date translation is a smaller problem than an out-of-date README, but
both are problems.

Documentation claims are held to the same standard as code: **do not write that something
works until it has been run.** `docs/ARCHITECTURE.md` distinguishes *implemented* from
*verified live*, and `docs/LIVE-CYCLE.md` is a transcript rather than a description. Keep
that separation.

## Pull requests

- One concern per PR. The loop has a lot of moving parts and a mixed diff is hard to judge.
- Say what you ran. "Tests pass" is fine for a unit change; anything touching the role loop,
  the harness or the human gate should say which command you executed and what it printed.
- If you cannot run a live vendor, say that too. Nobody expects a contributor to spend money
  on someone else's project — the stub path exists precisely so most work does not need to.
- Expect questions about *why the other behaviour is wrong*. That is not gatekeeping; it is
  the same question every existing decision in this repository had to answer.

## Reporting bugs

Include the command you ran, the `.warden/runs/<run-id>/` outcome (`run.json` and
`evidence.jsonl` are safe to paste; raw vendor transcripts often are not), your OS, and
`java -version`. `warden doctor` prints most of the environment in one go.

**Redact before pasting.** Run directories can contain your source, your prompts and your
vendor's replies.

Security issues do not go in the issue tracker — see [`SECURITY.md`](SECURITY.md).
