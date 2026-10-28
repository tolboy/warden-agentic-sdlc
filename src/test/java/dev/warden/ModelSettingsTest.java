package dev.warden;

import dev.warden.config.*;
import dev.warden.dashboard.Dashboard;
import dev.warden.execution.ModelCheck;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModelSettingsTest implements Suite {
    @Override public String name() { return "model-settings"; }
    @Override public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-model-settings-");
        new UserSetup().run(home);
        Path primary = home.resolve("profiles/primary.yaml");
        Path reserve = home.resolve("profiles/reserve.yaml");
        Files.writeString(primary, profile("primary")); Files.writeString(reserve, profile("reserve"));
        Files.writeString(home.resolve("policy.yaml"), "version: 1\nroles:\n  reviewer:\n    profiles: [primary]\n    strategy: first\n    require_independent_vendor: true\nfailover:\n  on_quota_exhausted: confirm\n");
        AtomicInteger pings = new AtomicInteger();
        ModelCheck probes = new ModelCheck(new ProcessRunner(), ignored -> null, (p,u,d,s) -> {
            pings.incrementAndGet(); return Map.of("ok", true, "code", "pong");
        });
        ModelSettingsService service = new ModelSettingsService(home, home, probes);
        var settings = service.list();
        check.eq("opening settings sends no inference", 0, pings.get());
        check.eq("central launch settings default enabled", true, ((Map<?, ?>) settings.get("check")).get("before_run"));
        var checked = service.apply("check", Map.of("profiles", List.of("primary")));
        check.eq("explicit action pings one profile", 1, pings.get());
        check.eq("explicit check returns receipt", true, checked.report().get("ok"));
        service.apply("check", Map.of("profiles", List.of("reserve")));
        var states = (Map<?, ?>) service.list().get("check_states");
        check.that("checking one profile retains other profiles' last answers", states.containsKey("primary") && states.containsKey("reserve"));
        check.that("pong never grants role verification", !Files.readString(primary).contains("connection_fingerprint"));
        String original = Files.readString(primary);
        check.eq("a model field cannot inject a verification shell command", 400,
                service.apply("save-profile", Map.of("name", "primary", "revision", ModelSettingsService.revision(primary), "model", "model; echo injected")).status());
        check.eq("unsafe input preserves the original profile", original, Files.readString(primary));
        var invalid = service.apply("save-profile", Map.of("name", "primary", "revision", "wrong", "model", "changed"));
        check.eq("stale editor cannot overwrite newer profile", 409, invalid.status());
        check.eq("conflict preserves original bytes", original, Files.readString(primary));
        String beforeFingerprint = ModelCheck.configuration(UserConfig.load(home).profiles().get("primary"), UserConfig.load(home));
        var saved = service.apply("save-profile", Map.of("name", "primary", "revision", ModelSettingsService.revision(primary), "model", "updated/model"));
        check.eq("existing CLI model is editable", true, saved.report().get("ok"));
        Profile updated = UserConfig.load(home).profiles().get("primary");
        check.eq("CLI flags and custom verification probe survive editing", "echo {{model}} verified", updated.verificationProbe());
        check.eq("editing resets capability stamp", false, updated.verified());
        check.that("old pong no longer matches settings", !beforeFingerprint.equals(ModelCheck.configuration(updated, UserConfig.load(home))));
        check.eq("unverified profile cannot be assigned from settings", 400,
                service.apply("save-route", Map.of("revision", ModelSettingsService.revision(home.resolve("policy.yaml")),
                        "role", "reviewer", "profiles", List.of("primary", "reserve"), "strategy", "first")).status());
        Files.writeString(primary, original);
        var routed = service.apply("save-route", Map.of("revision", ModelSettingsService.revision(home.resolve("policy.yaml")),
                "role", "reviewer", "profiles", List.of("primary", "reserve"), "strategy", "rotate"));
        check.eq("verified main/reserve roster is editable", true, routed.report().get("ok"));
        Policy policy = UserConfig.load(home).policy();
        check.eq("ordering is preserved", List.of("primary", "reserve"), policy.roles().get("reviewer").profiles());
        check.eq("independence policy survives roster edit", true, policy.roles().get("reviewer").requireIndependentVendor());
        check.eq("paid failover consent survives roster edit", "confirm", policy.failoverMode());
        check.eq("path traversal cannot target a profile", 400,
                service.apply("save-profile", Map.of("name", "../escape", "revision", "absent", "model", "x")).status());
        check.eq("unexpected secret field is rejected", 400,
                service.apply("save-profile", Map.of("name", "primary", "secret", "do-not-store")).status());
        var launch = service.apply("save-check", Map.of("revision", "absent", "before_run", false,
                "timeout_seconds", 30L, "max_output_tokens", 256L));
        check.eq("one shared settings file controls launch checks", true, launch.report().get("ok"));
        check.eq("settings read reflects saved mode", false, CheckSettings.load(home).beforeRun());
        new ConnectionStore(home).add(new Connection("stub", "custom", "openai_chat", "http://127.0.0.1:9999/v1", null, true));
        var api = service.apply("save-profile", Map.of("name", "new-api", "revision", "absent", "model", "deepseek/model",
                "vendor", "deepseek", "connection", "stub", "kind", "reviewer"));
        check.eq("API profile can be created in settings", true, api.report().get("ok"));
        check.eq("new API model is not silently assigned", List.of("primary", "reserve"), UserConfig.load(home).policy().roles().get("reviewer").profiles());
        check.eq("new API model still requires native verification", false, UserConfig.load(home).profiles().get("new-api").verified());
        new ConnectionStore(home).add(new Connection("router","openrouter","openai_chat","https://openrouter.ai/api/v1","env:TEST_ROUTER_KEY",true));
        var classifier=service.apply("save-classifier",Map.of("name","jev-classifier","revision","absent","connection","router","model","typesafe/jev-1.14","reported_model","typesafe/jev-1.14-20261014"));
        check.eq("typed classifier has a separate profile",true,classifier.report().get("ok"));
        check.eq("classifier cannot become a worker profile",false,UserConfig.load(home).profiles().containsKey("jev-classifier"));
        check.eq("classifier is visible alongside model settings",1L,((List<?>)service.list().get("profiles")).stream().filter(row->row instanceof Map<?,?> m && "classifier".equals(m.get("kind"))).count());
        check.eq("worker profile cannot shadow a classifier id",400,service.apply("save-profile",Map.of("name","jev-classifier","revision","absent","model","x","kind","reviewer","connection","stub","vendor","deepseek")).status());
        var routing=service.apply("save-routing",Map.of("revision",ModelSettingsService.revision(CheckSettings.file(home)),"enabled",true,"classifier","jev-classifier","min_confidence",0.7));
        check.eq("Jev routing is configured from shared settings",true,routing.report().get("ok"));
        service.apply("save-check",Map.of("revision",ModelSettingsService.revision(CheckSettings.file(home)),"before_run",true,"timeout_seconds",30L,"max_output_tokens",256L));
        check.eq("editing ping settings preserves Jev settings",true,RoutingSettings.load(home).enabled());
        check.eq("editing ping settings preserves classifier confidence",0.7,RoutingSettings.load(home).minConfidence());
        dashboard(check, home);
    }
    private static String profile(String name) {
        return "version: 1\nprofile: " + name + "\nrole: reviewer\nvendor: openai\nmodel: original/model\ncommand: echo\nargs: ['--model', '{{model}}']\nread_only: true\nprompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\nverification:\n  probe: echo {{model}} verified\n  expect: verified\n  verified_on: '2026-10-28'\n";
    }
    private static void dashboard(Check check, Path home) throws Exception {
        try (Dashboard dashboard = new Dashboard(home, 0, null, home, ignored -> null); HttpClient client = HttpClient.newHttpClient()) {
            dashboard.start(); String root = dashboard.url();
            var html = client.send(HttpRequest.newBuilder(URI.create(root)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check.contains("same panel offers CLI and API readiness", html.body(), "Проверить все модели");
            check.contains("same panel offers native capability check", html.body(), "Проверить допуск");
            check.that("template substitutions are complete", !html.body().contains("{{MODEL_SETTINGS}}"));
            var noToken = client.send(HttpRequest.newBuilder(URI.create(root + "api/models/save-check"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check.eq("check and settings mutations require page token", 403, noToken.statusCode());
            var read = client.send(HttpRequest.newBuilder(URI.create(root + "api/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check.eq("settings are readable without inference", 200, read.statusCode());
            check.that("GET settings does not contain credential values", !read.body().contains("do-not-store"));
        }
    }
}
