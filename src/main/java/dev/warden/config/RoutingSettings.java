package dev.warden.config;

import dev.warden.yaml.Yaml;
import java.nio.file.*;
import java.util.*;

/** Optional task classification; ordinary configured routing remains the fallback. */
public record RoutingSettings(boolean enabled,String classifier,double minConfidence) {
    public static final RoutingSettings DEFAULT=new RoutingSettings(false,"jev-classifier",0.6);
    public RoutingSettings { Connection.requireId(classifier);if(!Double.isFinite(minConfidence)||minConfidence<0||minConfidence>1)throw new IllegalArgumentException("invalid confidence threshold"); }
    public static RoutingSettings load(Path home)throws Exception{
        Path file=CheckSettings.file(home);if(!Files.exists(file))return DEFAULT;
        return parse(Values.of(Yaml.parse(Files.readString(file)),"settings.yaml").optMap("routing"));
    }
    public static RoutingSettings parse(Values v){
        v.rejectUnknownKeys(Set.of("enabled","classifier","min_confidence"));
        var settings=new RoutingSettings(v.optBool("enabled",false),v.optString("classifier","jev-classifier"),v.optDouble("min_confidence",0.6,0,1));
        v.throwIfAny();return settings;
    }
    public Map<String,Object> toMap(){return Map.of("enabled",enabled,"classifier",classifier,"min_confidence",minConfidence);}
    public String yamlValue(){return "\n  enabled: "+enabled+"\n  classifier: "+classifier+"\n  min_confidence: "+minConfidence;}
}
