package dev.warden.run;

import java.util.Locale;

/**
 * The live account of a run, for the person watching the terminal.
 *
 * Warden's stdout is one JSON object and stays that way: Conductor reads it, scripts read
 * it, and a stream of progress mixed into it would break both. This goes to stderr instead.
 *
 * Nothing printed here is evidence. Every line restates something the ledger already holds,
 * and a run that printed nothing is exactly as auditable as one that printed everything —
 * which is why this can be switched off without losing anything. It exists because a loop
 * that takes twenty minutes, says nothing at all, and then prints one JSON object is a black
 * box: the only way to find out where it was, or which vendor was busy, was to go and stat
 * files in four different run directories.
 */
public interface Progress {

    void line(String text);

    /** No output. The default everywhere, so tests and library callers stay silent. */
    Progress SILENT = text -> { };

    /** Progress on stderr, flushed per line so a piped run stays in step with the work. */
    static Progress toStderr() {
        return text -> {
            System.err.println(text);
            System.err.flush();
        };
    }

    /**
     * The same account, appended to a file as it happens.
     *
     * A run takes forty minutes and tells its story to one terminal. Close that terminal —
     * or start the run from a script, or over ssh, or from a scheduler — and the story is
     * gone. The ledger still holds every fact, which is what makes this safe to lose; what
     * it does not hold is the order a person watched them arrive in, or the sentence that
     * said which vendor was busy for sixteen minutes. Writing that costs nothing.
     *
     * Still not evidence, for exactly the reason the interface says: every line restates
     * something already in the ledger. It lives beside the evidence rather than in it, and
     * `runs/.gitignore` keeps it out of Git along with the raw transcripts.
     *
     * A failure to write is swallowed. A narration that cannot be saved must not end a run
     * that is otherwise going fine.
     */
    static Progress toFile(java.nio.file.Path file) {
        return text -> {
            try {
                // Created here rather than by the caller, so the sink has no ordering
                // dependency on whoever makes the run directory. The header lines are written
                // before the first stage, which is before the ledger has made anything.
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, text + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception notOurProblem) {
                // See above: the ledger is the record, this is the retelling.
            }
        };
    }

    /** Every sink, in order. Silent sinks cost nothing, so callers need no special case. */
    static Progress tee(Progress... sinks) {
        return text -> {
            for (Progress sink : sinks) sink.line(text);
        };
    }

    default void blank() { line(""); }

    /** `5m29s`, `18.5s`, `640ms` — a duration a person reads rather than converts. */
    static String elapsed(long millis) {
        if (millis < 0) return "?";
        if (millis < 1000) return millis + "ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        long minutes = millis / 60_000;
        long seconds = Math.round((millis % 60_000) / 1000.0);
        if (seconds == 60) { minutes++; seconds = 0; }
        return minutes + "m" + (seconds < 10 ? "0" : "") + seconds + "s";
    }

    /**
     * A cost the vendor reported, or `?`.
     *
     * Never `$0.00` for a missing figure. Most of these vendors are driven through a CLI on
     * a personal subscription and report no price at all, so a zero here would read as
     * "this call was free" for the one call nobody can price.
     */
    static String money(Object cost) {
        if (cost instanceof Number number) return String.format(Locale.ROOT, "$%.4f", number.doubleValue());
        return "?";
    }
}
