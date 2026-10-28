package dev.warden;

import dev.warden.config.*;
import dev.warden.testing.*;
import java.nio.file.*;
import java.util.*;

/** Migration previews, binds only what is safe or mapped, is idempotent, and rolls back without clobbering. */
public final class ServiceMigrationTest implements Suite {
    public String name() { return "service-migration"; }

    public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-service-migration-");
        Files.createDirectories(home.resolve("profiles")); Files.createDirectories(home.resolve("vendors"));
        write(home, "codex-plain", cli("codex-plain", "codex", "gpt-6.1-sol", "[exec, -m, \"{{model}}\"]") + "verification:\n  verified_on: \"2026-10-21\"\n");
        Files.writeString(home.resolve("profiles/grok-crlf.yaml"), cli("grok-crlf", "grok", "grok-4.7", "[-m, \"{{model}}\"]").replace("\n", "\r\n"));
        write(home, "codex-selected", cli("codex-selected", "codex", "gpt-6.1-sol", "[exec, --profile, warden-read]"));
        write(home, "deepseek-cli", cli("deepseek-cli", "codex", "deepseek/deepseek-v4.1-flash-1017", "[exec, --profile, deepseek]").replace("vendor: codex", "vendor: deepseek"));
        new ConnectionStore(home).add(new Connection("stub", "custom", "openai_chat", "http://127.0.0.1:9/v1", "env:STUB_KEY", true));
        write(home, "api-one", "version: 1\nprofile: api-one\nrole: reviewer\nrunner: api\nconnection: stub\nmodel_vendor: deepseek\nmodel: m\nread_only: true\n");
        Files.writeString(home.resolve("vendors/cli-provider-deepseek-catalog.json"), "{\"models\":[]}");
        Files.writeString(home.resolve("vendors/cli-provider-openai-catalog.json"), "{\"models\":[]}");
        var original = snapshot(home);
        var migration = new ServiceMigration(home);

        var preview = migration.preview(List.of());
        check.eq("preview writes nothing", original, snapshot(home));
        check.eq("preview lists safe bindings", List.of("profiles/codex-plain.yaml", "profiles/grok-crlf.yaml"), preview.get("writes"));
        check.eq("preview lists profiles needing a mapping", List.of("codex-selected", "deepseek-cli"), preview.get("needs_mapping"));
        check.eq("preview flags the developer-named CLI cache", List.of("vendors/cli-provider-deepseek-catalog.json"), preview.get("stale_vendor_caches"));
        check.eq("a DeepSeek-through-Codex profile gets no suggestion", null, row(preview, "deepseek-cli").get("suggested"));
        check.eq("a --profile Codex profile gets the client's service as a suggestion only", "openai", row(preview, "codex-selected").get("suggested"));
        check.eq("API profiles are left to their connection", "unchanged", row(preview, "api-one").get("action"));

        check.rejects("a mapping for an unknown profile is refused", "unknown profile", () -> migration.apply(List.of(ServiceMigration.Mapping.parse("ghost=openai"))));
        check.rejects("a mapping for an API profile is refused", "connection", () -> migration.apply(List.of(ServiceMigration.Mapping.parse("api-one=openai"))));
        check.rejects("a client is not accepted as a service", "service must name", () -> migration.apply(List.of(ServiceMigration.Mapping.parse("deepseek-cli=codex"))));
        check.eq("refused mappings write nothing", original, snapshot(home));

        var maps = List.of(ServiceMigration.Mapping.parse("codex-selected=openai:warden-read"));
        var applied = migration.apply(maps);
        String backup = String.valueOf(applied.get("backup"));
        check.that("apply records a backup", Files.isRegularFile(home.resolve("backups").resolve(backup).resolve("manifest.json")));
        var user = UserConfig.load(home);
        check.eq("inferred profile is bound explicitly", "openai", ServiceBinding.of(user.profiles().get("codex-plain")).service());
        check.eq("binding is now explicit", ServiceBinding.EXPLICIT, ServiceBinding.of(user.profiles().get("codex-plain")).source());
        check.eq("mapped profile keeps its account", "warden-read", ServiceBinding.of(user.profiles().get("codex-selected")).account());
        check.that("unmapped ambiguous profile is untouched", ServiceBinding.of(user.profiles().get("deepseek-cli")).needsMapping());
        check.eq("ambiguous file is byte-identical", original.get("profiles/deepseek-cli.yaml"), snapshot(home).get("profiles/deepseek-cli.yaml"));
        check.that("verification receipt survives a binding", user.profiles().get("codex-plain").verified());
        check.that("CRLF files keep CRLF", Files.readString(home.resolve("profiles/grok-crlf.yaml")).contains("service: x-ai\r\naccount: default\r\n"));
        check.that("stale developer cache is moved out", !Files.exists(home.resolve("vendors/cli-provider-deepseek-catalog.json")));
        check.that("live service cache stays", Files.exists(home.resolve("vendors/cli-provider-openai-catalog.json")));
        check.eq("every profile still parses", Map.of(), user.problems());

