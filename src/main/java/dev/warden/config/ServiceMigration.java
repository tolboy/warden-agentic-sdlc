package dev.warden.config;

import dev.warden.json.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Writes explicit {@code service:}/{@code account:} bindings into legacy CLI profiles.
 *
 * Preview first; {@code apply} binds only profiles that are safely inferred or explicitly mapped
 * by the operator, backs up every file it touches, and records the hash it wrote so
 * {@code rollback} can refuse to overwrite later edits. Re-running is a no-op. Vendor card caches
 * that no longer belong to any card (for example a {@code cli-provider-deepseek} catalog left by
 * the developer-named grouping) are moved into the same backup, never deleted.
 */
public final class ServiceMigration {
    public record Mapping(String profile, String service, String account) {
        public static Mapping parse(String value) {
            int equals = value == null ? -1 : value.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("--map expects NAME=SERVICE[:ACCOUNT]");
            String target = value.substring(equals + 1); int colon = target.indexOf(':');
            return new Mapping(value.substring(0, equals), colon < 0 ? target : target.substring(0, colon),
                    colon < 0 ? "default" : target.substring(colon + 1));
        }
    }

    private static final String PREFIX = "service-bindings-";
    private final Path home;

    public ServiceMigration(Path home) { this.home = home.toAbsolutePath().normalize(); }

    public Map<String, Object> preview(List<Mapping> mappings) throws Exception { return plan(mappings).report(false); }

    public Map<String, Object> apply(List<Mapping> mappings) throws Exception {
        try (var lock = lock()) {
            Plan plan = plan(mappings);
            if (plan.writes.isEmpty() && plan.stale.isEmpty()) {
                var report = plan.report(true); report.put("code", "already_migrated"); report.put("backup", null); return report;
            }
            String id = PREFIX + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
            Path backup = home.resolve("backups").resolve(id);
            for (int n = 2; Files.exists(backup); n++) backup = home.resolve("backups").resolve(id + "-" + n);
            Files.createDirectories(backup.resolve("files"));
            var files = new ArrayList<Map<String, Object>>(); var moved = new ArrayList<Map<String, Object>>();
            for (var write : plan.writes.entrySet()) {
                Path file = home.resolve(write.getKey());
                Path copy = backup.resolve("files").resolve(write.getKey());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                files.add(Map.of("path", write.getKey(), "before_sha256", sha(Files.readAllBytes(file)), "after_sha256", sha(write.getValue().getBytes(StandardCharsets.UTF_8))));
            }
            for (String stale : plan.stale) {
                Path copy = backup.resolve("files").resolve(stale);
                Files.createDirectories(copy.getParent());
                moved.add(Map.of("path", stale, "sha256", sha(Files.readAllBytes(home.resolve(stale)))));
                Files.copy(home.resolve(stale), copy, StandardCopyOption.COPY_ATTRIBUTES);
            }
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("id", backup.getFileName().toString()); manifest.put("created_at", Instant.now().toString());
            manifest.put("kind", "service_bindings"); manifest.put("files", files); manifest.put("moved_caches", moved);
            // The manifest exists before the first write, so an interrupted apply can still roll back.
            atomicWrite(backup.resolve("manifest.json"), Json.write(manifest));
            for (var write : plan.writes.entrySet()) atomicWrite(home.resolve(write.getKey()), write.getValue());
            for (String stale : plan.stale) Files.delete(home.resolve(stale));
            var report = plan.report(true); report.put("code", "service_bindings_written"); report.put("backup", manifest.get("id"));
            report.put("rollback", "warden profiles migrate-services --rollback " + manifest.get("id"));
            return report;
        }
    }

