package dev.warden.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict reader for the subset of YAML this tool's configuration uses.
 *
 * Configuration is written by people, so it gets comments and block layout rather than JSON.
 * But a permissive YAML parser is a liability here: this file decides which vendor may write
 * to a repository and what "done" means, so anything the parser does not fully understand is
 * an ERROR naming the line, never a silent reinterpretation. Guessing is how a config file
 * ends up meaning something its author did not intend.
 *
 * Supported: block mappings, block sequences, nested blocks by space indentation, plain and
 * quoted scalars, inline flow sequences and mappings, comments, one optional leading `---`.
 * Rejected with a message: tabs in indentation, anchors, aliases, tags, block scalars
 * (`|`, `&gt;`), merge keys, and more than one document.
 *
 * Values map to the same Java types as {@link dev.warden.json.Json}, so config and JSON share
 * one object model and one set of accessors.
 */
public final class Yaml {

    private Yaml() {}

    @SuppressWarnings("serial")
    public static class YamlException extends RuntimeException {
        public YamlException(String message) { super(message); }
    }

    private record Line(int number, int indent, String content) {}

    public static Object parse(String text) {
        List<Line> lines = readLines(text);
        if (lines.isEmpty()) return new LinkedHashMap<String, Object>();
        Cursor cursor = new Cursor(lines);
        Object value = parseBlock(cursor, lines.get(0).indent());
        if (!cursor.atEnd()) {
            throw new YamlException("line " + cursor.peek().number() + ": unexpected content at this indentation");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMapping(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new YamlException("expected a mapping at the top level of the document");
        }
        return (Map<String, Object>) value;
    }

    // ---------------------------------------------------------------- lexing

    private static List<Line> readLines(String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = text.split("\r?\n", -1);
        boolean sawDocumentStart = false;
        for (int index = 0; index < raw.length; index++) {
            String line = raw[index];
            int number = index + 1;

            String withoutComment = stripComment(line, number);
            if (withoutComment.isBlank()) continue;

            String trimmedRight = stripTrailing(withoutComment);
            if (trimmedRight.equals("---")) {
                if (sawDocumentStart) {
                    throw new YamlException("line " + number + ": multiple YAML documents are not supported");
                }
                sawDocumentStart = true;
                continue;
            }
            if (trimmedRight.equals("...")) continue;

            int indent = 0;
            while (indent < trimmedRight.length() && trimmedRight.charAt(indent) == ' ') indent++;
            if (indent < trimmedRight.length() && trimmedRight.charAt(indent) == '\t') {
                throw new YamlException("line " + number + ": tabs cannot be used for indentation");
            }
            String content = trimmedRight.substring(indent);
            rejectUnsupported(content, number);
            lines.add(new Line(number, indent, content));
        }
        return lines;
    }

    private static void rejectUnsupported(String content, int number) {
        if (content.startsWith("&") || content.startsWith("*")) {
            throw new YamlException("line " + number + ": anchors and aliases are not supported");
        }
        if (content.startsWith("<<:")) {
            throw new YamlException("line " + number + ": merge keys are not supported");
        }
        if (content.startsWith("!")) {
            throw new YamlException("line " + number + ": tags are not supported");
        }
    }

    /** Remove a `#` comment, but only when the `#` is outside quotes. */
    private static String stripComment(String line, int number) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c == '\\' && inDouble) { index++; continue; }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) {
                boolean startOfLineOrSpaced = index == 0 || line.charAt(index - 1) == ' ';
                if (startOfLineOrSpaced) return line.substring(0, index);
            }
        }
        if (inSingle || inDouble) {
            throw new YamlException("line " + number + ": unterminated quoted string");
        }
        return line;
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\r')) end--;
        return value.substring(0, end);
    }

    private static final class Cursor {
        private final List<Line> lines;
        private int index;

        Cursor(List<Line> lines) { this.lines = lines; }

        boolean atEnd() { return index >= lines.size(); }
        Line peek() { return lines.get(index); }
        Line next() { return lines.get(index++); }
    }

    // ---------------------------------------------------------------- block parsing

    private static Object parseBlock(Cursor cursor, int indent) {
        if (cursor.atEnd()) return null;
        Line first = cursor.peek();
        if (first.indent() != indent) {
            throw new YamlException("line " + first.number() + ": unexpected indentation "
                    + first.indent() + ", expected " + indent);
        }
        if (first.content().startsWith("- ") || first.content().equals("-")) {
            return parseSequence(cursor, indent);
        }
        return parseMappingBlock(cursor, indent);
    }

    private static List<Object> parseSequence(Cursor cursor, int indent) {
        List<Object> items = new ArrayList<>();
        while (!cursor.atEnd()) {
            Line line = cursor.peek();
            if (line.indent() < indent) break;
            if (line.indent() > indent) {
                throw new YamlException("line " + line.number() + ": unexpected indentation inside a sequence");
            }
            if (!line.content().startsWith("- ") && !line.content().equals("-")) break;
            cursor.next();
            String rest = line.content().equals("-") ? "" : line.content().substring(2).strip();
            if (rest.isEmpty()) {
                items.add(parseNestedOrNull(cursor, indent));
            } else if (isMappingEntry(rest)) {
                // `- key: value` starts a mapping whose first key sits on the dash line.
                items.add(parseInlineStartedMapping(cursor, indent, rest, line.number()));
            } else {
                items.add(scalar(rest, line.number()));
            }
        }
        return items;
    }

    private static Map<String, Object> parseMappingBlock(Cursor cursor, int indent) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        while (!cursor.atEnd()) {
            Line line = cursor.peek();
            if (line.indent() < indent) break;
            if (line.indent() > indent) {
                throw new YamlException("line " + line.number() + ": unexpected indentation inside a mapping");
            }
            if (line.content().startsWith("- ")) break;
            cursor.next();
            addEntry(mapping, cursor, indent, line.content(), line.number());
        }
        return mapping;
    }

    private static Map<String, Object> parseInlineStartedMapping(
            Cursor cursor, int indent, String firstEntry, int lineNumber) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        // The nested block of `- key:` is indented past the dash, not past the key.
        addEntry(mapping, cursor, indent, firstEntry, lineNumber);
        while (!cursor.atEnd()) {
            Line line = cursor.peek();
            if (line.indent() <= indent) break;
            cursor.next();
            addEntry(mapping, cursor, line.indent(), line.content(), line.number());
        }
        return mapping;
    }

    private static void addEntry(Map<String, Object> mapping, Cursor cursor, int indent,
                                 String content, int lineNumber) {
        int colon = findKeyColon(content, lineNumber);
        String key = unquote(content.substring(0, colon).strip(), lineNumber);
        String rest = content.substring(colon + 1).strip();
        if (mapping.containsKey(key)) {
            throw new YamlException("line " + lineNumber + ": duplicate key '" + key + "'");
        }
        if (rest.isEmpty()) {
            mapping.put(key, parseNestedOrNull(cursor, indent));
        } else if (rest.equals("|") || rest.equals(">") || rest.startsWith("|") || rest.startsWith(">")) {
            throw new YamlException("line " + lineNumber
                    + ": block scalars (| and >) are not supported; use a quoted single-line string");
        } else {
            mapping.put(key, scalar(rest, lineNumber));
        }
    }

    private static Object parseNestedOrNull(Cursor cursor, int indent) {
        if (cursor.atEnd()) return null;
        Line next = cursor.peek();
        if (next.indent() <= indent) return null;
        return parseBlock(cursor, next.indent());
    }

    private static boolean isMappingEntry(String content) {
        try {
            findKeyColon(content, 0);
            return true;
        } catch (YamlException e) {
            return false;
        }
    }

    /** The first `: ` (or trailing `:`) that is not inside quotes or a flow collection. */
    private static int findKeyColon(String content, int lineNumber) {
        boolean inSingle = false;
        boolean inDouble = false;
        int depth = 0;
        for (int index = 0; index < content.length(); index++) {
            char c = content.charAt(index);
            if (c == '\\' && inDouble) { index++; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (inSingle || inDouble) continue;
            if (c == '[' || c == '{') { depth++; continue; }
            if (c == ']' || c == '}') { depth--; continue; }
            if (c == ':' && depth == 0) {
                boolean isLast = index == content.length() - 1;
                if (isLast || content.charAt(index + 1) == ' ') return index;
            }
        }
        throw new YamlException("line " + lineNumber + ": expected 'key: value'");
    }

    // ---------------------------------------------------------------- scalars and flow

    private static Object scalar(String raw, int lineNumber) {
        String value = raw.strip();
        if (value.startsWith("[") || value.startsWith("{")) {
            Flow flow = new Flow(value, lineNumber);
            Object parsed = flow.readValue();
            flow.skipSpaces();
            if (!flow.atEnd()) {
                throw new YamlException("line " + lineNumber + ": trailing content after a flow collection");
            }
            return parsed;
        }
        return plainScalar(value, lineNumber);
    }

    private static Object plainScalar(String value, int lineNumber) {
        if (value.isEmpty() || value.equals("~") || value.equals("null")) return null;
        if (value.startsWith("'") || value.startsWith("\"")) return unquote(value, lineNumber);
        if (value.equals("true")) return Boolean.TRUE;
        if (value.equals("false")) return Boolean.FALSE;
        if (value.matches("-?\\d+")) {
            try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return value; }
        }
        if (value.matches("-?\\d*\\.\\d+([eE][-+]?\\d+)?")) {
            return Double.valueOf(value);
        }
        return value;
    }

    private static String unquote(String value, int lineNumber) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            StringBuilder builder = new StringBuilder();
            String body = value.substring(1, value.length() - 1);
            for (int index = 0; index < body.length(); index++) {
                char c = body.charAt(index);
                if (c != '\\') { builder.append(c); continue; }
                if (index + 1 >= body.length()) throw new YamlException("line " + lineNumber + ": dangling escape");
                char escape = body.charAt(++index);
                switch (escape) {
                    case 'n': builder.append('\n'); break;
                    case 't': builder.append('\t'); break;
                    case 'r': builder.append('\r'); break;
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    default: throw new YamlException("line " + lineNumber + ": unsupported escape '\\" + escape + "'");
                }
            }
            return builder.toString();
        }
        return value;
    }

    private static final class Flow {
        private final String text;
        private final int lineNumber;
        private int position;

        Flow(String text, int lineNumber) { this.text = text; this.lineNumber = lineNumber; }

        boolean atEnd() { return position >= text.length(); }

        void skipSpaces() {
            while (position < text.length() && text.charAt(position) == ' ') position++;
        }

        Object readValue() {
            skipSpaces();
            if (atEnd()) throw new YamlException("line " + lineNumber + ": unexpected end of flow collection");
            char c = text.charAt(position);
            if (c == '[') return readSequence();
            if (c == '{') return readMapping();
            return plainScalar(readFlowScalar(), lineNumber);
        }

        List<Object> readSequence() {
            position++;
            List<Object> items = new ArrayList<>();
            skipSpaces();
            if (!atEnd() && text.charAt(position) == ']') { position++; return items; }
            while (true) {
                items.add(readValue());
                skipSpaces();
                if (atEnd()) throw new YamlException("line " + lineNumber + ": unterminated flow sequence");
                char c = text.charAt(position++);
                if (c == ',') continue;
                if (c == ']') return items;
                throw new YamlException("line " + lineNumber + ": expected ',' or ']' in a flow sequence");
            }
        }

        Map<String, Object> readMapping() {
            position++;
            Map<String, Object> mapping = new LinkedHashMap<>();
            skipSpaces();
            if (!atEnd() && text.charAt(position) == '}') { position++; return mapping; }
            while (true) {
                skipSpaces();
                String key = String.valueOf(plainScalar(readFlowKey(), lineNumber));
                skipSpaces();
                if (atEnd() || text.charAt(position) != ':') {
                    throw new YamlException("line " + lineNumber + ": expected ':' in a flow mapping");
                }
                position++;
                mapping.put(key, readValue());
                skipSpaces();
                if (atEnd()) throw new YamlException("line " + lineNumber + ": unterminated flow mapping");
                char c = text.charAt(position++);
                if (c == ',') continue;
                if (c == '}') return mapping;
                throw new YamlException("line " + lineNumber + ": expected ',' or '}' in a flow mapping");
            }
        }

        String readFlowScalar() { return readUntil(",]}"); }
        String readFlowKey() { return readUntil(":,}"); }

        private String readUntil(String terminators) {
            skipSpaces();
            if (!atEnd() && (text.charAt(position) == '"' || text.charAt(position) == '\'')) {
                char quote = text.charAt(position++);
                StringBuilder builder = new StringBuilder();
                builder.append(quote);
                while (!atEnd()) {
                    char c = text.charAt(position++);
                    builder.append(c);
                    if (c == '\\' && quote == '"' && !atEnd()) { builder.append(text.charAt(position++)); continue; }
                    if (c == quote) return builder.toString();
                }
                throw new YamlException("line " + lineNumber + ": unterminated quoted scalar");
            }
            int start = position;
            while (!atEnd() && terminators.indexOf(text.charAt(position)) < 0) position++;
            return text.substring(start, position).strip();
        }
    }
}
