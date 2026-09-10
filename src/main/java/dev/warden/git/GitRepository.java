package dev.warden.git;

import dev.warden.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Git is the workspace truth: merge-base diff, blast radius and content fingerprint. */
public final class GitRepository {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private final Path root;
    private final ProcessRunner processes;

    public GitRepository(Path root, ProcessRunner processes) {
        this.root = root.toAbsolutePath().normalize();
        this.processes = processes;
    }

    public Path root() { return root; }

    public String mergeBase(String baseRef) throws IOException, InterruptedException {
        return git(List.of("merge-base", "HEAD", baseRef)).stdout().strip();
    }

    public Set<String> changedPaths() throws IOException, InterruptedException {
        Set<String> paths = new LinkedHashSet<>();
        // Content changes vs HEAD. Do not use `status --porcelain`: on Windows a file can be
        // "modified" in the index/worktree because of CRLF after `npm install` while
        // `git diff --name-only HEAD` is empty. That false dirty bit stopped a live
        // `warden do` before review — the implementer had only edited an in-scope CSS file.
        for (String path : git(List.of("diff", "--name-only", "-z", "HEAD", "--")).stdout().split("\0", -1)) {
            if (!path.isBlank()) addChangedPath(paths, path);
        }
        for (String path : git(List.of("ls-files", "-o", "--exclude-standard", "-z")).stdout().split("\0", -1)) {
            if (!path.isBlank()) addChangedPath(paths, path);
        }
        return paths;
    }

    /** Every path changed by commits, index, working tree or untracked files since merge-base. */
    public Set<String> changedPaths(String mergeBase) throws IOException, InterruptedException {
        Set<String> paths = new LinkedHashSet<>();
        String[] committedAndWorking = git(List.of("diff", "--name-only", "-z", mergeBase, "--"))
                .stdout().split("\0", -1);
        for (String path : committedAndWorking) if (!path.isBlank()) addChangedPath(paths, path);
        paths.addAll(changedPaths());
        return paths;
    }

    public String fingerprint(String mergeBase) throws IOException, InterruptedException {
        return fingerprint(mergeBase, false);
    }

    /**
     * The fingerprint of the source change alone, with Warden's own `.warden` tree left out.
     *
     * Two different questions want two different scopes, and conflating them cost a real
     * acceptance. "Did this read-only role touch anything?" must include `.warden`: a
     * reviewer writing a task file is a violation. "Is this the candidate the human was
     * shown?" must not: the operator accepts a source change, and Warden's own evidence,
     * logs and config are not part of what will be landed. A run whose log file happened to
     * live under `.warden` invalidated its own acceptance the moment the log was written —
     * the guard fired correctly over the wrong set of files.
     *
     * Contract integrity is not weakened by this. Any change under `.warden` during the run
     * is already `contract_mutated`, checked against a content snapshot after every role and
     * around every gate command.
     */
    public String sourceFingerprint(String mergeBase) throws IOException, InterruptedException {
        return fingerprint(mergeBase, true);
    }

