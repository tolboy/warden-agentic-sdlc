package dev.warden.dashboard;

import dev.warden.config.ConfigFileStore;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Profile;
import dev.warden.config.ProjectConfig;
import dev.warden.config.Profile;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.Workflow;
import dev.warden.json.Json;
import dev.warden.role.RoleResolver;
import dev.warden.run.TaskLoop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only invocation builder. It never creates runs, edits settings or calls a vendor. */
public final class NextRun {
    public static final class Conflict extends IOException {
        Conflict() { super("Settings changed; reload the task before previewing again"); }
    }

    record State(ConfigLoader.Loaded loaded, UserConfig user, String revision,
                 Map<Path, ConfigFileStore.Snapshot> sources) {}
    final Path project;
    final Path home;
    final RoleResolver.Availability availability;

    public NextRun(Path project, Path home, RoleResolver.Availability availability) {
        this.project = project.toAbsolutePath().normalize();
        this.home = home.toAbsolutePath().normalize();
        this.availability = availability;
    }

    public Map<String, Object> describe(String taskId) throws IOException {
        State state = read(taskId);
        var task = state.loaded().resolved();
        var user = state.user();
        Workflow workflow = user.policy() == null ? Workflow.builtIn() : user.policy().workflow();
        if (task.visualQa().agentEvidence()) workflow = workflow.forAgentEvidence();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", taskId);
        result.put("revision", state.revision());
        result.put("goal", task.goal());
        result.put("risk", task.risk());
        result.put("scope", task.scopePaths());
        result.put("acceptance", task.acceptanceCommands());
        result.put("visual_qa", task.visualQa().required());
        result.put("review_assurance", task.reviewAssurance());
        result.put("max_role_runs", task.budget().maxRoleRuns());
        result.put("max_cost_usd", task.budget().maxCostUsd());
        result.put("cost_cap", task.budget().costCap());
        result.put("max_elapsed_minutes", task.budget().maxElapsedMinutes());
        result.put("max_fix_attempts", task.maxFixAttempts());
        result.put("timeout_minutes", task.timeoutMinutes());
        result.put("gate_ttl_hours", task.budget().gateTtlHours());
        result.put("task_use", state.loaded().task().use().toMap());
        result.put("problems", user.problems());
        List<Map<String, Object>> stages = new ArrayList<>();
        for (var stage : workflow.stages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            row.put("kind", stage.kind().name());
            row.put("when", stage.when());
            var spec = user.policy() == null || stage.role() == null ? null : user.policy().roles().get(stage.role());
            row.put("fallback", spec == null ? List.of() : spec.profiles());
            row.put("strategy", spec == null ? null : spec.strategy());
            List<Map<String, Object>> profiles = new ArrayList<>();
            if (stage.kind() == Workflow.Kind.ROLE) for (Profile profile : user.profiles().values()) {
                if (!profile.role().equals(stage.role())) continue;
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("profile", profile.name());
                choice.put("vendor", profile.vendor());
                choice.put("model", profile.model());
                choice.put("effort", profile.effort());
                choice.put("runner", profile.runner());
                choice.put("verified", profile.verified());
                choice.put("read_only", profile.readOnly());
                choice.put("max_cost_usd", profile.maxCostUsd());
                profiles.add(choice);
            }
            row.put("profiles", profiles);
            stages.add(row);
        }
        result.put("stages", stages);
        return result;
    }

