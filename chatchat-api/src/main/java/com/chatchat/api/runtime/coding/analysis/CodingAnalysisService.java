package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.analysis.graph.AnalysisCodingPort;
import com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.CodingParentModels;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingSchemaResultVerifier;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
    private CodingDataSupplyService dataSupply;
    private CodingAnalysisSqlService analysisSql;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAnalysisSql(CodingAnalysisSqlService value){this.analysisSql=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDataSupply(CodingDataSupplyService value){this.dataSupply=value;}
    private static final Set<CodingTaskAccess.Action> GRANTS=EnumSet.allOf(CodingTaskAccess.Action.class);

    public CodingAnalysisService(CodingAnalysisProperties policy, CodingInvocationContext identity,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans, CodingAgentSPI agent,
            DurableCodingTaskService tasks, CodingEvidenceCollector collector, RuntimeExecutionCheckpointPort checkpoints,
            CodingParentModels models, McpPythonControlPlanePort python, ObjectMapper json) {
        this.policy=policy;this.identity=identity;this.inputs=inputs;this.plans=plans;this.agent=agent;
        this.tasks=tasks;this.collector=collector;this.checkpoints=checkpoints;this.models=models;this.python=python;this.json=json;
        if (policy.getMaxConcurrentTasks()<1 || policy.getMaxConcurrentTasks()>32 || policy.getTimeoutSeconds()<1
                || policy.getTimeoutSeconds()>3600 || policy.getMaxInputBytes()>256L*1024*1024
                || policy.getMaxInputRows()>1_000_000)
            throw new IllegalArgumentException("Invalid Coding deployment resource limits");
        capacity=new Semaphore(policy.getMaxConcurrentTasks());
    }
    public Map<String,String> capabilities() {
        return Map.of("RUN_CODING", "Exact request: {operation:'RUN_CODING',requestId:string,datasetReference:string,"
            + "instruction:string,outputSchema:{type:'object',properties:{FIELD:{type:TYPE}},required:[FIELD],additionalProperties:false}}. "
            + "Optional datasetReferences:[string] selects multiple delivered datasets for joins; use it instead of datasetReference. "
            + "Choose your own fields and types. Delegate an iterative AI Coding analysis: plan, inspect schema/data quality, "
            + "calculate, inspect real execution receipts, repair technical faults, and produce structured results. "
            + "Supports cleansing diagnostics, aggregation, time series, portfolio/cash/returns, joins, statistics and chart data. "
            + "instruction describes the calculation and required output; the Coding worker writes rows.jsonl. "
            + "Design the objective and outputSchema for computed facts, derived text or chart data, not a raw-source round trip. "
            + "Source schema/profile is already available. Do not request source-row reproduction merely to inspect data. "
            + "A derived transformation may retain the source field names; unchanged source values are not analytical results. "
            + "outputSchema validates EACH JSONL ROW, never the containing array/file; its root type MUST be object. "
            + "Supported keywords ONLY: type/properties/required/items/enum/additionalProperties/description/title. "
            + "Do not use array as the root type, minItems, maxItems, minLength or other unsupported keywords. "
            + "A rejected request executed no code; inspect its reason and correct the contract before claiming a Coding result. "
            + "Runtime grants no new data access. "
            + "Use a stable requestId; changed content under the same ID conflicts. Results have a scoped datasetReference "
            + "and six evidence refs. Validation is structural only; business correctness is NOT_EVALUATED. "
            + "Only resultAvailable:true admits a dataset for analysis; failed/pending receipts include reasons and limitations. "
            + "Data-analysis calculations require Coding. Pending/unknown is not a result. Calls share one durable Coding budget per parent run.");
    }
    public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context, Map<String,Object> request,
                                     Map<String,Dataset> sources) {
        return executeObserved(context, request, sources, event -> {});
    }
    @Override public Map<String,Object> executeObserved(AnalysisCoverageCoordinator.Request context, Map<String,Object> request,
                                     Map<String,Dataset> sources, java.util.function.Consumer<Map<String,Object>> observer) {
        ensureActive(context);
        if (!request.keySet().stream().allMatch(Set.of("operation","requestId","datasetReference","datasetReferences","instruction","outputSchema")::contains))
            throw new IllegalArgumentException("Unknown Coding request field");
        if (!"RUN_CODING".equals(request.get("operation"))) throw new IllegalArgumentException("Unknown Coding operation");
        String requestId=text(request.get("requestId"),128), instruction=text(request.get("instruction"),16000);
        Dataset source=selectSources(request,sources);
        String reference=source.reference();
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
            return identity.as(scope,GRANTS,()->run(context,requestId,taskId,fingerprint,scope,source,instruction,rowSchema,sources,observer));
        } finally {capacity.release();}
    }
    private Map<String,Object> run(AnalysisCoverageCoordinator.Request context,String requestId,String taskId,
            String fingerprint,KernelDataScope scope,Dataset source,String instruction,Map<String,Object> schema,Map<String,Dataset> sources,
            java.util.function.Consumer<Map<String,Object>> observer) {
        String key="coding:analysis-request:"+taskId;
        var saved=checkpoints.readExecutionCheckpoint(scope,key);
        CodingTaskContract contract;
        if (saved.isPresent()) {
            var record=tree(saved.get());
            if (!fingerprint.equals(record.path("requestHash").asText())) throw new IllegalArgumentException("Coding request identity conflict");
            contract=convert(record.get("contract"),CodingTaskContract.class);
        } else {
            var budget=budget(context,scope);
            if (!Instant.now().isBefore(budget.deadlineAt())) {
                // An expired shared delegation budget cannot be renewed with another model-chosen request ID.
                var receipt=Map.<String,Object>of("operation","RUN_CODING","requestId",requestId,"taskId",taskId,
                    "status","RESOURCE_BUDGET_EXHAUSTED","reasonCode","DEADLINE_EXCEEDED",
                    "budgetExhausted",true,"retryable",false,"resultAvailable",false);
                context.metadata().put("codingLastReceipt",receipt);
                return receipt;
            }
            var env=python.environment(text(policy.getEnvironmentId(),256));
            if(env==null || !"PUBLISHED".equals(env.status()) || !"NONE".equals(env.networkPolicy()))
                throw new SecurityException("Coding requires a published network-disabled deployment environment");
            String decision="runtime-coding-delegation:"+taskId, workspace="runtime-coding-workspace:"+taskId;
            boolean chunked=policy.getMaxInputRows()>100_000||policy.getMaxInputBytes()>16L*1024*1024;
            var input=chunked?inputs.captureChunked(scope,taskId,List.of(source),source.reference(),decision,workspace,
                policy.getMaxInputRows(),policy.getMaxInputBytes(),()->ensureActive(context))
                :inputs.capture(scope,taskId,List.of(source),source.reference(),decision,workspace,
                    policy.getMaxInputRows(),policy.getMaxInputBytes());
            var plan=plans.register(scope,taskId,input,new CodingValidationPlanService.Plan("runtime-result-schema","v1",Map.of("rowSchema",schema)));
            String goal=instruction+"\nAuthorized immutable input descriptor: "+ModelProtocolJson.compact(inputs.describe(scope,taskId,input))
                +"\nUse the authorized snapshot. Inspect its columns in Python if necessary. "
                +"Write rows.jsonl matching this row schema: "+ModelProtocolJson.compact(schema)
                +". Plan the analysis, inspect data types, missing dates, duplicate keys and precision before calculating. "
                +"Never invent missing observations, trading calendars, business formulas, rounding policies or silently repair business data. "
                +"Never substitute a common default for unmeasured source configuration. Request authorized data or preserve the assumption explicitly in conditional estimates and affected result fields. "
                +"Unrecognized formats and failed parsing are not empty datasets. Fail explicitly rather than replacing missing fields with zero; "
                +"zero counts require recognized observations or an authorized manifest that proves an empty source. "
                +"For multi-dataset input, each JSONL row is {datasetReference,record}; join by explicit keys and report unmatched rows. "
                +"Dataset references in inputVersions are exact opaque keys, including all suffixes. Never replace them with guessed short labels. "
                +"Use each input version's fieldSchema to write the complete calculation; source samples and printed rows are not returned to the model. "
                +"Perform inspection, parsing and calculation inside the same Python program that writes the contracted result. "
                +"Standalone print probes and auxiliary inspection files cannot supply you with source previews on the next turn. "
                +"Write auxiliary diagnostics under /workspace/output/validation; only contracted analysis rows belong under /workspace/output/results. "
                +"Parse source documents and diagnostic text in Python into calculated metrics and concise findings. "
                +"Never copy raw source documents, logs or record collections into a result field; they are compared against the immutable input and rejected. Original text analysis may be long. "
                +"For charts produce chart-ready columns and explicit chart specification in the contracted row schema; never fabricate plotted values. "
                +"Use Decimal for exact money calculations when required. Inspect receipts and repair technical failures with a complete program. "
                +"Schema checks do not establish business correctness. Explain limitations in your summary.";
            boolean dataAvailable=dataSupply!=null&&!dataSupply.describe(scope).isEmpty();
            boolean sqlAvailable=analysisSql!=null&&analysisSql.available();
            boolean dynamic=dataAvailable||sqlAvailable||input.chunked();
            List<String> tools=new ArrayList<>(List.of("python_execute"));
            if(dataAvailable)tools.add("data_request");if(sqlAvailable)tools.add("sql_validate");
            if(sqlAvailable)goal+="\nFor SQL analysis, use sql_validate for static diagnostics; it never executes SQL. You may execute SQLite in-memory SQL over the delivered files inside python_execute. Never connect to external databases.";
            if(input.chunked())goal+="\nThe original input is a JSON manifest mounted at /data/input/"+input.fileId()
                +"/manifest.json. Its chunks list is ordered and contains only authorized fileId/fileName/hash/rowCount/size. "
                +"Read each /data/input/{fileId}/{fileName} sequentially in Python and verify each chunk SHA-256 and total rows; never treat the manifest itself as JSONL data.";
            if(dataAvailable)goal+="\nAuthorized Java data capabilities: "+ModelProtocolJson.compact(dataSupply.describe(scope))
                +". Use data_request only when delivered data is insufficient; do not query sources yourself. Each response is a new immutable input version mounted in subsequent Python calls.";
            contract=new CodingTaskContract(dynamic?CodingProtocol.ANALYSIS_VERSION:CodingProtocol.VERSION,taskId,scope,
                new CodingTaskContract.Objective(goal,List.of("rows.jsonl"),List.of(plan)),input.reference(),dataAvailable?List.of(CodingParentModels.REQUIRED_REF,dataSupply.policyRef(scope)):List.of(CodingParentModels.REQUIRED_REF),workspace,
                new CodingTaskContract.Authorization(decision,tools,CodingInputSnapshotService.mountedFiles(input)
                    .stream().map(CodingInputSnapshotService.Chunk::fileId).toList(),List.of(),List.of(),
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
            long traceCursor = 0;
            bound=true;
            outcome=agent.submit(accepted);
            if(saved.isPresent()&&!outcome.status().terminal())outcome=agent.resume(scope,taskId);
            while(!outcome.status().terminal() && Instant.now().isBefore(accepted.budget().deadlineAt())) {
                ensureActive(context);
                traceCursor = publishTrace(scope,taskId,traceCursor,observer);
                if(outcome.executionControl().reconciliationRequired())break;
                try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
                outcome=agent.get(scope,taskId);
            }
            if(!outcome.status().terminal()) {
                // Unknown work remains recoverable under the original task; a poll is not a cancellation.
                outcome=agent.resume(scope,taskId);
                if (!outcome.status().terminal() && !Instant.now().isBefore(accepted.budget().deadlineAt())) {
                    agent.cancel(scope,taskId,"Parent Coding wait ended; outstanding execution must be reconciled");
                    outcome=agent.resume(scope,taskId);
                }
            }
            ensureActive(context);
            publishTrace(scope,taskId,traceCursor,observer);
            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("operation","RUN_CODING");receipt.put("requestId",requestId);receipt.put("taskId",taskId);
            receipt.put("status",outcome.status().name());receipt.put("reconciliationRequired",outcome.executionControl().reconciliationRequired());
            receipt.put("validationStatus",outcome.validationStatus().name());receipt.put("businessValidation","NOT_EVALUATED");
            receipt.put("usage",outcome.usage());
            receipt.put("resultAvailable",false);
            if(outcome.reasonCode()!=null)receipt.put("reasonCode",outcome.reasonCode().name());
            if (outcome.reasonCode() == CodingErrorCode.OUTPUT_CONTRACT_VIOLATION) {
                receipt.put("replanningRequired", true);
                receipt.put("retryable", false);
                receipt.put("diagnostic", "Revise the analytical objective/outputSchema and submit a new requestId within the same shared budget. "
                    + "Request computed metrics, derived text or structural summaries; do not reproduce source rows for the model to analyze. "
                    + "Same-shape transformations remain valid when their values are actually derived.");
            }
            if(outcome.executionControl().stopReason()!=null)receipt.put("stopReason",outcome.executionControl().stopReason().name());
            if (outcome.reasonCode()==CodingErrorCode.DEADLINE_EXCEEDED || outcome.reasonCode()==CodingErrorCode.BUDGET_EXCEEDED
                    || outcome.executionControl().stopReason()==CodingErrorCode.DEADLINE_EXCEEDED
                    || outcome.executionControl().stopReason()==CodingErrorCode.BUDGET_EXCEEDED) {
                receipt.put("budgetExhausted",true);receipt.put("retryable",false);
            }
            if(outcome.summary()!=null)receipt.put("summary",outcome.summary());
            receipt.put("limitations",outcome.limitations());
            if(outcome.status()==CodingTaskOutcome.Status.COMPLETED && outcome.validationStatus()!=CodingTaskOutcome.ValidationStatus.FAILED) {
                var result=collector.registeredResult(scope,taskId,outcome.resultDatasetRef());
                var review = new CodingSemanticAdmission(json).review(context.model(), instruction,
                    collector.codeForReview(scope, taskId, outcome.codeArtifactRef()), outcome.summary(), result,
                    inputs.describe(scope, taskId, inputs.requireAuthorized(accepted)), scope, taskId, checkpoints,
                    () -> ensureActive(context));
                receipt.put("semanticAdmission", review);
                observer.accept(Map.of("eventKind", "CODING_SEMANTIC_REVIEW", "taskId", taskId,
                    "runId", scope.runId(), "eventState", review.get("status"), "review", review));
                if (!"SUPPORTED".equals(review.get("status"))) {
                    receipt.remove("summary");
                    receipt.put("outputStatus", "SEMANTIC_REVIEW_REQUIRES_REVISION");
                    receipt.put("diagnostic", "Execution artifacts remain archived. Revise the program to limit findings to measured evidence and submit a new requestId; no analytical dataset was admitted.");
                    context.metadata().put("codingLastReceipt", Map.copyOf(receipt));
                    return Collections.unmodifiableMap(receipt);
                }
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
    private long publishTrace(KernelDataScope scope, String taskId, long cursor,
            java.util.function.Consumer<Map<String,Object>> observer) {
        List<CodingExecutionEvent> page;
        do {
            page = tasks.events(scope, taskId, cursor, 64);
            for (var event : page) {
                var trace = new LinkedHashMap<String,Object>();
                trace.put("eventKind", "CODING_TRACE");
                trace.put("eventId", event.eventId());
                trace.put("taskId", taskId);
                trace.put("runId", scope.runId());
                trace.put("stepId", event.operationId() == null ? taskId : event.operationId());
                trace.put("sequence", event.sequence());
                trace.put("occurredAt", event.occurredAt().toString());
                trace.put("codingEventType", event.type().name());
                trace.put("eventState", event.status().name());
                trace.put("summary", event.summary());
                trace.put("evidenceRefs", event.evidenceRefs());
                observer.accept(Collections.unmodifiableMap(trace));
                cursor = event.sequence();
            }
        } while (page.size() == 64);
        return cursor;
    }
    private Dataset selectSources(Map<String,Object> request,Map<String,Dataset> sources) {
        if(!request.containsKey("datasetReferences")) {
            var selected=sources.get(text(request.get("datasetReference"),512));
            if(selected==null)throw new SecurityException("Coding input is not delivered in this analysis");
            return selected;
        }
        if(request.containsKey("datasetReference")||!(request.get("datasetReferences") instanceof List<?> refs)
            ||refs.isEmpty()||refs.size()>8||new HashSet<>(refs).size()!=refs.size())throw new IllegalArgumentException("Select 1..8 unique delivered datasets");
        List<Dataset> selectedSources=new ArrayList<>();List<Map<String,Object>> versions=new ArrayList<>();
        long total=0;
        for(Object ref:refs) {
            var selected=sources.get(text(ref,512));if(selected==null)throw new SecurityException("Coding input is not delivered in this analysis");
            if(!selected.handle().recordCountExact()||selected.recordCount()>policy.getMaxInputRows()-total)throw new IllegalArgumentException("Combined Coding input row limit");
            total+=selected.recordCount();selectedSources.add(selected);
            var fieldSchema=new TreeMap<String,java.util.Set<String>>();
            var schemaPage=selected.handle().readPage(0,1000);
            for(var row:schemaPage.rows())json.valueToTree(row).properties().forEach(field->{
                if(fieldSchema.size()<256 || fieldSchema.containsKey(field.getKey()))
                    fieldSchema.computeIfAbsent(field.getKey(),ignored->new java.util.TreeSet<>()).add(field.getValue().getNodeType().name());
            });
            versions.add(Map.of("datasetReference",selected.reference(),"contentSha256",selected.handle().contentSha256(),
                "rowCount",selected.recordCount(),"fieldSchema",fieldSchema,"schemaCoverage","BOUNDED_STRUCTURAL_ONLY"));
        }
        final long count=total;
        var handle=new com.chatchat.agents.orchestration.analysis.dataset.PagedDatasetHandle(count,true,(offset,limit)->{
            if(offset<0||limit<1)throw new IllegalArgumentException("Invalid combined page");
            var rows=new ArrayList<Map<String,Object>>();long base=0;
            for(var selected:selectedSources) {
                long end=base+selected.recordCount();
                if(offset+rows.size()<end && rows.size()<limit) {
                    long local=Math.max(0,offset+rows.size()-base);
                    while(local<selected.recordCount()&&rows.size()<limit) {
                        var page=selected.handle().readPage(local,Math.min(limit-rows.size(),1000));
                        if(page.rows().isEmpty())throw new IllegalStateException("Combined source changed during page read");
                        for(var row:page.rows())rows.add(Map.of("datasetReference",selected.reference(),"record",row));
                        local+=page.rows().size();
                    }
                }
                base=end;
            }
            return new com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle.Page(offset,rows,offset+rows.size()<count);
        },null,()->{},Map.of("kind","CODING_INPUT_SET","sourceCount",selectedSources.size()));
        return new Dataset("coding-input-set:"+hash(versions),Map.of("inputVersions",List.copyOf(versions)),handle);
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
