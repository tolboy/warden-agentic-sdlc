package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.execution.ModelCheck;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.RunReadiness;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class ModelCheckTest implements Suite {
    @Override public String name() { return "model-check"; }
    @Override public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("warden-check-test-");
        check.eq("automatic checking defaults on", true, CheckSettings.load(home).beforeRun());
        check.rejects("typo cannot disable automatic checks", "unsupported", () -> CheckSettings.parse(
                Map.of("version", 1L, "check", Map.of("before_rnu", false))));
        check.rejects("unbounded wait is refused", "between", () -> CheckSettings.parse(
                Map.of("version", 1L, "check", Map.of("timeout_seconds", 999L))));
        try (Stub stub = new Stub()) {
            Profile api = api(stub.connection());
            UserConfig user = user(home, api, stub.connection());
            ModelCheck service = new ModelCheck(new ProcessRunner(), name -> "synthetic-secret", null);
            Map<String, Object> result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("exact served model answers nonce", true, result.get("ok"));
            check.eq("successful reply needs no failure diagnostic",false,row(result).containsKey("pong_failure"));
            check.eq("one bounded inference per model", 1, stub.calls.get());
            check.eq("hard output ceiling comes from central settings", 512L, Json.parseObject(stub.body).get("max_tokens"));
            check.eq("API ping does not stamp profile", false, api.verified());
            check.that("safe receipt exists", Files.isRegularFile(Path.of((String) result.get("receipt"))));
            check.that("credential stays out of receipts", !Files.readString(home.resolve("checks/latest.json")).contains("synthetic-secret"));
            check.eq("unknown money remains unknown", "unknown", row(result).get("cost_status"));
            stub.emptyTools=true;stub.fenced=true;
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("empty tool metadata and fenced JSON are valid availability replies",true,result.get("ok"));
            stub.emptyTools=false;stub.fenced=false;
            stub.served = "substituted/model";
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("a substituted model is not ready", "check_model_mismatch", row(result).get("code"));
            check.eq("model mismatch retains its own failure classification",false,row(result).containsKey("pong_failure"));
            stub.served = "test/model"; stub.status = 401;
            int authBefore = stub.calls.get();
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("expired credential blocks readiness", false, result.get("ok"));
            check.that("auth failure offers login action", row(result).get("next").toString().contains("Sign in"));
            check.eq("an auth refusal is asked exactly once", 1, stub.calls.get() - authBefore);
            check.eq("an auth refusal never changes the model", "test/model", user.profiles().get(api.name()).model());
            stub.status = 200; stub.failFirst = 1;
            int gatewayBefore = stub.calls.get();
            result = new ModelCheck(new ProcessRunner(), name -> "synthetic-secret", null,
                    dev.warden.execution.ApiRetry.Policy.CHECK, millis -> {}).run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("a check rides out one 502", true, result.get("ok"));
            check.eq("with exactly one more request", 2, stub.calls.get() - gatewayBefore);
            check.eq("and counts both attempts", 2L, row(result).get("api_attempt_count"));
            check.eq("the retried check names its policy", "check", row(result).get("api_retry_policy"));
            stub.status = 401;
            stub.status = 200; stub.pong = false;
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("HTTP success without a fresh pong fails", "check_no_pong", row(result).get("code"));
            check.eq("stale nonce has a safe distinct diagnostic","nonce_mismatch",row(result).get("pong_failure"));
            stub.pong=true;stub.finish="length";
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("even a matching partial answer cannot pass the output ceiling",false,result.get("ok"));
            check.eq("output exhaustion is distinct from nonce mismatch","output_limit",row(result).get("pong_failure"));
            check.eq("a cut-off pong is truncation, not a credential problem","role_api_output_truncated",row(result).get("code"));
            check.that("truncation advice raises the limit instead of asking to sign in",
                    !row(result).get("next").toString().contains("Sign in")
                            && row(result).get("next").toString().contains("max_output_tokens"));
            check.eq("truncation keeps the limit that was hit",512L,row(result).get("max_output_tokens"));
            stub.finish="synthetic-secret-upstream-reason";
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("unknown provider finish reason is projected as a fixed enum","unfinished_response",row(result).get("pong_failure"));
            check.that("upstream finish reason is not reflected in receipt",!Json.write(result).contains("synthetic-secret-upstream-reason"));
            stub.finish="stop";
            stub.pong = true; stub.toolCall = true;
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("a tool request is not a ping answer", false, result.get("ok"));
            check.eq("tool requests are distinguishable from wrong nonce","tool_call",row(result).get("pong_failure"));
            stub.finish="tool_calls";
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("normal tool finish reason still has a tool diagnostic","tool_call",row(result).get("pong_failure"));
            stub.finish="stop";stub.toolCall=false;stub.missingText=true;
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("missing assistant text is distinct from malformed JSON","missing_text",row(result).get("pong_failure"));
            stub.missingText=false;stub.invalidJson=true;
            int unreadableBefore=stub.calls.get();
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("non-JSON text has a safe fixed diagnostic","invalid_json",row(result).get("pong_failure"));
            check.that("invalid assistant content is not reflected in receipt",!Json.write(result).contains("synthetic-secret-content"));
            check.eq("a persistently unreadable answer is asked only twice",2,stub.calls.get()-unreadableBefore);
            stub.invalidJson=false;stub.proseFirst=1;
            int proseBefore=stub.calls.get();
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("one prose answer earns exactly one more ping and passes",true,result.get("ok"));
            check.eq("with exactly one more request",2,stub.calls.get()-proseBefore);
            check.eq("the receipt says the ping needed two answers",2L,row(result).get("ping_answers"));
            check.eq("and why the first did not count","invalid_json",row(result).get("first_pong_failure"));
            check.that("the prose is not reflected in receipt",!Json.write(result).contains("synthetic-secret-prose"));
            stub.pong=false;
            proseBefore=stub.calls.get();
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("a wrong nonce is not asked again",1,stub.calls.get()-proseBefore);
            stub.pong=true;stub.choices=0;
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("empty choices are explicit","missing_choice",row(result).get("pong_failure"));
            stub.choices=2;
            result=service.run(user,home,Set.of(api.name()),ignored->{});
            check.eq("multiple answers cannot choose a convenient pong","ambiguous_choice",row(result).get("pong_failure"));
            stub.choices=1;
            int before = stub.calls.get();
            result = new ModelCheck(new ProcessRunner(), name -> null, null).run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("missing key sends nothing", before, stub.calls.get());
            check.eq("missing key has explicit action", "check_auth_missing", row(result).get("code"));
            result = RunReadiness.live(new ProcessRunner()).check(user, home, List.of(api), true, ignored -> {});
            check.eq("strict budget is refused ahead of diagnostic spending", "check_strict_cost_unproven", result.get("code"));
            check.eq("strict diagnostic refusal sends nothing", before, stub.calls.get());
        }
        reasoningPingLimit(check);
        cli(check, home);
        Files.writeString(CheckSettings.file(home), new CheckSettings(false, 90, 512).yaml());
        check.eq("only personal settings disable the automatic check", "check_disabled_by_user",
                RunReadiness.live(new ProcessRunner()).check(user(home, null, null), home, List.of(), false, ignored -> {}).get("code"));
        ModelCheck exploding = new ModelCheck(new ProcessRunner(), ignored -> null, (p,u,d,s) -> {
            throw new IllegalStateException("synthetic-secret must not be printed");
        });
        var sanitized = exploding.run(user(home, cliProfile("stdin"), null), home, Set.of("cli-check"), ignored -> {});
        check.that("exception text is never projected", !Json.write(sanitized).contains("synthetic-secret"));
        check.eq("missing profiles fail closed", false,
                exploding.run(user(home, null, null), home, Set.of("missing"), ignored -> {}).get("ok"));
    }

    private static void reasoningPingLimit(Check check) {
        CheckSettings settings = new CheckSettings(true, 90, 512);
        Connection gonka = new Connection("g", "gonkarouter", "openai_chat", "https://api.gonkarouter.io/v1", "env:K", true);
        Profile deepseek = Profile.parse("version: 1\nprofile: ds\nrole: reviewer\nrunner: api\nconnection: g\nmodel_vendor: deepseek\nmodel: deepseek-ai/DeepSeek-V4-Flash-0731\nread_only: true\nlimits:\n  max_output_tokens: 2048\n", "fixture").withConnection(gonka);
        check.eq("a reasoning model's ping gets room to think", 2048, ModelCheck.pingOutputLimit(deepseek, gonka, settings));
        Profile small = Profile.parse("version: 1\nprofile: ds\nrole: reviewer\nrunner: api\nconnection: g\nmodel_vendor: deepseek\nmodel: deepseek-ai/DeepSeek-V4-Flash-0731\nread_only: true\nlimits:\n  max_output_tokens: 1024\n", "fixture").withConnection(gonka);
        check.eq("but never more than the profile allows", 1024, ModelCheck.pingOutputLimit(small, gonka, settings));
        Profile plain = Profile.parse("version: 1\nprofile: p\nrole: reviewer\nrunner: api\nconnection: g\nmodel_vendor: deepseek\nmodel: test/model\nread_only: true\nlimits:\n  max_output_tokens: 2048\n", "fixture").withConnection(gonka);
        check.eq("an ordinary model keeps the central ping limit", 512, ModelCheck.pingOutputLimit(plain, gonka, settings));
        check.that("GLM is treated as a reasoning model", ModelCheck.reasoningModel("zai-org/GLM-5.3-Flash"));
        check.that("an o-series id is a reasoning model", ModelCheck.reasoningModel("openai/o3-mini"));
        check.that("a plain chat model is not", !ModelCheck.reasoningModel("meta-llama/llama-3.3-70b-instruct"));
    }

    private static void cli(Check check, Path home) throws Exception {
        for (String delivery : List.of("stdin", "file")) {
            Profile profile = cliProfile(delivery);
            var result = new ModelCheck(new ProcessRunner()).run(user(home, profile, null), home,
                    Set.of(profile.name()), ignored -> {});
            check.eq("CLI " + delivery + " uses the real configured argv and fresh answer", true, result.get("ok"));
            check.eq("CLI pong is not a provider identity attestation", "configured_cli_launch_not_provider_attestation", row(result).get("identity"));
            check.eq("CLI diagnostic fee is explicitly reported", "reported", row(result).get("cost_status"));
            check.eq("CLI stream usage is retained", 7L, ((Map<?, ?>) row(result).get("tokens")).get("input"));
        }
        Profile stale = cliProfile("stdin", "stale-error");
        var result = new ModelCheck(new ProcessRunner()).run(user(home, stale, null), home, Set.of(stale.name()), ignored -> {});
        check.eq("a later protocol failure cannot reuse an earlier pong", false, result.get("ok"));
    }
    private static Profile cliProfile(String delivery) { return cliProfile(delivery, "success"); }
    private static Profile cliProfile(String delivery, String scenario) {
        String cp = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!System.getProperty("os.name").toLowerCase().contains("win")) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return Profile.parse("version: 1\nprofile: cli-check\nrole: reviewer\nvendor: openai\nmodel: test/model\ncommand: " + Json.write(java)
                + "\nargs: " + Json.write(List.of("-cp", cp, Cli.class.getName(), delivery, "{{model}}", "{{prompt_file}}", scenario))
                + "\nprompt_delivery: " + (delivery.equals("stdin") ? "stdin" : "argv")
                + "\nread_only: true\nprompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\n", "fixture");
    }
    public static final class Cli {
        public static void main(String[] args) throws Exception {
            if (!args[1].equals("test/model")) System.exit(2);
            String prompt = args[0].equals("stdin") ? new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    : Files.readString(Path.of(args[2]));
            String nonce = nonce(prompt);
            System.out.println(Json.write(Map.of("type", "item.completed", "item", Map.of("type", "agent_message", "text", Json.write(Map.of("pong", nonce))))));
            System.out.println("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":7,\"output_tokens\":2},\"total_cost_usd\":0.00001}");
            if (args.length > 3 && args[3].equals("stale-error")) System.out.println("{\"type\":\"turn.failed\",\"error\":{\"message\":\"session expired\"}}");
        }
    }
    private static Profile api(Connection connection) {
        return Profile.parse("version: 1\nprofile: api-check\nrole: reviewer\nrunner: api\nconnection: stub\nmodel_vendor: deepseek\nmodel: test/model\nread_only: true\nprompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\nlimits:\n  max_output_tokens: 2048\n", "fixture").withConnection(connection);
    }
    private static UserConfig user(Path home, Profile profile, Connection connection) {
        return new UserConfig(home, null, profile == null ? Map.of() : Map.of(profile.name(), profile), Map.of(), false,
                connection == null ? Map.of() : Map.of(connection.id(), connection));
    }
    private static Map<?, ?> row(Map<String, Object> result) { return (Map<?, ?>) ((List<?>) result.get("results")).get(0); }
    private static String nonce(String prompt) {
        var matcher = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("no nonce"); return matcher.group();
    }
    private static final class Stub implements AutoCloseable {
        final HttpServer server; final AtomicInteger calls = new AtomicInteger();
        int status = 200,choices=1,failFirst,proseFirst; String served = "test/model", body,finish="stop"; boolean pong = true, toolCall,emptyTools,fenced,missingText,invalidJson;
        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                int call = calls.incrementAndGet(); body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> message = new java.util.LinkedHashMap<>();
                message.put("content", Json.write(Map.of("pong", pong ? nonce(body) : "old")));
                if(fenced)message.put("content","```json\n"+message.get("content")+"\n```");
                if(emptyTools)message.put("tool_calls",null);
                if (toolCall) message.put("tool_calls", List.of(Map.of("id", "x")));
                if(missingText)message.remove("content");
                if(invalidJson)message.put("content","synthetic-secret-content");
                if(proseFirst>0){proseFirst--;message.put("content","synthetic-secret-prose");}
                var answers=new java.util.ArrayList<Map<String,Object>>();for(int i=0;i<choices;i++)answers.add(Map.of("finish_reason",finish,"message",message));
                String response = status == 200 ? Json.write(Map.of("model", served, "choices", answers))
                        : "{\"error\":{\"message\":\"synthetic-secret\"}}";
                int answer = status;
                if (failFirst > 0) { failFirst--; answer = 502; response = "{\"error\":{\"message\":\"bad gateway\"}}"; }
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answer, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
        }
        Connection connection() { return new Connection("stub", "custom", "openai_chat", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "env:TEST_KEY", true); }
        @Override public void close() { server.stop(0); }
    }
}
