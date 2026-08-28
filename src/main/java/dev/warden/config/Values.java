package dev.warden.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, path-aware access over the parsed config tree.
 *
 * Two rules run through all of it: an absent required value is an error rather than a
 * default, and an unknown key is an error rather than something ignored. Ignoring an unknown
 * key is how a typo in `read_only` silently turns a read-only role into a writable one.
 */
public final class Values {

    private final Map<String, Object> map;
    private final String source;
    private final String path;
    private final ConfigException.Collector collector;

    private Values(Map<String, Object> map, String source, String path,
                   ConfigException.Collector collector) {
        this.map = map;
        this.source = source;
        this.path = path;
        this.collector = collector;
    }

    @SuppressWarnings("unchecked")
    public static Values of(Object parsed, String source) {
        ConfigException.Collector collector = new ConfigException.Collector(source);
        if (!(parsed instanceof Map)) {
            throw new ConfigException(source, "the document must be a mapping, got "
                    + dev.warden.json.Json.typeName(parsed));
        }
        return new Values((Map<String, Object>) parsed, source, "", collector);
    }

    public String source() { return source; }
    public ConfigException.Collector collector() { return collector; }
    public Set<String> keys() { return map.keySet(); }
    public boolean has(String key) { return map.containsKey(key) && map.get(key) != null; }
    public void throwIfAny() { collector.throwIfAny(); }

    private String at(String key) { return path.isEmpty() ? key : path + "." + key; }

    /** Anything not listed is a typo or an unsupported feature; either way, refuse it. */
    public Values rejectUnknownKeys(Set<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) collector.add("unsupported key '" + at(key) + "'");
        }
        return this;
    }

    public void requireVersion(long expected) {
        Object raw = map.get("version");
        if (!(raw instanceof Long value) || value != expected) {
            collector.add("version must be " + expected);
        }
    }

    public String requireString(String key) {
        Object raw = map.get(key);
        if (raw instanceof String value && !value.isBlank()) return value;
        collector.add(at(key) + " must be a non-empty string"
                + (raw == null ? " (missing)" : ", got " + dev.warden.json.Json.typeName(raw)));
        return null;
    }

    public String optString(String key, String fallback) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        if (raw instanceof String value && !value.isBlank()) return value;
        collector.add(at(key) + " must be a non-empty string, got " + dev.warden.json.Json.typeName(raw));
        return fallback;
    }

    public String requireEnum(String key, Set<String> allowed, String fallback) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        if (raw instanceof String value && allowed.contains(value)) return value;
        collector.add(at(key) + " must be one of " + allowed + ", got " + raw);
        return fallback;
    }

    public long optInt(String key, long fallback, long min, long max) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        if (raw instanceof Long value) {
            if (value < min || value > max) {
                collector.add(at(key) + " must be between " + min + " and " + max + ", got " + value);
                return fallback;
            }
            return value;
        }
        collector.add(at(key) + " must be an integer, got " + dev.warden.json.Json.typeName(raw));
        return fallback;
    }

    public double optDouble(String key, double fallback, double min, double max) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        double value;
        if (raw instanceof Double number) value = number;
        else if (raw instanceof Long number) value = number.doubleValue();
        else {
            collector.add(at(key) + " must be a number, got " + dev.warden.json.Json.typeName(raw));
            return fallback;
        }
        if (value < min || value > max) {
            collector.add(at(key) + " must be between " + min + " and " + max + ", got " + value);
            return fallback;
        }
        return value;
    }

    public boolean optBool(String key, boolean fallback) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        collector.add(at(key) + " must be true or false, got " + dev.warden.json.Json.typeName(raw));
        return fallback;
    }

    public List<String> optStringList(String key, List<String> fallback) {
        Object raw = map.get(key);
        if (raw == null) return fallback;
        return readStringList(key, raw);
    }

    public List<String> requireStringList(String key) {
        Object raw = map.get(key);
        if (raw == null) {
            collector.add(at(key) + " is required and must be a non-empty list of strings");
            return List.of();
        }
        List<String> value = readStringList(key, raw);
        if (value.isEmpty()) collector.add(at(key) + " must not be empty");
        return value;
    }

    /**
     * A field that may be written as a bare name or as a list. The two forms mean different
     * things and the caller needs to know which was used, so the form is reported alongside
     * the values — see {@link Selector}.
     */
    public Selector selector(String key) {
        Object raw = map.get(key);
        if (raw == null) return new Selector(false, List.of());
        if (raw instanceof String single) return new Selector(true, List.of(single));
        return new Selector(false, readStringList(key, raw));
    }

    /**
     * `scope: ui` is a NAME and must be defined in project.yaml; `scope: ["src/lib"]` is an
     * explicit list. Keeping the two forms distinct is what makes a typo in a scope name an
     * error instead of a silently wrong blast radius.
     */
    public record Selector(boolean bareName, List<String> entries) {
        public boolean isEmpty() { return entries.isEmpty(); }
    }

    private List<String> readStringList(String key, Object raw) {
        if (!(raw instanceof List<?> list)) {
            collector.add(at(key) + " must be a list of strings, got " + dev.warden.json.Json.typeName(raw));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (item instanceof String value && !value.isBlank()) result.add(value);
            else collector.add(at(key) + "[" + index + "] must be a non-empty string, got "
                    + dev.warden.json.Json.typeName(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Values optMap(String key) {
        Object raw = map.get(key);
        if (raw == null) {
            return new Values(new LinkedHashMap<>(), source, at(key), collector);
        }
        if (!(raw instanceof Map)) {
            collector.add(at(key) + " must be a mapping, got " + dev.warden.json.Json.typeName(raw));
            return new Values(new LinkedHashMap<>(), source, at(key), collector);
        }
        return new Values((Map<String, Object>) raw, source, at(key), collector);
    }

    /**
     * A sequence of mappings, used by the workflow's ordered stages. Order is the point, so
     * this is a list rather than the name → value mappings the rest of the config uses.
     */
    @SuppressWarnings("unchecked")
    public List<Values> mapList(String key) {
        Object raw = map.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            collector.add(at(key) + " must be a list of mappings, got "
                    + dev.warden.json.Json.typeName(raw));
            return List.of();
        }
        List<Values> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (item instanceof Map) {
                result.add(new Values((Map<String, Object>) item, source,
                        at(key) + "[" + index + "]", collector));
            } else {
                collector.add(at(key) + "[" + index + "] must be a mapping, got "
                        + dev.warden.json.Json.typeName(item));
            }
        }
        return result;
    }

    /** A mapping of name → list of strings, used by `checks` and `scopes`. */
    public Map<String, List<String>> namedStringLists(String key) {
        return namedStringLists(key, false);
    }

    /**
     * @param allowEmpty an explicitly written `[]` is a statement, not a typo. It is accepted
     *                   only where the caller has something else that defines "done"; a
     *                   missing key still fails, because that one is an omission.
     */
    public Map<String, List<String>> namedStringLists(String key, boolean allowEmpty) {
        Values nested = optMap(key);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String name : nested.keys()) {
            result.put(name, allowEmpty ? nested.optStringList(name, List.of())
                    : nested.requireStringList(name));
        }
        return result;
    }
}
