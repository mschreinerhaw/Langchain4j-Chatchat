package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.analysis.PreauthorizedStructuredDataOperator;
import com.chatchat.api.runtime.analysis.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Authorized template acquisition in Java. A2 owns admission, unknown starts and all input revisions. */
@Service @ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingDataSupplyService {
    private final CodingDataSupplyProperties policy;
    private final DurableCodingTaskService tasks;
    private final PreauthorizedStructuredDataOperator templates;
    private final CodingInputSnapshotService inputs;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectMapper json;
    private final com.chatchat.common.retrieval.SkillExecutionScopePort scopes;
    public CodingDataSupplyService(CodingDataSupplyProperties policy, DurableCodingTaskService tasks,
        PreauthorizedStructuredDataOperator templates, CodingInputSnapshotService inputs,
        JdbcRuntimeEvidenceStore evidence, ObjectMapper json,com.chatchat.common.retrieval.SkillExecutionScopePort scopes) {
        this.policy=policy;this.tasks=tasks;this.templates=templates;this.inputs=inputs;this.evidence=evidence;
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.scopes=Objects.requireNonNull(scopes);
    }
    private List<CodingDataSupplyProperties.Grant> grants(KernelDataScope scope) {
        if(!policy.isEnabled())return List.of();
        if(policy.getMaxRounds()<1||policy.getMaxRounds()>16)throw new IllegalStateException("Invalid data supply round ceiling");
        return policy.getGrants().stream().filter(g->scope.tenantId().equals(g.tenantId())&&scope.userId().equals(g.userId()))
            .filter(g->scopes.resolve(scope.tenantId(),scope.userId(),g.skillId(),List.of(),List.of()).skillAllowed()).toList();
    }
    public String policyRef(KernelDataScope scope) {return "coding-data-policy:"+hash(grants(scope));}
    public List<Map<String,Object>> describe(KernelDataScope scope) {
        return grants(scope).stream().map(g->Map.<String,Object>of("authorizedTemplateId",g.templateId(),
            "parameterValues",g.parameterValues(),"requiredFields",g.requiredFields())).toList();
    }
    public void requireAuthorized(CodingTaskContract contract) {
        if(!CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
            || !contract.metadataRefs().contains(policyRef(contract.scope())) || grants(contract.scope()).isEmpty())
            throw new SecurityException("Dynamic data grant is unavailable or changed");
    }
    public List<CodingInputSnapshotService.Snapshot> snapshots(CodingTaskContract c, String operation) {
        var saved=tasks.privateState(c.scope(),c.taskId(),operation==null?"data-inputs":"inputs:"+operation);
        if(saved==null) {
            if(operation!=null)throw new SecurityException("Execution input revision was not bound");
            return List.of(inputs.requireAuthorized(c));
        }
        return List.of(json.convertValue(saved,CodingInputSnapshotService.Snapshot[].class));
    }
    public List<CodingInputSnapshotService.Snapshot> bindInputs(CodingTaskContract c,CodingJournal.Lease lease,String operation) {
        String key="inputs:"+operation;
        var saved=tasks.privateState(c.scope(),c.taskId(),key);
        if(saved==null) {
            saved=json.valueToTree(snapshots(c,null));
            tasks.privateState(c.scope(),c.taskId(),lease,key,null,saved);
        }
        return List.of(json.convertValue(saved,CodingInputSnapshotService.Snapshot[].class));
    }
    public List<CodingInputSnapshotService.Chunk> mountedFiles(CodingTaskContract c,String operation) {
        return snapshots(c,operation).stream().flatMap(s->CodingInputSnapshotService.mountedFiles(s).stream()).toList();
    }
    public boolean authorizes(CodingTaskContract c,String operation,Map<String,String> files) {
        if(!CodingProtocol.ANALYSIS_VERSION.equals(c.schemaVersion()))return false;
        var saved=tasks.privateState(c.scope(),c.taskId(),"inputs:"+operation);
        if(saved==null)return false;
        Map<String,String> actual=new TreeMap<>();
        for(var s:List.of(json.convertValue(saved,CodingInputSnapshotService.Snapshot[].class)))
            for(var file:CodingInputSnapshotService.mountedFiles(s))actual.put(file.fileId(),file.sha256());
        return actual.equals(files);
    }
    public List<String> provenanceIds(CodingTaskContract c,String operation) {
        var versions=snapshots(c,operation);List<String> refs=new ArrayList<>();
        for(int i=0;i<versions.size();i++) {
            var version=versions.get(i);refs.add(version.reference().substring(17));
            if(i>0) {
                String[] parts=version.sourceDatasetRef().split(":");
                if(parts.length!=3||!parts[0].equals("coding-data")||!parts[1].matches("data-[0-9a-f]{64}"))throw new SecurityException("Data version provenance is invalid");
                refs.add("coding:data:"+hash(Map.of("task",c.taskId(),"operation",parts[1])));
            }
        }
        return List.copyOf(refs);
    }
    public Map<String,Object> request(CodingTaskContract c,CodingJournal.Lease lease,JsonNode request) {
        requireAuthorized(c);
        if(!request.isObject()||request.size()!=9||!c.taskId().equals(text(request,"taskId",512)))throw new IllegalArgumentException("Exact data requirement required");
        String requestId=text(request,"requestId",128), operation=text(request,"operationId",128);
        if(!operation.equals("data-"+hash(Map.of("task",c.taskId(),"request",requestId))))throw new SecurityException("Data operation identity differs");
        String template=text(request,"authorizedTemplateId",256);
        text(request,"reason",2000);text(request,"expectedUsage",2000);
        var matches=grants(c.scope()).stream().filter(g->g.templateId().equals(template)).toList();
        if(matches.size()!=1)throw new SecurityException("Template grant is not unique");
        var grant=matches.get(0);
        var params=request.path("parameterBindings");
        if(!params.isObject()||params.size()!=grant.parameterValues().size())throw new SecurityException("Parameter bindings differ from grant");
        for(var entry:grant.parameterValues().entrySet())
            if(!params.path(entry.getKey()).isTextual()||!entry.getValue().contains(params.path(entry.getKey()).asText()))throw new SecurityException("Parameter value outside grant");
        if(!request.path("requiredFields").isArray()||!request.path("datasetRefs").isArray())throw new IllegalArgumentException("Data fields/references required");
        var current=snapshots(c,null);
        Set<String> refs=new HashSet<>();current.forEach(s->refs.add(s.sourceDatasetRef()));
        for(var ref:request.path("datasetRefs"))if(!ref.isTextual()||!refs.contains(ref.asText()))throw new SecurityException("Undelivered dataset reference");
        for(var field:request.path("requiredFields"))if(!field.isTextual()||!grant.requiredFields().contains(field.asText()))throw new SecurityException("Field outside grant");
        String digest=hash(request), receiptId="coding:data:"+hash(Map.of("task",c.taskId(),"operation",operation));
        var prior=evidence.find(c.scope(),receiptId);
        if(prior.isPresent()) {
            if(!digest.equals(prior.get().payload().get("requestHash")))throw new IllegalArgumentException("Data request identity conflict");
            return settle(c,lease,operation,receiptId,prior.get().payload());
        }
        // New requests cannot bypass the same cumulative budget, nor silently exceed bounded rounds.
        if(tasks.startedDataRounds(c.scope(),c.taskId())>=policy.getMaxRounds()
            &&!tasks.unresolvedOperations(c.scope(),c.taskId()).contains(operation))throw new IllegalStateException("DATA_ROUND_LIMIT");
        var reserved=tasks.reserve(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,digest,
            CodingJournal.OperationKind.TOOL,new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0"));
        if(!reserved.admitted())throw new IllegalStateException("DATA_BUDGET_DENIED");
        var start=tasks.startIntent(c.scope(),c.taskId(),lease,reserved.outcome().revision(),operation,"runtime-data:"+operation);
        if(!start.dispatchIntentCreated())throw new IllegalStateException("DATA_OUTCOME_UNKNOWN: existing acquisition must be reconciled; query not replayed");
        Map<String,Object> parameters=json.convertValue(params,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        var roles=scopes.resolve(c.scope().tenantId(),c.scope().userId(),grant.skillId(),List.of(),List.of());
        if(!roles.skillAllowed())throw new SecurityException("Data Skill permission revoked");
        var acquisitionScope=new KernelDataScope(c.scope().tenantId(),c.scope().userId(),operation,c.scope().conversationId(),c.scope().runId(),c.scope().environment(),Map.of());
        var ctx=new AnalysisContext("Coding data requirement: "+request.path("reason").asText(),acquisitionScope,grant.skillId(),List.of(),List.of(),roles.roles(),null,
            Map.of(PreauthorizedStructuredDataOperator.TEMPLATE_ID,grant.templateId(),PreauthorizedStructuredDataOperator.ASSET_NAME,grant.assetName(),
                PreauthorizedStructuredDataOperator.ENVIRONMENT,grant.environment(),PreauthorizedStructuredDataOperator.PARAMETERS,parameters));
        ctx=ctx.withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_OPERATION,operation);
        // The existing operator rechecks live tool/Skill authorization. Node never receives a tool executor.
        var result=templates.execute(ctx,new AnalysisScope(c.scope().tenantId(),c.scope().userId(),roles.roles(),List.of(),Map.of()),null);
        if(result.evidence().size()!=1)throw new IllegalStateException("DATA_ACQUISITION_UNKNOWN_OR_FAILED: no verified source receipt");
        try {
            var fact=result.evidence().get(0);
            var payload=json.readTree(fact.content());
            if(!payload.path("data").path("complete").asBoolean(false))
                return failedReceipt(c,lease,request,digest,receiptId,"PARTIAL","Source returned incomplete data; no input revision admitted",fact.evidenceId(),hash(payload));
            List<Map<String,Object>> rows=json.convertValue(payload.path("data").path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            for(var row:rows)for(var field:request.path("requiredFields"))if(!row.containsKey(field.asText()))
                return failedReceipt(c,lease,request,digest,receiptId,"INVALID_DATA","Required field missing; no input revision admitted",fact.evidenceId(),hash(payload));
            String source="coding-data:"+operation+":"+hash(rows);
            var dataset=new Dataset(source,Map.of("sourceEvidenceId",fact.evidenceId(),"templateId",template,"parameters",parameters),rows);
            var snapshot=inputs.captureForTask(c,List.of(dataset),source,100,1024*1024);
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("taskId",c.taskId());response.put("operationId",operation);response.put("requestId",requestId);
            response.put("requestHash",digest);response.put("executionHandle","runtime-data:"+operation);response.put("executorStopped",true);
            response.put("authorizedTemplateId",template);
            response.put("snapshot",json.convertValue(snapshot,Object.class));response.put("inputRevision",current.size());
            response.put("sourceEvidenceId",fact.evidenceId());response.put("sourceVersion",hash(payload));response.put("parameterBindings",parameters);
            response.put("status",rows.isEmpty()?"EMPTY":"AVAILABLE");
            evidence.register(new EvidenceRecord(null,receiptId,c.scope(),"CODING_DATA_RECEIPT","CodingDataSupplyService",evidence.contentHash(response),response,null,System.currentTimeMillis(),Map.of()),
                new EvidenceLineage(null,receiptId,c.scope(),List.of(snapshot.reference().substring("runtime-evidence:".length())),c.taskId(),"CodingDataSupplyService",null,Map.of()));
            return settle(c,lease,operation,receiptId,response);
        }catch(java.io.IOException ex){throw new IllegalStateException("DATA_INVALID_RESPONSE",ex);}
    }
    private Map<String,Object> failedReceipt(CodingTaskContract c,CodingJournal.Lease lease,JsonNode request,String hash,String id,
        String status,String diagnostic,String sourceEvidence,String sourceVersion) {
        String operation=request.path("operationId").asText();
        Map<String,Object> response=Map.of("taskId",c.taskId(),"operationId",operation,"requestId",request.path("requestId").asText(),
            "requestHash",hash,"executionHandle","runtime-data:"+operation,"executorStopped",true,"status",status,
            "diagnostic",diagnostic,"sourceEvidenceId",sourceEvidence,"sourceVersion",sourceVersion);
        evidence.register(new EvidenceRecord(null,id,c.scope(),"CODING_DATA_RECEIPT","CodingDataSupplyService",evidence.contentHash(response),response,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,c.scope(),List.of(c.inputSnapshotRef().substring(17)),c.taskId(),"CodingDataSupplyService",operation,Map.of()));
        return settle(c,lease,operation,id,response);
    }
    public boolean recover(CodingTaskContract c,CodingJournal.Lease lease,String operation) {
        if(tasks.get(c.scope(),c.taskId()).executionControl().stopReason()==null)requireAuthorized(c);
        String id="coding:data:"+hash(Map.of("task",c.taskId(),"operation",operation));
        var receipt=evidence.find(c.scope(),id);
        if(receipt.isEmpty())return false;
        settle(c,lease,operation,id,receipt.get().payload());return true;
    }
    private Map<String,Object> settle(CodingTaskContract c,CodingJournal.Lease lease,String operation,String receipt,Map<String,Object> response) {
        // Reauthorization already happened, including receipt replay. A lost HTTP response does not reacquire data.
        var snapshot=json.convertValue(response.get("snapshot"),CodingInputSnapshotService.Snapshot.class);
        boolean stopping=tasks.get(c.scope(),c.taskId()).executionControl().stopReason()!=null;
        var previous=tasks.privateState(c.scope(),c.taskId(),"data-inputs");
        var all=new ArrayList<>(snapshots(c,null));
        if(!stopping&&snapshot!=null&&all.stream().noneMatch(s->s.reference().equals(snapshot.reference()))) {
            all.add(snapshot);tasks.privateState(c.scope(),c.taskId(),lease,"data-inputs",previous,json.valueToTree(all));
        }
        var state=tasks.get(c.scope(),c.taskId());
        if(tasks.verifiedOperation(c.scope(),c.taskId(),operation).isEmpty())state=tasks.reconcileStopped(c.scope(),c.taskId(),lease,state.revision(),operation,"runtime-evidence:"+receipt);
        tasks.settle(c.scope(),c.taskId(),lease,state.revision(),operation,new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0"),true);
        if(stopping)return Map.of("requestId",response.get("requestId"),"status","STOPPING","evidenceRef","runtime-evidence:"+receipt);
        if(snapshot==null)return Map.of("requestId",response.get("requestId"),"status",response.get("status"),"resultAvailable",false,
            "diagnostic",response.get("diagnostic"),"recoverable",false,"evidenceRef","runtime-evidence:"+receipt);
        return Map.of("requestId",response.get("requestId"),"status",response.get("status"),"inputRevision",response.get("inputRevision"),
            "descriptor",inputs.describe(c.scope(),c.taskId(),snapshot),"datasetReference",snapshot.sourceDatasetRef(),"evidenceRef","runtime-evidence:"+receipt);
    }
    private static String text(JsonNode value,String field,int limit){var v=value.path(field);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>limit)throw new IllegalArgumentException("Invalid "+field);return v.asText();}
    public String hash(Object value){try{
        Object canonical=value instanceof JsonNode?json.convertValue(value,Object.class):value;
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
    }catch(Exception ex){throw new IllegalArgumentException("Invalid data identity",ex);}}
}
