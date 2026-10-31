package dev.warden.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The only facts Warden takes from Codex's own {@code config.toml}: which named profiles exist and
 * the {@code model_provider} each one sets explicitly. Read-only, one file, nothing else: no
 * {@code auth.json}, no token or credential file, no other key. Dotted keys, inline tables and
 * provider values inherited from the top level are deliberately not interpreted, so anything
 * unusual stays "needs mapping" instead of being guessed.
 */
public record CodexConfig(Path file, Set<String> profiles, Map<String, String> providers) {
    public static final CodexConfig NONE = new CodexConfig(null, Set.of(), Map.of());
    private static final long MAX_BYTES = 1 << 20;
    private static final Pattern PROFILE_HEADER = Pattern.compile(
            "^\\[\\s*profiles\\s*\\.\\s*(?:\"([^\"\\\\]+)\"|'([^']+)'|([A-Za-z0-9_-]+))\\s*\\]\\s*(?:#.*)?$");
    private static final Pattern PROVIDER = Pattern.compile(
            "^model_provider\\s*=\\s*(?:\"([^\"\\\\]*)\"|'([^']*)')\\s*(?:#.*)?$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public boolean present() { return file != null; }

    /** {@code $CODEX_HOME/config.toml}, else {@code ~/.codex/config.toml}; {@link #NONE} when absent or unreadable. */
    public static CodexConfig load() {
        String codexHome = System.getenv("CODEX_HOME");
        Path dir = codexHome == null || codexHome.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(codexHome);
        return load(dir.resolve("config.toml"));
    }

    public static CodexConfig load(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return NONE;
            return parse(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return NONE;
        }
    }

    static CodexConfig parse(Path file, String text) {
        var profiles = new HashSet<String>(); var providers = new TreeMap<String, String>(); var conflicted = new HashSet<String>();
        String section = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                var header = PROFILE_HEADER.matcher(line);
                section = null;
                if (header.matches()) {
                    String name = header.group(1) != null ? header.group(1) : header.group(2) != null ? header.group(2) : header.group(3);
                    if (NAME.matcher(name).matches()) { section = name; profiles.add(name); }
                }
                continue;
            }
            if (section == null) continue;
            var provider = PROVIDER.matcher(line);
            if (!provider.matches()) continue;
            String value = (provider.group(1) != null ? provider.group(1) : provider.group(2)).strip().toLowerCase(Locale.ROOT);
            String previous = providers.put(section, value);
            if (!NAME.matcher(value).matches() || (previous != null && !previous.equals(value))) conflicted.add(section);
        }
        for (String name : conflicted) providers.remove(name);
        return new CodexConfig(file, Set.copyOf(profiles), Map.copyOf(providers));
    }
}
