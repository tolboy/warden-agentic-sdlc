package dev.warden.execution;

import dev.warden.config.CodingAttestation;
import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Per-profile relay limits and the shield that hides Warden's run evidence from OpenCode. */
public final class OpenCodeLimitsTest implements Suite {
    @Override
    public String name() {
        return "opencode-limits";
    }

    @Override
    public void run(Check check) throws Exception {
        limits(check);
        shield(check);
    }

    private static String yaml(String limits) {
        return """
                version: 1
                profile: coder
                role: implementer
                runner: direct
                harness: opencode
                harness_executable: '/opt/opencode'
                connection: local
                model_vendor: z-ai
                model: z-ai/model-one
                read_only: false
                enforce_schema: true
                limits:
                  wall_clock_minutes: 12
                %s
                verification:
                  status: unverified
                """.formatted(limits);
    }

    private static void limits(Check check) throws Exception {
        Connection openrouter = new Connection("local", "openrouter", "openai_chat",
                "https://openrouter.ai/api/v1", "env:OPENROUTER_API_KEY", true);
        Connection gonka = new Connection("local", "gonkarouter", "openai_chat",
                "https://api.gonkarouter.io/v1", "env:GONKAROUTER_API_KEY", true);
        Path home = Path.of(".");
        Profile raised = Profile.parse(yaml("  max_output_tokens: 8192\n  max_requests: 30"), "raised")
                .withConnection(openrouter, home);
        Profile plain = Profile.parse(yaml("  max_output_tokens: 2048"), "plain").withConnection(openrouter, home);
        check.eq("a profile may raise relay turns", 30, CodingAttestation.requestLimit(raised));
        check.eq("a profile may raise relay output", 8192, CodingAttestation.outputLimit(raised));
        check.eq("without max_requests the old default of 8 holds", 8, CodingAttestation.requestLimit(plain));
        check.that("changing the turn limit changes the verified identity",
                !raised.apiFingerprint().equals(Profile.parse(
                        yaml("  max_output_tokens: 8192\n  max_requests: 29"), "other")
                        .withConnection(openrouter, home).apiFingerprint()));
        check.eq("an explicit default keeps an existing verification stamp",
                plain.apiFingerprint(),
                Profile.parse(yaml("  max_output_tokens: 2048\n  max_requests: 8"), "same")
                        .withConnection(openrouter, home).apiFingerprint());
        Profile gonkaRaised = Profile.parse(yaml("  max_output_tokens: 8192\n  max_requests: 30"), "g")
                .withConnection(gonka, home);
        check.eq("GonkaRouter output stays at its accepted 4096", 4096,
                CodingAttestation.outputLimit(gonkaRaised));
        check.eq("a wall clock override keeps the turn limit", 30,
                raised.withWallClockMinutes(3).maxRequests());
        refused(check, "turns above the ceiling are refused", yaml("  max_output_tokens: 2048\n  max_requests: 33"));
        refused(check, "zero turns are refused", yaml("  max_output_tokens: 2048\n  max_requests: 0"));
        refused(check, "max_requests outside a coding harness is refused", """
                version: 1
                profile: reviewer
                role: reviewer
                runner: api
                connection: local
                model_vendor: z-ai
                model: z-ai/model-one
                limits:
                  max_output_tokens: 2048
                  max_requests: 10
                """);
        try (var relay = new CodingRelay(openrouter, "z-ai/model-one", "s", Duration.ofMinutes(1), 30, 8192)) {
            check.that("the relay accepts the raised limits", relay.baseUrl() != null);
        }
        try (var relay = new CodingRelay(openrouter, "z-ai/model-one", "s", Duration.ofMinutes(1), 33, 8192)) {
            check.that("the relay refuses turns above its ceiling", false);
        } catch (IllegalArgumentException expected) {
            check.that("the relay refuses turns above its ceiling", true);
        }
        Map<String, Object> config = Json.parseObject(CodingProbe.config("http://127.0.0.1:1/v1", "m", 8192));
        Map<?, ?> model = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) config.get("provider"))
                .get("warden")).get("models")).get("m");
        check.eq("OpenCode plans for the relay's output limit", 8192L,
                ((Number) ((Map<?, ?>) model.get("limit")).get("output")).longValue());
    }

    private static void refused(Check check, String label, String yaml) {
        try {
            Profile.parse(yaml, "refused");
            check.that(label, false);
        } catch (RuntimeException expected) {
            check.that(label, true);
        }
    }

    /** OpenCode's evaluation: '*' matches any run of characters, the last matching rule wins. */
    private static Object lastMatch(Map<?, ?> rules, String path) {
        Object action = null;
        for (var rule : rules.entrySet()) {
            String regex = java.util.Arrays.stream(rule.getKey().toString().split("\\*", -1))
                    .map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
            if (path.matches(regex)) action = rule.getValue();
        }
        return action;
    }

    private static void shield(Check check) throws Exception {
        Path base = Files.createTempDirectory("warden-shield-").toRealPath();
        Path project = Files.createDirectories(base.resolve("project"));
        Path earlier = Files.createDirectories(project.resolve(".warden/runs/earlier"));
        Files.writeString(earlier.resolve("review.json"), "{\"verdict\":\"fail\"}");
        Path exchange = Files.createDirectories(project.resolve(".warden/runs/now/opencode-io-1"));
        Path home = Files.createDirectories(base.resolve("home/.warden"));

        Map<String, Object> rules = RunArtifactShield.permissions(project, exchange, home);
        check.eq("everything else stays denied first", "deny", rules.get("*"));
        List<?> read = List.copyOf(((Map<?, ?>) rules.get("read")).keySet());
        check.eq("reads of the run tree are denied", "deny",
                ((Map<?, ?>) rules.get("read")).get(".warden/runs/*"));
        check.eq("the exchange directory is allowed last, so it wins", ".warden/runs/now/opencode-io-1/*",
                read.get(read.size() - 1));
        Map<?, ?> readRules = (Map<?, ?>) rules.get("read");
        // OpenCode reports paths inside the project relative to it, others absolute.
        check.eq("the artifact can be written by its project-relative path", "allow",
                lastMatch(readRules, ".warden/runs/now/opencode-io-1/harness-artifact.json"));
        check.eq("the artifact can be written by its absolute path", "allow",
                lastMatch(readRules, exchange + "/harness-artifact.json"));
        check.eq("an earlier run stays unreadable by relative path", "deny",
                lastMatch(readRules, ".warden/runs/earlier/review.json"));
        check.eq("an earlier run stays unreadable by absolute path", "deny",
                lastMatch(readRules, project + "/.warden/runs/earlier/review.json"));
        check.eq("this run's own records outside the exchange stay unreadable", "deny",
                lastMatch(readRules, ".warden/runs/now/review.json"));
        check.eq("project files stay readable", "allow", lastMatch(readRules, "serve.mjs"));
        check.eq("edits follow the same rules", rules.get("read"), rules.get("edit"));
        check.eq("shell commands naming the run tree are denied", "deny",
                ((Map<?, ?>) rules.get("bash")).get("*.warden/runs*"));
        String config = CodingProbe.config("http://127.0.0.1:1/v1", "m", 2048, rules);
        check.that("the dispatch config is accepted with its own rules",
                CodingProbe.validConfig(config, "http://127.0.0.1:1/v1", "m", rules));
        check.that("the probe's rules cannot stand in for the dispatch's",
                !CodingProbe.validConfig(CodingProbe.config("http://127.0.0.1:1/v1", "m", 2048),
                        "http://127.0.0.1:1/v1", "m", rules));

        RunArtifactShield plan = RunArtifactShield.plan(project, home, exchange, List.of(), true);
        check.that("the run tree is hidden", plan.hiddenPaths().contains(project.resolve(".warden/runs").toString()));
        check.that("the Warden home is hidden", plan.hiddenPaths().contains(home.toString()));
        RunArtifactShield around = RunArtifactShield.plan(project, base, exchange, List.of(), true);
        check.that("a home that contains the project is never blanked out",
                !around.hiddenPaths().contains(base.toString()));
        RunArtifactShield rulesOnly = RunArtifactShield.plan(project, home, exchange, List.of(), false);
        check.eq("without a sandbox the command is unchanged", List.of("opencode", "run"),
                rulesOnly.wrap(List.of("opencode", "run")));
        check.eq("without a sandbox the rules still apply", "permission_rules", rulesOnly.mode());

        if (RunArtifactShield.bubblewrapWorks()) {
            check.eq("the OS enforces the shield where bubblewrap works", "mount_namespace", plan.mode());
            Path marker = exchange.resolve("written.txt");
            var result = new dev.warden.process.ProcessRunner().run(plan.wrap(List.of("sh", "-c",
                    "ls .warden/runs/earlier 2>/dev/null; cat .warden/runs/earlier/review.json 2>/dev/null;"
                            + " ls " + home + " | wc -l; echo ok > " + marker)),
                    project, Duration.ofSeconds(20));
            check.that("a shell cannot read an earlier run's evidence",
                    !result.stdout().contains("verdict") && !result.stdout().contains("review.json"));
            check.that("the exchange directory stays writable", Files.exists(marker));
            check.that("the real evidence is untouched", Files.exists(earlier.resolve("review.json")));
        }
    }
}
