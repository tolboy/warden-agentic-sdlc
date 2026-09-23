package dev.warden.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a bounded child process and always settles, including when descendants keep pipes open. */
public final class ProcessRunner {
    public static final int DEFAULT_CAPTURE_BYTES = 64 * 1024;

    public record Result(List<String> command, int exitCode, boolean timedOut, long durationMillis,
                         String stdout, String stderr, boolean stdoutTruncated,
                         boolean stderrTruncated) {
        public boolean ok() { return !timedOut && exitCode == 0; }
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
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
        LimitedBuffer stdout = new LimitedBuffer(captureBytes);
        LimitedBuffer stderr = new LimitedBuffer(captureBytes);
        // The readers start before anything is written. A child that prints before it reads
        // its input fills its stdout pipe and waits for it to be emptied; if this call were
        // still writing that child's stdin, each would wait on the other for good.
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
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
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Cancellation is not permission to orphan a vendor CLI or its descendants.
            terminateTree(process, Duration.ofSeconds(2));
            settle(writer);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        boolean timedOut = !finished;
        if (timedOut) terminateTree(process, Duration.ofSeconds(2));
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        // The child has exited or been stopped with its descendants, so nothing holds the read
        // end of its stdin any more and a write still waiting on that pipe fails now.
        settle(writer);

        // A killed descendant may keep an inherited pipe alive. Bound the readers separately.
        outReader.join(2_000);
        errReader.join(2_000);
        if (outReader.isAlive()) outReader.interrupt();
        if (errReader.isAlive()) errReader.interrupt();

        long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new Result(List.copyOf(command), exitCode, timedOut, durationMillis,
                stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated());
    }

    /**
     * Signals through {@link ProcessHandle}, never {@link Process#destroy}. On Unix,
     * {@code Process.destroy} closes the child's stdin right after the signal, and that close
     * flushes under the lock a prompt write still blocked on a full pipe holds. A child that
     * ignores SIGTERM and never reads its prompt would hold this call inside {@code destroy},
     * before the grace wait and the forced kill below could run. The handle only signals; the
     * stdin stream stays with its writer, which closes it once the pipe breaks.
     */
    private static void terminateTree(Process process, Duration grace) throws InterruptedException {
        ProcessHandle child = process.toHandle();
        process.descendants().forEach(ProcessHandle::destroy);
        child.destroy();
        if (process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
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
        private boolean truncated;

        LimitedBuffer(int limit) { this.limit = Math.max(0, limit); }

        synchronized void add(byte[] source, int length) {
            int writable = Math.min(length, Math.max(0, limit - bytes.size()));
            if (writable > 0) bytes.write(source, 0, writable);
            if (writable < length) truncated = true;
        }

        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
        synchronized boolean truncated() { return truncated; }
    }
}
