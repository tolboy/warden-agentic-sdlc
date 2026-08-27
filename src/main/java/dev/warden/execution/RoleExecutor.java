package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Vendor-neutral execution seam. Direct CLI and Orca are adapters, never policy owners. */
public interface RoleExecutor {
    /**
     * @param evidenceName base name for this attempt's raw output files. It is the role name
     *                     for a first attempt and carries an attempt suffix afterwards, so a
     *                     failover cannot overwrite the transcript of the run it replaced.
     */
    record Request(String runId, String role, Profile profile, TaskSpec.ResolvedTask task,
                   Path projectRoot, Path runDirectory, Path promptFile, Path schemaFile,
                   String context, String evidenceName, java.util.List<Path> attachments) {}

    record Result(boolean ok, String code, Duration duration, String rawOutput,
                  Map<String, Object> artifact, Map<String, Object> evidence) {}

    Result execute(Request request) throws Exception;
}
