package dev.warden.execution.orca;

import dev.warden.process.ProcessRunner;
import dev.warden.json.Json;
import dev.warden.execution.WorktreeLease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code warden probe orca}: the verification probe for a {@code runner: orca} profile.
 *
 * A direct profile's probe calls the vendor CLI. An Orca profile does not run that CLI; it
 * runs whatever Orca's {@code worker-start} launches for an agent, model and effort, so a
 * probe of the CLI says nothing about it. The shipped Orca examples therefore had no probe,
 * and `warden profiles --verify` — the command Warden itself advised for an Orca twin —
 * refused them for having none.
 *
 * This goes through the channel an Orca profile actually uses: a coordinator terminal, a
 * Run and a Task, {@code worker-start} with the agent, model and effort, and a typed
 * {@code worker_done} read back. The worker is asked for a word it can only get by reading a
 * file Warden has just written into the worktree — as text, or with {@code --image} drawn
 * in a picture — and the word is new on every probe, so a pass proves launch, tool access
 * and settlement rather than a process that ran or a model that guessed. The file is removed
 * afterwards on accepted settlement. An uncertain worker keeps its coordinator, fixture and
 * recovery journal; losing a wait response must never authorize stopping a live agent.
 *
 * Prints {@code probe-answer: matched} only when the answer is that word. Bounded by
 * {@code --wait-minutes}. Every Orca call goes through {@link OrcaClient}, so it runs the
 * same on Windows and POSIX and no argument passes through a shell.
 */
public final class OrcaProbe {

    public record Options(String agent, String model, String effort, boolean image, Path worktree,
                          int waitMinutes) {}

    public record Outcome(int exitCode, List<String> lines) {
        public boolean ok() { return exitCode == 0; }
    }

    /** A word the worker has to read, never one it could know. */
    private static final List<String> WORDS = List.of("MAGNOLIA", "LANTERN", "HARBOR", "CEDAR",
            "ORBIT", "VELVET", "COMPASS", "MEADOW", "GRANITE", "FALCON", "SAFFRON", "TIMBER");

    private static final Duration CALL = Duration.ofSeconds(30);
    private static final Duration START = Duration.ofMinutes(3);
    private static final Duration CHECK_WINDOW = Duration.ofSeconds(60);

    private final OrcaClient orca;
    private final SecureRandom random = new SecureRandom();

    public OrcaProbe(ProcessRunner processes) { this(new OrcaClient(processes)); }

    public OrcaProbe(OrcaClient orca) { this.orca = orca; }

