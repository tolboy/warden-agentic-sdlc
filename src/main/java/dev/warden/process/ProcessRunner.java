package dev.warden.process;

import dev.warden.execution.WorktreeLease;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

/** Runs a bounded child process and always settles, including when descendants keep pipes open. */
public final class ProcessRunner {
    public static final int DEFAULT_CAPTURE_BYTES = 64 * 1024;
    public static final long DEFAULT_RAW_BYTES = 64L * 1024 * 1024;

    private final Set<String> removedEnvironment;
    /** Applied after removals. The same name is put back, so one credential can be restored. */
    private final Map<String, String> environmentOverrides;

    public ProcessRunner() { this(Set.of(), Map.of()); }

    private ProcessRunner(Set<String> removedEnvironment, Map<String, String> environmentOverrides) {
        this.removedEnvironment = Set.copyOf(removedEnvironment);
        this.environmentOverrides = Map.copyOf(environmentOverrides);
    }

    /** A dispatch-scoped copy; neither this runner nor the JVM's environment is changed. */
    public ProcessRunner withoutEnvironment(Set<String> names) {
        Set<String> combined = new LinkedHashSet<>(removedEnvironment);
        combined.addAll(names);
        return new ProcessRunner(combined, environmentOverrides);
    }

    /** A security boundary also drops earlier explicit overrides for removed credentials. */
    public ProcessRunner withoutEnvironmentAndOverrides(Set<String> names) {
        Set<String> combined = new LinkedHashSet<>(removedEnvironment);
        combined.addAll(names);
        Map<String,String> kept = new LinkedHashMap<>(environmentOverrides);
        kept.keySet().removeIf(name -> combined.stream().anyMatch(removed -> windows()
                ? removed.equalsIgnoreCase(name) : removed.equals(name)));
        return new ProcessRunner(combined, kept);
    }

    /**
     * A dispatch-scoped copy that installs these variables after removals.
     * Values are credentials or generated config: never log the map.
     * An override wins over a removal of the same name so a harness can drop every
     * connection secret and then return exactly one.
     */
    public ProcessRunner withEnvironment(Map<String, String> overrides) {
        Map<String, String> combined = new LinkedHashMap<>(environmentOverrides);
        if (overrides != null) combined.putAll(overrides);
        combined.values().removeIf(java.util.Objects::isNull);
        return new ProcessRunner(removedEnvironment, combined);
    }

    private void removeEnvironment(Map<String, String> environment) {
        if (removedEnvironment.isEmpty()) return;
        if (windows()) {
            // Windows environment names are case-insensitive; inherited spelling need not
            // match the connection's reference. Never log names' associated values.
            environment.keySet().removeIf(name -> removedEnvironment.stream().anyMatch(name::equalsIgnoreCase));
        } else {
            removedEnvironment.forEach(environment::remove);
        }
    }

