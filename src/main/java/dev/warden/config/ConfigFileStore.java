package dev.warden.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Single-file optimistic edits. All cooperating writers use the persistent sidecar lock.
 * Readers see complete old/new files; unsupported atomic replacement fails closed.
 * An editor that ignores the lock can still race the final digest check and rename.
 * This is not a multi-file transaction or a power-loss durability guarantee. */
public final class ConfigFileStore {
    private static final ConcurrentHashMap<Path, Semaphore> LOCAL_LOCKS = new ConcurrentHashMap<>();
    public record Snapshot(Path file, String text, String sha256) {
        public Snapshot {
            if (!digest(text.getBytes(StandardCharsets.UTF_8)).equals(sha256))
                throw new IllegalArgumentException("snapshot SHA-256 must match its text");
        }
    }
    public record Saved(Path backup, String beforeSha256, String sha256) {}

    public static final class Refused extends IOException {
        private final String code;
        private final String actualSha256;
        public Refused(String code, String message, String actualSha256) {
            super(message);
            this.code = code;
            this.actualSha256 = actualSha256;
        }
        public String code() { return code; }
        public String actualSha256() { return actualSha256; }
    }

    @FunctionalInterface interface Replacer { void replace(Path from, Path to) throws IOException; }
    private final Replacer replacer;
    public ConfigFileStore() {
        this((from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING));
    }
    ConfigFileStore(Replacer replacer) { this.replacer = replacer; }

    public static Snapshot read(Path file) throws IOException {
        Path canonical = file.toRealPath();
        byte[] bytes = Files.readAllBytes(canonical);
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return new Snapshot(canonical, text, digest(bytes));
    }

    public static void expect(Snapshot source, String expectedSha256) throws Refused {
        if (expectedSha256 != null && !source.sha256().equalsIgnoreCase(expectedSha256)) {
            throw new Refused("config_conflict", "configuration changed; reload before applying the edit",
                    source.sha256());
        }
    }

    public Saved save(Snapshot source, String candidate, Consumer<String> validate,
                      String backupLabel) throws IOException {
        if (!backupLabel.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("invalid backup label");
        validate.accept(candidate); // Never expose an unparseable candidate, even briefly.
        Path file = source.file().toRealPath();
        Path lockFile = file.resolveSibling(file.getFileName() + ".warden-write.lock");
        // On POSIX, closing a second descriptor can release the JVM's first OS lock.
        // Refuse local contenders before opening another channel to the sidecar.
        Semaphore local = LOCAL_LOCKS.computeIfAbsent(file, ignored -> new Semaphore(1));
        if (!local.tryAcquire()) throw new Refused("config_busy", "another configuration edit is in progress; retry after reloading", null);
        try {
            // Never unlink this file: waiters must keep locking the same inode after a crash.
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException busy) { lock = null; }
                if (lock == null) throw new Refused("config_busy", "another configuration edit is in progress; retry after reloading", null);
                try (FileLock held = lock) {
                    Snapshot current = read(file);
                    expect(current, source.sha256());
                    var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
                    if (dos != null && dos.readAttributes().isReadOnly())
                        throw new AccessDeniedException(file.toString(), null, "configuration is read-only");
                    Path staged = Files.createTempFile(file.getParent(), ".warden-config-", ".tmp");
                    try {
                        copyPermissions(file, staged);
                        writeAndForce(staged, candidate.getBytes(StandardCharsets.UTF_8));
                        // Backups contain the exact accepted source, never a second racy copy.
                        Path backup = backup(file, source.text().getBytes(StandardCharsets.UTF_8), backupLabel);
                        try { replace(source, staged, file); }
                        catch (AtomicMoveNotSupportedException unsupported) {
                            throw new Refused("atomic_replace_unsupported", "filesystem does not support atomic configuration replacement", source.sha256());
                        }
                        return new Saved(backup, source.sha256(), digest(candidate.getBytes(StandardCharsets.UTF_8)));
                    } finally {
                        Files.deleteIfExists(staged);
                        // A completed backup remains useful even when replacement is refused.
                    }
                }
            }
        } finally { local.release(); }
    }

    private void replace(Snapshot source, Path staged, Path file) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
        while (true) {
            expect(read(file), source.sha256());
            try {
                replacer.replace(staged, file);
                return;
            } catch (AccessDeniedException denied) {
                // Windows readers may briefly hold handles that deny delete/rename sharing.
                if (!windows || System.nanoTime() >= deadline) throw denied;
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted while replacing configuration");
                }
            }
        }
    }

    private static Path backup(Path file, byte[] bytes, String label) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String base = file.getFileName() + ".before-" + label + "-" + stamp;
        for (int suffix = 1; ; suffix++) {
            Path backup = file.resolveSibling(base + (suffix == 1 ? "" : "-" + suffix));
            try (FileChannel out = FileChannel.open(backup, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                copyPermissions(file, backup);
                writeAndForce(out, bytes);
                return backup;
            } catch (java.nio.file.FileAlreadyExistsException collision) { /* preserve older backups */ }
        }
    }

    private static void copyPermissions(Path from, Path to) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(from, PosixFileAttributeView.class);
        if (posix != null) Files.setPosixFilePermissions(to, posix.readAttributes().permissions());
        AclFileAttributeView acl = Files.getFileAttributeView(from, AclFileAttributeView.class);
        if (acl != null) Files.getFileAttributeView(to, AclFileAttributeView.class).setAcl(acl.getAcl());
    }

    private static void writeAndForce(Path file, byte[] bytes) throws IOException {
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeAndForce(out, bytes);
        }
    }
    private static void writeAndForce(FileChannel out, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
