package dev.warden.execution.orca;

import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Orca capability probe. Mutating worker lifecycle is added only after live conformance. */
public final class OrcaClient {
    private final ProcessRunner processes;

    public OrcaClient(ProcessRunner processes) { this.processes = processes; }

    public Map<String, Object> status(Path workingDirectory) {
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            ProcessRunner.Result result = processes.run(
                    List.of("orca", "status", "--json"), workingDirectory, Duration.ofSeconds(15));
            if (!result.ok()) {
                summary.put("available", false);
                summary.put("reason", result.timedOut() ? "timeout" : "exit_" + result.exitCode());
                summary.put("stderr", result.stderr());
                return summary;
            }
            return summarize(Json.parseObject(result.stdout()));
        } catch (Exception failure) {
            summary.put("available", false);
            summary.put("reason", failure.getClass().getSimpleName());
            summary.put("message", String.valueOf(failure.getMessage()));
            return summary;
        }
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
