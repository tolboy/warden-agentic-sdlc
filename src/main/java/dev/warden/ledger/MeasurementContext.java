package dev.warden.ledger;

import dev.warden.Main;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.Profile;
import dev.warden.git.GitRepository;
import dev.warden.execution.RoleExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A safe snapshot of the conditions a call ran under, recorded beside {@code role_contract}.
 *
 * {@link RoleContract#of} is the reuse comparison: roster, strategy, independence, profile,
 * vendor, model, effort, {@code read_only} and two hashes. It does not carry wall-clock or
 * turn limits, grants, or a turn ceiling. Copying it is not enough to compare measurements.
 * This snapshot is additive and is never consulted by that comparison.
 *
 * A missing value stays absent. An unknown effective setting is never replaced by the
 * requested one.
 */
public final class MeasurementContext {

    private MeasurementContext() {}

    public static Map<String, Object> snapshot(Profile profile, TaskSpec.ResolvedTask task,
                                               UserConfig user) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema_version", 1L);
        context.put("warden_version", labelled(Main.VERSION, "warden"));
        context.put("adapter_version", labelled(null, "unknown"));
        context.put("wall_clock_minutes", labelled(profile.wallClockMinutes(), "profile"));
        context.put("timeout_minutes", task == null ? labelled(null, "unknown")
                : labelled(task.timeoutMinutes(), "task"));
        context.put("turn_limit", turnLimit(profile));
        context.put("grants", grants(task));
        context.put("prompt_template_sha256", labelled(digest(user, profile.promptTemplate()),
                profile.promptTemplate() == null ? "unknown" : "profile"));
        context.put("json_schema_sha256", labelled(digest(user, profile.jsonSchema()),
                profile.jsonSchema() == null ? "unknown" : "profile"));
        context.put("model", triple(profile.model(), "profile", null, "unknown", null, "unknown"));
        context.put("effort", triple(profile.effort(), "profile", null, "unknown", null, "unknown"));
        return context;
    }

    /**
     * Overlay vendor-reported model and effort without inventing an effective setting from
     * the requested one.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withReported(Map<String, Object> snapshot,
                                                   RoleExecutor.Result result) {
        Map<String, Object> context = new LinkedHashMap<>(snapshot == null ? Map.of() : snapshot);
        Map<String, Object> evidence = result == null || result.evidence() == null
                ? Map.of() : result.evidence();
        Object reportedModel = first(evidence, "model_reported");
        Object reportedEffort = first(evidence, "effort_reported");
        context.put("model", overlayTriple(map(context.get("model")), reportedModel, "vendor"));
        context.put("effort", overlayTriple(map(context.get("effort")), reportedEffort, "vendor"));

        Map<String, Object> launch = map(evidence.get("launch"));
        Map<String, Object> effective = map(launch.get("effective"));
        if (!effective.isEmpty()) {
            context.put("model", overlayEffective(map(context.get("model")),
                    effective.get("model"), "launch_receipt"));
            context.put("effort", overlayEffective(map(context.get("effort")),
                    effective.get("effort"), "launch_receipt"));
        }
        Object turns = first(evidence, "num_turns");
        if (turns instanceof Number) {
            Map<String, Object> limit = new LinkedHashMap<>(map(context.get("turn_limit")));
            Map<String, Object> reported = labelled(turns, "vendor");
            limit.put("reported", reported);
            context.put("turn_limit", limit);
        }
        return context;
    }

    private static Map<String, Object> turnLimit(Profile profile) {
        Long requested = flagLong(profile.args(), "--max-turns");
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("requested", labelled(requested, requested == null ? "unknown" : "profile_args"));
        limit.put("effective", labelled(null, "unknown"));
        limit.put("reported", labelled(null, "unknown"));
        return limit;
    }

    private static Map<String, Object> grants(TaskSpec.ResolvedTask task) {
        Map<String, Object> grants = new LinkedHashMap<>();
        if (task == null) {
            grants.put("source", "unknown");
            return grants;
        }
        grants.put("workspace_write", task.authority().workspaceWrite());
        grants.put("network", task.authority().network());
        grants.put("land", task.authority().land());
        grants.put("source", "task");
        return grants;
    }

    private static Map<String, Object> triple(Object requested, String requestedSource,
                                              Object effective, String effectiveSource,
                                              Object reported, String reportedSource) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requested", labelled(requested, requested == null ? "unknown" : requestedSource));
        value.put("effective", labelled(effective, effective == null ? "unknown" : effectiveSource));
        value.put("reported", labelled(reported, reported == null ? "unknown" : reportedSource));
        return value;
    }

    private static Map<String, Object> overlayTriple(Map<String, Object> existing,
                                                     Object reported, String source) {
        Map<String, Object> value = new LinkedHashMap<>(existing);
        if (reported != null) value.put("reported", labelled(reported, source));
        return value;
    }

    private static Map<String, Object> overlayEffective(Map<String, Object> existing,
                                                        Object effective, String source) {
        Map<String, Object> value = new LinkedHashMap<>(existing);
        if (effective != null) value.put("effective", labelled(effective, source));
        return value;
    }

    static Map<String, Object> labelled(Object value, String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (value != null) body.put("value", value);
        body.put("source", source == null ? "unknown" : source);
        return body;
    }

    private static Long flagLong(List<String> args, String name) {
        if (args == null) return null;
        for (int index = 0; index + 1 < args.size(); index++) {
            if (name.equals(args.get(index))) {
                try {
                    return Long.parseLong(args.get(index + 1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String digest(UserConfig user, String relative) {
        if (relative == null || user == null) return null;
        try {
            Path file = user.resolve(relative);
            return Files.isRegularFile(file) ? GitRepository.sha256(file) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object first(Map<String, Object> map, String... names) {
        for (String name : names) {
            if (map.get(name) != null) return map.get(name);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
