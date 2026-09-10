package dev.warden.execution.orca;

import dev.warden.config.Profile;
import dev.warden.execution.DirectCliExecutor;
import dev.warden.execution.QuotaSignal;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.json.Schema;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a role through Orca's supervised worker lifecycle.
 *
 * Warden never creates worktrees or branches. This adapter attaches to the Orca worktree that
 * already contains the project, starts one supervised worker, and accepts completion only from
 * a proven {@code worker_done} / dispatch settlement. Terminal text and heartbeats are not
 * completion evidence.
 *
 * If the current directory is not an Orca worktree, or the runtime cannot bind a coordinator
 * Run (Warden invoked outside an Orca terminal), the role fails closed with a named code
 * instead of falling back to Direct CLI — routing around a declared runner would hide it.
 *
 * <h2>Surviving its own restart</h2>
 *
 * A supervised worker outlives the Warden process that started it. That is the point — an
 * agent parked on a question a person has not answered yet should still be there tomorrow —
 * but it means the identities cannot be held in memory. Every identity this adapter creates
 * is written to {@link OrcaLifecycle} before it is used, so a second invocation attaches to
 * the worker that is already running rather than starting a rival one beside it.
 *
 * Two independent guards, because they fail differently. The record answers "is my worker
 * still there"; a {@code worker-list} scan answers "does anybody's worker hold this worktree",
 * which is the question that still has an answer when the record was never written because
 * the process died mid-start. Neither guard is allowed to read "cannot tell" as "no".
 */
public final class OrcaExecutor implements RoleExecutor {

    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CHECK_WINDOW = Duration.ofSeconds(60);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final GitRepository git;
    private final OrcaClient orca;

    public OrcaExecutor(ProcessRunner processes, GitRepository git) {
        this.git = git;
        this.orca = new OrcaClient(processes);
    }

    public OrcaExecutor(OrcaClient orca, GitRepository git) {
        this.git = git;
        this.orca = orca;
    }

