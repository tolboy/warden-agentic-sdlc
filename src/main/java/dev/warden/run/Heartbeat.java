package dev.warden.run;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A sign of life from a stage that has none of its own.
 *
 * A role is one subprocess that prints nothing until it is finished. The narration says
 * `dispatching, up to 60 min` and then — on the longest stage measured so far — said nothing
 * at all for twenty-two minutes. From the outside those twenty-two minutes are
 * indistinguishable from a hung vendor, from a loop that died, and from a finished run whose
 * last line has not flushed; the only way to tell was to go and stat files in the run
 * directory. The operator's complaint was narrower and fairer than that: the tab says the run
 * is going, and never says which role is going or how long it has been.
 *
 * So the loop says it out loud, once a minute, to both surfaces at once — a line in the
 * narration for whoever is watching the terminal, and the live role on the workspace card and
 * the tab for whoever is looking at a phone.
 *
 * <p><b>Not evidence, and not allowed to cost anything.</b> Every beat restates the two facts
 * the ledger will hold anyway: that this stage ran, and for how long. A beat that could not be
 * written is dropped rather than retried, a beat that throws is swallowed rather than raised,
 * and a dry run has no beats at all because nothing is working during one.</p>
 */
public final class Heartbeat implements AutoCloseable {

    /**
     * A minute.
     *
     * Short enough to answer "is this alive" before a person goes looking, long enough that an
     * hour-long role adds sixty lines to a narration rather than a thousand — and that the
     * board behind it is asked for sixty updates rather than a thousand, over a desktop app
     * whose CLI costs a process launch per call.
     */
    public static final Duration EVERY = Duration.ofMinutes(1);

    private final Thread thread;
    private final AtomicBoolean beating;

    private Heartbeat(Thread thread, AtomicBoolean beating) {
        this.thread = thread;
        this.beating = beating;
    }

    /** No beats. What a dry run gets, and what any caller can use in place of a null check. */
    public static Heartbeat none() { return new Heartbeat(null, new AtomicBoolean(false)); }

    public static Heartbeat over(String who, Progress progress, Workspace workspace) {
        return over(who, progress, workspace, EVERY);
    }

    /** The interval is a parameter so a test can watch a stage beat without waiting a minute. */
    public static Heartbeat over(String who, Progress progress, Workspace workspace, Duration every) {
        long started = System.nanoTime();
        AtomicBoolean beating = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(every.toMillis());
                    // Checked after the sleep, not before it: the stage can end at any point
                    // inside that minute, and a beat written after it ended is a beat that
                    // contradicts the result printed underneath it.
                    if (!beating.get()) return;
                    beat(who, (System.nanoTime() - started) / 1_000_000L, progress, workspace);
                }
            } catch (InterruptedException stageIsOver) {
                // close() is the only way out of that loop, and the line that follows this one
                // in the narration is the stage's own result. Nothing to add.
            }
        });
        return new Heartbeat(thread, beating);
    }

    /**
     * `      ... 12m00s   implementer still working`.
     *
     * Indented and prefixed like every other continuation line the loop prints, so a minute of
     * silence reads as part of the stage above it rather than as a new event. ASCII, like the
     * rest of the narration: this goes to a Windows console as well as to a UTF-8 file, and a
     * character that arrives there as `?` is a character that was not worth the ellipsis.
     */
    private static void beat(String who, long millis, Progress progress, Workspace workspace) {
        try {
            progress.line("      ... " + Progress.elapsed(millis) + "   " + who + " still working");
            workspace.working(who, millis);
        } catch (RuntimeException | Error notOurProblem) {
            // A stage must not die because nobody could be told it is still going.
        }
    }

    @Override
    public void close() {
        beating.set(false);
        if (thread == null) return;
        thread.interrupt();
        try {
            // Bounded: a beat blocked on a desktop app that stopped answering is not a reason
            // to hold the stage's own result back behind it.
            thread.join(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