        var afterApply = snapshot(home);
        var again = migration.apply(maps);
        check.eq("re-run reports already migrated", "already_migrated", again.get("code"));
        check.eq("re-run writes nothing", afterApply, snapshot(home));
        check.eq("re-run creates no backup", 1L, backups(home));
        check.rejects("a conflicting re-map is refused", "already bound", () -> migration.apply(List.of(ServiceMigration.Mapping.parse("codex-selected=openai:other"))));

        var rolledBack = migration.rollback(backup);
        check.eq("rollback succeeds", true, rolledBack.get("ok"));
        check.eq("rollback restores every byte, including moved caches", original, snapshot(home));
        check.eq("rollback is not repeated", "already_rolled_back", migration.rollback(backup).get("code"));

        var second = migration.apply(maps);
        Files.writeString(home.resolve("profiles/codex-plain.yaml"), Files.readString(home.resolve("profiles/codex-plain.yaml")) + "notes: edited later\n");
        var edited = snapshot(home);
        var refused = migration.rollback(String.valueOf(second.get("backup")));
        check.eq("rollback refuses to clobber later edits", "changed_since_migration", refused.get("code"));
        check.eq("refused rollback changes nothing", edited, snapshot(home));
        check.rejects("rollback ids cannot escape backups", "unknown migration backup", () -> migration.rollback("../profiles"));

        Path codexHome = Files.createTempDirectory("warden-codex-home-");
        Files.writeString(codexHome.resolve("config.toml"), "[profiles.deepseek]\nmodel_provider = \"openrouter\"\n[profiles.warden-read]\nmodel = \"gpt\"\n");
        byte[] codexBefore = Files.readAllBytes(codexHome.resolve("config.toml"));
        Path fresh = Files.createTempDirectory("warden-service-migration-codex-");
        Files.createDirectories(fresh.resolve("profiles"));
        write(fresh, "deepseek-cli", cli("deepseek-cli", "codex", "deepseek/deepseek-v4.1-flash-1017", "[exec, --profile, deepseek]").replace("vendor: codex", "vendor: deepseek"));
        write(fresh, "codex-selected", cli("codex-selected", "codex", "gpt-6.1-sol", "[exec, --profile, warden-read]"));
        var withCodex = new ServiceMigration(fresh, CodexConfig.load(codexHome.resolve("config.toml")));
        var codexPreview = withCodex.preview(List.of());
        check.eq("config.toml binds the Codex profile it names", "bind_codex_config", row(codexPreview, "deepseek-cli").get("action"));
        check.eq("the preview records where the binding came from", "codex config.toml profile deepseek", row(codexPreview, "deepseek-cli").get("binding_source"));
        check.eq("a Codex profile without an explicit provider still needs a mapping", List.of("codex-selected"), codexPreview.get("needs_mapping"));
        check.eq("its reason says why", "codex_profile_has_no_model_provider", row(codexPreview, "codex-selected").get("reason"));
        withCodex.apply(List.of(ServiceMigration.Mapping.parse("codex-selected=openai:warden-read")));
        var codexUser = UserConfig.load(fresh);
        check.eq("the applied binding is written explicitly", "openrouter:deepseek",
                codexUser.profiles().get("deepseek-cli").cliService().service() + ":" + codexUser.profiles().get("deepseek-cli").cliService().account());
        check.that("Codex config.toml is never written", java.util.Arrays.equals(codexBefore, Files.readAllBytes(codexHome.resolve("config.toml"))));
    }

    private static Map<String, Object> row(Map<String, Object> report, String name) {
        for (Object row : (List<?>) report.get("profiles")) if (row instanceof Map<?, ?> map && name.equals(map.get("profile"))) {
            @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map; return typed;
        }
        throw new AssertionError(name);
    }

    private static long backups(Path home) throws Exception {
        try (var list = Files.list(home.resolve("backups"))) { return list.filter(p -> p.getFileName().toString().startsWith("service-bindings-")).count(); }
    }

    private static Map<String, String> snapshot(Path home) throws Exception {
        var result = new TreeMap<String, String>();
        for (String dir : List.of("profiles", "vendors")) try (var files = Files.list(home.resolve(dir))) {
            for (Path file : files.toList()) result.put(dir + "/" + file.getFileName(), HexFormat.of().formatHex(Files.readAllBytes(file)));
        }
        return result;
    }

    private static void write(Path home, String name, String yaml) throws Exception { Files.writeString(home.resolve("profiles/" + name + ".yaml"), yaml); }

    private static String cli(String name, String command, String model, String args) {
        return "version: 1\nprofile: " + name + "\nrole: reviewer\nvendor: " + (command.equals("grok") ? "grok" : "codex") + "\nmodel: " + model
                + "\ncommand: " + command + "\nrunner: direct\nread_only: true\nargs: " + args + "\n";
    }
}