    @Override
    public Result execute(Request request) throws Exception {
        Profile profile = request.profile();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runner", "orca");
        evidence.put("role", request.role());
        evidence.put("profile", profile.name());
        evidence.put("vendor", profile.vendor());
        evidence.put("model", profile.model());
        evidence.put("effort_requested", profile.effort());
        evidence.put("launch_contract", OrcaLaunch.contract(profile));
        evidence.put("read_only", profile.readOnly());
        evidence.put("agent", profile.command());

        long deadline = System.nanoTime() + Duration.ofMinutes(profile.wallClockMinutes()).toNanos();
        String mergeBase = request.diffBaseCommit() != null
                ? request.diffBaseCommit() : git.mergeBase(request.task().baseRef());
        String fingerprintBefore = profile.readOnly() ? git.fingerprint(mergeBase) : null;

        OrcaLifecycle lifecycle = new OrcaLifecycle(request.projectRoot(), request.workflowRunId());
        String workerKey = OrcaLifecycle.workerKey(request.role(), request.runId());
        evidence.put("lifecycle_record", "runs/" + request.workflowRunId() + "/" + OrcaLifecycle.FILE_NAME);
        OrcaLifecycle.Snapshot recorded;
        try {
            recorded = lifecycle.read();
        } catch (IOException unreadable) {
            // The file that says whether a worker is already running is the one file whose
            // absence of an answer cannot be rounded down to "none".
            evidence.put("failure", "role_orca_lifecycle_unaccounted");
            evidence.put("lifecycle_error", String.valueOf(unreadable.getMessage()));
            evidence.put("resolution", "Warden cannot read its own Orca lifecycle record, so it "
                    + "cannot prove no worker is running for this run; inspect "
                    + lifecycle.file() + " and `orca orchestration worker-list --json`");
            return fail("role_orca_lifecycle_unaccounted", evidence, Duration.ZERO, "");
        }
        OrcaLifecycle.Worker prior = recorded.worker(workerKey).orElse(null);

        Map<String, Object> status = orca.status(request.projectRoot());
        evidence.put("orca_status", status);
        if (!Boolean.TRUE.equals(status.get("available"))) {
            evidence.put("failure", "role_orca_unavailable");
            return fail("role_orca_unavailable", evidence, Duration.ZERO, "");
        }
        if (!Boolean.TRUE.equals(status.get("orchestration_contract"))) {
            evidence.put("failure", "role_orca_unavailable");
            evidence.put("resolution", "Orca is running but orchestration.contract.v1 is not advertised");
            return fail("role_orca_unavailable", evidence, Duration.ZERO, "");
        }
        if ((profile.model() != null || profile.effort() != null)
                && !Boolean.TRUE.equals(status.get("worker_launch_preferences"))) {
            evidence.put("failure", "role_orca_launch_unsupported");
            evidence.put("resolution", "Orca does not advertise orchestration.worker-launch-preferences.v1; "
                    + "update the runtime. Warden will not drop model or effort overrides");
            return fail("role_orca_launch_unsupported", evidence, Duration.ZERO, "");
        }

        OrcaClient.Rpc current = orca.invoke(request.projectRoot(), Duration.ofSeconds(15),
                List.of("worktree", "current"));
        evidence.put("worktree_current_ok", current.ok());
        String selector = OrcaSettlement.worktreeSelector(current.envelope());
        if (!current.ok() || selector == null) {
            evidence.put("failure", "role_orca_no_worktree");
            evidence.put("stderr_tail", tail(current.stderr(), 2000));
            evidence.put("stdout_tail", tail(current.stdout(), 2000));
            evidence.put("resolution", "Warden does not create worktrees; run this profile from inside "
                    + "an Orca-managed checkout, or use runner: direct");
            return fail("role_orca_no_worktree", evidence, Duration.ZERO, current.stdout());
        }
        evidence.put("worktree_selector", selector);
        String worktreeId = nestedString(current.result(), "worktree", "id");
        if (worktreeId != null) evidence.put("worktree_id", worktreeId);

        // Ask about the worker this run already owns before creating anything. Whether it is
        // alive decides every branch below, and asking costs one read-only probe.
        if (prior != null && prior.active()) {
            OrcaClient.Rpc probe = orca.invoke(request.projectRoot(), PROBE_TIMEOUT,
                    List.of("orchestration", "worker-show", "--dispatch", prior.dispatchId()));
            OrcaRecovery.Assessment recovery = OrcaRecovery.assess(probe.envelope(), prior.dispatchId());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dispatch_id", prior.dispatchId());
            report.put("task_id", prior.taskId());
            report.put("verdict", recovery.verdict().name().toLowerCase());
            report.put("reason", recovery.reason());
            report.put("awaiting", prior.awaiting());
            report.put("awaiting_message", prior.awaitingMessage());
            report.put("recorded_at", prior.updatedAt().toString());
            evidence.put("recovered_worker", report);
            switch (recovery.verdict()) {
                case UNCERTAIN -> {
                    evidence.put("failure", "role_orca_lifecycle_unaccounted");
                    evidence.put("resolution", "run " + request.workflowRunId() + " recorded dispatch "
                            + prior.dispatchId() + " as active and Orca cannot say whether it still "
                            + "is (" + recovery.reason() + "). Inspect that dispatch with "
                            + "orchestration worker-show, then fence it with worker-stop or "
                            + "worker-abandon before running this role again");
                    return fail("role_orca_lifecycle_unaccounted", evidence,
                            elapsed(deadline, profile), "");
                }
                case GONE -> {
                    markWorker(lifecycle, workerKey, OrcaLifecycle.State.STOPPED,
                            recovery.reason(), evidence);
                    prior = null;
                }
                default -> { }
            }
        }
        boolean resuming = prior != null && prior.active();
        evidence.put("resumed", resuming);
        if (resuming) {
            if (!OrcaLaunch.contract(profile).equals(prior.launchContract())) {
                evidence.put("failure", "role_orca_launch_contract_changed");
                evidence.put("recorded_launch_contract", prior.launchContract());
                evidence.put("resolution", "The existing worker's launch contract differs or predates contract recording. "
                        + "Restore its original profile or inspect and fence that dispatch before a fresh run");
                return fail("role_orca_launch_contract_changed", evidence, Duration.ZERO, "");
            }
            OrcaClient.Rpc launchProbe = orca.invoke(request.projectRoot(), PROBE_TIMEOUT,
                    List.of("orchestration", "worker-show", "--dispatch", prior.dispatchId()));
            OrcaLaunch.Verification verified = OrcaLaunch.verify(profile, launchProbe.envelope());
            evidence.put("launch", verified.evidence());
            if (!launchProbe.ok() || !prior.dispatchId().equals(OrcaSettlement.dispatchId(launchProbe.envelope()))
                    || !prior.taskId().equals(OrcaSettlement.taskId(launchProbe.envelope())) || !verified.ok()) {
                evidence.put("failure", "role_orca_launch_unverified");
                evidence.put("resolution", "Cannot verify the existing dispatch launch options; retained without starting another worker");
                return fail("role_orca_launch_unverified", evidence, Duration.ZERO, "");
            }
        }
        if (resuming && profile.readOnly() && prior.readOnlyFingerprint() != null) {
            // Measure the read-only promise from where the worker started, not from where this
            // process happened to attach. A file written before the restart is still a violation.
            fingerprintBefore = prior.readOnlyFingerprint();
            evidence.put("read_only_window", "worker_start");
        }

        // The record above answers "is my worker alive". This answers "is anyone's", which is
        // the only question left when the previous process died before it could write one down.
        OrcaClient.Rpc census = orca.invoke(request.projectRoot(), PROBE_TIMEOUT,
                List.of("orchestration", "worker-list"));
        if (!census.ok()) {
            evidence.put("failure", "role_orca_lifecycle_unaccounted");
            evidence.put("stderr_tail", tail(census.stderr(), 2000));
            evidence.put("resolution", "Orca could not list supervised workers, so Warden cannot "
                    + "prove this worktree is free; it will not start an agent that may be the "
                    + "second one in the same checkout");
            return fail("role_orca_lifecycle_unaccounted", evidence, elapsed(deadline, profile), "");
        }
        List<OrcaRecovery.LiveWorker> contending = OrcaRecovery.contending(
                OrcaRecovery.liveWorkers(census.envelope()), worktreeId,
                resuming ? prior.dispatchId() : null);
        if (!contending.isEmpty()) {
            OrcaRecovery.LiveWorker holder = contending.get(0);
            evidence.put("contending_workers", contending.stream()
                    .map(OrcaRecovery.LiveWorker::toMap).toList());
            evidence.put("failure", "role_orca_worker_active");
            evidence.put("resolution", "supervised worker " + holder.dispatchId() + " is still in "
                    + "flight in this worktree, so Warden will not start a second agent beside it. "
                    + "Let it finish, answer it in Agent Dashboard, or fence it with "
                    + "orchestration worker-stop on that dispatch");
            return fail("role_orca_worker_active", evidence, elapsed(deadline, profile), "");
        }

        // Warden may be launched from an ordinary shell. Orca lifecycle mutations need a
        // coordinator identity, so create one exact Orca terminal and explicitly attribute
        // every command to it. This is what makes `warden do` the entry point instead of
        // requiring the operator to launch Warden again from inside Orca.
        //
        // A resumed worker keeps its original coordinator while that terminal is still alive:
        // an unacknowledged FIFO delivery is addressed to that recipient, and a fresh
        // coordinator would leave the receipt sitting unread in the old one.
        String coordinatorHandle = resuming
                ? liveCoordinator(request.projectRoot(), prior.coordinatorHandle(), evidence) : null;
        evidence.put("coordinator_reused", coordinatorHandle != null);
        if (coordinatorHandle == null) {
            OrcaClient.Rpc coordinator = orca.invoke(request.projectRoot(), Duration.ofSeconds(30),
                    List.of("terminal", "create",
                            "--worktree", selectorArgument(selector),
                            "--title", title("warden-coordinator-" + request.role() + "-" + request.task().id())));
            coordinatorHandle = nestedString(coordinator.result(), "terminal", "handle");
            evidence.put("coordinator_create_ok", coordinator.ok());
            if (!coordinator.ok() || coordinatorHandle == null) {
                evidence.put("failure", "role_orca_no_coordinator");
                evidence.put("stderr_tail", tail(coordinator.stderr(), 2000));
                evidence.put("stdout_tail", tail(coordinator.stdout(), 2000));
                evidence.put("resolution", "Orca could not create a coordinator terminal for Warden");
                return fail("role_orca_no_coordinator", evidence, elapsed(deadline, profile),
                        coordinator.stdout());
            }
        }
        evidence.put("orca_coordinator_handle", coordinatorHandle);

        boolean retainCoordinator = false;
        try {
        String runId = recorded.orcaRunId();
        OrcaClient.Rpc run;
        if (runId == null) {
            run = orca.invoke(request.projectRoot(), Duration.ofSeconds(20),
                    List.of("orchestration", "run-create",
                            "--objective", "warden " + request.workflowRunId() + ": "
                                    + request.task().goal(),
                            "--from", coordinatorHandle));
            evidence.put("run_create_ok", run.ok());
            runId = nestedString(run.result(), "run", "id");
            if (runId == null) runId = string(OrcaSettlement.first(run.result(), "runId", "id", "run_id"));
            evidence.put("orca_run_reused", false);
        } else {
            run = orca.invoke(request.projectRoot(), Duration.ofSeconds(20),
                    List.of("orchestration", "run-use", "--id", runId,
                            "--from", coordinatorHandle));
            evidence.put("run_use_ok", run.ok());
            evidence.put("orca_run_reused", true);
        }
        if (!run.ok() || runId == null) {
            evidence.put("failure", "role_orca_no_coordinator");
            evidence.put("stderr_tail", tail(run.stderr(), 2000));
            evidence.put("stdout_tail", tail(run.stdout(), 2000));
            evidence.put("resolution", "Orca could not bind a Run to Warden's coordinator terminal");
            return fail("role_orca_no_coordinator", evidence, elapsed(deadline, profile), run.stdout());
        }
        evidence.put("orca_run_id", runId);
        bindRun(lifecycle, runId, evidence);

        String taskId;
        String dispatchId;
        OrcaClient.Rpc started = null;
        if (resuming) {
            taskId = prior.taskId();
            dispatchId = prior.dispatchId();
            evidence.put("orca_task_id", taskId);
            evidence.put("orca_dispatch_id", dispatchId);
            evidence.put("prompt_delivery", "workspace_file");
            if (!coordinatorHandle.equals(prior.coordinatorHandle())) {
                rebindCoordinator(lifecycle, workerKey, coordinatorHandle, evidence);
            }
        } else {
            // The Orca CLI accepts --spec text, but a multiline Markdown argument is not a safe
            // Windows transport (the live run was parsed as a new command at the second line).
            // The full immutable prompt already exists in the worktree, so inject one short line
            // that names it and requires the typed artifact in worker_done payload.
            String taskSpec = oneLineSpec(request);
            evidence.put("task_spec", taskSpec);
            evidence.put("prompt_delivery", "workspace_file");
            evidence.put("attachments", request.attachments() == null ? List.of()
                    : request.attachments().stream().map(path -> path.toAbsolutePath().toString()).toList());
            if (profile.vision() != null) {
                evidence.put("vision_delivery", profile.vision().delivery());
                evidence.put("vision_verified", profile.hasVerifiedVision());
            }
            List<String> taskArgs = new ArrayList<>(List.of("orchestration", "task-create",
                    "--spec", taskSpec,
                    "--task-title", workerRowTitle(request, profile),
                    "--display-name", title(request.role() + " / " + profile.name() + " / "
                            + profile.model() + " / " + (profile.effort() == null ? "effort inherited" : profile.effort())),
                    "--run", runId,
                    "--from", coordinatorHandle));
            OrcaClient.Rpc task = orca.invoke(request.projectRoot(), Duration.ofSeconds(20), taskArgs);
            evidence.put("task_create_ok", task.ok());
            taskId = OrcaSettlement.taskId(task.envelope());
            if (taskId == null) taskId = string(OrcaSettlement.first(task.result(), "id", "taskId"));
            if (!task.ok() || taskId == null) {
                evidence.put("failure", "role_command_failed");
                evidence.put("stderr_tail", tail(task.stderr(), 2000));
                evidence.put("stdout_tail", tail(task.stdout(), 2000));
                return fail("role_command_failed", evidence, elapsed(deadline, profile), task.stdout());
            }
            evidence.put("orca_task_id", taskId);
            dev.warden.dashboard.RoleView.update(request.runDirectory(), request.evidenceName(), evidence);

            List<String> startArgs = new ArrayList<>(List.of("orchestration", "worker-start",
                    "--task", taskId,
                    "--worktree", selectorArgument(selector)));
            startArgs.addAll(OrcaLaunch.arguments(profile));
            startArgs.add("--run");
            startArgs.add(runId);
            startArgs.add("--from");
            startArgs.add(coordinatorHandle);
            startArgs.add("--timeout-ms");
            startArgs.add(String.valueOf(START_TIMEOUT.toMillis()));

            Duration startBudget = remaining(deadline);
            if (startBudget.isZero() || startBudget.isNegative()) {
                evidence.put("failure", "role_orca_timeout");
                evidence.put("resolution", "the role wall-clock expired before worker-start; "
                        + "no dispatch exists to fence");
                return fail("role_orca_timeout", evidence,
                        Duration.ofMinutes(profile.wallClockMinutes()), "");
            }
            started = orca.invoke(request.projectRoot(),
                    startBudget.compareTo(START_TIMEOUT) < 0 ? startBudget : START_TIMEOUT, startArgs);
            evidence.put("worker_start_ok", started.ok());
            evidence.put("worker_start_ready", OrcaSettlement.startReady(started.envelope()));
            dispatchId = OrcaSettlement.dispatchId(started.envelope());
            if (dispatchId != null) {
                evidence.put("orca_dispatch_id", dispatchId);
                // Written before readiness is even known. A dispatch Warden cannot prove it
                // fenced must still be findable by whichever process runs next.
                Instant now = Instant.now();
                recordWorker(lifecycle, new OrcaLifecycle.Worker(workerKey, request.role(), taskId,
                        dispatchId, coordinatorHandle, selector, worktreeId, profile.command(),
                        profile.model(), fingerprintBefore, OrcaLifecycle.State.ACTIVE, null, null,
                        now, now, OrcaLaunch.contract(profile), Map.of()), evidence);
                dev.warden.dashboard.RoleView.update(request.runDirectory(), request.evidenceName(), evidence);
            }
            if (!started.ok() || !OrcaSettlement.startReady(started.envelope())) {
                boolean fenced = dispatchId != null
                        && stopWorker(request.projectRoot(), dispatchId, evidence);
                if (fenced) {
                    markWorker(lifecycle, workerKey, OrcaLifecycle.State.STOPPED, "start_failed", evidence);
                } else {
                    retainCoordinator = true;
                    evidence.put("coordinator_retained", true);
                    evidence.put("failure", "role_orca_lifecycle_unaccounted");
                    evidence.put("resolution", dispatchId == null
                            ? "worker-start did not return a dispatch identity, so Warden cannot prove "
                                    + "that no worker remains active; inspect the retained coordinator"
                            : "worker-start did not become ready and Orca did not confirm fencing the "
                                    + "dispatch; inspect orchestration worker-show for dispatch "
                                    + dispatchId + " before recovery");
                    return fail("role_orca_lifecycle_unaccounted", evidence,
                            elapsed(deadline, profile), started.stdout());
                }
                Result quota = quotaFailure(profile, started.stdout(), started.stderr(),
                        elapsed(deadline, profile), evidence);
                if (quota != null) return quota;
                evidence.put("failure", "role_orca_start_failed");
                evidence.put("stderr_tail", tail(started.stderr(), 2000));
                evidence.put("stdout_tail", tail(started.stdout(), 2000));
                return fail("role_orca_start_failed", evidence, elapsed(deadline, profile),
                        started.stdout());
            }
            // Inspect the durable options on the exact dispatch, even if worker-start also
            // included a receipt. This is the same contract a resumed controller will read.
            OrcaClient.Rpc launchReceipt = dispatchId == null ? started : orca.invoke(
                    request.projectRoot(), PROBE_TIMEOUT,
                    List.of("orchestration", "worker-show", "--dispatch", dispatchId));
            OrcaLaunch.Verification verified = OrcaLaunch.verify(profile, launchReceipt.envelope());
            evidence.put("launch", verified.evidence());
            boolean identityMatches = taskId.equals(OrcaSettlement.taskId(launchReceipt.envelope()))
                    && dispatchId != null && dispatchId.equals(OrcaSettlement.dispatchId(launchReceipt.envelope()));
            if (!launchReceipt.ok() || !identityMatches || !verified.ok()) {
                boolean fenced = dispatchId != null && stopWorker(request.projectRoot(), dispatchId, evidence);
                if (fenced) markWorker(lifecycle, workerKey, OrcaLifecycle.State.STOPPED, "launch_unverified", evidence);
                else retainCoordinator = true;
                String code = fenced ? "role_orca_launch_unverified" : "role_orca_lifecycle_unaccounted";
                evidence.put("failure", code);
                evidence.put("resolution", "Orca did not confirm the exact requested launch options. "
                        + "Inspect launch evidence and the dispatch; no result from it is accepted");
                return fail(code, evidence, elapsed(deadline, profile), launchReceipt.stdout());
            }
            try {
                lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.worker(workerKey)
                        .map(worker -> snapshot.withWorker(worker.withLaunch(
                                OrcaLaunch.contract(profile), verified.evidence())))
                        .orElseThrow(() -> new IllegalStateException("started worker is missing from the lifecycle record"))));
            } catch (IOException cannotPersist) {
                retainCoordinator = true;
                evidence.put("failure", "role_orca_lifecycle_unaccounted");
                evidence.put("resolution", "Launch verified but its durable record could not be written: " + cannotPersist.getMessage());
                return fail("role_orca_lifecycle_unaccounted", evidence, elapsed(deadline, profile), started.stdout());
            }
            dev.warden.dashboard.RoleView.update(request.runDirectory(), request.evidenceName(), evidence);
            nameWorkerRow(request.projectRoot(), dispatchId, request, profile, evidence);
        }

