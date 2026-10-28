package dev.warden.run;

import dev.warden.config.*;
import dev.warden.execution.DecisionClient;
import dev.warden.execution.ModelCheck;
import dev.warden.json.Json;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Jev supplies bounded judgments; Warden owns stages, admission, independence and overrides. */
@FunctionalInterface
public interface TaskRouting {
    record Target(String stage,String role,Profile profile) {}
    record Result(RunOverride overrides,Map<String,Object> report) {}
    Result route(UserConfig user,Path project,TaskSpec.ResolvedTask task,List<Target> targets,Consumer<String> progress)throws Exception;
    static TaskRouting live(){return using(new DecisionClient());}
    static TaskRouting using(DecisionClient client){return using(client,false);}
    static TaskRouting using(DecisionClient client,boolean manual){
        return (user,project,task,targets,progress)->{
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("applied_efforts",Map.of());report.put("mode","effort_only_existing_stages");
            try{
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("classification cancelled");
                var settings=RoutingSettings.load(user.home());
                if(!manual && !settings.enabled())return unchanged("routing_disabled",report);
                if(task.budget().strictCostCap())return unchanged("routing_skipped_strict_budget",report);
                var profile=DecisionProfile.load(user.home()).get(settings.classifier());
                if(profile==null)return unchanged("classifier_profile_unavailable",report);
                JevRoutingPolicy policy;
                try{policy=JevRoutingPolicy.load(user.home());}
                catch(Exception invalid){return recorded(user,unchanged("routing_policy_invalid_kept_configured",report));}
                String policyHash=policy.fingerprint();report.put("policy_file",JevRoutingPolicy.file(user.home()).toString());report.put("policy_hash",policyHash);
                var questions=new LinkedHashMap<String,Map<String,Object>>();
                questions.put("kind",DecisionClient.choice(policy.instructions().get("kind"),policy.criteria().get("kind")));
                for(String role:List.of("planner","reviewer","visual_qa"))
                    questions.put(role,DecisionClient.choice(policy.instructions().get(role),policy.criteria().get("boolean")));
                Map<String,Target> eligible=new LinkedHashMap<>();List<Map<String,Object>> descriptions=new ArrayList<>();
                for(Target target:targets){
                    Profile p=target.profile();if(p==null)continue;
                    var description=new LinkedHashMap<String,Object>();description.put("stage",target.stage());description.put("role",target.role());
                    description.put("profile",p.name());description.put("model",p.model());description.put("configured_effort",p.effort());descriptions.add(description);
                    List<String> levels=supportedEfforts(p);
                    if(levels.isEmpty())continue;
                    if(eligible.size()>=12)break;
                    String key="effort_"+eligible.size();eligible.put(key,target);
                    var options=policy.options("effort",levels);
                    questions.put(key,DecisionClient.choice(policy.effortInstructions(target.stage(),target.role()),options));
                }
                progress.accept("route "+profile.name()+" · classifying task and effort …");
                var result=client.decide(profile,user,Map.of("task",task.goal(),"risk",task.risk(),"stages",descriptions),questions);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("classification cancelled");
                report.putAll(result.evidence());report.put("classifier",profile.name());
                try{if(!policyHash.equals(JevRoutingPolicy.load(user.home()).fingerprint()))return recorded(user,unchanged("routing_policy_changed_kept_configured",report));}
                catch(Exception changed){return recorded(user,unchanged("routing_policy_changed_kept_configured",report));}
                if(!result.ok())return recorded(user,unchanged(result.code(),report));
                Map<String,String> efforts=new LinkedHashMap<>();Map<String,Object> recommendations=new LinkedHashMap<>();
                report.put("classification",result.answers().get("kind"));
                for(String role:List.of("planner","reviewer","visual_qa"))recommendations.put(role,result.answers().get(role));
                report.put("recommended_roles",recommendations);report.put("roles_changed",false);
                List<String> uncertain=new ArrayList<>();Map<String,Object> effortDecisions=new LinkedHashMap<>();
                for(var entry:eligible.entrySet()){
                    Map<String,Object> answer=result.answers().get(entry.getKey());
                    effortDecisions.put(entry.getValue().stage(),answer);
                    // Jev's confidence is concentration, not the probability of correctness.
                    // Adjacent ambiguity (xhigh vs max) should not send a hard task back to
                    // medium. Use a supported lower quantile, rejecting broadly spread advice.
                    String effort=chooseEffort(answer,settings.minConfidence(),supportedEfforts(entry.getValue().profile()));
                    if(effort==null){uncertain.add(entry.getValue().stage());continue;}
                    if("high".equals(task.risk()) && List.of("low","medium").contains(effort))effort="high";
                    var proposed=new RunOverride(Map.of(),Map.of(entry.getValue().stage(),effort),Map.of());
                    if(proposed.problem(entry.getValue().stage(),entry.getValue().profile(),user.profiles())==null)
                        efforts.put(entry.getValue().stage(),effort);
                }
                report.put("applied_efforts",efforts);report.put("effort_decisions",effortDecisions);report.put("effort_policy","supported_lower_quantile");report.put("uncertain_stages",uncertain);report.put("code",efforts.isEmpty()?"routing_kept_configured":"routing_efforts_selected");
                report.put("ok",true);progress.accept("route "+report.get("code")+" · "+efforts);
                return recorded(user,new Result(new RunOverride(Map.of(),efforts,Map.of()),report));
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            catch(Exception unavailable){return recorded(user,unchanged("classifier_unavailable_kept_configured",report));}
        };
    }
    /** Explicit model capabilities; a transport flag alone does not establish valid levels. */
    static List<String> supportedEfforts(Profile profile){
        if(profile==null || profile.harness()!=null || profile.model()==null)return List.of();
        if(!"orca".equals(profile.runner()) && !("direct".equals(profile.runner()) && profile.args().stream().anyMatch(a->a.contains("{{effort}}"))))return List.of();
        String vendor=Profile.canonicalVendor(profile.modelVendor());
        if("openai".equals(vendor) && profile.model().startsWith("gpt-6"))return List.of("low","medium","high","xhigh","max");
        if("x-ai".equals(vendor) && "grok-4.7".equals(profile.model()))return List.of("low","medium","high","xhigh");
        return List.of();
    }
    private static String chooseEffort(Map<String,Object> answer,double threshold,List<String> levels){
        var probabilities=(Map<?,?>)answer.get("probabilities");
        double bestBand=0;
        for(int index=0;index<levels.size()-1;index++)bestBand=Math.max(bestBand,
                ((Number)probabilities.get(levels.get(index))).doubleValue()+((Number)probabilities.get(levels.get(index+1))).doubleValue());
        if(bestBand+1e-9<threshold)return null;
        double atLeast=0;
        for(int index=levels.size()-1;index>=0;index--){atLeast+=((Number)probabilities.get(levels.get(index))).doubleValue();if(atLeast>0 && atLeast+1e-9>=threshold)return levels.get(index);}
        return null;
    }
    private static Result unchanged(String code,Map<String,Object> report){report.put("ok",true);report.put("code",code);report.put("fallback","configured_workflow_and_efforts");return new Result(RunOverride.NONE,report);}
    private static Result recorded(UserConfig user,Result result)throws Exception{
        var report=new LinkedHashMap<>(result.report());report.put("at",java.time.Instant.now().toString());
        Path receipt=user.home().resolve("routing").resolve("route-"+UUID.randomUUID()+".json");
        ModelCheck.atomicWrite(receipt,Json.write(report));report.put("receipt",receipt.toString());return new Result(result.overrides(),report);
    }
}
