package dev.warden;

import dev.warden.dashboard.ModelSettingsUi;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Executes the panel's catalog selectors with a small DOM, without a browser or inference. */
public final class ModelCatalogUiTest implements Suite {
    public String name(){return "model-catalog-ui";}
    public void run(Check check)throws Exception {
        String html=ModelSettingsUi.html();
        check.contains("panel exposes explicit classifier compatibility action",html,"verify-classifier");
        check.contains("compatibility confirmation includes the exact saved pin",html,"reported_model:p.reported_model,confirm:true");
        check.contains("classifier edits use optimistic concurrency",html,"revision:modelFormRevision");
        check.that("catalog repaint never normalizes saved effort",!html.contains("slot.effort=levels.includes('medium')"));
        var runner=new ProcessRunner();
        try {
            if(!runner.run(List.of("node","--version"),Path.of("."),Duration.ofSeconds(5)).ok()){
                System.out.println("skip  model-catalog-ui DOM execution: Node unavailable");return;
            }
        }catch(IOException missing){System.out.println("skip  model-catalog-ui DOM execution: Node unavailable");return;}
        String functions=between(html,"/*catalog:start*/","/*catalog:end*/")
                +between(html,"function select(","function label(",true)
                +between(html,"function newModelOptions(","el('model-add')",true);
        String script="""
                'use strict';
                const assert=require('node:assert/strict');
                function node(tag,text){const n={tag,textContent:text,children:[],parent:null,selected:'',hidden:false,
                    setAttribute(){},scrollIntoView(){},append(child){if(child.parent)child.parent.children.splice(child.parent.children.indexOf(child),1);child.parent=this;this.children.push(child)},
                    replaceChildren(...children){for(const child of this.children)child.parent=null;this.children=[];for(const child of children)this.append(child)}};
                  Object.defineProperty(n,'childNodes',{get(){return this.children}});
                  Object.defineProperty(n,'value',{get(){return this.tag==='select'?(this.children.some(c=>c.value===this.selected)?this.selected:this.children[0]?.value||''):this.selected},set(v){this.selected=String(v??'')}});return n}
                const elements=new Map();
                const el=id=>{if(!elements.has(id)){const select=['model-kind','model-connection','model-id-select'].includes(id);const n=node(select?'select':'input');if(id==='model-kind')for(const kind of ['reviewer','implementer','classifier']){const o=node('option',kind);o.value=kind;n.append(o)}elements.set(id,n)}return elements.get(id)};
                let state={vendors:[],profiles:[]},connections=[{id:'router',provider:'openrouter'}],modelFormRevision;
                """+functions+"""
                const old={id:'old/model',origin:'configured',catalog_status:'removed',configured:true};
                const source={kind:'cli',catalog:{refresh_mode:'local_cache',freshness:'stale'},models:[{id:'new/model',label:'Newest',catalog_status:'listed'},old]};
                const before=JSON.stringify(source);const presentation=catalogPresentation(source);
                const oldChoice=select(catalogModelOptions(source.models,'old/model',true),'old/model','model');
                assert.equal(oldChoice.value,'old/model');assert.equal(JSON.stringify(source),before);
                const manual=select(catalogModelOptions([{id:'new/model'}],'manual/private-model'),'manual/private-model','model');
                assert.equal(manual.value,'manual/private-model');
                const freshApi=catalogPresentation({kind:'api',catalog:{refresh_mode:'remote_metadata',freshness:'current'}});
                const cliMetadata=catalogPresentation({kind:'cli',catalog:{refresh_mode:'cli_metadata',freshness:'current'}});
                const observedMetadata=catalogPresentation({kind:'cli',catalog:{refresh_mode:'cli_metadata',freshness:'unknown',timestamp_source:'metadata_query'}});
                const unknown=catalogPresentation({kind:'cli',catalog:{}});
                const cachedModel={id:'cached/model',origin:'catalog',catalog_status:'unknown',configured:false};
                state={vendors:[{id:'api-router',kind:'api',profiles:[],models:[{id:'typesafe/jev-2.0',catalog_status:'listed'}]}],profiles:[]};
                openModelForm({kind:'classifier',name:'jev-existing',revision:'saved-revision',connection:'router',model:'typesafe/jev-1.14',reported_model:'typesafe/jev-1.14-20261014'});
                assert.equal(el('model-id-select').value,'typesafe/jev-1.14');
                assert.equal(el('model-connection').value,'router');
                console.log(JSON.stringify({localLabel:presentation.refreshLabel,localInstruction:presentation.instruction,staleLabel:presentation.stateLabel,
                  apiLabel:freshApi.refreshLabel,cliLabel:cliMetadata.refreshLabel,unknown:unknown.freshness,oldChoice:oldChoice.value,
                  observationLabel:observedMetadata.timestampLabel,cacheLabel:catalogModelLabel(cachedModel),cacheNotice:catalogModelNotice({models:[cachedModel]},cachedModel.id),
                  removed:catalogModelNotice(source,'old/model'),missing:catalogModelNotice({models:[{id:'not-listed',catalog_status:'missing'}]},'not-listed'),
                  manual:manual.value,reported:el('model-reported').value,revision:modelFormRevision,editingLocked:el('model-name').readOnly&&el('model-kind').disabled,
                  classifierModel:el('model-id-select').value}));
                """;
        Path scratch=Files.createTempDirectory("warden-catalog-ui-");Path file=scratch.resolve("catalog-ui.cjs");
        try {
            Files.writeString(file,script);var result=runner.run(List.of("node",file.toAbsolutePath().toString()),scratch,Duration.ofSeconds(10));
            check.that("actual selector functions execute without mutation",result.ok());
            if(!result.ok())throw new IllegalStateException(result.stderr()+result.stdout());
            var data=Json.parseObject(result.stdout());
            check.eq("local reload is not labelled remote refresh","Перечитать кеш",data.get("localLabel"));
            check.contains("local cache instruction does not promise latest data",String.valueOf(data.get("localInstruction")),"локальный файл");
            check.eq("stale source is visible","Список устарел",data.get("staleLabel"));
            check.eq("API refresh describes metadata source","Обновить каталог",data.get("apiLabel"));
            check.eq("CLI metadata is distinguished from cache reload","Получить список через CLI",data.get("cliLabel"));
            check.eq("missing freshness stays unknown","unknown",data.get("unknown"));
            check.eq("CLI observation time is not called upstream date","Запрос списка к CLI",data.get("observationLabel"));
            check.contains("undated cached model is not called manually saved",String.valueOf(data.get("cacheLabel")),"актуальность не подтверждена");
            check.contains("undated cached model notice explains its origin",String.valueOf(data.get("cacheNotice")),"есть в кеше");
            check.eq("new catalog never replaces selected removed model","old/model",data.get("oldChoice"));
            check.contains("removed model retains its configuration",String.valueOf(data.get("removed")),"Настройка сохранена");
            check.contains("catalog absence is not access failure",String.valueOf(data.get("missing")),"не подтверждает отказ доступа");
            check.eq("manual selection stays selectable","manual/private-model",data.get("manual"));
            check.eq("classifier edit preserves exact expected snapshot","typesafe/jev-1.14-20261014",data.get("reported"));
            check.eq("classifier edit preserves prior revision","saved-revision",data.get("revision"));
            check.eq("editing cannot rename the saved classifier or change kind",true,data.get("editingLocked"));
            check.eq("newest catalog model does not replace edited classifier","typesafe/jev-1.14",data.get("classifierModel"));
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(scratch);}
    }
    private static String between(String source,String start,String end){return between(source,start,end,false);}
    private static String between(String source,String start,String end,boolean includeStart){int a=source.indexOf(start);int b=source.indexOf(end,a+start.length());if(a<0||b<0)throw new IllegalStateException("missing UI extraction markers");return source.substring(includeStart?a:a+start.length(),b);}
}
