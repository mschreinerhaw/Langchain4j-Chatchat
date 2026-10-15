package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.analysis.graph.AnalysisCodingPort;
import com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Calculates only on the current authenticated analysis's delivered data. No asset/template acquisition. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.analysis",name="enabled",havingValue="true")
public final class CodingAnalysisService implements AnalysisCodingPort {
    private final CodingAnalysisProperties policy;
    private final CodingInvocationContext identity;
    private final CodingInputSnapshotService inputs;
    private final CodingValidationPlanService plans;
    private final CodingAgentSPI agent;
    private final DurableCodingTaskService tasks;
    private final CodingEvidenceCollector collector;
    private final RuntimeExecutionCheckpointPort checkpoints;
    private final CodingParentModels models;
    private final McpPythonControlPlanePort python;
    private final ObjectMapper json;
    private final Semaphore capacity;
    private static final Set<CodingTaskAccess.Action> GRANTS=EnumSet.allOf(CodingTaskAccess.Action.class);

    public CodingAnalysisService(CodingAnalysisProperties policy, CodingInvocationContext identity,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans, CodingAgentSPI agent,
            DurableCodingTaskService tasks, CodingEvidenceCollector collector, RuntimeExecutionCheckpointPort checkpoints,
            CodingParentModels models, McpPythonControlPlanePort python, ObjectMapper json) {
        this.policy=policy;this.identity=identity;this.inputs=inputs;this.plans=plans;this.agent=agent;
        this.tasks=tasks;this.collector=collector;this.checkpoints=checkpoints;this.models=models;this.python=python;this.json=json;
        if (policy.getMaxConcurrentTasks()<1 || policy.getMaxConcurrentTasks()>32 || policy.getTimeoutSeconds()<1
                || policy.getTimeoutSeconds()>3600 || policy.getMaxInputBytes()>64L*1024*1024)
            throw new IllegalArgumentException("Invalid Coding deployment resource limits");
        capacity=new Semaphore(policy.getMaxConcurrentTasks());
    }
    public Map<String,String> capabilities() {
        return Map.of("RUN_CODING", "Exact request: {operation:'RUN_CODING',requestId:string,datasetReference:string,"
            + "instruction:string,outputSchema:{type:'object',properties:{FIELD:{type:TYPE}},required:[FIELD],additionalProperties:false}}. "
            + "Choose your own fields and types. Delegate Python on exactly one delivered immutable dataset. "
            + "instruction describes the calculation and required output; the Coding worker writes rows.jsonl. "
            + "outputSchema validates EACH JSONL ROW, never the containing array/file; its root type MUST be object. "
            + "Supported keywords ONLY: type/properties/required/items/enum/additionalProperties/description/title. "
            + "Do not use array as the root type, minItems, maxItems, minLength or other unsupported keywords. "
            + "A rejected request executed no code; inspect its reason and correct the contract before claiming a Coding result. "
            + "Runtime grants no new data access. "
            + "Use a stable requestId; changed content under the same ID conflicts. Results have a scoped datasetReference "
            + "and six evidence refs. Validation is structural only; business correctness is NOT_EVALUATED. "
            + "Only resultAvailable:true admits a dataset for analysis; failed/pending receipts include reasons and limitations. "
            + "You may analyze without Coding. Pending/unknown is not a result. Calls share one durable Coding budget per parent run.");
    }
    public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context, Map<String,Object> request,
                                     Map<String,Dataset> sources) {
        ensureActive(context);
        if (!request.keySet().stream().allMatch(Set.of("operation","requestId","datasetReference","instruction","outputSchema")::contains))
            throw new IllegalArgumentException("Unknown Coding request field");
        if (!"RUN_CODING".equals(request.get("operation"))) throw new IllegalArgumentException("Unknown Coding operation");
        String requestId=text(request.get("requestId"),128), instruction=text(request.get("instruction"),16000);
        String reference=text(request.get("datasetReference"),512);
        Dataset source=sources.get(reference);
        if (source==null) throw new SecurityException("Coding input is not delivered in this analysis");
        var rowSchema=WorkspaceResultSchema.object(request.get("outputSchema"));
        new CodingSchemaResultVerifier().validateRules(Map.of("rowSchema",rowSchema));
        var owner=context.isolationScope();
        for(String part:List.of(owner.tenantId(),owner.userId(),owner.runId()))
            if (part.isBlank() || Set.of("anonymous","default","unknown-request").contains(part))
                throw new SecurityException("Authenticated Runtime run identity is required for Coding");
        var scope=new KernelDataScope(owner.tenantId(),owner.userId(),owner.requestId(),owner.conversationId(),owner.runId(),null,Map.of());
        String taskId="analysis-coding-"+hash(Map.of("tenant",owner.tenantId(),"user",owner.userId(),"run",owner.runId(),"request",requestId));
        String fingerprint=hash(Map.of("instruction",instruction,"source",reference,
            "sourceHash",source.handle().contentSha256(),"schema",rowSchema));
        if (!capacity.tryAcquire()) return Map.of("operation","RUN_CODING","requestId",requestId,"taskId",taskId,
            "status","CAPACITY_UNAVAILABLE","resultAvailable",false);
        try {
            return identity.as(scope,GRANTS,()->run(context,requestId,taskId,fingerprint,scope,source,instruction,rowSchema,sources));
        } finally {capacity.release();}
    }
    private Map<String,Object> run(AnalysisCoverageCoordinator.Request context,String requestId,String taskId,
            String fingerprint,KernelDataScope scope,Dataset source,String instruction,Map<String,Object> schema,Map<String,Dataset> sources) {
        String key="coding:analysis-request:"+taskId;
        var saved=checkpoints.readExecutionCheckpoint(scope,key);
        CodingTaskContract contract;
        if (saved.isPresent()) {
            var record=tree(saved.get());
            if (!fingerprint.equals(record.path("requestHash").asText())) throw new IllegalArgumentException("Coding request identity conflict");
            contract=convert(record.get("contract"),CodingTaskContract.class);
        } else {
            var budget=budget(context,scope);
            var env=python.environment(text(policy.getEnvironmentId(),256));
            if(env==null || !"PUBLISHED".equals(env.status()) || !"NONE".equals(env.networkPolicy()))
                throw new SecurityException("Coding requires a published network-disabled deployment environment");
            String decision="runtime-coding-delegation:"+taskId, workspace="runtime-coding-workspace:"+taskId;
            var input=inputs.capture(scope,taskId,List.of(source),source.reference(),decision,workspace,
                policy.getMaxInputRows(),policy.getMaxInputBytes());
            var plan=plans.register(scope,taskId,input,new CodingValidationPlanService.Plan("runtime-result-schema","v1",Map.of("rowSchema",schema)));
            String goal=instruction+"\nAuthorized immutable input descriptor: "+ModelProtocolJson.compact(inputs.describe(scope,taskId,input))
                +"\nUse the authorized snapshot. Inspect its columns in Python if necessary. "
                +"Write rows.jsonl matching this row schema: "+ModelProtocolJson.compact(schema)
                +". Schema checks do not establish business correctness. Explain limitations in your summary.";
            contract=new CodingTaskContract(CodingProtocol.VERSION,taskId,scope,
                new CodingTaskContract.Objective(goal,List.of("rows.jsonl"),List.of(plan)),input.reference(),List.of(CodingParentModels.REQUIRED_REF),workspace,
                new CodingTaskContract.Authorization(decision,List.of("python_execute"),List.of(input.fileId()),List.of(),List.of(),
                    CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),budget,
                policy.getEnvironmentId(),plan,new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                    "runtime-coding-schema:"+hash(schema),"runtime-coding-structural.v1",List.of("rows.jsonl"),
                    List.of("application/x-ndjson"),policy.getMaxResultRows(),policy.getMaxResultBytes(),List.of(CodingTaskContract.RequiredEvidence.values())));
            String encoded=encode(Map.of("requestHash",fingerprint,"contract",contract));
            if(!checkpoints.compareAndSetExecutionCheckpoint(scope,key,null,encoded)) {
                var existing=tree(checkpoints.readExecutionCheckpoint(scope,key).orElseThrow());
                if(!fingerprint.equals(existing.path("requestHash").asText()))throw new IllegalArgumentException("Concurrent Coding request conflict");
                contract=convert(existing.get("contract"),CodingTaskContract.class);
            }
        }
        CodingTaskContract accepted=contract;
        ensureActive(context);
        CodingTaskOutcome outcome=null;
        boolean bound=false;
        try(var binding=models.bind(scope,taskId,context.model())) {
            bound=true;
            outcome=agent.submit(accepted);
            while(!outcome.status().terminal() && Instant.now().isBefore(accepted.budget().deadlineAt())) {
                ensureActive(context);
                if(outcome.executionControl().reconciliationRequired())break;
                try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
                outcome=agent.get(scope,taskId);
            }
            if(!outcome.status().terminal()) {
                // Let A2 record an expired deadline before cancellation can claim the stop reason.
                if (!Instant.now().isBefore(accepted.budget().deadlineAt())) outcome=agent.resume(scope,taskId);
                if (!outcome.status().terminal()) {
                    agent.cancel(scope,taskId,"Parent Coding wait ended; outstanding execution must be reconciled");
                    outcome=agent.resume(scope,taskId);
                }
            }
            ensureActive(context);
            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("operation","RUN_CODING");receipt.put("requestId",requestId);receipt.put("taskId",taskId);
            receipt.put("status",outcome.status().name());receipt.put("reconciliationRequired",outcome.executionControl().reconciliationRequired());
            receipt.put("validationStatus",outcome.validationStatus().name());receipt.put("businessValidation","NOT_EVALUATED");
            receipt.put("usage",outcome.usage());
            receipt.put("resultAvailable",false);
            if(outcome.reasonCode()!=null)receipt.put("reasonCode",outcome.reasonCode().name());
            if(outcome.executionControl().stopReason()!=null)receipt.put("stopReason",outcome.executionControl().stopReason().name());
            if(outcome.summary()!=null)receipt.put("summary",outcome.summary());
            receipt.put("limitations",outcome.limitations());
            if(outcome.status()==CodingTaskOutcome.Status.COMPLETED && outcome.validationStatus()!=CodingTaskOutcome.ValidationStatus.FAILED) {
                var result=collector.registeredResult(scope,taskId,outcome.resultDatasetRef());
                var refs=List.of(outcome.inputSnapshotRef(),outcome.codeArtifactRef(),outcome.executionReceiptRef(),
                    outcome.resultDatasetRef(),outcome.validationRecordRef(),outcome.evidenceBundleRef());
                var resultContext=new LinkedHashMap<String,Object>(result.analysisContext());
                resultContext.put("coding",Map.of("taskId",taskId,"inputDatasetReference",source.reference(),
                    "status","COMPLETED","resultAvailable",true,"validationStatus",outcome.validationStatus().name(),
                    "businessValidation","NOT_EVALUATED","limitations",outcome.limitations(),"evidenceRefs",refs));
                sources.put(result.reference(),new Dataset(result.reference(),resultContext,result.handle()));
                receipt.put("resultAvailable",true);
                receipt.put("datasetReference",result.reference());receipt.put("rowCount",result.recordCount());
                receipt.put("evidenceRefs",refs);
            }
            context.metadata().put("codingLastReceipt",Map.copyOf(receipt));
            return Collections.unmodifiableMap(receipt);
        } catch(RuntimeException failure) {
            if(bound && (outcome==null || !outcome.status().terminal()))
                try{agent.cancel(scope,taskId,"Parent Coding invocation interrupted");}catch(RuntimeException ignored){}
            throw failure;
        } catch(Exception failure){throw new IllegalStateException("Coding model binding failed",failure);}
    }
    private static void ensureActive(AnalysisCoverageCoordinator.Request context) {
        context.cancellationGuard().run();
        if(context.cancellationCheck().getAsBoolean())throw new java.util.concurrent.CancellationException("Parent analysis cancelled");
        Object deadline=context.runtimeAttributes().get("__agentDeadlineAt");
        if(deadline instanceof Number number && number.longValue()>0 && System.currentTimeMillis()>=number.longValue())
            throw new com.chatchat.agents.orchestration.model.AgentDeadlineExceededException("Parent analysis deadline reached");
    }
    private CodingTaskContract.Budget budget(AnalysisCoverageCoordinator.Request context,KernelDataScope scope) {
        String key="coding:analysis-budget.v1";
        var prior=checkpoints.readExecutionCheckpoint(scope,key);
        if(prior.isPresent())return convert(tree(prior.get()),CodingTaskContract.Budget.class);
        Instant deadline=Instant.now().plusSeconds(policy.getTimeoutSeconds()).truncatedTo(ChronoUnit.MILLIS);
        Object parentDeadline=context.runtimeAttributes().get("__agentDeadlineAt");
        if(parentDeadline instanceof Number number && number.longValue()>0) {
            Instant parent=Instant.ofEpochMilli(number.longValue());if(parent.isBefore(deadline))deadline=parent;
        }
        var budget=new CodingTaskContract.Budget("analysis-coding-budget:"+scope.runId(),deadline,
            policy.getMaxModelCalls(),policy.getMaxModelTokens(),policy.getMaxToolCalls(),policy.getMaxCodeExecutions(),
            policy.getMaxCostAmount(),policy.getCostCurrency(),policy.getMaxCpuMillis(),policy.getMaxMemoryBytes(),policy.getMaxResultBytes());
        String encoded=encode(budget);
        if(checkpoints.compareAndSetExecutionCheckpoint(scope,key,null,encoded))return budget;
        return convert(tree(checkpoints.readExecutionCheckpoint(scope,key).orElseThrow()),CodingTaskContract.Budget.class);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Coding checkpoint invalid",e);}}
    private String hash(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
            json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value))); }
        catch(Exception invalid){throw new IllegalArgumentException("Coding identity cannot be encoded",invalid);}
    }
    private com.fasterxml.jackson.databind.JsonNode tree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Coding checkpoint invalid",e);}}
    private <T>T convert(Object value,Class<T> type){return json.convertValue(value,type);}
    private static String text(Object value,int max){if(!(value instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException("Bounded Coding parameter required");return s;}
}