    private String fingerprint(String mergeBase, boolean sourceOnly)
            throws IOException, InterruptedException {
        MessageDigest digest = sha256();
        // Raw metadata catches deletion, rename and mode/status changes. File bytes catch
        // edits to already-dirty and binary files without decoding a binary patch as text.
        digest.update(shapeOf(git(List.of("diff", "--raw", "-z", mergeBase, "--")).stdout(), sourceOnly)
                .getBytes(StandardCharsets.UTF_8));
        Set<String> paths = changedPaths(mergeBase);
        if (sourceOnly) paths = dev.warden.config.WardenTree.sourcePaths(paths);
        List<String> changed = paths.stream().sorted().toList();
        for (String relative : changed) {
            digest.update((byte) 0);
            digest.update(normalize(relative).getBytes(StandardCharsets.UTF_8));
            Path file = root.resolve(relative).normalize();
            if (Files.isRegularFile(file)) {
                digest.update((byte) 1);
                digest.update(Files.readAllBytes(file));
            } else {
                digest.update((byte) 2);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * `git diff --raw` with the blob hashes removed, keeping the modes, the status and the path.
     *
     * The hashes are why this exists. A raw record is
     * {@code :<srcmode> <dstmode> <srcsha> <dstsha> <status>\0<path>}, and the destination hash
     * is all zeroes while a change sits in the working tree and a real object once it is
     * committed. So the same bytes in the same file produced two different fingerprints
     * depending only on whether they had been committed yet — which is not a change to the
     * candidate, and it broke the one flow that matters: `warden land --commit` made a commit
     * and then `warden land --push` refused it as `candidate_changed`, having been invalidated
     * by Warden's own previous step.
     *
     * What the raw line is here for survives: a deletion, a rename and a mode change all show
     * in the modes, the status letter and the path. The content is hashed separately below and
     * never depended on the blob ids at all.
     */
    static String shapeOf(String raw) {
        return shapeOf(raw, false);
    }

    /**
     * @param sourceOnly drop records whose every path is Warden's own. The filter below the
     *                   raw shape already did this to the content half of the fingerprint,
     *                   and leaving the shape unfiltered meant `.warden` still moved it: a
     *                   `land:` block added to `project.yaml` — the very edit the landing step
     *                   asks for when it has nowhere to push — made `warden land` refuse the
     *                   run as `candidate_changed`. That is the same failure the fingerprint
     *                   split was written to end, firing again through the one input the split
     *                   did not reach.
     */
    static String shapeOf(String raw, boolean sourceOnly) {
        StringBuilder shape = new StringBuilder();
        String[] records = raw.split("\0", -1);
        for (int index = 0; index < records.length; index++) {
            String record = records[index];
            if (record.isEmpty()) continue;
            if (record.charAt(0) != ':') {
                shape.append(record).append('\0');
                continue;
            }
            String[] fields = record.substring(1).trim().split("\\s+");
            // :srcmode dstmode srcsha dstsha status — keep everything but the two hashes.
            if (fields.length < 5) {
                shape.append(record).append('\0');
                continue;
            }
            String status = fields[fields.length - 1];
            // A rename or a copy carries two paths; everything else carries one.
            int pathCount = status.startsWith("R") || status.startsWith("C") ? 2 : 1;
            List<String> paths = new ArrayList<>();
            for (int offset = 1; offset <= pathCount && index + offset < records.length; offset++) {
                paths.add(records[index + offset]);
            }
            // A rename out of `.warden` into the source tree is a source change; only a record
            // that touches nothing but Warden's own tree is dropped.
            if (sourceOnly && !paths.isEmpty()
                    && paths.stream().allMatch(GitRepository::isWardenPath)) {
                index += paths.size();
                continue;
            }
            shape.append(':').append(fields[0]).append(' ').append(fields[1]).append(' ')
                    .append(status).append('\0');
        }
        return shape.toString();
    }

    private static boolean isWardenPath(String path) {
        String normalized = normalize(path);
        return normalized.equals(dev.warden.config.WardenTree.DIRECTORY)
                || normalized.startsWith(dev.warden.config.WardenTree.DIRECTORY + "/");
    }

    public List<String> outsideScope(Set<String> paths, List<String> scopes) {
        // A repository with no baseline has no existing code for a boundary to protect. The
        // token is the only way to say so, and it has to be written down to be true.
        if (scopes.contains(dev.warden.config.RepoPath.WHOLE_REPOSITORY)) return List.of();
        List<String> violations = new ArrayList<>();
        for (String path : paths) {
            boolean within = scopes.stream().anyMatch(scope -> path.equals(scope) || path.startsWith(scope + "/"));
            if (!within) violations.add(path);
        }
        violations.sort(Comparator.naturalOrder());
        return violations;
    }

    /**
     * Dirty paths, their bytes, HEAD and the content fingerprint at one moment, used to put
     * back only what a later writer introduced — including a commit.
     *
     * {@code reset --hard} plus {@code clean -fd} is not this. Those revert every tracked
     * modification and delete every untracked non-ignored file in the run root, including
     * work that predates the snapshot — and under {@code warden do --in-place} that root is
     * the operator's own checkout.
     */
    public static final class WorkingTreeSnapshot {
        private final String head;
        private final String fingerprint;
        private final Map<String, FileState> files;

        private WorkingTreeSnapshot(String head, String fingerprint, Map<String, FileState> files) {
            this.head = head;
            this.fingerprint = fingerprint;
            this.files = Map.copyOf(files);
        }
    }

    private record FileState(boolean present, byte[] bytes) {
        static FileState of(byte[] bytes) { return new FileState(true, bytes); }
        static FileState missing() { return new FileState(false, null); }
    }

    /**
     * Every path {@link #changedPaths()} currently reports, with the bytes that are there
     * (or a missing marker for a deletion vs HEAD), plus HEAD and the content fingerprint
     * against that commit. {@code .warden/runs} is already absent from the path set, so
     * evidence is not captured and is not later reverted.
     */
    public WorkingTreeSnapshot snapshotWorkingTree() throws IOException, InterruptedException {
        String head = git(List.of("rev-parse", "HEAD")).stdout().strip();
        if (head.isBlank()) {
            throw new IOException("git rev-parse HEAD returned no commit; refusing to snapshot a tree with no HEAD");
        }
        Map<String, FileState> files = new LinkedHashMap<>();
        for (String path : changedPaths()) {
            Path file = resolveInside(path);
            if (Files.isRegularFile(file)) files.put(path, FileState.of(Files.readAllBytes(file)));
            else files.put(path, FileState.missing());
        }
        return new WorkingTreeSnapshot(head, fingerprint(head), files);
    }

    /**
     * Discard only what moved after {@code before} was taken.
     *
     * A commit the later writer made is moved back with {@code reset --mixed} to the captured
     * HEAD, not {@code reset --hard}: mixed keeps the working tree, including evidence under
     * {@code .warden/runs} and untracked operator files, so the file-level restore can put
     * back only the writer's delta. Paths the writer introduced are then reverted to HEAD if
     * they are tracked and deleted if they are not. Paths that were already dirty are put
     * back byte-for-byte, including untracked files the writer deleted.
     *
     * Refuses rather than guessing when there is no snapshot, and refuses when the tree
     * cannot be shown to match the captured HEAD and fingerprint — a retry on a tree the
     * writer still holds would re-baseline the read-only check after the mutation.
     */
    public void restoreWorkingTree(WorkingTreeSnapshot before) throws IOException, InterruptedException {
        if (before == null) {
            throw new IOException("no working-tree snapshot; refusing to reset a tree Warden did not capture");
        }
        String head = git(List.of("rev-parse", "HEAD")).stdout().strip();
        if (!before.head.equals(head)) {
            git(List.of("reset", "--mixed", before.head));
        }
        Set<String> now = changedPaths();
        for (String path : now) {
            if (before.files.containsKey(path)) continue;
            revertIntroduced(path);
        }
        for (Map.Entry<String, FileState> entry : before.files.entrySet()) {
            Path file = resolveInside(entry.getKey());
            FileState state = entry.getValue();
            if (!state.present()) {
                if (Files.isRegularFile(file)) Files.delete(file);
                continue;
            }
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(file, state.bytes());
        }
        String headAfter = git(List.of("rev-parse", "HEAD")).stdout().strip();
        if (!before.head.equals(headAfter)) {
            throw new IOException("restore could not return HEAD to " + before.head
                    + " (now " + headAfter + "); refusing to retry on a tree the writer mutated");
        }
        if (!before.fingerprint.equals(fingerprint(before.head))) {
            throw new IOException("restore could not return the worktree to the captured baseline; "
                    + "refusing to retry on a tree the writer mutated");
        }
    }

    /**
     * Whether HEAD and the content fingerprint still match {@code before}. The planner
     * bootstrap asks this after the call, so a mutation is a protocol failure even if the
     * profile's {@code read_only} flag did not trigger the executor's own check.
     */
    public boolean matchesSnapshot(WorkingTreeSnapshot before) throws IOException, InterruptedException {
        if (before == null) {
            throw new IOException("no working-tree snapshot; refusing to treat an uncaptured tree as unchanged");
        }
        String head = git(List.of("rev-parse", "HEAD")).stdout().strip();
        return before.head.equals(head) && before.fingerprint.equals(fingerprint(before.head));
    }

    private void revertIntroduced(String path) throws IOException, InterruptedException {
        String gitPath = path.replace('\\', '/');
        // ls-files --error-unmatch succeeds for a staged addition that is not in HEAD, and
        // checkout HEAD -- path then fails. Ask HEAD, not the index.
        ProcessRunner.Result inHead = gitRaw(List.of("cat-file", "-e", "HEAD:" + gitPath));
        if (inHead.ok()) {
            git(List.of("checkout", "HEAD", "--", path));
            return;
        }
        gitRaw(List.of("rm", "-f", "--cached", "--", path));
        deleteRecursively(resolveInside(path));
    }

    private Path resolveInside(String relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("refusing to restore a path that escapes the worktree: " + relative);
        }
        return file;
    }

    private static void deleteRecursively(Path file) throws IOException {
        if (!Files.exists(file) && !Files.isSymbolicLink(file)) return;
        if (Files.isDirectory(file)) {
            try (var paths = Files.walk(file)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } else {
            Files.deleteIfExists(file);
        }
    }

    public ProcessRunner.Result git(List<String> args) throws IOException, InterruptedException {
        ProcessRunner.Result result = gitRaw(args);
        if (!result.ok()) throw new IOException("git " + String.join(" ", args) + " failed: " + result.stderr());
        return result;
    }

    private ProcessRunner.Result gitRaw(List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=" + root.toString().replace('\\', '/'));
        command.addAll(args);
        return processes.run(command, root, GIT_TIMEOUT);
    }

    public static String sha256(Path... files) throws IOException {
        MessageDigest digest = sha256();
        for (Path file : files) {
            digest.update(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 of file bytes only, for portable artifact integrity evidence. */
    public static String contentSha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String normalize(String path) { return path.replace('\\', '/').replaceFirst("^\\./", ""); }

    private static void addChangedPath(Set<String> paths, String rawPath) {
        String path = normalize(rawPath);
        // Runtime evidence is written by Warden itself while a role is running, so including
        // it would make every read-only fingerprint fail. Contract files are deliberately NOT
        // excluded: the run controller snapshots them before the first agent and a mutation
        // must remain observable. GateRunner exempts only the two exact snapshotted contract
        // paths from source blast-radius checks; any other .warden edit is a violation.
        if (path.equals(".warden/runs") || path.startsWith(".warden/runs/")) return;
        paths.add(path);
    }
}
