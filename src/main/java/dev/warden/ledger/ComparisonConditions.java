package dev.warden.ledger;

import dev.warden.Main;
import dev.warden.config.Profile;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.Workflow;
import dev.warden.json.Json;
import dev.warden.role.RoleContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Frozen requested configuration, including stages a stopped run never reached. */
public final class ComparisonConditions {
    private static final Map<String, Object> BUILD = buildIdentity();
    private ComparisonConditions() {}

    public static Map<String, Object> snapshot(Workflow workflow, UserConfig user,
            TaskSpec.ResolvedTask task, RunOverride overlay,
            Function<Workflow.Stage, List<Profile>> candidates,
            Function<Workflow.Stage, String> skip) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("warden_version", Main.VERSION);
        data.put("warden_build", BUILD);
        data.put("java_version", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name"));
        data.put("arch", System.getProperty("os.arch"));
        data.put("workflow", workflow.toList());
        data.put("run_override", overlay.toMap());
        data.put("risk", task.risk());
        data.put("review_assurance", task.reviewAssurance());
        data.put("max_fix_attempts", task.maxFixAttempts());
        data.put("max_role_runs", task.budget().maxRoleRuns());
        data.put("max_cost_usd", task.budget().maxCostUsd());
        data.put("max_elapsed_minutes", task.budget().maxElapsedMinutes());
        data.put("cost_cap", task.budget().costCap());
        data.put("failover_mode", user.policy() == null ? null : user.policy().failoverMode());
        data.put("failover_on_vendor_failure", user.policy() == null ? null : user.policy().vendorFailureMode());
        data.put("max_vendor_failovers", user.policy() == null ? null : user.policy().maxVendorFailovers());
        if (user.policy() != null) {
            data.put("repair_reserve", user.policy().repairReserve());
            data.put("repair_severities", user.policy().repairSeverities().stream().sorted().toList());
            data.put("contract_gaps", user.policy().contractGaps());
            data.put("rate_limit_retry", Map.of("max_attempts", user.policy().rateLimitRetry().maxAttempts(),
                    "backoff_seconds", user.policy().rateLimitRetry().backoffSeconds()));
            data.put("escalation", user.policy().escalation() == null ? null : user.policy().escalation().toMap());
            // Preparation and repair roles need not appear as normal workflow stages.
            // Freeze their declared rosters too, including models and effort, before any
            // outcome could determine which profiles appear in the comparison key.
            Map<String, Object> policyRoles = new TreeMap<>();
            user.policy().roles().forEach((role, spec) -> {
                List<Map<String, Object>> defaults = new ArrayList<>();
                for (String name : spec.profiles()) {
                    Profile profile = user.profiles().get(name);
                    defaults.add(profile == null ? Map.of("profile", name, "unavailable", true)
                            : profileTerms("policy/" + role, role, profile, task, user));
                }
                policyRoles.put(role, defaults);
            });
            data.put("policy_roles", policyRoles);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        boolean complete = BUILD.get("sha256") instanceof String;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("skipped", skip.apply(stage));
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (Profile profile : candidates.apply(stage)) {
                Map<String, Object> terms = profileTerms(stage.name(), stage.role(), profile, task, user);
                if (row.get("skipped") == null && terms.containsValue("unavailable")) complete = false;
                profiles.add(terms);
            }
            row.put("candidates", profiles);
            stages.add(row);
        }
        data.put("stages", stages);
        data.put("configuration_complete", complete);
        return recorded(data);
    }

    private static Map<String, Object> profileTerms(String stage, String role, Profile profile,
                                                    TaskSpec.ResolvedTask task, UserConfig user) {
        Map<String, Object> terms = RoleContract.of(stage, role,
                user.policy() == null ? null : user.policy().roles().get(role), profile, user);
        for (String key : List.of("prompt_template_sha256", "json_schema_sha256", "mcp_config_sha256")) {
            if (terms.get(key) instanceof String digest && !digest.matches("[0-9a-f]{64}"))
                terms.put(key, "unavailable");
        }
        terms.put("on_vendor_failure", user.policy() == null ? null : user.policy().vendorFailureModeFor(role));
        terms.put("measurement_context", MeasurementContext.snapshot(profile, task, user));
        terms.put("max_cost_usd", profile.maxCostUsd());
        terms.put("enforce_schema", profile.enforceSchema());
        terms.put("required_artifact_fields", profile.requiredArtifactFields());
        terms.put("launch_sha256", hash(java.util.Arrays.asList(profile.command(),
                profile.args(), profile.endpoint(), profile.promptDelivery(), profile.attachmentFlag())));
        return terms;
    }

    public static Map<String, Object> recorded(Map<String, Object> data) {
        return Map.of("schema_version", 1L, "source", "frozen_requested_configuration",
                "data", data, "sha256", hash(data));
    }

    /** Hash the loaded artifact, not the project being edited or the current Git checkout. */
    private static Map<String, Object> buildIdentity() {
        try {
            var code = Main.class.getProtectionDomain().getCodeSource();
            if (code == null) return Map.of("source", "unknown");
            Path location = Path.of(code.getLocation().toURI());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            if (Files.isRegularFile(location)) files = List.of(location);
            else try (var paths = Files.walk(location)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".class"))
                        .sorted(java.util.Comparator.comparing(file -> location.relativize(file).toString().replace('\\', '/')))
                        .toList();
            }
            if (files.isEmpty()) return Map.of("source", "unknown");
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                if (!file.equals(location)) {
                    digest.update(location.relativize(file).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                try (var stream = Files.newInputStream(file)) {
                    int read;
                    while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
            }
            return Map.of("sha256", HexFormat.of().formatHex(digest.digest()), "source",
                    Files.isDirectory(location) ? "code_source_classes" : "code_source_archive");
        } catch (Exception unavailable) {
            return Map.of("source", "unknown");
        }
    }

    /** Map order is not an experimental condition; ordered workflows and rosters are. */
    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ComparisonConditions::canonical).toList();
        return value;
    }
}
