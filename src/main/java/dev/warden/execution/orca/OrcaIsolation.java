package dev.warden.execution.orca;

import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Isolation is Orca's job. Warden never runs {@code git branch} / {@code git worktree add};
 * it asks Orca to create a checkout and then runs inside it.
 *
 * A main-worktree checkout is not isolation. {@code warden do} will not write into {@code main}
 * through this type: if Orca cannot place the work, the command fails closed rather than
 * editing the branch the operator is looking at.
 */
public final class OrcaIsolation {

    public record Placement(boolean isolated, Path path, String selector, String reason) {
        public static Placement inPlace(Path path) {
            return new Placement(false, path, null, "in_place");
        }
    }

    private final OrcaClient orca;

    public OrcaIsolation(ProcessRunner processes) { this.orca = new OrcaClient(processes); }

    public Placement isolate(Path project, String name, String baseBranch) throws Exception {
        Path root = project.toAbsolutePath().normalize();
        Map<String, Object> status = orca.status(root);
        if (!Boolean.TRUE.equals(status.get("available"))) {
            throw new IsolationException("role_orca_unavailable",
                    "Orca is not running; `warden do` will not edit the current branch in place. "
                            + "Start Orca, or pass --in-place for a throwaway repository.");
        }
        OrcaClient.Rpc current = orca.invoke(root, Duration.ofSeconds(20), List.of("worktree", "current"));
        if (current.ok() && Boolean.FALSE.equals(worktreeField(current.result(), "isMainWorktree"))) {
            Path here = pathOf(current.result());
            if (here != null && Files.isDirectory(here)) {
                return new Placement(true, here, OrcaSettlement.worktreeSelector(current.envelope()),
                        "already_isolated");
            }
        }

        String repoId = repoIdFor(root);
        if (repoId == null) {
            OrcaClient.Rpc added = orca.invoke(root, Duration.ofSeconds(30),
                    List.of("repo", "add", "--path", root.toString()));
            if (!added.ok()) {
                throw new IsolationException("role_orca_unavailable",
                        "orca repo add failed: " + tail(added.stderr(), 500));
            }
            repoId = string(OrcaSettlement.first(added.result(), "id", "repoId"));
            if (repoId == null) repoId = repoIdFor(root);
        }
        if (repoId == null) {
            throw new IsolationException("role_orca_unavailable", "Orca did not return a repo id for " + root);
        }

        OrcaClient.Rpc created = orca.invoke(root, Duration.ofMinutes(10), List.of(
                "worktree", "create",
                "--repo", "id:" + repoId,
                "--name", name,
                "--base-branch", baseBranch,
                "--no-parent",
                "--setup", "run"));
        if (!created.ok()) {
            throw new IsolationException("role_orca_no_worktree",
                    "orca worktree create failed: " + tail(created.stderr(), 800)
                            + tail(created.stdout(), 400));
        }
        Path path = pathOf(created.result());
        if (path == null || !Files.isDirectory(path)) {
            throw new IsolationException("role_orca_no_worktree",
                    "Orca created a worktree but did not return a usable path");
        }
        return new Placement(true, path, OrcaSettlement.worktreeSelector(created.envelope()), "created");
    }

    @SuppressWarnings("unchecked")
    String repoIdFor(Path project) throws Exception {
        OrcaClient.Rpc listed = orca.invoke(project, Duration.ofSeconds(20), List.of("repo", "list"));
        if (!listed.ok()) return null;
        Object repos = listed.result().get("repos");
        if (!(repos instanceof List<?> list)) return null;
        String want = normalize(project);
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> repo)) continue;
            Object path = repo.get("path");
            if (path != null && normalize(Path.of(String.valueOf(path))).equals(want)) {
                Object id = repo.get("id");
                return id == null ? null : String.valueOf(id);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Path pathOf(Map<String, Object> result) {
        Object nested = result.get("worktree");
        if (nested instanceof Map<?, ?> map && map.get("path") != null) {
            return Path.of(String.valueOf(map.get("path")));
        }
        Object path = result.get("path");
        return path == null ? null : Path.of(String.valueOf(path));
    }

    static Object worktreeField(Map<String, Object> result, String field) {
        Object nested = result.get("worktree");
        if (nested instanceof Map<?, ?> map) return map.get(field);
        return result.get(field);
    }

    static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    private static String string(Object value) { return value == null ? null : String.valueOf(value); }

    private static String tail(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }

    public static final class IsolationException extends IOException {
        private final String code;
        public IsolationException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
