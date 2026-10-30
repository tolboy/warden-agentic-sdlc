package dev.warden.execution;

import com.sun.net.httpserver.HttpServer;
import dev.warden.config.*;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Catalog observations, access receipts and the user's selected roles have separate lifecycles. */
public final class ModelCatalogLifecycleTest implements Suite {
    public String name(){return "model-catalog-lifecycle";}
    public void run(Check check)throws Exception { cliCache(check); apiLifecycle(check); cliProtocol(check); }

    private static void cliProtocol(Check check)throws Exception {
        Path dir=Files.createTempDirectory("warden-cli-model-protocol-");
        var result=CliModelCatalog.codex(command(dir,"pages"),dir,Duration.ofSeconds(5));
        check.eq("CLI metadata reads all pages",2,result.models().size());
        check.eq("CLI metadata only initializes and lists models",List.of("initialize","initialized","model/list","model/list"),Files.readAllLines(dir.resolve("methods")));
        check.eq("CLI metadata date does not imply upstream freshness","unknown",result.freshness());
        check.that("private metadata CLI settles after success",settled(dir));
        Path refused=Files.createTempDirectory("warden-cli-model-refused-");
        check.rejects("protocol refusal has a fixed safe error","catalog request refused",()->CliModelCatalog.codex(command(refused,"refuse"),refused,Duration.ofSeconds(5)));
        check.that("refused metadata CLI settles",settled(refused));
        Path slow=Files.createTempDirectory("warden-cli-model-timeout-");
        long started=System.nanoTime();
        check.rejects("nonanswering metadata CLI times out","catalog deadline",()->CliModelCatalog.codex(command(slow,"slow"),slow,Duration.ofSeconds(3)));
        check.that("metadata deadline includes bounded process cleanup",Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(10))<0);
        check.that("timed out metadata CLI is stopped",settled(slow));
        Path cancelled=Files.createTempDirectory("warden-cli-model-cancelled-");
        var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
        var worker=Thread.ofVirtual().start(()->{try{CliModelCatalog.codex(command(cancelled,"slow"),cancelled,Duration.ofSeconds(20));}catch(Throwable problem){failure.set(problem);interrupted.set(Thread.currentThread().isInterrupted());}});
        long ready=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<ready&&(!Files.exists(cancelled.resolve("methods"))||!Files.readString(cancelled.resolve("methods")).contains("model/list")))Thread.sleep(20);
        worker.interrupt();worker.join(8000);
        check.that("metadata cancellation propagates",failure.get() instanceof InterruptedException&&interrupted.get());
        check.that("cancelled metadata worker settles",!worker.isAlive());
        check.that("cancelled metadata child settles",settled(cancelled));
        Path oversized=Files.createTempDirectory("warden-cli-model-oversized-");
        check.rejects("unterminated frame is bounded before parsing","catalog_frame_limit",
                ()->CliModelCatalog.codex(command(oversized,"oversized"),oversized,Duration.ofSeconds(5)));
        check.that("oversized metadata child settles",settled(oversized));
        Path orphan=Files.createTempDirectory("warden-cli-model-orphan-");
        check.eq("metadata response survives parent exit",1,CliModelCatalog.codex(command(orphan,"orphan"),orphan,Duration.ofSeconds(5)).models().size());
        check.that("exited metadata parent settles",settled(orphan));
        long helper=Long.parseLong(Files.readString(orphan.resolve("helper-pid")));
        check.that("observed helper settles after metadata parent exits",ProcessHandle.of(helper).map(p->!p.isAlive()).orElse(true));
    }

    private static List<String> command(Path dir,String mode){
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        String classpath=Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().normalize().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        return List.of(executable,"-cp",classpath,MetadataCli.class.getName(),dir.toString(),mode);
    }
    private static boolean settled(Path dir)throws Exception {
        if(!Files.exists(dir.resolve("pid")))return false;
        long pid=Long.parseLong(Files.readString(dir.resolve("pid")));return ProcessHandle.of(pid).map(p->!p.isAlive()).orElse(true);
    }
    /** A strict local fixture rejects every operation other than the documented metadata handshake. */
    public static final class MetadataCli {
        public static void main(String[] args)throws Exception {
            if("helper".equals(args[1])){Files.writeString(Path.of(args[0]).resolve("helper-pid"),String.valueOf(ProcessHandle.current().pid()));Thread.sleep(60000);return;}
            Path dir=Path.of(args[0]);Files.writeString(dir.resolve("pid"),String.valueOf(ProcessHandle.current().pid()));boolean initialized=false;int pages=0;
            try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null){
                var message=Json.parseObject(line);String method=String.valueOf(message.get("method"));Files.writeString(dir.resolve("methods"),method+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                if("initialize".equals(method)){reply(Map.of("id",message.get("id"),"result",Map.of()));continue;}
                if("initialized".equals(method)){initialized=true;continue;}
                if(!initialized||!"model/list".equals(method))System.exit(7);
                var params=(Map<?,?>)message.get("params");if(!Boolean.FALSE.equals(params.get("includeHidden")))System.exit(8);
                if("slow".equals(args[1])){Thread.sleep(60000);continue;}
                if("oversized".equals(args[1])){System.out.print("x".repeat(CliModelCatalog.MAX_FRAME_BYTES+8192));System.out.flush();Thread.sleep(60000);continue;}
                if("orphan".equals(args[1])){
                    new ProcessBuilder(command(dir,"helper")).start();
                    long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                    while(!Files.exists(dir.resolve("helper-pid"))&&System.nanoTime()<deadline)Thread.sleep(10);
                    Thread.sleep(200);var result=new LinkedHashMap<String,Object>();result.put("data",List.of(Map.of("id","first")));result.put("nextCursor",null);
                    reply(Map.of("id",message.get("id"),"result",result));return;
                }
                if("refuse".equals(args[1])){reply(Map.of("id",message.get("id"),"error",Map.of("message","credential-never-save")));continue;}
                if(pages++==0)reply(Map.of("id",message.get("id"),"result",Map.of("data",List.of(Map.of("id","first","model","first")),"nextCursor","second-page")));
                else {if(!"second-page".equals(params.get("cursor")))System.exit(9);var result=new LinkedHashMap<String,Object>();result.put("data",List.of(Map.of("id","second","model","second")));result.put("nextCursor",null);reply(Map.of("id",message.get("id"),"result",result));}
            }}
        }
        private static void reply(Map<String,Object> value){System.out.println(Json.write(value));System.out.flush();}
    }

    private static void cliCache(Check check)throws Exception {
        Path home=Files.createTempDirectory("warden-catalog-lifecycle-cli-"),cliHome=Files.createTempDirectory("warden-catalog-source-");
        Files.createDirectories(home.resolve("profiles"));Files.createDirectories(cliHome.resolve(".codex"));
        Path profile=home.resolve("profiles/reader.yaml");Files.writeString(profile,"""
                version: 1
                profile: reader
                role: reviewer
                vendor: openai
                service: openai
                model: gpt-manual
                effort: medium
                command: codex
                runner: direct
                read_only: true
                args: [--model, '{{model}}', --effort, '{{effort}}']
                """
        );
        String original=Files.readString(profile);var probes=new AtomicInteger();
        var checks=new ModelCheck(new ProcessRunner(),ignored->null,(p,u,d,s)->{probes.incrementAndGet();return Map.of("ok",true,"code","pong");});
        var service=new VendorSettingsService(home,home,ignored->null,checks,cliHome,(c,e,p,h)->null);
        Path source=cliHome.resolve(".codex/models_cache.json");
        String old=Instant.now().minus(Duration.ofDays(2)).toString();writeCliCache(source,old);
        service.refresh(Set.of("cli-provider-openai"));var card=card(service,"openai");var catalog=catalog(card);
        check.eq("rereading an old cache does not refresh its observation","stale",catalog.get("freshness"));
        check.eq("old cache has typed stale code","catalog_stale",catalog.get("code"));
        check.eq("upstream date survives cache reload",old,catalog.get("fetched_at"));
        check.that("cache mtime and reload time are separate",catalog.containsKey("cache_modified_at")&&catalog.containsKey("refreshed_at"));
        check.eq("offline cache action says local cache","local_cache",catalog.get("refresh_mode"));
        check.eq("stale catalog cannot call a configured id removed","unknown",model(card,"gpt-manual").get("catalog_status"));
        check.eq("catalog does not invent access","not_checked",model(card,"gpt-cached").get("access_status"));
        writeCliCache(source,null);service.refresh(Set.of("cli-provider-openai"));catalog=catalog(card(service,"openai"));
        check.eq("missing upstream date is unknown","unknown",catalog.get("freshness"));
        check.eq("mtime is not substituted for upstream date",null,catalog.get("fetched_at"));
        writeCliCache(source,Instant.now().plus(Duration.ofDays(1)).toString());service.refresh(Set.of("cli-provider-openai"));
        check.eq("a future cache date is unknown","unknown",catalog(card(service,"openai")).get("freshness"));
        var calls=new AtomicInteger();
        var metadata=new VendorSettingsService(home,home,ignored->null,checks,cliHome,(client,executable,project,root)->{
            calls.incrementAndGet();check.eq("only the known CLI metadata client is requested","codex",client);
            return new VendorSettingsService.CliCatalogResult(List.of(
                    Map.of("id","gpt-metadata-id","model","gpt-next","displayName","Next","supportedReasoningEfforts",List.of(Map.of("reasoningEffort","high")),"private","never-save"),
                    Map.of("id","hidden","hidden",true)),"ignored upstream source",Instant.now().toString(),"current");
        });
        metadata.list();check.eq("viewing catalog never starts CLI metadata",0,calls.get());
        metadata.refresh(Set.of("cli-provider-openai"));card=card(metadata,"openai");catalog=catalog(card);
        check.eq("Codex uses supported CLI metadata operation","cli_metadata",catalog.get("refresh_mode"));
        check.eq("Codex query does not promise upstream cache freshness","unknown",catalog.get("freshness"));
        check.eq("Codex query date is marked as observation","metadata_query",catalog.get("timestamp_source"));
        check.eq("new model appears without selecting it",false,model(card,"gpt-next").get("configured"));
        check.eq("metadata id uses the wire model slug","listed",model(card,"gpt-next").get("catalog_status"));
        check.eq("never listed manual model is distinct from removed","missing",model(card,"gpt-manual").get("catalog_status"));
        String persisted=Files.readString(home.resolve("vendors/cli-provider-openai-catalog.json"));
        check.that("raw CLI metadata is not retained",!persisted.contains("never-save")&&!persisted.contains("gpt-metadata-id")&&!persisted.contains("hidden"));
        writeCliCache(source,Instant.now().toString());
        var failing=new VendorSettingsService(home,home,ignored->null,checks,cliHome,(c,e,p,h)->{throw new IllegalStateException("credential-never-save");});
        failing.refresh(Set.of("cli-provider-openai"));catalog=catalog(card(failing,"openai"));
        check.eq("failed CLI update is typed","catalog_cli_metadata_failed",catalog.get("refresh_code"));
        check.eq("a failed CLI update cannot make cache current","stale",catalog.get("freshness"));
        check.that("failed CLI update offers actual fallback instructions",catalog.get("refresh_instruction").toString().contains("model/list"));
        check.that("CLI failure message cannot leak credentials",!Files.readString(home.resolve("vendors/cli-provider-openai-catalog.json")).contains("credential-never-save"));
        check.eq("all catalog operations use no model ping",0,probes.get());
        check.eq("catalog operations preserve configured profile byte for byte",original,Files.readString(profile));
    }

    private static void apiLifecycle(Check check)throws Exception {
        Path home=Files.createTempDirectory("warden-catalog-lifecycle-api-"),cliHome=Files.createTempDirectory("warden-catalog-lifecycle-offline-");
        Files.createDirectories(home.resolve("profiles"));var pings=new AtomicInteger();
        var checks=new ModelCheck(new ProcessRunner(),ignored->null,(p,u,d,s)->{pings.incrementAndGet();return Map.of("ok",true,"code","pong");});
        try(var endpoint=new Endpoint()){
            new ConnectionStore(home).add(endpoint.connection());
            Path configured=home.resolve("profiles/reader.yaml"),manual=home.resolve("profiles/manual.yaml"),policy=home.resolve("policy.yaml");
            Files.writeString(configured,apiProfile("reader","retired/model"));Files.writeString(manual,apiProfile("manual","manual/model"));
            Files.writeString(policy,"version: 1\nroles:\n  reviewer:\n    profiles: [reader]\n    strategy: first\n");
            String original=Files.readString(configured),originalManual=Files.readString(manual),originalPolicy=Files.readString(policy);
            var service=new VendorSettingsService(home,home,key->"CATALOG_KEY".equals(key)?"catalog-secret":null,checks,cliHome,(c,e,p,h)->null);
            service.refresh(Set.of("api-fixture"));var card=card(service,"custom");
            check.eq("provider metadata uses explicit remote refresh","remote_metadata",catalog(card).get("refresh_mode"));
            check.eq("fresh provider observation is current","current",catalog(card).get("freshness"));
            check.eq("configured listed model retains selection",true,model(card,"retired/model").get("configured"));
            check.eq("catalog membership is not an access receipt","not_checked",model(card,"retired/model").get("access_status"));
            check.eq("manual missing model remains visible","missing",model(card,"manual/model").get("catalog_status"));
            service.check(Set.of("api-fixture"));int checked=pings.get();
            endpoint.models=List.of("new/unknown-model");service.refresh(Set.of("api-fixture"));card=card(service,"custom");
            check.eq("a previously listed configured model is marked removed","removed",model(card,"retired/model").get("catalog_status"));
            check.eq("catalog removal preserves separate successful access evidence","answered",model(card,"retired/model").get("access_status"));
            check.eq("unknown new vendor model is offered without auto-selection",false,model(card,"new/unknown-model").get("configured"));
            check.eq("new vendor model is not implicitly access-checked","not_checked",model(card,"new/unknown-model").get("access_status"));
            check.eq("configured manual id was never listed, not removed","missing",model(card,"manual/model").get("catalog_status"));
            check.eq("refresh does not ping any role",checked,pings.get());
            check.eq("refresh preserves retired model profile",original,Files.readString(configured));
            check.eq("refresh preserves manual model profile",originalManual,Files.readString(manual));
            check.eq("refresh never rewrites selected role models",originalPolicy,Files.readString(policy));
            String observed=(String)catalog(card).get("fetched_at");endpoint.status=503;
            service.refresh(Set.of("api-fixture"));card=card(service,"custom");var catalog=catalog(card);
            check.eq("provider refusal has typed catalog status","catalog_http_failed",catalog.get("code"));
            check.eq("failed refresh marks retained list stale","stale",catalog.get("freshness"));
            check.eq("failed refresh retains observation time",observed,catalog.get("fetched_at"));
            check.eq("failure does not label unknown membership as removal","unknown",model(card,"retired/model").get("catalog_status"));
            check.eq("catalog failure does not invalidate independent access check","answered",model(card,"retired/model").get("access_status"));
            check.eq("three updates issue exactly three metadata GETs",3,endpoint.calls.get());
            check.that("provider failure body is never persisted",!Files.readString(home.resolve("vendors/api-fixture-catalog.json")).contains("catalog-secret"));
            var settings=new ModelSettingsService(home,home,checks);
            var saved=settings.apply("save-profile",Map.of("name","manual","revision",ModelSettingsService.revision(manual),"model","manual/new-model","vendor","custom","connection","fixture"));
            check.eq("manual configuration still saves when catalog is unavailable",200,saved.status());
            check.eq("manual configured model survives reload","manual/new-model",UserConfig.load(home).profiles().get("manual").model());
            check.eq("manual save does not rewrite role roster",originalPolicy,Files.readString(policy));
            check.eq("manual configuration is not blocked by catalog admission",null,EffortCapabilities.problem(UserConfig.load(home).profiles().get("manual")));
        }
    }

    private static void writeCliCache(Path path,String fetched)throws Exception{
        var value=new LinkedHashMap<String,Object>();if(fetched!=null)value.put("fetched_at",fetched);
        value.put("models",List.of(Map.of("slug","gpt-cached","supported_reasoning_levels",List.of(Map.of("effort","medium")))));Files.writeString(path,Json.write(value));
    }
    private static String apiProfile(String name,String model){return "version: 1\nprofile: "+name+"\nrole: reviewer\nrunner: api\nconnection: fixture\nmodel_vendor: custom\nmodel: "+model+"\nread_only: true\nlimits: {max_output_tokens: 512}\n";}
    @SuppressWarnings("unchecked") private static Map<String,Object> card(VendorSettingsService service,String provider)throws Exception{return ((List<Map<String,Object>>)service.list().get("vendors")).stream().filter(v->provider.equals(v.get("provider"))).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") private static Map<String,Object> catalog(Map<String,Object> card){return (Map<String,Object>)card.get("catalog");}
    @SuppressWarnings("unchecked") private static Map<String,Object> model(Map<String,Object> card,String id){return ((List<Map<String,Object>>)card.get("models")).stream().filter(m->id.equals(m.get("id"))).findFirst().orElseThrow();}
    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();volatile int status=200;volatile List<String> models=List.of("retired/model","previous/other");
        Endpoint()throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/models",exchange->{calls.incrementAndGet();
            String response=status==200?Json.write(Map.of("data",models.stream().map(id->Map.of("id",id)).toList())):"{\"error\":\"catalog-secret\"}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();}
        Connection connection(){return new Connection("fixture","custom","openai_chat","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","env:CATALOG_KEY",true);}
        public void close(){server.stop(0);}
    }
}
