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
