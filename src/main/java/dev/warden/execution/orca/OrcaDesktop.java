package dev.warden.execution.orca;

import dev.warden.json.JsonFile;
import dev.warden.execution.ExecutionActivity;
import dev.warden.run.Progress;
import dev.warden.run.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic desktop projection, without borrowing supervised-worker ownership for an
 * external process. Orca 1.4.233 can project the workspace card, but cannot safely reclaim
 * an observer terminal after user takeover. Record that limitation instead of opening
 * unreclaimable tabs or pretending a direct invocation is a supervised worker.
 *
 * No mutation here launches an agent, changes a runner, or settles a Dispatch. One failed
 * projection opens the circuit for this instance. Receipts contain codes, never CLI output,
 * environment contents, commands, prompts or provider credentials.
 */
public final class OrcaDesktop implements Workspace {
    private static final Duration PROBE = Duration.ofSeconds(1);
    private static final Duration PROJECT = Duration.ofSeconds(2);
    private static final Duration OPEN = Duration.ofSeconds(3);
    private static final String OVERVIEW_LIMIT = "observer_ownership_unsupported";

    public record Policy(boolean interactive, boolean quiet, boolean headless) {
        public static Policy current(boolean quiet) {
            return detect(System.console() != null, quiet, System.getenv());
        }

        public static Policy detect(boolean console, boolean quiet, Map<String, String> environment) {
            boolean headless = false;
            for (String name : List.of("CI", "GITHUB_ACTIONS", "GITLAB_CI", "TF_BUILD", "BUILDKITE")) {
                String value = environment.get(name);
                if (value != null && !value.isBlank() && !value.equalsIgnoreCase("false")
                        && !value.equals("0")) headless = true;
            }
            headless |= environment.containsKey("SSH_CONNECTION") || environment.containsKey("SSH_TTY")
                    || "dumb".equals(environment.get("TERM"));
            return new Policy(console, quiet, headless);
        }

        String refusal() {
            if (quiet) return "quiet";
            if (headless) return "headless";
            if (!interactive) return "noninteractive";
            return null;
        }
    }

    private final OrcaClient client;
    private final Path root;
    private final Progress progress;
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private String selector;
    private Path journal;
    private String lastNote = "";
    private boolean stopped;
    private final java.util.ArrayDeque<Map<String, Object>> activityEvents = new java.util.ArrayDeque<>();
    private long activityEventsSeen;
    private long lastActivityProjection;
    private ExecutionActivity latestActivity;
    private ExecutionActivity pendingActivity;
    private ExecutionActivity displayedActivity;
    private boolean activityFlushScheduled;
    private final java.util.Set<String> warnings = new java.util.LinkedHashSet<>();

    private OrcaDesktop(OrcaClient client, Path root, Progress progress) {
        this.client = client;
        this.root = root.toAbsolutePath().normalize();
        this.progress = progress;
        receipt.put("schema_version", 1L);
        receipt.put("mode", "automatic");
        receipt.put("overview", "unavailable");
        receipt.put("projection", "cli");
        // No surface was created. An empty resource list must not imply terminal ownership.
        receipt.put("resources", List.of());
    }

