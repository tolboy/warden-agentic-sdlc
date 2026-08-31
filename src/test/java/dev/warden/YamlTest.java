package dev.warden;

import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import dev.warden.yaml.Yaml;

import java.util.List;
import java.util.Map;

public final class YamlTest implements Suite {

    @Override public String name() { return "yaml"; }

    @Override public void run(Check check) {
        Map<String, Object> simple = Yaml.parseMapping("""
                version: 1
                project: example-app
                base_ref: origin/main
                """);
        check.eq("integer scalar", Long.valueOf(1), simple.get("version"));
        check.eq("plain scalar", "example-app", simple.get("project"));
        check.eq("scalar with a slash is not split", "origin/main", simple.get("base_ref"));

        Map<String, Object> nested = Yaml.parseMapping("""
                defaults:
                  risk: medium
                  max_fix_attempts: 2
                  review: true
                """);
        Map<?, ?> defaults = (Map<?, ?>) nested.get("defaults");
        check.eq("nested mapping", "medium", defaults.get("risk"));
        check.eq("nested integer", Long.valueOf(2), defaults.get("max_fix_attempts"));
        check.eq("nested boolean", Boolean.TRUE, defaults.get("review"));

        Map<String, Object> sequences = Yaml.parseMapping("""
                checks:
                  fast:
                    - npm run check
                  full:
                    - npm run check
                    - npm run build
                """);
        Map<?, ?> checks = (Map<?, ?>) sequences.get("checks");
        check.eq("block sequence", List.of("npm run check", "npm run build"), checks.get("full"));
        check.eq("single-item sequence", List.of("npm run check"), checks.get("fast"));

        Map<String, Object> flow = Yaml.parseMapping("""
                paths: ["src", "docs"]
                limits: { wall_clock_minutes: 20, turns: 12 }
                empty_list: []
                empty_map: {}
                """);
        check.eq("flow sequence", List.of("src", "docs"), flow.get("paths"));
        check.eq("flow mapping value", Long.valueOf(20),
                ((Map<?, ?>) flow.get("limits")).get("wall_clock_minutes"));
        check.eq("empty flow sequence", List.of(), flow.get("empty_list"));
        check.eq("empty flow mapping", Map.of(), flow.get("empty_map"));

        Map<String, Object> comments = Yaml.parseMapping("""
                # a leading comment
                risk: high      # trailing comment
                note: "a # inside quotes is not a comment"
                url: 'single # quoted'
                """);
        check.eq("trailing comment stripped", "high", comments.get("risk"));
        check.eq("hash inside double quotes kept", "a # inside quotes is not a comment", comments.get("note"));
        check.eq("hash inside single quotes kept", "single # quoted", comments.get("url"));

        Map<String, Object> quoting = Yaml.parseMapping("""
                a: "line\\nbreak"
                b: 'it''s quoted'
                c: "colon: inside"
                d: null
                e: ~
                f:
                """);
        check.eq("double-quoted escape", "line\nbreak", quoting.get("a"));
        check.eq("single-quoted doubling", "it's quoted", quoting.get("b"));
        check.eq("colon inside quotes", "colon: inside", quoting.get("c"));
        check.eq("explicit null", null, quoting.get("d"));
        check.eq("tilde null", null, quoting.get("e"));
        check.eq("empty value is null", null, quoting.get("f"));

        List<?> profiles = (List<?>) Yaml.parseMapping("""
                roles:
                  - name: reviewer
                    strategy: rotate
                  - name: implementer
                    strategy: first
                """).get("roles");
        check.eq("sequence of mappings", 2, profiles.size());
        check.eq("first entry key", "reviewer", ((Map<?, ?>) profiles.get(0)).get("name"));
        check.eq("second entry value", "first", ((Map<?, ?>) profiles.get(1)).get("strategy"));

        Map<String, Object> deep = Yaml.parseMapping("""
                policy:
                  roles:
                    reviewer:
                      profiles: [grok-review, claude-review]
                      require_independent_vendor: true
                """);
        Map<?, ?> reviewer = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) deep.get("policy")).get("roles")).get("reviewer");
        check.eq("three levels deep", List.of("grok-review", "claude-review"), reviewer.get("profiles"));
        check.eq("deep boolean", Boolean.TRUE, reviewer.get("require_independent_vendor"));

        check.eq("leading document marker allowed", "x",
                Yaml.parseMapping("---\na: x\n").get("a"));

        // Strictness. Anything the parser does not fully understand must be an error naming
        // the line, because this file decides who may write to a repository.
        check.rejects("tabs rejected", "tabs cannot be used", () -> Yaml.parse("a:\n\tb: 1\n"));
        check.rejects("anchors rejected", "anchors and aliases", () -> Yaml.parse("a: 1\n&anchor\n"));
        check.rejects("merge keys rejected", "merge keys", () -> Yaml.parse("<<: base\n"));
        check.rejects("tags rejected", "tags are not supported", () -> Yaml.parse("!custom\n"));
        check.rejects("block scalars rejected", "block scalars", () -> Yaml.parse("a: |\n  text\n"));
        check.rejects("multiple documents rejected", "multiple YAML documents",
                () -> Yaml.parse("---\na: 1\n---\nb: 2\n"));
        check.rejects("duplicate keys rejected", "duplicate key", () -> Yaml.parse("a: 1\na: 2\n"));
        check.rejects("unterminated quote rejected", "unterminated", () -> Yaml.parse("a: \"open\n"));
        check.rejects("bad indentation rejected", "indentation", () -> Yaml.parse("a: 1\n  b: 2\n"));
        check.rejects("missing colon rejected", "expected 'key: value'", () -> Yaml.parse("just text\n"));
        check.rejects("top level must be a mapping", "expected a mapping",
                () -> Yaml.parseMapping("- one\n- two\n"));

        // A flow collection split across lines is a real thing people write. It is refused
        // with a message rather than half-parsed: a profile whose args list is silently
        // truncated would run a vendor with the wrong flags.
        check.rejects("multi-line flow sequence refused", "unexpected end of flow collection",
                () -> Yaml.parse("args: [\"a\",\n       \"b\"]\n"));
        check.eq("the block form is the supported way to write a long list",
                List.of("a", "b"),
                Yaml.parseMapping("args:\n  - \"a\"\n  - \"b\"\n").get("args"));

        check.eq("empty document is an empty mapping", Map.of(), Yaml.parse("# only a comment\n"));
    }
}