    public Map<String, Object> rollback(String id) throws Exception {
        if (id == null || !id.matches(PREFIX + "[0-9TZ-]{1,40}")) throw new IllegalArgumentException("unknown migration backup");
        try (var lock = lock()) {
            Path backup = home.resolve("backups").resolve(id);
            Path manifestFile = backup.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile)) throw new IllegalArgumentException("unknown migration backup");
            var manifest = Json.parseObject(Files.readString(manifestFile));
            if (manifest.get("rolled_back_at") != null) return Map.of("ok", false, "code", "already_rolled_back", "backup", id);
            var conflicts = new ArrayList<String>();
            for (var entry : maps(manifest.get("files"))) {
                Path file = home.resolve(safe(entry.get("path")));
                if (!Files.isRegularFile(file) || !sha(Files.readAllBytes(file)).equals(entry.get("after_sha256"))) conflicts.add(String.valueOf(entry.get("path")));
            }
            for (var entry : maps(manifest.get("moved_caches"))) {
                Path file = home.resolve(safe(entry.get("path")));
                if (Files.exists(file) && !sha(Files.readAllBytes(file)).equals(entry.get("sha256"))) conflicts.add(String.valueOf(entry.get("path")));
            }
            if (!conflicts.isEmpty()) {
                return Map.of("ok", false, "code", "changed_since_migration", "files", conflicts,
                        "next", "These files were edited after the migration; restore them by hand from " + backup);
            }
            var restored = new ArrayList<String>();
            for (var entry : maps(manifest.get("files"))) restore(backup, safe(entry.get("path")), restored);
            for (var entry : maps(manifest.get("moved_caches"))) restore(backup, safe(entry.get("path")), restored);
            manifest.put("rolled_back_at", Instant.now().toString());
            atomicWrite(manifestFile, Json.write(manifest));
            return Map.of("ok", true, "code", "service_bindings_rolled_back", "backup", id, "restored", restored);
        }
    }

    private void restore(Path backup, String relative, List<String> restored) throws IOException {
        Path target = home.resolve(relative);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".restore-", ".tmp");
        try {
            Files.copy(backup.resolve("files").resolve(relative), temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        restored.add(relative);
    }

    private Plan plan(List<Mapping> mappings) throws Exception {
        UserConfig user = UserConfig.load(home);
        var byName = new LinkedHashMap<String, Mapping>();
        for (Mapping mapping : mappings) {
            if (!user.profiles().containsKey(mapping.profile())) throw new IllegalArgumentException("--map names unknown profile " + mapping.profile());
            if (byName.put(mapping.profile(), mapping) != null) throw new IllegalArgumentException("--map repeats profile " + mapping.profile());
        }
        Plan plan = new Plan(); plan.problems.putAll(user.problems());
        var cards = new TreeSet<String>();
        for (Profile profile : user.profiles().values()) {
            var binding = ServiceBinding.of(profile);
            Mapping mapping = byName.get(profile.name());
            var row = new LinkedHashMap<String, Object>();
            row.put("profile", profile.name()); row.put("binding", binding.source());
            row.put("service", binding.service()); row.put("account", binding.account());
            String action; Profile after = profile;
            if (ServiceBinding.CONNECTION.equals(binding.source()) || ServiceBinding.ENDPOINT.equals(binding.source())) {
                if (mapping != null) throw new IllegalArgumentException(profile.name() + " uses a connection; bind the connection instead of the profile");
                action = "unchanged";
            } else if (ServiceBinding.EXPLICIT.equals(binding.source())) {
                if (mapping != null && !(mapping.service().equals(binding.service()) && mapping.account().equals(binding.account())))
                    throw new IllegalArgumentException(profile.name() + " is already bound to " + binding.service() + ":" + binding.account() + "; edit the file to change it");
                action = "already_bound";
            } else if (mapping != null || ServiceBinding.INFERRED.equals(binding.source())) {
                String service = mapping == null ? binding.service() : mapping.service();
                String account = mapping == null ? binding.account() : mapping.account();
                String relative = "profiles/" + profile.name() + ".yaml";
                String text = Files.readString(home.resolve(relative));
                String updated = insert(text, service, account);
                try { after = Profile.parse(updated, relative); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException(profile.name() + ": " + invalid.getMessage()); }
                plan.writes.put(relative, updated);
                action = mapping == null ? "bind_inferred" : "bind_mapped";
                row.put("service", service); row.put("account", account);
            } else {
                action = "needs_mapping"; row.put("reason", binding.reason()); row.put("suggested", binding.suggested());
                row.put("map_with", "--map " + profile.name() + "=" + (binding.suggested() == null ? "SERVICE" : binding.suggested()) + "[:ACCOUNT]");
            }
            row.put("action", action); plan.rows.add(row);
            cards.add(displayId(after));
        }
        Path vendors = home.resolve("vendors");
        if (Files.isDirectory(vendors)) try (var files = Files.list(vendors)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                var match = java.util.regex.Pattern.compile("(cli-[a-z0-9-]+)-(catalog|check)\\.json").matcher(name);
                if (match.matches() && !cards.contains(match.group(1))) plan.stale.add("vendors/" + name);
            }
        }
        return plan;
    }

    private static String displayId(Profile profile) {
        if (profile.connectionRef() != null) return "api-" + profile.connectionRef();
        var binding = ServiceBinding.of(profile);
        return binding.needsMapping() ? "cli-unmapped" : "cli-provider-" + binding.service();
    }

    /** Adds the binding after the top-level {@code vendor:} line, keeping the file's line endings. */
    static String insert(String text, String service, String account) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String lines = "service: " + service + eol + "account: " + account + eol;
        var vendor = java.util.regex.Pattern.compile("(?m)^vendor:[^\\r\\n]*\\r?\\n").matcher(text);
        if (vendor.find()) return text.substring(0, vendor.end()) + lines + text.substring(vendor.end());
        var profile = java.util.regex.Pattern.compile("(?m)^profile:[^\\r\\n]*\\r?\\n").matcher(text);
        if (profile.find()) return text.substring(0, profile.end()) + lines + text.substring(profile.end());
        throw new IllegalArgumentException("profile file has no top-level profile: line");
    }

    private final class Plan {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<String, String> writes = new TreeMap<>();
        final List<String> stale = new ArrayList<>();
        final Map<String, String> problems = new LinkedHashMap<>();

        Map<String, Object> report(boolean applied) {
            var report = new LinkedHashMap<String, Object>();
            report.put("ok", true); report.put("applied", applied); report.put("home", home.toString());
            report.put("code", applied ? "service_bindings_written" : "preview");
            report.put("profiles", rows);
            report.put("writes", new ArrayList<>(writes.keySet()));
            report.put("stale_vendor_caches", stale);
            report.put("needs_mapping", rows.stream().filter(r -> "needs_mapping".equals(r.get("action"))).map(r -> r.get("profile")).toList());
            if (!problems.isEmpty()) report.put("problems", problems);
            report.put("note", "Adding service/account changes no command, model, permission or tool, so verification receipts stay. "
                    + "Model check receipts are tied to the file and must be re-run. Older Warden builds reject the new keys; use --rollback to return.");
            return report;
        }
    }

    private FileChannel lock() throws IOException {
        Files.createDirectories(home);
        FileChannel channel = FileChannel.open(home.resolve(".model-settings.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (channel.tryLock() == null) { channel.close(); throw new IllegalStateException("settings_busy: another Warden is saving settings"); }
        return channel;
    }

    private static String safe(Object value) {
        String path = String.valueOf(value);
        if (!path.matches("(profiles/[A-Za-z0-9._-]+\\.yaml|vendors/cli-[a-z0-9-]+-(catalog|check)\\.json)")) throw new IllegalArgumentException("manifest path refused");
        return path;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".migrate-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
