package dev.warden.run;

import dev.warden.config.*;
import dev.warden.execution.*;
import dev.warden.json.Json;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Jev may rank only the admitted role roster. Warden validates the resulting cast. */
public final class ModelSelection {
    private ModelSelection() {}
    public record Selection(List<TaskRouting.Target> targets,Map<String,String> profiles,
                            Map<String,Object> report,boolean failed) {}
    private static final int MAX_STAGES=12,MAX_CANDIDATES=16;

    public static Selection choose(DecisionClient client,DecisionProfile classifier,UserConfig user,
            TaskSpec.ResolvedTask task,List<TaskRouting.Target> targets,RoutingSettings settings,
            JevRoutingPolicy policy,Consumer<String> progress)throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("enabled",true);report.put("policy_hash",policy.fingerprint());
        String snapshot=snapshot(user,targets);report.put("catalog_snapshot",snapshot);
        var capabilities=new EffortCapabilities(user.home(),user);
        var questions=new LinkedHashMap<String,Map<String,Object>>();
        var asked=new LinkedHashMap<String,TaskRouting.Target>();
        var optionsByStage=new LinkedHashMap<String,Map<String,Profile>>();
        var decisions=new ArrayList<Map<String,Object>>();
        for(var target:targets){
            var decision=new LinkedHashMap<String,Object>();
            decision.put("stage",target.stage());decision.put("role",target.role());
            decision.put("configured_profile",target.profile()==null?null:target.profile().name());
            decision.put("selected_profile",target.profile()==null?null:target.profile().name());
            decision.put("selected_model",target.profile()==null?null:target.profile().model());
            decision.put("profile_pinned",target.profilePinned());decision.put("rejected",target.rejected());
            var candidates=new ArrayList<Map<String,Object>>();var options=new LinkedHashMap<String,Profile>();
            for(Profile candidate:target.candidates()){
                if(candidate==null || !target.role().equals(candidate.role()))continue;
                String key="candidate_"+options.size();options.put(key,candidate);
                var row=new LinkedHashMap<String,Object>();row.put("option",key);row.put("profile",candidate.name());
                row.put("model",candidate.model());row.put("vendor",candidate.independenceVendor());
                row.put("runner",candidate.harness()==null?candidate.runner():candidate.harness());
                row.put("read_only",candidate.readOnly());row.put("effort",candidate.effort());
                row.put("capability_hash",hash(Json.write(capabilities.describe(candidate))));
                row.put("cost_status","unknown");candidates.add(row);
            }
            decision.put("candidates",candidates);decisions.add(decision);
            if(target.profilePinned()){decision.put("reason","explicit_profile");continue;}
            if(options.isEmpty()){decision.put("reason","no_admissible_candidates");continue;}
            if(options.size()==1){decision.put("reason","configured_only_candidate");continue;}
            if(options.size()>MAX_CANDIDATES || asked.size()>=MAX_STAGES){decision.put("reason","candidate_limit");continue;}
            String question="model_"+asked.size();asked.put(question,target);optionsByStage.put(target.stage(),options);
            var criteria=new LinkedHashMap<String,String>();
            for(var entry:options.entrySet()){
                var p=entry.getValue();criteria.put(entry.getKey(),"Profile "+p.name()+", model "+p.model()+", developer "+p.independenceVendor()+
                        ", runner "+(p.harness()==null?p.runner():p.harness())+", "+(p.readOnly()?"read-only":"workspace-writing")+
                        ". Cost is unknown; do not treat unknown cost as free or forbidden.");
            }
            questions.put(question,DecisionClient.choice("Choose the best authorized profile for stage "+target.stage()+
                    " (role "+target.role()+") for state.task. Consider task fit and the admitted tool capability. Only these options are authorized. "+
                    "Do not remove mandatory review or add roles. Different reviewer and writer model developers are required when policy says independent review. "+
                    "Return a distribution over the supplied candidates; uncertainty retains configured order.",criteria));
        }
        report.put("decisions",decisions);
        if(questions.isEmpty())return finish(targets,Map.of(),report,false,"model_selection_kept_configured");
        progress.accept("route "+classifier.name()+" · selecting among authorized role models …");
        var state=new LinkedHashMap<String,Object>();state.put("task",task.goal());state.put("risk",task.risk());
        state.put("stages",decisions);state.put("roles_changed",false);
        var result=client.decide(classifier,user,state,questions);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("model selection cancelled");
        report.put("classifier",result.evidence());
        if(!snapshot.equals(snapshot(user,targets)))return finish(targets,Map.of(),report,true,"configuration_changed");
        if(!result.ok())return finish(targets,Map.of(),report,true,result.code());
        var selected=new LinkedHashMap<String,String>();
        for(var question:asked.entrySet()){
            var target=question.getValue();var options=optionsByStage.get(target.stage());
            var answer=result.answers().get(question.getKey());
            var decision=decisions.stream().filter(d->target.stage().equals(d.get("stage"))).findFirst().orElseThrow();
            decision.put("answer",answer);
            String chosen=choose(answer,settings.minConfidence(),options.keySet());
            if(chosen==null){decision.put("reason","classifier_uncertain");continue;}
            Profile pick=options.get(chosen);
            selected.put(target.stage(),pick.name());decision.put("reason","classifier_selected");
            decision.put("selected_profile",pick.name());decision.put("selected_model",pick.model());
        }
        var effective=new ArrayList<TaskRouting.Target>();
        for(var target:targets){
            Profile chosen=target.profile();String name=selected.get(target.stage());
            if(name!=null)chosen=optionsByStage.get(target.stage()).values().stream().filter(p->name.equals(p.name())).findFirst().orElseThrow();
            effective.add(new TaskRouting.Target(target.stage(),target.role(),chosen,target.candidates(),target.rejected(),target.profilePinned()));
        }
        if(!independent(user,effective)){
            report.put("joint_validation","configured_fallback");
            return finish(targets,Map.of(),report,false,"dependent_selection");
        }
        report.put("joint_validation","passed");
        return finish(List.copyOf(effective),Map.copyOf(selected),report,false,selected.isEmpty()?"model_selection_kept_configured":"models_selected");
    }

    /** Unique, supported maximum probability above the operator's threshold. */
    public static String choose(Map<String,Object> answer,double threshold,Set<String> options){
        if(answer==null || options.isEmpty() || !Double.isFinite(threshold) || threshold<0 || threshold>1
                || !(answer.get("probabilities") instanceof Map<?,?> p) || !p.keySet().equals(options))return null;
        String best=null;double maximum=-1,sum=0;boolean tie=false;
        for(String key:options){
            if(!(p.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0 || n.doubleValue()>1)return null;
            double value=n.doubleValue();sum+=value;
            if(value>maximum+1e-9){best=key;maximum=value;tie=false;}else if(Math.abs(value-maximum)<=1e-9)tie=true;
        }
        if(Math.abs(sum-1)>0.02 || tie || maximum+1e-9<threshold || !Objects.equals(best,answer.get("choice")))return null;
        return best;
    }
    private static boolean independent(UserConfig user,List<TaskRouting.Target> targets){
        var writers=new HashSet<String>();
        for(var target:targets)if(target.profile()!=null && !target.profile().readOnly())writers.add(target.profile().independenceVendor());
        for(var target:targets){
            var spec=user.policy()==null?null:user.policy().roles().get(target.role());
            if(target.profile()!=null && target.profile().readOnly() && spec!=null && spec.requireIndependentVendor()
                    && writers.contains(target.profile().independenceVendor()))return false;
        }
        return true;
    }
    private static Selection finish(List<TaskRouting.Target> targets,Map<String,String> profiles,
            Map<String,Object> report,boolean failed,String code){
        report.put("code",code);
        if(report.get("decisions") instanceof List<?> decisions)for(Object item:decisions)if(item instanceof Map<?,?> raw){
            @SuppressWarnings("unchecked") var decision=(Map<String,Object>)raw;
            if(!decision.containsKey("reason"))decision.put("reason",failed?"classifier_failed":"configured_only_candidate");
        }
        if(!report.containsKey("joint_validation"))report.put("joint_validation","configured_fallback");
        return new Selection(targets,profiles,report,failed);
    }
    /** Hash only; paths and personal file contents are never added to the classifier state/receipt. */
    static String snapshot(UserConfig user,List<TaskRouting.Target> targets)throws Exception{
        var material=new StringBuilder();
        var paths=new TreeSet<Path>();paths.add(user.home().resolve("policy.yaml"));paths.add(CheckSettings.file(user.home()));paths.add(JevRoutingPolicy.file(user.home()));
        for(var target:targets){
            var profiles=new ArrayList<>(target.candidates());if(target.profile()!=null)profiles.add(target.profile());
            for(var profile:profiles){
                paths.add(user.home().resolve("profiles").resolve(profile.name()+".yaml"));
                if(profile.connectionRef()!=null)paths.add(user.home().resolve("connections").resolve(profile.connectionRef()+".yaml"));
                if(profile.promptTemplate()!=null)paths.add(user.resolve(profile.promptTemplate()));
                if(profile.jsonSchema()!=null)paths.add(user.resolve(profile.jsonSchema()));
                if(profile.mcpConfig()!=null)paths.add(user.resolve(profile.mcpConfig()));
                paths.add(user.home().resolve("vendors").resolve(VendorSettingsService.displayVendorId(profile)+"-catalog.json"));
                material.append(profile.name()).append(':').append(profile.model()).append(':').append(profile.effort())
                        .append(':').append(profile.apiFingerprint()).append(':').append(profile.args()).append(';');
            }
        }
        for(Path path:paths){material.append(path.getFileName()).append(':');
            material.append(Files.isRegularFile(path)?hash(Files.readString(path)):"absent").append(';');}
        return hash(material.toString());
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
