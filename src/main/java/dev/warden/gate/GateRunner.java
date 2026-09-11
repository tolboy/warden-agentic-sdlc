package dev.warden.gate;

import dev.warden.config.ConfigLoader;
import dev.warden.config.WardenTree;
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
    private Path home;

    private record Kind(String phase, String reportName, String eventType) {}

    private static final Kind ACCEPTANCE = new Kind("acceptance", "machine-gate", "machine_gate");
    private static final Kind BASELINE = new Kind("baseline", "baseline-gate", "baseline_gate");

    public GateRunner(ProcessRunner processes) { this.processes = processes; }

    /**
     * The operator home this gate writes measurements into. Must be passed by the caller;
     * this class never reads the environment.
     */
    public GateRunner withHome(Path home) {
        this.home = home;
        return this;
    }

    public record Outcome(boolean ok, String code, Path report, Map<String, Object> data) {}

    public Outcome run(ConfigLoader.Loaded loaded, String runId) throws IOException, InterruptedException {
        return run(loaded, runId, null, null);
    }

    /**
     * Run gates against the immutable base and `.warden` snapshot selected before the first
     * agent dispatch. Null values preserve the standalone {@code warden gates} behaviour,
     * which takes its own snapshot on entry and so only sees a command mutating the config.
     */
    public Outcome run(ConfigLoader.Loaded loaded, String runId, Map<String, String> pinnedConfig,
                       String pinnedMergeBase) throws IOException, InterruptedException {
        return runCommands(loaded, runId, pinnedConfig, pinnedMergeBase,
                loaded.resolved().acceptanceCommands(), ACCEPTANCE);
    }

    /**
     * Establish project health before the first vendor is dispatched. The commands are the
     * explicit project-owned baseline set; the ordinary machine gate runs them again because
     * {@code ResolvedTask.acceptanceCommands()} contains their ordered union with task checks.
     */
    public Outcome runBaseline(ConfigLoader.Loaded loaded, String runId,
                               Map<String, String> pinnedConfig, String pinnedMergeBase)
            throws IOException, InterruptedException {
        return runCommands(loaded, runId, pinnedConfig, pinnedMergeBase,
                loaded.resolved().baselineCommands(), BASELINE);
    }

    private Outcome runCommands(ConfigLoader.Loaded loaded, String runId,
                                Map<String, String> pinnedConfig, String pinnedMergeBase,
                                List<String> commandsToRun, Kind kind)
            throws IOException, InterruptedException {
        EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId, home);
        try {
        GitRepository git = new GitRepository(loaded.root(), processes);
        Map<String, String> config = pinnedConfig != null ? pinnedConfig
                : WardenTree.snapshot(loaded.root());
        String contractHash = WardenTree.digest(config);
        String mergeBase;
        try {
            mergeBase = pinnedMergeBase != null ? pinnedMergeBase
                    : git.mergeBase(loaded.resolved().baseRef());
        } catch (Exception failure) {
            return finish(ledger, kind, false, "base_ref_unresolvable", base(loaded, contractHash, null,
                    List.of(), List.of(), failure.getMessage()));
        }
        List<String> mutated = WardenTree.changedSince(loaded.root(), config);
        if (!mutated.isEmpty()) {
            Map<String, Object> report = base(loaded, contractHash, mergeBase,
                    List.copyOf(git.changedPaths(mergeBase)), List.of(),
                    "configuration changed after the run snapshot and before machine gates");
            report.put("configuration_changed", mutated);
            return finish(ledger, kind, false, "contract_mutated", report);
        }

        Set<String> initialPaths = git.changedPaths(mergeBase);
        List<String> initialViolations = git.outsideScope(WardenTree.sourcePaths(initialPaths),
                loaded.resolved().scopePaths());
        if (!initialViolations.isEmpty()) {
            return finish(ledger, kind, false, "preflight_outside_scope", base(loaded, contractHash, mergeBase,
                    List.copyOf(initialPaths), initialViolations, null));
        }
        String baselineSource = kind.equals(BASELINE) ? git.sourceFingerprint(mergeBase) : null;

        List<Map<String, Object>> commands = new ArrayList<>();
        long totalMillis = 0;
        for (int index = 0; index < commandsToRun.size(); index++) {
            String command = commandsToRun.get(index);
            List<String> beforeMutations = WardenTree.changedSince(loaded.root(), config);
            if (!beforeMutations.isEmpty()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(),
                        "configuration changed before command " + index);
                report.put("configuration_changed", beforeMutations);
                return finish(ledger, kind, false, "contract_mutated", report);
            }
            Set<String> beforePaths = git.changedPaths(mergeBase);
            List<String> beforeViolations = git.outsideScope(WardenTree.sourcePaths(beforePaths),
                    loaded.resolved().scopePaths());
            if (!beforeViolations.isEmpty()) {
                return finish(ledger, kind, false, "blast_radius_before_command", base(loaded, contractHash,
                        mergeBase, List.copyOf(beforePaths), beforeViolations, null));
            }

            Duration remaining = Duration.ofMinutes(loaded.resolved().timeoutMinutes()).minusMillis(totalMillis);
            if (remaining.isNegative() || remaining.isZero()) {
                return finish(ledger, kind, false, "gate_timeout", base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(), "shared task timeout exhausted"));
            }
            ProcessRunner.Result result = processes.run(shell(command), loaded.root(), remaining);
            totalMillis += result.durationMillis();
            commands.add(commandEvidence(index, command, result));

            List<String> afterMutations = WardenTree.changedSince(loaded.root(), config);
            if (!afterMutations.isEmpty()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(git.changedPaths(mergeBase)), List.of(),
                        "configuration changed by command " + index);
                report.put("configuration_changed", afterMutations);
                report.put("commands", commands);
                return finish(ledger, kind, false, "contract_mutated", report);
            }
            Set<String> afterPaths = git.changedPaths(mergeBase);
            List<String> violations = git.outsideScope(WardenTree.sourcePaths(afterPaths),
                    loaded.resolved().scopePaths());
            if (baselineSource != null) {
                String afterSource = git.sourceFingerprint(mergeBase);
                if (!baselineSource.equals(afterSource)) {
                    Map<String, Object> report = base(loaded, contractHash, mergeBase,
                            List.copyOf(afterPaths), violations,
                            "a baseline command changed project source before vendor dispatch");
                    report.put("commands", commands);
                    report.put("source_fingerprint_before", baselineSource);
                    report.put("source_fingerprint_after", afterSource);
                    return finish(ledger, kind, false, "baseline_mutated_source", report);
                }
            }
            if (!violations.isEmpty()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(afterPaths), violations, null);
                report.put("commands", commands);
                return finish(ledger, kind, false, "blast_radius_after_command", report);
            }
            if (!result.ok()) {
                Map<String, Object> report = base(loaded, contractHash, mergeBase,
                        List.copyOf(afterPaths), List.of(), result.timedOut() ? "command timed out" : "command failed");
                report.put("commands", commands);
                return finish(ledger, kind, false,
                        result.timedOut() ? "command_timeout" : "command_failed", report);
            }
        }

        Map<String, Object> report = base(loaded, contractHash, mergeBase,
                List.copyOf(git.changedPaths(mergeBase)), List.of(), null);
        report.put("commands", commands);
        report.put("worktree_fingerprint", git.fingerprint(mergeBase));
        report.put("does_not_cover", List.of("paths ignored by .gitignore"));
        return finish(ledger, kind, true, "passed", report);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Map<String, Object> report = base(loaded, null, null, List.of(), List.of(),
                    "gate execution interrupted");
            return finish(ledger, kind, false, "gate_interrupted", report);
        } catch (Exception failure) {
            Map<String, Object> report = base(loaded, null, null, List.of(), List.of(),
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return finish(ledger, kind, false, "gate_internal_error", report);
        }
    }

    private Outcome finish(EvidenceLedger ledger, Kind kind, boolean ok, String code,
                           Map<String, Object> report)
            throws IOException {
        report.put("phase", kind.phase());
        report.put("ok", ok);
        report.put("code", code);
        report.put("finished_at", Instant.now().toString());
        Path path = ledger.writeReport(kind.reportName(), report);
        ledger.append(kind.eventType(),
                Map.of("ok", ok, "code", code, "report", path.toString()));
        ledger.recordCorpusVisibility(report);
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

}
