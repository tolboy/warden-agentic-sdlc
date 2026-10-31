package dev.warden;

import dev.warden.approval.ApprovalStore;
import dev.warden.config.*;
import dev.warden.execution.RoleExecutor;
import dev.warden.process.ProcessRunner;
import dev.warden.run.*;
import dev.warden.testing.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Production loop and admission, with local role/readiness seams and real machine gates. */
public final class ModelRoutingLoopTest implements Suite {
    public String name(){return "model-routing-loop";}
    public void run(Check check)throws Exception {
        Fixture chosen=new Fixture();
        var result=chosen.loop((u,p,t,targets,progress)->{
            var writer=targets.stream().filter(x->x.role().equals("implementer")).findFirst().orElseThrow();
            check.eq("unverified candidate is absent",false,writer.candidates().stream().anyMatch(x->x.name().equals("unverified")));
            check.eq("unavailable executable is absent",false,writer.candidates().stream().anyMatch(x->x.name().equals("missing")));
            check.eq("candidate rejection explains unverified channel","profile_unverified",writer.rejected().get("unverified"));
            return advice(Map.of("implement","alternate"));
        }).run(chosen.loaded(),chosen.user(),"chosen",false);
        check.that("selected model completes gates and mandatory review",result.ok());
        check.eq("selected alternate executes",List.of("alternate","review"),chosen.calls);
        check.eq("model choice is recorded as actually applied",Map.of("implement","alternate"),routing(result).get("applied_profiles"));
        check.eq("successful model routing still needs human acceptance","human_gate",result.nextAction());

        Fixture pinned=new Fixture();
        result=pinned.loop((u,p,t,targets,progress)->{
            check.that("explicit model is marked fixed",targets.stream().filter(x->x.stage().equals("implement")).findFirst().orElseThrow().profilePinned());
            return advice(Map.of("implement","alternate"));
        }).withOverride(new RunOverride(Map.of("implement","primary"),Map.of(),Map.of()))
                .run(pinned.loaded(),pinned.user(),"pinned",false);
        check.that("explicit pin remains runnable",result.ok());
        check.eq("classifier cannot replace explicit model","primary",pinned.calls.getFirst());

        Fixture unsafe=new Fixture();
        result=unsafe.loop((u,p,t,targets,progress)->advice(Map.of("implement","alternate","review","dependent")))
                .run(unsafe.loaded(),unsafe.user(),"unsafe",false);
        check.that("dependent classifier advice falls back safely",result.ok());
        check.eq("dependent model never runs",List.of("primary","review"),unsafe.calls);
        check.eq("final admission rejection is explicit","final_cast_not_admissible",routing(result).get("model_selection_rejected"));

        Fixture unavailable=new Fixture();unavailable.failedPing="primary";
        result=unavailable.loop((u,p,t,targets,progress)->{
            var writer=targets.stream().filter(x->x.stage().equals("implement")).findFirst().orElseThrow();
            check.eq("failed fresh pong is excluded before classification",false,writer.candidates().stream().anyMatch(x->x.name().equals("primary")));
            return new TaskRouting.Result(RunOverride.NONE,Map.of("code","classifier_unavailable_kept_configured"));
        }).run(unavailable.loaded(),unavailable.user(),"fallback",false);
        check.that("classifier failure uses remaining configured order",result.ok());
        check.eq("failed default is not selected as fallback","alternate",unavailable.calls.getFirst());
        check.eq("fallback receipt names effective model","alternate",((Map<?,?>)routing(result).get("effective_profiles")).get("implement"));

        Fixture unavailablePin=new Fixture();unavailablePin.failedPing="primary";var classifiers=new AtomicInteger();
        result=unavailablePin.loop((u,p,t,targets,progress)->{classifiers.incrementAndGet();return advice(Map.of());})
                .withOverride(new RunOverride(Map.of("implement","primary"),Map.of(),Map.of()))
                .run(unavailablePin.loaded(),unavailablePin.user(),"unavailable",false);
        check.eq("unavailable explicit pin stops clearly","model_selection_unavailable",result.reason());
        check.eq("unavailable pin spends no classifier call",0,classifiers.get());
        check.eq("unavailable pin dispatches no worker",List.of(),unavailablePin.calls);

        Fixture unavailableCheck=new Fixture();unavailableCheck.readinessThrows=true;
        result=unavailableCheck.loop((u,p,t,targets,progress)->{throw new AssertionError("classifier must not run without checked candidates");})
                .run(unavailableCheck.loaded(),unavailableCheck.user(),"check-failure",false);
        check.eq("failed check infrastructure leaves a durable stop","model_selection_unavailable",result.reason());
        check.eq("check infrastructure failure dispatches no worker",List.of(),unavailableCheck.calls);

        Fixture continuation=new Fixture();continuation.rateFirstReview=true;var selections=new AtomicInteger();
        TaskRouting chooseOnce=(u,p,t,targets,progress)->{
            var writer=targets.stream().filter(x->x.stage().equals("implement")).findFirst().orElseThrow();
            if(writer.profilePinned())return advice(Map.of());
            selections.incrementAndGet();return advice(Map.of("implement","alternate"));
        };
        var first=continuation.loop(chooseOnce).run(continuation.loaded(),continuation.user(),"first",false);
        check.eq("unfinished reviewer is an infrastructure stop","rate_limited",first.reason());
        var decisions=new ApprovalStore(continuation.root);
        decisions.resolve("first",decisions.read("first").updatedAt().toString(),"retry","operator","retry endpoint");
        var resumed=continuation.loop(chooseOnce).run(continuation.loaded(),continuation.user(),"resumed",false,Map.of(),
                new TaskLoop.Continuation("first",null,true));
        check.that("continued selected cast completes",resumed.ok());
        check.eq("model is selected only once across continuation",1,selections.get());
        check.eq("continuation does not repay the implementer",List.of("alternate","review","review"),continuation.calls);
        check.eq("continuation identifies selection reuse","first",routing(resumed).get("selection_reused_from"));
        check.that("prior implementation judgement is reused",resumed.summaryReport().get("reused_judgements") instanceof Map<?,?>);
    }
    private static Map<?,?> routing(TaskLoop.Outcome result){return (Map<?,?>)result.summaryReport().get("task_routing");}
    private static TaskRouting.Result advice(Map<String,String> choices){return new TaskRouting.Result(new RunOverride(choices,Map.of(),Map.of()),
            Map.of("code","routing_models_selected","applied_profiles",choices));}
    private static final class Fixture {
        final Path scratch=Files.createTempDirectory("warden-model-loop-"),root=scratch.resolve("project"),home=scratch.resolve("home");
        final List<String> calls=new ArrayList<>();String failedPing;boolean rateFirstReview,readinessThrows;int reviewCount;
        Fixture()throws Exception {
            new UserSetup().run(home);Files.createDirectories(root.resolve("src"));Files.createDirectories(root.resolve(".warden/tasks"));
            Files.writeString(root.resolve("src/base.txt"),"baseline\n");Files.writeString(root.resolve(".gitignore"),".warden/\n");
            Files.writeString(root.resolve(".warden/project.yaml"),"version: 1\nproject: model-loop\nbase_ref: HEAD\nchecks: {pass: ['java -version']}\n");
            Files.writeString(root.resolve(".warden/tasks/task.yaml"),"version: 1\nid: task\ngoal: Create src/result.txt containing ok\nrisk: medium\nscope: [src]\nacceptance: ['java -version']\nmax_fix_attempts: 0\nbudgets: {max_role_runs: 6, cost_cap: off}\n");
            Files.writeString(CheckSettings.file(home),"version: 1\nrouting:\n  model_selection_enabled: true\n");
            for(var row:List.of(new String[]{"primary","implementer","openai"},new String[]{"alternate","implementer","anthropic"},
                    new String[]{"unverified","implementer","x-ai"},new String[]{"missing","implementer","z-ai"},
                    new String[]{"review","reviewer","google"},new String[]{"dependent","reviewer","anthropic"}))
                Files.writeString(home.resolve("profiles/"+row[0]+".yaml"),"version: 1\nprofile: "+row[0]+"\nrole: "+row[1]+"\nvendor: "+row[2]
                        +"\nmodel: fixture-model\nrunner: direct\ncommand: java\nargs: ['-version']\nread_only: "+row[1].equals("reviewer")
                        +"\nprompt_template: prompts/"+row[1]+".md\njson_schema: schemas/"+row[1]+".json\n"
                        +(row[0].equals("unverified")?"":"verification: {verified_on: '2026-10-29'}\n"));
            Files.writeString(home.resolve("policy.yaml"),"""
                    version: 1
                    roles:
                      implementer: {profiles: [primary, alternate, unverified, missing], strategy: first}
                      reviewer: {profiles: [review, dependent], strategy: first, require_independent_vendor: true}
                    review: {required_for_risk: [medium, high]}
                    workflow:
                      stages:
                        - {stage: implement, run: role, role: implementer, on_fail: stop}
                        - {stage: gates, run: machine_gates, on_fail: stop}
                        - {stage: review, run: role, role: reviewer, on_fail: stop, on_findings: stop}
                    """);
            var process=new ProcessRunner();
            for(var args:List.of(List.of("init","--quiet"),List.of("config","user.email","test@example.invalid"),List.of("config","user.name","Warden Test"),
                    List.of("add","."),List.of("commit","--quiet","-m","test: model selection fixture"))){
                var cmd=new ArrayList<String>(List.of("git"));cmd.addAll(args);
                if(!process.run(cmd,root,Duration.ofSeconds(15)).ok())throw new IllegalStateException("fixture git failed");
            }
        }
        ConfigLoader.Loaded loaded()throws Exception{return new ConfigLoader().load(root,"task");}
        UserConfig user()throws Exception{return UserConfig.load(home);}
        TaskLoop loop(TaskRouting routing){return new TaskLoop(new ProcessRunner()).withOrcaGate(false).withTaskRouting(routing)
                .withAvailability(p->!p.name().equals("missing"))
                .withReadiness((u,r,profiles,strict,progress)->{
                    if(readinessThrows)throw new java.io.IOException("fixture readiness unavailable");
                    return Map.of("ok",profiles.stream().noneMatch(p->p.name().equals(failedPing)
                        || p.name().equals("missing") || p.name().equals("unverified")));})
                .withExecutors((p,process,git)->request->{
                    calls.add(request.profile().name());boolean writer=request.role().equals("implementer");
                    if(!writer && ++reviewCount==1 && rateFirstReview)
                        return new RoleExecutor.Result(false,"role_api_rate_limited",Duration.ofMillis(1),"",null,Map.of("vendor_turn_started",true));
                    if(writer)Files.writeString(root.resolve("src/result.txt"),"ok\n");
                    var artifact=new LinkedHashMap<String,Object>();artifact.put("role",request.role());artifact.put("task_id","task");
                    artifact.put("status","completed");artifact.put("summary","fixture role passed");
                    if(writer)artifact.put("files_changed",List.of("src/result.txt"));else {artifact.put("verdict","pass");artifact.put("findings",List.of());}
                    return new RoleExecutor.Result(true,"ok",Duration.ofMillis(1),"",artifact,Map.of("vendor_turn_started",true));
                });}
    }
}