    public static Options parse(String[] args) {
        if (args.length < 2 || !"orca".equals(args[1])) {
            throw new IllegalArgumentException("usage: warden probe orca --agent <agent> "
                    + "[--model M] [--effort E] [--image] [--worktree DIR] [--wait-minutes N]");
        }
        String agent = null;
        String model = null;
        String effort = null;
        boolean image = false;
        Path worktree = Path.of(".");
        int wait = 8;
        for (int index = 2; index < args.length; index++) {
            String flag = args[index];
            switch (flag) {
                case "--image" -> image = true;
                case "--agent", "--model", "--effort", "--worktree", "--wait-minutes" -> {
                    if (index + 1 >= args.length) throw new IllegalArgumentException(flag + " needs a value");
                    String value = args[++index];
                    switch (flag) {
                        case "--agent" -> agent = value;
                        case "--model" -> model = value.isBlank() ? null : value;
                        case "--effort" -> effort = value.isBlank() ? null : value;
                        case "--worktree" -> worktree = Path.of(value);
                        default -> {
                            try {
                                wait = Integer.parseInt(value);
                            } catch (NumberFormatException notANumber) {
                                throw new IllegalArgumentException("--wait-minutes must be 1..60");
                            }
                            if (wait < 1 || wait > 60) {
                                throw new IllegalArgumentException("--wait-minutes must be 1..60");
                            }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("probe orca: unknown argument '" + flag + "'");
            }
        }
        if (agent == null || !agent.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("probe orca needs --agent, the Orca agent a "
                    + "profile's command names (claude, codex, grok, ...)");
        }
        return new Options(agent, model, effort, image, worktree.toAbsolutePath().normalize(), wait);
    }

    public Outcome run(Options options) {
        try (WorktreeLease.Scope lease = WorktreeLease.acquire(options.worktree(),
                "probe-orca-" + java.util.UUID.randomUUID())) {
            WorktreeLease.requireLocalDispatch(options.worktree());
            Outcome outcome = runOwned(options);
            // finally may discover a failed release after an otherwise correct answer.
            // Such a probe must not stamp the profile as verified.
            return outcome.lines().stream().anyMatch(line -> line.startsWith("probe-recovery-required:"))
                    ? new Outcome(1, outcome.lines()) : outcome;
        } catch (WorktreeLease.Refused refused) {
            return new Outcome(1, List.of("probe-failed: " + refused.code() + ": " + refused.getMessage()));
        } catch (IOException failed) {
            return new Outcome(1, List.of("probe-failed: " + failed.getMessage()));
        }
    }

    private Outcome runOwned(Options options) {
        List<String> lines = new ArrayList<>();
        String word = WORDS.get(random.nextInt(WORDS.size()));
        String nonce = Long.toHexString(random.nextLong() & Long.MAX_VALUE);
        Path fixture = options.worktree().resolve(".warden-probe-" + nonce);
        String handle = null;
        String dispatch = null;
        String runId = null;
        String taskId = null;
        String requestId = java.util.UUID.randomUUID().toString();
        boolean matched = false;
        boolean settled = false;
        boolean startUncertain = false;
        try {
            OrcaClient.Rpc current = call(lines, options.worktree(), CALL, List.of("worktree", "current"));
            String found = OrcaSettlement.worktreeSelector(current.envelope());
            if (!current.ok() || found == null) {
                lines.add("probe-failed: " + options.worktree() + " is not a worktree Orca manages; "
                        + "run the probe from one (profiles --verify fills --worktree with the "
                        + "directory it was run in)");
                return new Outcome(1, lines);
            }
            String selector = OrcaExecutor.selectorArgument(found);

            Files.createDirectories(fixture);
            String relative = fixture.getFileName().toString();
            String spec;
            if (options.image()) {
                writePicture(fixture.resolve("probe.png"), word);
                spec = "Warden profile probe. Open the image " + relative + "/probe.png in this "
                        + "worktree with your image-reading tool and read the single word written "
                        + "on it. Do not edit, create or delete any file. Send worker_done exactly "
                        + "once with outcome succeeded and payload key answer set to that word in "
                        + "capitals.";
            } else {
                Files.writeString(fixture.resolve("answer.txt"), "The probe word is " + word + ".\n",
                        StandardCharsets.UTF_8);
                spec = "Warden profile probe. Read the file " + relative + "/answer.txt in this "
                        + "worktree and find the probe word in it. Do not edit, create or delete any "
                        + "file. Send worker_done exactly once with outcome succeeded and payload key "
                        + "answer set to that word in capitals.";
            }

            OrcaClient.Rpc terminal = call(lines, options.worktree(), CALL, List.of("terminal", "create",
                    "--worktree", selector, "--title", "warden-probe-coordinator"));
            handle = nested(terminal.result(), "terminal", "handle");
            if (!terminal.ok() || handle == null) return failed(lines, "no coordinator terminal");

            OrcaClient.Rpc run = call(lines, options.worktree(), CALL, List.of("orchestration", "run-create",
                    "--objective", "warden profile probe", "--from", handle));
            runId = nested(run.result(), "run", "id");
            if (!run.ok() || runId == null) return failed(lines, "no orchestration run");

            OrcaClient.Rpc task = call(lines, options.worktree(), CALL, List.of("orchestration", "task-create",
                    "--spec", spec, "--task-title", "warden profile probe", "--run", runId, "--from", handle));
            taskId = OrcaSettlement.taskId(task.envelope());
            if (!task.ok() || taskId == null) return failed(lines, "no orchestration task");

            List<String> start = new ArrayList<>(List.of("orchestration", "worker-start",
                    "--task", taskId, "--worktree", selector, "--agent", options.agent()));
            if (options.model() != null) start.addAll(List.of("--model", options.model()));
            if (options.effort() != null) start.addAll(List.of("--effort", options.effort()));
            start.addAll(List.of("--run", runId, "--from", handle,
                    "--retry-request", requestId,
                    "--timeout-ms", String.valueOf(START.toMillis())));
            journal(fixture, handle, runId, taskId, null, requestId, "starting");
            WorktreeLease.startingVendor();
            startUncertain = true;
            OrcaClient.Rpc started = call(lines, options.worktree(), START.plusSeconds(30), start);
            dispatch = OrcaSettlement.dispatchId(started.envelope());
            Files.writeString(fixture.resolve("start-receipt.json"), Json.write(started.envelope()), StandardCharsets.UTF_8);
            // A definite preflight refusal has no worker. A lost/partial receipt may have one.
            if (dispatch == null && !started.timedOut()
                    && started.envelope().get("error") instanceof Map<?, ?> error
                    && "invalid_argument".equals(error.get("code"))) startUncertain = false;
            journal(fixture, handle, runId, taskId, dispatch, requestId, "waiting");
            // Orca 1.4.209 exits 1 when it could not see the agent's turn begin, and settles
            // the dispatch normally if the agent then answers; the wait below is what decides.
            boolean unobserved = OrcaSettlement.turnUnobserved(started.envelope());
            if (unobserved) lines.add("probe-note: Orca could not see the agent's turn start; waiting for its answer");
            if ((!started.ok() && !unobserved) || dispatch == null) {
                return failed(lines, "worker-start did not return a dispatch");
            }

            long deadline = System.nanoTime() + Duration.ofMinutes(options.waitMinutes()).toNanos();
            while (System.nanoTime() < deadline) {
                OrcaClient.Rpc checked = call(lines, options.worktree(), CHECK_WINDOW.plusSeconds(30),
                        List.of("orchestration", "check", "--wait",
                                "--types", "worker_done,escalation,question",
                                "--timeout-ms", String.valueOf(CHECK_WINDOW.toMillis()),
                                "--run", runId, "--terminal", handle));
                OrcaSettlement.Outcome seen = OrcaSettlement.fromCheck(checked.envelope(), taskId, dispatch);
                if (seen.deliveryId() != null) {
                    call(lines, options.worktree(), CALL, List.of("orchestration", "check",
                            "--ack", seen.deliveryId(), "--terminal", handle, "--run", runId));
                }
                if (seen.kind() == OrcaSettlement.Kind.COMPLETED || seen.kind() == OrcaSettlement.Kind.FAILED) {
                    settled = true;
                    journal(fixture, handle, runId, taskId, dispatch, requestId, "settled");
                    Object answer = seen.payload() == null ? null : seen.payload().get("answer");
                    String said = answer == null ? "" : String.valueOf(answer).strip()
                            .toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
                    matched = seen.kind() == OrcaSettlement.Kind.COMPLETED && word.equals(said);
                    lines.add(matched ? "probe-answer: matched"
                            : "probe-answer: mismatched (asked for the word in the file, got '"
                                    + (answer == null ? "" : answer) + "', outcome " + seen.reason() + ")");
                    break;
                }
                if (seen.kind() == OrcaSettlement.Kind.QUESTION || seen.kind() == OrcaSettlement.Kind.ESCALATED) {
                    lines.add("probe-blocked: the worker sent " + seen.type() + " instead of an answer; "
                            + "a trust question or an update prompt in its terminal is the usual cause");
                    break;
                }
            }
            if (!matched && lines.stream().noneMatch(line -> line.startsWith("probe-"))) {
                lines.add("probe-failed: no worker_done within " + options.waitMinutes() + " min");
            }
            return new Outcome(matched ? 0 : 1, lines);
        } catch (Exception failure) {
            return failed(lines, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            boolean cleaned = !startUncertain;
            if (cleaned && taskId != null && !settled) {
                try {
                    OrcaClient.Rpc cancelled = call(lines, options.worktree(), CALL,
                            List.of("orchestration", "task-update", "--id", taskId,
                                    "--status", "failed", "--result", "probe did not start a worker",
                                    "--run", runId, "--from", handle));
                    cleaned = cancelled.ok();
                } catch (Exception unavailable) {
                    cleaned = false;
                    lines.add("probe-cleanup: task cancellation uncertain: " + unavailable.getMessage());
                }
            }
            if (dispatch != null && settled) {
                try {
                    OrcaClient.Rpc released = call(lines, options.worktree(), CALL,
                            List.of("orchestration", "worker-release", "--dispatch", dispatch));
                    cleaned = released.ok()
                            && !List.of("release_pending", "release_unknown").contains(
                                    String.valueOf(released.result().get("state")));
                } catch (Exception unavailable) {
                    lines.add("probe-cleanup: release uncertain: " + unavailable.getMessage());
                }
            }
            if (cleaned && handle != null) {
                try {
                    cleaned = call(lines, options.worktree(), CALL,
                            List.of("terminal", "close", "--terminal", handle, "--tab")).ok();
                } catch (Exception unavailable) {
                    cleaned = false;
                    lines.add("probe-cleanup: coordinator close uncertain: " + unavailable.getMessage());
                }
            }
            if (cleaned) {
                try { WorktreeLease.vendorNotStarted(); }
                catch (IOException failed) { lines.add("probe-cleanup: cannot clear launch intent: " + failed.getMessage()); }
                deleteTree(fixture);
            }
            else {
                WorktreeLease.requireRecovery("Orca probe has unsettled resources: " + fixture.resolve("recovery.json"));
                lines.add("probe-recovery-required: preserved " + fixture.resolve("recovery.json")
                        + "; coordinator=" + handle + ", run=" + runId + ", dispatch=" + dispatch
                        + ". Inspect the worker before stopping or retrying; a timeout does not prove exit.");
            }
        }
    }

    private OrcaClient.Rpc call(List<String> lines, Path worktree, Duration timeout, List<String> args)
            throws Exception {
        OrcaClient.Rpc rpc = orca.invoke(worktree, timeout, args);
        lines.add("orca " + String.join(" ", args.subList(0, Math.min(2, args.size())))
                + (rpc.ok() ? " ok" : " failed " + failure(rpc)));
        return rpc;
    }

    private static String failure(OrcaClient.Rpc rpc) {
        if (rpc.envelope().get("error") instanceof Map<?, ?> error) {
            return String.valueOf(error.get("code")) + ": " + String.valueOf(error.get("message"));
        }
        return "exit=" + rpc.exitCode() + (rpc.timedOut() ? " timeout" : "")
                + (rpc.stderr() == null ? "" : " " + rpc.stderr().strip());
    }

    private static void journal(Path fixture, String handle, String run, String task,
                                String dispatch, String request, String state) throws IOException {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schema_version", 1); data.put("state", state);
        data.put("coordinator", handle); data.put("run_id", run);
        data.put("task_id", task); data.put("dispatch_id", dispatch);
        data.put("start_request_id", request);
        Path temporary = fixture.resolve("recovery.json.tmp");
        Files.writeString(temporary, Json.write(data), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, fixture.resolve("recovery.json"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, fixture.resolve("recovery.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Outcome failed(List<String> lines, String why) {
        lines.add("probe-failed: " + why);
        return new Outcome(1, lines);
    }

    /** The word, large and black on white, for a vision profile to read. */
    private static void writePicture(Path file, String word) throws IOException {
        System.setProperty("java.awt.headless", "true");
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(640, 200,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = picture.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, 640, 200);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 72));
            graphics.drawString(word, 40, 130);
        } finally {
            graphics.dispose();
        }
        javax.imageio.ImageIO.write(picture, "png", file.toFile());
    }

    private static String nested(Map<String, Object> result, String outer, String inner) {
        if (result == null || !(result.get(outer) instanceof Map<?, ?> map)) return null;
        Object value = map.get(inner);
        return value == null ? null : String.valueOf(value);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover probe directory holds one word and nothing else.
        }
    }
}