        OrcaSettlement.Outcome settlement = waitForSettlement(request.projectRoot(), taskId, dispatchId,
                runId, coordinatorHandle, deadline, evidence);
        evidence.put("settlement", settlement.kind().name().toLowerCase());
        evidence.put("settlement_reason", settlement.reason());
        if (settlement.deliveryId() != null) evidence.put("orca_delivery_id", settlement.deliveryId());
        if (settlement.messageId() != null) evidence.put("orca_message_id", settlement.messageId());

        if (settlement.kind() == OrcaSettlement.Kind.QUESTION) {
            retainCoordinator = true;
            evidence.put("failure", "role_human_input_required");
            evidence.put("coordinator_retained", true);
            if (settlement.reason() != null && settlement.reason().startsWith("agent_wait_")) {
                evidence.put("agent_wait", settlement.payload());
                evidence.put("resolution", "agent_wait_proven".equals(settlement.reason())
                        ? "The role wall-clock expired after Orca's latest exact observation "
                                + "showed a human-input wait. Open the worker under NEEDS YOU in "
                                + "Agent Dashboard; it is intentionally retained"
                        : "Orca last proved a human-input wait but the latest worker observation "
                                + "was unavailable. Inspect the retained worker in Agent Dashboard "
                                + "before recovery");
            } else {
                evidence.put("resolution", "Orca question " + settlement.messageId()
                        + " went unanswered for this role's whole budget. Reply to it with "
                        + "`orca orchestration reply --id " + settlement.messageId() + "`, then "
                        + "run this role again: the dispatch is recorded, so the next invocation "
                        + "attaches to this same worker rather than starting another");
            }
            // Deliberately still ACTIVE. The agent is alive and parked on a person, which is
            // exactly the state a later invocation must attach to rather than duplicate.
            // Recorded, so the question outlives this process and a later attach can name it.
            markWorker(lifecycle, workerKey, OrcaLifecycle.State.ACTIVE, "human",
                    settlement.messageId(), evidence);
            return fail("role_human_input_required", evidence, elapsed(deadline, profile), "");
        }

