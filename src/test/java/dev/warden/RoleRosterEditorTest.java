package dev.warden;

import dev.warden.config.*;
import dev.warden.testing.*;
import java.nio.file.*;
import java.util.*;

public final class RoleRosterEditorTest implements Suite {
    public String name(){return "role-roster-editor";}
    public void run(Check check)throws Exception {
        Path home=Files.createTempDirectory("warden-role-roster-");new UserSetup().run(home);
        Path primary=home.resolve("profiles/primary.yaml"),reserve=home.resolve("profiles/reserve.yaml"),policy=home.resolve("policy.yaml");
        Files.writeString(primary,profile("primary"));Files.writeString(reserve,profile("reserve"));
        Files.writeString(policy,"""
                version: 1
                roles:
                  reviewer:
                    profiles: [primary, reserve]
                    strategy: first
                    require_independent_vendor: true
                failover:
                  on_quota_exhausted: confirm
                  on_vendor_failure: stop
                """);
        var editor=new RoleRosterEditor(home);
        String originalPrimary=Files.readString(primary),originalReserve=Files.readString(reserve);
        var result=editor.apply(payload(policy,List.of(slot(reserve,"old-model","medium"),slot(primary,"old-model","medium"))));
        check.eq("ordered selection is saved",200,result.status());
        check.eq("unchanged launch keeps existing admission",originalPrimary,Files.readString(primary));
        check.eq("unchanged reserve keeps existing admission",originalReserve,Files.readString(reserve));
        check.eq("reordering needs no repeated capability probe",List.of(),result.report().get("pending_verification"));
        var loaded=UserConfig.load(home);
        check.eq("horizontal ordering reaches the resolver",List.of("reserve","primary"),loaded.policy().roles().get("reviewer").profiles());
        check.eq("independent review survives composition",true,loaded.policy().roles().get("reviewer").requireIndependentVendor());
        check.eq("paid failover consent survives composition","confirm",loaded.policy().failoverMode());
        check.eq("vendor failure policy survives composition","stop",loaded.policy().vendorFailureMode());
        String beforePolicy=Files.readString(policy);
        var stale=new LinkedHashMap<String,Object>(slot(reserve,"old-model","medium"));stale.put("revision","stale");
        result=editor.apply(payload(policy,List.of(slot(primary,"new-model","high"),stale)));
        check.eq("stale secondary selection conflicts",409,result.status());
        check.eq("late invalid selection cannot partly edit primary",originalPrimary,Files.readString(primary));
        check.eq("late invalid selection cannot partly edit policy",beforePolicy,Files.readString(policy));
        result=editor.apply(payload(policy,List.of(slot(primary,"new-model","high"),slot(reserve,"old-model","medium"))));
        check.eq("selected model and effort are saved together",200,result.status());
        check.eq("only changed template is pending capability validation",List.of("primary"),result.report().get("pending_verification"));
        loaded=UserConfig.load(home);Profile changed=loaded.profiles().get("primary");
        check.eq("new model reaches launch template","new-model",changed.model());
        check.eq("new effort reaches launch template","high",changed.effort());
        check.eq("old model verification is never inherited",false,changed.verified());
        check.eq("read only tool contract is retained",true,changed.readOnly());
        check.eq("command and launch grants are retained","echo",changed.command());
        check.eq("verification follows new model and effort","echo {{model}} {{effort}} verified",changed.verificationProbe());
        check.eq("unchanged reserve remains verified",true,loaded.profiles().get("reserve").verified());
        var revisions=(Map<?,?>)result.report().get("revisions");
        check.eq("caller gets post-save revision for integrated verification",ModelSettingsService.revision(primary),revisions.get("primary"));
        check.eq("duplicate template cannot fill two ordered slots",400,editor.apply(payload(policy,List.of(slot(primary,"new-model","high"),slot(primary,"new-model","high")))).status());
        var injection=new LinkedHashMap<>(slot(primary,"new-model","high"));injection.put("model","x; invoke-other-command");
        check.eq("model field cannot become a command",400,editor.apply(payload(policy,List.of(injection))).status());
        var unknown=new LinkedHashMap<>(slot(primary,"new-model","high"));unknown.put("command","other");
        check.eq("composition cannot edit launch commands",400,editor.apply(payload(policy,List.of(unknown))).status());
        var traversal=new LinkedHashMap<>(slot(primary,"new-model","high"));traversal.put("profile","../other");
        check.eq("template references cannot leave config home",400,editor.apply(payload(policy,List.of(traversal))).status());
        Files.writeString(primary,profile("primary").replace("'{{model}}'","'old-model'"));
        check.eq("literal CLI model cannot masquerade as editable binding",400,editor.apply(payload(policy,List.of(slot(primary,"next-model","medium")))).status());
        Files.writeString(primary,profile("primary").replace("probe: echo {{model}} {{effort}} verified","probe: echo old-model medium verified"));
        result=editor.apply(payload(policy,List.of(slot(primary,"next-model","high"))));
        check.eq("literal existing probe can be rebound safely",200,result.status());
        check.eq("both probe bindings update together","echo {{model}} {{effort}} verified",UserConfig.load(home).profiles().get("primary").verificationProbe());
        new ConnectionStore(home).add(new Connection("api","custom","openai_chat","http://127.0.0.1:9999/v1",null,true));
        Path api=home.resolve("profiles/api-review.yaml");
        Files.writeString(api,"version: 1\nprofile: api-review\nrole: reviewer\nrunner: api\nconnection: api\nmodel_vendor: deepseek\nmodel: deepseek/old\nread_only: true\nprompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\nverification: {status: unverified}\n");
        var apiSlot=new LinkedHashMap<String,Object>();apiSlot.put("profile","api-review");apiSlot.put("revision",ModelSettingsService.revision(api));apiSlot.put("model","z-ai/new");apiSlot.put("model_vendor","z-ai");
        result=editor.apply(payload(policy,List.of(apiSlot)));
        check.eq("API role selects model developer within same connection",200,result.status());
        changed=UserConfig.load(home).profiles().get("api-review");
        check.eq("API identity follows selected developer","z-ai",changed.modelVendor());
        check.eq("API connection remains the selected credential scope","api",changed.connectionRef());
        check.eq("API response availability cannot grant text or tool admission",false,changed.verified());
        check.eq("API changed slot is pending integrated validation",List.of("api-review"),result.report().get("pending_verification"));
    }
    private static Map<String,Object> payload(Path policy,List<?> slots)throws Exception{return Map.of("revision",ModelSettingsService.revision(policy),"role","reviewer","strategy","first","slots",slots);}
    private static Map<String,Object> slot(Path file,String model,String effort)throws Exception{return Map.of("profile",file.getFileName().toString().replace(".yaml",""),"revision",ModelSettingsService.revision(file),"model",model,"effort",effort);}
    private static String profile(String name){return "version: 1\nprofile: "+name+"\nrole: reviewer\nvendor: openai\nmodel: old-model\neffort: medium\ncommand: echo\nargs: ['--model','{{model}}','--effort','{{effort}}']\nread_only: true\nprompt_template: prompts/reviewer.md\njson_schema: schemas/reviewer.json\nverification:\n  probe: echo {{model}} {{effort}} verified\n  expect: verified\n  verified_on: '2026-10-28'\n";}
}
