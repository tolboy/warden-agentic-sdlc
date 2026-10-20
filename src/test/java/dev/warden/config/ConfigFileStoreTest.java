package dev.warden.config;

import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Native file locking, crash recovery and reader visibility; no vendor processes. */
public final class ConfigFileStoreTest implements Suite {
    @Override public String name() { return "config-file-store"; }
    private static final String ORIGINAL = """
            # operator note
            version: 1
            profile: sample
            role: reviewer
            vendor: fixture
            model: alpha
            command: fixture
            runner: orca
            """;
    private static final String EDITED = ORIGINAL.replace("model: alpha", "model: beta");
    private static void validate(String text) { Profile.parse(text, "sample.yaml"); }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-config-store-");
        try {
            basics(check, root.resolve("basic.yaml"));
            faults(check, root.resolve("fault.yaml"));
            contenders(check, root.resolve("race.yaml"));
            crossProcess(check, root.resolve("process.yaml"));
            localContentionKeepsOsLock(check, root.resolve("local-os.yaml"));
            readers(check, root.resolve("readers.yaml"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void basics(Check check, Path file) throws Exception {
        Files.writeString(file, ORIGINAL);
        ConfigFileStore.Snapshot source = ConfigFileStore.read(file);
        var store = new ConfigFileStore();
        check.rejects("invalid YAML is rejected before any backup", "version must be 1",
                () -> store.save(source, ORIGINAL.replace("version: 1", "version: 9"), ConfigFileStoreTest::validate, "test"));
        check.eq("validation never touched the active file", ORIGINAL, Files.readString(file));
        check.eq("validation created no artifacts", 0L, siblings(file, ".before-test-").size() + (long) siblings(file, ".warden-write.lock").size());
        var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        var originalAcl = acl == null ? null : acl.getAcl();
        ConfigFileStore.Saved saved = store.save(source, EDITED, ConfigFileStoreTest::validate, "test");
        check.eq("successful edit is complete", EDITED, Files.readString(file));
        check.eq("backup is the exact accepted source", ORIGINAL, Files.readString(saved.backup()));
        check.eq("new digest identifies the actual file", ConfigFileStore.read(file).sha256(), saved.sha256());
        if (posix != null) {
            check.eq("replacement retains restrictive POSIX mode", "rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            check.eq("backup retains restrictive POSIX mode", "rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(saved.backup())));
        }
        if (acl != null) {
            check.eq("replacement retains Windows ACL", originalAcl, Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class).getAcl());
            check.eq("backup retains Windows ACL", originalAcl, Files.getFileAttributeView(saved.backup(), java.nio.file.attribute.AclFileAttributeView.class).getAcl());
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Files.setAttribute(file, "dos:readonly", true);
            try {
                check.rejects("atomic replacement respects Windows read-only files", "configuration is read-only",
                        () -> store.save(ConfigFileStore.read(file), ORIGINAL, ConfigFileStoreTest::validate, "readonly"));
                check.eq("read-only refusal leaves bytes unchanged", EDITED, Files.readString(file));
            } finally { Files.setAttribute(file, "dos:readonly", false); }
        }
        check.rejects("old source is refused after another edit", "configuration changed",
                () -> store.save(source, ORIGINAL, ConfigFileStoreTest::validate, "test"));
        check.eq("stale writer preserves new bytes", EDITED, Files.readString(file));
        ConfigFileStore.Saved second = store.save(ConfigFileStore.read(file), ORIGINAL, ConfigFileStoreTest::validate, "test");
        check.that("backups never overwrite each other", !saved.backup().equals(second.backup()));
        check.eq("second backup contains its own predecessor", EDITED, Files.readString(second.backup()));
        check.eq("first backup survives later edits", ORIGINAL, Files.readString(saved.backup()));
    }

    private void faults(Check check, Path file) throws Exception {
        Files.writeString(file, ORIGINAL);
        var source = ConfigFileStore.read(file);
        var failed = new ConfigFileStore((from, to) -> { throw new IOException("simulated replace failure"); });
        check.rejects("failure before rename is propagated", "simulated replace failure",
                () -> failed.save(source, EDITED, ConfigFileStoreTest::validate, "fault"));
        check.eq("failed replace leaves old file complete", ORIGINAL, Files.readString(file));
        check.eq("failed replace retains a complete backup", ORIGINAL, Files.readString(siblings(file, ".before-fault-").get(0)));
        var unsupported = new ConfigFileStore((from, to) -> { throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture"); });
        try {
            unsupported.save(source, EDITED, ConfigFileStoreTest::validate, "fault");
            check.that("unsupported atomic rename must fail", false);
        } catch (ConfigFileStore.Refused refusal) {
            check.eq("unsupported filesystem has actionable code", "atomic_replace_unsupported", refusal.code());
        }
        check.eq("no non-atomic fallback truncated the source", ORIGINAL, Files.readString(file));
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            AtomicInteger attempts = new AtomicInteger();
            var transientHandle = new ConfigFileStore((from, to) -> {
                if (attempts.incrementAndGet() < 3) throw new AccessDeniedException(to.toString());
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            });
            transientHandle.save(source, EDITED, ConfigFileStoreTest::validate, "retry");
            check.eq("transient Windows sharing denial is retried", 3, attempts.get());
            Files.writeString(file, ORIGINAL);
            var changedDuringRetry = new ConfigFileStore((from, to) -> {
                Files.writeString(to, ORIGINAL + "# newer editor\n");
                throw new AccessDeniedException(to.toString());
            });
            check.rejects("retry rechecks the digest before replacement", "configuration changed",
                    () -> changedDuringRetry.save(source, EDITED, ConfigFileStoreTest::validate, "retry"));
            check.contains("retry cannot overwrite a newer edit", Files.readString(file), "# newer editor");
            Files.writeString(file, ORIGINAL);
        }
        check.that("lock is released after both failures", new ConfigFileStore().save(source, EDITED, ConfigFileStoreTest::validate, "fault").backup() != null);
    }

    private void contenders(Check check, Path file) throws Exception {
        Files.writeString(file, ORIGINAL);
        var source = ConfigFileStore.read(file);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                start.await();
                try {
                    new ConfigFileStore().save(source, ORIGINAL.replace("alpha", "writer" + index), ConfigFileStoreTest::validate, "race");
                    committed.incrementAndGet();
                } catch (ConfigFileStore.Refused conflict) {
                    if (!List.of("config_busy", "config_conflict").contains(conflict.code())) throw conflict;
                    refused.incrementAndGet();
                }
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        check.eq("only one edit with the same version commits", 1, committed.get());
        check.eq("the other edit is explicitly refused", 1, refused.get());
        check.eq("one backup reflects the winning edit", 1, siblings(file, ".before-race-").size());
        validate(Files.readString(file));
    }

    private void crossProcess(Check check, Path file) throws Exception {
        Files.writeString(file, ORIGINAL);
        Path ready = file.resolveSibling("holder.ready");
        Process holder = worker("hold", file, ready);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(ready) && holder.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            if (!Files.exists(ready)) throw new IOException("lock worker failed: " + Files.readString(file.resolveSibling("hold.log")));
            try {
                new ConfigFileStore().save(ConfigFileStore.read(file), EDITED, ConfigFileStoreTest::validate, "process");
                check.that("another JVM's lock must be respected", false);
            } catch (ConfigFileStore.Refused conflict) {
                check.eq("OS lock prevents a second JVM writer", "config_busy", conflict.code());
            }
            check.eq("busy file stays complete", ORIGINAL, Files.readString(file));
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly().waitFor();
        }
        Process crashed = worker("crash", file, ready);
        try {
            if (!crashed.waitFor(15, TimeUnit.SECONDS)) throw new IOException("crash worker timed out");
            check.eq("worker halts after staging and backup", 23, crashed.exitValue());
        } finally {
            if (crashed.isAlive()) crashed.destroyForcibly().waitFor();
        }
        check.eq("process crash never exposes staged bytes", ORIGINAL, Files.readString(file));
        check.eq("crash retained a complete predecessor", ORIGINAL, Files.readString(siblings(file, ".before-crash-").get(0)));
        check.that("OS releases crashed lock without deleting its sidecar", new ConfigFileStore().save(ConfigFileStore.read(file), EDITED, ConfigFileStoreTest::validate, "process").backup() != null);
    }

    private void readers(Check check, Path file) throws Exception {
        String a = ORIGINAL + "# " + "a".repeat(256 * 1024) + "\n";
        String b = EDITED + "# " + "b".repeat(256 * 1024) + "\n";
        Files.writeString(file, a);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger partial = new AtomicInteger();
        CountDownLatch observed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reader = pool.submit(() -> {
                while (!stopped.get()) {
                    String text = Files.readString(file);
                    if (!text.equals(a) && !text.equals(b)) partial.incrementAndGet();
                    observations.incrementAndGet();
                    observed.countDown();
                    Thread.sleep(1);
                }
                return null;
            });
            try {
                if (!observed.await(10, TimeUnit.SECONDS)) throw new IOException("reader did not start");
                for (int i = 0; i < 12; i++) new ConfigFileStore().save(ConfigFileStore.read(file), i % 2 == 0 ? b : a,
                        ConfigFileStoreTest::validate, "reader");
            } finally { stopped.set(true); }
            reader.get(15, TimeUnit.SECONDS);
        }
        check.that("concurrent readers actually observed replacements", observations.get() > 1);
        check.eq("readers only see whole old or new versions", 0, partial.get());
    }

