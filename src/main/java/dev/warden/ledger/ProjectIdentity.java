package dev.warden.ledger;

import dev.warden.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A project identity that survives moving or copying the tree, and is not the path.
 *
 * Git's root commits are the stable name of a repository across worktrees and checkouts.
 * When there is no git history to ask, a UUID stands in. Either way the answer is recorded
 * under {@code .warden/runs/} and that record decides afterwards, because the question is
 * asked on every construction and the tool that answers it can be missing, slow, or looking
 * at an orphan branch - and an identity that changes with the weather is two identities.
 */
public final class ProjectIdentity {

    public static final String FILE_NAME = ".project-identity";

    /** The field separator inside hashed identity material. */
    private static final String SEPARATOR = "\0";

    private ProjectIdentity() {}

    /**
     * One tree, one identity, decided once and then recorded.
     *
     * The recorded answer wins whenever it exists. That is what makes the identity survive a
     * git that is absent from this process's PATH, a rev-list that outruns its timeout, and a
     * HEAD moved onto an orphan branch whose root commits are not the ones the first resolve
     * saw. Asking git afresh on every construction, and minting a UUID whenever the answer did
     * not arrive, is how one project ends up with two identities and nothing joining them -
     * and the machinery that could join them is a later change set.
     *
     * When git does answer and the tree already carries a minted id, the git-derived id is
     * written beside it as {@code git_project_id} rather than replacing it: the id in use stays
     * stable, and the two names for this project are linked where a reader can see them. A copy
     * of a tree carries its record, so the copy keeps the original's identity.
     */
    public static String resolve(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String name = projectName(root);
        Probe probe = gitProbe(root);
        String gitId = probe.roots().isEmpty() ? null : gitIdentity(probe.roots(), name);

        Map<String, Object> record = readRecord(root);
        if (record.get("project_id") instanceof String recorded && !recorded.isBlank()) {
            if (gitId != null && !gitId.equals(String.valueOf(record.get("git_project_id")))) {
                record.put("git_project_id", gitId);
                writeRecord(root, record);
            }
            return recorded;
        }

        if (gitId != null) return persist(root, gitId, name, gitId, false);

        // Nothing recorded and no roots. `answered` separates "this is not a repository",
        // where minting is the right answer, from "git could not be asked", where it is a
        // guess. Both are recorded, and the guess says so, so a later successful probe links
        // its git id instead of starting a second identity.
        String minted = mintedIdentity(name);
        return persist(root, minted, name, null, !probe.answered());
    }

    /** What a git probe established: the root commits, and whether git answered at all. */
    private record Probe(List<String> roots, boolean answered) {}


    public static String projectName(Path projectRoot) {
        Path file = projectRoot.resolve(".warden").resolve("project.yaml");
        if (!Files.isRegularFile(file)) return null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("project:")) {
                    String value = trimmed.substring("project:".length()).strip();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value.isBlank() ? null : value;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String gitIdentity(List<String> roots, String name) {
        return sha256(material("git", String.join(SEPARATOR, roots), name));
    }

    private static String mintedIdentity(String name) {
        return sha256(material("uuid", UUID.randomUUID().toString(), name));
    }

    private static String material(String kind, String body, String name) {
        return "warden.project.v1" + SEPARATOR + kind + SEPARATOR + body
                + SEPARATOR + (name == null ? "" : name);
    }

    private static Map<String, Object> readRecord(Path root) {
        Path file = recordFile(root);
        try {
            if (Files.isRegularFile(file)) {
                return new LinkedHashMap<>(
                        Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            // A broken identity file is replaced rather than guessed from the path.
        }
        return new LinkedHashMap<>();
    }

    private static Path recordFile(Path root) {
        return root.resolve(".warden").resolve("runs").resolve(FILE_NAME);
    }

    private static void writeRecord(Path root, Map<String, Object> body) {
        try {
            Path file = recordFile(root);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.writePretty(body) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Identity still labels this process's events even if it cannot be persisted.
        }
    }

    private static String persist(Path root, String id, String name, String gitId,
                                  boolean provisional) {
        Path file = recordFile(root);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", 1L);
        body.put("project_id", id);
        if (name != null) body.put("project_name", name);
        if (gitId != null) body.put("git_project_id", gitId);
        if (provisional) body.put("provisional", Boolean.TRUE);
        try {
            Files.createDirectories(file.getParent());
            try {
                Files.writeString(file, Json.writePretty(body) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                Map<String, Object> existing = Json.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (existing.get("project_id") instanceof String previous && !previous.isBlank()) {
                    return previous;
                }
            }
        } catch (Exception ignored) {
            // Identity still labels this process's events even if it cannot be persisted.
        }
        return id;
    }

    private static Probe gitProbe(Path root) {
        List<String> command = List.of(
                "git", "-c", "safe.directory=" + root.toString().replace('\\', '/'),
                "-C", root.toString(), "rev-list", "--max-parents=0", "HEAD");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Probe(List.of(), false);
            }
            // git ran and said no. That is an answer, and minting an identity is then the
            // right response; a git that could not run at all throws instead.
            if (process.exitValue() != 0) return new Probe(List.of(), true);
            List<String> roots = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String commit = line.strip();
                if (commit.matches("[0-9a-f]{40,}")) roots.add(commit);
            }
            return new Probe(roots.stream().sorted().collect(Collectors.toList()), true);
        } catch (Exception ignored) {
            return new Probe(List.of(), false);
        }
    }

    static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
