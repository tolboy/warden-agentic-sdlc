# Windows CI: measured cost and bounded improvements

The completed [main baseline](https://github.com/tolboy/warden-agentic-sdlc/actions/runs/39658036676)
on `99e1dc7` took 30m13s / 32m51s for Windows JDK 25 / 21, versus
5m18s / 4m54s for Linux. Those are whole-job durations, not per-suite timings.
The previous registry printed its result only at the end, making a long running
suite hard to distinguish from a stalled process.

Two contributors were identified from source and local diagnostics:

- The workflow built once explicitly, then `test.sh` / `test.cmd` built again.
  The workflow now invokes the test script once; it still builds production and
  test classes and runs the complete registered suite list in all four matrix jobs.
  A single local Windows build took about 13.2s. Removing that duplicate alone
  cannot explain or eliminate a half-hour job.
- Full local-run stack samples repeatedly reached Git fingerprint computation in
  `TaskLoopTest`. Each fingerprint launched four Git commands: raw base diff, base
  path diff, HEAD path diff and untracked paths. The raw diff already contains the
  base paths. Reusing those names removes one process per fingerprint.

The HEAD-relative diff is retained: a committed edit can be cancelled in the
working tree, leaving no base-relative diff while the HEAD-relative path still
belongs in the fingerprint. NUL framing preserves path contents. File bytes,
modes, untracked paths, evidence exclusions and SHA-256 input formatting are
unchanged. No fingerprint cache, reduced test selection or weaker writer fencing
was introduced. Malformed/incomplete raw framing fails closed.

## Local measurements

Windows 11, JDK 25.0.2, production bytecode compiled with `--release 21`.
The before/after implementation was loaded separately and run against the same
working tree, alternating order, after warmup. There were twelve samples per
version. Timing was repeated with Git tracing disabled; a separate trace verified
the process counts.

| Source fingerprint operation | Before | After |
| --- | ---: | ---: |
| Git processes, each traced sample | 4 | 3 |
| Median without tracing | 252.9 ms | 195.1 ms |

That is about **22.9% less time for this local operation**. It is not a measured
speedup of the complete hosted Windows CI job. New suite start/completion markers,
elapsed milliseconds and per-suite check counts make the next complete run useful
for locating the remaining cost. The final aggregate check-count line and exit
status remain unchanged.

The independent branch based on `main` compiled successfully and passed **437
focused checks, 0 failed** across runtime, land, approval-store, roster and seven
Orca suites. This combined count is not added to other runs. Coverage includes
the existing pinned candidate fingerprint and commit/mode invariants, plus new
HEAD-cancelled and unusual-path cases. A side-by-side probe also matched both
source and read-only fingerprints between the previous and new implementations.

The POSIX-only newline-path case and the full platform registry remain for CI;
this change does not claim a completed full CI matrix on the new head. Raw local
diagnostics are retained under `out/ci-performance/` and the earlier desktop
checkout's `out/desktop-contract/`; those ignored paths are not public links.
The [sanitized summary](evidence/ci-fingerprint-performance-2026-10-21.json)
contains only measurements and scope.

This work is independent of the desktop observer ownership blocker and can be
reviewed without merging the partial Orca UI branch. It does not add an Orca API,
change desktop cleanup guarantees or complete desktop acceptance.
