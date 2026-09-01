package dev.warden.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Resolves project-owned configuration without ever mixing it with user vendor profiles. */
public final class ConfigLoader {
    public record Loaded(Path root, Path projectFile, Path taskFile, ProjectConfig project,
                         TaskSpec task, TaskSpec.ResolvedTask resolved) {}

    public Loaded load(Path start, String taskSelector) throws IOException {
        Path root = findProjectRoot(start);
        Path projectFile = root.resolve(".warden/project.yaml");
        Path taskFile = resolveTask(root, taskSelector);
        ProjectConfig project = ProjectConfig.parse(Files.readString(projectFile), projectFile.toString());
        TaskSpec task = TaskSpec.parse(Files.readString(taskFile), taskFile.toString());
        return new Loaded(root, projectFile, taskFile, project, task,
                task.resolve(project, taskFile.toString()));
    }

    public Path findProjectRoot(Path start) throws IOException {
        return locateProjectRoot(start).orElseThrow(() ->
                new IOException("no .warden/project.yaml found from " + start.toAbsolutePath()));
    }

    /**
     * The same walk as {@link #findProjectRoot}, without turning "not a project" into a generic
     * I/O failure. {@code status} and {@code approve} need that distinction so they can say so
     * instead of claiming the run does not exist.
     */
    public Optional<Path> locateProjectRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".warden/project.yaml"))) return Optional.of(current);
            current = current.getParent();
        }
        return Optional.empty();
    }

    private Path resolveTask(Path root, String selector) throws IOException {
        if (selector == null || selector.isBlank()) throw new IOException("task id or YAML path is required");
        Path direct = Path.of(selector);
        if (!direct.isAbsolute()) direct = root.resolve(direct);
        if (Files.isRegularFile(direct)) return direct.normalize();
        Path byId = root.resolve(".warden/tasks").resolve(selector.endsWith(".yaml") ? selector : selector + ".yaml");
        if (Files.isRegularFile(byId)) return byId.normalize();
        throw new IOException("task not found: " + selector);
    }
}
