package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.execution.DecisionClient;
import dev.warden.json.Json;
import dev.warden.run.*;
import dev.warden.testing.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real typed classifier transport; no worker or provider model calls. */
public final class ModelSelectionTest implements Suite {
    public String name(){return "model-selection";}
    public void run(Check check)throws Exception{
        var options=Set.of("candidate_0","candidate_1");
        check.eq("unique supported winner can be selected","candidate_1",ModelSelection.choose(answer("candidate_1",0.1,0.9),0.6,options));
        check.eq("uncertainty keeps configured order",null,ModelSelection.choose(answer("candidate_1",0.45,0.55),0.6,options));
        check.eq("ties cannot arbitrarily select a provider",null,ModelSelection.choose(answer("candidate_1",0.5,0.5),0.5,options));
        check.eq("chosen label must match highest probability",null,ModelSelection.choose(answer("candidate_0",0.1,0.9),0.6,options));
        check.eq("foreign probability labels are refused",null,ModelSelection.choose(Map.of("choice","attacker","probabilities",Map.of("attacker",1.0)),0.6,options));
        Path home=Files.createTempDirectory("warden-model-selection-");new UserSetup().run(home);
        var first=profile("first","implementer","openai",false);
        var alternate=profile("alternate","implementer","anthropic",false);
        var reviewer=profile("safe-review","reviewer","google",true);
        var dependent=profile("dependent-review","reviewer","anthropic",true);
        String policy="version: 1\nroles:\n  implementer: {profiles: [first, alternate], strategy: first}\n  reviewer: {profiles: [safe-review, dependent-review], strategy: first, require_independent_vendor: true}\n";
        Files.writeString(home.resolve("policy.yaml"),policy);
        try(Stub stub=new Stub()){
            new ConnectionStore(home).add(stub.connection());
            var classifier=new DecisionProfile("jev-test","test-classifier","typesafe/jev-1.14","typesafe/jev-1.14-20261014",3,12000,true);
            Files.createDirectories(DecisionProfile.file(home,classifier.name()).getParent());Files.writeString(DecisionProfile.file(home,classifier.name()),classifier.yaml());
            Files.writeString(CheckSettings.file(home),"version: 1\nrouting:\n  enabled: false\n  model_selection_enabled: true\n  classifier: jev-test\n  min_confidence: 0.6\n");
            var base=UserConfig.load(home);var user=new UserConfig(home,base.policy(),Map.of(first.name(),first,alternate.name(),alternate,reviewer.name(),reviewer,dependent.name(),dependent),Map.of(),true,base.connections());
            var task=TaskSpec.parse("version: 1\nid: model-choice\ngoal: Implement the requested change\nrisk: medium\nscope: [src]\nacceptance: [check]\n", "fixture")
                    .resolve(ProjectConfig.parse("version: 1\nproject: choose\nchecks: {pass: [check]}\n","fixture"),"fixture");
            var writer=new TaskRouting.Target("implement","implementer",first,List.of(first,alternate),Map.of("unverified","profile_unverified"),false);
            var reader=new TaskRouting.Target("review","reviewer",reviewer,List.of(reviewer),Map.of(),false);
            var client=new DecisionClient(ignored->null);
            var selected=TaskRouting.using(client).route(user,home,task,List.of(writer,reader),ignored->{});
            check.eq("model-only toggle performs one bounded selection request",1,stub.calls);
            check.eq("admitted alternate is pinned for the stage",Map.of("implement","alternate"),selected.overrides().toMap().get("profile"));
            check.eq("effort is untouched when only model choice is enabled",Map.of(),selected.report().get("applied_efforts"));
            check.eq("model routing is reported","routing_models_selected",selected.report().get("code"));
            check.that("only opaque candidate labels reach typed question",stub.modelOptions.equals(options));
            var selection=(Map<?,?>)selected.report().get("model_selection");
            check.that("catalog/policy snapshot is reconstructible",String.valueOf(selection.get("catalog_snapshot")).matches("[0-9a-f]{64}"));
            var decisions=(List<?>)selection.get("decisions");
            check.that("refused roster alternatives remain in receipt",Json.write(decisions).contains("profile_unverified"));
            check.that("unknown cost is explicit rather than zero/free",Json.write(decisions).contains("\"cost_status\":\"unknown\""));
            stub.probability=0.55;
            var uncertain=TaskRouting.using(client).route(user,home,task,List.of(writer,reader),ignored->{});
            check.that("uncertain model answer retains authorized configured cast",uncertain.overrides().isEmpty());
            check.contains("uncertainty has a typed explanation",Json.write(uncertain.report()),"classifier_uncertain");stub.probability=0.9;
            var pinned=new TaskRouting.Target("implement","implementer",first,List.of(first,alternate),Map.of(),true);
            int before=stub.calls;
            var explicit=TaskRouting.using(client).route(user,home,task,List.of(pinned,reader),ignored->{});
            check.eq("explicit model pin avoids model inference",before,stub.calls);
            check.that("explicit pin is not overridden",explicit.overrides().isEmpty());
            check.contains("receipt explains fixed model",Json.write(explicit.report()),"explicit_profile");
            var readerChoices=new TaskRouting.Target("review","reviewer",reviewer,List.of(reviewer,dependent),Map.of(),false);
            var unsafe=TaskRouting.using(client).route(user,home,task,List.of(writer,readerChoices),ignored->{});
            check.that("joint dependent writer/reviewer choices are discarded",unsafe.overrides().isEmpty());
            check.contains("joint safety rejection is explained",Json.write(unsafe.report()),"dependent_selection");
            stub.unauthorized=true;
            var foreign=TaskRouting.using(client).route(user,home,task,List.of(writer,reader),ignored->{});
            check.that("classifier cannot add a profile outside typed roster",foreign.overrides().isEmpty());
            check.eq("foreign answer is invalid classifier protocol","classifier_response_invalid",foreign.report().get("code"));stub.unauthorized=false;
            stub.status=503;
            var failed=TaskRouting.using(client).route(user,home,task,List.of(writer,reader),ignored->{});
            check.that("classifier failure preserves configured cast",failed.overrides().isEmpty());
            check.eq("fallback is explicit","configured_workflow_and_efforts",failed.report().get("fallback"));stub.status=200;
            stub.beforeReply=()->{try{Files.writeString(home.resolve("policy.yaml"),policy+"# edited during request\n");}catch(Exception e){throw new RuntimeException(e);}};
            var changed=TaskRouting.using(client).route(user,home,task,List.of(writer,reader),ignored->{});
            check.that("edited roster does not inherit an in-flight selection",changed.overrides().isEmpty());
            check.eq("configuration race is named","configuration_changed",changed.report().get("code"));
        }
    }
    private static Map<String,Object> answer(String selected,double a,double b){return Map.of("choice",selected,"probabilities",Map.of("candidate_0",a,"candidate_1",b));}
    private static Profile profile(String name,String role,String vendor,boolean readOnly){return Profile.parse("version: 1\nprofile: "+name+"\nrole: "+role+"\nvendor: "+vendor+"\nmodel: gpt-6.1-sol\neffort: medium\ncommand: codex\nargs: ['--effort','{{effort}}']\nread_only: "+readOnly+"\nverification: {verified_on: '2026-10-29'}\n","fixture");}
    private static final class Stub implements AutoCloseable{
        final HttpServer server;int calls,status=200;double probability=0.9;boolean unauthorized;Set<?> modelOptions=Set.of();Runnable beforeReply=()->{};
        Stub()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/decisions",exchange->{
            calls++;var request=Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var answers=new LinkedHashMap<String,Object>();
            for(var question:((Map<?,?>)request.get("questions")).entrySet()){
                var options=(Map<?,?>)((Map<?,?>)question.getValue()).get("criteria");modelOptions=options.keySet();
                var labels=options.keySet().stream().map(String::valueOf).toList();String selected=unauthorized?"foreign_profile":labels.get(labels.size()-1);
                var probabilities=new LinkedHashMap<String,Object>();for(String key:labels)probabilities.put(key,key.equals(selected)?probability:(1-probability)/(labels.size()-1));
                answers.put(String.valueOf(question.getKey()),Map.of("type","choice","choice",selected,"confidence",0.9,"probabilities",probabilities));
            }
            beforeReply.run();String response=status==200?Json.write(Map.of("model","typesafe/jev-1.14-20261014","answers",answers)):"{}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();}
        Connection connection(){return new Connection("test-classifier","custom","openai_chat","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",null,true);}
        public void close(){server.stop(0);}
    }
}
