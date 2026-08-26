package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Vendor-neutral execution seam. Direct CLI and Orca are adapters, never policy owners. */
public interface RoleExecutor {
    record Request(String runId, String role, Profile profile, TaskSpec.ResolvedTask task,
                   Path projectRoot, Path promptFile, Path schemaFile, String context) {}

    record Result(boolean ok, String code, Duration duration, String rawOutput,
                  Map<String, Object> artifact, Map<String, Object> evidence) {}

    Result execute(Request request) throws Exception;
}
