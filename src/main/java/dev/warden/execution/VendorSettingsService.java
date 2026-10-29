package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Vendor inventory and explicit diagnostics. Reading settings never authenticates or infers. */
public final class VendorSettingsService {
    private final Path home, project, cliHome;
    private final Function<String,String> environment;
    private final ModelCheck checks;
    public VendorSettingsService(Path home,Path project,Function<String,String> environment){
        this(home,project,environment,new ModelCheck(new ProcessRunner(),environment,null),Path.of(System.getProperty("user.home")));
    }
    public VendorSettingsService(Path home,Path project,Function<String,String> environment,ModelCheck checks,Path cliHome){
        this.home=home.toAbsolutePath().normalize();this.project=project;this.environment=environment;this.checks=checks;this.cliHome=cliHome;
    }
    private record Vendor(String id,String kind,String provider,String label,Connection connection,List<Profile> profiles,List<DecisionProfile> classifiers) {}
    public Map<String,Object> list()throws Exception{
        var user=UserConfig.load(home);var rows=new ArrayList<Map<String,Object>>();var evidence=CheckEvidence.current(home,user);
        for(var vendor:inventory(user).values()){
            var row=new LinkedHashMap<String,Object>();row.put("id",vendor.id());row.put("kind",vendor.kind());row.put("provider",vendor.provider());row.put("label",vendor.label());
            row.put("profiles",vendor.profiles().stream().map(Profile::name).toList());row.put("classifier_profiles",vendor.classifiers().stream().map(DecisionProfile::name).toList());
            row.put("access_label",vendor.connection()!=null?"API":UNMAPPED.equals(vendor.id())?"Сервис не указан":"Через CLI");row.put("billing","unknown");
            if(vendor.connection()==null)row.put("accounts",accounts(vendor));
            if(UNMAPPED.equals(vendor.id()))row.put("mapping",vendor.profiles().stream().map(p->{var binding=ServiceBinding.of(p);var item=new LinkedHashMap<String,Object>();item.put("profile",p.name());item.put("reason",binding.reason());item.put("suggested",binding.suggested());return item;}).toList());
            row.put("enabled",vendor.connection()==null || vendor.connection().enabled());row.put("docs_url",docs(vendor.provider()));
            var catalog=read(cache(vendor.id(),"catalog"));
            if(catalog.isEmpty() || !Objects.equals(catalog.get("configuration"),configuration(vendor)))catalog=unavailable("catalog_not_loaded");
            row.put("catalog",catalog);var models=new LinkedHashMap<String,Map<String,Object>>();
            if(catalog.get("models") instanceof List<?> cached)for(Object value:cached)if(value instanceof Map<?,?> model && model.get("id") instanceof String id)models.put(id,new LinkedHashMap<>((Map<String,Object>)model));
            for(var profile:vendor.profiles())if(profile.model()!=null)models.putIfAbsent(profile.model(),Map.of("id",profile.model(),"label",profile.model(),"efforts",profile.effort()==null?List.of():List.of(profile.effort()),"origin","configured"));
            for(var profile:vendor.classifiers())models.putIfAbsent(profile.model(),Map.of("id",profile.model(),"label",profile.model(),"efforts",List.of(),"origin","configured","kind","classifier"));
            var last=read(cache(vendor.id(),"check"));if(Objects.equals(last.get("configuration"),configuration(vendor)))row.put("check",last);
            var status=CheckEvidence.status(vendor.profiles(),vendor.classifiers(),evidence);
            for(var model:models.entrySet()){var answer=CheckEvidence.forModel(status,model.getKey());if(answer!=null){var copy=new LinkedHashMap<>(model.getValue());copy.put("check",answer);model.setValue(copy);}}
            row.put("models",new ArrayList<>(models.values()));row.put("status",status);
            rows.add(row);
        }
        return Map.of("vendors",rows);
    }
    public Map<String,Object> refresh(Set<String> ids)throws Exception{
        var user=UserConfig.load(home);var vendors=selected(user,ids);var rows=new ArrayList<Map<String,Object>>();
        for(var vendor:vendors){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("catalog refresh cancelled");
            if(UNMAPPED.equals(vendor.id())){rows.add(Map.of("vendor",vendor.id(),"status","unavailable","code","service_mapping_required","models",List.of()));continue;}
            Map<String,Object> catalog;
            try{catalog=vendor.connection()==null?cliCatalog(vendor):apiCatalog(vendor.connection());}
            catch(Exception failure){if(Thread.currentThread().isInterrupted())throw new InterruptedException("catalog refresh cancelled");catalog=unavailable("catalog_unavailable");}
            var result=new LinkedHashMap<>(catalog);result.put("vendor",vendor.id());result.put("configuration",configuration(vendor));result.put("refreshed_at",Instant.now().toString());
            // A failed refresh can retain the previous catalog only as explicitly stale data.
            if(!"available".equals(result.get("status"))){var old=read(cache(vendor.id(),"catalog"));if(Objects.equals(old.get("configuration"),configuration(vendor)) && old.get("models") instanceof List<?> prior && !prior.isEmpty()){result.put("models",prior);result.put("stale",true);if(old.get("fetched_at")!=null)result.put("fetched_at",old.get("fetched_at"));}}
            ModelCheck.atomicWrite(cache(vendor.id(),"catalog"),Json.write(result));rows.add(result);
        }
        return Map.of("ok",true,"code","vendor_catalogs_refreshed","results",rows);
    }
    public Map<String,Object> check(Set<String> ids)throws Exception{
        var user=UserConfig.load(home);var vendors=selected(user,ids);var rows=new ArrayList<Map<String,Object>>();boolean all=true;
        for(var vendor:vendors){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("vendor check cancelled");
            Set<String> names=new LinkedHashSet<>();
            Map<String,Object> report;
            if(UNMAPPED.equals(vendor.id())){
                // Pinging a profile whose service is unknown cannot prove anything about a service.
                rows.add(Map.of("vendor",vendor.id(),"ok",false,"code","service_mapping_required","results",List.of()));all=false;continue;
            }else if(vendor.connection()==null){
                // Each account authenticates separately, so each gets its own representative.
                // A retired first model need not make an authenticated account look
                // disconnected: try at most one other configured model, stopping at pong.
                var attempts=new ArrayList<Object>();var accountRows=new ArrayList<Object>();boolean available=true;
                var byAccount=new TreeMap<String,List<Profile>>();
                for(var profile:vendor.profiles())byAccount.computeIfAbsent(ServiceBinding.of(profile).account(),ignored->new ArrayList<>()).add(profile);
                for(var account:byAccount.entrySet()){
                    boolean answered=false;int attempted=0;var seenModels=new HashSet<String>();
                    var direct=account.getValue().stream().filter(p->!"orca".equals(p.runner())).toList();
                    var candidates=direct.isEmpty()?account.getValue():direct;
                    for(var profile:candidates.stream().sorted(Comparator.comparing((Profile p)->!p.readOnly()).thenComparing(p->!p.verified()).thenComparing(Profile::name)).toList()){
                        if(!seenModels.add(String.valueOf(profile.model())))continue;
                        var attempt=checks.run(user,project,Set.of(profile.name()),ignored->{});attempts.addAll((List<?>)attempt.get("results"));attempted++;
                        if(Boolean.TRUE.equals(attempt.get("ok"))){answered=true;break;}if(attempted>=2)break;
                    }
                    accountRows.add(Map.of("account",account.getKey(),"ok",answered));available&=answered;
                }
                report=Map.of("ok",available,"code",available?"check_passed":"model_check_failed","results",attempts,"accounts",accountRows);
            }else{
                // One diagnostic per configured API model, not per role/harness duplicate.
                var byModel=new LinkedHashMap<String,Profile>();
                vendor.profiles().stream().sorted(Comparator.comparing(p->!"api".equals(p.runner()))).forEach(p->byModel.putIfAbsent(p.model(),p));
                byModel.values().forEach(p->names.add(p.name()));vendor.classifiers().forEach(p->names.add(p.name()));
                report=names.isEmpty()?Map.of("ok",false,"code","vendor_model_required","results",List.of()):checks.run(user,project,names,ignored->{});
            }
            var row=new LinkedHashMap<String,Object>();row.put("vendor",vendor.id());row.put("configuration",configuration(vendor));row.put("ok",Boolean.TRUE.equals(report.get("ok")));row.put("code",report.get("code"));row.put("checked_at",Instant.now().toString());row.put("results",report.get("results"));
            if(report.get("accounts")!=null)row.put("accounts",report.get("accounts"));
            row.put("scope",vendor.connection()==null?"cli_vendor_representative_model":"configured_api_models");row.put("assumption",vendor.connection()==null?"One configured model answered per account of this service. Role launch channels are checked separately before execution.":"Catalog listing does not prove model access; configured API models are checked individually.");
            ModelCheck.atomicWrite(cache(vendor.id(),"check"),Json.write(row));rows.add(row);all&=Boolean.TRUE.equals(report.get("ok"));
        }
        return Map.of("ok",all,"code",all?"vendors_available":"vendor_check_failed","results",rows);
    }
    private List<Vendor> selected(UserConfig user,Set<String> ids)throws Exception{
        if(ids==null || ids.isEmpty() || ids.size()>64)throw new IllegalArgumentException("select vendors");var inventory=inventory(user);var selected=new ArrayList<Vendor>();
        for(String id:ids){if(!inventory.containsKey(id))throw new IllegalArgumentException("vendor unavailable");selected.add(inventory.get(id));}return selected;
    }
    private Map<String,Vendor> inventory(UserConfig user)throws Exception{
        var groups=new LinkedHashMap<String,Vendor>();
        for(var connection:user.connections().values())groups.put("api-"+connection.id(),new Vendor("api-"+connection.id(),"api",connection.provider(),providerLabel(connection.provider()),connection,new ArrayList<>(),new ArrayList<>()));
        for(var profile:user.profiles().values()){
            String id=displayVendorId(profile);var vendor=groups.get(id);
            if(vendor==null){
                var binding=ServiceBinding.of(profile);
                vendor=binding.needsMapping()?new Vendor(id,"unmapped",null,"Сервис не указан",null,new ArrayList<>(),new ArrayList<>())
                        :new Vendor(id,"cli",binding.service(),providerLabel(binding.service()),null,new ArrayList<>(),new ArrayList<>());
                groups.put(id,vendor);
            }
            vendor.profiles().add(profile);
        }
        for(var profile:DecisionProfile.load(home).values()){var vendor=groups.get("api-"+profile.connection());if(vendor!=null)vendor.classifiers().add(profile);}
        return groups;
    }
    public static final String UNMAPPED="cli-unmapped";
    /** The configuration stamp a current catalog for this profile's service card must carry. */
    public static String catalogConfiguration(UserConfig user,Profile profile){
        if(profile.connectionRef()!=null){var connection=user.connections().get(profile.connectionRef());return connection==null?null:connection.fingerprint();}
        String id=displayVendorId(profile);
        return hash(user.profiles().values().stream().filter(p->id.equals(displayVendorId(p))).map(VendorSettingsService::vendorId).distinct().sorted().toList().toString());
    }
    /** Model id to catalog effort levels from this profile's service catalog, or null when no current catalog is loaded. */
    record CatalogEfforts(Map<String,List<String>> levels,String source,String fetchedAt) {}
    static CatalogEfforts catalogEfforts(Path home,UserConfig user,Profile profile){
        String id=displayVendorId(profile);if(UNMAPPED.equals(id))return null;
        String configuration=catalogConfiguration(user,profile);if(configuration==null)return null;
        var catalog=read(home.resolve("vendors").resolve(id+"-catalog.json"));
        if(!Objects.equals(catalog.get("configuration"),configuration) || !(catalog.get("models") instanceof List<?> models))return null;
        var result=new LinkedHashMap<String,List<String>>();
        for(Object value:models)if(value instanceof Map<?,?> model && model.get("id") instanceof String modelId && model.get("efforts") instanceof List<?> efforts)
            result.put(modelId,efforts.stream().filter(String.class::isInstance).map(String.class::cast).toList());
        return new CatalogEfforts(Map.copyOf(result),safe(catalog.get("source")),safe(catalog.get("fetched_at")));
    }
    /**
     * Public service identity: one card per authenticating service. Clients, Orca, roles and
     * model developers never create a card; a CLI profile whose service is unknown is listed
     * under {@link #UNMAPPED} instead of being attributed to its model developer.
     */
    public static String displayVendorId(Profile profile){
        if(profile.connectionRef()!=null)return "api-"+profile.connectionRef();
        var binding=ServiceBinding.of(profile);return binding.needsMapping()?UNMAPPED:"cli-provider-"+binding.service();
    }
    private static List<Map<String,Object>> accounts(Vendor vendor){
        var accounts=new TreeMap<String,List<Profile>>();
        for(var profile:vendor.profiles()){var binding=ServiceBinding.of(profile);accounts.computeIfAbsent(binding.needsMapping()?"":binding.account(),ignored->new ArrayList<>()).add(profile);}
        var rows=new ArrayList<Map<String,Object>>();
        for(var account:accounts.entrySet()){if(account.getKey().isEmpty())continue;var row=new LinkedHashMap<String,Object>();row.put("id",account.getKey());
            row.put("binding",account.getValue().stream().map(p->ServiceBinding.of(p).source()).distinct().sorted().toList());row.put("profiles",account.getValue().stream().map(Profile::name).toList());rows.add(row);}
        return rows;
    }
    private static String providerLabel(String provider){if(provider==null)return "";return switch(provider){
        case "openai"->"OpenAI";case "x-ai"->"xAI";case "deepseek"->"DeepSeek";
        case "antigravity", "google"->"Google Antigravity";case "anthropic"->"Anthropic";
        case "openrouter"->"OpenRouter";case "gonkarouter"->"GonkaRouter";case "joingonka"->"JoinGonka";
        default->provider;};}
    /** Stable group identity separates CLI accounts/providers and Orca transport without exposing argv. */
    public static String vendorId(Profile p){
        if(p.connectionRef()!=null)return "api-"+p.connectionRef();
        Set<String> selectors=Set.of("--profile","--config","-c","--api-key","--base-url","--api-base","--organization","--project","--auth-mode","--auth-file","--credential","--endpoint");
        StringBuilder auth=new StringBuilder();var args=p.args();for(int i=0;i<args.size();i++){
            String a=args.get(i);int equals=a.indexOf('=');
            if(equals>0 && selectors.contains(a.substring(0,equals))){String value=a.substring(equals+1);if(!value.startsWith("model_reasoning_effort"))auth.append(a).append(';');}
            else if(selectors.contains(a) && i+1<args.size()){
                String value=args.get(++i);if(!value.startsWith("model_reasoning_effort"))auth.append(a).append('=').append(value).append(';');
            }
        }
        var binding=ServiceBinding.of(p);String service=binding.needsMapping()?"unmapped:"+Profile.canonicalVendor(p.modelVendor()):binding.service()+"/"+binding.account();
        return "cli-"+hash(service+"|"+p.command()+"|"+p.runner()+"|"+auth).substring(0,16);
    }
    private Map<String,Object> cliCatalog(Vendor vendor)throws Exception{
        boolean codex="openai".equals(vendor.provider()) && vendor.profiles().stream().allMatch(p->"codex".equals(commandName(p.command())));
        boolean grok="x-ai".equals(vendor.provider()) && vendor.profiles().stream().allMatch(p->"grok".equals(commandName(p.command())));
        if(!codex && !grok)return unavailable("catalog_not_supported");
        Path root=cliHome.resolve(codex?".codex":".grok");
        if(codex){String configured=environment.apply("CODEX_HOME");if(configured!=null&&!configured.isBlank())root=Path.of(configured);}
        Path source=root.resolve("models_cache.json");if(!Files.isRegularFile(source)||Files.size(source)>4*1024*1024)return unavailable("catalog_cache_missing");
        var data=Json.parseObject(Files.readString(source));var models=new ArrayList<Map<String,Object>>();
        if(codex && data.get("models") instanceof List<?> entries)for(Object entry:entries)if(entry instanceof Map<?,?> model && !"hide".equals(model.get("visibility"))){String id=safe(model.get("slug"));if(id!=null)models.add(model(id,safe(model.get("display_name")),efforts(model.get("supported_reasoning_levels"),"effort")));}
        if(grok && data.get("models") instanceof Map<?,?> entries)for(var entry:entries.entrySet())if(entry.getValue() instanceof Map<?,?> value && value.get("info") instanceof Map<?,?> info && !Boolean.TRUE.equals(info.get("hidden"))){String id=safe(info.get("id"));if(id!=null)models.add(model(id,safe(info.get("name")),efforts(info.get("reasoning_efforts"),"value")));}
        if(models.isEmpty())return unavailable("catalog_cache_invalid");
        var result=new LinkedHashMap<String,Object>();result.put("status","available");result.put("source",codex?"Codex CLI local model cache":"Grok CLI local model cache");result.put("models",models);String fetched=safe(data.get("fetched_at"));result.put("fetched_at",fetched==null?Files.getLastModifiedTime(source).toInstant().toString():fetched);
        result.put("warning","Local CLI cache; refresh the CLI's model menu to obtain newer releases.");return result;
    }
    private Map<String,Object> apiCatalog(Connection connection)throws Exception{
        if(!connection.enabled())return unavailable("connection_disabled");
        String token=new CredentialResolver(environment).resolve(connection);
        var reply=ApiExecutor.call(connection,"GET",URI.create(connection.baseUrl()+"/models"),null,token,Duration.ofSeconds(15));
        if(reply.status()!=200)return unavailable(reply.status()==401||reply.status()==403?"catalog_auth_failed":"catalog_http_failed");
        var data=Json.parseObject(reply.body());if(!(data.get("data") instanceof List<?> entries))return unavailable("catalog_response_invalid");
        var models=new LinkedHashMap<String,Map<String,Object>>();for(Object entry:entries){if(models.size()>=2000)break;if(entry instanceof Map<?,?> value){String id=safe(value.get("id")),label=safe(value.get("name"));if(id!=null && (token==null || !id.contains(token)) && (label==null || token==null || !label.contains(token)))models.put(id,model(id,label,List.of()));}}
        if(models.isEmpty())return unavailable("catalog_empty");return Map.of("status","available","source","provider_models_endpoint","fetched_at",Instant.now().toString(),"models",new ArrayList<>(models.values()),"warning","Catalog membership does not prove key access or coding capability.");
    }
    private static Map<String,Object> model(String id,String label,List<String> efforts){return Map.of("id",id,"label",label==null?id:label,"efforts",EffortCapabilities.ordered(efforts),"origin","catalog");}
    private static List<String> efforts(Object value,String key){var result=new ArrayList<String>();if(value instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> row){String effort=safe(row.get(key));if(effort!=null && effort.matches("[a-z][a-z0-9_-]{0,31}"))result.add(effort);}return result;}
    private static Map<String,Object> unavailable(String code){return Map.of("status","unavailable","code",code,"models",List.of(),"warning","Could not load a current model list. Enter a model from the vendor's current documentation.");}
    private Path cache(String id,String type){return home.resolve("vendors").resolve(id+"-"+type+".json");}
    private static Map<String,Object> read(Path path){try{if(Files.isRegularFile(path)&&Files.size(path)<=4*1024*1024)return Json.parseObject(Files.readString(path));}catch(Exception ignored){}return Map.of();}
    private static String configuration(Vendor vendor){return vendor.connection()!=null?vendor.connection().fingerprint():hash(vendor.profiles().stream().map(VendorSettingsService::vendorId).distinct().sorted().toList().toString());}
    private static String safe(Object value){if(!(value instanceof String text)||text.isBlank()||text.length()>256||text.chars().anyMatch(c->Character.isISOControl(c)))return null;return text;}
    private static String commandName(String command){return ServiceBinding.client(command);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String docs(String provider){if(provider==null)return "";return switch(provider){case "openai"->"https://developers.openai.com/codex/models";case "x-ai"->"https://docs.x.ai/developers/models";case "google", "antigravity"->"https://antigravity.google/docs/models/";case "anthropic"->"https://platform.claude.com/docs/en/about-claude/models/overview";case "openrouter"->"https://openrouter.ai/models";case "gonkarouter"->"https://gonkarouter.io/";case "joingonka"->"https://gate.joingonka.ai/dashboard";case "deepseek"->"https://api-docs.deepseek.com/quick_start/pricing";default->"";};}
}
