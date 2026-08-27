package dev.warden.role;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `{{placeholder}}` substitution for prompts and vendor argument lists.
 *
 * An unknown placeholder is an error, never left in place. A template that quietly sends
 * `{{tsak_id}}` to a model produces a plausible-looking review of the wrong thing, and the
 * mistake is invisible in the output. Failing here costs a second; failing later costs a
 * vendor run and a wrong verdict.
 */
public final class PromptRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private PromptRenderer() {}

    public static class UnknownPlaceholderException extends RuntimeException {
        private final Set<String> unknown;
        UnknownPlaceholderException(String source, Set<String> unknown, Set<String> known) {
            super(source + ": unknown placeholder(s) " + unknown + "; available: " + known);
            this.unknown = unknown;
        }
        public Set<String> unknown() { return unknown; }
    }

    public static String render(String template, Map<String, String> values, String source) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.get(key);
            if (replacement == null) {
                unknown.add(key);
                replacement = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (!unknown.isEmpty()) {
            throw new UnknownPlaceholderException(source, unknown, new LinkedHashSet<>(values.keySet()));
        }
        return out.toString();
    }

    /** True when the template refers to this placeholder at all. */
    public static boolean uses(String template, String key) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) return true;
        }
        return false;
    }
}
