package dev.warden.ledger;

import dev.warden.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allowlist projection of a local evidence event into a measurement.
 *
 * A field nobody allowed does not pass merely because it is new. Nested maps are projected
 * with their own allowlists, never copied whole. Nothing carrying goal, prompt, vendor
 * reply, finding text, arbitrary error strings, argv, environment, credentials, diffs or
 * sources is admitted.
 */
public final class MeasurementProjector {

    public static final long SCHEMA_VERSION = 1L;

    /**
     * Keys that never leave the local tree, even if a future caller stuffs them into an
     * otherwise allowed nested object. Measurement-context and grant {@code source} labels
     * (profile, launch_receipt, unknown, task) are not this list: they are allowlisted on
     * {@link #LABEL} and {@link #GRANTS}. {@code sources} (source text) still is.
     */
    private static final Set<String> NEVER = Set.of(
            "goal", "non_goals", "prompt", "prompt_path", "message", "error", "errors",
            "stderr", "stdout", "stderr_tail", "stdout_tail", "raw_stdout", "body_head",
            "answer_tail", "start_error", "argv", "args", "command", "command_preview",
            "dispatch_preview", "env", "environment", "credentials", "credential",
            "password", "secret", "token", "api_key", "api_key_env", "diff",
            "sources", "resolution", "note", "actor", "vendor_message", "task_spec",
            "agent_wait", "summary", "path", "report", "artifact_path", "screenshots_dir",
            "preview_log", "decision_path", "attachments_offered", "attachments",
            "lifecycle_error", "worker_release_stderr", "expected", "actual", "reproduce");

    private static final Set<String> TOP = Set.of(
            "event_id", "schema_version", "at", "type", "seq",
            "project_id", "project_name", "run_instance_id", "operator_run_id", "run_id",
            "parent_run_instance_id", "lineage", "continued_from",
            "stage", "role", "role_invocation_id", "task_id", "task_kind", "risk",
            "vendor_attempt", "vendor_attempt_id", "candidate_fingerprint",
            "ok", "code", "reason", "next_action", "verdict",
            "profile", "vendor", "model", "model_reported", "runner",
            "effort_requested", "read_only", "strategy", "independence_required",
            "rotation_counter", "workflow_run_id",
            "duration_millis", "duration_ms", "cost_usd", "attempts_cost_usd",
            "tokens", "num_turns", "timed_out", "exit_code", "stop_reason",
            "dry_run", "prepare", "attachment_count",
            "attempts_used", "role_runs", "total_cost_usd", "unpriced_calls",
            "cost_ceiling_binding", "budget_max_role_runs", "budget_max_cost_usd",
            "decision", "source", "wait_millis", "created_at", "decided_at", "updated_at",
            "lands", "kind",
            "from_profile", "from_vendor", "to_profile", "to_vendor", "cause", "authorized_by",
            "failed_over_from", "exhausted_profiles",
            "role_contract", "measurement_context", "vendor_attempts", "quota",
            "vision_capability", "findings", "finding_registry", "finding_history", "steps",
            "accounting", "outcomes", "budget_plan",
            "prompt_template_sha256", "json_schema_sha256", "prompt_sha256", "artifact_sha256",
            "contract_sha256", "acceptance_sha256",
            "diff_base_commit", "worktree_fingerprint",
            "avoided_vendor",
            "failover_declined_by_budget", "failover_declined_by_policy",
            "phase", "view_state");

    private static final Set<String> TOKENS = Set.of(
            "input", "output", "total", "input_tokens", "output_tokens", "total_tokens");

    private static final Set<String> ROLE_CONTRACT = Set.of(
            "stage", "role", "roster", "strategy", "require_independent_vendor",
            "profile", "vendor", "model", "effort", "read_only",
            "prompt_template_sha256", "json_schema_sha256",
            "on_fail", "on_findings", "recheck_after_fix", "fix_with");

    private static final Set<String> LABEL = Set.of("value", "source");

    private static final Set<String> TRIPLE = Set.of("requested", "effective", "reported");

    private static final Set<String> CONTEXT = Set.of(
            "schema_version", "warden_version", "adapter_version",
            "wall_clock_minutes", "timeout_minutes", "turn_limit", "grants",
            "prompt_template_sha256", "json_schema_sha256",
            "model", "effort", "contract_hash");

    private static final Set<String> GRANTS = Set.of(
            "workspace_write", "network", "land", "source");

    private static final Set<String> ATTEMPT = Set.of(
            "attempt", "profile", "vendor", "runner", "model", "model_reported",
            "effort_requested", "ok", "code", "duration_millis", "duration_ms",
            "cost_usd", "tokens", "inherited_unfinished_work",
            "vendor_attempt_id");

    private static final Set<String> FINDING = Set.of(
            "id", "severity", "status", "stage", "supersedes", "closed",
            "closed_ids", "reopened", "reopened_from", "id_source", "category",
            "category_source", "recorded_at_stage");

