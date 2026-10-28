package dev.warden;

import dev.warden.config.*;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.*;
import dev.warden.testing.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime integration: actual worker effort and mandatory gates, not just advice formatting. */
public final class TaskRoutingLoopTest implements Suite {
    public String name(){return "task-routing-loop";}
    public void run(Check check)throws Exception{
        Path scratch=Files.createTempDirectory("warden-routing-loop-");Path root=scratch.resolve("project"),home=scratch.resolve("home");
        Files.createDirectories(root.resolve("src"));Files.createDirectories(root.resolve(".warden/tasks"));new UserSetup().run(home);
        Files.writeString(root.resolve("src/base.txt"),"baseline");
        Files.writeString(root.resolve(".gitignore"),".warden/\n");
        Files.writeString(root.resolve(".warden/project.yaml"),"version: 1\nproject: route-loop\nbase_ref: HEAD\nchecks: {pass: ['java -version']}\n");
        Files.writeString(root.resolve(".warden/tasks/task.yaml"),"version: 1\nid: task\ngoal: Create src/result.txt containing ok\nrisk: medium\nscope: [src]\nacceptance: ['java -version']\nmax_fix_attempts: 0\nbudgets: {max_role_runs: 4, max_cost_usd: 0.001, cost_cap: off}\n");
        var process=new ProcessRunner();git(process,root,List.of("init","--quiet"));git(process,root,List.of("config","user.email","test@example.invalid"));git(process,root,List.of("config","user.name","Warden Test"));
        git(process,root,List.of("add","."));git(process,root,List.of("commit","--quiet","-m","test: routing fixture baseline"));
        Files.writeString(home.resolve("profiles/route-impl.yaml"),profile("route-impl","implementer","codex","impl",false,scratch));
        Files.writeString(home.resolve("profiles/route-review.yaml"),profile("route-review","reviewer","reviewvendor","review",true,scratch));
        Files.writeString(home.resolve("policy.yaml"),"""
                version: 1
                roles:
                  implementer: {profiles: [route-impl], strategy: first}
                  reviewer: {profiles: [route-review], strategy: first, require_independent_vendor: true}
                review: {required_for_risk: [medium, high]}
                workflow:
                  stages:
                    - stage: implement
                      run: role
                      role: implementer
                      on_fail: stop
                    - stage: gates
                      run: machine_gates
                      on_fail: stop
                    - stage: review
                      run: role
                      role: reviewer
                      on_fail: stop
                """);
        AtomicInteger classifications=new AtomicInteger();
        TaskRouting routing=(user,project,task,targets,progress)->{
            classifications.incrementAndGet();
            return new TaskRouting.Result(new RunOverride(Map.of(),Map.of("implement","high"),Map.of()),
                    Map.of("code","routing_efforts_selected","applied_efforts",Map.of("implement","high"),"recommended_roles",Map.of("reviewer","no")));
        };
        var loaded=new ConfigLoader().load(root,"task");var user=UserConfig.load(home);
        var loop=new TaskLoop(process).withTaskRouting(routing)
                .withOverride(new RunOverride(Map.of(),Map.of("implement","low"),Map.of()))
                .withReadiness((u,p,profiles,strict,progress)->{
                    var writer=profiles.stream().filter(profile->profile.name().equals("route-impl")).findFirst().orElseThrow();
                    check.eq("readiness sees the operator's effective effort","low",writer.effort());
                    return Map.of("ok",true,"code","check_passed");
                });
        loop.run(loaded,user,"preview",true);
        check.eq("preview never calls classifier",0,classifications.get());
        var outcome=loop.run(loaded,user,"routed",false);
        check.eq("classified run finishes its mandatory chain",true,outcome.ok());
        check.eq("classifier called once per actual launch",1,classifications.get());
        var cast=(List<?>)outcome.summaryReport().get("cast");var writer=(Map<?,?>)cast.stream().filter(row->row instanceof Map<?,?> m && "implement".equals(m.get("stage"))).findFirst().orElseThrow();
        check.eq("explicit effort wins over Jev advice","low",writer.get("effort"));
        var report=(Map<?,?>)outcome.summaryReport().get("task_routing");
        check.eq("receipt distinguishes effective effort from advice","low",((Map<?,?>)report.get("effective_efforts")).get("implement"));
        check.eq("overridden advice is not reported as applied",Map.of(),report.get("applied_efforts"));
        check.that("mandatory reviewer still ran despite contrary role advice",Files.exists(scratch.resolve("route-review.count")));
        check.that("off mode records spend without stopping at the dollar threshold",((Number)outcome.summaryReport().get("total_cost_usd")).doubleValue()>0.001);
        check.eq("successful run still waits for human approval","human_gate",outcome.nextAction());
        check.that("run keeps routing evidence",Files.isRegularFile(root.resolve(".warden/runs/routed/task-routing.json")));
        routedContinuationKeepsVerdicts(check);
    }
    /** A continuation compares a kept verdict with the effort routing selects, not the profile default. */
    private void routedContinuationKeepsVerdicts(Check check)throws Exception{
        Path scratch=Files.createTempDirectory("warden-routing-resume-");Path root=scratch.resolve("project"),home=scratch.resolve("home");
        Files.createDirectories(root.resolve("src"));Files.createDirectories(root.resolve(".warden/tasks"));new UserSetup().run(home);
        Files.writeString(root.resolve("src/base.txt"),"baseline");
        Files.writeString(root.resolve(".gitignore"),".warden/\n");
        Files.writeString(root.resolve(".warden/project.yaml"),"version: 1\nproject: route-resume\nbase_ref: HEAD\nchecks: {pass: ['java -version']}\n");
        Files.writeString(root.resolve(".warden/tasks/hello.yaml"),"version: 1\nid: hello\ngoal: Create src/result.txt containing ok\nrisk: medium\nscope: [src]\nacceptance: ['java -version']\nmax_fix_attempts: 0\nbudgets: {max_role_runs: 6, max_cost_usd: 1, cost_cap: off}\n");
        var process=new ProcessRunner();git(process,root,List.of("init","--quiet"));git(process,root,List.of("config","user.email","test@example.invalid"));git(process,root,List.of("config","user.name","Warden Test"));
        git(process,root,List.of("add","."));git(process,root,List.of("commit","--quiet","-m","test: routing resume baseline"));
        Files.writeString(home.resolve("profiles/resume-impl.yaml"),profile("resume-impl","implementer","codex","impl",false,scratch));
        Files.writeString(home.resolve("profiles/resume-review.yaml"),profile("resume-review","reviewer","reviewvendor","sequence:rate,pass",true,scratch));
        Files.writeString(home.resolve("policy.yaml"),"""
                version: 1
                roles:
                  implementer: {profiles: [resume-impl], strategy: first}
                  reviewer: {profiles: [resume-review], strategy: first, require_independent_vendor: true}
                review: {required_for_risk: [medium, high]}
                workflow:
                  stages:
                    - stage: implement
                      run: role
                      role: implementer
                      on_fail: stop
                    - stage: gates
                      run: machine_gates
                      on_fail: stop
                    - stage: review
                      run: role
                      role: reviewer
                      on_fail: stop
                """);
        TaskRouting routing=(user,project,task,targets,progress)->new TaskRouting.Result(
                new RunOverride(Map.of(),Map.of("implement","high"),Map.of()),
                Map.of("code","routing_efforts_selected","applied_efforts",Map.of("implement","high")));
        var first=new TaskLoop(process).withTaskRouting(routing)
                .run(new ConfigLoader().load(root,"hello"),UserConfig.load(home),"rr1",false);
        check.eq("a rate-limited reviewer stops the routed run on the endpoint","rate_limited",first.reason());
        var decisions=new dev.warden.approval.ApprovalStore(root);
        decisions.resolve("rr1",decisions.read("rr1").updatedAt().toString(),"retry","operator","limit reset");
        var resumed=new TaskLoop(process).withTaskRouting(routing)
                .run(new ConfigLoader().load(root,"hello"),UserConfig.load(home),"rr2",false,Map.of(),
                        new TaskLoop.Continuation("rr1",null,true));
        check.that("the routed continuation reaches the human gate",resumed.ok());
        var reused=(Map<?,?>)resumed.summaryReport().get("reused_judgements");
        check.that("and keeps the implementer that ran at the routed effort",
                reused!=null && ((List<?>)reused.get("stages")).contains("implement"));
        check.eq("so the writer was dispatched once across both runs","1",
                Files.readString(scratch.resolve("resume-impl.count")).strip());
    }
    private static void git(ProcessRunner runner,Path root,List<String> args)throws Exception{
        var command=new ArrayList<String>();command.add("git");command.addAll(args);
        var result=runner.run(command,root,Duration.ofSeconds(15));if(!result.ok())throw new IllegalStateException("git fixture failed");
    }
    private static String profile(String name,String role,String vendor,String mode,boolean readOnly,Path scratch){
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        String cp=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(s->Path.of(s).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var args=List.of("-cp",cp,"dev.warden.testing.StubVendor",mode,"--counter",scratch.resolve(name+".count").toString(),"--threshold","1","--prompt-file","{{prompt_file}}","--effort","{{effort}}");
        return "version: 1\nprofile: "+name+"\nrole: "+role+"\nvendor: "+vendor+"\nmodel: gpt-6.1-sol\neffort: medium\ncommand: "+Json.write(javaExecutable)+"\nargs: "+Json.write(args)+"\nread_only: "+readOnly+"\nprompt_template: prompts/"+role+".md\njson_schema: schemas/"+role+".json\nverification: {verified_on: '2026-10-28'}\n";
    }
}
