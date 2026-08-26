package dev.warden.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Every configuration error names the file, the field and what was expected.
 *
 * This is not politeness. The most common failure mode for a tool like this is not a crash
 * but a config that quietly means something its author did not intend, and the only defence
 * is refusing anything ambiguous with a message precise enough to act on.
 */
@SuppressWarnings("serial")
public class ConfigException extends RuntimeException {

    private final List<String> issues;

    public ConfigException(String source, List<String> issues) {
        super(source + ": " + String.join("; ", issues));
        this.issues = List.copyOf(issues);
    }

    public ConfigException(String source, String issue) {
        this(source, List.of(issue));
    }

    public List<String> issues() { return issues; }

    /** Collects issues so one run reports every problem, not just the first. */
    public static final class Collector {
        private final String source;
        private final List<String> issues = new ArrayList<>();

        public Collector(String source) { this.source = source; }

        public void add(String issue) { issues.add(issue); }
        public boolean hasIssues() { return !issues.isEmpty(); }
        public List<String> issues() { return issues; }

        public void throwIfAny() {
            if (!issues.isEmpty()) throw new ConfigException(source, issues);
        }
    }
}
