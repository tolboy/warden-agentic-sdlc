package dev.warden.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader and writer with no dependencies.
 *
 * Vendor CLIs answer in JSON and the pipeline's own artifacts are JSON, so this is on the
 * critical path for correctness rather than convenience. It is deliberately strict: input
 * that is not valid JSON produces a JsonException naming the offset, never a best guess.
 * A parser that silently accepts malformed vendor output would let a broken review through
 * looking like a clean one.
 *
 * Values map to: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Boolean, Long, Double, null.
 * Objects preserve key order so a rewritten artifact stays diffable against the original.
 */
public final class Json {

    private Json() {}

    @SuppressWarnings("serial")
    public static class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing content at offset " + parser.position);
        }
        return value;
    }

    /** Parse, requiring the document to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object, got " + typeName(value));
        }
        return (Map<String, Object>) value;
    }

    /**
     * Find the last complete JSON object printed on its own line, then fall back to the
     * outermost braces. Vendor CLIs interleave chatter with their answer; this is how the
     * answer is recovered without asking every vendor to behave identically.
     */
    public static Map<String, Object> findLastObject(String output) {
        if (output == null) return null;
        String trimmed = output.strip();
        if (trimmed.isEmpty()) return null;
        try {
            return parseObject(trimmed);
        } catch (JsonException ignored) {
            // not a bare document; keep looking
        }
        String[] lines = trimmed.split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].strip();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return parseObject(line);
                } catch (JsonException ignored) {
                    // keep looking
                }
            }
        }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            try {
                return parseObject(trimmed.substring(first, last + 1));
            } catch (JsonException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) { this.text = text; }

        boolean atEnd() { return position >= text.length(); }

        void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') position++;
                else break;
            }
        }

        Object readValue() {
            if (atEnd()) throw new JsonException("unexpected end of input");
            char c = text.charAt(position);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': return readLiteral("true", Boolean.TRUE);
                case 'f': return readLiteral("false", Boolean.FALSE);
                case 'n': return readLiteral("null", null);
                default: return readNumber();
            }
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') { position++; return result; }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new JsonException("object key must be a string at offset " + position);
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') { position++; continue; }
                if (next == '}') { position++; return result; }
                throw new JsonException("expected ',' or '}' at offset " + position);
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') { position++; return result; }
            while (true) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') { position++; continue; }
                if (next == ']') { position++; return result; }
                throw new JsonException("expected ',' or ']' at offset " + position);
            }
        }

        String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("unterminated string");
                char c = text.charAt(position++);
                if (c == '"') return builder.toString();
                if (c != '\\') {
                    if (c < 0x20) throw new JsonException("raw control character in string at offset " + (position - 1));
                    builder.append(c);
                    continue;
                }
                if (atEnd()) throw new JsonException("unterminated escape");
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) throw new JsonException("truncated \\u escape");
                        String hex = text.substring(position, position + 4);
                        position += 4;
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("bad \\u escape '" + hex + "'");
                        }
                        break;
                    default: throw new JsonException("unknown escape '\\" + escape + "'");
                }
            }
        }

        Object readNumber() {
            int start = position;
            if (peek() == '-') position++;
            boolean floating = false;
            while (!atEnd()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') { position++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { floating = true; position++; }
                else break;
            }
            String raw = text.substring(start, position);
            if (raw.isEmpty() || raw.equals("-")) throw new JsonException("expected a value at offset " + start);
            try {
                if (floating) return Double.valueOf(raw);
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number '" + raw + "'");
            }
        }

        Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw new JsonException("expected '" + literal + "' at offset " + position);
            }
            position += literal.length();
            return value;
        }

        char peek() {
            if (atEnd()) throw new JsonException("unexpected end of input");
            return text.charAt(position);
        }

        void expect(char expected) {
            if (atEnd() || text.charAt(position) != expected) {
                throw new JsonException("expected '" + expected + "' at offset " + position);
            }
            position++;
        }
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value, -1, 0);
        return builder.toString();
    }

    /** Pretty-print with tab indentation, matching the artifacts already on disk. */
    public static String writePretty(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value, 0, 0);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder out, Object value, int indent, int depth) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { writeString(out, s); return; }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            out.append(value); return;
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new JsonException("cannot write " + d + " as JSON");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                long asLong = d.longValue();
                out.append(asLong == 0 && 1 / d < 0 ? "-0.0" : String.valueOf(d));
            } else {
                out.append(d);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { out.append("{}"); return; }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<String, Object>) map).entrySet()) {
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                if (indent >= 0) out.append(' ');
                writeValue(out, entry.getValue(), indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) { out.append("[]"); return; }
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                writeValue(out, item, indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append(']');
            return;
        }
        throw new JsonException("cannot write " + value.getClass().getName() + " as JSON");
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) return;
        out.append('\n');
        out.append("\t".repeat(depth));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- typed access

    public static String typeName(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) return "object";
        if (value instanceof List) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Long || value instanceof Integer) return "integer";
        if (value instanceof Double) return "number";
        return value.getClass().getSimpleName();
    }
}
