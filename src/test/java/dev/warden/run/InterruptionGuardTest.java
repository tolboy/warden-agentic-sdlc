package dev.warden.run;

import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.concurrent.atomic.AtomicInteger;

/** An interrupted controller concludes its run exactly once, and never as a verdict. */
public final class InterruptionGuardTest implements Suite {
    @Override public String name() { return "interruption-guard"; }

    @Override public void run(Check check) {
        AtomicInteger recorded = new AtomicInteger();
        try (InterruptionGuard guard = new InterruptionGuard(recorded::incrementAndGet, false)) {
            guard.interrupt();
            check.eq("a signal before the run concludes records it", 1, recorded.get());
            check.that("and the main thread then yields its conclusion", !guard.conclude());
            guard.interrupt();
            check.eq("a second signal records nothing more", 1, recorded.get());
        }

        AtomicInteger late = new AtomicInteger();
        try (InterruptionGuard guard = new InterruptionGuard(late::incrementAndGet, false)) {
            check.that("a run that concludes first keeps its own conclusion", guard.conclude());
            guard.interrupt();
            check.eq("and a later signal writes nothing over it", 0, late.get());
        }

        try (InterruptionGuard guard = new InterruptionGuard(() -> {
            throw new IllegalStateException("ledger locked");
        }, false)) {
            guard.interrupt();
            check.that("a failed record does not hand the conclusion back", !guard.conclude());
        }

        try (InterruptionGuard installed = new InterruptionGuard(() -> { }, true)) {
            check.that("an installed hook is removed when the run concludes", installed.conclude());
        }

        check.eq("an interrupted controller is not a verdict on the work", false,
                TaskLoop.isAboutTheWork(TaskLoop.CONTROLLER_INTERRUPTED));
        check.eq("under a stable reason", "controller_interrupted", TaskLoop.CONTROLLER_INTERRUPTED);
    }
}
