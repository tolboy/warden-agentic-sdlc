package dev.warden.json;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
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
