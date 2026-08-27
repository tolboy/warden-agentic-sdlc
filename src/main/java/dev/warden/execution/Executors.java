package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.execution.orca.OrcaExecutor;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chooses an adapter from the profile's {@code runner} field. Routing stays in code: a model
 * never picks how it is launched.
 *
 * {@code local} is accepted as a name so a profile can declare the intent, and refused at
 * dispatch so the gap is visible rather than silently turned into a direct CLI call.
 */
public final class Executors {

    private Executors() {}

    public static RoleExecutor forProfile(Profile profile, ProcessRunner processes, GitRepository git) {
        return switch (profile.runner()) {
            case "orca" -> new OrcaExecutor(processes, git);
            case "local" -> new UnimplementedRunner(profile.runner());
            default -> new DirectCliExecutor(processes, git);
        };
    }

    private static final class UnimplementedRunner implements RoleExecutor {
        private final String runner;

        UnimplementedRunner(String runner) { this.runner = runner; }

        @Override
        public Result execute(Request request) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("runner", runner);
            evidence.put("failure", "role_runner_unimplemented");
            evidence.put("resolution", "this runner is named in the architecture and not yet wired; "
                    + "use runner: direct, or runner: orca inside an Orca worktree");
            return new Result(false, "role_runner_unimplemented", Duration.ZERO, "", null, evidence);
        }
    }
}
