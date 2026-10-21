package dev.warden.dashboard;

import dev.warden.json.Json;
import dev.warden.yaml.Yaml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps untouched sections verbatim; changed sections retain their comments before the new value. */
final class YamlSettingsEdit {
    private YamlSettingsEdit() {}
    static String replace(String text, Map<String, Object> values) {
        Map<String, Object> original = Yaml.parseMapping(text);
        Map<String, Object> edits = new LinkedHashMap<>();
        values.forEach((key, value) -> { if (!java.util.Objects.equals(original.get(key), value)) edits.put(key, value); });
        if (edits.isEmpty()) return text;
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        for (var edit : edits.entrySet()) {
            int begin = -1, end = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("#") || line.equals("---") || line.equals("...")) continue;
                // The strict parser accepts single-line top-level keys, including quoted keys.
                String key = Yaml.parseMapping(line).keySet().iterator().next();
                if (begin >= 0) { end = i; break; }
                if (key.equals(edit.getKey())) begin = i;
            }
            if (begin < 0) {
                begin = lines.size();
                if (begin > 0 && lines.get(begin - 1).equals("...")) begin--;
                end = begin;
            }
            List<String> replacement = new ArrayList<>();
            for (int i = begin; i < end; i++) {
                String comment = comment(lines.get(i));
                if (comment != null) replacement.add(comment);
            }
            if (edit.getValue() != null) replacement.add(edit.getKey() + ": " + Json.write(edit.getValue()));
            lines.subList(begin, end).clear(); lines.addAll(begin, replacement);
        }
        String result = String.join(eol, lines);
        if (!result.endsWith(eol)) result += eol;
        Map<String, Object> expected = new LinkedHashMap<>(original);
        edits.forEach((key, value) -> { if (value == null) expected.remove(key); else expected.put(key, value); });
        if (!expected.equals(Yaml.parseMapping(result))) throw new IllegalArgumentException("Cannot safely edit this YAML layout");
        return result;
    }
    private static String comment(String line) {
        boolean single = false, doub = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && doub) { i++; continue; }
            if (c == '\'' && !doub) single = !single;
            if (c == '"' && !single) doub = !doub;
            if (c == '#' && !single && !doub && (i == 0 || line.charAt(i - 1) == ' ')) return line.substring(i);
        }
        return null;
    }
    static String diff(String before, String after) {
        if (before.equals(after)) return "Нет изменений.";
        List<String> a = List.of(before.split("\r?\n", -1)), b = List.of(after.split("\r?\n", -1));
        int start = 0, tail = 0;
        while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start))) start++;
        while (tail < a.size() - start && tail < b.size() - start
                && a.get(a.size() - 1 - tail).equals(b.get(b.size() - 1 - tail))) tail++;
        StringBuilder out = new StringBuilder("--- текущий файл\n+++ после Apply\n@@ строка " + (start + 1) + " @@\n");
        for (int i = Math.max(0, start - 2); i < start; i++) out.append(' ').append(a.get(i)).append('\n');
        for (int i = start; i < a.size() - tail; i++) out.append('-').append(a.get(i)).append('\n');
        for (int i = start; i < b.size() - tail; i++) out.append('+').append(b.get(i)).append('\n');
        for (int i = b.size() - tail; i < Math.min(b.size(), b.size() - tail + 2); i++) out.append(' ').append(b.get(i)).append('\n');
        return out.toString();
    }
}
