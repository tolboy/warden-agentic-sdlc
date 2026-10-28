package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.execution.*;
import dev.warden.json.Json;
import dev.warden.run.TaskRouting;
import dev.warden.testing.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DecisionRoutingTest implements Suite {
    public String name(){return "decision-routing";}
    public void run(Check check)throws Exception{
        Path home=Files.createTempDirectory("warden-decisions-");new UserSetup().run(home);
        var defaults=JevRoutingPolicy.load(home);
        check.eq("absent routing policy uses reproducible defaults",JevRoutingPolicy.DEFAULT.fingerprint(),defaults.fingerprint());
        check.eq("editable rubric round-trips exactly",defaults.fingerprint(),JevRoutingPolicy.parse(defaults.yaml()).fingerprint());
        var customized=JevRoutingPolicy.parse("version: 1\ninstructions:\n  kind: Choose the dominant type of requested work.\ncriteria:\n  effort:\n    low: A mechanical edit with direct acceptance criteria.\n");
        check.eq("partial YAML customizes wording", "Choose the dominant type of requested work.",customized.instructions().get("kind"));
        check.eq("partial YAML preserves fixed effort alternatives",5,customized.criteria().get("effort").size());
        check.that("rubric edits change receipt fingerprint",!defaults.fingerprint().equals(customized.fingerprint()));
        check.rejects("rubric cannot inject new routing actions",null,()->JevRoutingPolicy.parse("criteria: {effort: {skip_review: skip mandatory review}}"));
        check.rejects("blank rubric descriptions fail closed",null,()->JevRoutingPolicy.parse("instructions: {kind: ''}"));
        check.rejects("oversize rubric wording is refused",null,()->JevRoutingPolicy.parse("instructions: {kind: "+Json.write("x".repeat(1601))+"}"));

        try(Stub stub=new Stub()){
            var connection=stub.connection();new ConnectionStore(home).add(connection);
            var profile=new DecisionProfile("jev-test",connection.id(),"typesafe/jev-1.14","typesafe/jev-1.14-20261014",3,8000,true);
            Files.createDirectories(DecisionProfile.file(home,profile.name()).getParent());Files.writeString(DecisionProfile.file(home,profile.name()),profile.yaml());
            var user=UserConfig.load(home);var client=new DecisionClient(ignored->"test-secret");
            var result=client.ping(profile,user);
            check.eq("typed challenge proves Jev is answering",true,result.get("ok"));
            check.eq("dated Jev snapshot is checked",profile.reportedModel(),result.get("model_reported"));
            check.eq("missing provider money does not prevent readiness","unknown",result.get("cost_status"));
            check.eq("same connection credential reaches only decisions endpoint","Bearer test-secret",stub.authorization);
            check.that("secret is absent from diagnostics",!Json.write(result).contains("test-secret"));
            stub.model="different/model";result=client.ping(profile,user);
            check.eq("substituted model is rejected","classifier_model_mismatch",result.get("code"));stub.model=profile.reportedModel();
            stub.badProbabilities=true;result=client.ping(profile,user);
            check.eq("invented or malformed probability does not become advice","classifier_response_invalid",result.get("code"));stub.badProbabilities=false;
            stub.status=401;result=client.ping(profile,user);
            check.eq("auth error has a typed cause","role_api_auth_invalid",result.get("code"));
            check.that("provider auth error body is not persisted",!Json.write(result).contains("test-secret"));stub.status=200;
            int calls=stub.calls.get();
            check.eq("missing credential fails before sending","classifier_auth_missing",new DecisionClient(ignored->null).ping(profile,user).get("code"));
            check.eq("missing credential makes no inference",calls,stub.calls.get());
            var oversized=client.decide(profile,user,Map.of("task","small"),Map.of("test",DecisionClient.choice("x".repeat(40000),Map.of("a","a","b","b"))));
            check.eq("rubric and state share a bounded total input","classifier_input_limit",oversized.code());
            check.eq("oversized rubric is refused without inference",calls,stub.calls.get());
            var target=worker();var scoped=new UserConfig(home,user.policy(),Map.of(target.name(),target),Map.of(),true,user.connections());
            Files.writeString(CheckSettings.file(home),CheckSettings.DEFAULT.yaml()+"routing:\n  enabled: true\n  classifier: jev-test\n  min_confidence: 0.6\n");
            var task=task(home,"medium");var targets=List.of(new TaskRouting.Target("implement","implementer",target));
            var route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("confident effort is selected","low",((Map<?,?>)route.overrides().toMap().get("effort")).get("implement"));
            check.eq("classification cannot delete mandatory roles",false,route.report().get("roles_changed"));
            check.eq("role advice is retained",true,route.report().containsKey("recommended_roles"));
            check.eq("unknown money does not block routing","unknown",route.report().get("cost_status"));
            check.that("classification gets its own receipt",Files.isRegularFile(Path.of((String)route.report().get("receipt"))));
            check.eq("routing receipt identifies policy",defaults.fingerprint(),route.report().get("policy_hash"));
            Files.writeString(JevRoutingPolicy.file(home),customized.yaml());
            route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("YAML question wording reaches typed request",customized.instructions().get("kind"),stub.kindInstructions);
            check.eq("custom policy is identified in receipt",customized.fingerprint(),route.report().get("policy_hash"));
            stub.beforeReply=()->{try{Files.writeString(JevRoutingPolicy.file(home),defaults.yaml());}catch(Exception failure){throw new RuntimeException(failure);}};
            route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("in-flight policy edit invalidates the recommendation","routing_policy_changed_kept_configured",route.report().get("code"));
            check.eq("changed rubric cannot apply stale effort",true,route.overrides().isEmpty());stub.beforeReply=()->{};
            Files.writeString(JevRoutingPolicy.file(home),"instructions: {kind: ''}");calls=stub.calls.get();
            route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("invalid policy visibly preserves workflow","routing_policy_invalid_kept_configured",route.report().get("code"));
            check.eq("invalid policy makes no request",calls,stub.calls.get());
            Files.writeString(JevRoutingPolicy.file(home),defaults.yaml());

            stub.confidence=0.1;stub.probability=0.4;route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("uncertain decisions keep configured efforts",true,route.overrides().isEmpty());stub.confidence=0.95;stub.probability=1;
            stub.effortBand=Map.of("low",0.0,"medium",0.03,"high",0.03,"xhigh",0.45,"max",0.49);
            route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("adjacent high-effort ambiguity does not revert to medium","xhigh",((Map<?,?>)route.overrides().toMap().get("effort")).get("implement"));
            var grok=Profile.parse("version: 1\nprofile: grok-worker\nrole: implementer\nvendor: grok\nmodel: grok-4.7\neffort: high\ncommand: grok\nargs: ['--effort','{{effort}}']\nread_only: false\n", "fixture");
            check.eq("Grok 4.7 has no max effort",List.of("low","medium","high","xhigh"),TaskRouting.supportedEfforts(grok));
            stub.effortBand=Map.of("low",0.0,"medium",0.0,"high",0.1,"xhigh",0.9);
            var grokUser=new UserConfig(home,user.policy(),Map.of(grok.name(),grok),Map.of(),true,user.connections());
            var grokRoute=TaskRouting.using(client).route(grokUser,home,task,List.of(new TaskRouting.Target("implement","implementer",grok)),ignored->{});
            check.eq("Grok receives a supported effort","xhigh",((Map<?,?>)grokRoute.overrides().toMap().get("effort")).get("implement"));
            check.eq("API transport cannot silently acquire CLI effort",List.of(),TaskRouting.supportedEfforts(Profile.parse("version: 1\nprofile: text\nrole: reviewer\nvendor: grok\nmodel: grok-4.7\nrunner: api\nconnection: example\nmodel_vendor: grok\nread_only: true\n", "fixture")));
            stub.effortBand=null;
            route=TaskRouting.using(client).route(scoped,home,task(home,"high"),targets,ignored->{});
            check.eq("high-risk task has a deterministic effort floor","high",((Map<?,?>)route.overrides().toMap().get("effort")).get("implement"));
            stub.status=503;route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("utility classifier failure preserves configured workflow",true,route.overrides().isEmpty());
            check.eq("failure is explicitly visible","configured_workflow_and_efforts",route.report().get("fallback"));stub.status=200;
            Files.writeString(CheckSettings.file(home),CheckSettings.DEFAULT.yaml()+"routing:\n  enabled: false\n  classifier: jev-test\n");calls=stub.calls.get();
            route=TaskRouting.using(client).route(scoped,home,task,targets,ignored->{});
            check.eq("disabled automatic classifier spends nothing",calls,stub.calls.get());
            check.eq("disabled state is reported","routing_disabled",route.report().get("code"));
            calls=stub.calls.get();TaskRouting.using(client,true).route(scoped,home,task,targets,ignored->{});
            check.eq("explicit classify works with automatic routing disabled",calls+1,stub.calls.get());
            calls=stub.calls.get();Thread.currentThread().interrupt();
            try{check.rejects("cancelled routing does not continue into workers",null,()->TaskRouting.using(client,true).route(scoped,home,task,targets,ignored->{}));}
            finally{Thread.interrupted();}
            check.eq("cancelled routing sends no request",calls,stub.calls.get());
            // A configuration change while a response is in flight invalidates that response.
            stub.beforeReply=()->{try{Files.writeString(DecisionProfile.file(home,profile.name()),profile.yaml().replace("enabled: true","enabled: false"));}catch(Exception e){throw new RuntimeException(e);}};
            check.eq("edited classifier is not blessed by an old in-flight response","classifier_configuration_changed",client.ping(profile,user).get("code"));
        }
        var parsed=TaskSpec.parse("version: 1\nid: defaults\ngoal: test\nscope: [src]\nacceptance: [check]\n","test");
        check.eq("implicit dollars no longer gate new tasks","off",parsed.budget().costCap());
        check.eq("reported money is disabled as a ceiling by default",0.0,parsed.budget().enforcedMaxCostUsd());
        parsed=TaskSpec.parse("version: 1\nid: explicit\ngoal: test\nscope: [src]\nacceptance: [check]\nbudgets: {max_cost_usd: 1}\n","test");
        check.eq("an explicitly requested money threshold is preserved","threshold",parsed.budget().costCap());
    }
    private static Profile worker(){
        return Profile.parse("version: 1\nprofile: codex-worker\nrole: implementer\nvendor: codex\nmodel: gpt-6.1-sol\neffort: medium\ncommand: codex\nargs: ['--model','{{model}}','--effort','{{effort}}']\nread_only: false\nprompt_template: prompts/implementer.md\njson_schema: schemas/implementer.json\nverification: {verified_on: '2026-10-28'}\n","fixture");
    }
    private static TaskSpec.ResolvedTask task(Path home,String risk)throws Exception{
        var project=ProjectConfig.parse("version: 1\nproject: decision-test\nchecks: {pass: [check]}\n","fixture");
        return TaskSpec.parse("version: 1\nid: decision-test\ngoal: Update a clearly named label\nrisk: "+risk+"\nscope: [src]\nacceptance: [check]\n","fixture").resolve(project,"fixture");
    }
    private static final class Stub implements AutoCloseable{
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();int status=200;String model="typesafe/jev-1.14-20261014",authorization,kindInstructions;boolean badProbabilities;double confidence=0.95,probability=1;Map<String,Double> effortBand;Runnable beforeReply=()->{};
        Stub()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/decisions",exchange->{
                calls.incrementAndGet();authorization=exchange.getRequestHeaders().getFirst("Authorization");var request=Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                var questions=(Map<?,?>)request.get("questions");if(questions.get("kind") instanceof Map<?,?> kind)kindInstructions=(String)kind.get("instructions");var answers=new LinkedHashMap<String,Object>();
                for(var question:questions.entrySet()){
                    var options=(Map<?,?>)((Map<?,?>)question.getValue()).get("criteria");String selected=options.containsKey("low")?"low":options.containsKey("yes")?"yes":String.valueOf(options.keySet().iterator().next());
                    if("availability".equals(question.getKey()))selected=(String)((Map<?,?>)request.get("state")).get("expected_choice");
                    Map<String,Object> probabilities=new LinkedHashMap<>();for(Object key:options.keySet())probabilities.put(String.valueOf(key),key.equals(selected)?probability:(1-probability)/(options.size()-1));
                    if(effortBand!=null && String.valueOf(question.getKey()).startsWith("effort_")){probabilities.clear();probabilities.putAll(effortBand);selected=options.containsKey("max")?"max":"xhigh";}
                    if(badProbabilities)probabilities.put(selected,"test-secret");
                    answers.put(String.valueOf(question.getKey()),Map.of("type","choice","choice",selected,"confidence",confidence,"probabilities",probabilities));
                }
                beforeReply.run();String reply=status==200?Json.write(Map.of("model",model,"answers",answers,"usage",Map.of("input_tokens",10L,"output_tokens",5L))):"{\"error\":{\"message\":\"test-secret\"}}";
                byte[] bytes=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        Connection connection(){return new Connection("decisions-test","custom","openai_chat","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","env:TEST_DECISIONS_KEY",true);}
        public void close(){server.stop(0);}
    }
}
