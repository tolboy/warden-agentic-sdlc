package dev.warden.config;

import dev.warden.execution.ModelCheck;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** The panel and commands share the same personal files, not a second settings database. */
public final class ModelSettingsService {
    public record Outcome(int status, Map<String, Object> report) {}
    private final Path home;
    private final Path project;
    private final ModelCheck checks;
    private final dev.warden.execution.VendorSettingsService vendors;
    private final AtomicBoolean checking = new AtomicBoolean();

    public ModelSettingsService(Path home, Path project, Function<String, String> environment) {
        this(home, project, new ModelCheck(new ProcessRunner(), environment, null),environment);
    }
    public ModelSettingsService(Path home, Path project, ModelCheck checks) {
        this(home,project,checks,System::getenv);
    }
    private ModelSettingsService(Path home,Path project,ModelCheck checks,Function<String,String> environment){
        this.home = home.toAbsolutePath().normalize(); this.project = project; this.checks = checks;
        this.vendors=new dev.warden.execution.VendorSettingsService(this.home,project,environment,checks,Path.of(System.getProperty("user.home")));
    }

    public Map<String, Object> list() throws Exception {
        UserConfig user = UserConfig.load(home);
        var capabilities = new dev.warden.execution.EffortCapabilities(home, user);
        var catalogModels = new LinkedHashMap<String, List<String>>();
        var vendorList = vendors.list();
        if (vendorList.get("vendors") instanceof List<?> cards) for (Object card : cards)
            if (card instanceof Map<?, ?> row && row.get("id") instanceof String id && row.get("models") instanceof List<?> models)
                catalogModels.put(id, models.stream().map(m -> m instanceof Map<?, ?> model ? String.valueOf(model.get("id")) : null).filter(java.util.Objects::nonNull).toList());
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Profile profile : user.profiles().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", profile.name()); row.put("role", profile.role());
            row.put("model", profile.model()); row.put("vendor", profile.modelVendor());
            row.put("vendor_id", dev.warden.execution.VendorSettingsService.displayVendorId(profile));
            // One capability list for the UI, save validation and the router (EffortCapabilities).
            row.put("efforts", capabilities.levels(profile));
            row.put("effort_capabilities",capabilities.describe(profile));
            var byModel = new LinkedHashMap<String, List<String>>();
            for (String model : catalogModels.getOrDefault(dev.warden.execution.VendorSettingsService.displayVendorId(profile), List.of())) {
                var levels = capabilities.levels(profile, model); if (!levels.isEmpty()) byModel.put(model, levels);
            }
            row.put("efforts_by_model", byModel);
            row.put("effort", profile.effort()); row.put("connection", profile.connectionRef());
            row.put("runner", profile.harness() == null ? profile.runner() : profile.harness());
            row.put("verified", profile.verified()); row.put("revision", revision(profileFile(profile.name())));
            row.put("configuration", ModelCheck.configuration(profile, user));
            profiles.add(row);
        }
        for(var classifier:DecisionProfile.load(home).values()){
            var row=new LinkedHashMap<String,Object>();row.put("name",classifier.name());row.put("role","classifier");row.put("kind","classifier");
            row.put("model",classifier.model());row.put("reported_model",classifier.reportedModel());row.put("vendor","typesafe");row.put("connection",classifier.connection());
            row.put("runner","decisions");row.put("verified",false);row.put("revision",revision(DecisionProfile.file(home,classifier.name())));
            var connection=user.connections().get(classifier.connection());
            row.put("configuration",connection==null?"unbound":classifier.fingerprint(connection));profiles.add(row);
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        if (user.policy() != null) for (var role : user.policy().roles().values())
            routes.add(Map.of("role", role.role(), "profiles", role.profiles(), "strategy", role.strategy()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true); result.put("config_home", home.toString());
        result.put("profiles", profiles); result.put("routes", routes);
        result.put("policy_revision", revision(home.resolve("policy.yaml")));
        result.put("settings_revision", revision(CheckSettings.file(home)));
        result.put("check", CheckSettings.load(home).toMap()); result.put("checking", checking.get());
        result.put("routing",RoutingSettings.load(home).toMap());
        result.putAll(vendorList);
        Path rubricFile=JevRoutingPolicy.file(home);
        var rubricView=new LinkedHashMap<String,Object>();rubricView.put("file",rubricFile.toString());rubricView.put("revision",revision(rubricFile));
        try{rubricView.put("yaml",JevRoutingPolicy.load(home).yaml());rubricView.put("valid",true);}
        catch(Exception invalid){rubricView.put("yaml",Files.isRegularFile(rubricFile)&&Files.size(rubricFile)<=24000?Files.readString(rubricFile):JevRoutingPolicy.DEFAULT.yaml());rubricView.put("valid",false);}
        result.put("jev_policy",rubricView);
        Path latest = home.resolve("checks/latest.json");
        if (Files.isRegularFile(latest) && Files.size(latest) < 1024 * 1024)
            result.put("latest_check", Json.parseObject(Files.readString(latest)));
        Path states = home.resolve("checks/states.json");
        if (Files.isRegularFile(states) && Files.size(states) < 1024 * 1024)
            result.put("check_states", Json.parseObject(Files.readString(states)));
        return result;
    }

    public Outcome apply(String action, Map<String, Object> payload) {
        try {
            if(Set.of("refresh-vendors","check-vendors").contains(action)){
                requireKeys(payload,Set.of("vendors"));var ids=new LinkedHashSet<>(strings(payload.get("vendors")));
                if(ids.isEmpty() || ids.size()>64)throw new Invalid();
                if(!checking.compareAndSet(false,true))return refused(409,"check_already_running");
                try{return new Outcome(200,"refresh-vendors".equals(action)?vendors.refresh(ids):vendors.check(ids));}
                finally{checking.set(false);}
            }
            if("save-composition".equals(action)){
                var saved=new RoleRosterEditor(home).apply(payload);
                if(saved.status()!=200)return new Outcome(saved.status(),saved.report());
                var report=new LinkedHashMap<String,Object>(saved.report());
                report.put("validation",List.of());report.put("ready",strings(saved.report().get("pending_verification")).isEmpty());
                report.put("inference_started",false);
                return new Outcome(200,report);
            }
            if ("check".equals(action)) {
                requireKeys(payload, Set.of("profiles"));
                Set<String> names = new LinkedHashSet<>(strings(payload.get("profiles")));
                if (names.isEmpty() || names.size() > 64) throw new Invalid();
                if (!checking.compareAndSet(false, true)) return refused(409, "check_already_running");
                try { return new Outcome(200, checks.run(UserConfig.load(home), project, names, ignored -> {})); }
                finally { checking.set(false); }
            }
            if ("verify".equals(action)) {
                requireKeys(payload, Set.of("name", "revision"));
                if (!checking.compareAndSet(false, true)) return refused(409, "check_already_running");
                try { return verify(payload); }
                finally { checking.set(false); }
            }
            Files.createDirectories(home);
            try (FileChannel channel = FileChannel.open(home.resolve(".model-settings.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) return refused(409, "settings_busy");
                switch (action) {
                    case "save-jev-policy" -> {
                        requireKeys(payload,Set.of("revision","yaml"));Path file=JevRoutingPolicy.file(home);expectRevision(file,payload);
                        if(!(payload.get("yaml") instanceof String yaml) || yaml.length()>24576)throw new Invalid();JevRoutingPolicy.parse(yaml);ModelCheck.atomicWrite(file,yaml);
                    }
                    case "save-check" -> {
                        requireKeys(payload, Set.of("revision", "before_run", "timeout_seconds", "max_output_tokens"));
                        Path file = CheckSettings.file(home); expectRevision(file, payload);
                        Map<String, Object> values = new LinkedHashMap<>(payload); values.remove("revision");
                        CheckSettings settings = CheckSettings.parse(Map.of("version", 1L, "check", values));
                        String yaml=Files.exists(file)?Files.readString(file):"version: 1\n";
                        String block=settings.yaml().substring(settings.yaml().indexOf("check:")+"check:".length()).stripTrailing();
                        yaml=replaceValue(yaml,List.of("check"),block);
                        CheckSettings.parse(dev.warden.yaml.Yaml.parse(yaml)); ModelCheck.atomicWrite(file,yaml);
                    }
                    case "save-routing" -> {
                        requireKeys(payload,Set.of("revision","enabled","model_selection_enabled","classifier","min_confidence"));
                        Path file=CheckSettings.file(home);expectRevision(file,payload);
                        // A change to either independent control must preserve omitted fields.
                        var values=new LinkedHashMap<String,Object>(RoutingSettings.load(home).toMap());
                        values.putAll(payload);values.remove("revision");
                        var routing=RoutingSettings.parse(Values.of(values,"routing"));
                        if((routing.enabled() || routing.modelSelectionEnabled()) && !DecisionProfile.load(home).containsKey(routing.classifier()))throw new Invalid();
                        String yaml=Files.exists(file)?Files.readString(file):CheckSettings.DEFAULT.yaml();
                        yaml=replaceValue(yaml,List.of("routing"),routing.yamlValue());
                        ModelCheck.atomicWrite(file,yaml);
                    }
                    case "save-profile" -> saveProfile(payload);
                    case "save-classifier" -> saveClassifier(payload);
                    case "save-route" -> saveRoute(payload);
                    default -> throw new Invalid();
                }
            }
            return new Outcome(200, Map.of("ok", true, "code", "settings_saved","settings_revision",revision(CheckSettings.file(home))));
        } catch (Changed changed) { return refused(409, "settings_changed_reload"); }
        catch (UnboundModel unbound) { return refused(400, "cli_model_binding_required"); }
        catch (dev.warden.execution.EffortCapabilities.Unsupported unsupported) { return refused(400, "effort_unsupported"); }
        catch (Invalid | ConfigException | IllegalArgumentException | dev.warden.yaml.Yaml.YamlException invalid) { return refused(400, "settings_invalid"); }
        catch (java.nio.channels.OverlappingFileLockException busy) { return refused(409, "settings_busy"); }
        catch (Exception unavailable) { return refused(503, "settings_unavailable"); }
    }

    private void saveClassifier(Map<String,Object> payload)throws Exception{
        requireKeys(payload,Set.of("name","revision","connection","model","reported_model"));
        String name=text(payload,"name"); Path file=DecisionProfile.file(home,name); expectRevision(file,payload);
        var user=UserConfig.load(home);if(user.profiles().containsKey(name))throw new Invalid();
        String connection=text(payload,"connection");var bound=user.connections().get(connection);
        if(bound==null || !"openrouter".equals(bound.provider()))throw new Invalid();
        var current=DecisionProfile.load(home).get(name);
        var profile=new DecisionProfile(name,connection,text(payload,"model"),text(payload,"reported_model"),
                current==null?15:current.timeoutSeconds(),current==null?8000:current.maxInputCharacters(),current==null||current.enabled());
        ModelCheck.atomicWrite(file,profile.yaml());
    }

    private Outcome verify(Map<String, Object> payload) throws Exception {
        String name = text(payload, "name"); Path file = profileFile(name); expectRevision(file, payload);
        if(DecisionProfile.load(home).containsKey(name))throw new Invalid();
        UserConfig user = UserConfig.load(home); Profile profile = user.profiles().get(name);
        if (profile == null) throw new Invalid();
        ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner(), (args, timeout) -> {
            var result = new dev.warden.execution.orca.OrcaProbe(new ProcessRunner()).run(
                    dev.warden.execution.orca.OrcaProbe.parse(args.toArray(String[]::new)));
            return new ProcessRunner.Result(args, result.exitCode(), false, 0L,
                    String.join("\n", result.lines()), "", false, false);
        });
        var result = verifier.run(profile, home, java.time.Duration.ofMinutes(10), project);
        if (!result.ok()) return new Outcome(200, Map.of("ok", false, "code", "profile_probe_failed",
                "profile", name, "transcript", result.transcript().toString()));
        try (FileChannel channel = FileChannel.open(home.resolve(".model-settings.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) return refused(409, "settings_busy");
            expectRevision(file, payload);
            Profile current = UserConfig.load(home).profiles().get(name);
            if (current == null) throw new Changed();
            if (profile.apiConnection() != null) {
                Object checked = Json.parseObject(Files.readString(result.transcript())).get("profile_fingerprint");
                if (!java.util.Objects.equals(checked, current.apiFingerprint())) throw new Changed();
            }
            if (current.verified()) return new Outcome(200, Map.of("ok", true, "code", "profile_verified", "profile", name));
            var stamp = verifier.stamp(file, current, java.time.LocalDate.now());
            return new Outcome(200, Map.of("ok", stamp.written(), "code", stamp.code(), "profile", name));
        }
    }

    private void saveProfile(Map<String, Object> payload) throws Exception {
        requireKeys(payload, Set.of("name", "revision", "model", "effort", "connection", "vendor", "kind", "harness_executable"));
        String name = text(payload, "name"); Path file = profileFile(name); expectRevision(file, payload);
        if(DecisionProfile.load(home).containsKey(name))throw new Invalid();
        String model = text(payload, "model");
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}")) throw new Invalid();
        UserConfig user = UserConfig.load(home);
        String yaml;
        if (Files.isRegularFile(file)) {
            Profile old = user.profiles().get(name);
            if (old == null) throw new Invalid();
            yaml = Files.readString(file);
            yaml = replaceValue(yaml, List.of("model"), Json.write(model));
            if (old.connectionRef() == null && !java.util.Objects.equals(old.model(), model)) {
                if (old.args().stream().noneMatch(argument -> argument.contains("{{model}}"))) throw new UnboundModel();
                // A model change cannot be stamped by a probe still pinned to the old model.
                if (old.verificationProbe() == null) throw new UnboundModel();
                if (!old.verificationProbe().contains("{{model}}")) {
                    if (old.model() == null || !old.verificationProbe().contains(old.model())) throw new UnboundModel();
                    yaml = replaceValue(yaml, List.of("verification", "probe"),
                            Json.write(old.verificationProbe().replace(old.model(), "{{model}}")));
                }
            }
            if (payload.get("effort") instanceof String effort && !effort.isBlank()) {
                if (!effort.matches("[A-Za-z0-9_-]{1,64}")) throw new Invalid();
                if (old.connectionRef() == null && !java.util.Objects.equals(effort, old.effort())) {
                    if (old.args().stream().noneMatch(argument -> argument.contains("{{effort}}"))
                            || old.verificationProbe() == null) throw new UnboundModel();
                    String probe = old.verificationProbe();
                    if (!probe.contains("{{effort}}")) {
                        if (old.effort() == null || !probe.contains(old.effort())) throw new UnboundModel();
                        // Keep a model binding installed above when both fields changed.
                        if (old.model() != null && !java.util.Objects.equals(old.model(), model))
                            probe = probe.replace(old.model(), "{{model}}");
                        probe = probe.replace(old.effort(), "{{effort}}");
                        yaml = replaceValue(yaml, List.of("verification", "probe"), Json.write(probe));
                    }
                }
                yaml = replaceValue(yaml, List.of("effort"), Json.write(text(payload, "effort")));
            }
            String finalEffort = payload.get("effort") instanceof String effort && !effort.isBlank() ? effort : old.effort();
            boolean effortChanged = !java.util.Objects.equals(finalEffort, old.effort());
            if (old.connectionRef() != null) {
                String connection = text(payload, "connection");
                if (!user.connections().containsKey(connection)) throw new Invalid();
                yaml = replaceValue(yaml, List.of("connection"), Json.write(connection));
                yaml = replaceValue(yaml, List.of("model_vendor"), Json.write(text(payload, "vendor")));
            }
            if (effortChanged || !java.util.Objects.equals(old.model(), model) || old.connectionRef()!=null) {
                Profile capabilityProfile=old.connectionRef()==null?old:Profile.parse(yaml,"settings")
                        .withConnection(user.connections().get(text(payload,"connection")),home);
                new dev.warden.execution.EffortCapabilities(home,user).require(capabilityProfile,model,finalEffort,effortChanged);
            }
            // Every edit invalidates the old probe. A ping cannot recreate this stamp.
            if (yaml.lines().anyMatch(line -> line.startsWith("verification:"))) {
                yaml = replaceValue(yaml, List.of("verification", "verified_on"), null);
                yaml = replaceValue(yaml, List.of("verification", "connection_fingerprint"), null);
            } else yaml += "\nverification:\n  status: unverified\n";
        } else {
            String kind = text(payload, "kind");
            boolean coding = "implementer".equals(kind);
            if (!coding && !"reviewer".equals(kind)) throw new Invalid();
            String connection = text(payload, "connection");
            if (!user.connections().containsKey(connection)) throw new Invalid();
            if (!Files.isRegularFile(home.resolve("prompts/" + kind + ".md"))
                    || !Files.isRegularFile(home.resolve("schemas/" + kind + ".json"))) throw new Invalid();
            yaml = "version: 1\nprofile: " + name + "\nrole: " + kind + "\nrunner: " + (coding ? "direct" : "api") + "\n"
                    + (coding ? "harness: opencode\nharness_executable: " + Json.write(text(payload, "harness_executable")) + "\n" : "")
                    + "connection: " + Json.write(connection) + "\nmodel_vendor: " + Json.write(text(payload, "vendor"))
                    + "\nmodel: " + Json.write(model) + "\nread_only: " + !coding
                    + "\nprompt_template: prompts/" + kind + ".md\njson_schema: schemas/" + kind + ".json\nenforce_schema: true\n"
                    + "limits:\n  wall_clock_minutes: 4\n  max_output_tokens: 2048\nverification:\n  status: unverified\n";
        }
        if (payload.get("effort") instanceof String selected && !selected.isBlank())
            yaml=replaceValue(yaml,List.of("effort"),Json.write(selected));
        Profile next = Profile.parse(yaml, file.toString());
        if (next.connectionRef() != null) {
            next=next.withConnection(user.connections().get(next.connectionRef()), home);
            new dev.warden.execution.EffortCapabilities(home,user).require(next,next.model(),next.effort(),true);
        }
        if (next.verified()) throw new Invalid();
        ModelCheck.atomicWrite(file, yaml);
    }

    private void saveRoute(Map<String, Object> payload) throws Exception {
        requireKeys(payload, Set.of("revision", "role", "profiles", "strategy"));
        Path file = home.resolve("policy.yaml"); expectRevision(file, payload);
        UserConfig user = UserConfig.load(home);
        String role = text(payload, "role");
        if (user.policy() == null || !user.policy().roles().containsKey(role)) throw new Invalid();
        List<String> names = strings(payload.get("profiles"));
        if (names.isEmpty() || new LinkedHashSet<>(names).size() != names.size()) throw new Invalid();
        for (String name : names) {
            Profile profile = user.profiles().get(name);
            if (profile == null || !role.equals(profile.role()) || !profile.verified()) throw new Invalid();
        }
        String yaml = Files.readString(file);
        yaml = replaceValue(yaml, List.of("roles", role, "profiles"), Json.write(names));
        yaml = replaceValue(yaml, List.of("roles", role, "strategy"), Json.write(text(payload, "strategy")));
        Policy.parse(yaml, file.toString());
        ModelCheck.atomicWrite(file, yaml);
    }

    /** Edit one mapping entry, retaining unrelated policy, comments, paths and flags. */
    static String replaceValue(String yaml, List<String> path, String value) {
        List<String> lines = new ArrayList<>(List.of(yaml.split("\\R", -1)));
        int start = 0, end = lines.size(), parentIndent = -2;
        for (int part = 0; part < path.size(); part++) {
            String key = path.get(part); int found = -1, indent = parentIndent + 2;
            for (int i = start; i < end; i++) {
                String line = lines.get(i); String stripped = line.stripLeading();
                int width = line.length() - stripped.length();
                if (stripped.startsWith(key + ":") && width > parentIndent && (part != 0 || width == 0)) {
                    found = i; indent = width; break;
                }
            }
            if (found < 0) {
                if (part != path.size() - 1) throw new Invalid();
                if (value == null) return yaml;
                lines.add(end, " ".repeat(indent) + key + ": " + value);
                return String.join("\n", lines) + "\n";
            }
            int limit = found + 1;
            while (limit < end) {
                String line = lines.get(limit), stripped = line.stripLeading();
                if (!stripped.isBlank() && !stripped.startsWith("#") && line.length() - stripped.length() <= indent) break;
                limit++;
            }
            if (part == path.size() - 1) {
                for (int i = limit - 1; i >= found; i--) lines.remove(i);
                if (value != null) lines.add(found, " ".repeat(indent) + key + ": " + value);
                return String.join("\n", lines) + "\n";
            }
            start = found + 1; end = limit; parentIndent = indent;
        }
        throw new Invalid();
    }

    private Path profileFile(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new Invalid();
        return home.resolve("profiles").resolve(name + ".yaml");
    }
    private static void requireKeys(Map<String, Object> payload, Set<String> allowed) {
        if (payload == null || !allowed.containsAll(payload.keySet())) throw new Invalid();
    }
    private static String text(Map<String, Object> payload, String key) {
        if (!(payload.get(key) instanceof String value) || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(c -> c < 32)) throw new Invalid();
        return value;
    }
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 64) throw new Invalid();
        List<String> result = new ArrayList<>();
        for (Object item : list) { if (!(item instanceof String name)) throw new Invalid(); result.add(name); }
        return result;
    }
    public static String revision(Path path) throws Exception {
        return Files.exists(path) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) : "absent";
    }
    private static void expectRevision(Path path, Map<String, Object> payload) throws Exception {
        if (!revision(path).equals(payload.get("revision"))) throw new Changed();
    }
    private static Outcome refused(int status, String code) { return new Outcome(status, Map.of("ok", false, "code", code)); }
    private static final class Invalid extends RuntimeException {}
    private static final class Changed extends RuntimeException {}
    private static final class UnboundModel extends RuntimeException {}
}