    private void applyEnvironment(Map<String, String> environment) {
        if (environmentOverrides.isEmpty()) return;
        for (Map.Entry<String, String> entry : environmentOverrides.entrySet()) {
            if (entry.getValue() == null) continue;
            if (windows()) {
                environment.keySet().removeIf(name -> name.equalsIgnoreCase(entry.getKey()));
            }
            environment.put(entry.getKey(), entry.getValue());
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public static final class CaptureException extends IOException {
        private final boolean settled;
        CaptureException(String message, boolean settled) { super(message); this.settled = settled; }
        public boolean settled() { return settled; }
    }

    public record Result(List<String> command, int exitCode, boolean timedOut, long durationMillis,
                         String stdout, String stderr, boolean stdoutTruncated,
                         boolean stderrTruncated, boolean outputLimitExceeded) {
        public Result(List<String> command, int exitCode, boolean timedOut, long durationMillis,
                      String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated) {
            this(command, exitCode, timedOut, durationMillis, stdout, stderr,
                    stdoutTruncated, stderrTruncated, false);
        }
        public boolean ok() { return !timedOut && !outputLimitExceeded && exitCode == 0; }
    }

    /**
     * Prepares one argument so that a Windows child receives it exactly as given.
     *
     * Java hands a Windows child one command line, and only wraps an argument in quotes when
     * it contains whitespace; it never escapes a double quote inside it. The child (node, an
     * Orca CLI, any MSVC-runtime program) then splits that line by the usual rules, which
     * treat a bare {@code "} as a quote toggle and drop it. A visual QA scenario such as
     * {@code css=[data-label="Don't Save"] press=Escape} reached the adapter as
     * {@code css=[data-label=Don't Save] press=Escape} inside a different quote span, so the
     * selector, the press and an unknown-step check all broke on Windows only.
     *
     * Each {@code "} is escaped as {@code \"}, and the backslashes right before it are doubled
     * as those rules require. Off Windows the argument is returned unchanged; arguments go
     * to the child as an array there.
     */
    public static String argument(String value) {
        return argument(null, value);
    }

    /**
     * {@link #argument(String)} for a known executable. Java itself escapes interior quotes
     * when it runs with {@code -Djdk.lang.Process.allowAmbiguousCommands=false} and the
     * executable is not a {@code .cmd}/{@code .bat} file (OpenJDK's ProcessImpl, mode
     * VERIFICATION_WIN32_SAFE); escaping here as well would hand the child literal backslashes.
     */
    public static String argument(String executable, String value) {
        boolean escape = needsManualEscaping(System.getProperty("os.name", ""),
                System.getProperty("jdk.lang.Process.allowAmbiguousCommands"), executable);
        return escape ? windowsEscaped(value) : value;
    }

    /**
     * Whether Warden must escape interior quotes itself, mirroring how OpenJDK's Windows
     * ProcessImpl builds a command line. Pure, so both modes are testable on any platform.
     *
     * @param osName             value of {@code os.name}
     * @param allowAmbiguous     value of {@code jdk.lang.Process.allowAmbiguousCommands}, or null
     * @param executable         the program being run, or null when unknown (assumed not a script)
     */
    public static boolean needsManualEscaping(String osName, String allowAmbiguous, String executable) {
        if (osName == null || !osName.toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        // OpenJDK: the property is ambiguous (lenient) unless it is exactly "false", ignoring case.
        boolean lenient = allowAmbiguous == null || !"false".equalsIgnoreCase(allowAmbiguous);
        if (lenient) return true;
        // Strict mode: Java escapes quotes itself, except for batch files (VERIFICATION_CMD_BAT).
        if (executable == null) return false;
        String lower = executable.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("\"")) lower = lower.substring(0, lower.length() - 1);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    /** The Windows form of {@link #argument}, on any platform, so it can be tested anywhere. */
    public static String windowsEscaped(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        int backslashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                backslashes++;
                escaped.append(character);
                continue;
            }
            if (character == '"') {
                for (int repeat = 0; repeat < backslashes; repeat++) escaped.append('\\');
                escaped.append('\\');
            }
            backslashes = 0;
            escaped.append(character);
        }
        return escaped.toString();
    }

    public Result run(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, timeout, DEFAULT_CAPTURE_BYTES);
    }

