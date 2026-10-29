import dev.warden.config.*;
import dev.warden.execution.DecisionClient;
import dev.warden.json.Json;
import dev.warden.run.TaskRouting;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit bounded rubric evaluation and deterministic selection replay; no roles run. */
class JevEffortEvaluation {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !Set.of("--live","--synthetic").contains(args[0]))
            throw new IllegalArgumentException("Usage: java -cp out/classes tools/agent/JevEffortEvaluation.java <--live|--synthetic> <cases.json> <output.json>");
        boolean live="--live".equals(args[0]);
        Path dataset=Path.of(args[1]), output=Path.of(args[2]);
        var specification=Json.parseObject(Files.readString(dataset));
        var levels=((List<?>)specification.get("levels")).stream().map(String::valueOf).toList();
        var cases=(List<?>)specification.get("cases");
        if(cases.size()>12)throw new IllegalArgumentException("At most twelve bounded requests per evaluation");
        var user=live?UserConfig.load():null;
        var settings=live?RoutingSettings.load(user.home()):null;
        var profile=live?DecisionProfile.load(user.home()).get(settings.classifier()):null;
        if(live && profile==null)throw new IllegalStateException("No configured Jev classifier");
        var policy=JevRoutingPolicy.DEFAULT;var client=new DecisionClient();
        var questions=new LinkedHashMap<String,Map<String,Object>>();
        questions.put("kind",DecisionClient.choice(policy.instructions().get("kind"),policy.criteria().get("kind")));
        for(String role:List.of("planner","reviewer","visual_qa"))
            questions.put(role,DecisionClient.choice(policy.instructions().get(role),policy.criteria().get("boolean")));
        questions.put("effort_0",DecisionClient.choice(policy.effortInstructions("implement","implementer"),policy.options("effort",levels)));
        var rows=new ArrayList<Map<String,Object>>();var report=new LinkedHashMap<String,Object>();
        report.put("at",Instant.now().toString());report.put("dataset",specification);report.put("rubric_hash",policy.fingerprint());
        report.put("classifier",live?profile.name():null);report.put("rubric","repository_default");report.put("rows",rows);
        report.put("evaluation_mode",live?"live_classifier":"synthetic_distribution_replay");
        report.put("max_requests",live?cases.size():0);
        report.put("scope","Small rubric smoke set and offline threshold replay; not a validated optimum or task-performance benchmark");
        Files.createDirectories(output.toAbsolutePath().getParent());
        for(Object value:cases){
            var sample=(Map<?,?>)value;var state=Map.of("task",sample.get("goal"),"risk",sample.get("risk"),
                    "stages",List.of(Map.of("stage","implement","role","implementer","model","gpt-6.1-sol","configured_effort","medium","supported_efforts",levels)));
            var result=live?client.decide(profile,user,state,questions):
                    new DecisionClient.Result(true,"synthetic_distribution",Map.of("effort_0",Map.of("probabilities",sample.get("synthetic_probabilities"))),Map.of());
            var row=new LinkedHashMap<String,Object>();row.put("id",sample.get("id"));row.put("group",sample.get("group"));
            row.put("ok",result.ok());row.put("code",result.code());row.put("evidence",result.evidence());row.put("answers",result.answers());
            var thresholds=new LinkedHashMap<String,Object>();
            if(result.ok())for(Object raw:(List<?>)specification.get("thresholds")){
                double threshold=((Number)raw).doubleValue();String chosen=TaskRouting.chooseEffort(result.answers().get("effort_0"),threshold,levels);
                var choice=new LinkedHashMap<String,Object>();choice.put("proposed",chosen);
                chosen=chosen==null?null:TaskRouting.applyRiskFloor(chosen,String.valueOf(sample.get("risk")),levels);
                choice.put("after_risk_floor",chosen);choice.put("keeps_configured",chosen==null);
                var band=(List<?>)sample.get("expected_band");choice.put("in_reviewed_band",band.isEmpty()?null:band.contains(chosen));
                thresholds.put(String.valueOf(threshold),choice);
            }
            row.put("thresholds",thresholds);rows.add(row);
            Files.writeString(output,Json.writePretty(report)+System.lineSeparator());
            System.out.println(sample.get("id")+": "+result.code()+" "+Json.write(thresholds));
        }
    }
}