    private void localContentionKeepsOsLock(Check check, Path file) throws Exception {
        Files.writeString(file, ORIGINAL);
        var source = ConfigFileStore.read(file);
        AtomicBoolean locallyRefused = new AtomicBoolean();
        AtomicInteger workerExit = new AtomicInteger();
        var owner = new ConfigFileStore((from, to) -> {
            try {
                try { new ConfigFileStore().save(source, EDITED, ConfigFileStoreTest::validate, "local"); }
                catch (ConfigFileStore.Refused busy) { locallyRefused.set(busy.code().equals("config_busy")); }
                Process contender = worker("attempt", file, file);
                try {
                    if (!contender.waitFor(15, TimeUnit.SECONDS)) throw new IOException("OS lock contender timed out");
                    workerExit.set(contender.exitValue());
                } finally { if (contender.isAlive()) contender.destroyForcibly().waitFor(); }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });
        owner.save(source, EDITED, ConfigFileStoreTest::validate, "owner");
        check.that("local contender is refused before opening its lock channel", locallyRefused.get());
        check.eq("local refusal did not release the owner's OS lock", 24, workerExit.get());
        check.eq("only the owner's version was committed", EDITED, Files.readString(file));
    }

    private static List<Path> siblings(Path file, String marker) throws IOException {
        try (var paths = Files.list(file.getParent())) {
            return paths.filter(p -> p.getFileName().toString().startsWith(file.getFileName() + marker)).toList();
        }
    }
    private static Process worker(String mode, Path file, Path ready) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Worker.class.getName(), mode,
                file.toString(), ready.toString()).redirectErrorStream(true).redirectOutput(file.resolveSibling(mode + ".log").toFile()).start();
    }
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[1]);
            if (args[0].equals("hold")) {
                try (var channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".warden-write.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); var held = channel.lock()) {
                    Files.writeString(Path.of(args[2]), "locked");
                    System.in.read();
                }
            } else if (args[0].equals("attempt")) {
                try { new ConfigFileStore().save(ConfigFileStore.read(file), EDITED, ConfigFileStoreTest::validate, "contender"); }
                catch (ConfigFileStore.Refused busy) { System.exit(busy.code().equals("config_busy") ? 24 : 25); }
            } else {
                new ConfigFileStore((from, to) -> Runtime.getRuntime().halt(23))
                        .save(ConfigFileStore.read(file), EDITED, ConfigFileStoreTest::validate, "crash");
            }
        }
    }
}
