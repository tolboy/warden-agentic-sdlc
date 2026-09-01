package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.approval.ApprovalException;
import dev.warden.approval.HumanDecision;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class ApprovalStoreTest implements Suite {
    @Override public String name() { return "approval-store"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-approval-");
        try {
            pendingSuccessHasStableContract(check, root);
            failureResolvesOnlyOnce(check, root);
            staleAndUnknownChoicesFailClosed(check, root);
            concurrentStoresStillResolveOnlyOnce(check, root);
            readsAndListsDecisions(check, root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void pendingSuccessHasStableContract(Check check, Path root) throws Exception {
        ApprovalStore store = store(root, "2026-08-27T12:00:00Z");
        HumanDecision pending = store.createSuccess("run-success", "task-1", "all gates passed",
                root.resolve(".warden/runs/run-success/summary.json"), "sha256:abc");

        check.eq("success is pending", HumanDecision.State.PENDING, pending.state());
        check.eq("success choices are accept/reject", List.of("accept", "reject"), pending.options());
        check.eq("summary path is project relative", ".warden/runs/run-success/summary.json",
                pending.summaryPath());
        Path target = root.resolve(".warden/runs/run-success/decision.json");
        check.that("decision artifact exists", Files.isRegularFile(target));
        Map<String, Object> json = Json.parseObject(Files.readString(target));
        check.eq("schema version is durable", 1L, json.get("schema_version"));
        check.eq("candidate fingerprint is recorded", "sha256:abc", json.get("candidate_fingerprint"));
        check.eq("pending decision is unresolved", null, json.get("decision"));
        try (var files = Files.list(target.getParent())) {
            check.eq("atomic write leaves only decision and durable lock", 2L, files.count());
        }
        check.that("cross-process lock has a stable inode",
                Files.isRegularFile(target.resolveSibling("decision.json.lock")));
        check.rejects("success cannot silently skip candidate verification",
                "invalid_candidate_fingerprint",
                () -> store.createSuccess("run-no-fingerprint", "task-1", "ready",
                        Path.of("summary.json"), null));
        check.rejects("summary evidence cannot point outside the project",
                "invalid_summary_path",
                () -> store.createFailure("run-outside", "task-1", "failed",
                        root.resolveSibling("outside-summary.json"), null));

        String original = Files.readString(target);
        Map<String, Object> wrongRun = new java.util.LinkedHashMap<>(json);
        wrongRun.put("run_id", "some-other-run");
        Files.writeString(target, Json.writePretty(wrongRun));
        check.rejects("decision content is bound to its run directory", "does not match directory",
                () -> store.read("run-success"));
        Files.writeString(target, original);
    }

    private void failureResolvesOnlyOnce(Check check, Path root) throws Exception {
        ApprovalStore store = store(root, "2026-08-27T12:01:00Z");
        HumanDecision pending = store.createFailure("run-failure", "task-2", "machine gate failed",
                Path.of(".warden/runs/run-failure/summary.json"), "sha256:def");
        HumanDecision resolved = store.resolve("run-failure", pending.updatedAt().toString(),
                "retry", "operator@example.test", "retry after fixing credentials");

        check.eq("failure choices are retry/abort", List.of("retry", "abort"), pending.options());
        check.eq("resolution changes state", HumanDecision.State.RESOLVED, resolved.state());
        check.eq("resolution records choice", "retry", resolved.decision());
        check.eq("resolution records actor", "operator@example.test", resolved.actor());
        check.rejects("duplicate resolution is rejected", "duplicate_decision",
                () -> store.resolve("run-failure", pending.updatedAt().toString(),
                        "abort", "another-operator", "changed mind"));
    }

    private void staleAndUnknownChoicesFailClosed(Check check, Path root) throws Exception {
        ApprovalStore store = store(root, "2026-08-27T12:02:00Z");
        HumanDecision pending = store.createSuccess("run-guarded", "task-3", "review complete",
                Path.of("summary.json"), "sha256:ghi");

        check.rejects("stale optimistic token is rejected", "stale_decision",
                () -> store.resolve("run-guarded", "2026-08-27T11:00:00Z",
                        "accept", "operator", ""));
        check.rejects("unknown choice is rejected", "unknown_decision",
                () -> store.resolve("run-guarded", pending.updatedAt().toString(),
                        "merge", "operator", ""));
        check.rejects("unknown run is rejected", "unknown_run",
                () -> store.resolve("does-not-exist", pending.updatedAt().toString(),
                        "accept", "operator", ""));
        String unknown = "";
        try {
            store.read("no-such-run");
        } catch (Exception thrown) {
            unknown = String.valueOf(thrown.getMessage());
        }
        check.contains("unknown_run names the project that was consulted", unknown,
                root.toAbsolutePath().normalize().toString());
        check.eq("failed attempts leave decision pending", HumanDecision.State.PENDING,
                store.read("run-guarded").state());
        check.rejects("path traversal run id is rejected", "unsafe_run_id",
                () -> store.find("../escape"));
    }

    private void readsAndListsDecisions(Check check, Path root) throws Exception {
        ApprovalStore store = store(root, "2026-08-27T12:03:00Z");
        check.eq("find returns empty for unknown run", false, store.find("missing").isPresent());
        check.eq("read round trips task", "task-1", store.read("run-success").taskId());
        List<HumanDecision> listed = store.list();
        check.eq("list returns all stored decisions", 4, listed.size());
        check.eq("list is stable by run id",
                List.of("run-concurrent", "run-failure", "run-guarded", "run-success"),
                listed.stream().map(HumanDecision::runId).toList());

        // A listing that answers `ok: true` while silently omitting a decision it could not
        // read is the failure this whole command exists to end. `--worktrees` reports such a
        // file per row and has its own reader; plain `status` refuses rather than under-report.
        Path broken = root.resolve(".warden/runs/zz-broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve(ApprovalStore.FILE_NAME), "{");
        check.rejects("an unreadable decision.json is not silently dropped from the listing",
                "zz-broken", store::list);
    }

    private void concurrentStoresStillResolveOnlyOnce(Check check, Path root) throws Exception {
        ApprovalStore firstStore = store(root, "2026-08-27T12:02:30Z");
        ApprovalStore secondStore = store(root, "2026-08-27T12:02:31Z");
        HumanDecision pending = firstStore.createSuccess("run-concurrent", "task-4",
                "ready", Path.of("summary.json"), "sha256:race");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var accept = pool.submit(() -> resolveAfterBarrier(firstStore, pending, "accept", ready, start));
            var reject = pool.submit(() -> resolveAfterBarrier(secondStore, pending, "reject", ready, start));
            ready.await();
            start.countDown();
            List<String> outcomes = List.of(accept.get(), reject.get()).stream().sorted().toList();
            check.eq("two store instances have one winner and fail the loser closed",
                    List.of("duplicate_decision", "ok"), outcomes);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String resolveAfterBarrier(ApprovalStore store, HumanDecision pending,
                                              String choice, CountDownLatch ready,
                                              CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            store.resolve(pending.runId(), pending.updatedAt().toString(), choice, choice, "");
            return "ok";
        } catch (ApprovalException rejected) {
            return rejected.code();
        }
    }

    private ApprovalStore store(Path root, String instant) {
        return new ApprovalStore(root, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
