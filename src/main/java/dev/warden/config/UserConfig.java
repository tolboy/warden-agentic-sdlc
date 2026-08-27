package dev.warden.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * `~/.warden` — configuration owned by whoever is running, not by the repository.
 *
 * Which model reviews your code is a property of your subscriptions. Two engineers on the
 * same project may hold entirely different ones, so this never lives in the project's git
 * history. It also means a project config stays portable: describe your checks, and the tool
 * works for anyone.
 *
 * A malformed profile is recorded and skipped rather than aborting the load. Refusing to
 * start because one of five profiles has a typo would hide the other four from `doctor`,
 * which is exactly the moment the operator needs to see everything at once. The profile
 * itself is still unusable — the resolver reports it as missing.
 */
public record UserConfig(
        Path home,
        Policy policy,
        Map<String, Profile> profiles,
        Map<String, String> problems,
        boolean policyPresent) {

    /**
     * Deliberately NOT `WARDEN_HOME`: the launcher scripts already use that name for the
     * installation directory, and the collision silently pointed `warden setup` at the
     * repository instead of the user's home. A real run caught it because setup prints where
     * it wrote; the names are kept apart so it cannot recur.
     */
    public static final String HOME_ENVIRONMENT_VARIABLE = "WARDEN_CONFIG_HOME";

    public static Path defaultHome() {
        String override = System.getenv(HOME_ENVIRONMENT_VARIABLE);
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), ".warden").toAbsolutePath().normalize();
    }

    public static UserConfig load() throws IOException { return load(defaultHome()); }

    public static UserConfig load(Path home) throws IOException {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();

        Path profileDirectory = home.resolve("profiles");
        if (Files.isDirectory(profileDirectory)) {
            List<Path> files;
            try (Stream<Path> entries = Files.list(profileDirectory)) {
                files = entries.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList();
            }
            for (Path file : files) {
                String key = stripExtension(file.getFileName().toString());
                try {
                    Profile profile = Profile.parse(Files.readString(file), file.toString());
                    if (!profile.name().equals(key)) {
                        problems.put(key, "profile name '" + profile.name()
                                + "' does not match the file name; rename one so they agree");
                        continue;
                    }
                    profiles.put(key, profile);
                } catch (RuntimeException failure) {
                    problems.put(key, String.valueOf(failure.getMessage()));
                }
            }
        }

        Path policyFile = home.resolve("policy.yaml");
        boolean policyPresent = Files.isRegularFile(policyFile);
        Policy policy = null;
        if (policyPresent) {
            try {
                policy = Policy.parse(Files.readString(policyFile), policyFile.toString());
            } catch (RuntimeException failure) {
                problems.put("policy.yaml", String.valueOf(failure.getMessage()));
            }
        }
        return new UserConfig(home, policy, profiles, problems, policyPresent);
    }

    /** Names referenced by the policy that no loadable profile provides. */
    public List<String> danglingProfileReferences() {
        List<String> dangling = new ArrayList<>();
        if (policy == null) return dangling;
        for (Policy.RoleSpec spec : policy.roles().values()) {
            for (String name : spec.profiles()) {
                if (!profiles.containsKey(name)) dangling.add(spec.role() + " -> " + name);
            }
        }
        return dangling;
    }

    /** Resolve a path written in a profile: absolute, or relative to the warden home. */
    public Path resolve(String reference) {
        Path candidate = Path.of(reference);
        return candidate.isAbsolute() ? candidate.normalize() : home.resolve(candidate).normalize();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
