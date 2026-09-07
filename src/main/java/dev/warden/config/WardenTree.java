package dev.warden.config;

import dev.warden.git.GitRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A content snapshot of the project's `.warden` tree, taken before the first agent is
 * dispatched and compared against afterwards.
 *
 * This replaces hashing two files. The narrower check let an agent add a second task
 * contract, rewrite the policy a later stage reads, or delete a scenario file, and none of
 * those is the selected task's `project.yaml` or `task.yaml`. What has to be true is simpler
 * than the old rule and stronger: **nothing under `.warden` changes while a run is in
 * flight** — a run cannot rewrite the terms it is judged by.
 *
 * `.warden/runs` is excluded, and only that: it is the evidence Warden itself is writing
 * while the run proceeds, so including it would make every check fail against itself.
 *
 * Because that invariant is enforced first, everything else in `.warden` is provably
 * unchanged by the time blast radius is measured. That is what lets those paths sit outside
 * the task's source scope without opening a hole: a project whose `.warden` directory is not
 * committed no longer fails its own second task for the crime of having a contract on disk.
 */
public final class WardenTree {

    public static final String DIRECTORY = ".warden";
    public static final String EVIDENCE = ".warden/runs";

    private WardenTree() {}

    /** Relative path → SHA-256 of its bytes, for every configuration file under `.warden`. */
    public static Map<String, String> snapshot(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path warden = root.resolve(DIRECTORY);
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(warden)) return Map.copyOf(files);
        try (var paths = Files.walk(warden)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = relative(root, file);
                if (isEvidence(relative)) continue;
                files.put(relative, GitRepository.contentSha256(file));
            }
        }
        return Map.copyOf(files);
    }

    /**
     * Which configuration paths differ from the snapshot: modified, added or removed. An
     * addition counts, because a task file that did not exist when the run started is a term
     * nobody agreed to.
     */
    public static List<String> changedSince(Path projectRoot, Map<String, String> pinned)
            throws IOException {
        Map<String, String> now = snapshot(projectRoot);
        Set<String> everything = new LinkedHashSet<>(pinned.keySet());
        everything.addAll(now.keySet());
        List<String> changed = new ArrayList<>();
        for (String path : everything) {
            String before = pinned.get(path);
            String after = now.get(path);
            if (before == null) changed.add(path + " (added)");
            else if (after == null) changed.add(path + " (removed)");
            else if (!before.equals(after)) changed.add(path + " (modified)");
        }
        return List.copyOf(changed);
    }

    /** One stable hash of the whole snapshot, for the run summary and the evidence report. */
    public static String digest(Map<String, String> snapshot) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(snapshot).entrySet()) {
            digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The same snapshot with one file's bytes replaced by a hash of only the part of it a
     * verdict depends on.
     *
     * Used for exactly one decision: whether a role's judgement from an earlier run still
     * describes this tree. {@link #digest} stays the answer to "has anything moved" and is
     * what a live run is fenced by; this is the answer to "has anything a reviewer read
     * moved", which is a narrower question and the only one worth asking after the run has
     * already stopped. The narrowing is confined to the selected task file — every other file
     * under `.warden` still enters by its full content, so a rewritten policy or a deleted
     * scenario declines reuse exactly as before.
     *
     * @param taskFile         the selected task contract, relative to the project root
     * @param acceptanceOfTask {@link TaskSpec.ResolvedTask#acceptanceFingerprint()} for it
     */
    public static String acceptanceDigest(Map<String, String> snapshot, String taskFile,
                                          String acceptanceOfTask) {
        Map<String, String> surface = new TreeMap<>(snapshot);
        if (taskFile != null) surface.put(taskFile, acceptanceOfTask);
        return digest(surface);
    }

    /**
     * Paths a task's blast radius is measured over: the project's source, with Warden's own
     * configuration removed. Sound only because a change to any of it has already failed the
     * run by this point.
     */
    public static Set<String> sourcePaths(Set<String> changed) {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : changed) {
            if (path.equals(DIRECTORY) || path.startsWith(DIRECTORY + "/")) continue;
            paths.add(path);
        }
        return paths;
    }

    private static boolean isEvidence(String relative) {
        return relative.equals(EVIDENCE) || relative.startsWith(EVIDENCE + "/");
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Kept so callers can report an empty snapshot without a null. */
    public static Map<String, String> none() { return new LinkedHashMap<>(); }
}
