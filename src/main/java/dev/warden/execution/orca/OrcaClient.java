package dev.warden.execution.orca;

import dev.warden.execution.DirectCliExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orca CLI RPC. Mutating worker lifecycle lives in {@link OrcaExecutor}; this class is the
 * thin JSON transport plus the read-only status/worktree probes.
 *
 * Every call goes through {@code --json}. Completion is never inferred here — callers classify
 * receipts with {@link OrcaSettlement}.
 */
public final class OrcaClient {

    public record Rpc(boolean ok, int exitCode, boolean timedOut, long durationMillis,
                      Map<String, Object> envelope, Map<String, Object> result,
                      String stdout, String stderr) {}

    private final ProcessRunner processes;

    public OrcaClient(ProcessRunner processes) { this.processes = processes; }

    public Map<String, Object> status(Path workingDirectory) {
        try {
            Rpc rpc = invoke(workingDirectory, Duration.ofSeconds(15), List.of("status"));
            if (!rpc.ok() || rpc.envelope().isEmpty()) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("available", false);
                summary.put("reason", rpc.timedOut() ? "timeout" : "exit_" + rpc.exitCode());
                summary.put("stderr", rpc.stderr());
                return summary;
            }
            return summarize(rpc.envelope());
        } catch (Exception failure) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("available", false);
            summary.put("reason", failure.getClass().getSimpleName());
            summary.put("message", String.valueOf(failure.getMessage()));
            return summary;
        }
    }

    public Rpc invoke(Path workingDirectory, Duration timeout, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(DirectCliExecutor.resolveExecutable("orca", workingDirectory));
        command.addAll(args);
        if (!command.contains("--json")) command.add("--json");
        ProcessRunner.Result process = processes.run(command, workingDirectory, timeout, 4 * 1024 * 1024);
        Map<String, Object> envelope = Json.findLastObject(process.stdout());
        if (envelope == null) envelope = Map.of();
        Map<String, Object> result = OrcaSettlement.resultOf(envelope);
        boolean ok = process.ok() && (envelope.isEmpty() || OrcaSettlement.envelopeOk(envelope));
        return new Rpc(ok, process.exitCode(), process.timedOut(), process.durationMillis(),
                envelope, result, process.stdout(), process.stderr());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> summarize(Map<String, Object> envelope) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Object rawResult = envelope.get("result");
        if (!(rawResult instanceof Map<?, ?> result)) {
            summary.put("available", false);
            summary.put("reason", "invalid_status_envelope");
            return summary;
        }
        Object rawRuntime = result.get("runtime");
        Object rawApp = result.get("app");
        Map<String, Object> runtime = rawRuntime instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> app = rawApp instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        boolean running = Boolean.TRUE.equals(app.get("running"));
        boolean reachable = Boolean.TRUE.equals(runtime.get("reachable"));
        summary.put("available", running && reachable);
        summary.put("app_running", running);
        summary.put("runtime_reachable", reachable);
        summary.put("runtime_state", runtime.get("state"));
        summary.put("runtime_id", runtime.get("runtimeId"));
        summary.put("app_version", runtime.get("appVersion"));
        Object capabilities = runtime.get("capabilities");
        boolean orchestration = capabilities instanceof List<?> list
                && list.contains("orchestration.contract.v1");
        summary.put("orchestration_contract", orchestration);
        return summary;
    }
}
