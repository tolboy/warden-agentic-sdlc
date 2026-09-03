package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.execution.orca.OrcaExecutor;
import dev.warden.git.GitRepository;
import dev.warden.process.ProcessRunner;

/**
 * Chooses an adapter from the profile's {@code runner} field. Routing stays in code: a model
 * never picks how it is launched.
 *
 * {@code local} POSTs to the profile's {@code endpoint}. It is never rewritten as a direct
 * CLI call — a missing local server must fail as itself, not as a vendor that was never asked
 * for.
 */
public final class Executors {

    private Executors() {}

    public static RoleExecutor forProfile(Profile profile, ProcessRunner processes, GitRepository git) {
        return switch (profile.runner()) {
            case "orca" -> new OrcaExecutor(processes, git);
            // git, so a read_only local role is held to the same fingerprint as a CLI one.
            case "local" -> new LocalHttpExecutor(System::getenv, null, git);
            default -> new DirectCliExecutor(processes, git);
        };
    }
}
