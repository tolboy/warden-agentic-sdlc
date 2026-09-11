package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.HomeCorpus;
import dev.warden.ledger.LedgerReader;
import dev.warden.ledger.MeasurementProjector;
import dev.warden.ledger.WriterIdentity;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.Preparation;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write-path acceptance for the home measurement corpus: durability, crash windows,
 * identity, lifecycle, a failing store, the allowlist, and measurement context.
 * Import of surviving history and the global reader are not built here.
 */
public final class HomeCorpusTest implements Suite {
    @Override public String name() { return "home-corpus"; }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String SECRET = "SECRET_MARKER_7f3c9e2a1b";

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-corpus-");
        try {
            durabilitySurvivesTreeDelete(check, sandbox);
            crashWindows(check, sandbox);
            concurrencyAndIdentity(check, sandbox);
            lifecycle(check, sandbox);
            failingStore(check, sandbox);
            neighbourPendingAndIndexFailure(check, sandbox);
            noRawContent(check, sandbox);
            measurementContext(check, sandbox);
            localLedgerUnchanged(check, sandbox);
        } finally {
            try {
                deleteTree(sandbox);
            } catch (IOException ignored) {
                // Windows can keep a git object mapped after the child exits.
            }
        }
    }

    private void durabilitySurvivesTreeDelete(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("durability-home");
        Path project = sandbox.resolve("durability-project");
        Files.createDirectories(project);
        EvidenceLedger ledger = new EvidenceLedger(project, "keep", home);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ok", true);
        evidence.put("code", "ok");
        evidence.put("role", "implementer");
        evidence.put("cost_usd", 1.25);
        evidence.put("tokens", Map.of("input", 10L, "output", 5L, "total", 15L));
        ledger.append("role_run", evidence);
        String projectId = ledger.projectId();
        String instance = ledger.runInstanceId();
        check.eq("confirmed write is marked ok", "ok",
                String.valueOf(ledger.corpusStatus().get("state")));

        deleteTree(project);
        check.that("the project tree is gone", !Files.exists(project.resolve(".warden")));

        List<Map<String, Object>> records = HomeCorpus.records(home);
        check.eq("the corpus still has the event after the tree is deleted", 1, records.size());
        check.eq("project identity survived", projectId, records.get(0).get("project_id"));
        check.eq("run instance survived", instance, records.get(0).get("run_instance_id"));
        check.eq("cost survived", 1.25, records.get(0).get("cost_usd"));

        Path copy = sandbox.resolve("durability-copy");
        copyTree(home.resolve("ledger"), copy.resolve("ledger"));
        List<Map<String, Object>> copied = HomeCorpus.records(copy);
        check.eq("a copied finished corpus is readable from another path", 1, copied.size());
        check.eq("and keeps the same event id", records.get(0).get("event_id"),
                copied.get(0).get("event_id"));
    }

    private void crashWindows(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("crash-home");
        Path project = sandbox.resolve("crash-project");
        Files.createDirectories(project);

        Process before = spawn(home, project, "before", Map.of("warden.ledger.crash", "start"));
        check.that("a crash before the local write is a non-zero halt", before.exitValue() != 0);
        check.eq("and left no confirmed corpus event", 0, HomeCorpus.records(home).size());
        Path evidence = project.resolve(".warden/runs/before/evidence.jsonl");
        check.that("and left no local evidence", !Files.isRegularFile(evidence) || Files.size(evidence) == 0);

        String midId = UUID.randomUUID().toString();
        Process mid = spawn(home, project, "mid",
                Map.of("warden.ledger.crash", "after_journal"),
                "--event-id", midId);
        check.that("a crash between local and shared write is a halt", mid.exitValue() != 0);
        Path midEvidence = project.resolve(".warden/runs/mid/evidence.jsonl");
        check.that("local evidence exists after the local write", Files.isRegularFile(midEvidence)
                && Files.size(midEvidence) > 0);
        check.eq("the shared corpus does not yet have the undelivered event", 0,
                countByEventId(HomeCorpus.records(home), midId));
        check.eq("the journal still names it undelivered", "pending",
                String.valueOf(HomeCorpus.status(project.resolve(".warden/runs/mid")).get("state")));

        Process recoveredMid = spawn(home, project, "mid", Map.of(), "--recover");
        check.eq("recovery after a mid-write crash exits 0", 0, recoveredMid.exitValue());
        check.eq("and delivers the event once", 1, countByEventId(HomeCorpus.records(home), midId));

        // The window the protocol is most exposed in, and the one this suite used to step
        // over: the local append landed and the journal row was never written. Nothing but
        // journalMissingLocalEvents stands between that measurement and a silent loss, so
        // the branch that synthesises the missing row has to be executed, not assumed.
        String localId = UUID.randomUUID().toString();
        Process local = spawn(home, project, "local",
                Map.of("warden.ledger.crash", "after_local"),
                "--event-id", localId);
        check.that("a crash after the local write is a halt", local.exitValue() != 0);
        Path localEvidence = project.resolve(".warden/runs/local/evidence.jsonl");
        check.that("the local event survived the halt", Files.isRegularFile(localEvidence)
                && Files.size(localEvidence) > 0);
        Path localOutbox = project.resolve(".warden/runs/local/outbox.jsonl");
        check.that("the journal never received it", !Files.isRegularFile(localOutbox)
                || Files.size(localOutbox) == 0);
        check.eq("and the corpus has nothing for it", 0,
                countByEventId(HomeCorpus.records(home), localId));

        Process recoveredLocal = spawn(home, project, "local", Map.of(), "--recover");
        check.eq("recovery after an unjournaled local write exits 0", 0,
                recoveredLocal.exitValue());
        check.eq("recovery journals the orphan and delivers it exactly once", 1,
                countByEventId(HomeCorpus.records(home), localId));
        check.eq("and the run is then clean", "ok",
                String.valueOf(HomeCorpus.status(project.resolve(".warden/runs/local")).get("state")));

        Process recoveredLocalAgain = spawn(home, project, "local", Map.of(), "--recover");
        check.eq("a second recovery exits 0", 0, recoveredLocalAgain.exitValue());
        check.eq("and does not deliver it twice", 1,
                countByEventId(HomeCorpus.records(home), localId));

        // The other end of the same protocol: everything succeeded, and the process died
        // between the acknowledgement and returning. Recovery must find nothing to do.
        String settledId = UUID.randomUUID().toString();
        Process settled = spawn(home, project, "settled",
                Map.of("warden.ledger.crash", "after_ack"),
                "--event-id", settledId);
        check.that("a crash after the acknowledgement is a halt", settled.exitValue() != 0);
        check.eq("the acknowledged event is in the corpus once", 1,
                countByEventId(HomeCorpus.records(home), settledId));
        check.eq("and the run already reports itself delivered", "ok",
                String.valueOf(HomeCorpus.status(project.resolve(".warden/runs/settled")).get("state")));

        Process recoveredSettled = spawn(home, project, "settled", Map.of(), "--recover");
        check.eq("recovery after an acknowledged write exits 0", 0, recoveredSettled.exitValue());
        check.eq("and leaves the count alone", 1,
                countByEventId(HomeCorpus.records(home), settledId));
        check.eq("no conflict was recorded for a replay of the same content", 0,
                HomeCorpus.conflicts(home).size());

        String ackId = UUID.randomUUID().toString();
        Process ack = spawn(home, project, "ack",
                Map.of("warden.ledger.crash", "after_shared"),
                "--event-id", ackId);
        check.that("a crash after the durable write is a halt", ack.exitValue() != 0);
        check.eq("the corpus already has the confirmed event", 1,
                countByEventId(HomeCorpus.records(home), ackId));

        Process recoveredAck = spawn(home, project, "ack",
                Map.of("warden.ledger.crash", "recover_after_shared"),
                "--recover");
        check.that("a crash during recovery is a halt", recoveredAck.exitValue() != 0);
        Process recoveredAckAgain = spawn(home, project, "ack", Map.of(), "--recover");
        check.eq("recovery after a recovery crash exits 0", 0, recoveredAckAgain.exitValue());
        check.eq("the confirmed event is still counted once", 1,
                countByEventId(HomeCorpus.records(home), ackId));

        List<Map<String, Object>> beforeRead = HomeCorpus.records(home);
        HomeCorpus.records(home);
        check.eq("reading the corpus does not rewrite it", beforeRead.size(),
                HomeCorpus.records(home).size());

        Path partialHome = sandbox.resolve("partial-home");
        Path partialProject = sandbox.resolve("partial-project");
        Files.createDirectories(partialHome);
        Files.createDirectories(partialProject);
        String partialId = UUID.randomUUID().toString();
        Path segment = HomeCorpus.directory(partialHome).resolve("segments")
                .resolve(WriterIdentity.current().id() + ".jsonl");
        Files.createDirectories(segment.getParent());
        Files.writeString(segment, "{\"truncated\":true", StandardCharsets.UTF_8);
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("event_id", partialId);
        projected.put("schema_version", 1L);
        projected.put("type", "role_run");
        projected.put("ok", true);
        MeasurementProjector.Projection projection = MeasurementProjector.project(projected);
        HomeCorpus.Result replayed = HomeCorpus.deliver(partialHome, partialId,
                projection.contentHash(), projection.body());
        check.that("same-JVM recovery after a truncated segment is confirmed", replayed.confirmed());
        List<Map<String, Object>> recovered = HomeCorpus.records(partialHome);
        check.eq("the replayed event is independently readable", 1,
                countByEventId(recovered, partialId));
        List<String> segmentLines = Files.readAllLines(segment, StandardCharsets.UTF_8);
        int complete = 0;
        for (String line : segmentLines) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> row = Json.parseObject(line);
                if (partialId.equals(row.get("event_id"))) complete++;
            } catch (RuntimeException ignored) {
                // The isolated unterminated fragment stays unreadable on its own line.
            }
        }
        check.eq("the confirmed event is a complete JSONL line of its own", 1, complete);
    }

    private void concurrencyAndIdentity(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("conc-home");
        Path a = sandbox.resolve("conc-a");
        Path b = sandbox.resolve("conc-b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        gitInit(a);
        gitInit(b);

        Process one = spawnAsync(home, a, "same", Map.of(), "--count", "12", "--payload", "from-a");
        Process two = spawnAsync(home, b, "same", Map.of(), "--count", "12", "--payload", "from-b");
        boolean finished = one.waitFor(30, TimeUnit.SECONDS) && two.waitFor(30, TimeUnit.SECONDS);
        check.that("both concurrent writers finished", finished);
        check.eq("writer A exited 0", 0, one.exitValue());
        check.eq("writer B exited 0", 0, two.exitValue());

        Path segments = HomeCorpus.directory(home).resolve("segments");
        int files = 0;
        int lines = 0;
        try (var listing = Files.list(segments)) {
            for (Path segment : listing.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                files++;
                List<String> body = Files.readAllLines(segment, StandardCharsets.UTF_8);
                for (String line : body) {
                    if (line.isBlank()) continue;
                    Json.parseObject(line);
                    lines++;
                }
            }
        }
        check.that("each JVM wrote its own segment", files >= 2);
        check.eq("every concurrent line is a complete JSON object", 24, lines);

        EvidenceLedger first = new EvidenceLedger(a, "same", home);
        EvidenceLedger second = new EvidenceLedger(b, "same", home);
        check.that("the same operator run id in two projects does not share a project id",
                !first.projectId().equals(second.projectId()));
        check.that("and does not share a run instance",
                !first.runInstanceId().equals(second.runInstanceId()));

        Path copy = sandbox.resolve("conc-a-copy");
        copyTree(a, copy);
        EvidenceLedger copied = new EvidenceLedger(copy, "copied", home);
        check.eq("a copied git project keeps the same project identity",
                first.projectId(), copied.projectId());
        check.that("a new run in the copy is a different instance",
                !first.runInstanceId().equals(copied.runInstanceId()));

        String eventId = UUID.randomUUID().toString();
        Path ident = sandbox.resolve("ident");
        Files.createDirectories(ident);
        EvidenceLedger once = new EvidenceLedger(ident, "id", home);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("ok", true);
        payload.put("code", "ok");
        once.append("role_run", payload);
        once.append("role_run", payload);
        check.eq("the same id with the same payload is idempotent", 1,
                countByEventId(HomeCorpus.records(home), eventId));

        Map<String, Object> other = new LinkedHashMap<>(payload);
        other.put("code", "other");
        once.append("role_run", other);
        check.eq("the original line is kept after a conflicting rewrite", 1,
                countByEventId(HomeCorpus.records(home), eventId));
        check.that("the conflict is recorded rather than dropping a line",
                !HomeCorpus.conflicts(home).isEmpty());

        Path shared = sandbox.resolve("shared-run");
        Files.createDirectories(shared);
        Process left = spawnAsync(home, shared, "gate", Map.of(), "--count", "8", "--payload", "left");
        Process right = spawnAsync(home, shared, "gate", Map.of(), "--count", "8", "--payload", "right");
        boolean both = left.waitFor(30, TimeUnit.SECONDS) && right.waitFor(30, TimeUnit.SECONDS);
        check.that("two writers of one run finished", both);
        check.eq("shared-run writer A exited 0", 0, left.exitValue());
        check.eq("shared-run writer B exited 0", 0, right.exitValue());
        Path evidence = shared.resolve(".warden/runs/gate/evidence.jsonl");
        List<String> localLines = Files.readAllLines(evidence, StandardCharsets.UTF_8);
        java.util.Set<Long> sequences = new java.util.LinkedHashSet<>();
        int localCount = 0;
        for (String line : localLines) {
            if (line.isBlank()) continue;
            Map<String, Object> row = Json.parseObject(line);
            Object seq = row.get("seq");
            if (seq instanceof Number number) sequences.add(number.longValue());
            localCount++;
        }
        check.eq("one run's concurrent appends are complete JSON objects", 16, localCount);
        check.eq("and each sequence number is unique", localCount, sequences.size());
    }

    private void lifecycle(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("life-home");
        new UserSetup().run(home);
        Path project = newProject(sandbox, "life");
        writeProfiles(home, sandbox, "life", 1, 1);

        RoleRunner.Outcome dry = runRole(project, home, "reviewer", "plan", true);
        check.that("planning-only dry-run succeeds", dry.ok());
        check.eq("and writes a dry_run accounting unit", "dry_run",
                accountingKind(home, "role_dry_run"));

        Files.writeString(project.resolve(".warden/tasks/locked.yaml"), """
                version: 1
                id: locked
                goal: A task that forbids workspace writes
                risk: medium
                scope: app
                authority: { workspace_write: false }
                """);
        RoleRunner.Outcome denied = runRole(project, home, "implementer", "denied", true, "locked");
        check.eq("a refusal before dispatch is named", "authority_denied", denied.code());
        check.that("and appears in the corpus as a signal, not a vendor call",
                hasType(home, "authority_denied"));
        check.eq("the refusal does not count as a new call", false,
                accountingCounts(home, "authority_denied"));

        writeProfile(home, "stub-spent", "reviewer", "spentvendor", true, "reviewer",
                "role, task_id, status, verdict, summary, findings", "quota",
                sandbox.resolve("life-spent.count"), 1);
        writePolicy(home, "stub-spent, loop-review", "loop-impl");
        RoleRunner.Outcome failedOver = runRole(project, home, "reviewer", "failover", false);
        check.that("quota failover still completes", failedOver.ok());
        Map<String, Object> roleRun = firstOfType(home, "role_run");
        check.that("failover records more than one vendor attempt",
                ((Number) ((Map<?, ?>) roleRun.get("accounting")).get("vendor_attempt_count")).longValue() >= 2);
        check.that("exhaustion is its own event", hasType(home, "role_quota_exhausted"));
        check.that("the switch is its own event", hasType(home, "role_failover"));
        check.that("each completed vendor attempt was journaled before the next",
                countType(home, "vendor_attempt") >= 2);
        Map<String, Object> attemptEvent = firstOfType(home, "vendor_attempt");
        check.that("the attempt event names the vendor and the outcome",
                attemptEvent.get("vendor") != null && attemptEvent.get("code") != null);
        boolean sawAttemptCost = false;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if (!"vendor_attempt".equals(row.get("type"))) continue;
            if (row.get("cost_usd") != null || row.get("tokens") != null) sawAttemptCost = true;
        }
        check.that("a journaled attempt that reported telemetry keeps it", sawAttemptCost);
        check.eq("and counts as the vendor call", true,
                accountingCounts(home, "vendor_attempt"));

        Path repair = newProject(sandbox, "repair");
        writeProfiles(home, sandbox, "repair", 2, 1);
        TaskLoop.Outcome repaired = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(repair, "hello"), UserConfig.load(home), "repair-1", false);
        check.that("a repair with a recheck still reaches the human gate", repaired.ok());
        check.eq("exactly one fix round was used", 1L, repaired.summaryReport().get("attempts_used"));
        long roleRuns = countType(home, "role_run");
        check.that("repair wrote role_run events", roleRuns >= 2);
        boolean sawHistory = false;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if ("task_run".equals(row.get("type"))
                    && "repair-1".equals(String.valueOf(row.get("operator_run_id")))
                    && row.get("finding_history") instanceof List<?> history
                    && !history.isEmpty()) {
                sawHistory = true;
                check.that("finding_history carries no finding text",
                        !stringify(history).contains("not good enough"));
            }
        }
        check.that("task_run projected finding_history from the real workflow", sawHistory);

        Path objected = newProject(sandbox, "object");
        writeProfiles(home, sandbox, "object", 1, 99);
        RoleRunner.Outcome reviewFail = runRole(objected, home, "reviewer", "obj", false);
        check.that("a reviewer that objects still writes a role_run", reviewFail.report() != null);
        boolean sawFindingId = false;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if (!"obj".equals(String.valueOf(row.get("operator_run_id")))) continue;
            if (row.get("findings") instanceof List<?> findings) {
                for (Object item : findings) {
                    if (item instanceof Map<?, ?> finding && finding.get("id") != null) {
                        sawFindingId = true;
                        check.that("finding text is not in the corpus finding",
                                !stringify(finding).contains("not good enough"));
                    }
                }
            }
        }
        check.that("a real reviewer finding id reached the corpus", sawFindingId);

        Path resume = newProject(sandbox, "resume");
        writeProfiles(home, sandbox, "resume", 1, 1);
        TaskLoop.Outcome first = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(resume, "hello"), UserConfig.load(home), "resume-1", false);
        check.that("the first resume source run succeeds", first.ok());
        long callsBefore = vendorCalls(home);
        TaskLoop.Outcome continued = new TaskLoop(new ProcessRunner())
                .run(new ConfigLoader().load(resume, "hello"), UserConfig.load(home), "resume-2", false,
                        Map.of(), new TaskLoop.Continuation("resume-1", null, true));
        check.that("a resume with carried judgements succeeds", continued.ok());
        check.that("a carried summary does not add vendor calls",
                vendorCalls(home) == callsBefore
                        || ((Number) continued.summaryReport().get("role_runs")).longValue()
                        <= ((Number) first.summaryReport().get("role_runs")).longValue());
        Map<String, Object> summary = firstOfType(HomeCorpus.records(home).stream()
                .filter(row -> "task_run".equals(row.get("type"))
                        && "resume-2".equals(String.valueOf(row.get("operator_run_id"))))
                .toList());
        if (summary != null) {
            check.eq("a workflow summary is not a new call", false,
                    ((Map<?, ?>) summary.get("accounting")).get("counts_as_new_calls"));
            check.that("workflow outcome is a separate field from role_ok",
                    ((Map<?, ?>) summary.get("outcomes")).get("workflow_outcome") != null);
        }

        Path accept = sandbox.resolve("accept");
        Files.createDirectories(accept);
        EvidenceLedger human = new EvidenceLedger(accept, "late", home);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("kind", "success");
        human.append("human_decision_pending", pending);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision", "accept");
        decision.put("source", "cli");
        decision.put("wait_millis", 12L);
        human.append("human_decision", decision);
        Map<String, Object> humanEvent = firstOfType(home, "human_decision");
        check.eq("late human accept is its own event", "accept", humanEvent.get("decision"));
        check.eq("human-decision source is kept", "cli", humanEvent.get("source"));
        check.eq("human accept is a separate outcome from role_ok", true,
                ((Map<?, ?>) humanEvent.get("outcomes")).get("human_accept"));
        check.that("role_ok is not stuffed into a human decision",
                !((Map<?, ?>) humanEvent.get("outcomes")).containsKey("role_ok"));

        Path reserved = sandbox.resolve("reserved");
        Files.createDirectories(reserved);
        Preparation.recordSkipped(reserved, "prep-skip", "hello", "auto", home);
        check.that("a reservation is a corpus measurement", hasType(home, "run_reserved"));
        check.that("and preparation completion is a corpus measurement", hasType(home, "run_prepared"));
        deleteTree(reserved);
        check.that("reservation survives deleting the project tree",
                firstOfType(home, "run_reserved") != null);
        check.eq("reservation is not a vendor call", false, accountingCounts(home, "run_reserved"));
        check.eq("prepared is not a vendor call", false, accountingCounts(home, "run_prepared"));
        Map<String, Object> reservedEvent = firstOfType(home, "run_reserved");
        check.eq("the reservation names its phase", "reserve", reservedEvent.get("phase"));
        Map<String, Object> preparedEvent = firstOfType(home, "run_prepared");
        check.eq("preparation completion names its phase", "prepared", preparedEvent.get("phase"));
    }

    private void failingStore(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("fail-home");
        Path project = sandbox.resolve("fail-project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        Files.writeString(home.resolve("ledger"), "not-a-directory");

        EvidenceLedger ledger = new EvidenceLedger(project, "blocked", home);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ok", true);
        evidence.put("code", "ok");
        evidence.put("role", "implementer");
        ledger.append("role_run", evidence);
        check.that("local evidence survives an unwritable home",
                Files.isRegularFile(project.resolve(".warden/runs/blocked/evidence.jsonl")));
        check.that("the journal is discoverable next to the run",
                Files.isRegularFile(project.resolve(".warden/runs/blocked/outbox.jsonl")));
        check.eq("the run is pending or error, not silently ok", true,
                List.of("pending", "error").contains(
                        String.valueOf(ledger.corpusStatus().get("state"))));

        check.rejects("new paid dispatch is refused while the corpus cannot be written",
                "ledger_unavailable",
                () -> HomeCorpus.requireDispatch(home, project));

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision", "accept");
        decision.put("source", "cli");
        ledger.append("human_decision", decision);
        String local = Files.readString(project.resolve(".warden/runs/blocked/evidence.jsonl"));
        check.contains("the human decision still lands locally", local, "human_decision");

        Files.deleteIfExists(home.resolve("ledger"));
        HomeCorpus.recoverProject(home, project);
        check.that("recovery after the store returns delivers the journal",
                countType(home, "role_run") >= 1);
        HomeCorpus.requireDispatch(home, project);
        check.that("dispatch is allowed again after recovery", true);

        Path lockHome = sandbox.resolve("lock-home");
        Path lockProject = sandbox.resolve("lock-project");
        Files.createDirectories(lockProject);
        Files.createDirectories(HomeCorpus.directory(lockHome).resolve("index.lock"));
        check.rejects("dispatch is refused when the index lock cannot be opened",
                "ledger_unavailable",
                () -> HomeCorpus.requireDispatch(lockHome, lockProject));

        Path visHome = sandbox.resolve("vis-home");
        Path visProject = sandbox.resolve("vis-project");
        Files.createDirectories(visHome);
        Files.createDirectories(visProject);
        Files.writeString(visHome.resolve("ledger"), "not-a-directory");
        EvidenceLedger vis = new EvidenceLedger(visProject, "visible", visHome);
        vis.append("human_decision", Map.of("decision", "accept", "source", "cli"));
        Map<String, Object> visible = vis.corpusVisibility();
        check.that("CLI visibility names pending or error delivery",
                List.of("pending", "error").contains(String.valueOf(visible.get("corpus_status"))));
        check.eq("and says the tree is not safe to delete", false,
                visible.get("tree_safe_to_delete"));

        Path indexHome = sandbox.resolve("index-home");
        Path indexProject = sandbox.resolve("index-project");
        Files.createDirectories(indexProject);
        Path ledgerDir = HomeCorpus.directory(indexHome);
        Files.createDirectories(ledgerDir.resolve(HomeCorpus.SEGMENTS));
        Path indexAsDirectory = ledgerDir.resolve("index.json");
        Files.createDirectories(indexAsDirectory);
        Files.writeString(indexAsDirectory.resolve("blocker"), "not-a-file");
        check.rejects("dispatch is refused when the index cannot be persisted",
                "ledger_unavailable",
                () -> HomeCorpus.requireDispatch(indexHome, indexProject));
        EvidenceLedger blockedIndex = new EvidenceLedger(indexProject, "index-dir", indexHome);
        blockedIndex.append("role_run", Map.of("ok", true, "code", "ok", "role", "implementer"));
        Map<String, Object> blockedVisible = blockedIndex.corpusVisibility();
        check.that("delivery fails when the index is not a file",
                List.of("pending", "error").contains(
                        String.valueOf(blockedVisible.get("corpus_status"))));
        check.eq("and the tree is not safe to delete", false,
                blockedVisible.get("tree_safe_to_delete"));

        Path gateHome = sandbox.resolve("gate-home");
        Path gateProject = newProject(sandbox, "gate-visible");
        Files.createDirectories(gateHome);
        Files.writeString(gateHome.resolve("ledger"), "not-a-directory");
        GateRunner.Outcome gate = new GateRunner(new ProcessRunner()).withHome(gateHome)
                .run(new ConfigLoader().load(gateProject, "hello"), "gate-visible");
        check.that("standalone gates report pending or error delivery",
                List.of("pending", "error").contains(
                        String.valueOf(gate.data().get("corpus_status"))));
        check.eq("and gates say the tree is not safe to delete", false,
                gate.data().get("tree_safe_to_delete"));
        VisualQaRunner.Outcome visual = new VisualQaRunner(new ProcessRunner(),
                Path.of("definitely-missing-visual-qa.mjs")).withHome(gateHome)
                .run(new ConfigLoader().load(gateProject, "hello"), "visual-visible");
        check.that("standalone visual-qa reports pending or error delivery",
                List.of("pending", "error").contains(
                        String.valueOf(visual.data().get("corpus_status"))));
        check.eq("and visual-qa says the tree is not safe to delete", false,
                visual.data().get("tree_safe_to_delete"));

        Path draftHome = sandbox.resolve("draft-home");
        Path draftProject = newProject(sandbox, "draft-visible");
        Files.createDirectories(draftHome);
        Files.writeString(draftHome.resolve("ledger"), "not-a-directory");
        EvidenceLedger prepared = new EvidenceLedger(draftProject, "do-draft", draftHome);
        prepared.reserveWorkflowRun("hello", Map.of("prepare", "always"));
        prepared.markPrepared(1, 0, 0);
        Map<String, Object> drafted = new LinkedHashMap<>();
        drafted.put("ok", true);
        drafted.put("code", "drafted");
        drafted.put("run_id", "do-draft");
        prepared.recordCorpusVisibility(drafted);
        check.that("planning-only completion reports pending or error delivery",
                List.of("pending", "error").contains(
                        String.valueOf(drafted.get("corpus_status"))));
        check.eq("and draft-only says the tree is not safe to delete", false,
                drafted.get("tree_safe_to_delete"));
    }


    /**
     * Two windows a reader found after the first repair of this slice, both about believing
     * a cached answer: a run that reports its own delivery cannot speak for the tree, and a
     * process's view of the event index cannot survive a delivery that did not finish.
     */
    private void neighbourPendingAndIndexFailure(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("neighbour-home");
        Path project = sandbox.resolve("neighbour-project");
        Files.createDirectories(home);
        Files.createDirectories(project);

        // A neighbouring run fails to deliver while the corpus is unwritable.
        Files.writeString(home.resolve("ledger"), "not-a-directory");
        EvidenceLedger neighbour = new EvidenceLedger(project, "neighbour-b", home);
        neighbour.append("role_run", roleEvidence());
        check.eq("the neighbour is pending after an unwritable corpus", true,
                List.of("pending", "error").contains(
                        String.valueOf(neighbour.corpusStatus().get("state"))));
        Files.deleteIfExists(home.resolve("ledger"));

        // A second run in the same tree then delivers successfully.
        EvidenceLedger delivered = new EvidenceLedger(project, "neighbour-a", home);
        delivered.append("role_run", roleEvidence());
        check.eq("the delivering run records its own state as ok", "ok",
                String.valueOf(delivered.corpusStatus().get("state")));
        check.eq("but the tree is not safe while the neighbour is undelivered", Boolean.FALSE,
                delivered.corpusVisibility().get("tree_safe_to_delete"));
        check.eq("and the count it reports is the tree's, not its own", 1L,
                delivered.corpusVisibility().get("corpus_undelivered"));

        HomeCorpus.recoverProject(home, project);
        check.eq("after the neighbour is replayed the tree is safe", Boolean.TRUE,
                delivered.corpusVisibility().get("tree_safe_to_delete"));

        // A delivery that forces its segment and then cannot append its index entry.
        Path failHome = sandbox.resolve("index-fail-home");
        Path failProject = sandbox.resolve("index-fail-project");
        Files.createDirectories(failProject);
        Path ledger = failHome.resolve("ledger");
        Files.createDirectories(ledger);
        Files.createDirectory(ledger.resolve("index.jsonl"));

        check.rejects("dispatch is refused when the index log cannot be appended",
                "ledger_unavailable",
                () -> HomeCorpus.requireDispatch(failHome, failProject));

        EvidenceLedger blocked = new EvidenceLedger(failProject, "index-fail", failHome);
        blocked.append("role_run", roleEvidence());
        check.eq("a failed index append is not an acknowledged delivery", true,
                List.of("pending", "error").contains(
                        String.valueOf(blocked.corpusStatus().get("state"))));

        Files.delete(ledger.resolve("index.jsonl"));
        HomeCorpus.recoverProject(failHome, failProject);
        check.eq("recovery keeps the record that was already forced, exactly once", 1,
                HomeCorpus.records(failHome).size());
        check.eq("and records no integrity conflict for it", 0,
                HomeCorpus.conflicts(failHome).size());
        check.eq("the run is then clean", "ok",
                String.valueOf(HomeCorpus.status(
                        failProject.resolve(".warden/runs/index-fail")).get("state")));

        // A local event that recovery cannot journal is still owed by the tree. The journal
        // is what undelivered rows are counted from, so a journal that cannot be read counts
        // zero of them - and a tree deleted on that zero loses the event.
        Path orphanHome = sandbox.resolve("orphan-home");
        Path orphanProject = sandbox.resolve("orphan-project");
        Files.createDirectories(orphanProject);
        Process halted = spawn(orphanHome, orphanProject, "orphan",
                Map.of("warden.ledger.crash", "after_local"));
        check.that("the child halted before journalling", halted.exitValue() != 0);
        Path orphanRun = orphanProject.resolve(".warden/runs/orphan");
        check.that("the local event is there", Files.isRegularFile(orphanRun.resolve("evidence.jsonl"))
                && Files.size(orphanRun.resolve("evidence.jsonl")) > 0);

        Files.createDirectory(orphanRun.resolve("outbox.jsonl"));
        EvidenceLedger orphan = new EvidenceLedger(orphanProject, "orphan", orphanHome);
        check.eq("recovery that cannot journal records an error", "error",
                String.valueOf(orphan.corpusStatus().get("state")));
        check.eq("and the tree is not safe to delete", Boolean.FALSE,
                orphan.corpusVisibility().get("tree_safe_to_delete"));

        deleteTree(orphanRun.resolve("outbox.jsonl"));
        HomeCorpus.recoverProject(orphanHome, orphanProject);
        check.eq("once the journal can be written the event is delivered", 1,
                HomeCorpus.records(orphanHome).size());
        check.eq("and only then is the tree safe", Boolean.TRUE,
                orphan.corpusVisibility().get("tree_safe_to_delete"));
    }

    private Map<String, Object> roleEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ok", true);
        evidence.put("code", "ok");
        evidence.put("role", "implementer");
        evidence.put("profile", "stub");
        evidence.put("vendor", "stubvendor");
        evidence.put("cost_usd", 0.25);
        return evidence;
    }

    private void noRawContent(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("secret-home");
        Path project = sandbox.resolve("secret-project");
        Files.createDirectories(project);
        EvidenceLedger ledger = new EvidenceLedger(project, "secret", home);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("secret_field", SECRET);
        nested.put("ok", true);
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", "F1");
        finding.put("severity", "P1");
        finding.put("status", "open");
        finding.put("stage", "review");
        finding.put("message", SECRET);
        finding.put("path", "C:\\Temp\\warden-fixture\\" + SECRET + ".java");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("goal", SECRET);
        evidence.put("prompt", SECRET);
        evidence.put("message", SECRET);
        evidence.put("error", SECRET);
        evidence.put("raw_stdout", SECRET);
        evidence.put("command_preview", List.of("vendor", "--prompt", SECRET));
        evidence.put("env", Map.of("TOKEN", SECRET));
        evidence.put("nested", nested);
        evidence.put("findings", List.of(finding));
        Map<String, Object> historyRow = new LinkedHashMap<>();
        historyRow.put("stage", "review");
        historyRow.put("attempt", 1L);
        historyRow.put("findings", List.of(finding));
        historyRow.put("closed", List.of());
        historyRow.put("closed_ids", List.of());
        evidence.put("finding_history", List.of(historyRow));
        evidence.put("ok", false);
        evidence.put("code", "role_command_failed");
        evidence.put("role", "reviewer");
        evidence.put("unknown_tomorrow_field", SECRET);
        ledger.append("role_run", evidence);

        String corpus = stringify(HomeCorpus.records(home));
        check.that("the secret does not reach the home corpus", !corpus.contains(SECRET));
        String exported = stringify(HomeCorpus.exportPortable(home));
        check.that("the secret does not reach a portable export", !exported.contains(SECRET));
        Map<String, Object> projected = HomeCorpus.records(home).get(0);
        check.that("an unknown new field does not pass the allowlist",
                !projected.containsKey("unknown_tomorrow_field"));
        check.that("finding text is stripped", !stringify(projected.get("findings")).contains(SECRET));
        check.that("finding id is kept", stringify(projected.get("findings")).contains("F1"));
        check.that("finding_history is projected", projected.get("finding_history") instanceof List<?>);
        check.that("finding_history keeps F1 without the secret",
                stringify(projected.get("finding_history")).contains("F1")
                        && !stringify(projected.get("finding_history")).contains(SECRET));
        String provenance = Files.readString(
                project.resolve(".warden/runs/secret/provenance.jsonl"), StandardCharsets.UTF_8);
        check.that("absolute paths do not reach the corpus",
                !corpus.contains("C:\\Temp\\warden-fixture"));
        check.that("a local provenance index is written", provenance.contains("event_id"));
    }

    private void measurementContext(Check check, Path sandbox) throws Exception {
        Path home = sandbox.resolve("ctx-home");
        Path project = sandbox.resolve("ctx-project");
        Files.createDirectories(project);
        EvidenceLedger ledger = new EvidenceLedger(project, "ctx", home);

        Map<String, Object> first = contextEvent(20, 30, 8, true, "low", "hash-a", "schema-a");
        Map<String, Object> second = contextEvent(40, 90, 40, false, "max", "hash-b", "schema-b");
        first.put("event_id", "ctx-1");
        second.put("event_id", "ctx-2");
        ledger.append("role_run", first);
        ledger.append("role_run", second);

        Map<String, Object> a = byEventId(HomeCorpus.records(home), "ctx-1");
        Map<String, Object> b = byEventId(HomeCorpus.records(home), "ctx-2");
        check.that("a turn-limit change is visible under the same profile name",
                !String.valueOf(a.get("measurement_context")).equals(
                        String.valueOf(b.get("measurement_context"))));
        Map<?, ?> ctxA = (Map<?, ?>) a.get("measurement_context");
        Map<?, ?> ctxB = (Map<?, ?>) b.get("measurement_context");
        check.that("wall-clock change is distinguishable",
                !String.valueOf(ctxA.get("wall_clock_minutes")).equals(
                        String.valueOf(ctxB.get("wall_clock_minutes"))));
        check.that("grant change is distinguishable",
                !String.valueOf(ctxA.get("grants")).equals(String.valueOf(ctxB.get("grants"))));
        check.that("effort change is distinguishable",
                !String.valueOf(ctxA.get("effort")).equals(String.valueOf(ctxB.get("effort"))));
        check.that("prompt hash change is distinguishable",
                !String.valueOf(ctxA.get("prompt_template_sha256")).equals(
                        String.valueOf(ctxB.get("prompt_template_sha256"))));
        Map<?, ?> modelA = (Map<?, ?>) ctxA.get("model");
        Map<?, ?> requested = (Map<?, ?>) modelA.get("requested");
        Map<?, ?> effective = (Map<?, ?>) modelA.get("effective");
        check.that("requested model is labelled", requested.get("value") != null);
        check.eq("requested model retains its source", "profile", requested.get("source"));
        check.that("unknown effective is not replaced by requested",
                effective.get("value") == null);
        check.eq("unknown effective retains its source", "unknown", effective.get("source"));
        check.eq("missing values stay absent rather than becoming zero", null,
                ((Map<?, ?>) ctxA.get("adapter_version")).get("value"));
        check.eq("adapter_version unknown source is kept", "unknown",
                ((Map<?, ?>) ctxA.get("adapter_version")).get("source"));
        check.eq("grants retain their source", "task", ((Map<?, ?>) ctxA.get("grants")).get("source"));
        Map<String, Object> projected = MeasurementProjector.project(first).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedContext =
                (Map<String, Object>) projected.get("measurement_context");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedModel = (Map<String, Object>) projectedContext.get("model");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedRequested = (Map<String, Object>) projectedModel.get("requested");
        check.eq("the allowlist keeps model.requested.source", "profile",
                projectedRequested.get("source"));
    }

    private void localLedgerUnchanged(Check check, Path sandbox) throws Exception {
        Path root = sandbox.resolve("local-ledger");
        new EvidenceLedger(root, "pass").append("machine_gate", Map.of("ok", true, "code", "passed"));
        new EvidenceLedger(root, "fail").append("machine_gate", Map.of("ok", false, "code", "failed"));
        Map<String, Object> summary = new LedgerReader().summarize(root);
        check.eq("warden ledger still reports schema 1", 1L, summary.get("schema_version"));
        check.that("and still has run_count, passed, failed, runs, metrics",
                summary.containsKey("run_count") && summary.containsKey("passed")
                        && summary.containsKey("failed") && summary.containsKey("runs")
                        && summary.containsKey("metrics"));
        check.eq("two local runs are still counted", 2L, summary.get("run_count"));
        check.that("no global mode leaked into the default summary",
                !summary.containsKey("global") && !String.valueOf(summary).contains("--global"));

        Path configRoot = sandbox.resolve("config-ledger");
        EvidenceLedger cfg = new EvidenceLedger(configRoot, "cfg");
        cfg.append("vendor_attempt", Map.of("ok", false, "code", "role_local_api_key_missing",
                "role", "implementer"));
        cfg.append("role_run", Map.of("ok", false, "code", "role_local_api_key_missing",
                "role", "implementer"));
        Map<String, Object> cfgMetrics = object(new LedgerReader().summarize(configRoot).get("metrics"));
        Map<String, Object> cfgFailures = object(cfgMetrics.get("failures"));
        check.eq("a local configuration refusal is counted once, not doubled by vendor_attempt",
                1L, cfgFailures.get("configuration"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> contextEvent(long wall, long timeout, long turns, boolean write,
                                             String effort, String promptHash, String schemaHash) {
        Map<String, Object> requestedTurns = new LinkedHashMap<>();
        requestedTurns.put("value", turns);
        requestedTurns.put("source", "profile_args");
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("requested", requestedTurns);
        turn.put("effective", Map.of("source", "unknown"));
        turn.put("reported", Map.of("source", "unknown"));
        Map<String, Object> grants = new LinkedHashMap<>();
        grants.put("workspace_write", write);
        grants.put("network", false);
        grants.put("land", false);
        grants.put("source", "task");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("requested", Map.of("value", "same-profile-model", "source", "profile"));
        model.put("effective", Map.of("source", "unknown"));
        model.put("reported", Map.of("source", "unknown"));
        Map<String, Object> effortMap = new LinkedHashMap<>();
        effortMap.put("requested", Map.of("value", effort, "source", "profile"));
        effortMap.put("effective", Map.of("source", "unknown"));
        effortMap.put("reported", Map.of("source", "unknown"));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("wall_clock_minutes", Map.of("value", wall, "source", "profile"));
        context.put("timeout_minutes", Map.of("value", timeout, "source", "task"));
        context.put("turn_limit", turn);
        context.put("grants", grants);
        context.put("prompt_template_sha256", Map.of("value", promptHash, "source", "profile"));
        context.put("json_schema_sha256", Map.of("value", schemaHash, "source", "profile"));
        context.put("model", model);
        context.put("effort", effortMap);
        context.put("adapter_version", Map.of("source", "unknown"));
        context.put("warden_version", Map.of("value", Main.VERSION, "source", "warden"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ok", true);
        evidence.put("code", "ok");
        evidence.put("role", "implementer");
        evidence.put("profile", "same-profile");
        evidence.put("measurement_context", context);
        evidence.put("role_contract", Map.of("profile", "same-profile", "role", "implementer"));
        return evidence;
    }

    private Process spawn(Path home, Path project, String runId, Map<String, String> properties,
                          String... extra) throws Exception {
        Process process = spawnAsync(home, project, runId, properties, extra);
        checkFinished(process);
        return process;
    }

    private Process spawnAsync(Path home, Path project, String runId, Map<String, String> properties,
                               String... extra) throws Exception {
        Files.createDirectories(home);
        Files.createDirectories(project);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(absoluteClassPath());
        properties.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("dev.warden.ledger.CorpusCrashDriver");
        command.add("--home");
        command.add(home.toString());
        command.add("--project");
        command.add(project.toString());
        command.add("--run-id");
        command.add(runId);
        command.addAll(List.of(extra));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void checkFinished(Process process) throws Exception {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("child process did not finish");
        }
    }

    private Path newProject(Path sandbox, String name) throws Exception {
        Path project = sandbox.resolve(name);
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.createDirectories(project.resolve("src"));
        String existsCheck = WINDOWS
                ? "if exist src\\\\result.txt (exit /b 0) else (exit /b 1)"
                : "test -f src/result.txt";
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: %s
                base_ref: HEAD
                checks:
                  fast: ["%s"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                """.formatted(name, existsCheck));
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Create src/result.txt containing the word ok
                risk: medium
                scope: app
                authority: { workspace_write: true }
                budgets: { max_role_runs: 8, max_cost_usd: 10.0 }
                max_fix_attempts: 2
                """);
        Files.writeString(project.resolve("README.md"), "seed\n");
        gitInit(project);
        return project;
    }

    private void writeProfiles(Path home, Path sandbox, String scenario, int implSucceedsOn,
                               int reviewPassesOn) throws IOException {
        Path implCounter = sandbox.resolve(scenario + "-impl.count");
        Path reviewCounter = sandbox.resolve(scenario + "-review.count");
        Files.deleteIfExists(implCounter);
        Files.deleteIfExists(reviewCounter);
        writeProfile(home, "loop-impl", "implementer", "implvendor", false, "implementer",
                "role, task_id, status, summary, files_changed", "impl", implCounter, implSucceedsOn);
        writeProfile(home, "loop-review", "reviewer", "reviewvendor", true, "reviewer",
                "role, task_id, status, verdict, summary, findings", "review", reviewCounter,
                reviewPassesOn);
        writePolicy(home, "loop-review", "loop-impl");
    }

    private void writeProfile(Path home, String name, String role, String vendor, boolean readOnly,
                              String promptAndSchema, String requiredFields, String mode,
                              Path counter, int threshold) throws IOException {
        Files.createDirectories(home.resolve("profiles"));
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: %s
                read_only: %s
                args:
                  - "-cp"
                  - %s
                  - "dev.warden.testing.StubVendor"
                  - "%s"
                  - "--counter"
                  - %s
                  - "--threshold"
                  - "%d"
                  - "--prompt-file"
                  - "{{prompt_file}}"
                limits: { wall_clock_minutes: 2 }
                prompt_template: prompts/%s.md
                json_schema: schemas/%s.json
                artifact:
                  required_fields: [%s]
                verification:
                  verified_on: "2026-08-26"
                """.formatted(name, role, vendor, yaml(javaExecutable()), readOnly,
                yaml(absoluteClassPath()), mode, yaml(counter.toString()), threshold,
                promptAndSchema, promptAndSchema, requiredFields));
    }

    private void writePolicy(Path home, String reviewers, String implementers) throws IOException {
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [%s], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [%s], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                failover: { on_quota_exhausted: auto }
                """.formatted(implementers, reviewers));
    }

    private RoleRunner.Outcome runRole(Path project, Path home, String role, String runId,
                                       boolean dryRun) throws Exception {
        return runRole(project, home, role, runId, dryRun, "hello");
    }

    private RoleRunner.Outcome runRole(Path project, Path home, String role, String runId,
                                       boolean dryRun, String task) throws Exception {
        return new RoleRunner(new ProcessRunner())
                .run(new ConfigLoader().load(project, task), UserConfig.load(home),
                        role, runId, null, null, dryRun);
    }

    private static void gitInit(Path project) throws Exception {
        ProcessRunner runner = new ProcessRunner();
        runner.run(List.of("git", "init", "-q", "-b", "main", "."), project, Duration.ofSeconds(30));
        runner.run(List.of("git", "config", "user.email", "test@example.invalid"), project,
                Duration.ofSeconds(30));
        runner.run(List.of("git", "config", "user.name", "test"), project, Duration.ofSeconds(30));
        if (Files.isDirectory(project.resolve(".warden")) || Files.isRegularFile(project.resolve("README.md"))) {
            runner.run(List.of("git", "add", "-A"), project, Duration.ofSeconds(30));
            runner.run(List.of("git", "commit", "-qm", "base"), project, Duration.ofSeconds(30));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    private static String absoluteClassPath() {
        String separator = java.io.File.pathSeparator;
        StringBuilder builder = new StringBuilder();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int countByEventId(List<Map<String, Object>> records, String eventId) {
        int count = 0;
        for (Map<String, Object> row : records) {
            if (eventId.equals(row.get("event_id"))) count++;
        }
        return count;
    }

    private static Map<String, Object> byEventId(List<Map<String, Object>> records, String eventId) {
        for (Map<String, Object> row : records) {
            if (eventId.equals(row.get("event_id"))) return row;
        }
        return Map.of();
    }

    private static boolean hasType(Path home, String type) throws IOException {
        return countType(home, type) > 0;
    }

    private static long countType(Path home, String type) throws IOException {
        long count = 0;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if (type.equals(row.get("type"))) count++;
        }
        return count;
    }

    private static Map<String, Object> firstOfType(Path home, String type) throws IOException {
        return firstOfType(HomeCorpus.records(home).stream()
                .filter(row -> type.equals(row.get("type"))).toList());
    }

    private static Map<String, Object> firstOfType(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String accountingKind(Path home, String type) throws IOException {
        Map<String, Object> row = firstOfType(home, type);
        if (row == null || !(row.get("accounting") instanceof Map<?, ?> accounting)) return null;
        return String.valueOf(accounting.get("kind"));
    }

    private static boolean accountingCounts(Path home, String type) throws IOException {
        Map<String, Object> row = firstOfType(home, type);
        if (row == null || !(row.get("accounting") instanceof Map<?, ?> accounting)) return false;
        return Boolean.TRUE.equals(accounting.get("counts_as_new_calls"));
    }

    private static long vendorCalls(Path home) throws IOException {
        long total = 0;
        for (Map<String, Object> row : HomeCorpus.records(home)) {
            if (!(row.get("accounting") instanceof Map<?, ?> accounting)) continue;
            if (Boolean.TRUE.equals(accounting.get("counts_as_new_calls"))) {
                Object count = accounting.get("vendor_attempt_count");
                total += count instanceof Number number ? number.longValue() : 1;
            }
        }
        return total;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            last = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        path.toFile().setWritable(true);
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException retry) {
                            last = retry;
                        }
                    }
                }
            } catch (IOException failure) {
                last = failure;
            }
            if (last == null || !Files.exists(root)) return;
            try {
                Thread.sleep(50L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null) throw last;
    }
}
