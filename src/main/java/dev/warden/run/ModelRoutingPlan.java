package dev.warden.run;

import dev.warden.config.*;
import dev.warden.execution.ModelCheck;
import dev.warden.role.*;
import dev.warden.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Admission and availability are decided by Warden before Jev sees a candidate label. */
final class ModelRoutingPlan {
    record Prepared(List<TaskRouting.Target> targets,Map<String,Object> checks,Map<String,Object> gap) {}
    static Prepared prepare(Path root,UserConfig user,TaskSpec.ResolvedTask task,List<Workflow.Stage> stages,
                            Workflow workflow,RoleRunner roles,RunOverride overlay,RunReadiness readiness,
                            Consumer<String> progress,String previousRun)throws Exception {
        var targets=new ArrayList<TaskRouting.Target>();var checks=new LinkedHashMap<String,Object>();
        var checked=new HashMap<String,Boolean>();var fixedVendors=new LinkedHashSet<String>(task.priorWriters());var fixedProfiles=new LinkedHashSet<String>();
        if(previousRun!=null){
            var prior=Json.parseObject(Files.readString(root.resolve(".warden/runs").resolve(previousRun).resolve("task-run.json")));
            if(prior.get("writer_vendors") instanceof List<?> vendors)for(Object vendor:vendors)if(vendor instanceof String text)fixedVendors.add(text);
        }
        for(var stage:stages)if(fixed(stage,roles,overlay)){
            var p=roles.peek(root,user,stage.name(),stage.role(),RoleResolver.Writers.NONE,workflow.rotationPositionOf(stage));
            if(p!=null && !p.readOnly()){fixedVendors.add(p.vendor());fixedProfiles.add(p.name());}
        }
        var fixedWriters=new RoleResolver.Writers(fixedVendors,fixedProfiles,true,"same_vendor_peer".equals(task.reviewAssurance()));
        for(var stage:stages){
            boolean pinned=fixed(stage,roles,overlay);
            var configured=roles.peek(root,user,stage.name(),stage.role(),RoleResolver.Writers.NONE,workflow.rotationPositionOf(stage));
            var rejected=new LinkedHashMap<String,String>();var pool=new ArrayList<Profile>();
            if(pinned){if(configured!=null)pool.add(configured);}
            else for(var c:roles.routingCandidates(user,stage.role(),fixedWriters)){
                if(c.eligible())pool.add(user.profiles().get(c.profile()));else rejected.put(c.profile(),c.rejected());
            }
            var eligible=new ArrayList<Profile>();
            for(var original:pool){
                var p=overlay.adapt(stage.name(),original,user.profiles());
                String problem=overlay.problem(stage.name(),original,user.profiles(),user);
                if(problem!=null){rejected.put(p.name(),"effort_or_host_unsupported");continue;}
                if(dev.warden.execution.EffortCapabilities.problem(p)!=null){rejected.put(p.name(),"role_effort_unsupported");continue;}
                if(p.mcpConfig()!=null&&!Files.isRegularFile(user.resolve(p.mcpConfig()))){rejected.put(p.name(),"mcp_config_missing");continue;}
                if(p.promptTemplate()==null || !Files.isRegularFile(user.resolve(p.promptTemplate()))
                        || p.jsonSchema()!=null&&!Files.isRegularFile(user.resolve(p.jsonSchema()))){rejected.put(p.name(),"role_contract_missing");continue;}
                if(stage.acquiresEvidence() && (p.vision()==null||!p.vision().acquires())){rejected.put(p.name(),"vision_acquisition_unavailable");continue;}
                var spec=user.policy().roles().get(stage.role());
                if(p.readOnly() && spec!=null && RoleResolver.assuranceOf(spec,p,user.profiles(),fixedWriters).startsWith("refused:")){
                    rejected.put(p.name(),"shares_vendor_with_writer");continue;
                }
                String identity=ModelCheck.configuration(p,user);
                if(readiness!=null&&!checked.containsKey(identity)){
                    Map<String,Object> answer;
                    try{answer=readiness.check(user,root,List.of(p),false,progress);}
                    catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
                    catch(Exception unavailable){answer=Map.of("ok",false,"code","model_check_unavailable");}
                    checked.put(identity,Boolean.TRUE.equals(answer.get("ok")));
                    checks.put(p.name(),Map.of("ok",Boolean.TRUE.equals(answer.get("ok")),"code",
                            "check_disabled_by_user".equals(answer.get("code"))?"not_checked":
                            Boolean.TRUE.equals(answer.get("ok"))?"available":"model_check_failed"));
                }
                if(Boolean.FALSE.equals(checked.get(identity))){
                    rejected.put(p.name(),"model_check_failed");if(!pinned)roles.excludeForRouting(p.name());continue;
                }
                eligible.add(p);
            }
            if(eligible.isEmpty())return new Prepared(targets,checks,Map.of("stage",stage.name(),"role",stage.role(),
                    "code",pinned?"pinned_model_unavailable":"no_admissible_model","rejected_profiles",rejected));
            String configuredName=configured==null?null:configured.name();
            if(configuredName==null || eligible.stream().noneMatch(p->p.name().equals(configuredName)))configured=eligible.getFirst();
            targets.add(new TaskRouting.Target(stage.name(),stage.role(),configured,List.copyOf(eligible),Map.copyOf(rejected),pinned));
        }
        var defaultsVendors=new LinkedHashSet<String>(fixedVendors);var defaultsNames=new LinkedHashSet<String>(fixedProfiles);
        for(var t:targets)if(t.profile()!=null&&!t.profile().readOnly()){defaultsVendors.add(t.profile().vendor());defaultsNames.add(t.profile().name());}
        var defaultsWriters=new RoleResolver.Writers(defaultsVendors,defaultsNames,true,"same_vendor_peer".equals(task.reviewAssurance()));
        var defaults=new ArrayList<TaskRouting.Target>();
        for(var t:targets){
            var selected=t.profile();var spec=user.policy().roles().get(t.role());
            if(selected!=null&&selected.readOnly()&&!t.profilePinned()&&spec!=null
                    && RoleResolver.assuranceOf(spec,selected,user.profiles(),defaultsWriters).startsWith("refused:"))
                for(var alternative:t.candidates())if(!RoleResolver.assuranceOf(spec,alternative,user.profiles(),defaultsWriters).startsWith("refused:")){selected=alternative;break;}
            defaults.add(new TaskRouting.Target(t.stage(),t.role(),selected,t.candidates(),t.rejected(),t.profilePinned()));
        }
        return new Prepared(List.copyOf(defaults),checks,null);
    }
    static boolean permitted(RunOverride advice,List<TaskRouting.Target> targets,RunOverride explicit,UserConfig user,
                             TaskSpec.ResolvedTask task){
        Map<?,?> choices=advice.toMap().get("profile") instanceof Map<?,?> selected?selected:Map.of();
        var byStage=new HashMap<String,TaskRouting.Target>();for(var t:targets)byStage.put(t.stage(),t);
        for(var entry:choices.entrySet()){
            var target=byStage.get(String.valueOf(entry.getKey()));
            if(target==null || target.candidates().stream().noneMatch(p->p.name().equals(entry.getValue())))return false;
        }
        var merged=advice.merged(explicit);var vendors=new LinkedHashSet<String>(task.priorWriters());var names=new LinkedHashSet<String>();
        for(var t:targets){var p=chosen(t,merged,user);if(p!=null&&!p.readOnly()){vendors.add(p.vendor());names.add(p.name());}}
        var writers=new RoleResolver.Writers(vendors,names,true,"same_vendor_peer".equals(task.reviewAssurance()));
        for(var t:targets){var p=chosen(t,merged,user);if(p==null)return false;
            if(merged.problem(t.stage(),p,user.profiles(),user)!=null)return false;
            var role=user.policy().roles().get(t.role());
            if(p.readOnly()&&role!=null&&RoleResolver.assuranceOf(role,p,user.profiles(),writers).startsWith("refused:"))return false;
        }
        return true;
    }
    private static Profile chosen(TaskRouting.Target target,RunOverride overlay,UserConfig user){
        String pin=overlay.pinnedProfile(target.stage());
        return overlay.adapt(target.stage(),pin==null?target.profile():user.profiles().get(pin),user.profiles());
    }
    private static boolean fixed(Workflow.Stage stage,RoleRunner roles,RunOverride overlay){
        return roles.pinnedForRouting(stage.name(),stage.role()) || overlay.toMap().get("host") instanceof Map<?,?> hosts&&hosts.containsKey(stage.name());
    }
}
