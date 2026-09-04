package dev.warden.execution.orca;

import dev.warden.approval.HumanDecision;
import dev.warden.process.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes Warden's pending human decision as an Orca decision gate, and reads the answer back.
 *
 * The point is reach, not authority. A run that stops for a person stops wherever that person
 * is not, and an Orca gate is answerable from any surface Orca reaches — another machine, a
 * phone — while {@code warden approve} needs the worktree. So the question is mirrored out and
 * the answer is carried back in.
 *
 * <h2>Warden stays the source of truth, and this is why</h2>
 *
 * Orca gates are not immutable. Measured against 1.4.196: {@code gate-resolve} accepts free
 * text rather than one of the declared options, and re-resolving an already-resolved gate
 * silently replaces the answer. Neither is a defect in Orca — a gate is a coordination
 * primitive between agents — but both would be defects in an authorization record.
 *
 * So the gate is a doorbell, never the decision. An answer is admitted only when it maps
 * exactly onto one of the options {@link HumanDecision} itself declares, only while the
 * pending decision is still the one the gate was published about (the version token), and for
 * an acceptance only while the candidate fingerprint still matches. Once {@code decision.json}
 * records a resolution it is final; a gate edited afterwards changes nothing, because the
 * second import is refused as a duplicate by the store rather than by politeness here.
 */
public final class OrcaDecisionGate {