    public Result run(List<String> command, Path workingDirectory, Duration timeout, int captureBytes)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, timeout, captureBytes, null);
    }

    /**
     * @param stdinText written to the child's standard input before it is closed, or null to
     *                  close it immediately. Some vendors accept a prompt only this way, and on
     *                  Windows it is the only channel that survives a `.cmd` shim intact.
     */
    public Result run(List<String> command, Path workingDirectory, Duration timeout, int captureBytes,
                      String stdinText) throws IOException, InterruptedException {
        return runCaptured(command, workingDirectory, timeout, captureBytes, stdinText, null, null, DEFAULT_RAW_BYTES, false);
    }

    /**
     * Spools output up to DEFAULT_RAW_BYTES per stream. The returned strings are bounded
     * head-and-tail previews; reaching a raw limit is a separate failed outcome.
     * Opening a file or failing to finish its capture fails the call rather than presenting
     * a partial transcript as complete. Existing overloads retain their prefix-only capture.
     */
    public Result run(List<String> command, Path workingDirectory, Duration timeout, int captureBytes,
                      String stdinText, Path stdoutFile, Path stderrFile)
            throws IOException, InterruptedException {
        try (OutputStream stdout = Files.newOutputStream(stdoutFile);
             OutputStream stderr = Files.newOutputStream(stderrFile)) {
            return runCaptured(command, workingDirectory, timeout, captureBytes, stdinText, stdout, stderr, DEFAULT_RAW_BYTES, false);
        }
    }

    /**
     * Vendor settlement with no raw spool. The caller redacts the bounded preview before
     * any evidence file is written; a live spool would persist secret-bearing bytes first.
     */
    public Result runVendor(List<String> command, Path workingDirectory, Duration timeout, int captureBytes,
                            String stdinText) throws IOException, InterruptedException {
        return runCaptured(command, workingDirectory, timeout, captureBytes, stdinText,
                null, null, DEFAULT_RAW_BYTES, true);
    }

    /** Vendor calls settle their known descendants before returning to a failover caller. */
    public Result runVendor(List<String> command, Path workingDirectory, Duration timeout, int captureBytes,
                            String stdinText, Path stdoutFile, Path stderrFile, long rawBytes)
            throws IOException, InterruptedException {
        if (rawBytes < 1) throw new IllegalArgumentException("raw output limit must be positive");
        try (OutputStream stdout = Files.newOutputStream(stdoutFile);
             OutputStream stderr = Files.newOutputStream(stderrFile)) {
            return runCaptured(command, workingDirectory, timeout, captureBytes, stdinText,
                    stdout, stderr, rawBytes, true);
        }
    }

    private Result runCaptured(List<String> command, Path workingDirectory, Duration timeout,
                               int captureBytes, String stdinText, OutputStream stdoutFile,
                               OutputStream stderrFile, long rawBytes, boolean vendor) throws IOException, InterruptedException {
        long started = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        removeEnvironment(builder.environment());
        applyEnvironment(builder.environment());
        if (vendor) WorktreeLease.startingVendor();
        Process process;
        try { process = builder.start(); }
        catch (IOException cannotStart) {
            if (vendor) WorktreeLease.vendorNotStarted();
            throw cannotStart;
        }
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        try {
            if (vendor) WorktreeLease.track(process.toHandle());
            if (vendor) observeDescendants(process, descendants, true);
        } catch (IOException receiptFailed) {
            process.toHandle().destroyForcibly();
            WorktreeLease.requireRecovery("cannot persist vendor process receipt");
            throw new CaptureException("cannot persist vendor process receipt", false);
        }
        LimitedBuffer stdout = new LimitedBuffer(captureBytes, stdoutFile, rawBytes);
        LimitedBuffer stderr = new LimitedBuffer(captureBytes, stderrFile, rawBytes);
        // The readers start before anything is written. A child that prints before it reads
        // its input fills its stdout pipe and waits for it to be emptied; if this call were
        // still writing that child's stdin, each would wait on the other for good.
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
        try {
            // No command executed by Warden is interactive. Leaving stdin open makes CLIs that
            // probe it wait forever for input that can never arrive — so it is always closed,
            // whether or not something was written to it first. Closing an empty pipe cannot
            // block, so with nothing to write it is closed here and now. A prompt is written on
            // its own thread, so the timeout below bounds it as well: written inline, a prompt
            // larger than the pipe blocked this call inside write() for as long as the child did
            // not read it, before any timeout, wall-clock cap or chain deadline could apply.
            Thread writer = null;
            if (stdinText == null) {
                feed(process.getOutputStream(), null);
            } else {
                byte[] input = stdinText.getBytes(StandardCharsets.UTF_8);
                writer = Thread.ofVirtual().start(() -> feed(process.getOutputStream(), input));
            }

            boolean finished;
            try {
                finished = await(process, timeout, descendants, vendor,
                        () -> stdout.exceeded() || stderr.exceeded() || stdout.failed() || stderr.failed());
            } catch (InterruptedException interrupted) {
                // Cancellation is not permission to orphan a vendor CLI or its descendants.
                terminateTree(process, descendants, Duration.ofSeconds(2), vendor);
                settle(writer);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            boolean outputLimit = stdout.exceeded() || stderr.exceeded();
            boolean captureFailed = stdout.failed() || stderr.failed();
            boolean timedOut = !finished && !outputLimit && !captureFailed;
            if (timedOut || outputLimit || captureFailed || (vendor && descendants.values().stream().anyMatch(ProcessHandle::isAlive))) {
                terminateTree(process, descendants, Duration.ofSeconds(2), vendor);
            }
            if (vendor && (process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive))) {
                WorktreeLease.requireRecovery("vendor process tree did not settle");
                throw new CaptureException("vendor process tree did not settle", false);
            }
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            // The child has exited or been stopped with its descendants, so nothing holds the read
            // end of its stdin any more and a write still waiting on that pipe fails now.
            settle(writer);

            // A killed descendant may keep an inherited pipe alive. Bound the readers separately.
            outReader.join(2_000);
            errReader.join(2_000);
            if (outReader.isAlive()) outReader.interrupt();
            if (errReader.isAlive()) errReader.interrupt();

            // Interrupting a thread blocked on a native pipe does not necessarily stop it. The
            // caller must not parse a spool that a surviving descendant may still append to.
            if (stdoutFile != null && (outReader.isAlive() || errReader.isAlive())) {
                stdout.stopSpooling();
                stderr.stopSpooling();
                if (vendor) WorktreeLease.requireRecovery("raw output capture did not settle");
                throw new CaptureException("raw output capture did not settle after process termination", false);
            }
            try { stdout.checkSpool(); stderr.checkSpool(); }
            catch (IOException failure) { throw new CaptureException(failure.getMessage(), true); }

            long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new Result(List.copyOf(command), exitCode, timedOut, durationMillis,
                    stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated(),
                    stdout.exceeded() || stderr.exceeded());
        } catch (IOException failure) {
            if (vendor && !(failure instanceof CaptureException)) {
                WorktreeLease.requireRecovery("vendor process receipt or capture failed");
                throw new CaptureException(failure.getMessage(), false);
            }
            throw failure;
        } finally {
            if (vendor && (process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive))) {
                terminateTree(process, descendants, Duration.ofSeconds(2), vendor);
            }
            // In particular on cancellation, never let a lingering reader race the owner
            // closing its raw file after this method returns.
            stdout.stopSpooling();
            stderr.stopSpooling();
        }
    }

    /**
     * Signals through {@link ProcessHandle}, never {@link Process#destroy}. On Unix,
     * {@code Process.destroy} closes the child's stdin right after the signal, and that close
     * flushes under the lock a prompt write still blocked on a full pipe holds. A child that
     * ignores SIGTERM and never reads its prompt would hold this call inside {@code destroy},
     * before the grace wait and the forced kill below could run. The handle only signals; the
     * stdin stream stays with its writer, which closes it once the pipe breaks.
     */
    private static void terminateTree(Process process, Map<Long, ProcessHandle> descendants,
                                      Duration grace, boolean vendor) throws InterruptedException, IOException {
        ProcessHandle child = process.toHandle();
        observeDescendants(process, descendants, vendor);
        descendants.values().forEach(ProcessHandle::destroy);
        child.destroy();
        if (awaitTree(process, descendants, grace, vendor)) return;
        observeDescendants(process, descendants, vendor);
        descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        awaitTree(process, descendants, grace, vendor);
    }

    private static boolean await(Process process, Duration timeout, Map<Long, ProcessHandle> descendants,
                                 boolean vendor, java.util.function.BooleanSupplier stop)
            throws InterruptedException, IOException {
        long started = System.nanoTime();
        long budget = Math.max(0, timeout.toNanos());
        while (process.isAlive()) {
            // Windows enumerates the system process table here. Git and short gate
            // commands need a census only when they must be terminated, not every call.
            if (vendor) observeDescendants(process, descendants, true);
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0 || stop.getAsBoolean()) return false;
            process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
        }
        return true;
    }

    private static void observeDescendants(Process process, Map<Long, ProcessHandle> descendants,
                                           boolean vendor) throws IOException {
        try (var children = process.descendants()) {
            for (ProcessHandle handle : children.toList()) {
                if (descendants.putIfAbsent(handle.pid(), handle) == null && vendor) WorktreeLease.track(handle);
            }
        }
    }

    private static boolean awaitTree(Process process, Map<Long, ProcessHandle> descendants, Duration grace, boolean vendor)
            throws InterruptedException, IOException {
        long deadline = System.nanoTime() + grace.toNanos();
        while (process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
            observeDescendants(process, descendants, vendor);
            if (System.nanoTime() >= deadline) return false;
            if (process.isAlive()) process.waitFor(25, TimeUnit.MILLISECONDS);
            else Thread.sleep(25);
        }
        return true;
    }

    /** Writes the child's input, if any, and always closes it. */
    private static void feed(OutputStream stdin, byte[] input) {
        try (stdin) {
            if (input != null) stdin.write(input);
        } catch (IOException childClosedEarly) {
            // A vendor that refuses before reading its input, or is stopped while this write
            // waits on its full pipe, is a normal outcome, not a fault of this runner; the exit
            // code and captured streams still describe what happened.
        }
    }

    /**
     * Waits a bounded time for the stdin writer to finish, which it does as soon as the child
     * reads its input or the pipe breaks. Only a descendant that escaped the process tree and
     * still holds the inherited pipe could keep it waiting; it is then interrupted and left,
     * holding nothing the caller needs, as the output readers are.
     */
    private static void settle(Thread writer) throws InterruptedException {
        if (writer == null) return;
        writer.join(2_000);
        if (writer.isAlive()) writer.interrupt();
    }

    private static void drain(InputStream input, LimitedBuffer target) {
        try (input) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) target.add(chunk, read);
        } catch (IOException ignored) {
            // Process termination closes streams; the process result remains authoritative.
        }
    }

    private static final class LimitedBuffer {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final byte[] tail;
        private OutputStream spool;
        private IOException spoolError;
        private int tailPosition;
        private long received;
        private boolean truncated;
        private final long rawLimit;
        private long persisted;
        private boolean exceeded;

        LimitedBuffer(int limit, OutputStream spool, long rawLimit) {
            this.limit = Math.max(0, limit);
            this.spool = spool;
            this.rawLimit = rawLimit;
            this.tail = new byte[spool == null ? 0 : this.limit / 2];
        }

        synchronized void add(byte[] source, int length) {
            if (spool != null) {
                try {
                    int count = (int) Math.min(length, Math.max(0, rawLimit - persisted));
                    if (count > 0) spool.write(source, 0, count);
                    persisted += count;
                    if (count < length) exceeded = true;
                }
                catch (IOException failure) { spoolError = failure; spool = null; }
            }
            int writable = Math.min(length, Math.max(0, limit - tail.length - bytes.size()));
            if (writable > 0) bytes.write(source, 0, writable);
            for (int index = writable; tail.length > 0 && index < length; index++) {
                tail[tailPosition] = source[index];
                tailPosition = (tailPosition + 1) % tail.length;
            }
            received += length;
            if (received > limit) truncated = true;
        }

        synchronized String text() {
            if (tail.length == 0) return bytes.toString(StandardCharsets.UTF_8);
            int size = (int) Math.min(tail.length, received - bytes.size());
            ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.size() + size);
            result.writeBytes(bytes.toByteArray());
            int start = size < tail.length ? 0 : tailPosition;
            int first = Math.min(size, tail.length - start);
            result.write(tail, start, first);
            result.write(tail, 0, size - first);
            return result.toString(StandardCharsets.UTF_8);
        }
        synchronized void stopSpooling() { spool = null; }
        synchronized void checkSpool() throws IOException {
            if (spoolError != null) throw new IOException("cannot persist raw process output", spoolError);
        }
        synchronized boolean truncated() { return truncated; }
        synchronized boolean exceeded() { return exceeded; }
        synchronized boolean failed() { return spoolError != null; }
    }
}
