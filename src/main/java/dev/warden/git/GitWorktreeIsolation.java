package dev.warden.git;

import dev.warden.execution.Isolation;
import dev.warden.process.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Isolation with nothing but Git.
 *
 * The built-in backend, and the reason `warden do` works for somebody who has just cloned this
 * repository. Warden's own first paragraph is about having no dependencies, and until this
 * existed its headline command required a desktop application to be installed and running —
 * with the only fallback being `--in-place`, which is the path everything else here calls
 * unsafe. A tool whose safe route needs a third-party app and whose available route is the
 * dangerous one has its defaults the wrong way round.
 *
 * The worktree goes in a sibling directory, `<repo>-worktrees/<name>`, not inside the
 * repository. Inside would put the candidate's own checkout in the blast radius of the task
 * being run in it, and `git status` in the parent would list it forever.
 */
public final class GitWorktreeIsolation implements Isolation {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final ProcessRunner processes;

    public GitWorktreeIsolation(ProcessRunner processes) { this.processes = processes; }

    @Override
    public Placement isolate(Path project, String name, String baseBranch) throws Exception {
        Path root = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git")) && !Files.isRegularFile(root.resolve(".git"))) {
            throw new IsolationException("isolation_not_a_repository",
                    root + " is not a Git repository, so there is nothing to cut a worktree from. "
                            + "Use --init-repo to make one, or --in-place for a directory you are "
                            + "content to have edited where it stands.");
        }

        String branch = sanitize(name);
        Path home = root.resolveSibling(root.getFileName() + "-worktrees");
        Path target = home.resolve(branch);

        // Already there. The same answer Orca gives when the operator is standing in a worktree
        // it made earlier: join it rather than refusing, so a second `warden do` on the same
        // task id continues the work instead of demanding a new name.
        if (Files.isDirectory(target)) {
            if (!Files.exists(target.resolve(".git"))) {
                throw new IsolationException("isolation_path_occupied",
                        target + " exists and is not a Git worktree. Warden will not empty a "
                                + "directory it did not create; move it aside or pass --task-id "
                                + "to work under another name.");
            }
            return new Placement(true, target, branch, "already_isolated");
        }

        Files.createDirectories(home);
        List<String> add = new ArrayList<>(List.of("git", "worktree", "add", "-b", branch,
                target.toString(), baseBranch));
        ProcessRunner.Result created = processes.run(add, root, TIMEOUT);
        if (!created.ok()) {
            // A branch of that name already exists but has no worktree — the usual case being a
            // worktree removed by hand. Attaching to the branch is right; recreating it is not,
            // because the work already on it is the operator's.
            ProcessRunner.Result attached = processes.run(
                    List.of("git", "worktree", "add", target.toString(), branch), root, TIMEOUT);
            if (!attached.ok()) {
                throw new IsolationException("isolation_worktree_failed",
                        "git worktree add refused both a new branch and the existing one: "
                                + tail(created.stderr()) + " / " + tail(attached.stderr()));
            }
            return new Placement(true, target, branch, "attached");
        }
        return new Placement(true, target, branch, "created");
    }

    /**
     * A branch name Git will accept, from a task id.
     *
     * Task ids are already slugs, so this almost never changes anything. It exists for the
     * cases that would otherwise fail at `git worktree add` with a message about refnames,
     * which tells an operator nothing about the task id they chose.
     */
    public static String sanitize(String name) {
        StringBuilder safe = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '/') safe.append(c);
            else if (!safe.isEmpty() && safe.charAt(safe.length() - 1) != '-') safe.append('-');
        }
        while (!safe.isEmpty() && (safe.charAt(safe.length() - 1) == '-'
                || safe.charAt(safe.length() - 1) == '/')) {
            safe.deleteCharAt(safe.length() - 1);
        }
        return safe.isEmpty() ? "warden-work" : safe.toString();
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) return "(no output)";
        String trimmed = text.strip();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(trimmed.length() - 400);
    }
}