    private static final Set<String> FINDING_HISTORY = Set.of(
            "stage", "attempt", "candidate_fingerprint", "findings", "finding_registry",
            "blocking_ids", "closed", "persisted", "new_findings", "candidate_moved",
            "severity_downgraded", "blocking_retracted", "source_run", "closed_ids");

    private static final Set<String> BUDGET_PLAN = Set.of(
            "requested_cap", "minimum_success_calls", "paying_stages",
            "sufficient_for_success", "repair_reserve", "recovery_branches",
            "cost_reserve", "time_reserve");

    private static final Set<String> RECOVERY_BRANCH = Set.of(
            "stage", "calls_needed_to_repair", "calls_to_repair_and_be_judged",
            "calls_to_repair_and_finish", "calls_required_here",
            "reachable_under_cap", "repair_allowed_under_cap");

    private static final Set<String> STEP = Set.of(
            "step", "stage", "attempt", "ok", "code", "dry_run", "role", "profile", "vendor");

    private static final Set<String> QUOTA = Set.of("detected_by", "matched_signature");

    private static final Set<String> VISION = Set.of("verified", "delivery");

    private static final Set<String> ACCOUNTING = Set.of(
            "kind", "counts_as_new_calls", "vendor_attempt_count");

    private static final Set<String> OUTCOMES = Set.of(
            "role_ok", "workflow_outcome", "human_accept");

    private static final Set<String> LINEAGE = Set.of(
            "parent_run_instance_id", "continued_from", "kind");

    private static final Set<String> PROVENANCE_KEYS = Set.of(
            "prompt_path", "artifact_path", "report", "path", "raw_stdout",
            "screenshots_dir", "preview_log", "decision_path", "summary");

    private static final Map<String, Set<String>> NESTED = Map.ofEntries(
            Map.entry("tokens", TOKENS),
            Map.entry("role_contract", ROLE_CONTRACT),
            Map.entry("measurement_context", CONTEXT),
            Map.entry("vendor_attempts", ATTEMPT),
            Map.entry("findings", FINDING),
            Map.entry("finding_registry", FINDING),
            Map.entry("finding_history", FINDING_HISTORY),
            Map.entry("budget_plan", BUDGET_PLAN),
            Map.entry("recovery_branches", RECOVERY_BRANCH),
            Map.entry("steps", STEP),
            Map.entry("quota", QUOTA),
            Map.entry("vision_capability", VISION),
            Map.entry("accounting", ACCOUNTING),
            Map.entry("outcomes", OUTCOMES),
            Map.entry("lineage", LINEAGE),
            Map.entry("grants", GRANTS),
            Map.entry("warden_version", LABEL),
            Map.entry("adapter_version", LABEL),
            Map.entry("wall_clock_minutes", LABEL),
            Map.entry("timeout_minutes", LABEL),
            Map.entry("prompt_template_sha256", LABEL),
            Map.entry("json_schema_sha256", LABEL),
            Map.entry("turn_limit", TRIPLE),
            Map.entry("model", TRIPLE),
            Map.entry("effort", TRIPLE),
            Map.entry("requested", LABEL),
            Map.entry("effective", LABEL),
            Map.entry("reported", LABEL));

    public record Projection(
            Map<String, Object> body,
            Map<String, Object> provenance,
            String contentHash) {}

    private MeasurementProjector() {}

    public static Projection project(Map<String, Object> localEvent) {
        Map<String, Object> body = projectMap(localEvent, TOP);
        body.put("schema_version", SCHEMA_VERSION);
        body.put("accounting", accountingFor(text(localEvent.get("type")), localEvent));
        body.put("outcomes", outcomesFor(text(localEvent.get("type")), localEvent));
        Map<String, Object> hashed = new LinkedHashMap<>(body);
        hashed.remove("at");
        hashed.remove("seq");
        String hash = ProjectIdentity.sha256(Json.write(hashed));
        return new Projection(body, provenanceOf(localEvent), hash);
    }

