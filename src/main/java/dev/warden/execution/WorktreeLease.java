package dev.warden.execution;

import dev.warden.json.Json;
import dev.warden.json.JsonFile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One controller per checkout, including its readers and machine gates. A run-id reservation
 * only protects that run's evidence; it does not protect the tree two different runs edit.
 *
 * The retained OS lock handles live controllers. The durable active marker handles crashes:
 * a dead controller's CLI may still be alive, so an unlocked file is not evidence of safety.
 * Such a tree requires inspection/fencing rather than an automatic retry under a new run id.
 */
public final class WorktreeLease {
    private static final String LOCK = "warden-execution.lock";
    private static final String STATE = "warden-execution.json";
    private static final ThreadLocal<Map<Path, Owner>> OWNED = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Deque<Owner>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private WorktreeLease() { }

    public static final class Refused extends IOException {
        private final String code;
        private final Path stateFile;

        private Refused(String code, Path stateFile, String message) {
            super(message);
            this.code = code;
            this.stateFile = stateFile;
        }

        public String code() { return code; }
        public Map<String, Object> report() {
            return Map.of("ok", false, "code", code, "reason", code,
                    "message", getMessage(), "execution_state", stateFile.toString(),
                    "next_action", "worktree_busy".equals(code) ? "wait_for_worktree" : "recover_worktree");
        }
    }

    public static final class Scope implements AutoCloseable {
        private Owner owner;

        private Scope(Owner owner) { this.owner = owner; }

        @Override public void close() throws IOException {
            Owner held = owner;
            if (held == null) return;
            if (held.thread != Thread.currentThread()) {
                throw new IllegalStateException("worktree lease must be closed by its owning thread");
            }
            owner = null;
            CURRENT.get().removeFirstOccurrence(held);
            if (--held.references > 0) return;
            OWNED.get().remove(held.root);
            try {
                synchronized (held) {
                    try { Runtime.getRuntime().removeShutdownHook(held.shutdownHook); }
                    catch (IllegalStateException shuttingDown) {
                        // The hook may already be queued behind this monitor. Settle it
                        // here before releasing admission; the queued hook then sees closed.
                        held.shutdown();
                    }
                    List<Map<String, Object>> alive = held.processes.values().stream()
                            .filter(ProcessHandle::isAlive).map(WorktreeLease::identity).toList();
                    Map<String, Object> state = new LinkedHashMap<>(held.state);
                    state.put("state", alive.isEmpty() && held.recoveryReason == null
                            && !Boolean.TRUE.equals(held.state.get("vendor_launch_pending"))
                            ? "released" : "recovery_required");
                    state.put("updated_at", Instant.now().toString());
                    if (!alive.isEmpty()) state.put("active_processes", alive);
                    if (held.recoveryReason != null) state.put("recovery_reason", held.recoveryReason);
                    JsonFile.writeAtomically(held.stateFile, state);
                    held.closed = true;
                }
            } finally {
                try { held.lock.release(); }
                finally { held.channel.close(); }
            }
        }
    }

    /** A preview does not own or fence execution. */
    public static Scope preview() { return new Scope(null); }