    public Map<String, Object> preview(Map<String, Object> request) throws IOException {
        if (!Set.of("task", "revision", "override").containsAll(request.keySet()))
            throw new IllegalArgumentException("Unknown preview field");
        if (!(request.get("task") instanceof String taskId)
                || !(request.get("revision") instanceof String expected) || expected.isBlank())
            throw new IllegalArgumentException("task and revision are required");
        State state = read(taskId);
        if (!state.revision().equals(expected)) throw new Conflict();
        Object raw = request.get("override");
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("override must be a mapping");
        @SuppressWarnings("unchecked")
        RunOverride overlay = RunOverride.fromMap((Map<String, Object>) raw);
        Map<Profile, Boolean> found = new LinkedHashMap<>();
        RoleResolver.Availability cached = profile -> found.computeIfAbsent(
                profile, ignored -> availability.available(profile));
        Map<String, Object> result = new LinkedHashMap<>(TaskLoop.settingsPreview(
                project, state.loaded(), state.user(), overlay, cached));
        // Catch edits and rotation changes made while availability was being checked.
        if (!read(taskId).revision().equals(expected)) throw new Conflict();
        result.put("task", taskId);
        result.put("revision", expected);
        result.put("override", overlay.toMap());
        List<String> argv = new ArrayList<>(List.of("warden", "run", taskId));
        for (var kind : overlay.toMap().entrySet()) {
            String flag = switch (kind.getKey()) {
                case "profile" -> "--use";
                case "effort" -> "--effort";
                case "host" -> "--host";
                default -> throw new IllegalArgumentException("Unknown override");
            };
            for (var entry : ((Map<?, ?>) kind.getValue()).entrySet()) {
                argv.add(flag);
                argv.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        List<String> dry = new ArrayList<>(argv);
        dry.add("--dry-run");
        result.put("working_directory", project.toString());
        result.put("config_home", home.toString());
        result.put("dry_run_argv", dry);
        result.put("dry_run_command", command(dry));
        boolean ok = Boolean.TRUE.equals(result.get("settings_ok"));
        result.put("run_argv", ok ? argv : null);
        result.put("run_command", ok ? command(argv) : null);
        result.put("task_overlay", ok ? yaml(state.loaded().task().use().merged(overlay)) : null);
        return result;
    }

    State read(String taskId) throws IOException {
        if (taskId == null || !taskId.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,79}"))
            throw new IllegalArgumentException("Choose a task ID from this project");
        Map<String, String> before = versions(taskId);
        Map<Path, ConfigFileStore.Snapshot> sources = new LinkedHashMap<>();
        UserConfig user = UserConfig.load(home, sources);
        Path projectFile = project.resolve(".warden/project.yaml");
        Path taskFile = project.resolve(".warden/tasks").resolve(taskId + ".yaml");
        var projectSource = ConfigFileStore.read(projectFile);
        var taskSource = ConfigFileStore.read(taskFile);
        sources.put(projectFile, projectSource);
        sources.put(taskFile, taskSource);
        ProjectConfig config = ProjectConfig.parse(projectSource.text(), projectFile.toString());
        TaskSpec task = TaskSpec.parse(taskSource.text(), taskFile.toString());
        for (var source : sources.entrySet()) {
            var current = ConfigFileStore.read(source.getKey());
            if (!current.equals(source.getValue())) throw new Conflict();
        }
        Map<String, String> after = versions(taskId);
        if (!before.equals(after)) throw new Conflict();
        var loaded = new ConfigLoader.Loaded(project, projectFile, taskFile, config, task,
                task.resolve(config, taskFile.toString()));
        return new State(loaded, user, digest(Json.write(after).getBytes(StandardCharsets.UTF_8)), sources);
    }

    private Map<String, String> versions(String taskId) throws IOException {
        Map<String, String> versions = new TreeMap<>();
        versions.put("project_root", project.toRealPath().toString());
        versions.put("config_home", home.toRealPath().toString());
        version(versions, "policy", home.resolve("policy.yaml"), false);
        for (String directory : List.of("profiles", "connections")) {
            Path entries = home.resolve(directory);
            if (Files.isDirectory(entries)) try (var listing = Files.list(entries)) {
                for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted().toList()) version(versions, directory + "/" + file.getFileName(), file, false);
            }
        }
        Path realProject = project.toRealPath();
        for (String file : List.of(".warden/project.yaml", ".warden/tasks/" + taskId + ".yaml",
                ".warden/runs/rotation.json")) {
            Path path = project.resolve(file);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !path.toRealPath().startsWith(realProject))
                throw new IllegalArgumentException("Project settings must stay inside this project");
            version(versions, file, path, !file.endsWith("rotation.json"));
        }
        return versions;
    }

    private static void version(Map<String, String> versions, String key, Path file, boolean required)
            throws IOException {
        if (!required && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            versions.put(key, "absent");
            return;
        }
        if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024)
            throw new IllegalArgumentException("Settings file is missing or larger than 1 MiB: " + key);
        versions.put(key, file.toRealPath() + ":" + digest(Files.readAllBytes(file)));
    }

    private String command(List<String> argv) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String words = argv.stream().map(s -> quote(s, windows)).collect(java.util.stream.Collectors.joining(" "));
        if (windows) return "& { Push-Location -LiteralPath " + quote(project.toString(), true)
                + " -ErrorAction Stop; $wardenConfigBefore = $env:WARDEN_CONFIG_HOME; try { "
                + "$env:WARDEN_CONFIG_HOME = " + quote(home.toString(), true) + "; & " + words
                + " } finally { $env:WARDEN_CONFIG_HOME = $wardenConfigBefore; Pop-Location } }";
        return "(cd -- " + quote(project.toString(), false) + " && WARDEN_CONFIG_HOME="
                + quote(home.toString(), false) + " " + words + ")";
    }

    private static String quote(String text, boolean windows) {
        return "'" + text.replace("'", windows ? "''" : "'\"'\"'") + "'";
    }

    private static String yaml(RunOverride overlay) {
        if (overlay.isEmpty()) return "# No task use: overlay\n";
        StringBuilder yaml = new StringBuilder("use:\n");
        Map<String, Object> map = overlay.toMap();
        for (String stage : overlay.stages()) {
            yaml.append("  ").append(Json.write(stage)).append(":\n");
            for (var kind : map.entrySet()) {
                Object value = ((Map<?, ?>) kind.getValue()).get(stage);
                if (value != null) yaml.append("    ").append(kind.getKey()).append(": ")
                        .append(Json.write(value)).append('\n');
            }
        }
        return yaml.toString();
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
