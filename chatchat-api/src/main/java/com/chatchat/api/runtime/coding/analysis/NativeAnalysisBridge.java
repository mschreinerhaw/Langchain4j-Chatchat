package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.VisualReportMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Freeze already-authorized Runtime rows in Silo, then delegate the original
 * objective. No Sandbox, generated script, domain formulas or repair strategy. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeAnalysisBridge {
    private final NativeCodingTasks coding;private final UserAssetService assets;private final CodingInvocationContext identity;private final ObjectMapper json;private final CodingAnalysisProperties policy;
    public NativeAnalysisBridge(NativeCodingTasks coding,UserAssetService assets,CodingInvocationContext identity,ObjectMapper json,CodingAnalysisProperties policy){this.coding=coding;this.assets=assets;this.identity=identity;this.json=json;this.policy=policy;}
    public Optional<Map<String,Object>> snapshot(KernelDataScope scope,boolean visual){return identity.as(scope,Set.of(READ),()->coding.snapshot(scope,visual?"primary-data-analysis-visual-v1":"primary-data-analysis-v1"));}
    public Map<String,Object> primary(AnalysisCoverageCoordinator.Request context,Map<String,Dataset> sources,java.util.function.Consumer<Map<String,Object>> observer){
        var owner=context.isolationScope();var scope=new KernelDataScope(owner.tenantId(),owner.userId(),owner.requestId(),owner.conversationId(),owner.runId(),null,Map.of());
        boolean visual=VisualReportMode.analysisRequested(context.runtimeAttributes());
        return execute(context,scope,visual?"primary-data-analysis-visual-v1":"primary-data-analysis-v1",context.query(),CodingAnalysisService.primaryReportSchema(false),sources,observer,visual);
    }
    public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context,KernelDataScope scope,String request,String objective,Map<String,Object> schema,Map<String,Dataset> sources,java.util.function.Consumer<Map<String,Object>> observer,boolean visual){
        if(sources==null||sources.isEmpty())throw new IllegalArgumentException("Authorized data or an explicit supply-status receipt required");
        if(sources.size()>com.chatchat.common.runtime.coding.CodingProtocol.MAX_ANALYSIS_SOURCES)throw new IllegalArgumentException("Authorized source count exceeds Runtime policy");
        for(String part:List.of(scope.tenantId(),scope.userId(),scope.runId()))if(part.isBlank()||Set.of("anonymous","default","unknown-request").contains(part))throw new SecurityException("Authenticated Runtime scope required");
        return identity.as(scope,Set.of(READ,SUBMIT,STOP),()->{
            var files=new ArrayList<String>();long bytes=0,rows=0;int index=0;
            String snapshotTask=NativeCodingTasks.taskId(scope,request);
            for(var source:sources.values()){
                var output=new ByteArrayOutputStream();long offset=0;
                for(;;){
                    if(context.cancellationCheck().getAsBoolean())throw new java.util.concurrent.CancellationException();
                    var page=source.handle().readPage(offset,1024);
                    for(var row:page.rows())try{byte[] data=json.writeValueAsBytes(row);output.write(data);output.write('\n');bytes+=data.length+1;rows++;
                        if(bytes>policy.getMaxInputBytes()||rows>policy.getMaxInputRows())throw new IllegalArgumentException("Authorized Native snapshot exceeds configured input resources");
                    }catch(java.io.IOException invalid){throw new IllegalStateException("Authorized row snapshot encoding failed",invalid);}
                    offset+=page.rows().size();if(!page.hasMore())break;if(page.rows().isEmpty())throw new IllegalStateException("Source paging made no progress");
                }
                index++;byte[] data=output.toByteArray();String name="source-"+index;
                var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,snapshotTask,scope.runId(),"native-runtime-data:"+request+":"+source.reference()+":"+UserAssetService.hash(data),name+".jsonl","application/json",data);files.add(file.id());
                var contextFields=new LinkedHashMap<String,Object>();
                for(String key:List.of("fieldSchema","fieldDefinitions","columns","dataQuality","recordMeaning","sourceDescription","snapshotSemantics","templateParameterBinding","representation","sourceSha256","recordType","sourceVerifiedEmpty"))if(source.analysisContext().containsKey(key))contextFields.put(key,source.analysisContext().get(key));
                var description=new LinkedHashMap<String,Object>();description.put("datasetReference",source.reference());description.put("rowCount",offset);description.put("inputStatus",offset==0?"VERIFIED_EMPTY_SNAPSHOT":"DELIVERED_SNAPSHOT");description.put("format","JSON_LINES");description.put("fileName",name+".jsonl");description.put("sha256",file.sha256());description.put("sourceContext",contextFields);
                try{var meta=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,snapshotTask,scope.runId(),"native-runtime-context:"+file.id(),name+"-context.json","application/json",json.writeValueAsBytes(description));files.add(meta.id());}catch(java.io.IOException invalid){throw new IllegalArgumentException("Source context cannot be frozen",invalid);}
            }
            String instruction=objective;
            if(instruction.length()>16000){byte[] data=instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8);var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,snapshotTask,scope.runId(),"native-original-objective:"+UserAssetService.hash(data),"original-task-objective.txt","text/plain",data);files.add(file.id());instruction="Read original-task-objective.txt from the supplied files for the full original user objective.";}
            instruction+="\nUse the frozen source contexts to interpret the supplied JSONL rows and distinguish missing data from verified empty results. Preserve source references and field names. Choose your own source structure and computation. Provide a complete Markdown report and the requested structured result; never treat transport/status observations as business data.";
            var types=visual?List.of("REPORT","RESULT","CHART"):List.of("REPORT","RESULT");
            String model=Objects.toString(context.metadata().get("modelName"),null);
            var submitted=coding.submit(scope,request,files,instruction,model,types,schema);
            observer.accept(Map.of("eventKind","CODING_TASK_SUBMITTED","taskId",submitted.taskId(),"source","JAVA_NATIVE_S3"));
            var result=coding.await(scope,submitted.taskId(),context.cancellationCheck());
            if(result.get("datasetReference") instanceof String ref&&result.get("results") instanceof List<?> raw){
                @SuppressWarnings("unchecked") var values=(List<Map<String,Object>>)raw;
                sources.put(ref,new Dataset(ref,Map.of("taskId",submitted.taskId(),"validationScope","STRUCTURAL_AND_OBJECT_INTEGRITY","businessEvaluated",false),values));
            }
            return result;
        });
    }
}