    /** Resolve only against the execution directory supplied by the loop. Never creates a checkout. */
    public static OrcaDesktop attach(OrcaClient client, Path root, Policy policy, Progress progress) {
        OrcaDesktop desktop = new OrcaDesktop(client, root, progress);
        String refusal = policy.refusal();
        if (refusal != null) {
            desktop.receipt.put("reason", refusal);
            return desktop;
        }
        try {
            OrcaClient.Rpc status = client.invoke(root, PROBE, List.of("status"));
            if (!status.ok()) {
                desktop.fail("runtime_unavailable");
                return desktop;
            }
            Map<String, Object> summary = OrcaClient.summarize(status.envelope());
            if (Boolean.FALSE.equals(summary.get("app_running"))) {
                // At most one bounded start, only for an interactive local desktop invocation.
                desktop.receipt.put("startup_attempted", true);
                if (!client.invoke(root, OPEN, List.of("open")).ok()) {
                    desktop.fail("desktop_start_failed");
                    return desktop;
                }
                status = client.invoke(root, PROBE, List.of("status"));
                summary = OrcaClient.summarize(status.envelope());
            }
            if (!status.ok() || !Boolean.TRUE.equals(summary.get("available"))
                    || !"ready".equals(summary.get("runtime_state"))) {
                desktop.fail("runtime_not_ready");
                return desktop;
            }
            desktop.receipt.put("runtime_id", summary.get("runtime_id"));
            desktop.receipt.put("app_version", summary.get("app_version"));
            OrcaClient.Rpc current = client.invoke(root, PROBE, List.of("worktree", "current"));
            // Do not import folders as a side effect of this partial integration. The supported
            // `repo add --path` route is documented, but an automatic observer still lacks release.
            if (!current.ok() || !desktop.matches(current.result())) {
                desktop.fail("execution_workspace_unregistered");
                return desktop;
            }
            desktop.selector = OrcaSettlement.worktreeSelector(current.envelope());
            if (desktop.selector == null) {
                desktop.fail("execution_workspace_unregistered");
                return desktop;
            }
            desktop.receipt.put("projection", "workspace_card");
            desktop.receipt.put("reason", OVERVIEW_LIMIT);
            desktop.receipt.put("worktree_selector", desktop.selector);
            desktop.warn("Orca card connected; automatic overview unavailable: " + OVERVIEW_LIMIT
                    + ". Continuing with CLI progress; --watch remains an explicit retained view.");
        } catch (Exception failure) {
            desktop.fail("desktop_probe_failed");
        }
        return desktop;
    }

    private boolean matches(Map<String, Object> result) {
        Object item = result.get("worktree");
        if (!(item instanceof Map<?, ?> worktree) || !(worktree.get("path") instanceof String path)) return false;
        try { return Files.isSameFile(root, Path.of(path)); }
        catch (Exception failure) { return false; }
    }

