package dev.warden.testing;

import dev.warden.execution.orca.OrcaClient;
import dev.warden.json.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An Orca that answers in memory and remembers every call, for tests of the parts of Warden
 * that talk to it. Each terminal, Run, Task, dispatch and gate it creates gets a fresh id, so a
 * test can tell "one Run" from "two Runs that happen to share a name".
 */
public final class FakeOrca {

    private final List<List<String>> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();
    private final String worktreeId;
    /** What a {@code check --wait} returns for the dispatch it is asked about; null for nothing yet. */
    private volatile Function<List<String>, Map<String, Object>> answer = args -> null;
    private volatile boolean orcaWorktree = true;
    private volatile String lastTask;
    private volatile String lastDispatch;
    private volatile Map<String, Object> lastLaunch = Map.of();
    private volatile Start start = Start.READY;
    /** What worker-show says about the worker right now; see {@link #showing}. */
    private volatile Supplier<Map<String, String>> view = Map::of;

    /** How worker-start answers. */
    public enum Start {
        /** {@code ready: true}. */
        READY,
        /**
         * Orca 1.4.209 after it typed the task and saw no turn begin in 30 s: exit 1 with
         * {@code ok: true}, {@code state: outcome_unknown}, {@code turnStart: unobserved}.
         */
        TURN_UNOBSERVED,
        /** Exit 1 with {@code state: failed}, as a start Orca gave up on. */
        FAILED
    }

    public FakeOrca(Path worktree) {
        this.worktreeId = "repo::" + worktree.toAbsolutePath().normalize();
    }

    public OrcaClient client() {
        return new OrcaClient((directory, timeout, args) -> invoke(args));
    }

    public List<List<String>> calls() { return List.copyOf(calls); }

    /** Every call whose first two words are {@code verb}, e.g. "orchestration run-create". */
    public List<List<String>> calls(String verb) {
        List<List<String>> matching = new ArrayList<>();
        for (List<String> call : calls) {
            if (String.join(" ", call.subList(0, Math.min(2, call.size()))).equals(verb)) matching.add(call);
        }
        return matching;
    }

    /** The value after {@code flag} in a call, or null. */
    public static String option(List<String> call, String flag) {
        int at = call.indexOf(flag);
        return at < 0 || at + 1 >= call.size() ? null : call.get(at + 1);
    }

    /** The Task and dispatch of the last worker-start, for an answer that has to name them. */
    public String lastTask() { return lastTask; }

    public String lastDispatch() { return lastDispatch; }

    public FakeOrca answering(Function<List<String>, Map<String, Object>> next) {
        this.answer = next;
        return this;
    }

    public FakeOrca notAWorktree() {
        this.orcaWorktree = false;
        return this;
    }

    public FakeOrca starting(Start start) {
        this.start = start;
        return this;
    }

    /**
     * The worker as each worker-show finds it: {@code state} (the worker's own, e.g.
     * {@code start_unknown}), {@code screen} (the agent tab's preview), {@code heartbeat} (when
     * the agent last sent one) and {@code activity} (Orca's fleet status, e.g. {@code working}).
     * Asked on every call, so a test can change the answer as its checks go by.
     */
    public FakeOrca showing(Supplier<Map<String, String>> view) {
        this.view = view;
        return this;
    }

    private synchronized String next(String kind) {
        int value = counters.merge(kind, 1, Integer::sum);
        return kind + "-" + value;
    }

    private OrcaClient.Rpc invoke(List<String> args) {
        calls.add(List.copyOf(args));
        String verb = String.join(" ", args.subList(0, Math.min(2, args.size())));
        if ("status".equals(args.get(0))) {
            return ok(Map.of("app", Map.of("running", true), "runtime", Map.of("reachable", true,
                    "capabilities", List.of("orchestration.contract.v1",
                            "orchestration.worker-launch-preferences.v1"))));
        }
        return switch (verb) {
            case "worktree current" -> orcaWorktree
                    ? ok(Map.of("worktree", Map.of("id", worktreeId)))
                    : refused("not_an_orca_worktree");
            case "worktree set", "terminal rename", "terminal close", "orchestration worker-read",
                    "orchestration task-update", "orchestration run-use" -> ok(Map.of());
            case "terminal create", "terminal show" -> ok(Map.of("terminal", Map.of("handle", next("terminal"))));
            case "orchestration run-create" -> ok(Map.of("run", Map.of("id", next("run"))));
            case "orchestration task-create" -> ok(Map.of("task", Map.of("id", next("task"))));
            case "orchestration worker-list" -> ok(Map.of("workers", List.of()));
            case "orchestration worker-start" -> {
                lastTask = option(args, "--task");
                lastDispatch = next("dispatch");
                lastLaunch = launch(args);
                yield switch (start) {
                    case READY -> ok(Map.of("ready", true, "dispatchId", lastDispatch,
                            "taskId", String.valueOf(lastTask), "launch", lastLaunch));
                    case TURN_UNOBSERVED -> exitOne(turnUnobserved(args));
                    case FAILED -> exitOne(Map.of("dispatchId", lastDispatch,
                            "taskId", String.valueOf(lastTask), "state", "failed",
                            "stage", "agent_launch", "lastError", "agent_prompt_stalled",
                            "launch", lastLaunch));
                };
            }
            case "orchestration worker-show" -> ok(workerShow());
            case "orchestration dispatch-show" -> ok(Map.of("dispatch", Map.of(
                    "id", String.valueOf(lastDispatch), "task_id", String.valueOf(lastTask),
                    "status", "dispatched")));
            case "orchestration worker-stop" -> ok(Map.of("stopped", true));
            case "orchestration worker-release" -> ok(Map.of("released", true, "releaseState", "released"));
            case "orchestration gate-create" -> ok(Map.of("gate", Map.of("id", next("gate"))));
            case "orchestration check" -> {
                if (args.contains("--ack")) yield ok(Map.of());
                Map<String, Object> delivered = answer.apply(args);
                yield ok(delivered == null ? Map.of("count", 0) : delivered);
            }
            default -> throw new IllegalStateException("unexpected Orca call " + args);
        };
    }

