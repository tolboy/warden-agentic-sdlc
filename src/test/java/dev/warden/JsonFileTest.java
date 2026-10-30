package dev.warden;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real Windows sharing locks reproduce a live process-receipt replacement failure. */
public final class JsonFileTest implements Suite {
    @Override public String name() { return "json-file"; }

    @Override public void run(Check check) throws Exception {
        Path directory = Files.createTempDirectory("warden-json-sharing-");
        Path target = directory.resolve("receipt.json");
        try {
            JsonFile.writeAtomically(target, Map.of("state", "old"));
            check.eq("atomic receipt is readable", "old", Json.parseObject(Files.readString(target)).get("state"));
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) return;
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<?> writer;
                var entered = new CountDownLatch(1);
                try (var held = Files.newByteChannel(target, noShareDeleteRead())) {
                    writer = pool.submit(() -> {
                        entered.countDown();
                        JsonFile.writeAtomically(target, Map.of("state", "new"));
                        return null;
                    });
                    check.that("writer starts under the reader lock", entered.await(2, TimeUnit.SECONDS));
                    Thread.sleep(100);
                    check.that("sharing lock prevents replacement", !writer.isDone());
                    check.eq("old receipt remains intact while blocked", "old",
                            Json.parseObject(Files.readString(target)).get("state"));
                }
                writer.get(3, TimeUnit.SECONDS);
            }
            check.eq("replacement succeeds after reader releases", "new",
                    Json.parseObject(Files.readString(target)).get("state"));
            try (var held = Files.newByteChannel(target, noShareDeleteRead())) {
                long start = System.nanoTime();
                check.rejects("permanent sharing denial fails closed", null,
                        () -> JsonFile.writeAtomically(target, Map.of("state", "lost")));
                check.that("permanent denial is bounded", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
                var failure = new AtomicReference<Throwable>();
                var interrupted = new AtomicReference<Boolean>();
                Thread cancelled = new Thread(() -> {
                    try { JsonFile.writeAtomically(target, Map.of("state", "cancelled")); }
                    catch (Throwable thrown) { failure.set(thrown); }
                    interrupted.set(Thread.currentThread().isInterrupted());
                });
                cancelled.start();
                long waiting = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (cancelled.isAlive() && cancelled.getState() != Thread.State.TIMED_WAITING
                        && System.nanoTime() < waiting) Thread.sleep(1);
                check.eq("writer reaches sharing retry backoff", Thread.State.TIMED_WAITING, cancelled.getState());
                cancelled.interrupt(); cancelled.join(3_000);
                check.that("cancellation stops retry", !cancelled.isAlive());
                check.that("cancellation is reported", failure.get() instanceof InterruptedIOException);
                check.eq("cancellation preserves interrupt flag", true, interrupted.get());
            }
            check.eq("failed replacements retain previous complete receipt", "new",
                    Json.parseObject(Files.readString(target)).get("state"));
            try (var paths = Files.list(directory)) {
                check.eq("failed replacements clean temporary files", List.of(target), paths.toList());
            }
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    /** Windows read handle without FILE_SHARE_DELETE. The option is internal, so load it by name. */
    private static Set<OpenOption> noShareDeleteRead() throws ReflectiveOperationException {
        OpenOption noShareDelete = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
                .getField("NOSHARE_DELETE").get(null);
        return Set.of(StandardOpenOption.READ, noShareDelete);
    }
}
