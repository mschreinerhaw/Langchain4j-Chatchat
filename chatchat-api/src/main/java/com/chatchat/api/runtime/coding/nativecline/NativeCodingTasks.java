package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.evidence.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Authenticated Runtime entry, used by HTTP and existing analysis services. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeCodingTasks {
    private final NativeSiloTaskService capture;
    private final NativeS3CodingAdapter adapter;
    private final NativeS3Settings settings;
    private final CodingInvocationContext identity;
    private final UserAssetService assets;
    private final CodingEvidenceCollector collector;
    public NativeCodingTasks(NativeSiloTaskService capture,NativeS3CodingAdapter adapter,NativeS3Settings settings,CodingInvocationContext identity,UserAssetService assets,CodingEvidenceCollector collector){
        this.capture=capture;this.adapter=adapter;this.settings=settings;this.identity=identity;this.assets=assets;this.collector=collector;
    }
    public record Submitted(String taskId,String runId,CodingTaskOutcome outcome){}
    public static String taskId(KernelDataScope scope,String requestId){return "native-"+UserAssetService.hash((scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+requestId).getBytes(StandardCharsets.UTF_8));}
    public Submitted submit(KernelDataScope scope,String requestId,List<String> files,String instruction,String model,List<String> artifactTypes,Map<String,Object> schema){
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.SUBMIT);
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.READ);
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,128}")||instruction==null||instruction.isBlank()||instruction.length()>24000)throw new IllegalArgumentException("Explicit request identity and objective required");
        String selected=model==null||model.isBlank()?settings.defaultModel:model;settings.profile(selected);
        if(schema==null)schema=Map.of();if(!schema.isEmpty())new CodingSchemaResultVerifier().validateRules(Map.of("rowSchema",schema));
        String task=taskId(scope,requestId);
        var original=files==null?List.<String>of():List.copyOf(files);
        if(original.isEmpty()){
            var metadata=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,task,scope.runId(),"native-empty-input:"+task,"authorized-input-scope.json","application/json","{\"inputStatus\":\"NO_FILES_SUPPLIED\",\"sources\":[]}".getBytes(StandardCharsets.UTF_8));
            original=List.of(metadata.id());
        }
        var budget=new CodingTaskContract.Budget("native-budget:"+task,Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,3L*1024*1024*1024,64L*1024*1024);
        var contract=capture.capture(scope,task,original,instruction,selected,budget,Map.of("profile","artifact-integrity-v1"),artifactTypes==null||artifactTypes.isEmpty()?List.of("RESULT"):List.copyOf(artifactTypes),schema);
        return new Submitted(task,scope.runId(),adapter.submit(contract));
    }
    public CodingTaskOutcome get(KernelDataScope scope,String task){return adapter.get(scope,task);}
    public CodingTaskOutcome cancel(KernelDataScope scope,String task){return adapter.cancel(scope,task,"Authenticated caller cancelled Native task");}
    public CodingTaskOutcome resume(KernelDataScope scope,String task){return adapter.resume(scope,task);}
    public Optional<Map<String,Object>> snapshot(KernelDataScope scope,String requestId){
        String task=taskId(scope,requestId);
        try{return Optional.of(receipt(scope,task,adapter.get(scope,task)));}
        catch(CodingContractException absent){if(absent.error().code()==CodingErrorCode.NOT_FOUND)return Optional.empty();throw absent;}
    }
    private Map<String,Object> receipt(KernelDataScope scope,String task,CodingTaskOutcome outcome){
        var result=new LinkedHashMap<String,Object>();result.put("operation","RUN_CODING");result.put("taskId",task);result.put("runId",scope.runId());result.put("status",outcome.status().name());result.put("terminal",outcome.status().terminal());result.put("validationStatus",outcome.validationStatus().name());result.put("businessValidationPassed",false);
        result.put("resultAvailable",outcome.resultDatasetRef()!=null);result.put("summary",outcome.summary());result.put("eventCursor",outcome.lastEventSequence());result.put("taskRevision",outcome.revision());result.put("cancellationRequested",outcome.cancellationRequested());result.put("reconciliationRequired",outcome.executionControl().reconciliationRequired());
        result.put("artifacts",assets.list(UserAssetService.Owner.of(scope),null,task,100).stream().filter(f->Set.of("CODE","ARTIFACT").contains(f.kind())&&scope.runId().equals(f.runId())).toList());
        if(outcome.resultDatasetRef()!=null){var dataset=collector.registeredResult(scope,task,outcome.resultDatasetRef());result.put("datasetReference",dataset.reference());result.put("results",dataset.handle().readPage(0,100).rows());}
        return result;
    }
    public Map<String,Object> await(KernelDataScope scope,String task,BooleanSupplier cancelled){
        for(;;){
            var outcome=adapter.get(scope,task);
            if(outcome.status().terminal()){
                return receipt(scope,task,outcome);
            }
            if(cancelled.getAsBoolean()){adapter.cancel(scope,task,"Parent task cancelled");throw new java.util.concurrent.CancellationException("Parent task cancelled; Native stop reconciliation continues");}
            try{Thread.sleep(500);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("Native task remains durably owned; request was interrupted",stopped);}
        }
    }
}
