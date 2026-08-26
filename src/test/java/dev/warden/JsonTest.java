package dev.warden;

import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;
import java.util.Map;

public final class JsonTest implements Suite {

    @Override public String name() { return "json"; }

    @Override public void run(Check check) {
        check.eq("object round trip", "{\"a\":1}", Json.write(Json.parse("{\"a\": 1}")));
        check.eq("nested access", "b", ((Map<?, ?>) Json.parse("{\"a\":{\"x\":\"b\"}}")).get("a") instanceof Map<?, ?> m ? m.get("x") : null);
        check.eq("array", List.of(1L, 2L, 3L), Json.parse("[1,2,3]"));
        check.eq("string escapes", "a\"b\\c\nd", Json.parse("\"a\\\"b\\\\c\\nd\""));
        check.eq("unicode escape", "\u00e9", Json.parse("\"\\u00e9\""));
        check.eq("integers stay integers", Long.valueOf(42), Json.parse("42"));
        check.eq("floats stay floats", Double.valueOf(0.5), Json.parse("0.5"));
        check.eq("booleans", Boolean.TRUE, Json.parse("true"));
        check.eq("null", null, Json.parse("null"));
        check.eq("key order preserved", "{\"z\":1,\"a\":2}", Json.write(Json.parse("{\"z\":1,\"a\":2}")));

        // Strictness: a parser that guesses would let malformed vendor output through.
        check.rejects("trailing content", "trailing", () -> Json.parse("{} {}"));
        check.rejects("unterminated string", "unterminated", () -> Json.parse("\"abc"));
        check.rejects("raw control character", "control character", () -> Json.parse("\"a\nb\""));
        check.rejects("unknown escape", "unknown escape", () -> Json.parse("\"a\\qb\""));
        check.rejects("missing comma", "expected", () -> Json.parse("{\"a\":1 \"b\":2}"));
        check.rejects("non-string key", "must be a string", () -> Json.parse("{a:1}"));
        check.rejects("not an object", "expected a JSON object", () -> Json.parseObject("[1]"));

        // Vendor envelopes: the answer is rarely the whole of stdout.
        String chatty = "starting up\nthinking...\n{\"verdict\":\"fail\",\"n\":2}\n";
        check.eq("last object on its own line", "fail", Json.findLastObject(chatty).get("verdict"));
        String embedded = "prefix {\"a\":{\"b\":1}} suffix";
        check.eq("outermost braces fallback", 1L,
                ((Map<?, ?>) Json.findLastObject(embedded).get("a")).get("b"));
        check.eq("nothing parseable yields null", null, Json.findLastObject("no json here"));
        check.eq("empty output yields null", null, Json.findLastObject("   "));

        String pretty = Json.writePretty(Json.parse("{\"a\":[1,2],\"b\":{}}"));
        check.contains("pretty printing indents with tabs", pretty, "\n\t\"a\"");
        check.eq("pretty output re-parses", Json.parse("{\"a\":[1,2],\"b\":{}}"), Json.parse(pretty));
    }
}
