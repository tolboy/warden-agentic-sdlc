package dev.warden.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared interpretation of recorded role measurements; requested settings are not receipts. */
final class RoleMeasurements {
    private RoleMeasurements() {}

    static Object reportedModel(Map<String, Object> row) {
        Object reported = row.get("model_reported");
        if (reported instanceof String value && !value.isBlank()) return value;
        return contextModel(row, "reported");
    }

    static Object requestedModel(Map<String, Object> row) {
        if (row.containsKey("model_requested")) return row.get("model_requested");
        // Historical `model` has no provenance. It remains in raw evidence, but
        // establishes neither a requested setting nor a vendor receipt.
        return contextModel(row, "requested");
    }

    private static Object contextModel(Map<String, Object> row, String source) {
        if (row.get("measurement_context") instanceof Map<?, ?> context
                && context.get("model") instanceof Map<?, ?> model
                && model.get(source) instanceof Map<?, ?> labelled
                && labelled.get("value") instanceof String value && !value.isBlank()) return value;
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> failovers(Map<String, Object> role) {
        // The journal is authoritative, including a confirmed continuation that has
        // only one new attempt. Preserve repeated from/to pairs and their order.
        if (role.get("role_failovers") instanceof List<?> rows) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : rows) if (item instanceof Map<?, ?> map) {
                Map<String, Object> event = new LinkedHashMap<>((Map<String, Object>) map);
                alias(event, "from_profile", "from");
                alias(event, "to_profile", "to");
                if (event.get("from_code") == null) event.put("from_code", event.get("role_code"));
                result.add(event);
            }
            return result;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (role.get("vendor_attempts") instanceof List<?> rows && rows.size() > 1) {
            for (int index = 1; index < rows.size(); index++) {
                if (!(rows.get(index - 1) instanceof Map<?, ?> before)
                        || !(rows.get(index) instanceof Map<?, ?> after)) continue;
                if (!changed(before, after, "profile") && !changed(before, after, "vendor")) continue;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("from_profile", before.get("profile"));
                event.put("from_vendor", before.get("vendor"));
                event.put("to_profile", after.get("profile"));
                event.put("to_vendor", after.get("vendor"));
                event.put("from_code", before.get("code"));
                result.add(event);
            }
        } else if (role.get("failed_over_from") instanceof List<?> prior) {
            for (int index = 0; index < prior.size(); index++) {
                Object from = prior.get(index);
                Object to = index + 1 < prior.size() ? prior.get(index + 1) : role.get("profile");
                if (from == null || to == null || Objects.equals(from, to)) continue;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("from_profile", from);
                event.put("to_profile", to);
                result.add(event);
            }
        }
        return result;
    }

    private static boolean changed(Map<?, ?> before, Map<?, ?> after, String field) {
        Object left = before.get(field), right = after.get(field);
        return left instanceof String a && !a.isBlank()
                && right instanceof String b && !b.isBlank() && !a.equals(b);
    }

    private static void alias(Map<String, Object> event, String field, String legacy) {
        if (event.get(field) == null) event.put(field, event.get(legacy));
    }
}
