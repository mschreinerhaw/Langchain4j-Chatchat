package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.evidence.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Authenticated Runtime entry, used by HTTP and existing analysis services. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeCodingTasks {
    @org.springframework.beans.factory.annotation.Autowired private NativeVisualReport visualReports;
    @org.springframework.beans.factory.annotation.Autowired private NativeBasicReport basicReports;
    @org.springframework.beans.factory.annotation.Autowired private com.chatchat.api.runtime.coding.data.NativePublishedTemplate publishedTemplates;
    private final NativeSiloTaskService capture;
    private final NativeS3CodingAdapter adapter;
    private final NativeS3Settings settings;
    private final CodingInvocationContext identity;
    private final UserAssetService assets;
    private final CodingEvidenceCollector collector;
    private final com.chatchat.agents.runtime.coding.DurableCodingTaskService tasks;
    public NativeCodingTasks(NativeSiloTaskService capture,NativeS3CodingAdapter adapter,NativeS3Settings settings,CodingInvocationContext identity,UserAssetService assets,CodingEvidenceCollector collector,com.chatchat.agents.runtime.coding.DurableCodingTaskService tasks){
        this.capture=capture;this.adapter=adapter;this.settings=settings;this.identity=identity;this.assets=assets;this.collector=collector;this.tasks=tasks;
    }
    public record Submitted(String taskId,String runId,CodingTaskOutcome outcome){}
    public static String taskId(KernelDataScope scope,String requestId){return "native-"+UserAssetService.hash((scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+requestId).getBytes(StandardCharsets.UTF_8));}
    public String taskIdentity(KernelDataScope scope,String requestId){String legacy=taskId(scope,requestId);return capture.taskIdentity(scope,legacy,adapter.temporalScheduling()?"temporal-"+legacy:legacy);}
    public Submitted submit(KernelDataScope scope,String requestId,List<String> files,String instruction,String model,List<String> artifactTypes,Map<String,Object> schema){
        return submit(scope,requestId,files,instruction,model,artifactTypes,schema,Map.of());
    }
    public Submitted submit(KernelDataScope scope,String requestId,List<String> files,String instruction,String model,List<String> artifactTypes,Map<String,Object> schema,Map<String,Object> capabilities){
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.SUBMIT);
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.READ);
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid Native request ID");
        if(instruction==null||instruction.isBlank())throw new IllegalArgumentException("Native task objective is required");
        if(instruction.length()>24000)throw new IllegalArgumentException("Native task objective exceeds 24000 UTF-16 code units");
        String selected=model==null||model.isBlank()?settings.defaultModel():model;settings.profile(selected);
        if(schema==null)schema=Map.of();if(!schema.isEmpty())new CodingSchemaResultVerifier().validateRules(Map.of("rowSchema",schema));
        String task=taskIdentity(scope,requestId);
        var original=files==null?List.<String>of():List.copyOf(files);
        if(original.isEmpty()){
            var metadata=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,task,scope.runId(),"native-empty-input:"+task,"authorized-input-scope.json","application/json","{\"inputStatus\":\"NO_FILES_SUPPLIED\",\"sources\":[]}".getBytes(StandardCharsets.UTF_8));
            original=List.of(metadata.id());
        }
        var deliverables=artifactTypes==null||artifactTypes.isEmpty()?List.of("RESULT"):List.copyOf(artifactTypes);
        var runtimeContext=new LinkedHashMap<>(capabilities);
        runtimeContext.putIfAbsent(CodingRuntimeContext.NATIVE_TASK_KIND,"GENERIC");
        boolean analysis=CodingRuntimeContext.isNativeAnalysis(runtimeContext);
        if(analysis){
            runtimeContext.put(CodingRuntimeContext.DELIVERY_RULES,NativeDeliveryPolicy.freeze(deliverables,runtimeContext));
            runtimeContext.computeIfAbsent("analysisReportLanguage",
                key->NativeReportLanguage.resolve(instruction,Map.of(),Map.of()));
        }
        var budget=settings.analysisBudget(task,analysis);
        var contract=capture.capture(scope,task,original,instruction,selected,budget,Map.of("profile","artifact-integrity-v1"),deliverables,schema,runtimeContext);
        return new Submitted(task,scope.runId(),adapter.submit(contract));
    }
    /** Studio supplies only server-validated snapshots and its existing budget, never execution authority. */
    public CodingTaskOutcome submitStudio(KernelDataScope scope,String task,String context,String model,
            CodingTaskContract.Budget budget,Map<String,Object> studio,Map<String,Object> capabilities) {
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.SUBMIT);
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.READ);
        String selected=model==null||model.isBlank()?settings.defaultModel():model;
        settings.profile(selected);
        var input=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.CONTEXT,task,scope.runId(),
            "studio-source-context:"+task,"studio-context.txt","text/plain",context.getBytes(StandardCharsets.UTF_8));
        String dialect=studio.get("dialect").toString();
        String profile="python".equals(dialect)?"python":"sql";
        String objective="Use the authorized Studio context in input/data/0/studio-context.txt to propose complete "
            +dialect+" source files. Preserve code outside the requested changes. "
            +"Deliver exactly one RESULT, output/studio-proposal.json, using "+profile
            +"-studio.output.v1 with files [{path,content}] and notes. Never execute the proposed code or access a database directly."
            +(studio.get("schema") instanceof Map<?,?> schema&&schema.containsKey("discoveryId")
                ?" Use Runtime workspace_metadata and workspace_validate to verify authorized physical fields and business definitions; do not invent evidence.":"");
        var contract=capture.capture(scope,task,List.of(input.id()),objective,selected,budget,
            Map.of("profile","artifact-integrity-v1"),List.of("RESULT"),Map.of(),capabilities,studio);
        return adapter.submit(contract);
    }

    public CodingTaskOutcome get(KernelDataScope scope,String task){return adapter.get(scope,task);}
    public Map<String,Object> delivery(KernelDataScope scope,String task){return receipt(scope,task,adapter.get(scope,task));}
    public CodingTaskOutcome cancel(KernelDataScope scope,String task){return adapter.cancel(scope,task,"Authenticated caller cancelled Native task");}
    public CodingTaskOutcome resume(KernelDataScope scope,String task){return adapter.resume(scope,task);}
    public Optional<Map<String,Object>> snapshot(KernelDataScope scope,String requestId){
        String task=taskIdentity(scope,requestId);
        try{return Optional.of(receipt(scope,task,adapter.get(scope,task)));}
        catch(CodingContractException absent){if(absent.error().code()==CodingErrorCode.NOT_FOUND)return Optional.empty();throw absent;}
    }
    private Map<String,Object> receipt(KernelDataScope scope,String task,CodingTaskOutcome outcome){
        var result=new LinkedHashMap<String,Object>();result.put("operation","RUN_CODING");result.put("taskId",task);result.put("runId",scope.runId());result.put("status",outcome.status().name());result.put("terminal",outcome.status().terminal());result.put("validationStatus",outcome.validationStatus().name());result.put("businessValidationPassed",false);
        if(outcome.reasonCode()!=null)result.put("reasonCode",outcome.reasonCode().name());
        result.put("resultAvailable",outcome.resultDatasetRef()!=null);result.put("summary",outcome.summary());result.put("eventCursor",outcome.lastEventSequence());result.put("taskRevision",outcome.revision());result.put("cancellationRequested",outcome.cancellationRequested());result.put("reconciliationRequired",outcome.executionControl().reconciliationRequired());
        result.put("analysisStartedAt",outcome.startedAt()==null?outcome.createdAt().toString():outcome.startedAt().toString());if(outcome.endedAt()!=null)result.put("analysisFinishedAt",outcome.endedAt().toString());result.put("usage",outcome.usage());result.put("stageSnapshotChecked",outcome.status().terminal());
        try{result.put("analysisEvents",tasks.events(scope,task,Math.max(0,outcome.lastEventSequence()-256),256).stream().map(NativeCodingProgress::eventView).toList());result.put("progressHistoryStatus",outcome.lastEventSequence()>256?"RECENT_WINDOW":"AVAILABLE");}
        catch(CodingContractException compacted){if(compacted.error().code()!=CodingErrorCode.EVENT_CURSOR_EXPIRED)throw compacted;result.put("analysisEvents",List.of());result.put("progressHistoryStatus","COMPACTED");}
        var files=new ArrayList<UserAssetService.File>();
        for(var kind:List.of(UserAssetService.Kind.CODE,UserAssetService.Kind.ARTIFACT))files.addAll(assets.list(UserAssetService.Owner.of(scope),kind,task,100).stream().filter(f->scope.runId().equals(f.runId())).toList());
        result.put("artifacts",files);
        if(basicReports!=null&&NativeBasicReport.eligible(outcome)&&adapter.permitsBasicReport(scope,task))
            basicReports.read(tasks.contract(scope,task),outcome).ifPresent(report->applyBasicReport(result,report));
        if(outcome.resultDatasetRef()==null&&canExposeSupplyEvidence(outcome)&&adapter.activeIdentity(scope)){
            capture.supplyInventory(tasks.contract(scope,task)).ifPresent(snapshot->{
                UserAssetService.File file;
                try{file=assets.get(UserAssetService.Owner.of(scope),snapshot.originalAssetId());}
                catch(org.springframework.web.server.ResponseStatusException unavailable){
                    if(unavailable.getStatusCode().value()==404)return;
                    throw unavailable;
                }
                if("AVAILABLE".equals(file.state())&&"DATASET".equals(file.kind())
                        &&task.equals(file.taskId())&&scope.runId().equals(file.runId())
                        &&("native-supply-inventory:"+file.sha256()).equals(file.sourceRef())
                        &&snapshot.sha256().equals(file.sha256())){
                    result.put("dataSupplyEvidence",Map.of("level","INPUT_SNAPSHOT_EVIDENCE",
                        "assetId",file.id(),"sha256",file.sha256(),"sizeBytes",file.size(),
                        "snapshotPersistedAt",Instant.ofEpochMilli(file.createdAt()).toString()));
                }
            });
        }
        if (publishedTemplates != null) {
            var metrics = publishedTemplates.supplementMetrics(tasks.contract(scope, task));
            if (!metrics.isEmpty()) result.put("supplementMetrics", metrics);
        }
        if(outcome.resultDatasetRef()!=null){var dataset=collector.registeredResult(scope,task,outcome.resultDatasetRef());result.put("datasetReference",dataset.reference());result.put("results",dataset.handle().readPage(0,100).rows());}
        if(outcome.status()==CodingTaskOutcome.Status.COMPLETED&&visualReports!=null){
            var rows=result.get("results") instanceof List<?> values?values:List.of();
            try{
                visualReports.project(scope,task,tasks.contract(scope,task).runtimeContext(),rows,files)
                    .ifPresent(report->result.put("visualReport",report));
            }catch(RuntimeException unavailable){
                org.slf4j.LoggerFactory.getLogger(NativeCodingTasks.class).warn(
                    "Native report presentation unavailable taskId={} errorType={}",task,unavailable.getClass().getSimpleName());
                result.put("presentationStatus","TEXT_FALLBACK");
            }
        }
        return result;
    }
    private static void applyBasicReport(Map<String,Object> result,NativeBasicReport.Delivered report){
        result.put("resultAvailable",true);
        result.put("resultLevel",NativeBasicReport.LEVEL);
        result.put("reportOrigin",NativeBasicReport.LEVEL);
        result.put("dataSupplyStatus","AUTHORIZED_SNAPSHOTS_VERIFIED");
        result.put("deterministicComputationStatus","BOUNDED_FIELD_STATISTICS");
        result.put("intelligentAnalysisStatus",result.get("status"));
        result.put("reportDeliveryStatus","PASSED");
        result.put("businessCompletionStatus","PARTIAL");
        result.put("basicReportReceipt",report.proof());
        result.put("datasetReference","runtime-basic-report:"+report.files().get(1).sha256());
        result.put("results",List.of(report.row()));
        var files=new LinkedHashMap<String,UserAssetService.File>();
        if(result.get("artifacts") instanceof List<?> previous)
            for(var item:previous)if(item instanceof UserAssetService.File file)files.put(file.id(),file);
        for(var file:report.files())files.put(file.id(),file);
        result.put("artifacts",List.copyOf(files.values()));
    }
    static boolean canExposeSupplyEvidence(CodingTaskOutcome outcome){
        return outcome.status().terminal()&&!outcome.cancellationRequested()
            &&!outcome.executionControl().reconciliationRequired()
            &&outcome.reasonCode()!=null&&Set.of(CodingErrorCode.EXECUTION_FAILED,
                CodingErrorCode.OUTPUT_CONTRACT_VIOLATION,CodingErrorCode.BUDGET_EXCEEDED,
                CodingErrorCode.DEADLINE_EXCEEDED).contains(outcome.reasonCode());
    }
    public Map<String,Object> await(KernelDataScope scope,String task,BooleanSupplier cancelled){
        return await(scope,task,cancelled,null);
    }
    public Map<String,Object> await(KernelDataScope scope,String task,BooleanSupplier cancelled,java.util.function.Consumer<Map<String,Object>> observer){
        return await(scope,task,cancelled,observer,false);
    }
    public Map<String,Object> awaitWithBasicReport(KernelDataScope scope,String task,BooleanSupplier cancelled,
            java.util.function.Consumer<Map<String,Object>> observer){
        identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.SUBMIT);
        return await(scope,task,cancelled,observer,true);
    }
    private Map<String,Object> await(KernelDataScope scope,String task,BooleanSupplier cancelled,
            java.util.function.Consumer<Map<String,Object>> observer,boolean allowBasicReport){
        var budget=tasks.contract(scope,task).budget();
        long cursor=0;
        for(;;){
            var outcome=adapter.get(scope,task);
            if(observer!=null){
                try{for(int page=0;page<4;page++){
                    var events=tasks.events(scope,task,cursor,128);for(var event:events){observer.accept(NativeCodingProgress.trace(event));cursor=event.sequence();}if(events.size()<128)break;
                }}catch(CodingContractException compacted){if(compacted.error().code()!=CodingErrorCode.EVENT_CURSOR_EXPIRED)throw compacted;observer.accept(Map.of("eventKind","CODING_PROGRESS_HISTORY_COMPACTED","taskId",task,"eventCursor",outcome.lastEventSequence()));cursor=Math.max(cursor,outcome.lastEventSequence()-1);}
                if(outcome.status().terminal()&&cursor<outcome.lastEventSequence())continue;
            }
            if(outcome.status().terminal()){
                if(allowBasicReport&&basicReports!=null&&NativeBasicReport.eligible(outcome)
                        &&!cancelled.getAsBoolean()&&adapter.permitsBasicReport(scope,task)){
                    basicReports.publish(tasks.contract(scope,task),outcome,
                        ()->!cancelled.getAsBoolean()&&adapter.permitsBasicReport(scope,task)).ifPresent(report->{
                            if(observer!=null)observer.accept(Map.of("eventKind","RUNTIME_BASIC_REPORT_VERIFIED",
                                "taskId",task,"reportOrigin",NativeBasicReport.LEVEL,
                                "artifactCount",report.files().size(),"reasonCode",outcome.reasonCode().name()));
                        });
                }
                return receipt(scope,task,outcome);
            }
            if(cancelled.getAsBoolean()){adapter.cancel(scope,task,"Parent task cancelled");throw new java.util.concurrent.CancellationException("Parent task cancelled; Native stop reconciliation continues");}
            long remaining=budget.hasDeadline()?Duration.between(Instant.now(),budget.deadlineAt()).toMillis():500;
            if(remaining<=0){
                // Deadline is a stop request. A report must wait for authoritative reconciliation.
                if(allowBasicReport&&remaining>-Duration.ofSeconds(35).toMillis()){
                    try{Thread.sleep(500);}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("Native stop reconciliation continues",stopped);}
                    continue;
                }
                var pending=receipt(scope,task,outcome);
                pending.put("waitDeadlineReached",true);
                return pending;
            }
            try{Thread.sleep(Math.min(500,remaining));}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("Native task remains durably owned; request was interrupted",stopped);}
        }
    }
}