        boolean terminalSettlement = settlement.kind() == OrcaSettlement.Kind.COMPLETED
                || settlement.kind() == OrcaSettlement.Kind.FAILED;
        boolean lifecycleAccounted = true;
        if (terminalSettlement && dispatchId != null) {
            OrcaClient.Rpc released = orca.invoke(request.projectRoot(), Duration.ofSeconds(30),
                    List.of("orchestration", "worker-release", "--dispatch", dispatchId));
            evidence.put("worker_release_ok", released.ok());
            evidence.put("worker_release", released.result());
            if (!released.ok()) {
                evidence.put("worker_release_error_code", OrcaSettlement.errorCode(released.envelope()));
                evidence.put("worker_release_stderr", tail(released.stderr(), 1000));
            }
            if (released.ok()) {
                markWorker(lifecycle, workerKey, OrcaLifecycle.State.SETTLED,
                        settlement.kind().name().toLowerCase(), evidence);
            } else {
                lifecycleAccounted = false;
                retainCoordinator = true;
                evidence.put("coordinator_retained", true);
            }
        } else if (settlement.kind() == OrcaSettlement.Kind.ESCALATED
                || settlement.kind() == OrcaSettlement.Kind.CHECKPOINT
                || settlement.kind() == OrcaSettlement.Kind.UNKNOWN) {
            lifecycleAccounted = dispatchId != null
                    && stopWorker(request.projectRoot(), dispatchId, evidence);
            if (lifecycleAccounted) {
                markWorker(lifecycle, workerKey, OrcaLifecycle.State.STOPPED,
                        settlement.kind().name().toLowerCase(), evidence);
            } else {
                retainCoordinator = true;
                evidence.put("coordinator_retained", true);
            }
        }
        boolean deliveryAccounted = lifecycleAccounted
                && acknowledge(request.projectRoot(), runId, coordinatorHandle,
                        settlement.deliveryId(), evidence);
        if (!deliveryAccounted) {
            retainCoordinator = true;
            evidence.put("coordinator_retained", true);
            if (lifecycleAccounted) {
                evidence.put("resolution", "Orca will replay this unacknowledged FIFO delivery; "
                        + "inspect the retained coordinator before another role is dispatched");
            }
        } else {
            evidence.put("delivery_accounted", true);
        }

