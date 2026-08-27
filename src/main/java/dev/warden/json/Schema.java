package dev.warden.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A JSON Schema subset validator with no dependencies.
 *
 * Vendor structured-output is a convenience, not a guarantee. Warden has to assert the
 * artifact against the same schema the prompt advertised, even when the vendor could not
 * enforce it. The keywords here are exactly those used by {@code ~/.warden/schemas/*.json}:
 * {@code type}, {@code const}, {@code enum}, {@code required}, {@code properties},
 * {@code additionalProperties:false}, {@code items}, {@code maxItems}, {@code maxLength},
 * {@code minimum}. Anything else is ignored rather than guessed at — a keyword we do not
 * implement must not silently become a pass.
 */
public final class Schema {

    private Schema() {}

    public static List<String> validate(Object value, Object schema) {
        List<String> errors = new ArrayList<>();
        validate(value, schema, "", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void validate(Object value, Object schema, String pointer, List<String> errors) {
        if (!(schema instanceof Map<?, ?> raw)) return;
        Map<String, Object> spec = (Map<String, Object>) raw;
        String at = pointer.isEmpty() ? "(root)" : pointer;

        if (spec.containsKey("const")) {
            if (!Json.write(value).equals(Json.write(spec.get("const")))) {
                errors.add(at + ": must equal " + Json.write(spec.get("const")));
            }
            return;
        }
        if (spec.get("enum") instanceof List<?> options) {
            boolean allowed = options.stream().anyMatch(option -> Json.write(option).equals(Json.write(value)));
            if (!allowed) {
                errors.add(at + ": must be one of " + Json.write(options) + ", got " + Json.write(value));
            }
            return;
        }
        if (spec.get("type") != null) {
            List<String> allowed = spec.get("type") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(spec.get("type")));
            if (allowed.stream().noneMatch(expected -> typeMatches(value, expected))) {
                errors.add(at + ": expected " + String.join("|", allowed) + ", got " + typeOf(value));
                return;
            }
        }
        if (value instanceof String text && spec.get("maxLength") instanceof Number max) {
            if (text.length() > max.longValue()) {
                errors.add(at + ": longer than maxLength " + max.longValue() + " (" + text.length() + ")");
            }
        }
        if (value instanceof Number number && spec.get("minimum") instanceof Number min) {
            if (number.doubleValue() < min.doubleValue()) {
                errors.add(at + ": below minimum " + min);
            }
        }
        if (value instanceof List<?> list) {
            if (spec.get("maxItems") instanceof Number max && list.size() > max.longValue()) {
                errors.add(at + ": more than maxItems " + max.longValue() + " (" + list.size() + ")");
            }
            if (spec.get("items") != null) {
                for (int index = 0; index < list.size(); index++) {
                    validate(list.get(index), spec.get("items"), pointer + "[" + index + "]", errors);
                }
            }
        }
        if (value instanceof Map<?, ?> object) {
            if (spec.get("required") instanceof List<?> required) {
                for (Object field : required) {
                    if (!object.containsKey(String.valueOf(field))) {
                        errors.add(at + ": missing required field '" + field + "'");
                    }
                }
            }
            Map<String, Object> properties = spec.get("properties") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            if (Boolean.FALSE.equals(spec.get("additionalProperties"))) {
                for (Object key : object.keySet()) {
                    if (!properties.containsKey(String.valueOf(key))) {
                        errors.add(at + ": unsupported field '" + key + "'");
                    }
                }
            }
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (object.containsKey(entry.getKey())) {
                    validate(object.get(entry.getKey()), entry.getValue(), pointer + "." + entry.getKey(), errors);
                }
            }
        }
    }

    private static boolean typeMatches(Object value, String expected) {
        String actual = typeOf(value);
        if ("number".equals(expected)) return "number".equals(actual) || "integer".equals(actual);
        return expected.equals(actual);
    }

    private static String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof List<?>) return "array";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Long || value instanceof Integer) return "integer";
        if (value instanceof Number) return "number";
        return value.getClass().getSimpleName();
    }
}
