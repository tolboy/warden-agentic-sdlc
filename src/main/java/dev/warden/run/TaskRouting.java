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
    record Target(String stage,String role,Profile profile,List<Profile> candidates,Map<String,String> rejected,boolean profilePinned) {
        public Target(String stage,String role,Profile profile){this(stage,role,profile,profile==null?List.of():List.of(profile),Map.of(),false);}
        public Target { candidates=List.copyOf(candidates);rejected=Map.copyOf(rejected); }
    }
    record Result(RunOverride overrides,Map<String,Object> report) {}
    Result route(UserConfig user,Path project,TaskSpec.ResolvedTask task,List<Target> targets,Consumer<String> progress)throws Exception;
    static TaskRouting live(){return using(new DecisionClient());}
    static TaskRouting using(DecisionClient client){return using(client,false);}
    static TaskRouting using(DecisionClient client,boolean manual){
        return (user,project,task,targets,progress)->{
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("applied_efforts",Map.of());report.put("applied_profiles",Map.of());report.put("mode","effort_only_existing_stages");
            try{
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("classification cancelled");
                var settings=RoutingSettings.load(user.home());
                if(!manual && !settings.enabled() && !settings.modelSelectionEnabled())return unchanged("routing_disabled",report);
                if(task.budget().strictCostCap())return unchanged("routing_skipped_strict_budget",report);
                var profile=DecisionProfile.load(user.home()).get(settings.classifier());
                var capabilities=new dev.warden.execution.EffortCapabilities(user.home(),user);
                if(profile==null)return unchanged("classifier_profile_unavailable",report);
                report.put("classifier",profile.name());
                JevRoutingPolicy policy;
                try{policy=JevRoutingPolicy.load(user.home());}
                catch(Exception invalid){return recorded(user,unchanged("routing_policy_invalid_kept_configured",report));}
                String policyHash=policy.fingerprint();report.put("policy_file",JevRoutingPolicy.file(user.home()).toString());report.put("policy_hash",policyHash);
                List<Target> modelInputs=targets;
                Map<String,String> selectedModels=Map.of();
                if(settings.modelSelectionEnabled()){
                    var selection=ModelSelection.choose(client,profile,user,task,targets,settings,policy,progress);
                    report.put("model_selection",selection.report());
                    if(selection.failed())return recorded(user,unchanged(String.valueOf(selection.report().get("code")),report));
                    targets=selection.targets();selectedModels=selection.profiles();
                    report.put("mode","authorized_models_and_effort");report.put("applied_profiles",selectedModels);
                    report.put("suggested_profiles",selectedModels);
                    if(!manual && !settings.enabled()){
                        report.put("ok",true);report.put("roles_changed",false);
                        report.put("code",selectedModels.isEmpty()?"routing_kept_configured":"routing_models_selected");
                        return recorded(user,new Result(new RunOverride(selectedModels,Map.of(),Map.of()),report));
                    }
                }
                var questions=new LinkedHashMap<String,Map<String,Object>>();
                questions.put("kind",DecisionClient.choice(policy.instructions().get("kind"),policy.criteria().get("kind")));
                for(String role:List.of("planner","reviewer","visual_qa"))
                    questions.put(role,DecisionClient.choice(policy.instructions().get(role),policy.criteria().get("boolean")));
                Map<String,Target> eligible=new LinkedHashMap<>();List<Map<String,Object>> descriptions=new ArrayList<>();
                for(Target target:targets){
                    Profile p=target.profile();if(p==null)continue;
                    var description=new LinkedHashMap<String,Object>();description.put("stage",target.stage());description.put("role",target.role());
                    description.put("profile",p.name());description.put("model",p.model());description.put("configured_effort",p.effort());descriptions.add(description);
                    description.put("effort_capabilities",capabilities.describe(p));
                    // The router chooses only among the model's levels that the classifier policy can name.
                    List<String> levels=supportedEfforts(capabilities.levels(p));
                    if(levels.isEmpty())continue;
                    if(eligible.size()>=12)break;
                    String key="effort_"+eligible.size();eligible.put(key,target);
                    var options=policy.options("effort",levels);
                    questions.put(key,DecisionClient.choice(policy.effortInstructions(target.stage(),target.role()),options));
                }
                report.put("stage_capabilities",descriptions);
                progress.accept("route "+profile.name()+" · classifying task and effort …");
                var result=client.decide(profile,user,Map.of("task",task.goal(),"risk",task.risk(),"stages",descriptions),questions);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("classification cancelled");
                report.putAll(result.evidence());report.put("classifier",profile.name());
                try{if(!policyHash.equals(JevRoutingPolicy.load(user.home()).fingerprint()))return recorded(user,unchanged("routing_policy_changed_kept_configured",report));}
                catch(Exception changed){return recorded(user,unchanged("routing_policy_changed_kept_configured",report));}
                if(report.get("model_selection") instanceof Map<?,?> modelReport && !Objects.equals(
                        modelReport.get("catalog_snapshot"),ModelSelection.snapshot(user,modelInputs)))
                    return recorded(user,unchanged("model_roster_changed_kept_configured",report));
                if(!result.ok())return recorded(user,unchanged(result.code(),report));
                Map<String,String> efforts=new LinkedHashMap<>();Map<String,Object> recommendations=new LinkedHashMap<>();
                report.put("classification",result.answers().get("kind"));
                for(String role:List.of("planner","reviewer","visual_qa"))recommendations.put(role,result.answers().get(role));
                report.put("recommended_roles",recommendations);report.put("roles_changed",false);
                List<String> uncertain=new ArrayList<>();Map<String,Object> effortDecisions=new LinkedHashMap<>();List<Map<String,Object>> warnings=new ArrayList<>();
                for(var entry:eligible.entrySet()){
                    Map<String,Object> answer=result.answers().get(entry.getKey());
                    effortDecisions.put(entry.getValue().stage(),answer);
                    // Jev's confidence is concentration, not the probability of correctness.
                    // Adjacent ambiguity (xhigh vs max) should not send a hard task back to
                    // medium. Use a supported lower quantile, rejecting broadly spread advice.
                    String effort=chooseEffort(answer,settings.minConfidence(),supportedEfforts(capabilities.levels(entry.getValue().profile())));
                    if(effort==null){uncertain.add(entry.getValue().stage());continue;}
                    effort=applyRiskFloor(effort,task.risk(),supportedEfforts(capabilities.levels(entry.getValue().profile())));
                    if(effort==null){uncertain.add(entry.getValue().stage());continue;}
                    var proposed=new RunOverride(Map.of(),Map.of(entry.getValue().stage(),effort),Map.of());
                    if(proposed.problem(entry.getValue().stage(),entry.getValue().profile(),user.profiles(),user)==null){
                        efforts.put(entry.getValue().stage(),effort);
                        var warning=effortWarning(entry.getValue().stage(),entry.getValue().profile(),effort,supportedEfforts(capabilities.levels(entry.getValue().profile())));
                        if(warning!=null)warnings.add(warning);
                    }
                }
                report.put("applied_efforts",efforts);report.put("effort_decisions",effortDecisions);report.put("effort_policy","supported_lower_quantile");report.put("uncertain_stages",uncertain);report.put("effort_warnings",warnings);report.put("code",!selectedModels.isEmpty()?"routing_models_selected":efforts.isEmpty()?"routing_kept_configured":"routing_efforts_selected");
                report.put("ok",true);progress.accept("route "+report.get("code")+" · "+efforts);
                for(var warning:warnings)progress.accept("warn  "+warning.get("message"));
                return recorded(user,new Result(new RunOverride(selectedModels,efforts,Map.of()),report));
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            catch(Exception unavailable){return recorded(user,unchanged("classifier_unavailable_kept_configured",report));}
        };
    }
    /** Model capabilities without a catalog (the built-in table); a transport flag alone does not establish valid levels. */
    static List<String> supportedEfforts(Profile profile){return supportedEfforts(new dev.warden.execution.EffortCapabilities(null,null).levels(profile));}
    /** Routable levels: the shared capability list narrowed to what the classifier policy defines, weakest first. */
    public static List<String> supportedEfforts(List<String> capability){return capability.stream().filter(JevRoutingPolicy.EFFORTS::contains).toList();}
    /** A risk floor may raise a proposal only to a level the actual model supports. */
    public static String applyRiskFloor(String proposed,String risk,List<String> levels){
        if(!levels.contains(proposed))return null;
        if(!"high".equals(risk)||!List.of("none","minimal","low","medium").contains(proposed))return proposed;
        for(String level:levels)if(List.of("high","xhigh","max","ultra").contains(level))return level;
        return null; // No supported high-risk level: preserve the operator's configuration.
    }
    /**
     * A warning for a Jev-chosen effort that is the model's strongest routable level or stronger
     * than the profile's configured effort; null when neither holds. Trusting Jev includes its top
     * level, so the level is applied, but the operator is told before money is spent.
     */
    public static Map<String,Object> effortWarning(String stage,Profile profile,String effort,List<String> levels){
        if(effort==null || profile==null)return null;
        boolean highest=!levels.isEmpty() && effort.equals(levels.get(levels.size()-1));
        String configured=profile.effort();
        int chosen=dev.warden.execution.EffortCapabilities.ORDER.indexOf(effort),base=configured==null?-1:dev.warden.execution.EffortCapabilities.ORDER.indexOf(configured);
        boolean above=chosen>=0 && base>=0 && chosen>base;
        if(!highest && !above)return null;
        var reasons=new ArrayList<String>();
        if(highest)reasons.add("the highest level "+profile.model()+" supports");
        if(above)reasons.add("above the configured "+configured);
        var warning=new LinkedHashMap<String,Object>();
        warning.put("stage",stage);warning.put("profile",profile.name());warning.put("model",profile.model());warning.put("effort",effort);
        warning.put("configured_effort",configured);warning.put("highest_supported",highest);warning.put("above_configured",above);
        warning.put("message","Jev chose effort "+effort+" for stage "+stage+" ("+profile.name()+"): "+String.join(", ",reasons)+"; this may be slow or expensive.");
        return warning;
    }
    /** Warnings whose stage still runs at the Jev-chosen level after explicit task/run overrides. */
    public static List<Map<String,Object>> keptWarnings(Object warnings,Map<?,?> applied){
        var kept=new ArrayList<Map<String,Object>>();if(!(warnings instanceof List<?> list))return kept;
        for(Object item:list)if(item instanceof Map<?,?> warning && applied.containsKey(warning.get("stage"))
                && java.util.Objects.equals(applied.get(warning.get("stage")),warning.get("effort"))){
            var copy=new LinkedHashMap<String,Object>();for(var e:warning.entrySet())copy.put(String.valueOf(e.getKey()),e.getValue());kept.add(copy);
        }
        return kept;
    }
    /** Select from a validated distribution; public so saved calibration samples use production logic. */
    public static String chooseEffort(Map<String,Object> answer,double threshold,List<String> levels){
        if(levels==null || levels.isEmpty() || new HashSet<>(levels).size()!=levels.size()
                || answer==null || !Double.isFinite(threshold) || threshold<0 || threshold>1
                || !(answer.get("probabilities") instanceof Map<?,?> probabilities))return null;
        if(!probabilities.keySet().equals(new HashSet<>(levels)))return null;
        double sum=0;
        for(String level:levels){
            if(!(probabilities.get(level) instanceof Number value) || !Double.isFinite(value.doubleValue())
                    || value.doubleValue()<0 || value.doubleValue()>1)return null;
            sum+=value.doubleValue();
        }
        if(Math.abs(sum-1)>0.02)return null;
        double bestBand=((Number)probabilities.get(levels.get(0))).doubleValue();
        for(int index=0;index<levels.size()-1;index++)bestBand=Math.max(bestBand,
                ((Number)probabilities.get(levels.get(index))).doubleValue()+((Number)probabilities.get(levels.get(index+1))).doubleValue());
        if(bestBand+1e-9<threshold)return null;
        double atLeast=0;
        for(int index=levels.size()-1;index>=0;index--){atLeast+=((Number)probabilities.get(levels.get(index))).doubleValue();if(atLeast>0 && atLeast+1e-9>=threshold)return levels.get(index);}
        return null;
    }
    private static Result unchanged(String code,Map<String,Object> report){report.put("applied_profiles",Map.of());report.put("ok",true);report.put("code",code);report.put("fallback","configured_workflow_and_efforts");return new Result(RunOverride.NONE,report);}
    private static Result recorded(UserConfig user,Result result)throws Exception{
        var report=new LinkedHashMap<>(result.report());report.put("at",java.time.Instant.now().toString());
        Path receipt=user.home().resolve("routing").resolve("route-"+UUID.randomUUID()+".json");
        ModelCheck.atomicWrite(receipt,Json.write(report));report.put("receipt",receipt.toString());return new Result(result.overrides(),report);
    }
}
