package dev.warden.execution.orca;

import dev.warden.execution.Isolation;
import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public final class OrcaIsolation implements Isolation {

    private final OrcaClient orca;

    public OrcaIsolation(ProcessRunner processes) { this.orca = new OrcaClient(processes); }

    public OrcaIsolation(OrcaClient orca) { this.orca = orca; }

    @Override
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

        Placement attached = attachExisting(root, name, repoId);
        if (attached != null) return attached;

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

    /**
     * Join a worktree Orca already made for this name, instead of creating another whose
     * display name collides. Orca appends a numeric suffix when the name is taken; a dry
     * run followed by a live run with the same task id used to leave two.
     *
     * Prefer an exact {@code displayName} match. If several of those exist, prefer the one
     * whose path's last segment equals {@code name}. If that is still not unique, do not
     * guess — fall through and create.
     */
    @SuppressWarnings("unchecked")
    private Placement attachExisting(Path root, String name, String repoId) throws Exception {
        OrcaClient.Rpc listed = orca.invoke(root, Duration.ofSeconds(20), List.of("worktree", "ps"));
        if (!listed.ok()) return null;
        Object raw = listed.result().get("worktrees");
        if (!(raw instanceof List<?> rows)) return null;

        List<Map<String, Object>> byName = new ArrayList<>();
        List<Map<String, Object>> bySegment = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) continue;
            if (Boolean.TRUE.equals(row.get("isMainWorktree"))) continue;
            if (Boolean.TRUE.equals(row.get("isArchived"))) continue;
            if (!repoId.equals(string(row.get("repoId")))) continue;
            Object pathValue = row.get("path");
            if (pathValue == null) continue;
            Path path = Path.of(String.valueOf(pathValue));
            if (!Files.isDirectory(path)) continue;
            Map<String, Object> asMap = (Map<String, Object>) row;
            boolean nameMatch = name.equals(string(row.get("displayName")));
            Path fileName = path.getFileName();
            boolean segmentMatch = fileName != null && name.equals(fileName.toString());
            if (nameMatch) byName.add(asMap);
            else if (segmentMatch) bySegment.add(asMap);
        }
        Map<String, Object> chosen = chooseAttachment(byName, bySegment, name);
        if (chosen == null) return null;
        Path path = Path.of(String.valueOf(chosen.get("path")));
        String selector = selectorFor(chosen, path);
        return new Placement(true, path, selector, "attached");
    }

    private static Map<String, Object> chooseAttachment(List<Map<String, Object>> byName,
                                                        List<Map<String, Object>> bySegment,
                                                        String name) {
        if (byName.size() == 1) return byName.get(0);
        if (byName.size() > 1) {
            List<Map<String, Object>> tighter = new ArrayList<>();
            for (Map<String, Object> row : byName) {
                Path path = Path.of(String.valueOf(row.get("path")));
                Path fileName = path.getFileName();
                if (fileName != null && name.equals(fileName.toString())) tighter.add(row);
            }
            return tighter.size() == 1 ? tighter.get(0) : null;
        }
        return bySegment.size() == 1 ? bySegment.get(0) : null;
    }

    private static String selectorFor(Map<String, Object> worktree, Path path) {
        Map<String, Object> nested = new LinkedHashMap<>();
        Object id = worktree.get("worktreeId");
        if (id == null) id = worktree.get("id");
        if (id != null) nested.put("id", id);
        nested.put("path", path.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("result", Map.of("worktree", nested));
        String selector = OrcaSettlement.worktreeSelector(envelope);
        return selector != null ? selector : "path:" + path;
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

}
