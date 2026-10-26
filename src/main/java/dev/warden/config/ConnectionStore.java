package dev.warden.config;

import dev.warden.json.JsonFile;
import dev.warden.yaml.Yaml;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Nonsecret connection files under the user's Warden home. */
public final class ConnectionStore {
    public record Snapshot(Map<String, Connection> connections, Map<String, String> problems) {
        public Snapshot {
            connections = Collections.unmodifiableMap(new LinkedHashMap<>(connections));
            problems = Collections.unmodifiableMap(new LinkedHashMap<>(problems));
        }
    }

    private final Path home;
    private final Path directory;

    public ConnectionStore(Path home) {
        this.home = home.toAbsolutePath().normalize();
        this.directory = this.home.resolve("connections");
    }

    public Snapshot load() throws IOException {
        Map<String, Connection> connections = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();
        for (Path file : yamlFiles(directory)) {
            String key = file.getFileName().toString().replaceFirst("\\.yaml$", "");
            try {
                Connection connection = read(file);
                if (!connection.id().equals(key)) {
                    problems.put(key, "connection id does not match the file name");
                } else connections.put(key, connection);
            } catch (ConfigException failure) {
                problems.put(key, failure.getMessage());
            } catch (IOException failure) {
                problems.put(key, "connection file could not be read");
            }
        }
        return new Snapshot(connections, problems);
    }

    public Path add(Connection connection) throws IOException {
        Path target = path(connection.id());
        return JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException("connection", "connection already exists; existing files are never overwritten by add");
            }
            writeAtomically(target, connection.toYaml(), false);
            return target;
        });
    }

    public Connection setEnabled(String id, boolean enabled) throws IOException {
        Path target = path(id);
        return JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            Connection current = read(target);
            if (!current.id().equals(id)) throw new ConfigException("connection", "connection id does not match the file name");
            Connection next = current.withEnabled(enabled);
            String original = Files.readString(target, StandardCharsets.UTF_8);
            java.util.regex.Matcher line = java.util.regex.Pattern.compile(
                    "(?m)^(enabled:[ \\t]*)(true|false)([ \\t]*(?:#[^\\r\\n]*)?)$").matcher(original);
            String updated;
            if (line.find()) {
                updated = original.substring(0, line.start(2)) + enabled + original.substring(line.end(2));
            } else if (!Yaml.parseMapping(original).containsKey("enabled")) {
                updated = original + (original.endsWith("\n") ? "" : "\n") + "enabled: " + enabled + "\n";
            } else {
                throw new ConfigException("connection", "enabled has an unsupported YAML layout; no file was changed");
            }
            Connection.parse(updated, target.toString());
            writeAtomically(target, updated, true);
            return next;
        });
    }

    /** A referenced connection cannot be removed, including references in an unverified profile. */
    public void remove(String id) throws IOException {
        Path target = path(id);
        JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException("connection", "connection file does not exist or is not a regular file");
            }
            for (Path profile : yamlFiles(home.resolve("profiles"))) {
                Map<String, Object> parsed;
                try {
                    if (!Files.isRegularFile(profile, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("profile is not a regular file");
                    }
                    parsed = Yaml.parseMapping(Files.readString(profile));
                } catch (RuntimeException | IOException failure) {
                    throw new ConfigException("connection", "cannot check profile references; repair unreadable or invalid profile YAML before removing a connection");
                }
                if (id.equals(parsed.get("connection"))) {
                    throw new ConfigException("connection", "connection is referenced by a profile; update the profile before removing it");
                }
            }
            Files.delete(target);
            return null;
        });
    }

    private Path path(String id) {
        Connection.requireId(id);
        return directory.resolve(id + ".yaml");
    }

    private static Connection read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigException("connection", "connection file does not exist or is not a regular file");
        }
        return Connection.parse(Files.readString(file), file.toString());
    }

    private static List<Path> yamlFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        if (!Files.isDirectory(directory)) throw new IOException("configuration directory is not a directory");
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }

    private static void writeAtomically(Path target, String yaml, boolean replace) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".connection-", ".tmp");
        try {
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (replace) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
