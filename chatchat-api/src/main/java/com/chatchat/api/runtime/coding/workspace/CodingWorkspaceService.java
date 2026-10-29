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
@lombok.extern.slf4j.Slf4j
@ConditionalOnProperty(prefix="chatchat.coding.workspace",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.cline",name="enabled",havingValue="true")
public final class CodingWorkspaceService {
    private SqlWorkspaceMetadataTools exploration;
    @org.springframework.beans.factory.annotation.Autowired
    public void setExploration(SqlWorkspaceMetadataTools value){exploration=value;}
    private SqlStudioReferenceResolver references;
    @org.springframework.beans.factory.annotation.Autowired
    public void setReferences(SqlStudioReferenceResolver value){references=value;}
    private ObjectProvider<SqlStudioMetadataService> metadata;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMetadata(ObjectProvider<SqlStudioMetadataService> value){metadata=value;}
    private com.chatchat.api.assets.RuntimeAssetArchive assets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAssets(com.chatchat.api.assets.RuntimeAssetArchive value){assets=value;}
    private com.chatchat.api.datascience.StudioTaskHistory history;
    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(com.chatchat.api.datascience.StudioTaskHistory value){history=value;}
    public static final String INPUT_TYPE="CODING_WORKSPACE_INPUT";
    public static final String FAST_PATH_REF="runtime-coding:workspace-fast-path:v1";
    private boolean fastPathEnabled;
    private int fastPathMaxContextBytes=32768;
    @Value("${chatchat.coding.workspace.fast-path.enabled:false}")
    public void setFastPathEnabled(boolean enabled){fastPathEnabled=enabled;}
    @Value("${chatchat.coding.workspace.fast-path.max-context-bytes:32768}")
    public void setFastPathMaxContextBytes(int bytes){
        if(bytes<1024 || bytes>65536)throw new IllegalArgumentException("Invalid fast path context limit");
        fastPathMaxContextBytes=bytes;
    }
    private final DurableCodingTaskService tasks;
    private final CodingInvocationContext identity;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectProvider<CodingAgentSPI> agents;
    private final SqlGlotToolchain parser;
    private final ObjectMapper json;
    private final long timeoutSeconds;
    private long maxModelTokens=262144;
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.workspace.max-model-tokens:262144}")
    public void setMaxModelTokens(long value){
        if(value<32768 || value>8388608)throw new IllegalArgumentException("Invalid workspace token budget");
        maxModelTokens=value;
    }
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
                boolean autonomous=!python && schema.get("discoveryId") instanceof String && schema.get("projectId") instanceof String;
                boolean fast=fastPathEnabled && !autonomous && encode(payload).getBytes(StandardCharsets.UTF_8).length<=fastPathMaxContextBytes;
                register(scope,inputId,INPUT_TYPE,payload,List.of(),task,null);
                var budget=new CodingTaskContract.Budget("workspace-budget:"+task,
                    Instant.now().plusSeconds(timeoutSeconds).truncatedTo(ChronoUnit.MILLIS),autonomous?24:8,maxModelTokens,autonomous?64:16,0,
                    "1","USD",0,0,1024*1024);
                contract=new CodingTaskContract(CodingProtocol.WORKSPACE_VERSION,task,scope,
                    new CodingTaskContract.Objective((fast?"Use the Java-prefetched authorized ":"Read the authorized ")+language
                        +(fast?" engineering context and propose source files directly with workspace_propose. ":" engineering context with workspace_read, then propose complete source files with workspace_propose. ")
                        +"Preserve unrelated code. The Runtime will parse proposals and return errors to repair. Never execute code or claim business validation. "
                        +(autonomous?"Use workspace_metadata to autonomously discover tables, inspect fields, known relationships and enterprise business definitions in the selected database. No table has been preselected for you. Use workspace_validate, then continue exploring and repairing when metadata or columns are missing. Missing snapshot entries are not proof of invalid SQL. Unknown metric definitions and joins must be explicit; do not invent them.":""),
                        List.of("proposal.jsonl"),List.of(profile+":parse-and-review.v1")),
                    "runtime-evidence:"+inputId,autonomous?List.of(SqlWorkspaceMetadataTools.PROFILE):fast?List.of(FAST_PATH_REF):List.of(),workspace,
                    new CodingTaskContract.Authorization(decision,new ArrayList<>(SqlWorkspaceMetadataTools.tools(autonomous)),List.of(),
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
            return tasks.events(scope,task,Math.max(0,current.lastEventSequence()-128),128).stream().map(event->{
                if(event.operationId()==null)return event;
                var progress=evidence.readExecutionCheckpoint(scope,"coding:workspace-progress:"+event.operationId());
                return progress.isEmpty()?event:event.withPayload(decode(progress.get(),Map.class));
            }).toList();
        });
    }
    public Map<String,Object> requireAuthorized(CodingTaskContract contract){
        identity.require(contract.scope(),CodingTaskAccess.Action.READ);
        boolean python="python-studio.output.v1".equals(contract.outputContract().resultSchemaRef());
        String profile=python?"python-studio":"sql-studio";
        if(!supports(contract) || !SqlWorkspaceMetadataTools.tools(SqlWorkspaceMetadataTools.enabled(contract)).equals(new HashSet<>(contract.authorization().allowedTools()))
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
        if(SqlWorkspaceMetadataTools.enabled(contract) && (python || !(data.get("schema") instanceof Map<?,?> schema)
                || !(schema.get("discoveryId") instanceof String) || !(schema.get("projectId") instanceof String)))
            throw new SecurityException("SQL metadata profile must bind an owned discovery context");
        if(!INPUT_TYPE.equals(record.evidenceType()) || !contract.taskId().equals(data.get("taskId"))
                || python!="python".equals(data.get("dialect"))
                || !contract.authorization().decisionRef().equals(data.get("decisionRef"))
                || !contract.workspaceRef().equals(data.get("workspaceRef"))
                || !evidence.contentHash(data).equals(record.contentSha256()))
            throw new SecurityException("Workspace input binding differs from contract");
        return data;
    }
    /** Both callbacks use the existing fenced A2 journal, cumulative tool budget and Evidence Store. */
    public Map<String,Object> metadata(CodingTaskContract contract,CodingJournal.Lease lease,String operation,Map<String,Object> arguments){
        return explorationTool(contract,lease,operation,"workspace_metadata",arguments);
    }
    public Map<String,Object> validateSql(CodingTaskContract contract,CodingJournal.Lease lease,String operation,Map<String,Object> arguments){
        return explorationTool(contract,lease,operation,"workspace_validate",arguments);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> explorationTool(CodingTaskContract contract,CodingJournal.Lease lease,String operation,String tool,Map<String,Object> arguments){
        var input=requireAuthorized(contract);
        if(exploration==null||!SqlWorkspaceMetadataTools.enabled(contract)||!contract.authorization().allowedTools().contains(tool))throw new SecurityException("SQL exploration tool is outside this task authorization");
        if(encode(arguments).length()>250000)throw new IllegalArgumentException("Workspace tool arguments exceed limit");
        String hash=evidence.contentHash(Map.of("tool",tool,"arguments",arguments)),receiptId="coding:workspace:"+operation+":receipt";
        var old=evidence.find(contract.scope(),receiptId);
        if(old.isPresent()&&!hash.equals(old.get().payload().get("proposalHash")))throw new IllegalArgumentException("Workspace operation identity conflict");
        if(old.isPresent()){
            // Only task-bound physical cache reads are reused, and current grants/snapshots are rechecked.
            var schema=json.convertValue(input.get("schema"),new TypeReference<Map<String,Object>>(){});
            exploration.context(contract,schema);
            if(tool.equals("workspace_metadata"))exploration.query(contract,schema,arguments);
            if(tool.equals("workspace_validate")){
                var files=SqlOutputBundle.parse(encode(Map.of("schemaVersion","sql-studio.output.v1","files",arguments.getOrDefault("files",List.of()),"notes","validation")),json);
                var fresh=exploration.validate(contract,schema,files,(String)input.get("dialect"));
                if(!Boolean.TRUE.equals(fresh.get("valid")))return fresh;
            }
            return response(old.get());
        }
        var reserved=reserve(contract,lease,operation,hash);
        var intent=tasks.startIntent(contract.scope(),contract.taskId(),lease,reserved.revision(),operation,"runtime-workspace:"+operation);
        if(!intent.dispatchIntentCreated())throw new IllegalStateException("Reconcile metadata read before retry");
        String phase=tool.equals("workspace_validate")?"VALIDATING_SQL":Set.of("DESCRIBE","LINEAGE").contains(Objects.toString(arguments.get("operation"),""))?"CONFIRMING_FIELDS":"FINDING_TABLES";
        evidence.mutateExecutionCheckpoint(contract.scope(),"coding:workspace-progress:"+operation,ignored->encode(Map.of("sqlProgressPhase",phase,"tool",tool,"state","RUNNING")));
        long started=System.nanoTime();Map<String,Object> result;
        try{
            var schema=json.convertValue(input.get("schema"),new TypeReference<Map<String,Object>>(){});
            if(tool.equals("workspace_metadata"))result=exploration.query(contract,schema,arguments);
            else{
                var files=SqlOutputBundle.parse(encode(Map.of("schemaVersion","sql-studio.output.v1","files",arguments.getOrDefault("files",List.of()),"notes","validation")),json);
                result=exploration.validate(contract,schema,files,(String)input.get("dialect"));
            }
        }catch(SqlGlotToolchain.UnconfirmedStopException unknown){throw unknown;}
        catch(Exception error){result=SqlWorkspaceMetadataTools.failure(error);}
        result=new LinkedHashMap<>(result);result.put("durationMs",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        result.put("operationId",operation);result.put("tool",tool);
        register(contract.scope(),receiptId,"CODING_WORKSPACE_TOOL_RECEIPT",Map.of("taskId",contract.taskId(),"operationId",operation,
            "executionHandle","runtime-workspace:"+operation,"executorStopped",true,"sqlExecuted",false,"proposalHash",hash,"tool",tool,
            "arguments",arguments,"response",result),List.of(id(contract.inputSnapshotRef())),contract.taskId(),operation);
        var current=tasks.get(contract.scope(),contract.taskId());
        current=tasks.reconcileStopped(contract.scope(),contract.taskId(),lease,current.revision(),operation,"runtime-evidence:"+receiptId);
        tasks.settle(contract.scope(),contract.taskId(),lease,current.revision(),operation,count(1,encode(result).getBytes(StandardCharsets.UTF_8).length),true);
        var completed=result;
        evidence.mutateExecutionCheckpoint(contract.scope(),"coding:workspace-progress:"+operation,ignored->encode(Map.of("sqlProgressPhase",phase,"tool",tool,
            "state",Boolean.FALSE.equals(completed.get("valid"))||Set.of("FAILED","NOT_FOUND").contains(Objects.toString(completed.get("status"),""))?"FAILED":"COMPLETED","durationMs",completed.get("durationMs"))));
        log.info("studio_sql_tool task={} operation={} tool={} phase={} durationMs={} status={}",contract.taskId(),operation,tool,phase,result.get("durationMs"),result.getOrDefault("status",result.get("valid")));
        recordExploration(contract,operation,tool,result);
        return result;
    }
    private void recordExploration(CodingTaskContract contract,String operation,String tool,Map<String,Object> result){
        evidence.mutateExecutionCheckpoint(contract.scope(),"coding:workspace-audit:"+contract.taskId(),previous->{
            Map<String,Object> audit=previous==null?new LinkedHashMap<>():decode(previous,Map.class);
            var row=new LinkedHashMap<String,Object>();row.put("operationId",operation);row.put("tool",tool);row.put("durationMs",result.getOrDefault("durationMs",result.getOrDefault("validationDurationMs",0)));
            row.put("status",result.getOrDefault("status",Boolean.TRUE.equals(result.get("valid"))?"VALID":"FAILED"));
            row.put("valid",Boolean.TRUE.equals(result.get("valid")));row.put("receiptRef","runtime-evidence:coding:workspace:"+operation+":receipt");
            if(result.get("category")!=null)row.put("category",result.get("category"));
            audit.put(operation,row);return encode(audit);
        });
    }
    public Map<String,Object> diagnostics(KernelDataScope scope,String task){
        return identity.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->{
            tasks.get(scope,task);var saved=evidence.readExecutionCheckpoint(scope,"coding:workspace-audit:"+task);
            if(saved.isEmpty())return Map.of();
            var audit=decode(saved.get(),Map.class);var operations=json.convertValue(audit.values(),new TypeReference<List<Map<String,Object>>>(){});
            long attempts=operations.stream().filter(row->"workspace_propose".equals(row.get("tool"))).count();
            long validations=operations.stream().filter(row->Set.of("workspace_validate","workspace_propose").contains(row.get("tool"))).count();
            long duration=operations.stream().mapToLong(row->((Number)row.get("durationMs")).longValue()).sum();
            var proposals=operations.stream().filter(row->"workspace_propose".equals(row.get("tool"))).toList();
            return Map.of("autonomous",true,"toolCalls",operations.size(),"validationAttempts",validations,"repairAttempts",Math.max(0,attempts-1),
                "toolDurationMs",duration,"finalSqlValid",!proposals.isEmpty()&&Boolean.TRUE.equals(proposals.get(proposals.size()-1).get("valid")),"operations",operations);
        });
    }
    private List<String> explorationParents(CodingTaskContract contract){
        var parents=new ArrayList<String>();parents.add(id(contract.inputSnapshotRef()));
        var audit=evidence.readExecutionCheckpoint(contract.scope(),"coding:workspace-audit:"+contract.taskId());
        if(audit.isPresent())for(Object value:decode(audit.get(),Map.class).values())if(value instanceof Map<?,?> row
                && Set.of("workspace_metadata","workspace_validate").contains(row.get("tool"))
                && row.get("receiptRef") instanceof String reference){
            String parent=id(reference);if(evidence.find(contract.scope(),parent).isPresent())parents.add(parent);
        }
        return parents;
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
        long validationStarted=System.nanoTime();
        if(SqlWorkspaceMetadataTools.enabled(contract))evidence.mutateExecutionCheckpoint(contract.scope(),"coding:workspace-progress:"+operation,
            ignored->encode(Map.of("sqlProgressPhase","VALIDATING_SQL","tool","workspace_propose","state","RUNNING")));
        try{
            files="python".equals(input.get("dialect"))?SqlOutputBundle.parsePython(encode(proposal),json):SqlOutputBundle.parse(encode(proposal),json);
            if(encode(files).getBytes(StandardCharsets.UTF_8).length>contract.outputContract().maxResultBytes())
                throw new IllegalArgumentException("SQL proposal exceeds task output limit");
            Map<String,Object> schema=json.convertValue(input.get("schema"),new TypeReference<Map<String,Object>>(){});
            if(SqlWorkspaceMetadataTools.enabled(contract)){
                validation=exploration.validate(contract,schema,files,(String)input.get("dialect"));
            }else{
            if(!"python".equals(input.get("dialect")) && schema.get("discoveryId") instanceof String discovery && references!=null){
                var context=metadata.getObject().getScoped(contract.scope(),schema.get("projectId").toString(),discovery);
                var cached=json.convertValue(schema.getOrDefault("tables",List.of()),new TypeReference<List<Map<String,Object>>>(){});
                var resolved=references.resolve(contract.scope(),context,(String)input.get("dialect"),files,cached);
                schema=new LinkedHashMap<>(schema);schema.put("tables",resolved);
            }
            validation=parser.validate((String)input.get("dialect"),files,schema);
            }
        }catch(SqlGlotToolchain.UnconfirmedStopException unknown){throw unknown;}
        catch(Exception invalid){
            validation=SqlWorkspaceMetadataTools.enabled(contract)?SqlWorkspaceMetadataTools.failure(invalid):Map.of("valid",false,"sqlExecuted",false,"fieldValidation","NOT_EVALUATED",
                "diagnostics",List.of(Map.of("status","FAILED","category",invalid instanceof SecurityException
                    || invalid instanceof org.springframework.web.server.ResponseStatusException status && status.getStatusCode().value()==403 ? "AUTHORIZATION" : "VALIDATION",
                    "message",Objects.toString(invalid.getMessage(),"SQL proposal rejected"))));
        }
        boolean valid=Boolean.TRUE.equals(validation.get("valid"));
        Object normalization=validation.getOrDefault("normalizedFiles",List.of());
        // Keep complete normalized source in Evidence, not in every subsequent model turn.
        var publicValidation=new LinkedHashMap<>(validation);publicValidation.remove("normalizedFiles");
        String base="coding:workspace:"+operation;
        var result=new LinkedHashMap<String,Object>();
        result.put("valid",valid);result.put("validation",publicValidation);result.put("sqlExecuted",false);
        result.put("validationDurationMs",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-validationStarted));
        result.put("operationId",operation);result.put("resultAvailable",valid);
        if(SqlWorkspaceMetadataTools.enabled(contract)){
            recordExploration(contract,operation,"workspace_propose",result);
            evidence.mutateExecutionCheckpoint(contract.scope(),"coding:workspace-progress:"+operation,ignored->encode(Map.of("sqlProgressPhase","VALIDATING_SQL","tool","workspace_propose","state",valid?"COMPLETED":"FAILED")));
        }
        if(valid){result.put("codeArtifactRef","runtime-evidence:"+base+":code");
            result.put("executionReceiptRef","runtime-evidence:"+receiptId);
            result.put("resultDatasetRef","runtime-evidence:"+base+":result");
            result.put("validationRecordRef","runtime-evidence:"+base+":validation");
            result.put("evidenceBundleRef","runtime-evidence:"+base+":bundle");}
        var receipt=Map.<String,Object>of("taskId",contract.taskId(),"operationId",operation,
            "executionHandle","runtime-workspace:"+operation,"executorStopped",true,"sqlExecuted",false,
            "proposalHash",payloadHash,"response",result,"files",files,"notes",Objects.toString(proposal.get("notes"),""),"normalization",normalization);
        register(contract.scope(),receiptId,"CODING_WORKSPACE_TOOL_RECEIPT",receipt,
            SqlWorkspaceMetadataTools.enabled(contract)?explorationParents(contract):List.of(id(contract.inputSnapshotRef())),contract.taskId(),operation);
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
            if(assets!=null){
                for(var file:files)assets.code(contract.scope(),contract.taskId(),"workspace:"+operation+":"+file.path(),
                    java.nio.file.Path.of(file.path()).getFileName().toString(),file.content());
                assets.context(contract.scope(),contract.taskId(),"workspace-context:"+operation,
                    Map.of("taskId",contract.taskId(),"operationId",operation,"inputSnapshotRef",contract.inputSnapshotRef(),
                        "files",files,"notes",notes,"validation",result.get("validation")));
            }
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
        try{projectHistoryChecked(scope,task,outcome);}
        catch(RuntimeException unavailable){
            // The durable task is already accepted/committed. A secondary projection
            // must not hide its ID or turn valid code into a failed Coding execution.
            // No revision is acknowledged: the next scoped read retries from Evidence.
            log.warn("studio_history_projection_pending taskId={} cause={}",task,unavailable.getClass().getSimpleName());
        }
    }
    private void projectHistoryChecked(KernelDataScope scope,String task,CodingTaskOutcome outcome){
        String projectedKey="coding:workspace-history-revision:v2:"+task;
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
