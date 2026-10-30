package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.execution.*;
import dev.warden.json.Json;
import dev.warden.testing.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Future versions are admitted by a typed compatibility proof and explicit identity confirmation. */
public final class DecisionCompatibilityTest implements Suite {
    public String name(){return "decision-compatibility";}
    public void run(Check check)throws Exception{
        Path home=Files.createTempDirectory("warden-future-jev-");new UserSetup().run(home);
        var calls=new AtomicInteger();var served=new AtomicReference<>("typesafe/jev-1.15-20261029");
        var compatible=new AtomicBoolean(true);var beforeReply=new AtomicReference<Runnable>(()->{});
        var authorization=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/decisions",exchange->{
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            calls.incrementAndGet();var body=Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            var state=(Map<?,?>)body.get("state");String marker=String.valueOf(state.get("expected_choice"));
            var answers=new LinkedHashMap<String,Object>();
            for(var entry:((Map<?,?>)body.get("questions")).entrySet()){
                var options=(Map<?,?>)((Map<?,?>)entry.getValue()).get("criteria");var distribution=new LinkedHashMap<String,Object>();
                for(Object option:options.keySet())distribution.put(String.valueOf(option),marker.equals(option)?1.0:0.0);
                answers.put(String.valueOf(entry.getKey()),Map.of("type",compatible.get()?"choice":"text","choice",marker,
                        "confidence",1.0,"probabilities",distribution));
            }
            beforeReply.get().run();byte[] bytes=Json.write(Map.of("model",served.get(),"answers",answers)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();
        });server.start();
        try{
            var connection=new Connection("future-test","custom","openai_chat","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",null,true);
            new ConnectionStore(home).add(connection);
            var profile=new DecisionProfile("future-jev",connection.id(),"typesafe/jev-1.15",served.get(),3,8000,true);
            Path file=DecisionProfile.file(home,profile.name());Files.createDirectories(file.getParent());Files.writeString(file,profile.yaml());
            var user=UserConfig.load(home);var client=new DecisionClient(ignored->null);
            check.eq("future version parses without a Java release",profile,DecisionProfile.parse(profile.yaml()));
            check.eq("new version is not admitted by its name",false,DecisionCompatibility.admitted(profile,user));
            check.eq("normal ping requires proof","classifier_compatibility_required",client.ping(profile,user).get("code"));
            check.eq("unconfirmed version spends no request",0,calls.get());
            check.eq("probe requires explicit confirmation","classifier_confirmation_required",
                    DecisionCompatibility.verify(client,profile,user,profile.reportedModel(),false).get("code"));
            check.eq("confirmation must name exact saved pin","classifier_confirmation_required",
                    DecisionCompatibility.verify(client,profile,user,"typesafe/jev-1.15-20261030",true).get("code"));
            check.eq("bad confirmation spends no request",0,calls.get());
            var verified=DecisionCompatibility.verify(client,profile,user,profile.reportedModel(),true);
            check.eq("typed challenge verifies future snapshot",true,verified.get("ok"));
            check.eq("one bounded challenge is used",1,calls.get());
            check.eq("matching profile/connection is admitted",true,DecisionCompatibility.admitted(profile,user));
            check.eq("verified status is observable","verified",DecisionCompatibility.status(profile,user).get("state"));
            check.eq("normal execution works after proof",true,client.ping(profile,user).get("ok"));
            Path receipt=DecisionCompatibility.file(home,profile.name());String old=Files.readString(receipt);

            var update=new DecisionProfile(profile.name(),connection.id(),profile.model(),"typesafe/jev-1.15-20261030",3,8000,true);
            Files.writeString(file,update.yaml());user=UserConfig.load(home);
            check.eq("changed reported pin invalidates old proof",false,DecisionCompatibility.admitted(update,user));
            var mismatch=DecisionCompatibility.verify(client,update,user,update.reportedModel(),true);
            check.eq("identity mismatch is never adopted","classifier_model_mismatch",mismatch.get("code"));
            check.eq("safe observed identity helps explicit correction",profile.reportedModel(),mismatch.get("model_reported"));
            check.eq("mismatch cannot overwrite old proof",old,Files.readString(receipt));
            served.set(update.reportedModel());
            var service=new ModelSettingsService(home,home,ignored->null);int before=calls.get();
            var refused=service.apply("verify-classifier",Map.of("name",profile.name(),"revision",ModelSettingsService.revision(file),
                    "reported_model",update.reportedModel(),"confirm",false));
            check.eq("settings endpoint requires confirmation",400,refused.status());
            check.eq("stale settings cannot verify a changed pin",409,service.apply("verify-classifier",Map.of("name",profile.name(),
                    "revision","stale","reported_model",update.reportedModel(),"confirm",true)).status());
            check.eq("rejected settings make no request",before,calls.get());
            var accepted=service.apply("verify-classifier",Map.of("name",profile.name(),"revision",ModelSettingsService.revision(file),
                    "reported_model",update.reportedModel(),"confirm",true));
            check.eq("explicit settings action verifies compatible update",true,accepted.report().get("ok"));
            check.eq("new snapshot is now admitted",true,DecisionCompatibility.admitted(update,UserConfig.load(home)));

            var authenticated=new Connection(connection.id(),"custom","openai_chat",connection.baseUrl(),"env:WARDEN_COMPATIBILITY_TEST_TOKEN",true);
            new ConnectionStore(home).update(authenticated,new ConnectionStore(home).versioned(connection.id()).revision());
            var supplied=new ModelSettingsService(home,home,key->"WARDEN_COMPATIBILITY_TEST_TOKEN".equals(key)?"fixture-compatibility-token":null);
            var authenticatedResult=supplied.apply("verify-classifier",Map.of("name",profile.name(),"revision",ModelSettingsService.revision(file),
                    "reported_model",update.reportedModel(),"confirm",true));
            check.eq("settings verification uses supplied credential resolver",true,authenticatedResult.report().get("ok"));
            check.eq("supplied credential reaches only the selected endpoint","Bearer fixture-compatibility-token",authorization.get());
            new ConnectionStore(home).update(connection,new ConnectionStore(home).versioned(connection.id()).revision());

            var major=new DecisionProfile(profile.name(),connection.id(),"typesafe/jev-2.0","typesafe/jev-2.0-20261029",3,8000,true);
            Files.writeString(file,major.yaml());served.set(major.reportedModel());compatible.set(false);
            check.eq("new major version must prove schema compatibility","classifier_response_invalid",
                    DecisionCompatibility.verify(client,major,UserConfig.load(home),major.reportedModel(),true).get("code"));
            check.eq("incompatible schema cannot activate version",false,DecisionCompatibility.admitted(major,UserConfig.load(home)));
            compatible.set(true);
            beforeReply.set(()->{try{Files.writeString(file,major.yaml().replace("timeout_seconds: 3","timeout_seconds: 4"));}catch(Exception e){throw new RuntimeException(e);}});
            check.eq("editing during the challenge prevents stamping","classifier_configuration_changed",
                    DecisionCompatibility.verify(client,major,UserConfig.load(home),major.reportedModel(),true).get("code"));
            beforeReply.set(()->{});
            var current=DecisionProfile.load(home).get(profile.name());
            check.eq("new version with compatible typed protocol can be confirmed",true,
                    DecisionCompatibility.verify(client,current,UserConfig.load(home),current.reportedModel(),true).get("ok"));
            var replacement=new Connection(connection.id(),"custom","openai_chat",connection.baseUrl(),"env:CHANGED_TEST_TOKEN",true);
            new ConnectionStore(home).update(replacement,new ConnectionStore(home).versioned(connection.id()).revision());
            check.eq("changed connection invalidates compatibility",false,DecisionCompatibility.admitted(current,UserConfig.load(home)));
            check.rejects("latest alias is never an exact version",null,()->new DecisionProfile("x",connection.id(),"typesafe/jev-latest","typesafe/jev-latest",3,8000,true));
            check.rejects("worker model cannot masquerade as classifier",null,()->new DecisionProfile("x",connection.id(),"deepseek/model","deepseek/model",3,8000,true));
        }finally{server.stop(0);}
    }
}
