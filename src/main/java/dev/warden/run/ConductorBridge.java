package dev.warden.run;

import dev.warden.execution.DirectCliExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Optional human UI/checkpoint adapter. Warden remains the state machine and decision store;
 * Conductor only runs the prepared inner task and records the human's choice through
 * {@code warden approve}. This avoids two competing retry controllers.
 */
public final class ConductorBridge {

    public record Outcome(boolean ok, int exitCode, boolean timedOut, Path workflow) {}

    public Outcome run(Path projectRoot, String taskId, String runId, boolean autoReject,
                       Duration timeout) throws IOException, InterruptedException {
        return run(projectRoot, taskId, runId, autoReject, timeout, "off");
    }

    public Outcome run(Path projectRoot, String taskId, String runId, boolean autoReject,
                       Duration timeout, String prepare) throws IOException, InterruptedException {
        Path workflow = locateWorkflow();
        if (workflow == null) {
            throw new IOException("conductor/do.yaml not found next to Warden; set WARDEN_HOME");
        }
        String mode = prepare == null || prepare.isBlank() ? "off" : prepare;
        List<String> command = new ArrayList<>();
        command.add(DirectCliExecutor.resolveExecutable("conductor", projectRoot));
        command.addAll(List.of("run", workflow.toString(),
                "--input", "task=" + taskId,
                "--input", "project_dir=" + projectRoot.toAbsolutePath().normalize(),
                "--input", "run_id=" + runId,
                "--input", "actor=" + System.getProperty("user.name", "local-operator"),
                "--input", "prepare=" + mode));
        if (autoReject) command.add("--skip-gates");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .inheritIO();
        // Conductor/Python otherwise inherits a Windows ANSI console and can corrupt task
        // paths/messages that Warden itself keeps as UTF-8.
        builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!finished) terminate(process);
        int exit = process.isAlive() ? -1 : process.exitValue();
        return new Outcome(finished && exit == 0, exit, !finished, workflow);
    }

    static Path locateWorkflow() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("WARDEN_HOME");
        if (home != null && !home.isBlank()) candidates.add(Path.of(home, "conductor", "do.yaml"));
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve("conductor/do.yaml"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static void terminate(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (process.waitFor(2, TimeUnit.SECONDS)) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
    }
}
