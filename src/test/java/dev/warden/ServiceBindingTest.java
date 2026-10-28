package dev.warden;

import dev.warden.config.CodexConfig;
import dev.warden.config.Profile;
import dev.warden.config.ServiceBinding;
import dev.warden.testing.*;
import java.nio.file.Files;
import java.util.List;

/** A CLI profile's service is stated or safely inferred; it is never guessed from the model developer. */
public final class ServiceBindingTest implements Suite {
    public String name() { return "service-binding"; }

    public void run(Check check) throws Exception {
        var plain = binding(cli("codex", "codex", "gpt-6.1-sol", "direct", "[exec, -, -m, \"{{model}}\"]", ""));
        check.eq("plain Codex with an OpenAI model is inferred as OpenAI", "openai", plain.service());
        check.eq("inferred binding is marked as inferred", ServiceBinding.INFERRED, plain.source());
        check.eq("inferred account is the default login", "default", plain.account());

        var unrelatedOverride = binding(cli("codex", "codex", "gpt-6-astra", "direct",
                "[exec, -c, \"shell_environment_policy.set.GIT_CONFIG_GLOBAL=x\", -m, \"{{model}}\"]", ""));
        check.eq("a harmless -c override does not hide the service", "openai", unrelatedOverride.service());

        var orca = binding(cli("codex", "codex", "gpt-6.1-sol", "orca", null, ""));
        check.eq("Orca is an executor, not a service", "openai", orca.service());

        var deepseek = binding(cli("deepseek", "codex", "deepseek/deepseek-v4.1-flash-1017", "direct",
                "[exec, --profile, deepseek, -m, \"{{model}}\"]", ""));
        check.that("DeepSeek through a Codex profile needs a mapping", deepseek.needsMapping());
        check.eq("its service is not invented", null, deepseek.service());
        check.eq("and not even suggested from the model developer", null, deepseek.suggested());

        var selected = binding(cli("codex", "codex", "gpt-6.1-sol", "direct", "[exec, --profile, warden-read, -m, \"{{model}}\"]", ""));
        check.that("a Codex --profile can redirect the provider, so it needs a mapping", selected.needsMapping());
        check.eq("the client's own service is offered only as a suggestion", "openai", selected.suggested());
        check.eq("the reason is explicit", "provider_selector_in_args", selected.reason());

        for (String args : List.of("[exec, -p, other]", "[exec, \"--profile=other\"]", "[exec, -c, \"model_provider=deepseek\"]",
                "[exec, -c, \"model_providers.x.base_url=http://h\"]", "[exec, --oss]", "[exec, \"--config=profile=other\"]"))
            check.that("provider selector " + args + " blocks inference",
                    binding(cli("codex", "codex", "gpt-6.1-sol", "direct", args, "")).needsMapping());

        check.eq("Grok CLI is xAI", "x-ai", binding(cli("grok", "grok", "grok-4.7", "direct", "[-m, \"{{model}}\"]", "")).service());
        check.eq("Antigravity serves several developers under one login", "antigravity",
                binding(cli("codex", "agy", "gpt-oss-120b-medium", "direct", "[\"-p={{prompt}}\"]", "")).service());
        check.that("an unknown client is never attributed to its model developer",
                binding(cli("openai", "my-wrapper", "gpt-6.1-sol", "direct", "[]", "")).needsMapping());

        var explicit = binding(cli("deepseek", "codex", "deepseek/x", "direct", "[exec, --profile, deepseek]", "service: openrouter\naccount: personal\n"));
        check.eq("an explicit service wins", "openrouter", explicit.service());
        check.eq("an explicit account is kept", "personal", explicit.account());
        check.eq("explicit source is reported", ServiceBinding.EXPLICIT, explicit.source());
        check.eq("explicit binding without account uses default", "default",
                binding(cli("codex", "codex", "gpt-6.1-sol", "direct", "[exec]", "service: openai\n")).account());

        check.rejects("a client is not a service", "service must name", () -> Profile.parse(cli("codex", "codex", "m", "direct", "[exec]", "service: codex\n"), "f"));
        check.rejects("an alias is not canonical", "service must name", () -> Profile.parse(cli("grok", "grok", "m", "direct", "[]", "service: xai\n"), "f"));
        check.rejects("account requires service", "account requires service", () -> Profile.parse(cli("codex", "codex", "m", "direct", "[exec]", "account: work\n"), "f"));
        check.rejects("account must be a label", "account must be", () -> Profile.parse(cli("codex", "codex", "m", "direct", "[exec]", "service: openai\naccount: \"Work Login\"\n"), "f"));
        check.rejects("API profiles use their connection", "service and account apply to CLI profiles", () -> Profile.parse(
                "version: 1\nprofile: p\nrole: reviewer\nrunner: api\nconnection: c\nmodel_vendor: deepseek\nmodel: m\nservice: openai\nread_only: true\n", "f"));

        var bound = Profile.parse(cli("codex", "codex", "gpt-6.1-sol", "direct", "[exec]", "service: openai\naccount: work\n"), "f");
        check.eq("Orca hosting keeps the binding", "work", ServiceBinding.of(bound.hostedOnOrca()).account());
        check.eq("an effort change keeps the binding", "work", ServiceBinding.of(bound.withEffort("high")).account());
        check.eq("review independence still uses the model developer", "openai", bound.independenceVendor());

        var dir = Files.createTempDirectory("warden-codex-config-");
        var toml = dir.resolve("config.toml");
        Files.writeString(toml, String.join("\n",
                "model = \"gpt-6.1-sol\"", "model_provider = \"openai\"   # top level is never inherited",
                "[model_providers.openrouter]", "model_provider = \"ignored\"",
                "[profiles.deepseek]", "model = \"deepseek/x\"", "model_provider = \"OpenRouter\" # comment",
                "[profiles.\"warden-read\"]", "model = \"gpt-6.1-sol\"",
                "[profiles.twice]", "model_provider = \"openai\"", "model_provider = \"openrouter\"",
                "[profiles.client]", "model_provider = \"codex\"",
                "[profiles.deepseek.extra]", "model_provider = \"x-ai\"", ""));
        Files.writeString(dir.resolve("auth.json"), "{\"must\":\"never be read\"}");
        var codex = CodexConfig.load(toml);
        check.eq("config.toml keeps only explicit per-profile providers", java.util.Map.of("deepseek", "openrouter", "client", "codex"), codex.providers());
        check.that("a profile without model_provider is still known", codex.profiles().contains("warden-read"));
        var viaConfig = ServiceBinding.of(Profile.parse(cli("deepseek", "codex", "deepseek/x", "direct", "[exec, --profile, deepseek, -m, \"{{model}}\"]", ""), "f"), codex);
        check.eq("an explicit Codex profile provider becomes the service", "openrouter", viaConfig.service());
        check.eq("the Codex profile name is the account", "deepseek", viaConfig.account());
        check.eq("the source is recorded as Codex config", ServiceBinding.CODEX_CONFIG, viaConfig.source());
        check.eq("the origin names the file and profile", "codex config.toml profile deepseek", viaConfig.reason());
        check.eq("--profile=NAME is read the same way", "openrouter",
                codexBinding(codex, "[exec, \"--profile=deepseek\"]").service());
        check.eq("no explicit provider keeps it unmapped", "codex_profile_has_no_model_provider", codexBinding(codex, "[exec, --profile, warden-read]").reason());
        check.eq("a profile absent from config.toml stays unmapped", "codex_profile_not_in_config", codexBinding(codex, "[exec, --profile, ghost]").reason());
        check.eq("a provider set twice with different values conflicts", "codex_profile_has_no_model_provider", codexBinding(codex, "[exec, --profile, twice]").reason());
        check.eq("a client name is not accepted as a provider", "codex_model_provider_names_a_client", codexBinding(codex, "[exec, --profile, client]").reason());
        check.eq("another selector next to --profile conflicts", "codex_profile_and_other_selector",
                codexBinding(codex, "[exec, --profile, deepseek, -c, \"model_provider=openai\"]").reason());
        check.eq("two --profile flags conflict", "codex_profile_ambiguous", codexBinding(codex, "[exec, --profile, deepseek, -p, warden-read]").reason());
        check.that("a missing config.toml changes nothing", codexBinding(CodexConfig.load(dir.resolve("absent.toml")), "[exec, --profile, deepseek]").needsMapping());
        check.eq("plain profiles ignore config.toml", ServiceBinding.INFERRED, ServiceBinding.of(Profile.parse(cli("codex", "codex", "gpt-6.1-sol", "direct", "[exec]", ""), "f"), codex).source());
        check.eq("an explicit binding beats config.toml", "openai", ServiceBinding.of(Profile.parse(
                cli("deepseek", "codex", "deepseek/x", "direct", "[exec, --profile, deepseek]", "service: openai\n"), "f"), codex).service());
    }

    private static ServiceBinding codexBinding(CodexConfig codex, String args) {
        return ServiceBinding.of(Profile.parse(cli("codex", "codex", "gpt-6.1-sol", "direct", args, ""), "f"), codex);
    }

    private static ServiceBinding binding(String yaml) { return ServiceBinding.of(Profile.parse(yaml, "fixture")); }

    private static String cli(String vendor, String command, String model, String runner, String args, String extra) {
        return "version: 1\nprofile: p\nrole: reviewer\nvendor: " + vendor + "\n" + extra + "model: " + model
                + "\ncommand: " + command + "\nrunner: " + runner + "\nread_only: true\n" + (args == null ? "" : "args: " + args + "\n");
    }
}
