package dev.warden.execution;

import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded metadata-only CLI operations. No thread/start, turn/start, prompt or role command. */
final class CliModelCatalog {
    static final int MAX_FRAME_BYTES=1024*1024,MAX_OUTPUT_BYTES=4*1024*1024;
    static final class ProtocolFailure extends IOException {
        private final String code;
        ProtocolFailure(String code){super(code);this.code=code;}
        String code(){return code;}
    }
    private CliModelCatalog() {}
    static VendorSettingsService.CliCatalogResult query(String client,String executable,Path project,Path cliHome)throws Exception {
        // A synthetic CLI home is an offline source unless the caller injects its own query.
        if(!cliHome.toAbsolutePath().normalize().equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()))return null;
        String command=DirectCliExecutor.resolveExecutable(executable,project);
        if("grok".equals(client)) {
            var answer=new ProcessRunner().run(List.of(command,"models"),project,Duration.ofSeconds(20),4096);
            if(!answer.ok())throw new IOException("CLI metadata request failed");
            return new VendorSettingsService.CliCatalogResult(List.of(),"Grok CLI models metadata",null,"current");
        }
        if(!"codex".equals(client))return null;
        return codex(List.of(command,"app-server","--stdio"),project,Duration.ofSeconds(20));
    }
    static VendorSettingsService.CliCatalogResult codex(List<String> command,Path project,Duration timeout)throws Exception {
        Process child=new ProcessBuilder(command).directory(project.toFile()).start();
        var responses=new ArrayBlockingQueue<Map<String,Object>>(64);
        var readFailure=new AtomicReference<String>();
        var descendants=new ConcurrentHashMap<Long,ProcessHandle>();var observing=new AtomicBoolean(true);
        var observer=Thread.ofVirtual().start(()->{
            while(observing.get()){
                observe(child,descendants);
                try{Thread.sleep(25);}catch(InterruptedException stopped){break;}
            }
        });
        var reader=Thread.ofVirtual().start(()->{
            try(var input=child.getInputStream()){readResponses(input,responses);}
            catch(ProtocolFailure failure){readFailure.compareAndSet(null,failure.code());}
            catch(IOException failure){readFailure.compareAndSet(null,"catalog_stream_failed");}
        });
        var errors=Thread.ofVirtual().start(()->{try(var input=child.getErrorStream()){input.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}});
        long deadline=System.nanoTime()+timeout.toNanos();
        try(var writer=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8))) {
            send(writer,Map.of("id",1,"method","initialize","params",Map.of("clientInfo",Map.of("name","warden_catalog","version","1"))));
            await(responses,readFailure,1,deadline);
            send(writer,Map.of("method","initialized","params",Map.of()));
            var models=new ArrayList<Map<String,Object>>();var cursors=new HashSet<String>();String cursor=null;
            for(int page=0;page<10;page++){
                int id=page+2;var params=new LinkedHashMap<String,Object>();params.put("limit",200);params.put("includeHidden",false);if(cursor!=null)params.put("cursor",cursor);
                send(writer,Map.of("id",id,"method","model/list","params",params));
                var result=await(responses,readFailure,id,deadline);
                if(!(result.get("data") instanceof List<?> rows))throw new IOException("catalog protocol");
                for(Object value:rows)if(value instanceof Map<?,?> row){
                    @SuppressWarnings("unchecked") var typed=(Map<String,Object>)row;
                    models.add(typed);if(models.size()>2000)throw new IOException("catalog limit");
                }
                Object next=result.get("nextCursor");
                if(next==null)return new VendorSettingsService.CliCatalogResult(models,"Codex CLI model/list",Instant.now().toString(),"unknown");
                if(!(next instanceof String text)||text.length()>2048||!cursors.add(text))throw new IOException("catalog cursor");
                cursor=text;
            }
            throw new IOException("catalog page limit");
        } catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        finally {
            // Only this private metadata process and its known children are touched.
            boolean interrupted=Thread.interrupted();boolean settled=false;
            try {
                try{child.getOutputStream().close();}catch(IOException ignored){}
                if(!(settled=awaitTree(child,descendants,Duration.ofSeconds(2)))){
                    descendants.values().forEach(ProcessHandle::destroy);child.destroy();
                    if(!(settled=awaitTree(child,descendants,Duration.ofSeconds(1)))){
                        descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();
                        settled=awaitTree(child,descendants,Duration.ofSeconds(2));
                    }
                }
            }catch(InterruptedException cancelled){interrupted=true;}
            finally {
                observe(child,descendants);descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if(child.isAlive())child.destroyForcibly();
                try{settled=awaitTree(child,descendants,Duration.ofSeconds(2));reader.join(1000);errors.join(1000);}catch(InterruptedException cancelled){interrupted=true;}
                observing.set(false);observer.interrupt();
                try{observer.join(1000);}catch(InterruptedException cancelled){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            if(!settled)throw new ProtocolFailure("catalog_cleanup_failed");
        }
    }
    private static void observe(Process child,ConcurrentMap<Long,ProcessHandle> descendants){
        try(var handles=child.descendants()){handles.forEach(handle->descendants.putIfAbsent(handle.pid(),handle));}
        for(var parent:descendants.values())if(parent.isAlive())try(var handles=parent.descendants()){handles.forEach(handle->descendants.putIfAbsent(handle.pid(),handle));}
    }
    private static boolean awaitTree(Process child,ConcurrentMap<Long,ProcessHandle> descendants,Duration grace)throws InterruptedException{
        long deadline=System.nanoTime()+grace.toNanos();
        while(child.isAlive()||descendants.values().stream().anyMatch(ProcessHandle::isAlive)){
            observe(child,descendants);if(System.nanoTime()>=deadline)return false;Thread.sleep(25);
        }
        return true;
    }
    /** Byte limits apply before growing a frame or decoding/parsing any JSON. */
    private static void readResponses(InputStream input,BlockingQueue<Map<String,Object>> responses)throws IOException{
        var frame=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int total=0,read;
        while((read=input.read(chunk))!=-1){
            if(read>MAX_OUTPUT_BYTES-total)throw new ProtocolFailure("catalog_output_limit");total+=read;
            for(int index=0;index<read;index++){
                int value=chunk[index]&255;
                if(value=='\n'){
                    if(frame.size()==0)continue;
                    String line=frame.toString(StandardCharsets.UTF_8);frame.reset();Map<String,Object> message;
                    try{message=Json.parseObject(line);}catch(Exception invalid){throw new ProtocolFailure("catalog_frame_invalid");}
                    if(message.containsKey("id")&&!responses.offer(message))throw new ProtocolFailure("catalog_response_backlog");
                }else{
                    if(frame.size()>=MAX_FRAME_BYTES)throw new ProtocolFailure("catalog_frame_limit");frame.write(value);
                }
            }
        }
        throw new ProtocolFailure(frame.size()==0?"catalog_stream_closed":"catalog_response_incomplete");
    }
    private static void send(BufferedWriter writer,Map<String,Object> message)throws IOException{writer.write(Json.write(message));writer.newLine();writer.flush();}
    private static Map<String,Object> await(BlockingQueue<Map<String,Object>> responses,AtomicReference<String> readFailure,int id,long deadline)throws Exception{
        while(System.nanoTime()<deadline){
            var answer=responses.poll();
            if(answer==null){if(readFailure.get()!=null)throw new ProtocolFailure(readFailure.get());answer=responses.poll(Math.max(1,Math.min(deadline-System.nanoTime(),TimeUnit.MILLISECONDS.toNanos(50))),TimeUnit.NANOSECONDS);}
            if(answer==null)continue;
            if(!(answer.get("id") instanceof Number number)||number.intValue()!=id)continue;
            if(answer.containsKey("error")||!(answer.get("result") instanceof Map<?,?> result))throw new IOException("catalog request refused");
            @SuppressWarnings("unchecked") var typed=(Map<String,Object>)result;return typed;
        }
        throw new IOException("catalog deadline");
    }
}
