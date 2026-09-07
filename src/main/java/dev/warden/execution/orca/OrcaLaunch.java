package dev.warden.execution.orca;

import dev.warden.config.Profile;
import dev.warden.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The launch contract is data, independent of terminal titles and agent narration. */
public final class OrcaLaunch {
    private OrcaLaunch() {}

    public static Map<String, Object> requested(Profile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agent", profile.command());
        value.put("model", profile.model());
        value.put("effort", profile.effort());
        return value;
    }

    /** Persisted before waiting; a resume must not relabel an existing worker's contract. */
    public static Map<String, Object> contract(Profile profile) {
        Map<String, Object> value = new LinkedHashMap<>(requested(profile));
        value.put("profile", profile.name());
        value.put("role", profile.role());
        value.put("vendor", profile.vendor());
        value.put("read_only", profile.readOnly());
        value.put("prompt_delivery", profile.promptDelivery());
        value.put("args", profile.args());
        value.put("wall_clock_minutes", profile.wallClockMinutes());
        value.put("vision_delivery", profile.vision() == null ? null : profile.vision().delivery());
        value.put("vision_verified", profile.hasVerifiedVision());
        value.put("prompt_template", profile.promptTemplate());
        value.put("json_schema", profile.jsonSchema());
        value.put("enforce_schema", profile.enforceSchema());
        value.put("artifact_required_fields", profile.requiredArtifactFields());
        return value;
    }

    public static List<String> arguments(Profile profile) {
        List<String> args = new ArrayList<>(List.of("--agent", profile.command()));
        if (profile.model() != null) args.addAll(List.of("--model", profile.model()));
        if (profile.effort() != null) args.addAll(List.of("--effort", profile.effort()));
        return List.copyOf(args);
    }

    public record Verification(boolean ok, String reason, Map<String, Object> evidence) {}

    public static Verification verify(Profile profile, Map<String, Object> envelope) {
        Map<String, Object> launch = receipt(envelope);
        Map<String, Object> requested = object(launch.get("requested"));
        Map<String, Object> effective = object(launch.get("effective"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("requested", requested(profile));
        evidence.put("orca_requested", requested);
        evidence.put("effective", effective);
        evidence.put("source", "orca_launch_receipt");
        // A receipt confirms launch options, not the provider's actual model inference.
        evidence.put("provider_execution_verified", false);
        String reason = "matched";
        for (var field : requested(profile).entrySet()) {
            if (field.getValue() == null) continue;
            if (!Objects.equals(field.getValue(), requested.get(field.getKey()))
                    || !Objects.equals(field.getValue(), effective.get(field.getKey()))) {
                reason = launch.isEmpty() ? "launch_receipt_missing" : "launch_" + field.getKey() + "_mismatch";
                break;
            }
        }
        evidence.put("status", reason);
        evidence.put("effort_source", profile.effort() != null ? "warden_profile"
                : effective.get("effort") != null ? "orca_launch_receipt" : "inherited_unknown");
        return new Verification(reason.equals("matched"), reason, evidence);
    }

    /** worker-start and worker-show shapes observed in Orca 1.4.197. */
    public static Map<String, Object> receipt(Map<String, Object> envelope) {
        Map<String, Object> result = OrcaSettlement.resultOf(envelope);
        Map<String, Object> launch = object(result.get("launch"));
        if (!launch.isEmpty()) return launch;
        Map<String, Object> worker = object(result.get("worker"));
        Map<String, Object> options = object(worker.get("startOptions"));
        if (options.isEmpty()) options = object(worker.get("start_options"));
        return object(options.get("launch"));
    }

    private static Map<String, Object> object(Object raw) {
        if (raw instanceof String text) {
            try { return Json.parseObject(text); } catch (RuntimeException invalid) { return Map.of(); }
        }
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return value;
    }
}
