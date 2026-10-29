package dev.warden.execution;

import dev.warden.config.*;
import dev.warden.json.Json;
import dev.warden.testing.*;
import java.nio.file.*;
import java.util.*;

/** Model/connection admission and exact gateway level discovery, without paid calls. */
public final class EffortCapabilitiesTest implements Suite {
    public String name(){return "effort-capabilities";}
    public void run(Check check)throws Exception {
        Path home=Files.createTempDirectory("warden-effort-catalog-");
        var connection=new Connection("fixture","custom","openai_chat","http://127.0.0.1:9876/v1",null,true);
        new ConnectionStore(home).add(connection);
        var profile=Profile.parse("""
                version: 1
                profile: reader
                role: reviewer
                runner: api
                connection: fixture
                model_vendor: openai
                model: openai/gpt-6-example
                read_only: true
                prompt_template: prompts/reviewer.md
                json_schema: schemas/reviewer.json
                limits: {max_output_tokens: 1024}
                ""","fixture").withConnection(connection,home);
        var declared=Map.of("reasoning",Map.of("supported_efforts",List.of("max","medium","low","none","ultra"),"mandatory",true));
        var row=VendorSettingsService.apiModel(connection,profile.model(),null,declared);
        check.eq("gateway model levels are exact and ordered",List.of("low","medium","max"),row.get("efforts"));
        check.eq("discovered wire field is explicit","openrouter_reasoning",row.get("effort_delivery"));
        check.eq("missing metadata exposes no effort",List.of(),VendorSettingsService.apiModel(connection,"test/plain",null,Map.of()).get("efforts"));
        var all=new LinkedHashMap<String,Object>();all.put("supported_efforts",null);
        check.eq("null metadata accepts every documented gateway level",EffortCapabilities.API_LEVELS,
                VendorSettingsService.apiModel(connection,"test/all",null,Map.of("reasoning",all)).get("efforts"));
        var user=UserConfig.load(home);
        check.eq("API never inherits CLI built-in guesses",List.of(),new EffortCapabilities(home,user).levels(profile));
        write(home,connection,row,false);
        var capabilities=new EffortCapabilities(home,user);
        check.eq("catalog levels reach API registry",List.of("low","medium","max"),capabilities.levels(profile));
        check.eq("registry records transport","openrouter_reasoning",capabilities.describe(profile).get("delivery"));
        check.eq("registry retains observation date","2026-10-29T00:00:00Z",capabilities.describe(profile).get("observed_at"));
        check.eq("supported explicit effort is admitted",null,EffortCapabilities.problem(profile.withEffort("max")));
        check.eq("unsupported effort fails before dispatch","role_effort_unsupported",EffortCapabilities.problem(profile.withEffort("high")));
        var override=new RunOverride(Map.of(),Map.of("review","max"),Map.of());
        check.eq("API overlay passes exact supported level",null,override.problem("review",profile,Map.of()));
        check.eq("API overlay actually changes dispatched profile","max",override.adapt("review",profile,Map.of()).effort());
        check.that("unsupported overlay is refused",new RunOverride(Map.of(),Map.of("review","high"),Map.of()).problem("review",profile,Map.of())!=null);
        var noDelivery=new LinkedHashMap<>(row);noDelivery.remove("effort_delivery");write(home,connection,noDelivery,false);
        check.eq("old catalog without delivery proof cannot enable effort","role_effort_unsupported",EffortCapabilities.problem(profile.withEffort("medium")));
        write(home,connection,row,true);
        check.eq("stale catalog does not authorize API effort","role_effort_unsupported",EffortCapabilities.problem(profile.withEffort("medium")));
        write(home,connection,row,false);
        Files.createDirectories(home.resolve("prompts")); Files.createDirectories(home.resolve("schemas"));
        Files.writeString(home.resolve("prompts/reviewer.md"),"Review {{goal}} and return the artifact.");
        Files.writeString(home.resolve("schemas/reviewer.json"),"{}");
        var settings=new ModelSettingsService(home,home,ignored->null);
        var saved=settings.apply("save-profile",Map.of("name","reader","revision","absent","model",profile.model(),
                "vendor","openai","connection","fixture","kind","reviewer","effort","medium"));
        check.eq("settings can create API profile with supported effort",200,saved.status());
        Path profileFile=home.resolve("profiles/reader.yaml");
        check.eq("saved API effort survives reload","medium",UserConfig.load(home).profiles().get("reader").effort());
        Path policy=home.resolve("policy.yaml");
        Files.writeString(policy,"version: 1\nroles:\n  reviewer:\n    profiles: [reader]\n    strategy: first\n");
        var roster=new RoleRosterEditor(home);
        var changedEffort=roster.apply(Map.of("revision",ModelSettingsService.revision(policy),"role","reviewer","strategy","first",
                "slots",List.of(Map.of("profile","reader","revision",ModelSettingsService.revision(profileFile),"model",profile.model(),"effort","max"))));
        check.eq("role editor binds API effort without inventing CLI args",200,changedEffort.status());
        check.eq("role edit persists exact API effort","max",UserConfig.load(home).profiles().get("reader").effort());
        var refused=settings.apply("save-profile",Map.of("name","reader","revision",ModelSettingsService.revision(profileFile),
                "model",profile.model(),"vendor","openai","connection","fixture","effort","high"));
        check.eq("settings reject unsupported API level",400,refused.status());
        check.eq("refused settings preserve saved effort","max",UserConfig.load(home).profiles().get("reader").effort());
        var changed=new Connection("fixture","custom","openai_chat","http://127.0.0.1:9877/v1",null,true);
        check.eq("connection change invalidates catalog levels",List.of(),new EffortCapabilities(home,
                new UserConfig(home,user.policy(),user.profiles(),Map.of(),false,Map.of("fixture",changed))).levels(profile));
        check.eq("no effort remains compatible without registry",null,EffortCapabilities.problem(profile));
        check.rejects("local HTTP runner still refuses effort",null,()->Profile.parse("""
                version: 1
                profile: local
                role: reviewer
                runner: local
                endpoint: http://127.0.0.1:9876/run
                effort: medium
                read_only: true
                ""","fixture"));
    }
    private static void write(Path home,Connection connection,Map<String,Object> model,boolean stale)throws Exception{
        Files.createDirectories(home.resolve("vendors"));
        Files.writeString(home.resolve("vendors/api-fixture-catalog.json"),Json.write(Map.of(
                "status","available","stale",stale,"configuration",connection.fingerprint(),
                "source","fixture models endpoint","fetched_at","2026-10-29T00:00:00Z","models",List.of(model))));
    }
}