    @Override public synchronized void watch(Path narration, String runId) {
        if (journal != null) return;
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            warn("Desktop receipt unavailable: invalid run identity.");
            return;
        }
        try {
            Path directory = root;
            for (String part : List.of(".warden", "runs", runId)) {
                directory = directory.resolve(part);
                if (Files.isSymbolicLink(directory)) throw new java.io.IOException("linked evidence directory");
                if (!Files.exists(directory)) Files.createDirectory(directory);
                if (!directory.toRealPath().startsWith(root.toRealPath())) {
                    throw new java.io.IOException("evidence directory outside execution root");
                }
            }
            journal = directory.resolve("desktop.json");
        } catch (Exception failure) {
            warn("Desktop receipt unavailable: evidence directory is not contained in the execution root.");
            return;
        }
        receipt.put("run_id", runId);
        receipt.put("owner", "warden:" + runId);
        persist();
    }

    @Override public synchronized void note(String text) {
        lastNote = clip(text);
        displayedActivity = null;
        pendingActivity = null;
        project(List.of("--comment", lastNote));
    }

    @Override public synchronized void state(State state) {
        stopped = state != State.RUNNING;
        if (stopped) pendingActivity = null;
        receipt.put("last_requested_state", state.name().toLowerCase(java.util.Locale.ROOT));
        String column = switch (state) {
            case RUNNING -> "in-progress";
            case WAITING_FOR_HUMAN -> "in-review";
            case SETTLED -> "completed";
        };
        project(List.of("--workspace-status", column));
        persist();
    }

    @Override public synchronized void working(String who, long millis) {
        if (!stopped && !lastNote.isEmpty()) {
            project(List.of("--comment", clip((displayedActivity == null ? lastNote : displayedActivity.summary())
                    + " · " + Progress.elapsed(millis))));
        }
    }

    @Override public synchronized void activity(ExecutionActivity event) {
        if (stopped) return;
        // A callback already in transport may outlive its attempt. Never let it replace
        // a later attempt or restore an earlier attempt after its final event.
        if (latestActivity != null) {
            var previous = latestActivity.attempt();
            var next = event.attempt();
            if (previous.equals(next)
                    && latestActivity.update().kind() == ExecutionActivity.Kind.ATTEMPT_FINISHED) return;
            if (!previous.equals(next) && event.update().kind() != ExecutionActivity.Kind.DISPATCHING) return;
        }
        latestActivity = event;
        activityEventsSeen++;
        if (activityEvents.size() == 24) activityEvents.removeFirst();
        activityEvents.addLast(event.toMap());
        long now = System.nanoTime();
        boolean boundary = event.update().kind() == ExecutionActivity.Kind.DISPATCHING
                || event.update().kind() == ExecutionActivity.Kind.ATTEMPT_FINISHED;
        if (!boundary && now - lastActivityProjection < 1_000_000_000L) {
            // Keep a meaningful tool/message event ahead of generic byte progress.
            if (pendingActivity == null || event.update().kind() != ExecutionActivity.Kind.OUTPUT) pendingActivity = event;
            if (!activityFlushScheduled) {
                activityFlushScheduled = true;
                Thread.ofVirtual().name("warden-desktop-projection").start(this::flushActivity);
            }
            return;
        }
        pendingActivity = null;
        projectActivity(event, now);
    }

    private void flushActivity() {
        try {
            while (true) {
                long wait;
                synchronized (this) {
                    if (stopped || pendingActivity == null) return;
                    wait = 1_000_000_000L - (System.nanoTime() - lastActivityProjection);
                    if (wait <= 0) {
                        ExecutionActivity event = pendingActivity;
                        pendingActivity = null;
                        projectActivity(event, System.nanoTime());
                        return;
                    }
                }
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
            }
        } catch (InterruptedException closed) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                activityFlushScheduled = false;
                if (!stopped && pendingActivity != null) {
                    activityFlushScheduled = true;
                    Thread.ofVirtual().name("warden-desktop-projection").start(this::flushActivity);
                }
            }
        }
    }

    private void projectActivity(ExecutionActivity event, long now) {
        lastActivityProjection = now;
        displayedActivity = event;
        receipt.put("activity_events_seen", activityEventsSeen);
        receipt.put("activity_events_retained", List.copyOf(activityEvents));
        receipt.put("activity_projection", "lossy_metadata_only");
        project(List.of("--comment", clip(event.summary() + " · " + Progress.elapsed(event.elapsedMillis()))));
        persist();
    }

    @Override public synchronized void answered(String decision) {
        stopped = true;
        note("Warden decision: " + decision);
    }

    private void project(List<String> fields) {
        if (selector == null) return;
        var args = new java.util.ArrayList<>(List.of("worktree", "set", "--worktree", selector));
        args.addAll(fields);
        try {
            OrcaClient.Rpc result = client.invoke(root, PROJECT, args);
            if (!result.ok()) {
                receipt.put("projection_failure", Map.of("timed_out", result.timedOut(),
                        "exit_code", result.exitCode(), "duration_millis", result.durationMillis()));
                fail("projection_failed");
            }
        } catch (Exception failure) {
            receipt.put("projection_failure", Map.of("kind", "transport_exception"));
            fail("projection_failed");
        }
    }

    private void fail(String reason) {
        selector = null;
        receipt.put("projection", "cli");
        receipt.put("reason", reason);
        warn("Orca desktop fallback: " + reason + "; continuing with CLI progress.");
        persist();
    }

    private void persist() {
        if (journal == null) return;
        receipt.put("updated_at", Instant.now().toString());
        try { JsonFile.writeAtomically(journal, receipt); }
        catch (Exception failure) { warn("Desktop receipt could not be saved; CLI evidence remains authoritative."); }
    }

    private void warn(String text) {
        if (warnings.size() >= 3 || !warnings.add(text)) return;
        try { progress.line(text); } catch (RuntimeException failure) { /* UI cannot fail work. */ }
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").strip();
        return flat.length() <= 140 ? flat : flat.substring(0, 139) + "…";
    }
}