        OrcaClient.Rpc transcript = readTranscript(request.projectRoot(), dispatchId, remaining(deadline));
        String raw = transcript != null ? transcript.stdout() : started == null ? "" : started.stdout();
        writeRaw(request, raw, transcript == null ? "" : transcript.stderr());
        if (transcript != null) {
            evidence.put("raw_stdout", "raw/" + request.evidenceName() + ".stdout.txt");
        }

        if (profile.readOnly()) {
            String fingerprintAfter = git.fingerprint(mergeBase);
            boolean unchanged = fingerprintBefore.equals(fingerprintAfter);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("method", "worktree_content_fingerprint");
            check.put("covers", "tracked edits, deletes, renames and untracked file contents");
            check.put("does_not_cover", "paths ignored by .gitignore");
            check.put("since", resuming ? "worker_start" : "role_start");
            check.put("matched", unchanged);
            evidence.put("read_only_check", check);
            if (!unchanged) {
                evidence.put("failure", "role_violated_read_only");
                return fail("role_violated_read_only", evidence, elapsed(deadline, profile), raw);
            }
        }

        if (!lifecycleAccounted || !deliveryAccounted) {
            evidence.put("failure", "role_orca_lifecycle_unaccounted");
            evidence.putIfAbsent("resolution", "Orca did not account for the previous worker "
                    + "lifecycle; no retry or later stage may share this worktree yet");
            return fail("role_orca_lifecycle_unaccounted", evidence,
                    elapsed(deadline, profile), raw);
        }

        Duration duration = elapsed(deadline, profile);
        if (settlement.kind() == OrcaSettlement.Kind.ESCALATED
                || settlement.kind() == OrcaSettlement.Kind.FAILED) {
            Result quota = quotaFailure(profile, raw, transcript == null ? "" : transcript.stderr(),
                    duration, evidence);
            if (quota != null) return quota;
            evidence.put("failure", "role_command_failed");
            return fail("role_command_failed", evidence, duration, raw);
        }
        if (settlement.kind() != OrcaSettlement.Kind.COMPLETED) {
            evidence.put("failure", remaining(deadline).isZero() || remaining(deadline).isNegative()
                    ? "role_orca_timeout" : "role_orca_unsettled");
            evidence.put("resolution", "Orca did not prove worker_done for this dispatch; terminal "
                    + "text is not treated as completion");
            return fail(String.valueOf(evidence.get("failure")), evidence, duration, raw);
        }

        Map<String, Object> artifact = artifactFrom(settlement.payload(), raw);
        if (artifact == null) {
            Result quota = quotaFailure(profile, raw, transcript == null ? "" : transcript.stderr(),
                    duration, evidence);
            if (quota != null) return quota;
            evidence.put("failure", "role_artifact_unparseable");
            evidence.put("stdout_tail", tail(raw, 2000));
            return fail("role_artifact_unparseable", evidence, duration, raw);
        }
        artifact.putIfAbsent("role", request.role());
        artifact.putIfAbsent("task_id", request.task().id());
        artifact.putIfAbsent("run_id", request.runId());

        List<String> missing = new ArrayList<>();
        for (String field : profile.requiredArtifactFields()) {
            if (!artifact.containsKey(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            evidence.put("failure", "role_artifact_incomplete");
            evidence.put("missing_fields", missing);
            return fail("role_artifact_incomplete", evidence, duration, raw);
        }
        List<String> schemaErrors = schemaErrors(profile, request, artifact);
        if (!schemaErrors.isEmpty()) {
            evidence.put("failure", "role_artifact_schema_violation");
            evidence.put("schema_errors", schemaErrors.stream().limit(40).toList());
            return fail("role_artifact_schema_violation", evidence, duration, raw);
        }
        String semanticFailure = DirectCliExecutor.semanticFailure(request.role(), artifact);
        if (semanticFailure != null) {
            evidence.put("failure", semanticFailure);
            evidence.put("artifact_status", artifact.get("status"));
            return new Result(false, semanticFailure, duration, raw, artifact, evidence);
        }
        DirectCliExecutor.collectTelemetry(artifact, evidence);
        evidence.put("verdict", artifact.get("verdict"));
        return new Result(true, "ok", duration, raw, artifact, evidence);
        } catch (Exception uncertain) {
            // A transport or persistence exception after dispatch is not proof the worker
            // exited. Keep its coordinator reachable instead of closing its mailbox.
            retainCoordinator = true;
            evidence.put("failure", "role_orca_lifecycle_unaccounted");
            evidence.put("coordinator_retained", true);
            evidence.put("resolution", "Orca lifecycle operation failed; inspect the recorded dispatch before retry: "
                    + uncertain.getMessage());
            return fail("role_orca_lifecycle_unaccounted", evidence, elapsed(deadline, profile), "");
        } finally {
            if (!retainCoordinator) closeCoordinator(request.projectRoot(), coordinatorHandle, evidence);
        }
    }

