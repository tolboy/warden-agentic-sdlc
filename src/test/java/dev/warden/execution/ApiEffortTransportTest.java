package dev.warden.execution;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.Connection;
import dev.warden.config.Profile;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Observes real HTTP bodies at the API and OpenCode relay boundaries. */
public final class ApiEffortTransportTest implements Suite {
    @Override public String name() { return "api-effort-transport"; }

    @Override public void run(Check check) throws Exception {
        Path home=Files.createTempDirectory("warden-api-effort-");
        AtomicReference<Map<String,Object>> posted=new AtomicReference<>();
        AtomicReference<String> reported=new AtomicReference<>();
        AtomicBoolean pingMode=new AtomicBoolean();
        AtomicInteger status=new AtomicInteger(200),hits=new AtomicInteger();
        AtomicLong responseDelay=new AtomicLong();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            hits.incrementAndGet();
            try { Thread.sleep(responseDelay.get()); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new java.io.IOException(cancelled); }
            posted.set(Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            var response=new LinkedHashMap<String,Object>();
            response.put("model","test/model");response.put("id","effort-fixture");
            String content=Json.write(Map.of("status","completed","verdict","pass","summary","checked","findings",List.of()));
            if(pingMode.get()) {
                var nonce=java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(Json.write(posted.get()));
                if(nonce.find())content=Json.write(Map.of("pong",nonce.group()));
            }
            response.put("choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content",content))));
            response.put("usage",Map.of("prompt_tokens",10,"completion_tokens",5,"completion_tokens_details",Map.of("reasoning_tokens",4)));
            if(reported.get()!=null)response.put("reasoning",Map.of("effort",reported.get()));
            byte[] bytes=Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();
        });server.start();
        try {
            var connection=new Connection("effort-test","custom","openai_chat",
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/v1",null,true);
            Files.createDirectories(home.resolve("connections"));Files.createDirectories(home.resolve("vendors"));
            Files.writeString(home.resolve("connections/effort-test.yaml"),connection.toYaml());
            Path catalog=home.resolve("vendors/api-effort-test-catalog.json");
            catalog(catalog,connection.fingerprint());
            Path prompt=home.resolve("prompt.md");Files.writeString(prompt,"Review the fixture.");
            var executor=new ApiExecutor(ignored->null,Duration.ofSeconds(5),null);
            Profile base=profile(connection,home);
            var outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.that("registered API effort executes",outcome.ok());
            check.eq("actual API JSON carries selected effort",Map.of("effort","medium"),posted.get().get("reasoning"));
            check.eq("actual API transmission recorded","medium",outcome.evidence().get("effort_transmitted"));
            check.eq("answered call is sent","sent",outcome.evidence().get("effort_transmission"));
            check.eq("reasoning tokens are not effort confirmation","not_reported_by_provider",outcome.evidence().get("effort_verification"));
            outcome=executor.execute(request(base.withEffort("max"),prompt));
            check.that("registered max is deliverable",outcome.ok());
            check.eq("max is transmitted unchanged",Map.of("effort","max"),posted.get().get("reasoning"));
            int before=hits.get();
            outcome=executor.execute(request(base.withEffort("ultra"),prompt));
            check.eq("unsupported effort stops","role_effort_unsupported",outcome.code());
            check.eq("unsupported effort spends no request",before,hits.get());
            check.eq("unsupported effort says not sent","not_sent",outcome.evidence().get("effort_transmission"));
            reported.set("medium");
            outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.eq("explicit provider metadata is recorded","medium",outcome.evidence().get("effort_reported"));
            check.eq("explicit matching report is identified","confirmed_by_provider",outcome.evidence().get("effort_verification"));
            reported.set("low");
            outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.eq("provider mismatch stops before accepting artifact","role_api_effort_mismatch",outcome.code());
            reported.set("auto");
            outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.eq("unknown structured provider effort is a mismatch","role_api_effort_mismatch",outcome.code());
            check.eq("unknown provider value is replaced with a safe sentinel","unrecognized",outcome.evidence().get("effort_reported"));
            reported.set(null);status.set(400);before=hits.get();
            outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.that("provider rejection remains failure",!outcome.ok());
            check.eq("provider refusal is not retried at a lower effort",before+1,hits.get());
            check.eq("rejected request still carries exact effort",Map.of("effort","medium"),posted.get().get("reasoning"));
            status.set(200);catalog(catalog,"stale");before=hits.get();
            outcome=executor.execute(request(base.withEffort("medium"),prompt));
            check.eq("stale catalog cannot authorize delivery","role_effort_unsupported",outcome.code());
            check.eq("stale capability spends no call",before,hits.get());
            Files.delete(catalog);
            outcome=executor.execute(request(base,prompt));
            check.that("legacy API without effort needs no effort catalog",outcome.ok());
            check.that("legacy body leaves effort unset",!posted.get().containsKey("reasoning"));
            check.eq("legacy evidence identifies no request","not_requested",outcome.evidence().get("effort_transmission"));

            catalog(catalog,connection.fingerprint());pingMode.set(true);
            Profile pingProfile=base.withEffort("medium");
            var user=new dev.warden.config.UserConfig(home,null,Map.of(pingProfile.name(),pingProfile),Map.of(),false,Map.of(connection.id(),connection));
            var ping=new ModelCheck(new dev.warden.process.ProcessRunner(),ignored->null,null).run(user,home,Set.of(pingProfile.name()),ignored->{});
            check.eq("availability check with configured effort gets fresh pong",true,ping.get("ok"));
            check.eq("availability check sends the configured effort",Map.of("effort","medium"),posted.get().get("reasoning"));
            Map<?,?> pingRow=(Map<?,?>)((List<?>)ping.get("results")).getFirst();
            check.eq("availability check preserves transmission evidence","medium",pingRow.get("effort_transmitted"));
            reported.set("credential-like-arbitrary-provider-text");
            ping=new ModelCheck(new dev.warden.process.ProcessRunner(),ignored->null,null).run(user,home,Set.of(pingProfile.name()),ignored->{});
            pingRow=(Map<?,?>)((List<?>)ping.get("results")).getFirst();
            check.eq("ping rejects unknown structured effort","check_effort_mismatch",pingRow.get("code"));
            check.that("unknown provider text never enters ping receipt",!Json.write(ping).contains("credential-like-arbitrary-provider-text"));
            reported.set(null);
            pingMode.set(false);

            try(var relay=new CodingRelay(connection,"test/model",null,Duration.ofSeconds(5),3,128,"max")) {
                var body=new LinkedHashMap<String,Object>();body.put("model","test/model");body.put("messages",List.of());
                body.put("reasoning",Map.of("effort","low"));body.put("reasoning_effort","high");
                var answer=post(relay,body);
                check.eq("coding relay forwards successful response",200,answer.statusCode());
                check.eq("relay enforces Warden's effort over harness choice",Map.of("effort","max"),posted.get().get("reasoning"));
                check.that("relay does not forward alternate harness effort",!posted.get().containsKey("reasoning_effort"));
                check.eq("relay receipt records actual transmission","max",relay.evidence().get("effort_transmitted"));
                check.eq("relay does not infer confirmation from tokens","not_reported_by_provider",relay.evidence().get("effort_verification"));
                post(relay,body);
                check.eq("every coding turn has the same exact effort",Map.of("effort","max"),posted.get().get("reasoning"));
            }
            reported.set("low");
            try(var relay=new CodingRelay(connection,"test/model",null,Duration.ofSeconds(5),3,128,"high")) {
                var answer=post(relay,Map.of("model","test/model","messages",List.of()));
                check.eq("relay blocks conflicting provider effort",502,answer.statusCode());
                check.eq("relay mismatch is classified","probe_served_effort_mismatch",relay.evidence().get("failure"));
            }
            reported.set("high");
            try(var relay=new CodingRelay(connection,"test/model",null,Duration.ofSeconds(5),3,128,"high")) {
                post(relay,Map.of("model","test/model","messages",List.of()));
                check.eq("explicit matching relay response confirms that turn","confirmed_by_provider",relay.evidence().get("effort_verification"));
                reported.set(null);
                post(relay,Map.of("model","test/model","messages",List.of()));
                check.eq("one confirmed turn does not confirm unreported turns","not_reported_by_provider",relay.evidence().get("effort_verification"));
                check.eq("mixed confirmation does not fabricate an aggregate level",null,relay.evidence().get("effort_reported"));
            }
            reported.set("high");
            try(var relay=new CodingRelay(connection,"test/model",null,Duration.ofSeconds(5),3,128,"high")) {
                post(relay,Map.of("model","test/model","messages",List.of()));
                check.eq("HTTP-failure scenario begins with confirmed turn","confirmed_by_provider",relay.evidence().get("effort_verification"));
                status.set(500);
                check.eq("second relay HTTP error is refused",502,post(relay,Map.of("model","test/model","messages",List.of())).statusCode());
                check.eq("later HTTP failure clears aggregate confirmation","not_reported_by_provider",relay.evidence().get("effort_verification"));
                check.eq("later HTTP failure clears aggregate report",null,relay.evidence().get("effort_reported"));
                Map<?,?> first=(Map<?,?>)((List<?>)relay.evidence().get("responses")).getFirst();
                check.eq("first turn retains its own confirmation","confirmed_by_provider",first.get("effort_verification"));
            }
            status.set(200);
            try(var relay=new CodingRelay(connection,"test/model",null,Duration.ofSeconds(2),3,128,"high")) {
                post(relay,Map.of("model","test/model","messages",List.of()));
                check.eq("timeout scenario begins with confirmed turn","confirmed_by_provider",relay.evidence().get("effort_verification"));
                responseDelay.set(2500);
                check.eq("timed-out second relay request is refused",502,post(relay,Map.of("model","test/model","messages",List.of())).statusCode());
                check.eq("later timeout clears aggregate confirmation","not_reported_by_provider",relay.evidence().get("effort_verification"));
                check.eq("later timeout clears aggregate report",null,relay.evidence().get("effort_reported"));
                check.eq("timeout preserves unknown delivery","unknown",relay.evidence().get("effort_transmission"));
            } finally { responseDelay.set(0); }
        } finally {server.stop(0);}
    }

    private static void catalog(Path path,String configuration)throws Exception {
        Files.writeString(path,Json.write(Map.of("status","available","configuration",configuration,"source","loopback fixture",
                "fetched_at",java.time.Instant.now().toString(),"models",List.of(Map.of("id","test/model","efforts",List.of("low","medium","high","max"),
                        "effort_delivery","openrouter_reasoning")))));
    }
    private static Profile profile(Connection c,Path home) {
        return Profile.parse("version: 1\nprofile: effort-api\nrole: reviewer\nmodel_vendor: deepseek\nmodel: test/model\nrunner: api\nconnection: effort-test\nread_only: true\nlimits: {wall_clock_minutes: 1, max_output_tokens: 128}\nartifact: {required_fields: [status, verdict, summary, findings]}\n","fixture").withConnection(c,home);
    }
    private static RoleExecutor.Request request(Profile profile,Path prompt) {
        return new RoleExecutor.Request("effort","effort","reviewer",profile,null,null,prompt.getParent(),prompt.getParent(),prompt,null,"","reviewer",List.of());
    }
    private static HttpResponse<String> post(CodingRelay relay,Map<String,Object> body)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(relay.baseUrl()+"/chat/completions"))
                .header("Authorization","Bearer "+relay.bearer()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
