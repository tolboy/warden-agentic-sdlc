package dev.warden.gate;

import dev.warden.config.ConfigLoader;
import dev.warden.git.GitRepository;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic preflight and acceptance gates. No model participates in routing decisions. */
public final class GateRunner {
    private final ProcessRunner processes;

    public GateRunner(ProcessRunner processes) { this.processes = processes; }

    public record Outcome(boolean ok, String code, Path report, Map<String, Object> data) {}

    public Outcome run(ConfigLoader.Loaded loaded, String runId) throws IOException, InterruptedException {
        return run(loaded, runId, null, null);
    }

    /**
     * Run gates against the immutable base and contract snapshot selected before the first
     * agent dispatch. Null values preserve the standalone {@code warden gates} behaviour.
     */
    public Outcome run(ConfigLoader.Loaded loaded, String runId, String expectedContractHash,
                       String pinnedMergeBase) throws IOException, InterruptedException {
        EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId);
        try {
        GitRepository git = new GitRepository(loaded.root(), processes);
        String observedHash = GitRepository.sha256(loaded.projectFile(), loaded.taskFile());
        String contractHash = expectedContractHash != null ? expectedContractHash : observedHash;
        String mergeBase;
        try {
            mergeBase = pinnedMergeBase != null ? pinnedMergeBase
                    : git.mergeBase(loaded.resolved().baseRef());
        } catch (Exception failure) {
            return finish(ledger, false, "base_ref_unresolvable", base(loaded, contractHash, null,
                    List.of(), List.of(), failure.getMessage()));
        }
        if (!contractHash.equals(observedHash)) {
            return finish(ledger, false, "contract_mutated", base(loaded, contractHash, mergeBase,
                    List.copyOf(git.changedPaths(mergeBase)), List.of(),
                    "configuration changed after the run snapshot and before machine gates"));
        }

        Set<String> initialPaths = git.changedPaths(mergeBase);
        List<String> initialViolations = git.outsideScope(sourcePaths(loaded, initialPaths),
                loaded.resolved().scopePaths());
        if (!initialViolations.isEmpty()) {
            return finish(ledger, false, "preflight_outside_scope", base(loaded, contractHash, mergeBase,
                    List.copyOf(initialPaths), initialViolations, null));
        }

        List<Map<String, Object>> commands = new ArrayList<>();
        long totalMillis = 0;
        for (int index = 0; index < loaded.resolved().acceptanceCommands().size(); index++) {
            String command = loaded.resolved().acceptanceCommands().get(index);
            String beforeHash = GitRepository.sha256(loaded.projectFile(), loaded.taskFile());
            if (!contractHash.equals(beforeHash)) {
                return finish(ledger, false, "contract_mutated", base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(), "configuration changed before command " + index));
            }
            Set<String> beforePaths = git.changedPaths(mergeBase);
            List<String> beforeViolations = git.outsideScope(sourcePaths(loaded, beforePaths),
                    loaded.resolved().scopePaths());
            if (!beforeViolations.isEmpty()) {
                return finish(ledger, false, "blast_radius_before_command", base(loaded, contractHash,
                        mergeBase, List.copyOf(beforePaths), beforeViolations, null));
            }

            Duration remaining = Duration.ofMinutes(loaded.resolved().timeoutMinutes()).minusMillis(totalMillis);
            if (remaining.isNegative() || remaining.isZero()) {
                return finish(ledger, false, "gate_timeout", base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(), "shared task timeout exhausted"));
            }
            ProcessRunner.Result result = processes.run(shell(command), loaded.root(), remaining);
            totalMillis += result.durationMillis();
            commands.add(commandEvidence(index, command, result));

            String afterHash = GitRepository.sha256(loaded.projectFile(), loaded.taskFile());
            if (!contractHash.equals(afterHash)) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(), "configuration changed by command " + index);
                report.put("commands", commands);
                return finish(ledger, false, "contract_mutated", report);
            }
            Set<String> afterPaths = git.changedPaths(mergeBase);
            List<String> violations = git.outsideScope(sourcePaths(loaded, afterPaths),
                    loaded.resolved().scopePaths());
            if (!violations.isEmpty()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(afterPaths), violations, null);
                report.put("commands", commands);
                return finish(ledger, false, "blast_radius_after_command", report);
            }
            if (!result.ok()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(afterPaths), List.of(), result.timedOut() ? "command timed out" : "command failed");
                report.put("commands", commands);
                return finish(ledger, false, result.timedOut() ? "command_timeout" : "command_failed", report);
            }
        }

        Map<String, Object> report = base(loaded, contractHash, mergeBase,
                List.copyOf(git.changedPaths(mergeBase)), List.of(), null);
        report.put("commands", commands);
        report.put("worktree_fingerprint", git.fingerprint(mergeBase));
        report.put("does_not_cover", List.of("paths ignored by .gitignore"));
        return finish(ledger, true, "passed", report);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Map<String, Object> report = base(loaded, null, null, List.of(), List.of(),
                    "gate execution interrupted");
            return finish(ledger, false, "gate_interrupted", report);
        } catch (Exception failure) {
            Map<String, Object> report = base(loaded, null, null, List.of(), List.of(),
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return finish(ledger, false, "gate_internal_error", report);
        }
    }

    private Outcome finish(EvidenceLedger ledger, boolean ok, String code, Map<String, Object> report)
            throws IOException {
        report.put("ok", ok);
        report.put("code", code);
        report.put("finished_at", Instant.now().toString());
        Path path = ledger.writeReport("machine-gate", report);
        ledger.append("machine_gate", Map.of("ok", ok, "code", code, "report", path.toString()));
        return new Outcome(ok, code, path, report);
    }

    private static Map<String, Object> base(ConfigLoader.Loaded loaded, String hash, String mergeBase,
                                            List<String> changed, List<String> violations, String message) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1L);
        report.put("task_id", loaded.resolved().id());
        report.put("project", loaded.project().project());
        report.put("contract_sha256", hash);
        report.put("base_ref", loaded.resolved().baseRef());
        report.put("merge_base", mergeBase);
        report.put("scope_paths", loaded.resolved().scopePaths());
        report.put("changed_paths", changed);
        report.put("violations", violations);
        if (message != null) report.put("message", message);
        return report;
    }

    private static Map<String, Object> commandEvidence(int index, String command, ProcessRunner.Result result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", (long) index);
        item.put("command", command);
        item.put("exit_code", (long) result.exitCode());
        item.put("timed_out", result.timedOut());
        item.put("duration_ms", result.durationMillis());
        item.put("stdout", result.stdout());
        item.put("stderr", result.stderr());
        item.put("stdout_truncated", result.stdoutTruncated());
        item.put("stderr_truncated", result.stderrTruncated());
        return item;
    }

    private static List<String> shell(String command) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    /**
     * The project contract and selected task may have been created by {@code warden do} and
     * are governed by the snapshot hash, not by the task's source-code scope. No other Warden
     * path receives this exemption.
     */
    private static Set<String> sourcePaths(ConfigLoader.Loaded loaded, Set<String> changed) {
        Set<String> paths = new java.util.LinkedHashSet<>(changed);
        paths.remove(relative(loaded.root(), loaded.projectFile()));
        paths.remove(relative(loaded.root(), loaded.taskFile()));
        return paths;
    }

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
