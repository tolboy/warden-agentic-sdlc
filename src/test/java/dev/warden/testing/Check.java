package dev.warden.testing;

import java.util.ArrayList;
import java.util.List;

/**
 * Assertions for the hand-rolled runner. No test framework is used because the tool ships
 * with zero dependencies, and a build that needs a network to run its own tests is a build
 * that cannot be trusted offline.
 *
 * A failed check records and continues, so one broken expectation does not hide the next
 * twenty.
 */
public final class Check {

    public record Failure(String suite, String description, String detail) {}

    private final List<Failure> failures = new ArrayList<>();
    private String suite = "";
    private int passed;

    public void beginSuite(String name) { this.suite = name; }

    public List<Failure> failures() { return failures; }
    public int passed() { return passed; }

    public void that(String description, boolean condition) {
        if (condition) passed++;
        else failures.add(new Failure(suite, description, "expected true"));
    }

    public void eq(String description, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) passed++;
        else failures.add(new Failure(suite, description, "expected <" + expected + "> but was <" + actual + ">"));
    }

    public void contains(String description, String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) passed++;
        else failures.add(new Failure(suite, description, "expected to contain <" + needle + "> in <" + haystack + ">"));
    }

    /** Asserts the body throws, and that the message mentions {@code messageFragment}. */
    public void rejects(String description, String messageFragment, ThrowingRunnable body) {
        try {
            body.run();
            failures.add(new Failure(suite, description, "expected a rejection but nothing was thrown"));
        } catch (Throwable thrown) {
            String message = String.valueOf(thrown.getMessage());
            if (messageFragment == null || message.contains(messageFragment)) passed++;
            else failures.add(new Failure(suite, description,
                    "rejected with <" + message + ">, expected it to mention <" + messageFragment + ">"));
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Throwable; }
}
