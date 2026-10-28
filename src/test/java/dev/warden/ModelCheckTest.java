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
            stub.served = "test/model"; stub.status = 401;
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("expired credential blocks readiness", false, result.get("ok"));
            check.that("auth failure offers login action", row(result).get("next").toString().contains("Sign in"));
            stub.status = 200; stub.pong = false;
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("HTTP success without a fresh pong fails", "check_no_pong", row(result).get("code"));
            stub.pong = true; stub.toolCall = true;
            result = service.run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("a tool request is not a ping answer", false, result.get("ok"));
            int before = stub.calls.get();
            result = new ModelCheck(new ProcessRunner(), name -> null, null).run(user, home, Set.of(api.name()), ignored -> {});
            check.eq("missing key sends nothing", before, stub.calls.get());
            check.eq("missing key has explicit action", "check_auth_missing", row(result).get("code"));
            result = RunReadiness.live(new ProcessRunner()).check(user, home, List.of(api), true, ignored -> {});
            check.eq("strict budget is refused ahead of diagnostic spending", "check_strict_cost_unproven", result.get("code"));
            check.eq("strict diagnostic refusal sends nothing", before, stub.calls.get());
        }
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
        int status = 200; String served = "test/model", body; boolean pong = true, toolCall,emptyTools,fenced;
        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet(); body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> message = new java.util.LinkedHashMap<>();
                message.put("content", Json.write(Map.of("pong", pong ? nonce(body) : "old")));
                if(fenced)message.put("content","```json\n"+message.get("content")+"\n```");
                if(emptyTools)message.put("tool_calls",null);
                if (toolCall) message.put("tool_calls", List.of(Map.of("id", "x")));
                String response = status == 200 ? Json.write(Map.of("model", served, "choices", List.of(Map.of("finish_reason", "stop", "message", message))))
                        : "{\"error\":{\"message\":\"synthetic-secret\"}}";
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
        }
        Connection connection() { return new Connection("stub", "custom", "openai_chat", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "env:TEST_KEY", true); }
        @Override public void close() { server.stop(0); }
    }
}
