package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.json.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** A catalog entry never proves that a new Jev snapshot speaks the current Decisions protocol. */
public final class DecisionCompatibility {
    private static final String PROTOCOL="decisions_choice_v1";
    private DecisionCompatibility() {}
    public static Path file(Path home,String name){return DecisionProfile.file(home,name).resolveSibling(name+".compatibility.json");}
    public static boolean admitted(DecisionProfile profile,UserConfig user){
        if(profile.legacyCompatible())return true;
        try{
            var connection=user.connections().get(profile.connection());if(connection==null)return false;
            Path path=file(user.home(),profile.name());
            if(!Files.isRegularFile(path)||Files.size(path)>16384)return false;
            var receipt=Json.parseObject(Files.readString(path));
            return Boolean.TRUE.equals(receipt.get("ok"))&&PROTOCOL.equals(receipt.get("protocol"))
                    && profile.reportedModel().equals(receipt.get("confirmed_reported_model"))
                    && profile.fingerprint(connection).equals(receipt.get("configuration"))
                    && receipt.get("verified_at") instanceof String at && Instant.parse(at)!=null;
        }catch(Exception unavailable){return false;}
    }
    public static Map<String,Object> status(DecisionProfile profile,UserConfig user){
        var report=new LinkedHashMap<String,Object>();report.put("reported_model",profile.reportedModel());
        report.put("state",profile.legacyCompatible()?"legacy_supported":admitted(profile,user)?"verified":"required");
        if("verified".equals(report.get("state")))try{report.put("verified_at",Json.parseObject(Files.readString(file(user.home(),profile.name()))).get("verified_at"));}catch(Exception ignored){}
        return report;
    }
    /** The caller confirms the exact saved identity, then one typed nonce challenge checks compatibility. */
    public static Map<String,Object> verify(DecisionClient client,DecisionProfile profile,UserConfig user,
                                            String confirmedReportedModel,boolean confirm)throws Exception{
        if(!confirm || !profile.reportedModel().equals(confirmedReportedModel))return Map.of("ok",false,
                "code","classifier_confirmation_required","expected_reported_model",profile.reportedModel(),"vendor_turn_started",false);
        var result=new LinkedHashMap<String,Object>(client.probeCompatibility(profile,user));
        result.put("protocol",PROTOCOL);result.put("confirmed_reported_model",confirmedReportedModel);
        if(!Boolean.TRUE.equals(result.get("ok"))){
            if("classifier_model_mismatch".equals(result.get("code"))&&result.get("model_reported") instanceof String observed)
                result.put("message","Expected "+profile.reportedModel()+"; provider reported "+observed+". Review and explicitly save a new pin before confirming it.");
            return result;
        }
        var current=DecisionProfile.load(user.home()).get(profile.name());
        var connection=new ConnectionStore(user.home()).load().connections().get(profile.connection());
        if(current==null||connection==null||!profile.fingerprint(user.connections().get(profile.connection())).equals(current.fingerprint(connection))){
            result.put("ok",false);result.put("code","classifier_configuration_changed");return result;
        }
        result.put("verified_at",Instant.now().toString());result.put("code","classifier_compatibility_verified");
        JsonFile.writeAtomically(file(user.home(),profile.name()),result);
        return result;
    }
}
