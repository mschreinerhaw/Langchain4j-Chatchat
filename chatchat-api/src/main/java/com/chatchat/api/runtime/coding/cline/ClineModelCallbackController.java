package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.CodingTaskRevisions;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisSqlService;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** First D1 callback slice: durable model admission and receipt reconciliation. */
@RestController
@RequestMapping("/internal/coding/cline")
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class ClineModelCallbackController {
    private static final java.util.concurrent.ScheduledExecutorService MODEL_LEASE_RENEWALS =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
            var thread = new Thread(work, "coding-model-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    private java.util.concurrent.ScheduledExecutorService modelLeaseRenewals = MODEL_LEASE_RENEWALS;
    void setModelLeaseRenewals(java.util.concurrent.ScheduledExecutorService scheduler) {
        modelLeaseRenewals = scheduler;
    }
    private static final Logger LOG = LoggerFactory.getLogger(ClineModelCallbackController.class);
    private static final Set<CodingTaskAccess.Action> GRANTS = EnumSet.of(
        CodingTaskAccess.Action.READ, CodingTaskAccess.Action.WORKER, CodingTaskAccess.Action.EVIDENCE, CodingTaskAccess.Action.SUBMIT);
    private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;
    private final ClineRuntimeModelBridge model;
    private final ClineModelReceiptStore receipts;
    private final CodingInputSnapshotService inputs;
    private final CodingValidationPlanService plans;
    private final CodingEvidenceCollector collector;
    private final ObjectMapper mapper;
    private final Map<String, Instant> freshDispatches = new ConcurrentHashMap<>();
    private com.chatchat.api.runtime.coding.nativecode.NativeCodingWorkspaceService nativeWorkspace;
    private com.chatchat.api.runtime.coding.nativecode.NativeCodingCommandService nativeCommands;
    private com.chatchat.api.runtime.coding.nativecode.NativeCodingTaskService nativeTasks;
    private com.chatchat.api.runtime.coding.nativecode.NativeCodingMcpService nativeMcp;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNativeMcp(com.chatchat.api.runtime.coding.nativecode.NativeCodingMcpService value){nativeMcp=value;}
    @PostMapping("/native-mcp-list") public Map<String,Object> nativeMcpList(@RequestBody JsonNode body){return bound(body,call->{
        nativeWorkspace(body,call);if(nativeMcp==null)throw new SecurityException("Native MCP unavailable");
        return Map.of("tools",nativeMcp.list(contract(body,call),call.lease(),body.path("serverName").asText()));
    });}
    @PostMapping("/native-mcp-call") public Map<String,Object> nativeMcpCall(@RequestBody JsonNode body){return bound(body,call->{
        nativeWorkspace(body,call);if(nativeMcp==null)throw new SecurityException("Native MCP unavailable");
        var request=body.path("request");
        var result=nativeMcp.call(contract(body,call),call.lease(),request.path("serverName").asText(),request.path("toolName").asText(),
            request.path("toolCallId").asText(),nativeMap(request.path("arguments")));
        assertStillOwned(call);return Map.of("result",result);
    });}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNativeWorkspace(com.chatchat.api.runtime.coding.nativecode.NativeCodingWorkspaceService value){nativeWorkspace=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNativeCommands(com.chatchat.api.runtime.coding.nativecode.NativeCodingCommandService value){nativeCommands=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNativeTasks(com.chatchat.api.runtime.coding.nativecode.NativeCodingTaskService value){nativeTasks=value;}

    @PostMapping("/native-open") public Map<String,Object> nativeOpen(@RequestBody JsonNode body){return bound(body,call->nativeWorkspace(body,call).open(contract(body,call),call.lease()));}
    @PostMapping("/native-commit") public Map<String,Object> nativeCommit(@RequestBody JsonNode body){return bound(body,call->nativeWorkspace(body,call).commit(contract(body,call),call.lease(),nativeMap(body.path("proposal"))));}
    @PostMapping("/native-authorize") public Map<String,Object> nativeAuthorize(@RequestBody JsonNode body){return bound(body,call->nativeWorkspace(body,call).authorize(contract(body,call),call.lease(),nativeMap(body.path("admission"))));}
    @PostMapping("/native-replay") public Map<String,Object> nativeReplay(@RequestBody JsonNode body){return bound(body,call->nativeWorkspace(body,call).replay(contract(body,call),body.path("lookup").path("toolCallId").asText()));}
    @PostMapping("/native-tool-finish") public Map<String,Object> nativeToolFinish(@RequestBody JsonNode body){return bound(body,false,true,call->nativeWorkspace(body,call).finishTool(contract(body,call),call.lease(),body.path("receipt").path("toolCallId").asText(),nativeMap(body.path("receipt").path("result"))));}
    @PostMapping("/native-command") public Map<String,Object> nativeCommand(@RequestBody JsonNode body){return bound(body,call->{nativeWorkspace(body,call);if(nativeCommands==null)throw new SecurityException("Native commands unavailable");return nativeCommands.execute(contract(body,call),call.lease(),nativeMap(body.path("command")));});}
    @PostMapping("/native-command-cancel") public Map<String,Object> nativeCancel(@RequestBody JsonNode body){return bound(body,false,true,call->{nativeWorkspace(body,call);if(nativeCommands==null)throw new SecurityException("Native commands unavailable");return nativeCommands.cancel(contract(body,call));});}
    private com.chatchat.api.runtime.coding.nativecode.NativeCodingWorkspaceService nativeWorkspace(JsonNode body,Call call){
        if(nativeWorkspace==null)throw new SecurityException("Native Coding is disabled");nativeWorkspace.requireAuthorized(contract(body,call));return nativeWorkspace;
    }
    private Map<String,Object> nativeMap(JsonNode value){if(!value.isObject())throw new IllegalArgumentException("Native callback object required");return mapper.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});}
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.completion-reserve.minimum-ratio:0.15}")
    private double completionMinimumRatio = 0.15;
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.completion-reserve.maximum-ratio:0.35}")
    private double completionMaximumRatio = 0.35;

    @PostMapping("/budget")
    public Map<String,Object> budget(@RequestBody JsonNode body) {
        return bound(body,call->{
            var contract=contract(body,call);
            var estimate=body.path("contextTokensEstimate");
            if(!estimate.isIntegralNumber()||!estimate.canConvertToLong()||estimate.asLong()<0)
                throw new IllegalArgumentException("Nonnegative context token estimate required");
            var snapshot=new java.util.LinkedHashMap<String,Object>(tasks.budgetSnapshot(call.scope(),call.taskId(),estimate.asLong(),
                new com.chatchat.agents.runtime.coding.CodingCompletionBudget(completionMinimumRatio,completionMaximumRatio)));
            snapshot.put("modelContext",model.contextPolicy(contract));
            return snapshot;
        });
    }
    private CodingWorkspaceService workspace;
    private CodingDataSupplyService dataSupply;
    private CodingAnalysisSqlService analysisSql;
    private com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService knowledge;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setKnowledge(com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService value){knowledge=value;}
    @PostMapping("/knowledge")
    public Map<String,Object> knowledge(@RequestBody JsonNode body) {
        return bound(body,call->{
            if(knowledge==null)throw new SecurityException("Knowledge tools unavailable");
            Map<String,Object> result;
            try {result=knowledge.call(contract(body,call,false),call.lease(),body.path("tool").asText(),
                body.path("toolCallId").asText(),body.path("arguments"));}
            catch(SecurityException denied) {
                String reason=Objects.toString(denied.getMessage(),"");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    reason.equals("Resource source changed; start a new task to acquire current evidence")
                        ?"SOURCE_CHANGED: reacquire evidence in a new task":"ACCESS_DENIED: resource access rejected; not an empty query result",denied);
            }
            catch(IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNKNOWN: invalid resource query parameters; correct the tool arguments",invalid);
            }
            assertStillOwned(call);return result;
        });
    }
    private com.chatchat.api.runtime.coding.CodingSkillReader skillReader;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setSkillReader(com.chatchat.api.runtime.coding.CodingSkillReader value) { this.skillReader=value; }
    @PostMapping("/read-skill")
    public Map<String,Object> readSkill(@RequestBody JsonNode body) {
        return bound(body,call -> {
            if (skillReader==null) throw new SecurityException("Skill reader unavailable");
            var result=skillReader.read(contract(body,call),call.lease(),body.path("skillId").asText(""),body.path("path").asText(""));
            assertStillOwned(call);
            return result;
        });
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAnalysisSql(CodingAnalysisSqlService value){this.analysisSql=value;}
    @PostMapping("/validate-sql")
    public Map<String,Object> validateSql(@RequestBody JsonNode body) {
        return bound(body,call->{
            if(analysisSql==null)throw new SecurityException("Analysis SQL validator unavailable");
            var request=body.path("validation");
            return analysisSql.validate(contract(body,call),call.lease(),request.path("operationId").asText(),request.path("dialect").asText(),request.path("sql").asText());
        });
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDataSupply(CodingDataSupplyService service) {this.dataSupply=service;}
    @PostMapping("/data-request")
    public Map<String,Object> dataRequest(@RequestBody JsonNode body) {
        return bound(body, call -> {
            var c=contract(body,call);
            if(dataSupply==null||!c.authorization().allowedTools().contains("data_request"))throw new SecurityException("Data supply unavailable");
            return dataSupply.request(c,call.lease(),body.path("requirement"));
        });
    }
    /** Context preparation, not a model-selected tool or data acquisition operation. */
    @PostMapping("/input-context")
    public Map<String,Object> inputContext(@RequestBody JsonNode body) {
        return bound(body,call->{
            var c=contract(body,call);
            if(!c.runtimeContext().containsKey("inputFiles"))throw new SecurityException("Versioned file context was not authorized");
            var input=inputs.requireAuthorized(c);
            var description=inputs.describe(call.scope(),call.taskId(),input);
            var response=new java.util.LinkedHashMap<String,Object>();
            for(String key:List.of("fileId","sha256","rowCount","format","inputManifest","inputVersions","nativeFileSchemas"))
                if(description.containsKey(key))response.put(key,description.get(key));
            response.put("files",CodingInputSnapshotService.mountedFiles(input).stream().map(file->Map.of(
                "fileId",file.fileId(),"fileName",file.fileName(),"sha256",file.sha256(),"rowCount",file.rowCount(),"size",file.size(),
                "virtualPath","/data/input/"+file.fileId()+"/"+file.fileName())).toList());
            response.put("fileLayout", CodingInputLayout.describe(c.runtimeContext()));
            var resultSchema=plans.rowSchema(c);
            if(!resultSchema.isEmpty())response.put("outputContract",Map.of("rowSchema",resultSchema,
                "requiredFiles",c.outputContract().requiredFiles(),"authority","RUNTIME_RESULT_CONTRACT",
                "reportDeclarationConsistency",com.chatchat.api.runtime.coding.evidence.CodingSchemaResultVerifier.REPORT_SCOPE_RULE));
            if(c.metadataRefs().contains(com.chatchat.api.runtime.coding.data.CodingInputObservation.POLICY_REF))
                response.put("analysisPreview",inputs.observeForCoding(c));
            if(mapper.valueToTree(response).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180000)
                throw new IllegalArgumentException("Input metadata exceeds the bounded Coding context; narrower authorized projection required");
            return response;
        });
    }

    @PostMapping("/input-read")
    public Map<String,Object> readInput(@RequestBody JsonNode body) {
        return bound(body,call->{
            var request=body.path("read");
            if(!request.isObject() || request.size()!=4 || !request.path("datasetReference").isTextual()
                    || !request.path("toolCallId").isTextual()
                    || !request.path("offset").canConvertToInt() || !request.path("limit").canConvertToInt()
                    || !request.path("offset").isIntegralNumber() || !request.path("limit").isIntegralNumber())
                throw new IllegalArgumentException("Exact bounded input reference, offset and limit are required");
            var c=contract(body,call);
            var result=dataSupply==null
                ? inputs.readForCoding(c,call.lease(),request.path("toolCallId").asText(),request.path("datasetReference").asText(),request.path("offset").asInt(),request.path("limit").asInt())
                : dataSupply.readInput(c,call.lease(),request.path("toolCallId").asText(),request.path("datasetReference").asText(),request.path("offset").asInt(),request.path("limit").asInt());
            assertStillOwned(call);
            return result;
        });
    }

    @PostMapping("/submit-report")
    public Map<String,Object> submitReport(@RequestBody JsonNode body) {
        return bound(body,call->{
            var c=contract(body,call);var submission=body.path("submission");
            if(!c.permitsTextResult() || !c.authorization().allowedTools().contains("report_submit")
                    || !tasks.startedCodeOperations(call.scope(),call.taskId()).isEmpty())
                throw new SecurityException("This task requires actual execution evidence");
            if(!submission.isObject() || submission.size()!=2 || !submission.path("toolCallId").isTextual()
                    || !submission.path("report").isObject())throw new IllegalArgumentException("Exact report submission required");
            String toolId=submission.path("toolCallId").asText();
            if(toolId.isBlank()||toolId.length()>512)throw new IllegalArgumentException("Bounded tool call identity required");
            String operation="workspace-"+sha(call.taskId()+"\0report_submit\0"+toolId);
            var report=mapper.convertValue(submission.path("report"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            long bytes=mapper.valueToTree(report).toString().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>Math.min(128*1024,c.outputContract().maxResultBytes()))throw new IllegalArgumentException("Report exceeds output budget");
            var frozen=tasks.privateState(call.scope(),call.taskId(),"text-result-submission");
            if(frozen!=null && !operation.equals(frozen.path("operationId").asText()))throw new IllegalStateException("A final text result was already submitted");
            String fingerprint=sha(c.inputSnapshotRef()+"\0"+submission.path("report").toString());
            var admitted=CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                tasks.reserve(call.scope(),call.taskId(),call.lease(),revision,operation,fingerprint,
                    CodingJournal.OperationKind.TOOL,new CodingTaskOutcome.Counters(0,0,1,0,0,0,bytes+4096,"0")));
            if(!admitted.admitted())throw new IllegalStateException("Report tool budget denied");
            CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                tasks.startIntent(call.scope(),call.taskId(),call.lease(),revision,operation,"runtime-workspace:"+operation));
            var input=inputs.requireAuthorized(c);var descriptor=inputs.describe(call.scope(),call.taskId(),input);
            var allowed=new java.util.HashSet<String>();
            if(descriptor.get("inputVersions") instanceof List<?> versions)for(Object version:versions)
                if(version instanceof Map<?,?> fields && fields.get("datasetReference") instanceof String ref)allowed.add(ref);
            if(allowed.isEmpty())allowed.add(input.sourceDatasetRef());
            CodingEvidenceCollector.Collected collected;
            try {
                if(com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.enabled(c)) {
                    if(knowledge==null)throw new SecurityException("Knowledge validation unavailable");
                    allowed.addAll(knowledge.authorizedReferences(c));
                    knowledge.validateReport(c,report);
                }
                collected=collector.collectText(c,operation,input,report,allowed,plans.validator(c));
            }
            catch(RuntimeException invalid) {
                // No external executor ran. Archive a truthful stopped local-tool receipt so
                // schema/reference repairs consume budget without leaving an unknown operation.
                var record=collector.textSubmissionFailure(c,operation);
                CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                    tasks.reconcileStopped(call.scope(),call.taskId(),call.lease(),revision,operation,record));
                CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                    tasks.settle(call.scope(),call.taskId(),call.lease(),revision,operation,new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0"),true));
                if(invalid instanceof com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.ReportReferenceException)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage(),invalid);
                if(com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.enabled(c)
                        && invalid instanceof IllegalArgumentException
                        && "Text result does not match the existing row schema".equals(invalid.getMessage()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Report structural validation failed. "
                            + com.chatchat.api.runtime.coding.evidence.CodingSchemaResultVerifier.REPORT_SCOPE_RULE
                            + " Use real JSON arrays for limitations, evidenceReferences and fieldProvenance. Required row schema: "
                            + mapper.valueToTree(plans.rowSchema(c)),invalid);
                throw invalid;
            }
            CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                tasks.reconcileStopped(call.scope(),call.taskId(),call.lease(),revision,operation,collected.executionReceiptRef()));
            CodingTaskRevisions.mutate(tasks,call.scope(),call.taskId(),revision->
                tasks.settle(call.scope(),call.taskId(),call.lease(),revision,operation,new CodingTaskOutcome.Counters(0,0,1,0,0,0,bytes,"0"),true));
            tasks.privateState(call.scope(),call.taskId(),call.lease(),"text-result-submission",null,mapper.valueToTree(Map.of("operationId",operation)));
            assertStillOwned(call);
            return Map.of("operationId",operation,"status","SUCCEEDED","executorStopped",true,"pythonExecuted",false,
                "executionMode","TEXT_ONLY","resultAvailable",true,"resultDatasetRef",collected.resultDatasetRef(),
                "validationRecordRef",collected.validationRecordRef(),"evidenceBundleRef",collected.evidenceBundleRef(),"validationStatus","INCONCLUSIVE");
        });
    }

    /** Reads only an immutable structured result produced by this Coding task. */
    @PostMapping("/read-artifact")
    public Map<String,Object> readArtifact(@RequestBody JsonNode body) {
        return bound(body, call -> {
            var contract=contract(body,call);
            if(!CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
                    || !contract.authorization().allowedTools().contains("artifact_read"))
                throw new SecurityException("Artifact read is outside the Runtime authorization");
            String reference=body.path("reference").asText("");
            if(!reference.startsWith("runtime-evidence:") || reference.length()>256)
                throw new IllegalArgumentException("A bounded Runtime evidence reference is required");
            var page=collector.readResultPage(call.scope(),call.taskId(),reference,0,2);
            if(page.rows().size()!=1 || page.hasMore())
                throw new IllegalStateException("Analysis review requires exactly one complete report row");
            var response=new java.util.LinkedHashMap<String,Object>();
            response.put("reference",reference);response.put("rows",page.rows());response.put("complete",true);
            if(com.chatchat.common.runtime.coding.VisualReportMode.active(contract.runtimeContext()))collector.visualReport(call.scope(),call.taskId(),reference).ifPresent(report->
                response.put("visualReportValidation",Map.of("status",report.getOrDefault("status","FAILED"),"validation",report.getOrDefault("validation",Map.of()),
                    "artifactValidationStatus",report.getOrDefault("artifactValidationStatus","NOT_RECORDED"),
                    "layoutValidationStatus",report.getOrDefault("layoutValidationStatus","NOT_RECORDED"),
                    "chartValidationStatus",com.chatchat.api.runtime.coding.evidence.VisualReportArtifacts.chartValidationStatus(report),
                    "availableChartCount",com.chatchat.api.runtime.coding.evidence.VisualReportArtifacts.availableChartCount(report),
                    "presentationDiagnostics",report.getOrDefault("presentationDiagnostics",List.of()))));
            var row=page.rows().get(0);
            if(row.containsKey("reportMarkdown") && row.containsKey("evidenceReferences")) {
                var input=inputs.requireAuthorized(contract);
                var description=inputs.describe(call.scope(),call.taskId(),input);
                response.put("sourceFieldDefinitions",description.getOrDefault("inputVersions",List.of()));
                var authorized=new java.util.LinkedHashSet<String>();
                if(description.get("inputVersions") instanceof Iterable<?> versions) {
                    for(Object version:versions)if(version instanceof Map<?,?> source
                            && source.get("datasetReference") instanceof String ref && !ref.isBlank())authorized.add(ref);
                } else authorized.add(input.sourceDatasetRef());
                if(dataSupply!=null)dataSupply.snapshots(contract,null).stream()
                    .map(CodingInputSnapshotService.Snapshot::sourceDatasetRef)
                    .filter(ref->ref!=null&&!ref.equals(input.sourceDatasetRef())).forEach(authorized::add);
                String referenceRepair=null;
                java.util.List<Map<String,Object>> repairSources=java.util.List.of();
                int repairSourceCount=0;
                if(com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.enabled(contract)) {
                    if(knowledge==null)throw new SecurityException("Knowledge validation unavailable");
                    var disclosed=knowledge.authorizedReferences(contract);authorized.addAll(disclosed);
                    try {knowledge.validateReport(contract,row);}
                    catch(com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.ReportReferenceException malformed) {
                        // A repairable citation declaration must not hide the visual
                        // validation receipt. Revoked/unavailable sources still throw.
                        referenceRepair=malformed.getMessage();
                        var metadata=knowledge.citations(contract,disclosed);
                        repairSourceCount=metadata.size();
                        repairSources=citationRepairSources(metadata,Objects.toString(row.get("reportMarkdown"),""));
                    }
                }
                var cited=row.get("evidenceReferences");
                boolean valid=referenceRepair==null && cited instanceof List<?> refs && !refs.isEmpty()
                    && refs.stream().allMatch(ref->ref instanceof String && authorized.contains(ref));
                var check=new java.util.LinkedHashMap<String,Object>();
                check.put("valid",valid);check.put("authorizedDatasetReferences",List.copyOf(authorized));check.put("repairRequired",!valid);
                if(referenceRepair!=null)check.put("repairGuidance",referenceRepair);
                if(referenceRepair!=null){
                    check.put("citationRepairSources",repairSources);
                    check.put("citationRepairSourcesTruncated",repairSourceCount>repairSources.size());
                    check.put("citationRepairSourcesAuthority","METADATA_ONLY_NOT_CLAIM_VERIFICATION");
                }
                response.put("reportReferenceValidation",check);
            }
            return response;
        });
    }
    static java.util.List<Map<String,Object>> citationRepairSources(java.util.List<Map<String,Object>> sources,String markdown){
        return sources.stream().sorted(java.util.Comparator
            .comparingInt((Map<String,Object> source)->source.get("source_url") instanceof String url&&!url.isBlank()&&mentionedUrl(markdown,url)?0:1)
            .thenComparing(source->Objects.toString(source.get("evidence_id"),"")))
            .limit(32).map(source->{
                var hint=new java.util.LinkedHashMap<String,Object>();
                for(String key:java.util.List.of("evidence_id","title","source_url","section_path","published_at","retrieved_at","resource_id"))
                    if(source.get(key) instanceof String text&&!text.isBlank()&&text.length()<=4096)
                        hint.put(key,key.equals("title")&&text.length()>256?text.substring(0,256):text);
                return Map.<String,Object>copyOf(hint);
            }).toList();
    }
    private static boolean mentionedUrl(String markdown,String url){
        for(int index=markdown.indexOf(url);index>=0;index=markdown.indexOf(url,index+1)){
            int end=index+url.length();
            if((index==0||" \t\r\n([<\"'`".indexOf(markdown.charAt(index-1))>=0)
                &&(end==markdown.length()||" \t\r\n)]>\"'`，。；".indexOf(markdown.charAt(end))>=0))return true;
        }
        return false;
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setWorkspace(CodingWorkspaceService workspace) { this.workspace=workspace; }

    @PostMapping("/workspace-read")
    public Map<String,Object> readWorkspace(@RequestBody JsonNode body) {
        return bound(body, call -> {
            var contract=contract(body,call);
            if(workspace==null)throw new SecurityException("Workspace Coding profile is not enabled");
            return workspace.read(contract,call.lease(),body.path("operationId").asText());
        });
    }
    @PostMapping("/checkpoint")
    public Map<String,Object> checkpoint(@RequestBody JsonNode body) {
        return bound(body, call -> {
            contract(body, call,false);
            tasks.saveSdkCheckpoint(call.scope(), call.taskId(), call.lease(), body.path("checkpoint"));
            return Map.of("saved", true);
        });
    }
    @PostMapping("/workspace-propose")
    public Map<String,Object> proposeWorkspace(@RequestBody JsonNode body) {
        return bound(body, call -> {
            var contract=contract(body,call);
            if(workspace==null)throw new SecurityException("Workspace Coding profile is not enabled");
            if(!body.path("proposal").isObject())throw new IllegalArgumentException("SQL file proposal is required");
            return workspace.propose(contract,call.lease(),body.path("operationId").asText(),
                mapper.convertValue(body.path("proposal"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        });
    }

    @PostMapping("/workspace-metadata")
    public Map<String,Object> workspaceMetadata(@RequestBody JsonNode body){
        return bound(body,call->{
            var contract=contract(body,call);
            if(workspace==null||!body.path("query").isObject())throw new SecurityException("Workspace metadata profile unavailable");
            return workspace.metadata(contract,call.lease(),body.path("operationId").asText(),mapper.convertValue(body.path("query"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        });
    }
    @PostMapping("/workspace-validate")
    public Map<String,Object> workspaceValidate(@RequestBody JsonNode body){
        return bound(body,call->{
            var contract=contract(body,call);
            if(workspace==null||!body.path("validation").isObject())throw new SecurityException("Workspace validation profile unavailable");
            return workspace.validateSql(contract,call.lease(),body.path("operationId").asText(),mapper.convertValue(body.path("validation"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        });
    }

    public ClineModelCallbackController(CodingInvocationContext identity, DurableCodingTaskService tasks,
            ClineRuntimeModelBridge model, ClineModelReceiptStore receipts,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingEvidenceCollector collector, ObjectMapper mapper) {
        this.identity = identity; this.tasks = tasks; this.model = model; this.receipts = receipts;
        this.inputs = inputs; this.plans = plans; this.collector = collector; this.mapper = mapper;
    }

    /** Signed connectivity check only; never creates a task or grants execution authority. */
    @PostMapping(value="/preflight", produces="application/json")
    public Map<String,Object> preflight() {
        return Map.of("status","READY","codeLifecycleVersion","runtime_os.code_lifecycle.v1",
            "serverTime",Instant.now().toString());
    }

    @PostMapping("/reserve-model")
    public Map<String, Object> reserve(@RequestBody JsonNode body) {
        return bound(body, call -> {
            CodingTaskContract contract = contract(body, call);
            if (skillReader!=null) skillReader.freezeCatalog(contract,call.lease());
            JsonNode request = body.path("model");
            String operationId = operationId(request.path("operationId").asText(null));
            if (request.path("modelCalls").asLong(-1) != 1)
                throw new IllegalArgumentException("Exactly one model call must be reserved");
            long tokens = request.path("maxTokens").asLong(-1);
            if (tokens < 1 || tokens > contract.budget().maxModelTokens())
                throw new IllegalArgumentException("Model token reservation exceeds contract");
            tokens = Math.max(tokens, model.modelTokenReservation(contract));
            String cost = CodingBudgetShare.amount(contract.budget().maxCostAmount(),
                Math.max(1, contract.budget().maxModelCalls()));
            final long reservedTokens = tokens;
            var reserved = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.reserve(call.scope(), call.taskId(), call.lease(), revision,
                operationId, sha(call.contractHash() + "\0" + operationId + "\0" + reservedTokens),
                CodingJournal.OperationKind.MODEL,
                new CodingTaskOutcome.Counters(1, reservedTokens, 0, 0, 0, 0, 0, cost)));
            if (!reserved.admitted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Model budget denied");
            var intent = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.startIntent(call.scope(), call.taskId(), call.lease(), revision, operationId, "runtime-model:" + operationId));
            if (intent.dispatchIntentCreated()) {
                Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
                freshDispatches.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
                freshDispatches.put(key(call, operationId), Instant.now());
            }
            return Map.of("admitted", true, "dispatchIntentCreated", intent.dispatchIntentCreated(),
                "revision", intent.outcome().revision());
        });
    }

    @PostMapping("/model")
    public Map<String, Object> model(@RequestBody JsonNode body) {
        return bound(body, call -> {
            JsonNode request = body.path("request");
            boolean managedAttempt = request.path("managedAttempt").asBoolean(false);
            // Managed admission validates sources in reserve below; legacy dispatch validates here.
            CodingTaskContract contract = contract(body, call,!managedAttempt);
            String operationId = operationId(request.path("operationId").asText(null));
            boolean supportsModelFeedback = "runtime_os.coding.model_feedback.v1".equals(request.path("modelResponseFeedbackVersion").asText());
            var accounting = body.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) accounting).set("model", mapper.valueToTree(
                Map.of("operationId", operationId, "modelCalls", 1, "maxTokens", 1)));
            var prior = receipts.response(call.scope(), call.taskId(), operationId);
            if (prior.isPresent()) {
                boolean replay=request.path("receiptReplayExpected").asBoolean();
                if(replay&&!tasks.modelCheckpointMatches(call.scope(),call.taskId(),operationId,request.path("sdkCheckpointOrdinal").asLong(-1)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Archived model response differs from the restored SDK boundary");
                if(managedAttempt)contract(body,call);
                if (prior.get().containsKey("error") && !(supportsModelFeedback && repairableModelResponse(prior.get()))) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Archived model response contains invalid tool arguments");
                if (managedAttempt) settle(accounting);
                if(replay){var cached=new java.util.LinkedHashMap<String,Object>(prior.get());cached.put("runtimeReceiptReplayed",true);return cached;}
                return prior.get();
            }
            if(request.path("receiptReplayExpected").asBoolean())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Restored SDK requires an existing model receipt; dispatch forbidden");
            if(request.has("sdkCheckpointOrdinal"))tasks.bindModelCheckpoint(call.scope(),call.taskId(),call.lease(),
                operationId,request.path("sdkCheckpointOrdinal").asLong(-1));
            // One authenticated transport request, preserving the existing durable
            // admission/start/receipt/settlement transactions and crash semantics.
            if (managedAttempt) reserve(accounting);
            Instant permittedAt = freshDispatches.get(key(call, operationId));
            if (!tasks.unresolvedOperations(call.scope(), call.taskId()).contains(operationId)
                    || permittedAt == null || permittedAt.isBefore(Instant.now().minus(Duration.ofMinutes(5))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Model dispatch is not a fresh A2 start intent");
            // Renew before dispatch. The bound model uses the tool deadline; a late
            // response remains archival evidence even if ownership expires meanwhile.
            CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.renew(call.scope(), call.taskId(), call.lease(), Duration.ofMinutes(5), revision));
            if (!freshDispatches.remove(key(call, operationId), permittedAt))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Model dispatch permission was already consumed");
            Map<String, Object> response;
            long localRequestStarted=System.nanoTime();
            var renewal = modelLeaseRenewals.scheduleWithFixedDelay(() -> identity.as(call.scope(), GRANTS, () -> {
                var current = tasks.get(call.scope(), call.taskId());
                if (current.status().terminal() || current.cancellationRequested()
                        || current.executionControl().stopReason() != null
                        || !contract.budget().deadlineAt().isAfter(Instant.now())) return null;
                CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                    tasks.renew(call.scope(), call.taskId(), call.lease(), Duration.ofMinutes(5), revision));
                return null;
            }), 60, 60, java.util.concurrent.TimeUnit.SECONDS);
            try {
                response = model.complete(contract, request);
            } catch (ClineRuntimeModelBridge.InvalidModelResponseException invalid) {
                receipts.commit(contract, operationId, "runtime-model:" + operationId, invalid.receipt());
                if (Boolean.FALSE.equals(invalid.receipt().get("providerDispatched")))
                    settle(accounting); // Definitive local rejection must not leave an unknown execution.
                if (supportsModelFeedback && repairableModelResponse(invalid.receipt())) {
                    assertStillOwned(call);
                    if (managedAttempt) settle(accounting);
                    return invalid.receipt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    Boolean.FALSE.equals(invalid.receipt().get("providerDispatched"))
                        ? "Runtime model request rejected before provider dispatch; local stop receipt archived"
                        : "Model response rejected; provider stop and usage receipt archived", invalid);
            } catch (RuntimeException providerFailure) {
                // No provider response can be delivered by this callback after it throws.
                // Upstream inference/billing may remain unknown: do NOT release its reservation.
                receipts.commitLocalTermination(contract,operationId,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-localRequestStarted),
                    com.chatchat.agents.model.ModelFailureCategory.classify(providerFailure).name());
                LOG.warn("Bound Coding model operation {} failed after its durable start intent",
                    operationId, providerFailure);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    isRequestTimeout(providerFailure)
                        ? "Model API request timed out; local request terminated, provider usage must be reconciled"
                        : "Runtime model request failed; execution outcome must be reconciled", providerFailure);
            } finally {
                renewal.cancel(false);
            }
            // Preserve a genuine late provider receipt even when cancellation/fencing won the race.
            // Receipt archival cannot publish a result or grant another dispatch.
            receipts.commit(contract, operationId, "runtime-model:" + operationId, response);
            assertStillOwned(call);
            if (managedAttempt) settle(accounting);
            return response;
        });
    }

    static boolean isRequestTimeout(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof java.net.http.HttpTimeoutException
                    || failure instanceof java.net.SocketTimeoutException
                    || failure instanceof java.util.concurrent.TimeoutException
                    || failure.getClass().getSimpleName().equals("TimeoutException")) return true;
        }
        return false;
    }

    private static boolean repairableModelResponse(Map<String, Object> response) {
        return response.get("modelResponseFeedback") instanceof Map<?, ?> feedback
            && "runtime_os.coding.model_feedback.v1".equals(feedback.get("schemaVersion"))
            && Boolean.TRUE.equals(feedback.get("repairAllowed"))
            && feedback.get("diagnosticCode") instanceof String diagnostic
            && Set.of("MODEL_OUTPUT_TRUNCATED", "MODEL_TOOL_ARGUMENTS_INVALID").contains(diagnostic);
    }

    @PostMapping("/settle-model")
    public Map<String, Object> settle(@RequestBody JsonNode body) {
        return bound(body, call -> {
            contract(body, call,false);
            String operationId = operationId(body.path("model").path("operationId").asText(null));
            Map<String, Object> response = receipts.response(call.scope(), call.taskId(), operationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provider response is unknown; keep the budget reservation"));
            String proof = receipts.proofRef(call.scope(), call.taskId(), operationId);
            var current = tasks.get(call.scope(), call.taskId());
            if (tasks.verifiedOperation(call.scope(), call.taskId(), operationId).isEmpty())
                current = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                    tasks.reconcileStopped(call.scope(), call.taskId(), call.lease(), revision, operationId, proof));
            @SuppressWarnings("unchecked") Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            long input = number(usage, "inputTokens"), output = number(usage, "outputTokens");
            boolean meteringComplete = Boolean.FALSE.equals(response.get("providerDispatched"));
            current = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.settle(call.scope(), call.taskId(), call.lease(), revision,
                operationId, new CodingTaskOutcome.Counters(1, Math.addExact(input, output),
                    0, 0, 0, 0, 0, "0"), meteringComplete));
            return Map.of("revision", current.revision(), "meteringComplete", meteringComplete,
                "receiptRef", proof);
        });
    }

    @PostMapping("/event")
    public Map<String, Object> event(@RequestBody JsonNode body) {
        return bound(body, false, true, call -> {
            JsonNode event = body.path("event");
            if (!event.isObject() || event.toString().length() > 8192
                    || !event.path("sequence").isIntegralNumber())
                throw new IllegalArgumentException("Bounded worker event and sequence are required");
            String type = event.path("type").asText("");
            // TOOL_STARTED precedes Runtime admission, so its proposed operation is advisory only.
            boolean admissionPending = event.path("admissionPending").asBoolean(false)
                && Set.of("MODEL_STARTED", "MODEL_FAILED", "MODEL_TIMING", "MODEL_TOOL_DISCLOSURE").contains(type);
            String operation = admissionPending ? null : "TOOL_FINISHED".equals(type)
                ? event.path("operationId").asText(null)
                : type.startsWith("MODEL_") ? operationId("model-" + event.path("modelCall").asLong(-1)) : null;
            var snapshot = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.recordWorkerProgress(call.scope(), call.taskId(), call.lease(), revision,
                    event.path("sequence").asLong(), type, operation, sha(event.toString()), workerPayload(event)));
            return Map.of("authoritativeRevision", snapshot.revision());
        });
    }

    /** Only bounded telemetry fields are retained. Source, prompts and tool responses are private artifacts. */
    private Map<String,Object> workerPayload(JsonNode event) {
        var payload = new java.util.LinkedHashMap<String,Object>();
        if(event.path("toolSchemaSha256").isTextual() && event.path("toolSchemaSha256").asText().matches("[a-f0-9]{64}"))
            payload.put("toolSchemaSha256",event.path("toolSchemaSha256").asText());
        for(String key:List.of("authorizedTools","disclosedTools")) {
            var value=event.path(key);
            if(value.isArray()&&value.size()<=64) {
                var names=new java.util.TreeSet<String>();boolean valid=true;
                for(var name:value)if(name.isTextual()&&name.asText().matches("[a-z][a-z0-9_]{0,63}"))names.add(name.asText());else valid=false;
                if(valid)payload.put(key,List.copyOf(names));
            }
        }
        for (String key : List.of("type", "timestamp", "stage", "source", "attemptId", "operationId", "tool", "status", "planText", "executionOrigin",
                "scriptPath", "codeSha256", "fileName", "proposedOperationId", "skillFingerprint", "skillId", "skillVersion", "selectedModel", "providerModel", "protocol", "startedAt", "finishedAt", "validationStatus", "outputStatus",
                "resultDatasetRef", "validationRecordRef", "evidenceBundleRef", "errorCategory", "diagnosticCode", "parentCodeHash", "changeKind", "proposalHash", "toolCallId", "responseMode", "strategy", "reason", "requestHash", "estimator", "contextChangeReason", "compactionStrategy", "queueTimeScope", "providerTimingAvailability", "sdkSessionId", "receiptReplayOperationId")) {
            JsonNode value = event.path(key);
            if (value.isTextual() && value.asText().length() <= 512) payload.put(key, value.asText());
        }
        for (String key : List.of("scriptVersion", "modelCall", "sourceBytes", "durationMs", "maxOutputTokens", "repairAttempts", "repairAttempt", "maxRepairAttempts", "queueTimeMs", "firstTokenMs", "generationTimeMs", "retryTimeMs", "parentCodeVersion", "codeVersion", "toolDurationMs", "messageCount", "requestBytes", "cpuTimeMs", "maxRssBytes", "callbackFirstEventMs", "responseBytes", "toolCount", "validationDurationMs",
                "contextSystemBytes", "contextToolsBytes", "contextToolIndexBytes", "contextTaskBytes", "contextSkillsBytes", "contextCodeBytes", "contextReportBytes", "contextHistoryBytes", "contextEnvelopeBytes", "estimatedInputTokens", "softInputTokenBudget",
                "repeatedContextBytes", "duplicateContextBytes", "avoidableRepeatedContextBytes", "newContextBytes", "compactionCount", "contextSizeBefore", "contextSizeAfter", "callbackConnectMs", "callbackHeadersMs", "callbackBodyMs", "callbackHttpDurationMs",
                "constructionDurationMs", "restoreDurationMs", "preparationDurationMs", "checkpointOrdinal", "restoredMessageCount")) {
            if (event.path(key).isIntegralNumber() && event.path(key).asLong() >= 0)
                payload.put(key, event.path(key).asLong());
        }
        if (event.path("resultAvailable").isBoolean())
            payload.put("resultAvailable", event.path("resultAvailable").asBoolean());
        if (event.path("repairAllowed").isBoolean())payload.put("repairAllowed",event.path("repairAllowed").asBoolean());
        if (event.path("exceedsSoftBudget").isBoolean())payload.put("exceedsSoftBudget",event.path("exceedsSoftBudget").asBoolean());
        if (event.path("receiptReplayed").isBoolean())payload.put("receiptReplayed",event.path("receiptReplayed").asBoolean());
        if (event.path("usage").isObject()) {
            var usage = new java.util.LinkedHashMap<String,Object>();
            for (String key : List.of("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "reasoningTokens"))
                if (event.path("usage").path(key).isIntegralNumber() && event.path("usage").path(key).asLong() >= 0)
                    usage.put(key, event.path("usage").path(key).asLong());
            payload.put("usage", usage);
        }
        payload.put("source","NODE_CLINE");
        if (!payload.containsKey("stage")) {
            String type=event.path("type").asText();
            String tool=event.path("tool").asText();
            payload.put("stage", "CODE_READY".equals(type)?"CODE_READY"
                : Set.of("CONTEXT_READY","SKILLS_AVAILABLE").contains(type)?"CONTEXT_READY"
                : "TOOL_STARTED".equals(type)&&"python_execute".equals(tool)?"VALIDATING"
                : "TOOL_STARTED".equals(type)&&"data_request".equals(tool)?"WAITING_CONTEXT"
                : "TOOL_FINISHED".equals(type)&&"python_execute".equals(tool)?"RESULT_VALIDATING":"CODING");
        }
        return Map.copyOf(payload);
    }

    /** Only the independently verified MCP operation and immutable evidence may become authoritative. */
    @PostMapping("/finish")
    public Map<String, Object> finish(@RequestBody JsonNode body) {
        return bound(body, true, true, call -> {
            CodingTaskContract contract = contract(body, call);
            JsonNode outcome = body.path("outcome");
            String sdkStatus = outcome.path("status").asText();
            if (!Set.of("completed", "failed", "aborted").contains(sdkStatus))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown SDK completion status");
            if (call.snapshot().executionControl().stopReason() != null) {
                var stopped = tasks.finishStoppedAfterReconciliation(call.scope(), call.taskId(),
                    call.lease(), call.snapshot().revision());
                return Map.of("status", stopped.status().name(), "revision", stopped.revision());
            }
            String operationId = outcome.path("operationId").asText(null);
            if(com.chatchat.common.runtime.coding.NativeCodeWorkspace.enabled(contract)){
                if(nativeTasks==null)throw new SecurityException("Native Coding is disabled");
                var result=nativeTasks.complete(contract,call.lease(),nativeMap(outcome));return Map.of("status",result.status().name(),"revision",result.revision(),"usage",result.usage());
            }
            if ("failed".equals(sdkStatus))
                return failed(call);
            if(CodingWorkspaceService.supports(contract)) {
                if(workspace==null || !"completed".equals(sdkStatus))
                    throw new IllegalStateException("Workspace Coding did not complete");
                if(operationId==null)return failed(call);
                return completion(workspace.complete(contract,call.lease(),operationId));
            }
            boolean textResult=contract.permitsTextResult() && operationId!=null && operationId.matches("workspace-[0-9a-f]{64}");
            if(textResult) {
                var submitted=tasks.privateState(call.scope(),call.taskId(),"text-result-submission");
                if(submitted==null || !operationId.equals(submitted.path("operationId").asText()))
                    throw new SecurityException("No final text submission is bound to this task");
            }
            if (!"completed".equals(sdkStatus)
                    || operationId == null || !textResult && !operationId.matches("cline-[0-9a-f]{64}"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SDK run has no completed, independently verifiable Python operation");
            var input = inputs.requireAuthorized(contract);
            var collected = textResult ? collector.textResult(contract,operationId) : collector.collect(call.scope(), contract, operationId, tasks.scriptFile(call.scope(),call.taskId(),operationId),
                contract.outputContract().requiredFiles().get(0),
                List.of(new CodingEvidenceCollector.InputFile(input.fileId(), input.fileName(), input.sha256())),
                plans.validator(contract));
            if(!textResult && com.chatchat.common.runtime.coding.VisualReportMode.active(contract.runtimeContext())) {
                var visual=collector.visualReport(call.scope(),call.taskId(),collected.resultDatasetRef());
                if(visual.isEmpty() && plans.requiresVisualReport(contract)
                        || visual.isPresent() && "FAILED".equals(visual.get().get("artifactValidationStatus")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Visual report artifact contract requires technical repair before completion");
            }
            CodingTaskOutcome current = tasks.get(call.scope(), call.taskId());
            if (current.status().terminal()) {
                if (current.status() != CodingTaskOutcome.Status.COMPLETED
                        || !collected.evidenceBundleRef().equals(current.evidenceBundleRef()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Terminal Coding result differs from the SDK completion");
                return completion(current);
            }
            Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            if (at.isBefore(current.updatedAt())) at = current.updatedAt();
            CodingTaskOutcome proposal = new CodingTaskOutcome(current.schemaVersion(), current.taskId(),
                current.scope(), current.contractSha256(), CodingTaskOutcome.Status.COMPLETED,
                current.revision() + 1, current.lastEventSequence() + 1, current.createdAt(), at,
                current.startedAt(), at, false, null,
                textResult?"Text report archived with authorized sources":"Coding execution archived; validation " + collected.validationStatus(),
                textResult?List.of("Text explanation only; no calculations were executed or independently verified."):current.limitations(), collected.inputSnapshotRef(), collected.codeArtifactRef(),
                collected.executionReceiptRef(), collected.resultDatasetRef(),
                collected.validationRecordRef(), collected.evidenceBundleRef(),
                collected.validationStatus(),
                current.reproducibility(), current.usage(),
                new CodingTaskOutcome.ExecutionControl(null, null, true, false),
                current.parentTaskId(), current.supersedesTaskId(), Map.of());
            var committed = tasks.commitOutcome(call.scope(), call.taskId(), call.lease(),
                current.revision(), proposal);
            return completion(committed);
        });
    }

    private Map<String, Object> failed(Call call) {
        CodingTaskOutcome current = tasks.get(call.scope(), call.taskId());
        if (current.status().terminal())
            return Map.of("status", current.status().name(), "revision", current.revision());
        if (!tasks.unresolvedOperations(call.scope(), call.taskId()).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "SDK failure has an unknown execution; reconcile before terminal failure");
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (at.isBefore(current.updatedAt())) at = current.updatedAt();
        var proposal = new CodingTaskOutcome(current.schemaVersion(), current.taskId(), current.scope(),
            current.contractSha256(), CodingTaskOutcome.Status.FAILED, current.revision() + 1,
            current.lastEventSequence() + 1, current.createdAt(), at, current.startedAt(), at,
            false, CodingErrorCode.EXECUTION_FAILED, workspace!=null&&CodingWorkspaceService.supports(tasks.contract(call.scope(),call.taskId()))
                ?workspace.failureSummary(tasks.contract(call.scope(),call.taskId())):"Coding loop failed without a verified result",
            List.of("No completed Coding result or independent validation was submitted"),
            current.inputSnapshotRef(), null, null, null, null, null,
            CodingTaskOutcome.ValidationStatus.NOT_RUN, current.reproducibility(), current.usage(),
            new CodingTaskOutcome.ExecutionControl(null, null, true, false),
            current.parentTaskId(), current.supersedesTaskId(), Map.of());
        var committed = tasks.commitOutcome(call.scope(), call.taskId(), call.lease(),
            current.revision(), proposal);
        return Map.of("status", committed.status().name(), "revision", committed.revision());
    }

    private static Map<String, Object> completion(CodingTaskOutcome outcome) {
        return Map.of("status", outcome.status().name(), "revision", outcome.revision(),
            "resultDatasetRef", outcome.resultDatasetRef(),
            "validationRecordRef", outcome.validationRecordRef(),
            "evidenceBundleRef", outcome.evidenceBundleRef());
    }

    private record Call(KernelDataScope scope, String taskId, String contractHash,
                        CodingJournal.Lease lease, CodingTaskOutcome snapshot) { }

    private <T> T bound(JsonNode body, Function<Call, T> operation) {
        return bound(body, false, operation);
    }

    private <T> T bound(JsonNode body, boolean allowTerminal, Function<Call, T> operation) {
        return bound(body, allowTerminal, false, operation);
    }

    private <T> T bound(JsonNode body, boolean allowTerminal, boolean allowStop,
                        Function<Call, T> operation) {
        try {
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Callback body is required");
            KernelDataScope scope = mapper.treeToValue(body.path("scope"), KernelDataScope.class);
            String taskId = body.path("taskId").asText(null);
            String contractHash = body.path("contractHash").asText(null);
            long fencingToken = body.path("fencingToken").asLong(-1);
            if (scope == null || taskId == null || contractHash == null || fencingToken < 1)
                throw new IllegalArgumentException("Complete callback binding is required");
            return identity.as(scope, GRANTS, () -> {
                CodingTaskOutcome snapshot = tasks.get(scope, taskId);
                CodingJournal.Lease lease = tasks.ownership(scope, taskId);
                if (!snapshot.contractSha256().equals(contractHash)
                        || lease.fencingToken() != fencingToken || lease.owner() == null
                        || !lease.owner().startsWith("cline-adapter:")
                        || !lease.leaseUntil().isAfter(Instant.now())
                        || !allowTerminal && snapshot.status().terminal()
                        || !allowStop && (snapshot.cancellationRequested()
                            || snapshot.executionControl().stopReason() != null))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Callback task, lease, or stop state is no longer current");
                return operation.apply(new Call(scope, taskId, contractHash, lease, snapshot));
            });
        } catch (ResponseStatusException known) { throw known; }
        catch (CodingContractException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Coding task state rejected callback: " + conflict.error().code(), conflict);
        }
        catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Coding callback binding", invalid);
        }
    }

    private CodingTaskContract contract(JsonNode body, Call call) {
        return contract(body,call,true);
    }
    /** Private checkpoints and stop/usage accounting disclose no retained source content.
     * Tool reads validate before/after IO; every model dispatch and replay still validates sources. */
    private CodingTaskContract contract(JsonNode body, Call call,boolean sourceAdmission) {
        try {
            CodingTaskContract contract = mapper.treeToValue(body.path("contract"), CodingTaskContract.class);
            call.snapshot().validateAgainst(contract);
            if(com.chatchat.common.runtime.coding.NativeCodeWorkspace.enabled(contract)){
                if(nativeWorkspace==null)throw new SecurityException("Native Coding is disabled");nativeWorkspace.requireAuthorized(contract);
            }
            if(sourceAdmission && com.chatchat.api.runtime.coding.knowledge.CodingKnowledgeService.enabled(contract)) {
                if(knowledge==null)throw new SecurityException("Knowledge validation unavailable");
                knowledge.validateContext(contract);
            }
            if (contract.authorization().networkPolicy() != CodingTaskContract.NetworkPolicy.NONE)
                throw new SecurityException("Coding model cannot access a networked Sandbox");
            return contract;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Callback contract is not authorized", invalid);
        }
    }

    private void assertStillOwned(Call call) {
        CodingJournal.Lease lease = tasks.ownership(call.scope(), call.taskId());
        CodingTaskOutcome current = tasks.get(call.scope(), call.taskId());
        if (lease.fencingToken() != call.lease().fencingToken()
                || !lease.owner().equals(call.lease().owner())
                || !lease.leaseUntil().isAfter(Instant.now())
                || !tasks.contract(call.scope(),call.taskId()).budget().deadlineAt().isAfter(Instant.now())
                || current.status().terminal() || current.cancellationRequested()
                || current.executionControl().stopReason() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Coding ownership or stop state changed while the model was running");
    }

    private static String operationId(String value) {
        if (value == null || !value.matches("model-[1-9][0-9]{0,5}"))
            throw new IllegalArgumentException("Bounded model operation ID is required");
        return value;
    }

    private static long number(Map<String, Object> value, String key) {
        Object actual = value == null ? null : value.get(key);
        if (!(actual instanceof Number number) || number.longValue() < 0)
            throw new IllegalStateException("Provider token usage is unknown; retain reservation");
        return number.longValue();
    }

    private static String key(Call call, String operation) {
        return call.scope().tenantId() + "\0" + call.scope().userId() + "\0" + call.scope().runId()
            + "\0" + call.taskId() + "\0" + operation + "\0" + call.lease().fencingToken();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
