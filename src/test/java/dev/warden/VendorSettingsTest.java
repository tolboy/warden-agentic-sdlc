package dev.warden;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.execution.*;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.run.RunReadiness;
import dev.warden.testing.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Catalog lookup is explicit, model access differs from vendor authentication, and receipts are projected. */
public final class VendorSettingsTest implements Suite {
    public String name(){return "vendor-settings";}
    public void run(Check check)throws Exception{
        Path home=Files.createTempDirectory("warden-vendor-settings-"),cliHome=Files.createTempDirectory("warden-vendor-cli-");
        Files.createDirectories(home.resolve("profiles"));Files.createDirectories(cliHome.resolve(".codex"));Files.createDirectories(cliHome.resolve(".grok"));
        Files.writeString(home.resolve("profiles/codex-read.yaml"),cli("codex-read","codex","codex","gpt-6.1-sol","medium","warden-read","direct"));
        Files.writeString(home.resolve("profiles/codex-other.yaml"),cli("codex-other","codex","codex","gpt-6-astra","high","warden-read","direct"));
        Files.writeString(home.resolve("profiles/deepseek-read.yaml"),cli("deepseek-read","deepseek","codex","deepseek/model","medium","deepseek","direct"));
        Files.writeString(home.resolve("profiles/grok-read.yaml"),cli("grok-read","grok","grok","grok-4.7","high","","direct"));
        Files.writeString(cliHome.resolve(".codex/models_cache.json"),Json.write(Map.of("fetched_at","2026-10-28T00:00:00Z","identity","private-account-fingerprint","models",List.of(
                Map.of("slug","gpt-6.1-sol","display_name","Sol","visibility","list","supported_reasoning_levels",List.of(Map.of("effort","medium","description","ignore"))),
                Map.of("slug","gpt-reserve","visibility","hide")))));
        Files.writeString(cliHome.resolve(".grok/models_cache.json"),Json.write(Map.of("fetched_at","2026-10-28T00:00:00Z","models",Map.of("grok-4.7",Map.of("api_key","cache-secret-must-not-escape","info",Map.of("id","grok-4.7","name","Grok","reasoning_efforts",List.of(Map.of("value","high"))))))));
        AtomicInteger probes=new AtomicInteger();var probed=new ArrayList<String>();
        var transport=new ModelCheck(new ProcessRunner(),ignored->null,(p,u,d,s)->{probes.incrementAndGet();probed.add(p.name());return Map.of("ok",true,"code","pong","vendor_turn_started",true);});
        try(var api=new CatalogStub()){
            var connection=api.connection();new ConnectionStore(home).add(connection);
            Files.writeString(home.resolve("profiles/api-one.yaml"),api("api-one","deepseek/one"));
            Files.writeString(home.resolve("profiles/api-one-copy.yaml"),api("api-one-copy","deepseek/one"));
            Files.writeString(home.resolve("profiles/api-two.yaml"),api("api-two","deepseek/two"));
            var service=new VendorSettingsService(home,home,key->"CATALOG_KEY".equals(key)?"catalog-secret":null,transport,cliHome);
            var before=service.list();check.that("billing is not inferred from CLI or API",rows(before).stream().allMatch(r->"unknown".equals(r.get("billing"))));check.eq("GET inventory never calls API",0,api.calls.get());check.eq("GET inventory never pings",0,probes.get());
            var rows=rows(before);check.eq("same CLI auth/model family shares vendor group",4,rows.size());
            String codexId=vendor(rows,"openai").get("id").toString(),grokId=vendor(rows,"x-ai").get("id").toString(),deepseekId=vendor(rows,"deepseek").get("id").toString();
            check.eq("new catalog reports unavailable until explicit refresh","unavailable",((Map<?,?>)vendor(rows,"openai").get("catalog")).get("status"));
            service.refresh(Set.of(codexId,grokId,deepseekId,"api-stub"));
            check.eq("refresh makes exactly one models GET",1,api.calls.get());check.eq("refresh authenticates intended endpoint","Bearer catalog-secret",api.authorization);
            rows=rows(service.list());var codex=vendor(rows,"openai");var grok=vendor(rows,"x-ai");
            check.eq("local source provenance remains explicit","Codex CLI local model cache",((Map<?,?>)codex.get("catalog")).get("source"));
            check.that("hidden cache models are omitted",!Json.write(codex).contains("gpt-reserve"));check.that("local credential metadata is never projected",!Json.write(grok).contains("cache-secret"));
            check.that("cache identity is never projected",!Json.write(codex).contains("private-account"));
            check.eq("unsupported CLI catalog warns rather than inventing versions","unavailable",((Map<?,?>)vendor(rows,"deepseek").get("catalog")).get("status"));
            check.that("manual fallback has documentation link",vendor(rows,"deepseek").get("docs_url").toString().startsWith("https://"));
            check.eq("API catalog safely projects ids",2,((List<?>)((Map<?,?>)vendor(rows,"custom").get("catalog")).get("models")).size());
            check.that("API raw metadata is not persisted",!Files.readString(home.resolve("vendors/api-stub-catalog.json")).contains("upstream-private-field"));
            service.check(Set.of(codexId));check.eq("vendor check pings one CLI model",1,probes.get());
            service.check(Set.of("api-stub"));check.eq("configured API models are pinged separately without role duplicates",3,probes.get());
            check.eq("catalog GET never pays for chat inference",1,api.calls.get());
            check.eq("vendor receipt states its assumption","cli_vendor_representative_model",((Map<?,?>)vendor(rows(service.list()),"openai").get("check")).get("scope"));
            api.status=503;service.refresh(Set.of("api-stub"));var stale=(Map<?,?>)vendor(rows(service.list()),"custom").get("catalog");
            check.eq("failed refresh is not shown as current","unavailable",stale.get("status"));check.eq("last known list explicitly marked stale",true,stale.get("stale"));
            check.that("error bodies and keys never persist",!Files.readString(home.resolve("vendors/api-stub-catalog.json")).contains("catalog-secret"));
            check.rejects("arbitrary vendor paths are refused",null,()->service.refresh(Set.of("../../elsewhere")));
            var user=UserConfig.load(home);var one=user.profiles().get("codex-read");var other=user.profiles().get("codex-other");
            check.eq("same auth groups across model and effort",VendorSettingsService.vendorId(one),VendorSettingsService.vendorId(other));
            var different=Profile.parse(cli("other-account","codex","codex","gpt-6.1-sol","medium","other-account","direct"),"fixture");
            check.that("different auth selector separates CLI accounts",!VendorSettingsService.vendorId(one).equals(VendorSettingsService.vendorId(different)));
            var inlineAccount=Profile.parse(cli("inline-account","codex","codex","gpt-6.1-sol","medium","","direct").replace("args: [","args: [\"--profile=another-account\","),"fixture");
            check.that("inline auth selectors do not merge accounts",!VendorSettingsService.vendorId(one).equals(VendorSettingsService.vendorId(inlineAccount)));
            var orca=Profile.parse(cli("orca-read","codex","codex","gpt-6.1-sol","medium","","orca"),"fixture");
            check.that("Orca readiness is not proven by direct CLI",!VendorSettingsService.vendorId(one).equals(VendorSettingsService.vendorId(orca)));
            int earlier=probes.get();var result=RunReadiness.using(transport).check(user,home,List.of(one,other,one.withEffort("low"),user.profiles().get("api-one"),user.profiles().get("api-one-copy"),user.profiles().get("api-two"),different,orca),false,ignored->{});
            check.eq("launch readiness groups CLI but preserves API models/accounts/channels",earlier+5,probes.get());
            check.eq("readiness discloses representative count",5,result.get("checked_representatives"));check.eq("readiness discloses original profile count",8,result.get("selected_profiles"));
            Files.writeString(home.resolve("profiles/agy-a.yaml"),cli("agy-a","google","agy","gemini-unavailable","high","","direct"));
            Files.writeString(home.resolve("profiles/agy-b.yaml"),cli("agy-b","openai","agy","gpt-oss-available","high","","direct"));
            var agyRows=rows(service.list());var agyVendor=vendor(agyRows,"antigravity");
            check.eq("Antigravity login groups across model developers",2,((List<?>)agyVendor.get("profiles")).size());
            AtomicInteger agyAttempts=new AtomicInteger();var partial=new ModelCheck(new ProcessRunner(),ignored->null,(p,u,d,s)->{agyAttempts.incrementAndGet();boolean ok=p.model().equals("gpt-oss-available");return Map.of("ok",ok,"code",ok?"pong":"check_timeout");});
            var fallbackService=new VendorSettingsService(home,home,key->"CATALOG_KEY".equals(key)?"catalog-secret":null,partial,cliHome);
            var agyResult=fallbackService.check(Set.of(agyVendor.get("id").toString()));
            check.eq("a second configured model can prove shared CLI login",true,agyResult.get("ok"));
            check.eq("failed CLI representative gets one bounded alternative",2,agyAttempts.get());
            Files.writeString(home.resolve("profiles/codex-write.yaml"),cli("codex-write","codex","codex","gpt-6.1-sol","high","warden-write","direct"));
            Files.writeString(home.resolve("profiles/codex-orca.yaml"),cli("codex-orca","codex","codex","gpt-6.1-sol","medium","","orca"));
            var publicRows=rows(service.list());
            check.eq("one OpenAI card across CLI profiles and Orca",1L,publicRows.stream().filter(r->"openai".equals(r.get("provider"))).count());
            check.eq("public vendor label has no client or orchestrator","OpenAI",vendor(publicRows,"openai").get("label"));
            check.eq("all OpenAI role templates remain selectable",4,((List<?>)vendor(publicRows,"openai").get("profiles")).size());
            check.eq("DeepSeek service label does not name its Codex client","DeepSeek",vendor(publicRows,"deepseek").get("label"));
            check.that("vendor titles never include Orca",publicRows.stream().noneMatch(r->String.valueOf(r.get("label")).contains("Orca")));
            check.eq("Orca and direct share public vendor identity",VendorSettingsService.displayVendorId(one),VendorSettingsService.displayVendorId(orca));
            check.that("public grouping preserves technical readiness isolation",!VendorSettingsService.vendorId(one).equals(VendorSettingsService.vendorId(orca)));
            var changed=new Connection("stub","custom","openai_chat",connection.baseUrl(),"env:OTHER_KEY",true);var stored=new ConnectionStore(home).versioned("stub");new ConnectionStore(home).update(changed,stored.revision());
            check.that("changed connection cannot reuse vendor check",!vendor(rows(service.list()),"custom").containsKey("check"));
        }
    }
    private static String cli(String name,String vendor,String command,String model,String effort,String account,String runner){return "version: 1\nprofile: "+name+"\nrole: reviewer\nvendor: "+vendor+"\nmodel: "+model+"\neffort: "+effort+"\ncommand: "+command+"\nrunner: "+runner+"\nread_only: true\n"+("orca".equals(runner)?"":"args: "+Json.write(account.isEmpty()?List.of("--model","{{model}}","--effort","{{effort}}") : List.of("--profile",account,"--model","{{model}}","--effort","{{effort}}"))+"\n");}
    private static String api(String name,String model){return "version: 1\nprofile: "+name+"\nrole: reviewer\nrunner: api\nconnection: stub\nmodel_vendor: deepseek\nmodel: "+model+"\nread_only: true\nlimits: {max_output_tokens: 512}\n";}
    private static List<Map<String,Object>> rows(Map<String,Object> report){return (List<Map<String,Object>>)report.get("vendors");}
    private static Map<String,Object> vendor(List<Map<String,Object>> rows,String provider){return rows.stream().filter(r->provider.equals(r.get("provider"))).findFirst().orElseThrow();}
    private static final class CatalogStub implements AutoCloseable{
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();String authorization;int status=200;
        CatalogStub()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/models",exchange->{calls.incrementAndGet();authorization=exchange.getRequestHeaders().getFirst("Authorization");String response=status==200?Json.write(Map.of("data",List.of(Map.of("id","deepseek/one","name","One","upstream-private-field","do-not-save"),Map.of("id","deepseek/two")))):"{\"error\":\"catalog-secret\"}";byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();}
        Connection connection(){return new Connection("stub","custom","openai_chat","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","env:CATALOG_KEY",true);}
        public void close(){server.stop(0);}
    }
}
