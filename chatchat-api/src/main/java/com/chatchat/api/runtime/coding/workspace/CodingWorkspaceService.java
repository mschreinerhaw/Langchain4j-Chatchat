package com.chatchat.api.runtime.coding.workspace;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.datascience.sql.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** SQL application profile over the existing A2, SPI, worker and Evidence Store. No database execution. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding.workspace",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.cline",name="enabled",havingValue="true")
public final class CodingWorkspaceService {
    private com.chatchat.api.datascience.StudioTaskHistory history;
    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(com.chatchat.api.datascience.StudioTaskHistory value){history=value;}
    public static final String INPUT_TYPE="CODING_WORKSPACE_INPUT";
    private final DurableCodingTaskService tasks;
    private final CodingInvocationContext identity;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectProvider<CodingAgentSPI> agents;
    private final SqlGlotToolchain parser;
    private final ObjectMapper json;
    private final long timeoutSeconds;
    public CodingWorkspaceService(DurableCodingTaskService tasks,CodingInvocationContext identity,
            JdbcRuntimeEvidenceStore evidence,ObjectProvider<CodingAgentSPI> agents,SqlGlotToolchain parser,ObjectMapper json,
            @Value("${chatchat.coding.workspace.timeout-seconds:300}") long timeoutSeconds){
        this.tasks=tasks;this.identity=identity;this.evidence=evidence;this.agents=agents;this.parser=parser;this.json=json;
        if(timeoutSeconds<1 || timeoutSeconds>3600)throw new IllegalArgumentException("Invalid workspace task timeout");
        this.timeoutSeconds=timeoutSeconds;
    }
    public static boolean supports(CodingTaskContract contract){
        return CodingProtocol.WORKSPACE_VERSION.equals(contract.schemaVersion())
            && contract.outputContract().resultFormat()==CodingTaskContract.ResultFormat.WORKSPACE_FILES;
    }
    /** Called only after the Studio has checked project ownership and persisted its plan/context. */
    public CodingTaskOutcome submit(KernelDataScope scope,String task,String context,String dialect,String modelName){
        return submit(scope,task,context,dialect,modelName,Map.of());
    }
    public CodingTaskOutcome submit(KernelDataScope scope,String task,String context,String dialect,String modelName,Map<String,Object> schema){
        boolean python="python".equals(dialect);
        String language=python?"Python":"SQL", profile=python?"python-studio":"sql-studio";
        if(!parser.available())throw new IllegalStateException("SQLGlot must be configured before enabling SQL Coding");
        return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
            if(context==null || context.length()>250000 || !python&&!SqlStudioService.DIALECTS.contains(dialect))
                throw new IllegalArgumentException("Invalid SQL workspace context");
            String key="coding:workspace-contract:"+task;
            var saved=evidence.readExecutionCheckpoint(scope,key);
            CodingTaskContract contract;
            if(saved.isPresent()){
                contract=decode(saved.get(),CodingTaskContract.class);
                var snapshot=requireAuthorized(contract);
                if(!context.equals(snapshot.get("context")) || !dialect.equals(snapshot.get("dialect"))
                        || !Objects.toString(modelName,"").equals(snapshot.get("modelName")) || !schema.equals(snapshot.get("schema")))
                    throw new IllegalArgumentException("SQL Coding request identity conflict");
            }else{
                String inputId="coding:workspace-input:"+task;
                String decision="studio-workspace:"+task, workspace="studio-workspace:"+task;
                var payload=Map.<String,Object>of("taskId",task,"context",context,"dialect",dialect,
                    "modelName",Objects.toString(modelName,""),"decisionRef",decision,"workspaceRef",workspace,"schema",schema);
                register(scope,inputId,INPUT_TYPE,payload,List.of(),task,null);
                var budget=new CodingTaskContract.Budget("workspace-budget:"+task,
                    Instant.now().plusSeconds(timeoutSeconds).truncatedTo(ChronoUnit.MILLIS),8,32768,16,0,
                    "1","USD",0,0,1024*1024);
                contract=new CodingTaskContract(CodingProtocol.WORKSPACE_VERSION,task,scope,
                    new CodingTaskContract.Objective("Read the authorized "+language+" engineering context with workspace_read, then propose complete source files with workspace_propose. Preserve unrelated code. The Runtime will parse proposals and return errors to repair. Never execute code or claim business validation.",
                        List.of("proposal.jsonl"),List.of(profile+":parse-and-review.v1")),
                    "runtime-evidence:"+inputId,List.of(),workspace,
                    new CodingTaskContract.Authorization(decision,List.of("workspace_read","workspace_propose"),List.of(),
                        List.of("context"),List.of(python?"candidate-python-files":"candidate-sql-files"),CodingTaskContract.NetworkPolicy.NONE,List.of(),
                        List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE)),budget,python?"python-ast:3":"sqlglot:30.23.0",
                    profile+":parse-and-review.v1",new CodingTaskContract.OutputContract(
                        CodingTaskContract.ResultFormat.WORKSPACE_FILES,profile+".output.v1",profile+".validation.v1",
                        List.of("proposal.jsonl"),List.of("application/x-ndjson"),32,512*1024,
                        List.of(CodingTaskContract.RequiredEvidence.values())));
                String encoded=encode(contract);
                if(!evidence.compareAndSetExecutionCheckpoint(scope,key,null,encoded))
                    contract=decode(evidence.readExecutionCheckpoint(scope,key).orElseThrow(),CodingTaskContract.class);
            }
            var admitted=agents.getObject().submit(contract);
            projectHistory(scope,task,admitted);
            return admitted;
        });
    }
    public CodingTaskOutcome get(KernelDataScope scope,String task){
        return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
            var agent=agents.getObject();var current=agent.get(scope,task);
            if(!current.status().terminal() && !Instant.now().isBefore(tasks.contract(scope,task).budget().deadlineAt()))
                current=agent.resume(scope,task);
            projectHistory(scope,task,current);
            return current;
        });
    }
    public CodingTaskOutcome cancel(KernelDataScope scope,String task){
        return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
            var result=agents.getObject().cancel(scope,task,"Studio user cancelled");projectHistory(scope,task,result);return result;});
    }
    public CodingTaskOutcome resume(KernelDataScope scope,String task){
        return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->agents.getObject().resume(scope,task));
    }
    public List<CodingExecutionEvent> events(KernelDataScope scope,String task){
        return identity.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->{
            var current=tasks.get(scope,task);
            return tasks.events(scope,task,Math.max(0,current.lastEventSequence()-128),128);
        });
    }
    public Map<String,Object> requireAuthorized(CodingTaskContract contract){
        identity.require(contract.scope(),CodingTaskAccess.Action.READ);
        boolean python="python-studio.output.v1".equals(contract.outputContract().resultSchemaRef());
        String profile=python?"python-studio":"sql-studio";
        if(!supports(contract) || !Set.of("workspace_read","workspace_propose").equals(new HashSet<>(contract.authorization().allowedTools()))
                || !contract.authorization().allowedDataRefs().isEmpty()
                || !contract.authorization().allowedSideEffects().equals(List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE))
                || contract.authorization().networkPolicy()!=CodingTaskContract.NetworkPolicy.NONE
                || !contract.authorization().readablePathRefs().equals(List.of("context"))
                || !contract.authorization().writablePathRefs().equals(List.of(python?"candidate-python-files":"candidate-sql-files"))
                || contract.budget().maxCodeExecutions()!=0 || !contract.environmentRef().equals(python?"python-ast:3":"sqlglot:30.23.0")
                || !contract.validationPlanRef().equals(profile+":parse-and-review.v1")
                || !contract.outputContract().resultSchemaRef().equals(profile+".output.v1"))
            throw new SecurityException("Invalid workspace task authorization");
        var record=evidence.find(contract.scope(),id(contract.inputSnapshotRef())).orElseThrow();
        var data=record.payload();
        if(!INPUT_TYPE.equals(record.evidenceType()) || !contract.taskId().equals(data.get("taskId"))
                || python!="python".equals(data.get("dialect"))
                || !contract.authorization().decisionRef().equals(data.get("decisionRef"))
                || !contract.workspaceRef().equals(data.get("workspaceRef"))
                || !evidence.contentHash(data).equals(record.contentSha256()))
            throw new SecurityException("Workspace input binding differs from contract");
        return data;
    }
    /** Reads one server-captured snapshot; it cannot discover assets or resolve arbitrary paths. */
    public Map<String,Object> read(CodingTaskContract contract,CodingJournal.Lease lease,String operation){
        var input=requireAuthorized(contract);
        var reserved=reserve(contract,lease,operation,evidence.contentHash(Map.of("input",contract.inputSnapshotRef())));
        var intent=tasks.startIntent(contract.scope(),contract.taskId(),lease,reserved.revision(),operation,"runtime-workspace:"+operation);
        String receiptId="coding:workspace:"+operation+":receipt";
        var result=Map.<String,Object>of("context",input.get("context"),"dialect",input.get("dialect"),"schema",input.get("schema"),"sqlExecuted",false);
        if(intent.dispatchIntentCreated())
            register(contract.scope(),receiptId,"CODING_WORKSPACE_TOOL_RECEIPT",Map.of(
                "taskId",contract.taskId(),"operationId",operation,"executionHandle","runtime-workspace:"+operation,
                "executorStopped",true,"sqlExecuted",false,"response",result),List.of(id(contract.inputSnapshotRef())),contract.taskId(),operation);
        else if(evidence.find(contract.scope(),receiptId).isEmpty())throw new IllegalStateException("Workspace read outcome requires reconciliation");
        settleProposal(contract,lease,operation,receiptId,result,List.of());
        return result;
    }
    public Map<String,Object> propose(CodingTaskContract contract,CodingJournal.Lease lease,String operation,Map<String,Object> proposal){
        var input=requireAuthorized(contract);String payloadHash=evidence.contentHash(proposal);
        String receiptId="coding:workspace:"+operation+":receipt";
        var old=evidence.find(contract.scope(),receiptId);
        if(old.isPresent()){
            if(!payloadHash.equals(old.get().payload().get("proposalHash")))throw new IllegalArgumentException("Workspace operation identity conflict");
            var restored=json.convertValue(old.get().payload().get("files"),new TypeReference<List<SqlOutputBundle.Candidate>>(){});
            settleProposal(contract,lease,operation,receiptId,response(old.get()),restored);
            publish(contract,operation,payloadHash,restored,response(old.get()),Objects.toString(old.get().payload().get("notes"),""),old.get().payload().get("normalization"));
            return response(old.get());
        }
        var admitted=reserve(contract,lease,operation,payloadHash);
        var intent=tasks.startIntent(contract.scope(),contract.taskId(),lease,admitted.revision(),operation,"runtime-workspace:"+operation);
        if(!intent.dispatchIntentCreated())throw new IllegalStateException("Reconcile previous workspace proposal before retry");
        evidence.mutateExecutionCheckpoint(contract.scope(),latestKey(contract),ignored->operation);
        Map<String,Object> validation;List<SqlOutputBundle.Candidate> files=List.of();
        try{
            files="python".equals(input.get("dialect"))?SqlOutputBundle.parsePython(encode(proposal),json):SqlOutputBundle.parse(encode(proposal),json);
            if(encode(files).getBytes(StandardCharsets.UTF_8).length>contract.outputContract().maxResultBytes())
                throw new IllegalArgumentException("SQL proposal exceeds task output limit");
            validation=parser.validate((String)input.get("dialect"),files,json.convertValue(input.get("schema"),new TypeReference<Map<String,Object>>(){}));
        }catch(SqlGlotToolchain.UnconfirmedStopException unknown){throw unknown;}
        catch(Exception invalid){
            validation=Map.of("valid",false,"sqlExecuted",false,"fieldValidation","NOT_EVALUATED",
                "diagnostics",List.of(Map.of("status","FAILED","message",Objects.toString(invalid.getMessage(),"SQL proposal rejected"))));
        }
        boolean valid=Boolean.TRUE.equals(validation.get("valid"));
        Object normalization=validation.getOrDefault("normalizedFiles",List.of());
        // Keep complete normalized source in Evidence, not in every subsequent model turn.
        var publicValidation=new LinkedHashMap<>(validation);publicValidation.remove("normalizedFiles");
        String base="coding:workspace:"+operation;
        var result=new LinkedHashMap<String,Object>();
        result.put("valid",valid);result.put("validation",publicValidation);result.put("sqlExecuted",false);
        result.put("operationId",operation);result.put("resultAvailable",valid);
        if(valid){result.put("codeArtifactRef","runtime-evidence:"+base+":code");
            result.put("executionReceiptRef","runtime-evidence:"+receiptId);
            result.put("resultDatasetRef","runtime-evidence:"+base+":result");
            result.put("validationRecordRef","runtime-evidence:"+base+":validation");
            result.put("evidenceBundleRef","runtime-evidence:"+base+":bundle");}
        var receipt=Map.<String,Object>of("taskId",contract.taskId(),"operationId",operation,
            "executionHandle","runtime-workspace:"+operation,"executorStopped",true,"sqlExecuted",false,
            "proposalHash",payloadHash,"response",result,"files",files,"notes",Objects.toString(proposal.get("notes"),""),"normalization",normalization);
        register(contract.scope(),receiptId,"CODING_WORKSPACE_TOOL_RECEIPT",receipt,
            List.of(id(contract.inputSnapshotRef())),contract.taskId(),operation);
        settleProposal(contract,lease,operation,receiptId,result,files);
        publish(contract,operation,payloadHash,files,result,Objects.toString(proposal.get("notes"),""),normalization);
        return Collections.unmodifiableMap(result);
    }
    private void settleProposal(CodingTaskContract contract,CodingJournal.Lease lease,String operation,String receipt,
            Map<String,Object> result,List<SqlOutputBundle.Candidate> files){
        var current=tasks.get(contract.scope(),contract.taskId());
        if(tasks.verifiedOperation(contract.scope(),contract.taskId(),operation).isEmpty())
            current=tasks.reconcileStopped(contract.scope(),contract.taskId(),lease,current.revision(),operation,"runtime-evidence:"+receipt);
        tasks.settle(contract.scope(),contract.taskId(),lease,current.revision(),operation,
            count(1,Boolean.TRUE.equals(result.get("valid"))?encode(files).getBytes(StandardCharsets.UTF_8).length:0),true);
    }
    @SuppressWarnings("unchecked")
    private void publish(CodingTaskContract contract,String operation,String payloadHash,List<SqlOutputBundle.Candidate> files,
            Map<String,Object> result,String notes,Object normalization){
        if(Boolean.TRUE.equals(result.get("valid"))){
            String base="coding:workspace:"+operation, receiptId=base+":receipt";
            var validation=new LinkedHashMap<>((Map<String,Object>)result.get("validation"));
            validation.put("normalizedFiles",normalization==null?List.of():normalization);
            var records=new ArrayList<EvidenceRecord>();var lineages=new ArrayList<EvidenceLineage>();
            add(records,lineages,contract,operation,base+":code","CODING_WORKSPACE_CODE",Map.of("files",files),List.of(id(contract.inputSnapshotRef())));
            add(records,lineages,contract,operation,base+":result","CODING_WORKSPACE_FILE_INDEX",Map.of("files",files,"notes",notes,"validation",validation),List.of(base+":code",receiptId));
            add(records,lineages,contract,operation,base+":validation","CODING_WORKSPACE_VALIDATION",validation,List.of(base+":result"));
            String key="coding:workspace-batch:"+operation;
            String verified=encode(Map.of("stage","VERIFIED","proposalHash",payloadHash));
            String committed=encode(Map.of("stage","COMMITTED","proposalHash",payloadHash));
            var prior=evidence.readExecutionCheckpoint(contract.scope(),key);
            if(prior.isEmpty())evidence.compareAndSetExecutionCheckpoint(contract.scope(),key,null,verified);
            evidence.commitCodingEvidence(contract.scope(),key,verified,committed,records,lineages,base+":bundle",
                List.of(id(contract.inputSnapshotRef()),base+":code",receiptId,base+":result",base+":validation"),
                Map.of("taskId",contract.taskId(),"profile",contract.outputContract().resultSchemaRef(),"sqlExecuted",false,"codeExecuted",false));
        }
    }
    /** Only the latest parsed proposal may complete. Reading output never executes SQL. */
    public CodingTaskOutcome complete(CodingTaskContract contract,CodingJournal.Lease lease,String operation){
        requireAuthorized(contract);
        String latest=evidence.readExecutionCheckpoint(contract.scope(),latestKey(contract)).orElse(null);
        if(operation==null || !operation.equals(latest))throw new IllegalStateException("No current verified workspace proposal");
        if(tasks.verifiedOperation(contract.scope(),contract.taskId(),operation).isEmpty())throw new IllegalStateException("Workspace operation is not stopped");
        var response=response(evidence.find(contract.scope(),"coding:workspace:"+operation+":receipt").orElseThrow());
        if(!Boolean.TRUE.equals(response.get("valid")))throw new IllegalStateException("SQL proposal has not passed parsing");
        String bundle=(String)response.get("evidenceBundleRef");
        if(evidence.snapshot(contract.scope(),id(bundle)).isEmpty())throw new IllegalStateException("Workspace evidence has not committed");
        var current=tasks.get(contract.scope(),contract.taskId());
        if(current.status().terminal())return current;
        Instant at=Instant.now().truncatedTo(ChronoUnit.MILLIS);if(at.isBefore(current.updatedAt()))at=current.updatedAt();
        var next=new CodingTaskOutcome(current.schemaVersion(),current.taskId(),current.scope(),current.contractSha256(),
            CodingTaskOutcome.Status.COMPLETED,current.revision()+1,current.lastEventSequence()+1,current.createdAt(),at,current.startedAt(),at,
            false,null,"Source candidates parsed and staged for user review; no code executed",
            List.of("Business semantics and database execution were not evaluated; field validation scope is recorded separately"),contract.inputSnapshotRef(),
            (String)response.get("codeArtifactRef"),(String)response.get("executionReceiptRef"),(String)response.get("resultDatasetRef"),
            (String)response.get("validationRecordRef"),bundle,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE,
            current.reproducibility(),current.usage(),new CodingTaskOutcome.ExecutionControl(null,null,true,false),null,null,Map.of());
        var committed=tasks.commitOutcome(contract.scope(),contract.taskId(),lease,current.revision(),next);
        projectHistory(contract.scope(),contract.taskId(),committed);
        return committed;
    }
    public void captureHistory(KernelDataScope scope,String task,com.chatchat.api.datascience.StudioTaskHistory.Request request){
        identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
            String key="coding:workspace-history:"+task, value=encode(request);
            boolean created=evidence.compareAndSetExecutionCheckpoint(scope,key,null,value);
            if(!created && !value.equals(evidence.readExecutionCheckpoint(scope,key).orElseThrow()))
                throw new IllegalArgumentException("Studio history request identity conflict");
            return null;
        });
    }
    private synchronized void projectHistory(KernelDataScope scope,String task,CodingTaskOutcome outcome){
        if(history==null)return;
        String projectedKey="coding:workspace-history-revision:"+task;
        var projected=evidence.readExecutionCheckpoint(scope,projectedKey);
        if(projected.isPresent() && Long.parseLong(projected.get())>=outcome.revision())return;
        evidence.readExecutionCheckpoint(scope,"coding:workspace-history:"+task).ifPresent(saved->{
            var request=decode(saved,com.chatchat.api.datascience.StudioTaskHistory.Request.class);
            Map<String,Object> output=outcome.status()==CodingTaskOutcome.Status.COMPLETED?output(scope,task,outcome):Map.of();
            var trace=events(scope,task).stream().map(event->Map.<String,Object>of("type",event.type().name(),
                "status",event.status().name(),"summary",Objects.toString(event.summary(),""),"sequence",event.sequence())).toList();
            history.project(scope,task,request,outcome,output,trace);
            evidence.mutateExecutionCheckpoint(scope,projectedKey,ignored->Long.toString(outcome.revision()));
        });
    }
    public CodingTaskOutcome recover(CodingTaskContract contract,CodingJournal.Lease lease){
        var latest=evidence.readExecutionCheckpoint(contract.scope(),latestKey(contract));
        if(latest.isEmpty())return null;
        var receipt=evidence.find(contract.scope(),"coding:workspace:"+latest.get()+":receipt");
        if(receipt.isEmpty() || !Boolean.TRUE.equals(response(receipt.get()).get("valid")))return null;
        var restored=json.convertValue(receipt.get().payload().get("files"),new TypeReference<List<SqlOutputBundle.Candidate>>(){});
        settleProposal(contract,lease,latest.get(),receipt.get().evidenceId(),response(receipt.get()),restored);
        publish(contract,latest.get(),(String)receipt.get().payload().get("proposalHash"),restored,response(receipt.get()),
            Objects.toString(receipt.get().payload().get("notes"),""),receipt.get().payload().get("normalization"));
        return complete(contract,lease,latest.get());
    }
    public Map<String,Object> output(KernelDataScope scope,String task,CodingTaskOutcome outcome){
        return identity.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->{
            tasks.get(scope,task);
            var record=evidence.find(scope,id(outcome.resultDatasetRef())).orElseThrow();
            if(!"CODING_WORKSPACE_FILE_INDEX".equals(record.evidenceType())
                    || !evidence.lineage(scope,record.evidenceId()).orElseThrow().producingTaskId().equals(task))
                throw new SecurityException("Workspace result does not belong to task");
            return record.payload();
        });
    }
    private CodingTaskOutcome reserve(CodingTaskContract contract,CodingJournal.Lease lease,String operation,String hash){
        if(operation==null || !operation.matches("workspace-[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid workspace operation ID");
        var admitted=tasks.reserve(contract.scope(),contract.taskId(),lease,tasks.get(contract.scope(),contract.taskId()).revision(),
            operation,hash,CodingJournal.OperationKind.TOOL,count(1,contract.outputContract().maxResultBytes()));
        if(!admitted.admitted())throw new IllegalStateException("Workspace tool budget denied");return admitted.outcome();
    }
    private static CodingTaskOutcome.Counters count(long tools,long bytes){return new CodingTaskOutcome.Counters(0,0,tools,0,0,0,bytes,"0");}
    private static String latestKey(CodingTaskContract contract){return "coding:workspace-latest:"+contract.taskId();}
    private static String id(String ref){if(ref==null || !ref.startsWith("runtime-evidence:"))throw new SecurityException("Invalid workspace reference");return ref.substring("runtime-evidence:".length());}
    @SuppressWarnings("unchecked") private static Map<String,Object> response(EvidenceRecord record){return (Map<String,Object>)record.payload().get("response");}
    private void register(KernelDataScope scope,String id,String type,Map<String,Object> data,List<String> parents,String task,String operation){
        data=json.convertValue(data,new TypeReference<Map<String,Object>>(){});
        evidence.register(new EvidenceRecord(null,id,scope,type,"CodingWorkspaceService",evidence.contentHash(data),data,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,scope,parents,task,"CodingWorkspaceService",operation,Map.of()));
    }
    private void add(List<EvidenceRecord> records,List<EvidenceLineage> lineages,CodingTaskContract contract,String operation,String id,String type,Map<String,Object> data,List<String> parents){
        data=json.convertValue(data,new TypeReference<Map<String,Object>>(){});
        records.add(new EvidenceRecord(null,id,contract.scope(),type,"CodingWorkspaceService",evidence.contentHash(data),data,null,System.currentTimeMillis(),Map.of()));
        lineages.add(new EvidenceLineage(null,id,contract.scope(),parents,contract.taskId(),"CodingWorkspaceService",operation,Map.of()));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid workspace content",e);}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid workspace checkpoint",e);}}
}
