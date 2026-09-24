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
                yield ok(Map.of("ready", true, "dispatchId", lastDispatch, "taskId", String.valueOf(lastTask),
                        "launch", lastLaunch));
            }
            case "orchestration worker-show" -> ok(Map.of(
                    "dispatch", Map.of("id", String.valueOf(lastDispatch), "task_id", String.valueOf(lastTask),
                            "status", "dispatched"),
                    "worker", Map.of("agent_terminal_handle", "worker",
                            "startOptions", Map.of("launch", lastLaunch))));
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

    public static OrcaClient.Rpc ok(Map<String, Object> result) {
        Map<String, Object> body = Map.of("ok", true, "result", result);
        return new OrcaClient.Rpc(true, 0, false, 0, body, result, Json.write(body), "");
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
