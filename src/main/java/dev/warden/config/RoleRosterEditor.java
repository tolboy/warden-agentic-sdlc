package dev.warden.config;

import dev.warden.execution.ModelCheck;
import dev.warden.json.Json;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Saves an ordered role composition while keeping launch/tool contracts in internal templates. */
public final class RoleRosterEditor {
    public record Outcome(int status, Map<String,Object> report) {}
    private final Path home;
    public RoleRosterEditor(Path home) { this.home=home.toAbsolutePath().normalize(); }

    /** Explicit user action. Never infers or grants admission; callers may verify pending models afterward. */
    public Outcome apply(Map<String,Object> payload) {
        try {
            Files.createDirectories(home);
            try (var channel=FileChannel.open(home.resolve(".model-settings.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                 var lock=channel.tryLock()) {
                if(lock==null)return refused(409,"settings_busy");
                return save(payload);
            }
        } catch(Changed conflict) { return refused(409,"settings_changed_reload"); }
        catch(Unbound unavailable) { return refused(400,"cli_model_binding_required"); }
        catch(dev.warden.execution.EffortCapabilities.Unsupported unsupported) { return refused(400,"effort_unsupported"); }
        catch(Invalid | IllegalArgumentException | ConfigException invalid) { return refused(400,"settings_invalid"); }
        catch(java.nio.channels.OverlappingFileLockException busy) { return refused(409,"settings_busy"); }
        catch(Exception unavailable) { return refused(503,"settings_unavailable"); }
    }

    private Outcome save(Map<String,Object> payload)throws Exception {
        keys(payload,Set.of("revision","role","strategy","slots"));
        Path policyFile=home.resolve("policy.yaml");expect(policyFile,text(payload,"revision"));
        var user=UserConfig.load(home);String role=text(payload,"role"),strategy=text(payload,"strategy");
        var capabilities=new dev.warden.execution.EffortCapabilities(home,user);
        if(user.policy()==null || !user.policy().roles().containsKey(role) || !Set.of("first","rotate").contains(strategy))throw new Invalid();
        if(!(payload.get("slots") instanceof List<?> slots) || slots.isEmpty() || slots.size()>64)throw new Invalid();
        var names=new ArrayList<String>();var pending=new ArrayList<String>();
        var updates=new LinkedHashMap<Path,String>();
        var oldBytes=new LinkedHashMap<Path,byte[]>();
        var expected=new LinkedHashMap<Path,String>();expected.put(policyFile,text(payload,"revision"));
        for(Object raw:slots) {
            if(!(raw instanceof Map<?,?> rawMap))throw new Invalid();
            var slot=new LinkedHashMap<String,Object>();
            for(var entry:rawMap.entrySet()) { if(!(entry.getKey() instanceof String key))throw new Invalid();slot.put(key,entry.getValue()); }
            keys(slot,Set.of("profile","revision","model","effort","model_vendor"));
            String name=text(slot,"profile");
            if(!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new Invalid();
            if(names.contains(name))throw new Invalid();names.add(name);
            Profile old=user.profiles().get(name);
            if(old==null || !role.equals(old.role()))throw new Invalid();
            Path file=home.resolve("profiles").resolve(name+".yaml");
            String revision=text(slot,"revision");expect(file,revision);expected.put(file,revision);
            String model=text(slot,"model");
            if(!model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}"))throw new Invalid();
            String yaml=Files.readString(file),original=yaml;
            boolean modelChanged=!Objects.equals(old.model(),model);
            String probe=old.verificationProbe();
            if(modelChanged) {
                if(old.connectionRef()==null) {
                    boolean launchBound="orca".equals(old.runner()) || old.args().stream().anyMatch(a->a.contains("{{model}}"));
                    if(!launchBound || probe==null)throw new Unbound();
                    if(!probe.contains("{{model}}")) {
                        if(old.model()==null || !probe.contains(old.model()))throw new Unbound();
                        probe=probe.replace(old.model(),"{{model}}");
                    }
                }
                yaml=replace(yaml,List.of("model"),Json.write(model));
            }
            if(slot.containsKey("model_vendor")) {
                String vendor=text(slot,"model_vendor");
                if(!vendor.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))throw new Invalid();
                if(old.connectionRef()==null && !Objects.equals(Profile.canonicalVendor(vendor),Profile.canonicalVendor(old.modelVendor())))throw new Invalid();
                if(old.connectionRef()!=null && !Objects.equals(Profile.canonicalVendor(vendor),Profile.canonicalVendor(old.modelVendor())))
                    yaml=replace(yaml,List.of("model_vendor"),Json.write(vendor));
            }
            if(slot.containsKey("effort")) {
                String effort=text(slot,"effort");
                if(!effort.matches("[a-z][a-z0-9_-]{0,63}"))throw new Invalid();
                if(!Objects.equals(effort,old.effort())) {
                    boolean nativeApi=old.connectionRef()!=null;
                    boolean launchBound=nativeApi || "orca".equals(old.runner()) || old.args().stream().anyMatch(a->a.contains("{{effort}}"));
                    if(!launchBound || (!nativeApi && probe==null))throw new Unbound();
                    if(!nativeApi && !probe.contains("{{effort}}")) {
                        if(old.effort()==null || !probe.contains(old.effort()))throw new Unbound();
                        probe=probe.replace(old.effort(),"{{effort}}");
                    }
                    yaml=replace(yaml,List.of("effort"),Json.write(effort));
                }
            }
            String finalEffort=slot.containsKey("effort")?text(slot,"effort"):old.effort();
            boolean effortChanged=!Objects.equals(finalEffort,old.effort());
            if(effortChanged || modelChanged)capabilities.require(old,model,finalEffort,effortChanged);
            if(!Objects.equals(probe,old.verificationProbe()))yaml=replace(yaml,List.of("verification","probe"),Json.write(probe));
            boolean edited=!yaml.equals(original);
            if(edited) {
                if(yaml.lines().anyMatch(line->line.startsWith("verification:"))) {
                    yaml=replace(yaml,List.of("verification","verified_on"),null);
                    yaml=replace(yaml,List.of("verification","connection_fingerprint"),null);
                } else yaml+="\nverification:\n  status: unverified\n";
                var next=Profile.parse(yaml,file.toString());
                if(next.connectionRef()!=null)next=next.withConnection(user.connections().get(next.connectionRef()),home);
                if(next.verified())throw new Invalid();
                updates.put(file,yaml);
            }
            if(edited || !old.verified())pending.add(name);
        }
        String policy=Files.readString(policyFile);
        policy=replace(policy,List.of("roles",role,"profiles"),Json.write(names));
        policy=replace(policy,List.of("roles",role,"strategy"),Json.write(strategy));
        Policy.parse(policy,policyFile.toString());updates.put(policyFile,policy);
        // Recheck every revision immediately before the first write. No partial validation edits.
        for(var entry:expected.entrySet())expect(entry.getKey(),entry.getValue());
        for(Path file:updates.keySet())oldBytes.put(file,Files.readAllBytes(file));
        var written=new ArrayList<Path>();
        try {
            for(var entry:updates.entrySet()){ModelCheck.atomicWrite(entry.getKey(),entry.getValue());written.add(entry.getKey());}
        } catch(Exception failure) {
            Collections.reverse(written);
            for(Path file:written)try{ModelCheck.atomicWrite(file,new String(oldBytes.get(file),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        var revisions=new LinkedHashMap<String,String>();for(String name:names)revisions.put(name,ModelSettingsService.revision(home.resolve("profiles").resolve(name+".yaml")));
        return new Outcome(200,Map.of("ok",true,"code","role_composition_saved","role",role,"pending_verification",pending,
                "profiles",names,"revisions",revisions,"policy_revision",ModelSettingsService.revision(policyFile)));
    }
    private static String replace(String yaml,List<String> path,String value){return ModelSettingsService.replaceValue(yaml,path,value);}
    private static void keys(Map<String,Object> map,Set<String> allowed){if(map==null || !allowed.containsAll(map.keySet()))throw new Invalid();}
    private static String text(Map<String,Object> map,String key){if(!(map.get(key) instanceof String value) || value.isBlank() || value.length()>512 || value.chars().anyMatch(c->c<32))throw new Invalid();return value;}
    private static void expect(Path file,String revision)throws Exception{if(!ModelSettingsService.revision(file).equals(revision))throw new Changed();}
    private static Outcome refused(int status,String code){return new Outcome(status,Map.of("ok",false,"code",code));}
    private static final class Invalid extends RuntimeException {}
    private static final class Changed extends RuntimeException {}
    private static final class Unbound extends RuntimeException {}
}
