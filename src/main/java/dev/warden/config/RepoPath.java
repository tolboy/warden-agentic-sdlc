package dev.warden.config;

/**
 * Repository-relative path validation.
 *
 * Every path in a config eventually becomes a blast-radius boundary, so a path that escapes
 * the repository is a security question, not a formatting one. Normalisation is deliberately
 * conservative: anything with `..`, a drive letter, a leading slash or a glob character is
 * rejected outright rather than cleaned up, because "cleaning up" a path the author did not
 * intend is how a boundary check ends up guarding the wrong directory.
 */
public final class RepoPath {

    /**
     * The one spelling of "every path in this repository". It is a reserved token, not a
     * path, precisely so it cannot be arrived at by accident: `.` and `*` are refused, and a
     * blast radius that silently means everything is the failure mode this whole class exists
     * to prevent. It is written by `warden init` only for a project that has no files yet,
     * where there is no existing code for a boundary to protect.
     */
    public static final String WHOLE_REPOSITORY = "<repository>";

    private RepoPath() {}

    /** Returns the normalised path, or null when it is not a safe repository-relative path. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.strip().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty() || value.equals(".")) return null;
        if (value.startsWith("/")) return null;
        if (value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') return null;
        for (char c : new char[] {'\0', '*', '?', '[', ']'}) {
            if (value.indexOf(c) >= 0) return null;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return value;
    }

    /** True when {@code candidate} is the allowed path itself or lives beneath it. */
    public static boolean isWithin(String candidate, String allowed) {
        return candidate.equals(allowed) || candidate.startsWith(allowed + "/");
    }

    /** Git revision names that are safe to hand to `git rev-parse`. */
    public static boolean isSafeRef(String ref) {
        return ref != null
                && ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
                && !ref.contains("..")
                && !ref.contains("//");
    }

    /** Slugs used for ids and run directories; also keeps them safe as path segments. */
    public static boolean isSlug(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{0,79}");
    }

    public static boolean isRunId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    }
}
