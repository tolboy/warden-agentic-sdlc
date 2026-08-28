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
import java.util.LinkedHashSet;
import java.util.List;
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
        MessageDigest digest = sha256();
        // Raw metadata catches deletion, rename and mode/status changes. File bytes catch
        // edits to already-dirty and binary files without decoding a binary patch as text.
        digest.update(git(List.of("diff", "--raw", "-z", mergeBase, "--"))
                .stdout().getBytes(StandardCharsets.UTF_8));
        List<String> changed = changedPaths(mergeBase).stream().sorted().toList();
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

    public ProcessRunner.Result git(List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=" + root.toString().replace('\\', '/'));
        command.addAll(args);
        ProcessRunner.Result result = processes.run(command, root, GIT_TIMEOUT);
        if (!result.ok()) throw new IOException("git " + String.join(" ", args) + " failed: " + result.stderr());
        return result;
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