    /**
     * The recorded coordinator terminal, when it is still alive; otherwise null.
     *
     * A stale handle is an ordinary outcome of a restart, not a failure: Orca answers
     * {@code terminal_handle_stale} and the caller creates a fresh coordinator. What is lost
     * with the old terminal is only its mailbox, and dispatch state still proves settlement.
     */
    private String liveCoordinator(Path root, String handle, Map<String, Object> evidence) {
        if (handle == null || handle.isBlank()) return null;
        try {
            OrcaClient.Rpc shown = orca.invoke(root, PROBE_TIMEOUT,
                    List.of("terminal", "show", "--terminal", handle));
            if (shown.ok()) return handle;
            evidence.put("coordinator_lost", OrcaSettlement.errorCode(shown.envelope()));
            return null;
        } catch (Exception unreachable) {
            evidence.put("coordinator_lost", String.valueOf(unreachable.getMessage()));
            return null;
        }
    }

    /**
     * Name the worker terminal, because that title is what the Agent Dashboard shows.
     *
     * Orca stores the task display name but the dashboard row reads the live terminal title,
     * which starts as an internal worker handle and is later overwritten by whatever the agent
     * CLI sets. Warden therefore names the row itself at start, so a person glancing at the
     * board before the agent has said anything still sees which role and vendor is running.
     * Best effort by design: a row with the wrong label is a cosmetic fault, and failing a
     * paid dispatch over it would not be.
     */
    private void nameWorkerRow(Path root, String dispatchId, Request request, Profile profile,
                               Map<String, Object> evidence) {
        if (dispatchId == null) return;
        try {
            OrcaClient.Rpc shown = orca.invoke(root, PROBE_TIMEOUT,
                    List.of("orchestration", "worker-show", "--dispatch", dispatchId));
            String terminal = OrcaSettlement.agentTerminalHandle(shown.envelope());
            if (terminal == null) {
                evidence.put("worker_row_named", false);
                return;
            }
            evidence.put("orca_worker_terminal", terminal);
            OrcaClient.Rpc renamed = orca.invoke(root, PROBE_TIMEOUT,
                    List.of("terminal", "rename", "--terminal", terminal,
                            "--title", workerRowTitle(request, profile)));
            evidence.put("worker_row_named", renamed.ok());
        } catch (Exception ignored) {
            evidence.put("worker_row_named", false);
        }
    }

    /**
     * One line naming the role, the vendor and the task.
     *
     * The vendor name is in it for a reason beyond legibility: Orca buckets a dashboard row by
     * reading its title for a known agent name, so a title without one is a row with no status.
     */
    static String workerRowTitle(Request request, Profile profile) {
        return title(profile.vendor() + " " + request.role() + " - " + request.task().id());
    }

