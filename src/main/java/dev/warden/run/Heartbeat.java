package dev.warden.run;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A sign of life from a stage that has none of its own.
 *
 * A role is one subprocess that prints nothing until it is finished. The narration says
 * `dispatching, up to 60 min` and then — on the longest stage measured so far — said nothing
 * at all for twenty-two minutes. From the outside those twenty-two minutes are
 * indistinguishable from a hung vendor, from a loop that died, and from a finished run whose
 * last line has not flushed; the only way to tell was to go and stat files in the run
 * directory. The operator's complaint was narrower and fairer than that: the tab says the run
 * is going, and never says which role is going or how long it has been. On a roster where the
 * implementer can be grok or Codex, the role name is the one thing they already knew.
 *
 * So the loop says it out loud, once a minute, to both surfaces at once — a line in the
 * narration for whoever is watching the terminal, and the live role on the workspace card and
 * the tab for whoever is looking at a phone. When a vendor is filling the role, the beat
 * names that profile and vendor as well; a stage that runs no vendor is still just `gates`
 * or `browser harness`, with no empty pair of brackets.
 *
 * The profile is not chosen here. Resolution lives in the role runner and advances rotation
 * state, so a second lookup could name a profile that was not the one dispatched. The name
 * arrives via {@link #filledBy} from the resolution that actually happened, in time for the
 * first beat of the stage.
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
    private final AtomicReference<Who> who;

    private Heartbeat(Thread thread, AtomicBoolean beating, AtomicReference<Who> who) {
        this.thread = thread;
        this.beating = beating;
        this.who = who;
    }

    /** No beats. What a dry run gets, and what any caller can use in place of a null check. */
    public static Heartbeat none() {
        return new Heartbeat(null, new AtomicBoolean(false), new AtomicReference<>(new Who("")));
    }

    public static Heartbeat over(String who, Progress progress, Workspace workspace) {
        return over(who, progress, workspace, EVERY);
    }

    /** The interval is a parameter so a test can watch a stage beat without waiting a minute. */
    public static Heartbeat over(String who, Progress progress, Workspace workspace, Duration every) {
        long started = System.nanoTime();
        AtomicBoolean beating = new AtomicBoolean(true);
        AtomicReference<Who> name = new AtomicReference<>(new Who(who));
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(every.toMillis());
                    // Checked after the sleep, not before it: the stage can end at any point
                    // inside that minute, and a beat written after it ended is a beat that
                    // contradicts the result printed underneath it.
                    if (!beating.get()) return;
                    beat(name.get(), (System.nanoTime() - started) / 1_000_000L, progress, workspace);
                }
            } catch (InterruptedException stageIsOver) {
                // close() is the only way out of that loop, and the line that follows this one
                // in the narration is the stage's own result. Nothing to add.
            }
        });
        return new Heartbeat(thread, beating, name);
    }

    /**
     * The profile the resolution that is about to dispatch actually chose.
     *
     * Safe to call on {@link #none()}, after {@link #close()}, and with a blank profile: those
     * are the cases where nobody is filling the role, and inventing a pair of brackets would
     * be a lie. A later call replaces an earlier one, so a failover names who is working now.
     */
    public void filledBy(String profile, String vendor) {
        if (thread == null) return;
        if (profile == null || profile.isBlank()) return;
        who.updateAndGet(current -> current.filling(profile, vendor));
    }

    /**
     * `implementer (grok-implement / grok)`, or just `implementer` when nobody was resolved.
     *
     * The narration and the card both use this. Empty brackets are never produced: a stage
     * that runs no vendor, or a role whose verdict was carried over and dispatched nobody,
     * stays the bare name.
     */
    public static String spoken(String role, String profile, String vendor) {
        if (role == null || role.isEmpty()) return "";
        if (profile == null || profile.isBlank()) return role;
        if (vendor == null || vendor.isBlank()) return role + " (" + profile + ")";
        return role + " (" + profile + " / " + vendor + ")";
    }

    /**
     * The tab is short. Profile only: `implementer (grok-implement)`. A tab that also carried
     * the vendor would repeat what the profile name already says, and the one surface an
     * operator reads without clicking anything is the one that runs out of width first.
     */
    public static String tab(String role, String profile) {
        if (role == null || role.isEmpty()) return "";
        if (profile == null || profile.isBlank()) return role;
        return role + " (" + profile + ")";
    }

    /**
     * `      ... 12m00s   implementer (grok-implement / grok) still working`.
     *
     * Indented and prefixed like every other continuation line the loop prints, so a minute of
     * silence reads as part of the stage above it rather than as a new event. ASCII, like the
     * rest of the narration: this goes to a Windows console as well as to a UTF-8 file, and a
     * character that arrives there as `?` is a character that was not worth the ellipsis.
     */
    private static void beat(Who who, long millis, Progress progress, Workspace workspace) {
        try {
            progress.line("      ... " + Progress.elapsed(millis) + "   " + who.spoken() + " still working");
            workspace.working(who.tab(), millis);
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

    private static final class Who {
        private final String role;
        private final String profile;
        private final String vendor;

        Who(String role) { this(role, null, null); }

        Who(String role, String profile, String vendor) {
            this.role = role == null ? "" : role;
            this.profile = profile;
            this.vendor = vendor;
        }

        Who filling(String profile, String vendor) {
            return new Who(role, profile, vendor);
        }

        String spoken() { return Heartbeat.spoken(role, profile, vendor); }

        String tab() { return Heartbeat.tab(role, profile); }
    }
}
