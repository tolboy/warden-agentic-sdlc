package dev.warden;

import dev.warden.json.Json;
import dev.warden.json.Schema;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;
import java.util.Map;

public final class SchemaTest implements Suite {
    @Override public String name() { return "schema"; }

    @Override public void run(Check check) {
        Object reviewer = Json.parse("""
                {
                  "type": "object",
                  "required": ["role", "verdict", "findings"],
                  "properties": {
                    "role": { "const": "reviewer" },
                    "verdict": { "enum": ["pass", "fail"] },
                    "findings": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["severity", "path", "expected", "actual"],
                        "properties": {
                          "severity": { "enum": ["P1", "P2", "P3"] },
                          "path": { "type": "string" },
                          "line": { "type": "integer" },
                          "expected": { "type": "string" },
                          "actual": { "type": "string" }
                        }
                      }
                    }
                  }
                }
                """);

        Map<String, Object> good = Json.parseObject("""
                {"role":"reviewer","verdict":"fail","findings":[
                  {"severity":"P1","path":"src/a.txt","expected":"ok","actual":"no","line":3}]}
                """);
        check.eq("a matching artifact has no errors", List.of(), Schema.validate(good, reviewer));

        check.that("wrong const is refused",
                !Schema.validate(Json.parseObject("{\"role\":\"implementer\",\"verdict\":\"pass\",\"findings\":[]}"),
                        reviewer).isEmpty());
        check.that("wrong enum is refused",
                !Schema.validate(Json.parseObject("{\"role\":\"reviewer\",\"verdict\":\"ok\",\"findings\":[]}"),
                        reviewer).isEmpty());
        check.that("missing required field is refused",
                Schema.validate(Json.parseObject("{\"role\":\"reviewer\",\"verdict\":\"pass\"}"), reviewer)
                        .stream().anyMatch(error -> error.contains("findings")));
        check.that("a finding missing expected/actual is refused",
                !Schema.validate(Json.parseObject("""
                        {"role":"reviewer","verdict":"fail","findings":[{"severity":"P1","path":"x"}]}
                        """), reviewer).isEmpty());
        check.that("a non-integer line is refused",
                !Schema.validate(Json.parseObject("""
                        {"role":"reviewer","verdict":"pass","findings":[
                          {"severity":"P2","path":"x","expected":"a","actual":"b","line":"3"}]}
                        """), reviewer).isEmpty());

        Object closed = Json.parse("""
                {"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":false}
                """);
        check.that("additionalProperties:false refuses unknown keys",
                Schema.validate(Json.parseObject("{\"a\":\"x\",\"b\":1}"), closed)
                        .stream().anyMatch(error -> error.contains("unsupported field 'b'")));
        check.eq("and allows the declared keys", List.of(),
                Schema.validate(Json.parseObject("{\"a\":\"x\"}"), closed));

        Object bounded = Json.parse("{\"type\":\"string\",\"maxLength\":3}");
        check.that("maxLength is enforced",
                !Schema.validate("abcd", bounded).isEmpty());
        check.eq("unknown keywords are ignored, not guessed at", List.of(),
                Schema.validate("ok", Json.parse("{\"type\":\"string\",\"format\":\"email\"}")));
    }
}
