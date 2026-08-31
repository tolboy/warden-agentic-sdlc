package dev.warden.execution;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where the work happens, so that it does not happen on the branch the operator is looking at.
 *
 * `warden do` refuses to edit your checkout. Something has to hand it a separate one, and for
 * a long time that something could only be Orca — which made a desktop application a hard
 * requirement of the headline command, in a tool whose first paragraph is about having no
 * dependencies. Git has done worktrees since 2015 and every user already has it.
 *
 * So there are two backends and one contract. Neither creates a branch on the checkout it was
 * pointed at, and both fail closed: no isolation means no run, not a run in place. `--in-place`
 * remains the one way to say "edit the tree I am standing in", and it stays explicit because
 * that is the whole of the difference.
 */
public interface Isolation {

    /**
     * Where the loop will run, and how it got there.
     *
     * @param isolated false only for `--in-place`
     * @param path     the worktree the run will use
     * @param selector how the backend names this worktree, when it names them at all
     * @param reason   `created`, `already_isolated`, `attached` or `in_place` — recorded so a
     *                 report can say whether this run made its own room or joined one
     */
    record Placement(boolean isolated, Path path, String selector, String reason) {
        public static Placement inPlace(Path path) {
            return new Placement(false, path, null, "in_place");
        }
    }

    /**
     * Cut a worktree named {@code name} from {@code baseBranch}, or explain why not.
     *
     * The name is a suggestion: a backend may sanitise it. The branch is not — a worktree cut
     * from the wrong base is a candidate judged against the wrong diff.
     */
    Placement isolate(Path project, String name, String baseBranch) throws Exception;

    /**
     * A refusal to isolate, carrying the code the run will stop with.
     *
     * An IOException because every caller is already handling one, and because a failure to
     * make room for the work is not a different kind of event from a failure to write in it.
     */
    final class IsolationException extends IOException {
        private final String code;

        public IsolationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