    private void recordWorker(OrcaLifecycle lifecycle, OrcaLifecycle.Worker worker,
                              Map<String, Object> evidence) {
        try {
            lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.withWorker(worker)));
        } catch (Exception failure) {
            evidence.put("lifecycle_write_error", String.valueOf(failure.getMessage()));
        }
    }

    private void markWorker(OrcaLifecycle lifecycle, String key, OrcaLifecycle.State state,
                            String awaiting, Map<String, Object> evidence) {
        markWorker(lifecycle, key, state, awaiting, null, evidence);
    }

    private void markWorker(OrcaLifecycle lifecycle, String key, OrcaLifecycle.State state,
                            String awaiting, String message, Map<String, Object> evidence) {
        try {
            lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.worker(key)
                    .map(worker -> snapshot.withWorker(
                            worker.at(state, awaiting, message, Instant.now())))
                    .orElse(snapshot)));
        } catch (Exception failure) {
            evidence.put("lifecycle_write_error", String.valueOf(failure.getMessage()));
        }
    }

    private void rebindCoordinator(OrcaLifecycle lifecycle, String key, String handle,
                                   Map<String, Object> evidence) {
        try {
            lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.worker(key)
                    .map(worker -> snapshot.withWorker(worker.withCoordinator(handle, Instant.now())))
                    .orElse(snapshot)));
        } catch (Exception failure) {
            evidence.put("lifecycle_write_error", String.valueOf(failure.getMessage()));
        }
    }

    private void bindRun(OrcaLifecycle lifecycle, String runId, Map<String, Object> evidence) {
        try {
            lifecycle.mutate(OrcaLifecycle.of(snapshot -> snapshot.withOrcaRunId(runId)));
        } catch (Exception failure) {
            evidence.put("lifecycle_write_error", String.valueOf(failure.getMessage()));
        }
    }

    private OrcaSettlement.Outcome waitForSettlement(Path root, String taskId, String dispatchId,
                                                     String runId, String coordinatorHandle,
                                                     long deadline, Map<String, Object> evidence)
            throws Exception {
        OrcaSettlement.Outcome last = new OrcaSettlement.Outcome(
                OrcaSettlement.Kind.CHECKPOINT, taskId, dispatchId, null, Map.of(), "not_waited");
        OrcaSettlement.Outcome lastProvenAgentWait = null;
        OrcaSettlement.Outcome latestAgentObservation = null;
        OrcaSettlement.Outcome lastQuestion = null;
        while (positive(remaining(deadline))) {
            Duration beforeCheck = remaining(deadline);
            Duration window = beforeCheck.compareTo(CHECK_WINDOW) < 0 ? beforeCheck : CHECK_WINDOW;
            List<String> checkArgs = new ArrayList<>(List.of(
                    "orchestration", "check",
                    "--wait",
                    "--types", "worker_done,escalation,question",
                    "--timeout-ms", String.valueOf(Math.max(1, window.toMillis()))));
            if (runId != null) {
                checkArgs.add("--run");
                checkArgs.add(runId);
            }
            checkArgs.add("--terminal");
            checkArgs.add(coordinatorHandle);
            Duration checkBudget = bounded(remaining(deadline), window.plusSeconds(5));
            if (!positive(checkBudget)) break;
            OrcaClient.Rpc checked = orca.invoke(root, checkBudget, checkArgs);
            last = OrcaSettlement.fromCheck(checked.envelope(), taskId, dispatchId);
            if (last.kind() == OrcaSettlement.Kind.COMPLETED
                    || last.kind() == OrcaSettlement.Kind.ESCALATED
                    || last.kind() == OrcaSettlement.Kind.FAILED) {
                return last;
            }
            if (last.kind() == OrcaSettlement.Kind.QUESTION) {
                // A question is a healthy wait, not an ending: the agent is alive and blocked on
                // an answer. Take delivery of it and keep watching, so an answer given while
                // this controller is still running lets the same worker finish here — and so
                // the FIFO advances instead of replaying this question to every later attach.
                acknowledge(root, runId, coordinatorHandle, last.deliveryId(), evidence);
                lastQuestion = new OrcaSettlement.Outcome(last.kind(), last.taskId(),
                        last.dispatchId(), last.type(), last.payload(), last.reason(),
                        null, last.messageId());
                evidence.put("question_pending", last.messageId());
                continue;
            }
            // Agent Dashboard can prove that the exact native agent session is parked on a
            // prompt only a person can answer. That wait is healthy: keep this controller
            // alive so answering it from Orca/mobile lets the same worker continue. Remember
            // the last proven state only so a wall-clock expiry says "needs a person" rather
            // than pretending the agent merely ran out of time. An absent observation never
            // clears a prior wait; only Orca's explicit null does.
            Duration observationBudget = bounded(remaining(deadline), Duration.ofSeconds(15));
            if (dispatchId != null && positive(observationBudget)) {
                OrcaClient.Rpc worker = orca.invoke(root, observationBudget,
                        List.of("orchestration", "worker-show", "--dispatch", dispatchId));
                OrcaSettlement.Outcome observed = OrcaSettlement.fromWorkerShow(worker.envelope());
                latestAgentObservation = observed;
                evidence.put("agent_wait_observation", observed.reason());
                if (observed.kind() == OrcaSettlement.Kind.QUESTION) {
                    lastProvenAgentWait = observed;
                    evidence.put("agent_wait", observed.payload());
                } else if (observed.kind() == OrcaSettlement.Kind.CHECKPOINT
                        && "agent_wait_checked_clear".equals(observed.reason())) {
                    lastProvenAgentWait = null;
                    evidence.remove("agent_wait");
                }
            }
            Duration showBudget = bounded(remaining(deadline), Duration.ofSeconds(15));
            if (!positive(showBudget)) break;
            OrcaClient.Rpc shown = orca.invoke(root, showBudget,
                    List.of("orchestration", "dispatch-show", "--task", taskId,
                            "--from", coordinatorHandle));
            OrcaSettlement.Outcome shownOutcome = OrcaSettlement.fromDispatchShow(shown.envelope());
            if (shownOutcome.kind() == OrcaSettlement.Kind.COMPLETED
                    || shownOutcome.kind() == OrcaSettlement.Kind.FAILED
                    || shownOutcome.kind() == OrcaSettlement.Kind.ESCALATED) {
                return shownOutcome;
            }
            last = shownOutcome.kind() == OrcaSettlement.Kind.UNKNOWN ? last : shownOutcome;
        }
        evidence.put("wait_exhausted", true);
        // An unanswered question outranks every other reason the clock ran out: the worker did
        // not stall, it asked something and nobody replied inside the role's budget.
        if (lastQuestion != null) return lastQuestion;
        if (latestAgentObservation != null
                && latestAgentObservation.kind() == OrcaSettlement.Kind.QUESTION) {
            return latestAgentObservation;
        }
        if (lastProvenAgentWait != null && latestAgentObservation != null
                && latestAgentObservation.kind() == OrcaSettlement.Kind.UNKNOWN) {
            Map<String, Object> payload = new LinkedHashMap<>(lastProvenAgentWait.payload());
            payload.put("latest_observation", latestAgentObservation.reason());
            return new OrcaSettlement.Outcome(OrcaSettlement.Kind.QUESTION, taskId, dispatchId,
                    "agent_wait", payload, "agent_wait_last_seen_unverifiable");
        }
        return last;
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static Duration bounded(Duration remaining, Duration cap) {
        if (!positive(remaining)) return Duration.ZERO;
        return remaining.compareTo(cap) < 0 ? remaining : cap;
    }

    private OrcaClient.Rpc readTranscript(Path root, String dispatchId, Duration timeout) {
        if (dispatchId == null || timeout.isZero() || timeout.isNegative()) return null;
        try {
            return orca.invoke(root, timeout.compareTo(Duration.ofSeconds(20)) < 0 ? timeout : Duration.ofSeconds(20),
                    List.of("orchestration", "worker-read", "--dispatch", dispatchId, "--source", "auto"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeRaw(Request request, String stdout, String stderr) throws Exception {
        Path rawDirectory = request.runDirectory().resolve("raw");
        Files.createDirectories(rawDirectory);
        Files.writeString(rawDirectory.resolve(request.evidenceName() + ".stdout.txt"),
                stdout == null ? "" : stdout, StandardCharsets.UTF_8);
        Files.writeString(rawDirectory.resolve(request.evidenceName() + ".stderr.txt"),
                stderr == null ? "" : stderr, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> artifactFrom(Map<String, Object> payload, String raw) {
        Object nested = payload == null ? null : payload.get("warden_artifact");
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = new LinkedHashMap<>((Map<String, Object>) map);
            if (looksLikeArtifact(artifact)) return artifact;
        }
        if (nested instanceof String text) {
            Map<String, Object> artifact = DirectCliExecutor.unwrap(Json.findLastObject(text));
            if (looksLikeArtifact(artifact)) return artifact;
        }
        Map<String, Object> fromPayload = DirectCliExecutor.unwrap(payload);
        if (looksLikeArtifact(fromPayload)) return fromPayload;
        return DirectCliExecutor.unwrap(Json.findLastObject(raw));
    }

    private static String oneLineSpec(Request request) {
        String prompt = request.promptFile().toAbsolutePath().normalize().toString().replace('\\', '/');
        StringBuilder spec = new StringBuilder();
        spec.append("Warden role ").append(request.role())
                .append(" for task ").append(request.task().id())
                .append(". Read and follow UTF-8 prompt file ").append(prompt)
                .append(" in this worktree. Send worker_done exactly once with explicit outcome; ")
                .append("put the exact JSON artifact required by that prompt in payload key warden_artifact.");
        if (request.profile().readOnly()) {
            spec.append(" This role is read-only: do not edit, create or delete project files. "
                    + "Warden rejects results if the worktree content fingerprint changes.");
        }
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            spec.append(" Image evidence paths are listed in the prompt; open every one with the agent's image tool.");
        }
        return spec.toString().replace('\r', ' ').replace('\n', ' ');
    }

    private boolean acknowledge(Path root, String runId, String coordinatorHandle,
                                String deliveryId, Map<String, Object> evidence) {
        if (deliveryId == null) return true;
        try {
            List<String> args = new ArrayList<>(List.of(
                    "orchestration", "check", "--ack", deliveryId,
                    "--terminal", coordinatorHandle));
            if (runId != null) args.addAll(List.of("--run", runId));
            OrcaClient.Rpc acknowledged = orca.invoke(root, Duration.ofSeconds(20), args);
            evidence.put("delivery_ack_ok", acknowledged.ok());
            return acknowledged.ok();
        } catch (Exception failure) {
            evidence.put("delivery_ack_ok", false);
            evidence.put("delivery_ack_error", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /**
     * Fence one dispatch, and say why if it could not be fenced.
     *
     * A failed fence is the one outcome that blocks every later stage in this worktree, so the
     * operator is owed more than a false: the first attempt at this recorded only the boolean,
     * and a live timeout then reported an unaccounted lifecycle with nothing to act on.
     */
    private boolean stopWorker(Path root, String dispatchId, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc stopped = orca.invoke(root, Duration.ofSeconds(30),
                    List.of("orchestration", "worker-stop", "--dispatch", dispatchId));
            evidence.put("worker_stop_ok", stopped.ok());
            evidence.put("worker_stop", stopped.result());
            if (stopped.ok()) return true;
            evidence.put("worker_stop_exit_code", (long) stopped.exitCode());
            evidence.put("worker_stop_timed_out", stopped.timedOut());
            evidence.put("worker_stop_error_code", OrcaSettlement.errorCode(stopped.envelope()));
            evidence.put("worker_stop_stderr", tail(stopped.stderr(), 1000));
            evidence.put("worker_stop_stdout", tail(stopped.stdout(), 1000));
            return fencedAnyway(root, dispatchId, evidence);
        } catch (Exception failure) {
            evidence.put("worker_stop_ok", false);
            evidence.put("worker_stop_error", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /**
     * Ask the dispatch itself whether it is still running, after Orca refused to stop it.
     *
     * A refusal is not a failure to fence. A worker that timed out and had its terminal closed
     * answers {@code dispatch_inactive} — Orca declining to stop something that is already
     * stopped — and treating that as an unaccounted lifecycle blocks every later stage in the
     * worktree over a worker that is provably gone. Measured on Orca 1.4.196: the timed-out
     * dispatch reported {@code status: failed}, {@code stage: process_exited},
     * {@code observation.status: exited}, and {@code worker-list} showed nothing in flight.
     *
     * The reverse still fails closed. If the probe says the dispatch is live, or says nothing
     * usable, the fence is unproven and the caller must not let anything else share the tree.
     */
    private boolean fencedAnyway(Path root, String dispatchId, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc shown = orca.invoke(root, PROBE_TIMEOUT,
                    List.of("orchestration", "worker-show", "--dispatch", dispatchId));
            OrcaRecovery.Assessment after = OrcaRecovery.assess(shown.envelope(), dispatchId);
            boolean fenced = after.verdict() == OrcaRecovery.Verdict.SETTLED
                    || after.verdict() == OrcaRecovery.Verdict.GONE;
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("method", "worker_show_after_refused_stop");
            verification.put("verdict", after.verdict().name().toLowerCase());
            verification.put("reason", after.reason());
            verification.put("fenced", fenced);
            evidence.put("worker_stop_verification", verification);
            return fenced;
        } catch (Exception unreachable) {
            evidence.put("worker_stop_verification", Map.of(
                    "method", "worker_show_after_refused_stop",
                    "fenced", false,
                    "error", String.valueOf(unreachable.getMessage())));
            return false;
        }
    }

    private void closeCoordinator(Path root, String handle, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc closed = orca.invoke(root, Duration.ofSeconds(20),
                    List.of("terminal", "close", "--terminal", handle, "--tab"));
            evidence.put("coordinator_close_ok", closed.ok());
        } catch (Exception failure) {
            evidence.put("coordinator_close_ok", false);
            evidence.put("coordinator_close_error", String.valueOf(failure.getMessage()));
        }
    }

    private static String nestedString(Map<String, Object> result, String container, String key) {
        Object nested = result == null ? null : result.get(container);
        if (nested instanceof Map<?, ?> map && map.get(key) != null) return String.valueOf(map.get(key));
        Object direct = result == null ? null : result.get(key);
        return direct == null ? null : String.valueOf(direct);
    }

    private static String selectorArgument(String selector) {
        if (selector == null) return "current";
        if (selector.startsWith("id:") || selector.startsWith("path:")
                || selector.startsWith("name:") || selector.startsWith("branch:")
                || selector.equals("current") || selector.equals("active")) return selector;
        return selector.contains("::") ? "id:" + selector : selector;
    }

    private static String title(String raw) {
        String oneLine = raw == null ? "warden" : raw.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80);
    }

    private static boolean looksLikeArtifact(Map<String, Object> candidate) {
        return candidate != null && (candidate.containsKey("status")
                || candidate.containsKey("verdict")
                || candidate.containsKey("summary")
                || candidate.containsKey("files_changed"));
    }

    private static List<String> schemaErrors(Profile profile, Request request, Map<String, Object> artifact)
            throws Exception {
        boolean mustCheck = profile.enforceSchema() || "planner".equals(request.role());
        if (!mustCheck || request.schemaFile() == null || !Files.isRegularFile(request.schemaFile())) {
            return List.of();
        }
        Object schema = Json.parse(Files.readString(request.schemaFile()));
        return Schema.validate(artifact, schema);
    }

    private static Result quotaFailure(Profile profile, String stdout, String stderr,
                                       Duration duration, Map<String, Object> evidence) {
        QuotaSignal.Detection detection = QuotaSignal.detect(profile.quotaSignatures(), stdout, stderr);
        if (!detection.matched()) return null;
        evidence.put("failure", "role_quota_exhausted");
        evidence.put("quota", detection.report());
        evidence.put("stderr_tail", tail(stderr, 2000));
        evidence.put("stdout_tail", tail(stdout, 2000));
        return new Result(false, "role_quota_exhausted", duration, stdout, null, evidence);
    }

    private static Result fail(String code, Map<String, Object> evidence, Duration duration, String raw) {
        evidence.putIfAbsent("failure", code);
        return new Result(false, code, duration, raw == null ? "" : raw, null, evidence);
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    private static Duration elapsed(long deadlineNanos, Profile profile) {
        Duration budget = Duration.ofMinutes(profile.wallClockMinutes());
        Duration left = remaining(deadlineNanos);
        Duration used = budget.minus(left);
        return used.isNegative() ? Duration.ZERO : used;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String tail(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }
}
