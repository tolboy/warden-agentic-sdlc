package dev.warden;

import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The durable Orca record. Every check here is about one property: a later process must be
 * able to tell "a worker is running" from "no worker is running" from "I cannot tell", and
 * the third must never collapse into the second.
 */
public final class OrcaLifecycleTest implements Suite {

    @Override public String name() { return "orca-lifecycle"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-lifecycle");
        OrcaLifecycle lifecycle = new OrcaLifecycle(root, "run-1");

        check.eq("a missing record reads as empty, not as an error",
                0, lifecycle.read().workers().size());
        check.eq("an empty record binds no Orca run", null, lifecycle.read().orcaRunId());

        Instant now = Instant.parse("2026-09-04T09:00:00Z");
        OrcaLifecycle.Worker worker = new OrcaLifecycle.Worker(
                OrcaLifecycle.workerKey("reviewer", "run-1--reviewer-1"), "reviewer",
                "task_abc", "ctx_abc", "term_1", "id:repo::C:/p", "repo::C:/p",
                "claude", "opus", "fingerprint-1", OrcaLifecycle.State.ACTIVE, null, null, now, now);
        lifecycle.mutate(OrcaLifecycle.of(snapshot ->
                snapshot.withOrcaRunId("run_orca").withWorker(worker)));

        OrcaLifecycle.Snapshot reread = new OrcaLifecycle(root, "run-1").read();
        check.eq("the Orca run id survives a new reader", "run_orca", reread.orcaRunId());
        check.eq("the dispatch survives a new reader", "ctx_abc",
                reread.worker("reviewer#run-1--reviewer-1").orElseThrow().dispatchId());
        check.eq("the read-only fingerprint survives", "fingerprint-1",
                reread.worker("reviewer#run-1--reviewer-1").orElseThrow().readOnlyFingerprint());
        check.eq("an active worker is listed as active", 1, reread.active().size());
        check.eq("an unknown key finds nothing", true,
                reread.worker("implementer#run-1--implementer-1").isEmpty());

        lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.worker(worker.key())
                .map(found -> snapshot.withWorker(found.at(OrcaLifecycle.State.SETTLED, "completed", now)))
                .orElse(snapshot)));
        check.eq("a settled worker no longer blocks a start",
                0, new OrcaLifecycle(root, "run-1").read().active().size());

        // A run that stopped for a person keeps its worker active on purpose: that is the
        // state a later invocation must attach to instead of starting a rival agent.
        OrcaLifecycle waiting = new OrcaLifecycle(root, "run-2");
        waiting.mutate(OrcaLifecycle.of(snapshot -> snapshot.withWorker(
                worker.at(OrcaLifecycle.State.ACTIVE, "human", "msg_1", now))));
        check.eq("a worker parked on a person stays active", 1, waiting.read().active().size());
        check.eq("and says what it is waiting for", "human",
                waiting.read().active().get(0).awaiting());
        // Written before the question delivery is acknowledged, so the question that has to be
        // answered survives Warden taking delivery of it.
        check.eq("and names the message to answer", "msg_1",
                waiting.read().active().get(0).awaitingMessage());

        OrcaLifecycle.Gate gate = new OrcaLifecycle.Gate("gate_1", "task_gate", "run_orca",
                "term_2", "success", List.of("accept", "reject"),
                "2026-09-04T09:00:00Z", "fingerprint-1", now);
        waiting.mutate(OrcaLifecycle.of(snapshot -> snapshot.withGate(gate)));
        OrcaLifecycle.Gate storedGate = new OrcaLifecycle(root, "run-2").read().gate();
        check.eq("the gate id survives", "gate_1", storedGate.gateId());
        check.eq("the version token survives", "2026-09-04T09:00:00Z", storedGate.decisionUpdatedAt());
        check.eq("the candidate fingerprint survives", "fingerprint-1", storedGate.candidateFingerprint());
        check.eq("the options survive", List.of("accept", "reject"), storedGate.options());

        check.eq("a run directory with a record is discoverable",
                List.of("run-1", "run-2"), OrcaLifecycle.runIds(root));

        // Fail closed. Each of these would, if silently tolerated, let Warden start a second
        // agent beside a worker it simply failed to parse.
        check.eq("an unknown worker state is an error", true,
                unreadable(root, "run-3", Map.of(
                        "schema_version", 1L, "run_id", "run-3",
                        "workers", List.of(Map.of("key", "k", "role", "reviewer",
                                "task_id", "t", "dispatch_id", "d", "state", "probably-running",
                                "created_at", "2026-09-04T09:00:00Z",
                                "updated_at", "2026-09-04T09:00:00Z")))));
        check.eq("an unsupported schema version is an error", true,
                unreadable(root, "run-4", Map.of("schema_version", 99L, "run_id", "run-4")));
        check.eq("a worker with no dispatch id is an error", true,
                unreadable(root, "run-5", Map.of(
                        "schema_version", 1L, "run_id", "run-5",
                        "workers", List.of(Map.of("key", "k", "role", "reviewer", "task_id", "t",
                                "state", "active", "created_at", "2026-09-04T09:00:00Z",
                                "updated_at", "2026-09-04T09:00:00Z")))));

        Path truncated = root.resolve(".warden/runs/run-6/orca.json");
        Files.createDirectories(truncated.getParent());
        Files.writeString(truncated, "{\"schema_version\": 1, \"run_id\"", StandardCharsets.UTF_8);
        boolean refused = false;
        try {
            new OrcaLifecycle(root, "run-6").read();
        } catch (Exception expected) {
            refused = true;
        }
        check.eq("a half-written record is an error, not an empty one", true, refused);

        boolean unsafe = false;
        try {
            new OrcaLifecycle(root, "../escape");
        } catch (IllegalArgumentException expected) {
            unsafe = true;
        }
        check.eq("an unsafe run id is refused", true, unsafe);
    }

    private static boolean unreadable(Path root, String runId, Map<String, Object> content)
            throws Exception {
        Path file = root.resolve(".warden/runs").resolve(runId).resolve("orca.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(content), StandardCharsets.UTF_8);
        try {
            new OrcaLifecycle(root, runId).read();
            return false;
        } catch (Exception expected) {
            return true;
        }
    }
}
