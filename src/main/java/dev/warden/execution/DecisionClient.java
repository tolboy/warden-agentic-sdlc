package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.json.Json;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** OpenRouter Decisions API, sharing bounded transport and credential resolution with chat. */
public final class DecisionClient {
    public record Result(boolean ok,String code,Map<String,Map<String,Object>> answers,Map<String,Object> evidence) {}
    private final Function<String,String> environment;
    public DecisionClient(){this(System::getenv);}
    public DecisionClient(Function<String,String> environment){this.environment=environment;}

    public Result decide(DecisionProfile profile,UserConfig user,Object state,Map<String,Map<String,Object>> questions) throws Exception {
        return decide(profile,user,state,questions,false);
    }
    private Result decide(DecisionProfile profile,UserConfig user,Object state,Map<String,Map<String,Object>> questions,boolean compatibilityProbe) throws Exception {
        Map<String,Object> evidence=new LinkedHashMap<>(); evidence.put("cost_status","unknown");
        evidence.put("model_requested",profile.model()); evidence.put("vendor_turn_started",false);
        Connection c=user.connections().get(profile.connection());
        if(!profile.enabled() || c==null || !c.enabled())return fail("classifier_connection_unavailable",evidence);
        boolean openrouter="openrouter".equals(c.provider());
        if(!openrouter && !("custom".equals(c.provider()) && Set.of("127.0.0.1","localhost","::1","[::1]").contains(URI.create(c.baseUrl()).getHost())))
            return fail("classifier_endpoint_unsupported",evidence);
        if(!compatibilityProbe&&!DecisionCompatibility.admitted(profile,user))return fail("classifier_compatibility_required",evidence);
        String serialized=Json.write(state);
        if(serialized.length()>profile.maxInputCharacters() || questions.isEmpty() || questions.size()>16)
            return fail("classifier_input_limit",evidence);
        String token;
        try{token=new CredentialResolver(environment).resolve(c);}catch(RuntimeException missing){return fail("classifier_auth_missing",evidence);}
        String fingerprint=profile.fingerprint(c); evidence.put("configuration",fingerprint);
        String endpoint=openrouter?"https://openrouter.ai/api/alpha/decisions":c.baseUrl()+"/decisions";
        var body=Map.of("model",profile.model(),"state",state,"questions",questions,"provider",Map.of("allow_fallbacks",false));
        String bodyJson=Json.write(body);
        if(bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32*1024)return fail("classifier_input_limit",evidence);
        evidence.put("vendor_turn_started",true);
        ApiExecutor.HttpReply response;
        try {response=ApiExecutor.call(c,"POST",URI.create(endpoint),bodyJson,token,Duration.ofSeconds(profile.timeoutSeconds()));}
        catch(ApiExecutor.CallFailure failure){return fail(failure.code,evidence);}
        var parsed=ApiExecutor.object(response.body()); String error=ApiExecutor.errorCode(response.status(),parsed);
        if(error!=null)return fail(error,evidence);
        if(parsed!=null && parsed.get("model") instanceof String reported
                && reported.matches("typesafe/jev-[1-9][0-9]*\\.[0-9]+(?:\\.[0-9]+)?(?:-[0-9]{8})?")
                && reported.length()<128 && (token==null||!reported.contains(token)))evidence.put("model_reported",reported);
        if(parsed==null || !profile.reportedModel().equals(parsed.get("model")))return fail("classifier_model_mismatch",evidence);
        evidence.put("model_reported",profile.reportedModel());
        if(parsed.get("usage") instanceof Map<?,?> usage){
            Map<String,Object> tokens=new LinkedHashMap<>();
            for(String key:List.of("input_tokens","output_tokens")) if(usage.get(key) instanceof Number n && finite(n,0,Double.MAX_VALUE))tokens.put(key,n.longValue());
            evidence.put("tokens",tokens);
            if(usage.get("cost") instanceof Number cost && finite(cost,0,Double.MAX_VALUE)){
                evidence.put("cost_usd",cost.doubleValue());evidence.put("cost_status","reported");
            }
        }
        if(!(parsed.get("answers") instanceof Map<?,?> answers) || !answers.keySet().equals(questions.keySet()))return fail("classifier_response_invalid",evidence);
        Map<String,Map<String,Object>> validated=new LinkedHashMap<>();
        for(var question:questions.entrySet()){
            if(!(question.getValue().get("criteria") instanceof Map<?,?> options)
                    || !(answers.get(question.getKey()) instanceof Map<?,?> answer)
                    || !"choice".equals(answer.get("type")) || !(answer.get("choice") instanceof String choice)
                    || !options.containsKey(choice) || !(answer.get("confidence") instanceof Number confidence) || !finite(confidence,0,1)
                    || !(answer.get("probabilities") instanceof Map<?,?> probabilities) || !probabilities.keySet().equals(options.keySet()))return fail("classifier_response_invalid",evidence);
            double sum=0;Map<String,Object> safeProbabilities=new LinkedHashMap<>();
            for(Object key:options.keySet()){
                if(!(probabilities.get(key) instanceof Number n) || !finite(n,0,1))return fail("classifier_response_invalid",evidence);
                sum+=n.doubleValue();safeProbabilities.put(String.valueOf(key),n.doubleValue());
            }
            if(Math.abs(sum-1)>0.02)return fail("classifier_response_invalid",evidence);
            validated.put(question.getKey(),Map.of("type","choice","choice",choice,"confidence",confidence.doubleValue(),"probabilities",safeProbabilities));
        }
        // Do not bless a result for an endpoint/profile edited during its request.
        if(java.nio.file.Files.isRegularFile(DecisionProfile.file(user.home(),profile.name()))) {
            var current=DecisionProfile.load(user.home()).get(profile.name());
            var connection=new ConnectionStore(user.home()).load().connections().get(profile.connection());
            if(current==null || connection==null || !fingerprint.equals(current.fingerprint(connection)))return fail("classifier_configuration_changed",evidence);
        }
        return new Result(true,"classifier_answered",validated,evidence);
    }
    public Map<String,Object> ping(DecisionProfile profile,UserConfig user) throws Exception {
        return ping(profile,user,false);
    }
    public Map<String,Object> probeCompatibility(DecisionProfile profile,UserConfig user)throws Exception{
        return ping(profile,user,true);
    }
    private Map<String,Object> ping(DecisionProfile profile,UserConfig user,boolean compatibilityProbe) throws Exception {
        String marker="pong_"+UUID.randomUUID().toString().replace("-","");
        var result=decide(profile,user,Map.of("expected_choice",marker),Map.of("availability",choice(
                "Select the option equal to state.expected_choice.",Map.of(marker,"The marker equals state.expected_choice.","not_ready","The marker does not equal state.expected_choice."))),compatibilityProbe);
        boolean ok=result.ok() && marker.equals(result.answers().get("availability").get("choice"));
        Map<String,Object> report=new LinkedHashMap<>(result.evidence());report.put("ok",ok);
        report.put("code",ok?"pong":result.ok()?"classifier_no_pong":result.code());report.put("identity","provider_response_pinned_decision_model");return report;
    }
    public static Map<String,Object> choice(String instructions,Map<String,String> options){return Map.of("type","choice","instructions",instructions,"criteria",options);}
    private static boolean finite(Number n,double low,double high){return Double.isFinite(n.doubleValue()) && n.doubleValue()>=low && n.doubleValue()<=high;}
    private static Result fail(String code,Map<String,Object> evidence){return new Result(false,code,Map.of(),evidence);}
}