    /** What publishing produced, or the named reason it produced nothing. */
    public record Publication(boolean published, String reason, OrcaLifecycle.Gate gate,
                              String detail) {

        public Publication(boolean published, String reason, OrcaLifecycle.Gate gate) {
            this(published, reason, gate, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("published", published);
            value.put("reason", reason);
            if (detail != null) value.put("detail", detail);
            if (gate != null) {
                value.put("gate_id", gate.gateId());
                value.put("task_id", gate.taskId());
                value.put("orca_run_id", gate.orcaRunId());
                value.put("options", gate.options());
            }
            return value;
        }
    }

    /** One gate as Orca reports it. {@code status} is {@code pending} or {@code resolved}. */
    public record Answer(String gateId, String status, String resolution, String question) {
        public boolean resolved() { return "resolved".equals(status); }
    }

    private final OrcaClient orca;

    public OrcaDecisionGate(ProcessRunner processes) {
        this.orca = new OrcaClient(processes);
    }

    /**
     * Mirror {@code decision} into Orca as a pending gate, recording the gate in {@code lifecycle}.
     *
     * Never throws for an Orca that is absent, busy or old: a run that reached its human gate
     * has already done the work, and losing the extra surface must not turn a green run red.
     * The reason is returned so the caller can say what did not happen instead of implying it did.
     */
    public Publication publish(Path root, OrcaLifecycle lifecycle, HumanDecision decision,
                               String objective) {
        try {
            Map<String, Object> status = orca.status(root);
            if (!Boolean.TRUE.equals(status.get("available"))) {
                return new Publication(false, "orca_unavailable", null);
            }
            if (!Boolean.TRUE.equals(status.get("orchestration_contract"))) {
                return new Publication(false, "orchestration_contract_missing", null);
            }
            OrcaClient.Rpc current = orca.invoke(root, Duration.ofSeconds(15),
                    List.of("worktree", "current"));
            String selector = OrcaSettlement.worktreeSelector(current.envelope());
            if (!current.ok() || selector == null) {
                return new Publication(false, "not_an_orca_worktree", null);
            }
            OrcaClient.Rpc coordinator = orca.invoke(root, Duration.ofSeconds(30),
                    List.of("terminal", "create", "--worktree", selectorArgument(selector),
                            "--title", "warden-gate-" + decision.runId()));
            String handle = nested(coordinator.result(), "terminal", "handle");
            if (!coordinator.ok() || handle == null) {
                return new Publication(false, "no_coordinator", null);
            }
            try {
                String orcaRunId = lifecycle.read().orcaRunId();
                OrcaClient.Rpc run = orcaRunId == null
                        ? orca.invoke(root, Duration.ofSeconds(20),
                                List.of("orchestration", "run-create",
                                        "--objective", oneLine("warden " + decision.runId() + ": " + objective),
                                        "--from", handle))
                        : orca.invoke(root, Duration.ofSeconds(20),
                                List.of("orchestration", "run-use", "--id", orcaRunId, "--from", handle));
                if (orcaRunId == null) {
                    orcaRunId = nested(run.result(), "run", "id");
                    if (orcaRunId == null) {
                        orcaRunId = string(OrcaSettlement.first(run.result(), "runId", "id", "run_id"));
                    }
                }
                if (!run.ok() || orcaRunId == null) {
                    return new Publication(false, "no_run", null, detailOf(run));
                }

                // The gate hangs on a task of its own rather than on whichever task a vendor
                // last ran. That task is finished; what is unfinished is the person's answer,
                // and a blocked task should name the thing that is actually blocked.
                OrcaClient.Rpc task = orca.invoke(root, Duration.ofSeconds(20),
                        List.of("orchestration", "task-create",
                                "--spec", oneLine("Warden run " + decision.runId() + " stopped for a person: "
                                        + decision.reason()),
                                "--task-title", truncate("Warden decision: " + decision.runId()),
                                "--display-name", truncate("decision / " + decision.kind().jsonValue()),
                                "--run", orcaRunId, "--from", handle));
                String taskId = OrcaSettlement.taskId(task.envelope());
                if (taskId == null) taskId = string(OrcaSettlement.first(task.result(), "id", "taskId"));
                if (!task.ok() || taskId == null) {
                    return new Publication(false, "no_task", null, detailOf(task));
                }

                OrcaClient.Rpc created = orca.invoke(root, Duration.ofSeconds(20),
                        List.of("orchestration", "gate-create",
                                "--task", taskId,
                                "--question", oneLine(question(decision)),
                                "--options", OrcaClient.jsonArgument(decision.options()),
                                "--from", handle));
                String gateId = nested(created.result(), "gate", "id");
                if (!created.ok() || gateId == null) {
                    return new Publication(false, "gate_refused", null,
                            detailOf(created));
                }

                OrcaLifecycle.Gate gate = new OrcaLifecycle.Gate(gateId, taskId, orcaRunId, handle,
                        decision.kind().jsonValue(), decision.options(),
                        decision.updatedAt().toString(), decision.candidateFingerprint(),
                        Instant.now());
                lifecycle.mutate(OrcaLifecycle.of(snapshot ->
                        snapshot.withOrcaRunId(gate.orcaRunId()).withGate(gate)));
                return new Publication(true, "gate_published", gate);
            } finally {
                // The gate outlives the terminal: gate-list and gate-resolve both address a Run
                // directly, so leaving a coordinator tab open would buy nothing and litter the
                // operator's board for as long as the decision is unanswered.
                closeQuietly(root, handle);
            }
        } catch (Exception failure) {
            return new Publication(false, "publish_failed", null,
                    String.valueOf(failure.getMessage()));
        }
    }

    /** Why Orca refused, in the form an operator can act on: its code, else its last words. */
    private static String detailOf(OrcaClient.Rpc rpc) {
        String code = OrcaSettlement.errorCode(rpc.envelope());
        if (code != null) return code;
        String stderr = rpc.stderr() == null ? "" : rpc.stderr().strip();
        if (!stderr.isEmpty()) return stderr.length() <= 300 ? stderr : stderr.substring(0, 300);
        String stdout = rpc.stdout() == null ? "" : rpc.stdout().strip();
        return stdout.length() <= 300 ? stdout : stdout.substring(0, 300);
    }

    /** Read one published gate back from Orca, or null when it cannot be read. */
    public Answer read(Path root, OrcaLifecycle.Gate gate) throws Exception {
        OrcaClient.Rpc listed = orca.invoke(root, Duration.ofSeconds(20),
                List.of("orchestration", "gate-list", "--run", gate.orcaRunId()));
        if (!listed.ok()) return null;
        return answerFrom(listed.envelope(), gate.gateId());
    }

    /**
     * Find one gate in a {@code gate-list} envelope.
     *
     * The options field arrives as a JSON string rather than an array in Orca 1.4, the same
     * wire shape {@code worker_done} payloads use. It is not read here in any case: the
     * options that bind are Warden's own.
     */
    public static Answer answerFrom(Map<String, Object> envelope, String gateId) {
        if (envelope == null || !OrcaSettlement.envelopeOk(envelope)) return null;
        Object raw = OrcaSettlement.resultOf(envelope).get("gates");
        List<Map<String, Object>> gates = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) gates.add(cast(map));
        }
        Object single = OrcaSettlement.resultOf(envelope).get("gate");
        if (single instanceof Map<?, ?> map) gates.add(cast(map));
        for (Map<String, Object> gate : gates) {
            if (!gateId.equals(string(OrcaSettlement.first(gate, "id", "gateId", "gate_id")))) continue;
            return new Answer(gateId,
                    string(OrcaSettlement.first(gate, "status", "state")),
                    string(gate.get("resolution")),
                    string(gate.get("question")));
        }
        return null;
    }

    /**
     * The declared option a free-text resolution names, or null when it names none.
     *
     * Case and surrounding space are forgiven because a person typed it. Nothing else is:
     * "accept if the tests pass" is not an acceptance, and guessing which half of it to obey
     * is exactly the judgement Warden is not allowed to make on someone's behalf.
     */
    public static String choose(String resolution, List<String> options) {
        if (resolution == null) return null;
        String normalized = resolution.strip();
        for (String option : options) {
            if (option.equalsIgnoreCase(normalized)) return option;
        }
        return null;
    }

    /** What the person is being asked, with the options spelled out in the question itself. */
    public static String question(HumanDecision decision) {
        return "Warden run " + decision.runId() + " (" + decision.taskId() + "): "
                + decision.reason() + " Reply with exactly one of "
                + String.join(" or ", decision.options())
                + ". Nothing is landed either way.";
    }

    private void closeQuietly(Path root, String handle) {
        try {
            orca.invoke(root, Duration.ofSeconds(20),
                    List.of("terminal", "close", "--terminal", handle, "--tab"));
        } catch (Exception ignored) {
            // A leftover coordinator tab is untidy, never wrong.
        }
    }

    private static String selectorArgument(String selector) {
        if (selector == null) return "current";
        if (selector.startsWith("id:") || selector.startsWith("path:")
                || selector.startsWith("name:") || selector.startsWith("branch:")
                || selector.equals("current") || selector.equals("active")) return selector;
        return selector.contains("::") ? "id:" + selector : selector;
    }

    private static String nested(Map<String, Object> result, String container, String key) {
        Object nested = result == null ? null : result.get(container);
        if (nested instanceof Map<?, ?> map && map.get(key) != null) return String.valueOf(map.get(key));
        Object direct = result == null ? null : result.get(key);
        return direct == null ? null : String.valueOf(direct);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String truncate(String text) {
        String line = oneLine(text);
        return line.length() <= 80 ? line : line.substring(0, 80);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