    /** The receipt Orca gives: what was asked for, launched as asked. */
    private static Map<String, Object> launch(List<String> args) {
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("agent", option(args, "--agent"));
        if (option(args, "--model") != null) requested.put("model", option(args, "--model"));
        if (option(args, "--effort") != null) requested.put("effort", option(args, "--effort"));
        return Map.of("requested", requested, "effective", requested);
    }

    /** The receipt, field for field, that Orca 1.4.209 gave a Claude look on 2026-09-24. */
    private Map<String, Object> turnUnobserved(List<String> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", option(args, "--run"));
        result.put("taskId", String.valueOf(lastTask));
        result.put("dispatchId", lastDispatch);
        result.put("state", "outcome_unknown");
        result.put("stage", "turn_start_unobserved");
        result.put("turnStart", "unobserved");
        result.put("lastError", "Dispatch input was written and submitted, but claude's turn start "
                + "could not be verified during observation (up to 30s). This is unverifiable, "
                + "not proof the worker is dead.");
        result.put("launch", lastLaunch);
        result.put("effects", List.of(
                Map.of("kind", "terminal", "role", "agent", "action", "created", "id", "worker"),
                Map.of("kind", "dispatch_input", "role", "agent", "id", "worker", "state", "accepted"),
                Map.of("kind", "dispatch_input", "role", "agent", "id", "worker", "state", "turn_unobserved")));
        result.put("nextCommands", List.of(
                "orca orchestration worker-show --dispatch " + lastDispatch + " --json"));
        return result;
    }

    private Map<String, Object> workerShow() {
        Map<String, String> now = view.get();
        Map<String, Object> dispatch = new LinkedHashMap<>(Map.of("id", String.valueOf(lastDispatch),
                "task_id", String.valueOf(lastTask), "status", "dispatched"));
        if (now.get("heartbeat") != null) dispatch.put("lastHeartbeatAt", now.get("heartbeat"));
        Map<String, Object> worker = new LinkedHashMap<>(Map.of("agent_terminal_handle", "worker",
                "startOptions", Map.of("launch", lastLaunch)));
        if (now.get("state") != null) worker.put("state", now.get("state"));
        Map<String, Object> result = new LinkedHashMap<>(Map.of("dispatch", dispatch, "worker", worker));
        if (now.get("screen") != null) result.put("terminal", Map.of("handle", "worker", "preview", now.get("screen")));
        if (now.get("activity") != null) {
            result.put("projection", Map.of("stage", Map.of("activity", now.get("activity"))));
        }
        return result;
    }

    public static OrcaClient.Rpc ok(Map<String, Object> result) {
        Map<String, Object> body = Map.of("ok", true, "result", result);
        return new OrcaClient.Rpc(true, 0, false, 0, body, result, Json.write(body), "");
    }

    /** What the Orca CLI does with a worker-start that is not ready: the receipt, and exit 1. */
    private static OrcaClient.Rpc exitOne(Map<String, Object> result) {
        Map<String, Object> body = Map.of("ok", true, "result", result);
        return new OrcaClient.Rpc(false, 1, false, 0, body, result, Json.write(body), "");
    }

    private static OrcaClient.Rpc refused(String code) {
        Map<String, Object> body = Map.of("ok", false, "error", Map.of("code", code));
        return new OrcaClient.Rpc(false, 1, false, 0, body, Map.of(), Json.write(body), code);
    }

    /** One delivery holding a worker_done for the dispatch a check names by run. */
    public static Map<String, Object> workerDone(String task, String dispatch, Map<String, Object> payload) {
        return Map.of("delivery", Map.of("id", "delivery-" + dispatch, "messages", List.of(Map.of(
                "type", "worker_done", "taskId", task, "dispatchId", dispatch, "outcome", "succeeded",
                "payload", payload))));
    }
}
