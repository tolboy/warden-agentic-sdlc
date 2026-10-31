package dev.warden.config;

import dev.warden.json.Json;
import dev.warden.yaml.Yaml;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Typed decision profiles are not workers and cannot acquire a coding/review role. */
public record DecisionProfile(String name, String connection, String model, String reportedModel,
                              int timeoutSeconds, int maxInputCharacters, boolean enabled) {
    public DecisionProfile {
        Connection.requireId(name); Connection.requireId(connection);
        if (model == null || !model.matches("typesafe/jev-[1-9][0-9]*\\.[0-9]+(?:\\.[0-9]+)?(?:-[0-9]{8})?"))
            throw new IllegalArgumentException("pin an explicit Jev version");
        if (reportedModel == null || !(reportedModel.equals(model) || !model.matches(".*-[0-9]{8}$")
                && reportedModel.matches(java.util.regex.Pattern.quote(model) + "-[0-9]{8}")))
            throw new IllegalArgumentException("pin the reported Jev snapshot");
        if (timeoutSeconds < 1 || timeoutSeconds > 30 || maxInputCharacters < 256 || maxInputCharacters > 12000)
            throw new IllegalArgumentException("invalid classifier bounds");
    }
    /** Compatibility built into the original integration; future snapshots need an explicit probe. */
    public boolean legacyCompatible(){
        return (Set.of("typesafe/jev-1.13","typesafe/jev-1.14").contains(model)&&model.equals(reportedModel))
                || "typesafe/jev-1.14".equals(model)&&"typesafe/jev-1.14-20261014".equals(reportedModel);
    }
    public static Path file(Path home, String name) { Connection.requireId(name); return home.resolve("classifiers").resolve(name + ".yaml"); }
    public static DecisionProfile parse(String text) {
        Values v=Values.of(Yaml.parse(text), "classifier profile").rejectUnknownKeys(Set.of("version","profile","connection","model","reported_model","timeout_seconds","max_input_characters","enabled"));
        v.requireVersion(1);
        var p=new DecisionProfile(v.requireString("profile"),v.requireString("connection"),v.requireString("model"),
                v.requireString("reported_model"),(int)v.optInt("timeout_seconds",15,1,30),
                (int)v.optInt("max_input_characters",8000,256,12000),v.optBool("enabled",true));
        v.throwIfAny(); return p;
    }
    public static Map<String,DecisionProfile> load(Path home) throws Exception {
        Map<String,DecisionProfile> rows=new LinkedHashMap<>(); Path dir=home.resolve("classifiers");
        if(!Files.isDirectory(dir))return rows;
        try(var files=Files.list(dir)) {
            for(Path path:files.filter(p->p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                var profile=parse(Files.readString(path));
                if(!path.getFileName().toString().equals(profile.name()+".yaml"))throw new IllegalArgumentException("classifier file/name mismatch");
                rows.put(profile.name(),profile);
            }
        }
        return rows;
    }
    public String fingerprint(Connection bound) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((yaml()+bound.fingerprint()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public String yaml() {
        return "version: 1\nprofile: "+name+"\nconnection: "+connection+"\nmodel: "+Json.write(model)
                +"\nreported_model: "+Json.write(reportedModel)+"\ntimeout_seconds: "+timeoutSeconds
                +"\nmax_input_characters: "+maxInputCharacters+"\nenabled: "+enabled+"\n";
    }
}