    public static Scope acquire(Path checkout, String runId) throws IOException {
        Path root = checkoutRoot(checkout);
        Owner nested = OWNED.get().get(root);
        if (nested != null) {
            // Reentrancy is confined to this thread. Another controller in this JVM must
            // compete for the OS lock even when it supplies exactly the same run id.
            nested.references++;
            CURRENT.get().push(nested);
            return new Scope(nested);
        }
        Path directory = administration(root);
        Files.createDirectories(directory);
        Path stateFile = directory.resolve(STATE);
        FileChannel channel = FileChannel.open(directory.resolve(LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { /* same refusal as another JVM */ }
            if (lock == null) {
                throw new Refused("worktree_busy", stateFile,
                        "Another Warden controller owns this worktree. Wait for it to finish "
                                + "or use a separate worktree; choosing a new run id does not isolate edits.");
            }
            if (attributes(stateFile) != null) {
                Map<String, Object> previous;
                try {
                    if (!attributes(stateFile).isRegularFile()) throw new IOException("not a regular state file");
                    previous = Json.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
                } catch (Exception unreadable) { previous = Map.of(); }
                if (!"released".equals(previous.get("state"))) {
                    throw new Refused("worktree_recovery_required", stateFile,
                            "The previous worktree owner did not release execution safely. Run `warden recover` "
                                    + "to inspect its durable process receipts and release the fence after "
                                    + "the previous vendor processes have stopped. State: " + stateFile);
                }
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("schema_version", 2L);
            state.put("vendor_launch_pending", false);
            state.put("processes", List.of());
            state.put("state", "active");
            state.put("lease_id", UUID.randomUUID().toString());
            state.put("run_id", runId);
            state.put("controller", identity(ProcessHandle.current()));
            state.put("acquired_at", Instant.now().toString());
            // Written before any caller may spawn: even a crash between start() and PID
            // observation leaves a fence. Recovery additionally checks durable launch intent
            // and PID start times; a controller PID by itself is never enough.
            JsonFile.writeAtomically(stateFile, state);
            Owner owner = new Owner(root, stateFile, channel, lock, state);
            OWNED.get().put(root, owner);
            CURRENT.get().push(owner);
            return new Scope(owner);
        } catch (IOException | RuntimeException failure) {
            try { if (lock != null) lock.release(); }
            catch (IOException releaseFailed) { failure.addSuppressed(releaseFailed); }
            try { channel.close(); }
            catch (IOException closeFailed) { failure.addSuppressed(closeFailed); }
            throw failure;
        }
    }

    public static boolean isHeld() { return !CURRENT.get().isEmpty(); }

    /** A missing process receipt is not proof of termination, even if all sampled PIDs died. */
    public static void requireRecovery(String reason) {
        Owner owner = CURRENT.get().peek();
        if (owner != null) {
            owner.recoveryReason = reason;
            synchronized (owner) {
                owner.state.put("recovery_reason", reason);
                try { owner.persist(); }
                catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
            }
        }
    }

    /**
     * Orca workers may intentionally outlive a controller waiting on a human. Only their
     * adapter can attach/reconcile them; changing the next profile to direct/local must not
     * bypass that adapter's census and start a second writer beside a retained worker.
     */
    public static void requireLocalDispatch(Path root) throws IOException {
        Path runs = root.resolve(".warden/runs");
        try {
            if (attributes(runs) == null) return;
            List<Path> directories;
            try (var entries = Files.list(runs)) { directories = entries.toList(); }
            for (Path directory : directories) {
                var directoryAttributes = attributes(directory);
                if (directoryAttributes == null || !directoryAttributes.isDirectory()) continue;
                Path file = directory.resolve(dev.warden.execution.orca.OrcaLifecycle.FILE_NAME);
                var recordAttributes = attributes(file);
                if (recordAttributes == null) continue;
                try {
                    if (!recordAttributes.isRegularFile() || !Files.isReadable(file)) {
                        throw new IOException("lifecycle record is not a readable regular file");
                    }
                    var lifecycle = new dev.warden.execution.orca.OrcaLifecycle(root,
                            directory.getFileName().toString());
                    if (lifecycle.read().active().isEmpty()) continue;
                } catch (IOException | IllegalArgumentException unreadable) {
                    throw new Refused("worktree_recovery_required", file,
                            "Cannot account for a previous Orca worker. Inspect its lifecycle record "
                                    + file + " before starting a new workflow or direct/local role.");
                }
                throw new Refused("worktree_recovery_required", file,
                        "An Orca worker is still recorded active in " + file
                                + ". Resume that exact role/run through its Orca profile to reconcile "
                                + "the dispatch, or inspect and fence it before starting another workflow "
                                + "or direct/local role.");
            }
        } catch (IOException uncertain) {
            if (uncertain instanceof Refused refused) throw refused;
            throw new Refused("worktree_recovery_required", runs,
                    "Cannot inspect previous worker records in " + runs
                            + ". Restore read access before starting another workflow.");
        }
    }

    /** Record launch intent before start(): an interrupted PID handoff must fail closed. */
    public static void startingVendor() throws IOException {
        Owner owner = CURRENT.get().peek();
        if (owner != null) synchronized (owner) {
            owner.state.put("vendor_launch_pending", true);
            owner.persist();
        }
    }

    public static void vendorNotStarted() throws IOException {
        Owner owner = CURRENT.get().peek();
        if (owner != null) synchronized (owner) {
            owner.state.put("vendor_launch_pending", false);
            owner.persist();
        }
    }

    /** Only vendor processes belong here; gate/build daemons do not own vendor writes. */
    public static void track(ProcessHandle process) throws IOException {
        Owner owner = CURRENT.get().peek();
        if (owner != null) synchronized (owner) {
            ProcessHandle previous = owner.processes.get(process.pid());
            if (previous == null || !previous.isAlive()) {
                owner.processes.put(process.pid(), process);
                owner.receipts.put(process.pid(), identity(process));
                owner.state.put("processes", List.copyOf(owner.receipts.values()));
                owner.state.put("vendor_launch_pending", false);
                owner.persist();
            }
        }
    }

    /** Never remove lock files: recovery competes for the same OS lock as admission. */
    public static Map<String, Object> recover(Path checkout, boolean confirmStopped) throws IOException {
        Path directory = administration(checkoutRoot(checkout));
        Files.createDirectories(directory);
        Path file = directory.resolve(STATE);
        try (FileChannel channel = FileChannel.open(directory.resolve(LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) throw new Refused("worktree_busy", file, "The worktree controller is still running.");
            try (FileLock held = lock) {
                if (attributes(file) == null) return Map.of("ok", true, "code", "worktree_recovered");
                Map<String, Object> previous;
                try {
                    if (!attributes(file).isRegularFile()) throw new IOException("not a regular state file");
                    previous = Json.parseObject(Files.readString(file));
                } catch (Exception unreadable) {
                    throw new Refused("worktree_recovery_required", file,
                            "Cannot read execution receipts; restore readable state before recovery.");
                }
                if ("released".equals(previous.get("state"))) {
                    return Map.of("ok", true, "code", "worktree_recovered", "execution_state", file.toString());
                }
                List<Object> receipts = new java.util.ArrayList<>();
                if ("active".equals(previous.get("state"))) receipts.add(previous.get("controller"));
                boolean uncertain = !(previous.get("processes") instanceof List<?>)
                        || !(previous.get("schema_version") instanceof Number schema) || schema.longValue() != 2
                        || !("active".equals(previous.get("state")) || "recovery_required".equals(previous.get("state")))
                        || !Boolean.FALSE.equals(previous.get("vendor_launch_pending"))
                        || previous.containsKey("recovery_reason");
                if (previous.get("processes") instanceof List<?> processes) receipts.addAll(processes);
                if (previous.get("active_processes") instanceof List<?> processes) receipts.addAll(processes);
                List<Object> live = new java.util.ArrayList<>();
                for (Object receipt : receipts) {
                    if (!(receipt instanceof Map<?, ?> identity) || !(identity.get("pid") instanceof Number pid)) {
                        uncertain = true;
                        continue;
                    }
                    ProcessHandle process = ProcessHandle.of(pid.longValue()).orElse(null);
                    if (process == null || !process.isAlive()) continue;
                    Object started = identity.get("started_at");
                    var actual = process.info().startInstant();
                    if (!(started instanceof String) || actual.isEmpty()) { uncertain = true; live.add(receipt); }
                    else if (actual.get().toString().equals(started)) live.add(receipt);
                }
                if (!live.isEmpty()) throw new Refused("worktree_recovery_required", file,
                        "Previous processes are still alive: " + Json.write(live) + ". Stop them before recovery.");
                if (uncertain && !confirmStopped) throw new Refused("worktree_recovery_required", file,
                        "Process receipts are incomplete. Inspect and stop previous vendor workers, then "
                                + "run `warden recover --confirm-stopped` to acknowledge that inspection.");
                Map<String, Object> recovered = new LinkedHashMap<>(previous);
                recovered.put("state", "released");
                recovered.put("recovered_at", Instant.now().toString());
                recovered.put("recovery_confirmed_by_operator", confirmStopped);
                JsonFile.writeAtomically(file, recovered);
                return Map.of("ok", true, "code", "worktree_recovered", "execution_state", file.toString());
            }
        }
    }

    private static Path checkoutRoot(Path checkout) throws IOException {
        Path requested = checkout.toRealPath();
        for (Path candidate = requested; candidate != null; candidate = candidate.getParent()) {
            if (attributes(candidate.resolve(".git")) != null) return candidate;
        }
        return requested;
    }

    private static Map<String, Object> identity(ProcessHandle process) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("pid", process.pid());
        process.info().startInstant().ifPresent(started -> identity.put("started_at", started.toString()));
        return identity;
    }

    private static Path administration(Path root) throws IOException {
        Path dotGit = root.resolve(".git");
        var gitAttributes = attributes(dotGit);
        if (gitAttributes != null && gitAttributes.isDirectory()) return dotGit.toRealPath();
        if (gitAttributes != null && gitAttributes.isRegularFile()) {
            String pointer = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (!pointer.startsWith("gitdir:") || pointer.substring(7).isBlank()) {
                throw new IOException("Cannot locate this worktree's Git administration directory");
            }
            Path target = Path.of(pointer.substring(7).strip());
            return (target.isAbsolute() ? target : root.resolve(target)).toRealPath();
        }
        if (gitAttributes != null) throw new IOException("Invalid worktree Git administration path: " + dotGit);
        // Bootstrap/tests may not have Git yet. This remains checkout-local and stays
        // outside the contract snapshot; a later Git check still decides repository validity.
        return root.resolve(".warden/runs");
    }

    private static java.nio.file.attribute.BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        }
    }

