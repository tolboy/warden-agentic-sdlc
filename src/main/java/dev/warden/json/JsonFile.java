package dev.warden.json;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable JSON files that two Warden processes may reach for at the same time.
 *
 * Two things are needed and neither is the default: a reader must never see half a file, and
 * a compare-and-swap must hold across processes rather than only across threads. The write is
 * therefore a temporary file forced to disk and moved atomically into place, and the critical
 * section is an OS file lock on a retained sibling.
 *
 * The lock file is deliberately never deleted. Deleting and recreating it can split waiters
 * across two different filesystem objects, which makes the critical section look present and
 * be absent.
 */
public final class JsonFile {

    /** File locks protect processes; these monitors prevent overlapping-lock errors in one JVM. */
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private JsonFile() {}

    @FunctionalInterface
    public interface IoOperation<T> {
        T run() throws IOException;
    }

    /** Replace {@code target} with {@code value}, or leave the previous content untouched. */
    public static void writeAtomically(Path target, Map<String, Object> value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".json-", ".tmp");
        try {
            Files.writeString(temporary, Json.writePretty(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        // Windows denies atomic replacement while a reader omits FILE_SHARE_DELETE.
        // A short-lived reader must not abort a live vendor's process receipt. Keep the
        // old file and atomicity throughout; permanent denial still fails closed.
        long deadline = System.nanoTime() + java.time.Duration.ofMillis(500).toNanos();
        while (true) {
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                if (System.nanoTime() >= deadline) throw denied;
                try { Thread.sleep(25); }
                catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    var failure = new InterruptedIOException("atomic JSON replacement interrupted");
                    failure.initCause(denied);
                    throw failure;
                }
            }
        }
    }

    /** Run {@code operation} while holding the exclusive lock guarding {@code target}. */
    public static <T> T underExclusiveLock(Path target, String lockName, IoOperation<T> operation)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path lockPath = target.resolveSibling(lockName).toAbsolutePath().normalize();
        Object monitor = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return operation.run();
            }
        }
    }
}