    public static Map<String, Object> stripPortable(Map<String, Object> record) {
        return stripPortableValue(record);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> projectMap(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) return out;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key) || NEVER.contains(key)) continue;
            Object projected = projectValue(key, entry.getValue());
            if (projected != SKIP) out.put(key, projected);
        }
        return out;
    }

    private static final Object SKIP = new Object();

    private static Object projectValue(String key, Object value) {
        if (value == null) return null;
        if (NEVER.contains(key)) return SKIP;
        if (value instanceof Map<?, ?> map) {
            Set<String> nested = NESTED.get(key);
            if (nested == null) return SKIP;
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) map;
            return projectMap(body, nested);
        }
        if (value instanceof List<?> list) {
            Set<String> nested = NESTED.get(key);
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (nested == null) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) map;
                    Map<String, Object> projected = projectMap(body, nested);
                    if (!projected.isEmpty()) out.add(projected);
                } else if (item instanceof String || item instanceof Number
                        || item instanceof Boolean || item == null) {
                    if (nested != null) continue;
                    if (item instanceof String text && isUnsafeString(key, text)) continue;
                    out.add(item);
                }
            }
            return out;
        }
        if (value instanceof String text) {
            if (isUnsafeString(key, text)) return SKIP;
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        return SKIP;
    }

    private static boolean isUnsafeString(String key, String text) {
        if (text == null) return false;
        if (isOpaque(key)) return false;
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return true;
        if (text.contains("://")) return true;
        if (text.length() >= 3 && Character.isLetter(text.charAt(0))
                && text.charAt(1) == ':' && (text.charAt(2) == '\\' || text.charAt(2) == '/')) {
            return true;
        }
        if (text.startsWith("/") && text.contains("/") && text.length() > 1) return true;
        if (text.contains("\\") && (text.contains(".warden") || text.contains("Users")
                || text.contains("home"))) {
            return true;
        }
        return text.length() > 240;
    }

    private static boolean isOpaque(String key) {
        return key.endsWith("_id") || key.endsWith("_sha256") || key.endsWith("_hash")
                || key.endsWith("_fingerprint") || "event_id".equals(key)
                || "candidate_fingerprint".equals(key) || "diff_base_commit".equals(key)
                || "matched_signature".equals(key);
    }

    private static Map<String, Object> accountingFor(String type, Map<String, Object> original) {
        Map<String, Object> accounting = new LinkedHashMap<>();
        if (type == null) {
            accounting.put("kind", "unknown");
            accounting.put("counts_as_new_calls", false);
            return accounting;
        }
        switch (type) {
            case "vendor_attempt" -> {
                accounting.put("kind", "vendor_attempt");
                accounting.put("counts_as_new_calls", true);
                accounting.put("vendor_attempt_count", 1L);
            }
            case "role_run" -> {
                // Per-attempt events carry the call count. This record is the role summary.
                accounting.put("kind", "vendor_attempt");
                accounting.put("counts_as_new_calls", false);
                int attempts = 1;
                if (original.get("vendor_attempts") instanceof List<?> list && !list.isEmpty()) {
                    attempts = list.size();
                }
                accounting.put("vendor_attempt_count", (long) attempts);
            }
            case "run_reserved", "run_prepared" -> {
                accounting.put("kind", "preparation");
                accounting.put("counts_as_new_calls", false);
            }
            case "task_run", "task_dry_run" -> {
                accounting.put("kind", "workflow_summary");
                accounting.put("counts_as_new_calls", false);
            }
            case "human_decision", "human_decision_pending", "human_wait" -> {
                accounting.put("kind", "human_decision");
                accounting.put("counts_as_new_calls", false);
            }
            case "machine_gate", "baseline_gate", "visual_qa" -> {
                accounting.put("kind", "gate");
                accounting.put("counts_as_new_calls", false);
            }
            case "role_dry_run" -> {
                accounting.put("kind", "dry_run");
                accounting.put("counts_as_new_calls", false);
            }
            default -> {
                accounting.put("kind", "signal");
                accounting.put("counts_as_new_calls", false);
            }
        }
        return accounting;
    }

    private static Map<String, Object> outcomesFor(String type, Map<String, Object> original) {
        Map<String, Object> outcomes = new LinkedHashMap<>();
        if ("role_run".equals(type) && original.get("ok") instanceof Boolean ok) {
            outcomes.put("role_ok", ok);
        }
        if ("task_run".equals(type) || "task_dry_run".equals(type)) {
            Object reason = original.get("reason");
            if (reason == null) reason = original.get("next_action");
            if (reason != null) outcomes.put("workflow_outcome", String.valueOf(reason));
        }
        if ("human_decision".equals(type)) {
            String decision = text(original.get("decision"));
            if (decision != null) {
                outcomes.put("human_accept", "accept".equals(decision) || "approved".equals(decision));
            }
        }
        return outcomes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provenanceOf(Map<String, Object> original) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        Object eventId = original.get("event_id");
        if (eventId != null) provenance.put("event_id", eventId);
        for (String key : PROVENANCE_KEYS) {
            Object value = original.get(key);
            if (value instanceof String text && !text.isBlank()) provenance.put(key, text);
        }
        if (original.get("findings") instanceof List<?> findings) {
            provenance.put("finding_count", (long) findings.size());
        }
        return provenance;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripPortableValue(Map<String, Object> record) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = System.getProperty("user.name", "");
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                if (!user.isBlank() && text.equals(user)) continue;
                if (isUnsafeString(entry.getKey(), text) && !isOpaque(entry.getKey())) continue;
                out.put(entry.getKey(), text);
            } else if (value instanceof Map<?, ?> map) {
                out.put(entry.getKey(), stripPortableValue((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                List<Object> copied = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> nested) {
                        copied.add(stripPortableValue((Map<String, Object>) nested));
                    } else if (item instanceof String text) {
                        if (!user.isBlank() && text.equals(user)) continue;
                        if (isUnsafeString(entry.getKey(), text) && !isOpaque(entry.getKey())) continue;
                        copied.add(text);
                    } else {
                        copied.add(item);
                    }
                }
                out.put(entry.getKey(), copied);
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