    private static final class Owner {
        final Path root;
        final Path stateFile;
        final FileChannel channel;
        final FileLock lock;
        final Map<String, Object> state;
        final Thread thread = Thread.currentThread();
        final Map<Long, ProcessHandle> processes = new ConcurrentHashMap<>();
        final Map<Long, Map<String, Object>> receipts = new LinkedHashMap<>();
        volatile String recoveryReason;
        final Thread shutdownHook;
        int references = 1;
        boolean closed;

        synchronized void persist() throws IOException {
            state.put("updated_at", Instant.now().toString());
            JsonFile.writeAtomically(stateFile, state);
        }

        private synchronized void shutdown() {
            if (closed) return;
            try {
                // JVM shutdown/Ctrl+C: stop only the vendor census, never build daemons.
                processes.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                while (processes.values().stream().anyMatch(ProcessHandle::isAlive)
                        && System.nanoTime() < deadline) Thread.sleep(25);
                processes.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                while (processes.values().stream().anyMatch(ProcessHandle::isAlive)
                        && System.nanoTime() < deadline) Thread.sleep(25);
                boolean safe = processes.values().stream().noneMatch(ProcessHandle::isAlive)
                        && !Boolean.TRUE.equals(state.get("vendor_launch_pending")) && recoveryReason == null;
                state.put("state", safe ? "released" : "recovery_required");
                persist();
            } catch (Exception ignored) { /* durable active state remains a recovery fence */ }
        }

        Owner(Path root, Path stateFile, FileChannel channel, FileLock lock, Map<String, Object> state) {
            this.root = root;
            this.stateFile = stateFile;
            this.channel = channel;
            this.lock = lock;
            this.state = state;
            shutdownHook = new Thread(this::shutdown, "warden-vendor-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }
}
