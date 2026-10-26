package dev.warden.config;

import dev.warden.json.JsonFile;
import dev.warden.yaml.Yaml;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Nonsecret connection files under the user's Warden home. */
public final class ConnectionStore {
    /** Exact file revision, separate from the semantic runtime fingerprint. */
    public record Versioned(Connection connection, String revision) {}

    @SuppressWarnings("serial")
    public static class Conflict extends IOException {
        public Conflict() { super("connection revision changed; reload before saving"); }
        protected Conflict(String message) { super(message); }
    }

    @SuppressWarnings("serial")
    public static final class AlreadyExists extends Conflict {
        public AlreadyExists() { super("connection already exists; add never overwrites a file"); }
    }

    @SuppressWarnings("serial")
    public static final class Referenced extends Conflict {
        public Referenced(String message) { super(message); }
    }

    @SuppressWarnings("serial")
    public static final class Missing extends IOException {
        public Missing() { super("connection file does not exist"); }
    }

    private record Loaded(Versioned value, String yaml) {}
    private record Line(String text, String ending) {}
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
                throw new AlreadyExists();
            }
            writeAtomically(target, connection.toYaml(), false);
            return target;
        });
    }

    public Connection setEnabled(String id, boolean enabled) throws IOException {
        return setEnabled(id, enabled, null, false).connection();
    }

    public Versioned versioned(String id) throws IOException {
        Loaded loaded = readLoaded(path(id));
        requireMatchingId(id, loaded.value().connection());
        return loaded.value();
    }

    public Versioned update(Connection next, String expectedRevision) throws IOException {
        requireRevision(expectedRevision);
        Path target = path(next.id());
        return JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            Loaded current = readLoaded(target);
            requireMatchingId(next.id(), current.value().connection());
            checkRevision(expectedRevision, current.value().revision());
            return replace(target, current, next);
        });
    }

    public Versioned setEnabled(String id, boolean enabled, String expectedRevision) throws IOException {
        requireRevision(expectedRevision);
        return setEnabled(id, enabled, expectedRevision, true);
    }

    private Versioned setEnabled(String id, boolean enabled, String expectedRevision, boolean compare)
            throws IOException {
        Path target = path(id);
        return JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            Loaded current = readLoaded(target);
            requireMatchingId(id, current.value().connection());
            if (compare) checkRevision(expectedRevision, current.value().revision());
            return replace(target, current, current.value().connection().withEnabled(enabled));
        });
    }

    /** A referenced connection cannot be removed, including references in an unverified profile. */
    public void remove(String id) throws IOException {
        remove(id, null, false);
    }

    public void remove(String id, String expectedRevision) throws IOException {
        requireRevision(expectedRevision);
        remove(id, expectedRevision, true);
    }

    private void remove(String id, String expectedRevision, boolean compare) throws IOException {
        Path target = path(id);
        JsonFile.underExclusiveLock(target, ".connections.lock", () -> {
            Loaded current = readLoaded(target);
            requireMatchingId(id, current.value().connection());
            if (compare) checkRevision(expectedRevision, current.value().revision());
            for (Path profile : yamlFiles(home.resolve("profiles"))) {
                Map<String, Object> parsed;
                try {
                    if (!Files.isRegularFile(profile, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("profile is not a regular file");
                    }
                    parsed = Yaml.parseMapping(Files.readString(profile));
                } catch (RuntimeException | IOException failure) {
                    throw new Referenced("cannot check profile references; repair unreadable or invalid profile YAML before removing a connection");
                }
                if (id.equals(parsed.get("connection"))) {
                    throw new Referenced("connection is referenced by a profile; update the profile before removing it");
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
        return readLoaded(file).value().connection();
    }

    private static Loaded readLoaded(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new Missing();
            throw new ConfigException("connection", "connection file does not exist or is not a regular file");
        }
        byte[] bytes = Files.readAllBytes(file);
        String yaml;
        try {
            yaml = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new ConfigException(file.toString(), "connection file is not UTF-8; no file was changed");
        }
        return new Loaded(new Versioned(Connection.parse(yaml, file.toString()), digest(bytes)), yaml);
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void requireMatchingId(String id, Connection connection) {
        if (!connection.id().equals(id)) throw new ConfigException("connection", "connection id does not match the file name");
    }

    private static void requireRevision(String revision) {
        if (revision == null || !revision.matches("[a-f0-9]{64}")) {
            throw new ConfigException("connection", "revision must be a lowercase SHA-256 file revision");
        }
    }

    private static void checkRevision(String expected, String actual) throws Conflict {
        if (!expected.equals(actual)) throw new Conflict();
    }

    private static Versioned replace(Path target, Loaded current, Connection next) throws IOException {
        if (current.value().connection().equals(next)) return current.value();
        String updated = edit(current.yaml(), current.value().connection(), next);
        Connection parsed = Connection.parse(updated, target.toString());
        if (!parsed.equals(next)) throw layout();
        writeAtomically(target, updated, true);
        return new Versioned(parsed, digest(updated.getBytes(StandardCharsets.UTF_8)));
    }

    /** Only edit the scalar paths whose meaning changed; unfamiliar YAML is left intact. */
    private static String edit(String yaml, Connection before, Connection after) {
        List<Line> lines = lines(yaml);
        if (!before.provider().equals(after.provider())) scalar(lines, "provider", after.provider(), false);
        if (!before.protocol().equals(after.protocol())) scalar(lines, "protocol", after.protocol(), false);
        if (!before.baseUrl().equals(after.baseUrl())) scalar(lines, "base_url", quote(after.baseUrl()), false);
        if (!java.util.Objects.equals(before.credentialRef(), after.credentialRef())) auth(lines, after.credentialRef());
        if (before.enabled() != after.enabled()) scalar(lines, "enabled", Boolean.toString(after.enabled()), true);
        StringBuilder result = new StringBuilder();
        for (Line line : lines) result.append(line.text()).append(line.ending());
        return result.toString();
    }

    private static List<Line> lines(String text) {
        List<Line> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && c != '\n') continue;
            int end = i;
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            result.add(new Line(text.substring(start, end), text.substring(end, i + 1)));
            start = i + 1;
        }
        if (start < text.length()) result.add(new Line(text.substring(start), ""));
        return result;
    }

    private static void scalar(List<Line> lines, String key, String value, boolean mayAppend) {
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).text();
            if (!text.startsWith(key + ":")) continue;
            replaceScalar(lines, i, key.length() + 1, value);
            return;
        }
        if (!mayAppend) throw layout();
        // Do not append a duplicate when the original used quoted or otherwise unfamiliar keys.
        StringBuilder source = new StringBuilder();
        lines.forEach(line -> source.append(line.text()).append(line.ending()));
        if (Yaml.parseMapping(source.toString()).containsKey(key)) throw layout();
        append(lines, lines.size(), key + ": " + value);
    }

    private static void auth(List<Line> lines, String reference) {
        int header = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).text().startsWith("auth:")) { header = i; break; }
        if (header < 0 || !lines.get(header).text().substring(5).stripLeading().matches("(?:#.*)?")) throw layout();
        int end = header + 1;
        while (end < lines.size()) {
            String line = lines.get(end).text();
            if (!line.isBlank() && !line.stripLeading().startsWith("#") && !line.startsWith(" ")) break;
            end++;
        }
        int type = -1;
        int ref = -1;
        String indent = null;
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i).text();
            int width = line.length() - line.stripLeading().length();
            if (width < 1 || !line.substring(0, width).matches(" +")) continue;
            String key = line.substring(width);
            if (key.startsWith("type:")) {
                type = i;
                indent = line.substring(0, width);
            } else if (key.startsWith("credential_ref:")) ref = i;
        }
        if (type < 0) throw layout();
        if (ref >= 0 && !lines.get(ref).text().startsWith(indent + "credential_ref:")) throw layout();
        replaceScalar(lines, type, indent.length() + 5, reference == null ? "none" : "api_key");
        if (reference != null) {
            if (ref >= 0) replaceScalar(lines, ref, indent.length() + 15, reference);
            else append(lines, type + 1, indent + "credential_ref: " + reference);
        } else if (ref >= 0) {
            String line = lines.get(ref).text();
            int comment = commentAt(line, indent.length() + 15);
            if (comment >= 0) lines.set(ref, new Line(indent + line.substring(comment), lines.get(ref).ending()));
            else lines.remove(ref);
        }
    }

    private static void replaceScalar(List<Line> lines, int index, int colonEnd, String value) {
        Line line = lines.get(index);
        String text = line.text();
        int start = colonEnd;
        while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) start++;
        int comment = commentAt(text, start);
        int end = comment < 0 ? text.length() : comment;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start == end) throw layout();
        lines.set(index, new Line(text.substring(0, start) + value + text.substring(end), line.ending()));
    }

    private static int commentAt(String text, int start) {
        boolean single = false;
        boolean doubled = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (doubled && c == '\\') { escaped = true; continue; }
            if (!doubled && c == '\'') {
                if (single && i + 1 < text.length() && text.charAt(i + 1) == '\'') { i++; continue; }
                single = !single;
            } else if (!single && c == '"') doubled = !doubled;
            else if (!single && !doubled && c == '#' && (i == start || Character.isWhitespace(text.charAt(i - 1)))) return i;
        }
        return -1;
    }

    private static void append(List<Line> lines, int index, String text) {
        String newline = lines.stream().map(Line::ending).filter(value -> !value.isEmpty()).findFirst().orElse("\n");
        if (index > 0 && lines.get(index - 1).ending().isEmpty()) {
            Line previous = lines.get(index - 1);
            lines.set(index - 1, new Line(previous.text(), newline));
        }
        lines.add(index, new Line(text, newline));
    }

    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static ConfigException layout() {
        return new ConfigException("connection", "unsupported YAML layout; edit the file directly; no file was changed");
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
