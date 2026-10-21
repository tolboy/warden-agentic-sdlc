package dev.warden.dashboard;

import dev.warden.config.ConfigFileStore;
import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.json.Json;
import dev.warden.run.TaskLoop;
import dev.warden.yaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, reviewed, single-file edits. Never dispatches or changes profile verification. */
public final class SettingsEditor {
    private final NextRun next;
    public SettingsEditor(NextRun next) { this.next = next; }

    public Map<String, Object> describe(String task) throws IOException {
        var state = next.read(task);
        Policy policy = state.user().policy();
        if (policy == null) throw new IllegalArgumentException("A readable personal policy.yaml is required");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", state.revision());
        List<Object> roles = new ArrayList<>();
        for (var role : policy.roles().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role.role()); row.put("profiles", role.profiles()); row.put("strategy", role.strategy());
            row.put("independent", role.requireIndependentVendor());
            row.put("on_vendor_failure", role.vendorFailureMode());
            row.put("choices", state.user().profiles().values().stream().filter(p -> p.role().equals(role.role()))
                    .map(p -> Map.of("profile", p.name(), "label", p.name() + " · " + p.vendor() + " · "
                            + (p.model() == null ? "agent default" : p.model()) + " · "
                            + (p.effort() == null ? "effort unspecified" : p.effort()) + " · " + p.runner()
                            + (p.verified() ? "" : " · unverified"))).toList());
            roles.add(row);
        }
        result.put("roles", roles);
        result.put("on_quota_exhausted", policy.failoverMode());
        result.put("on_vendor_failure", policy.vendorFailureMode());
        result.put("max_per_stage", policy.maxVendorFailovers());
        result.put("max_attempts", policy.rateLimitRetry().maxAttempts());
        result.put("backoff_seconds", policy.rateLimitRetry().backoffSeconds());
        result.put("repair_reserve", policy.repairReserve());
        return result;
    }

    private record Edit(String task, String revision, String target, ConfigFileStore.Snapshot source,
                        String candidate, Map<String, Object> result) {}

    public Map<String, Object> preview(Map<String, Object> request) throws IOException {
        return prepare(request, false).result();
    }

    public Map<String, Object> apply(Map<String, Object> request) throws IOException {
        Edit edit = prepare(request, true);
        if (!edit.result().get("preview_sha256").equals(request.get("preview_sha256")))
            throw new NextRun.Conflict();
        if (!Boolean.TRUE.equals(edit.result().get("apply_allowed")))
            throw new IllegalArgumentException("Resolve preview refusals before Apply");
        // Recheck all resolver inputs inside the target file's write lock, before backup/rename.
        var saved = new ConfigFileStore().save(edit.source(), edit.candidate(), candidate -> {
            if (edit.target().equals("task")) TaskSpec.parse(candidate, edit.source().file().toString());
            else Policy.parse(candidate, edit.source().file().toString());
        }, "dashboard", () -> {
            if (!next.read(edit.task()).revision().equals(edit.revision())) throw new NextRun.Conflict();
        });
        return Map.of("ok", true, "file", edit.source().file().toString(), "backup", saved.backup().toString(),
                "before_sha256", saved.beforeSha256(), "sha256", saved.sha256());
    }

    @SuppressWarnings("unchecked")
    private Edit prepare(Map<String, Object> request, boolean applying) throws IOException {
        Set<String> keys = applying ? Set.of("task", "target", "revision", "changes", "preview_sha256")
                : Set.of("task", "target", "revision", "changes");
        requireKeys(request, keys);
        if (!(request.get("task") instanceof String task) || !(request.get("revision") instanceof String revision)
                || !(request.get("target") instanceof String target) || !Set.of("task", "personal").contains(target))
            throw new IllegalArgumentException("task, revision and target (task/personal) are required");
        var state = next.read(task);
        if (!state.revision().equals(revision)) throw new NextRun.Conflict();
        var loaded = state.loaded();
        var user = state.user();
        var path = target.equals("task") ? loaded.taskFile() : next.home.resolve("policy.yaml");
        var source = state.sources().get(path);
        if (source == null) throw new IllegalArgumentException("Settings file is not readable");
        if (!source.file().startsWith((target.equals("task") ? next.project : next.home).toRealPath()))
            throw new IllegalArgumentException("Settings file must stay inside its settings directory");
        Map<String, Object> root = Yaml.parseMapping(source.text());
        Map<String, Object> changed = map(request.get("changes"));
        Map<String, Object> replacements = new LinkedHashMap<>();
        if (target.equals("task")) {
            requireKeys(changed, Set.of("use", "limits"));
            if (changed.containsKey("use")) {
                RunOverride overlay = RunOverride.fromMap(map(changed.get("use")));
                Map<String, Object> use = new LinkedHashMap<>();
                for (String stage : overlay.stages().stream().sorted().toList()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (var kind : overlay.toMap().entrySet()) {
                        Object value = ((Map<?, ?>) kind.getValue()).get(stage);
                        if (value != null) row.put(kind.getKey(), value);
                    }
                    use.put(stage, row);
                }
                replacements.put("use", use.isEmpty() ? null : use);
            }
            if (changed.containsKey("limits")) {
                Map<String, Object> limits = map(changed.get("limits"));
                requireKeys(limits, Set.of("max_role_runs", "max_cost_usd", "cost_cap", "max_elapsed_minutes",
                        "gate_ttl_hours", "max_fix_attempts", "timeout_minutes"));
                Map<String, Object> budgets = new LinkedHashMap<>(mapOrEmpty(root.get("budgets")));
                for (var entry : limits.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    if (!key.equals("cost_cap") && value != null && !(value instanceof Number))
                        throw new IllegalArgumentException("A numeric limit is required: " + key);
                    if (value instanceof Number n && (!Double.isFinite(n.doubleValue())
                            || (!key.equals("max_cost_usd") && n.doubleValue() != n.longValue())))
                        throw new IllegalArgumentException("Invalid numeric limit: " + key);
                    if (Set.of("max_fix_attempts", "timeout_minutes").contains(key)) replacements.put(key, value);
                    else if (value == null) budgets.remove(key); else budgets.put(key, value);
                }
                replacements.put("budgets", budgets.isEmpty() ? null : budgets);
            }
        } else {
            requireKeys(changed, Set.of("roles", "on_quota_exhausted", "on_vendor_failure", "max_per_stage",
                    "max_attempts", "backoff_seconds", "repair_reserve"));
            if (changed.containsKey("max_per_stage") && (!(changed.get("max_per_stage") instanceof Number n)
                    || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()))
                throw new IllegalArgumentException("Integer max_per_stage required");
            if (user.policy() == null) throw new IllegalArgumentException("Personal policy is unreadable");
            if (changed.containsKey("roles")) {
                Map<String, Object> roles = new LinkedHashMap<>(map(root.get("roles")));
                for (var entry : map(changed.get("roles")).entrySet()) {
                    String role = entry.getKey();
                    if (!roles.containsKey(role)) throw new IllegalArgumentException("Unknown role: " + role);
                    Map<String, Object> edit = map(entry.getValue());
                    requireKeys(edit, Set.of("profiles", "strategy", "on_vendor_failure"));
                    Map<String, Object> spec = new LinkedHashMap<>(map(roles.get(role)));
                    if (edit.containsKey("profiles")) {
                        if (!(edit.get("profiles") instanceof List<?> names) || names.isEmpty()
                                || names.stream().distinct().count() != names.size())
                            throw new IllegalArgumentException("Provide a nonempty ordered list of unique profiles");
                        for (Object name : names) {
                            var profile = user.profiles().get(name);
                            if (profile == null || !role.equals(profile.role()))
                                throw new IllegalArgumentException("Profile does not fill " + role + ": " + name);
                        }
                    }
                    edit.forEach((key, value) -> { if (value == null) spec.remove(key); else spec.put(key, value); });
                    roles.put(role, spec);
                }
                replacements.put("roles", roles);
            }
            merge(replacements, root, "failover", changed, Set.of("on_quota_exhausted", "on_vendor_failure", "max_per_stage"));
            merge(replacements, root, "budget", changed, Set.of("repair_reserve"));
            if (changed.containsKey("max_attempts") || changed.containsKey("backoff_seconds")) {
                Map<String, Object> retry = new LinkedHashMap<>(mapOrEmpty(root.get("retry")));
                Map<String, Object> rate = new LinkedHashMap<>(mapOrEmpty(retry.get("rate_limited")));
                for (String key : List.of("max_attempts", "backoff_seconds")) if (changed.containsKey(key)) {
                    Object value = changed.get(key);
                    if (!(value instanceof Number n) || n.doubleValue() != n.longValue())
                        throw new IllegalArgumentException("Integer retry setting required");
                    rate.put(key, value);
                }
                retry.put("rate_limited", rate); replacements.put("retry", retry);
            }
        }
        String candidate = YamlSettingsEdit.replace(source.text(), replacements);
        if (target.equals("task")) {
            TaskSpec parsed = TaskSpec.parse(candidate, path.toString());
            loaded = new ConfigLoader.Loaded(loaded.root(), loaded.projectFile(), loaded.taskFile(),
                    loaded.project(), parsed, parsed.resolve(loaded.project(), path.toString()));
        } else {
            Policy parsed = Policy.parse(candidate, path.toString());
            user = new UserConfig(user.home(), parsed, user.profiles(), user.problems(), true);
        }
        Map<String, Object> preview = TaskLoop.settingsPreview(next.project, loaded, user, RunOverride.NONE, next.availability);
        if (!next.read(task).revision().equals(revision)) throw new NextRun.Conflict();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target); result.put("file", source.file().toString());
        boolean differs = !source.text().equals(candidate);
        result.put("changed", differs);
        result.put("apply_allowed", differs && Boolean.TRUE.equals(preview.get("settings_ok")));
        result.put("preview_sha256", NextRun.digest((target + "\n" + task + "\n" + revision + "\n" + candidate).getBytes(StandardCharsets.UTF_8)));
        result.put("diff", YamlSettingsEdit.diff(source.text(), candidate));
        result.put("preview", preview);
        // Invocation export can express use:, but cannot express unsaved task budgets/removals.
        Map<String, Object> original = Yaml.parseMapping(source.text());
        if (target.equals("task") && sameExceptUse(original, Yaml.parseMapping(candidate))) {
            RunOverride desired = loaded.task().use();
            if (state.loaded().task().use().merged(desired).toMap().equals(desired.toMap()))
                result.put("exports", next.preview(Map.of("task", task, "revision", revision, "override", desired.toMap())));
        }
        return new Edit(task, revision, target, source, candidate, result);
    }

    private static boolean sameExceptUse(Map<String, Object> a, Map<String, Object> b) {
        a = new LinkedHashMap<>(a); b = new LinkedHashMap<>(b); a.remove("use"); b.remove("use");
        return a.equals(b);
    }
    private static void merge(Map<String, Object> replacements, Map<String, Object> root, String block,
                              Map<String, Object> changed, Set<String> keys) {
        Map<String, Object> values = new LinkedHashMap<>(mapOrEmpty(root.get(block)));
        boolean touched = false;
        for (String key : keys.stream().sorted().toList()) if (changed.containsKey(key)) {
            touched = true; values.put(key, changed.get(key));
        }
        if (touched) replacements.put(block, values);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("A mapping is required");
        return (Map<String, Object>) raw;
    }
    private static Map<String, Object> mapOrEmpty(Object raw) { return raw == null ? Map.of() : map(raw); }
    private static void requireKeys(Map<String, Object> map, Set<String> allowed) {
        if (!allowed.containsAll(map.keySet())) throw new IllegalArgumentException("Unknown settings field");
    }
}
