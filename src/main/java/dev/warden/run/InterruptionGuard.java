package dev.warden.run;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides who writes a run's conclusion when the controller is stopped while a stage runs.
 *
 * <p>A controller stopped by Ctrl+C or SIGTERM used to leave no summary and no decision:
 * the vendor census was killed, the JVM exited, and {@code --continue} refused with
 * {@code unknown_run}. Measured 2026-10-28 on hello-world-test: implement and gates had
 * passed, the review was interrupted, and the only way on was a new run that paid the
 * implementer again. Exactly one side concludes the run: the main thread when it gets there
 * first, otherwise the shutdown hook, which records the stages that already finished so a
 * continuation can keep them.</p>
 */
final class InterruptionGuard implements AutoCloseable {
    private final AtomicBoolean concluded = new AtomicBoolean();
    private final Thread hook;
    private final boolean installed;

    InterruptionGuard(Runnable onInterrupt, boolean install) {
        this.hook = new Thread(() -> {
            if (!concluded.compareAndSet(false, true)) return;
            try {
                onInterrupt.run();
            } catch (RuntimeException failed) {
                System.err.println("warn  could not record the interrupted run: " + failed.getMessage());
            }
        }, "warden-run-interrupted");
        this.installed = install;
        if (install) Runtime.getRuntime().addShutdownHook(hook);
    }

    /** Claims the conclusion for the main thread; false when shutdown has already taken it. */
    boolean conclude() {
        return concluded.compareAndSet(false, true);
    }

    /** What the shutdown hook does, callable directly by tests. */
    void interrupt() {
        hook.run();
    }

    /**
     * Holds a main thread that lost the race. The hook is writing the summary and the
     * decision; a second conclusion from this thread would overwrite them, and the JVM halts
     * once the hook returns.
     */
    static void yieldToShutdown() {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignored) {
                // Keep waiting: the JVM is exiting.
            }
        }
    }

    @Override
    public void close() {
        if (!installed) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // The hook already ran or is running.
        }
    }
}
