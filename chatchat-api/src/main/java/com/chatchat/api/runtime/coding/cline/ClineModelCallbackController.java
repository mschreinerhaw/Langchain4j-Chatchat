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
    private CodingWorkspaceService workspace;
    private CodingDataSupplyService dataSupply;
    private CodingAnalysisSqlService analysisSql;
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
            for(String key:List.of("fileId","sha256","rowCount","format","inputManifest","inputVersions"))
                if(description.containsKey(key))response.put(key,description.get(key));
            response.put("files",CodingInputSnapshotService.mountedFiles(input).stream().map(file->Map.of(
                "fileId",file.fileId(),"fileName",file.fileName(),"sha256",file.sha256(),"rowCount",file.rowCount(),"size",file.size(),
                "virtualPath","/data/input/"+file.fileId()+"/"+file.fileName())).toList());
            response.put("fileLayout", CodingInputLayout.describe(c.runtimeContext()));
            if(c.metadataRefs().contains(com.chatchat.api.runtime.coding.data.CodingInputObservation.POLICY_REF))
                response.put("analysisPreview",inputs.observeForCoding(c));
            if(mapper.valueToTree(response).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180000)
                throw new IllegalArgumentException("Input metadata exceeds the bounded Coding context; narrower authorized projection required");
            return response;
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
                    "availableChartCount",report.get("charts") instanceof List<?> charts?charts.size():0)));
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
                var cited=row.get("evidenceReferences");
                boolean valid=cited instanceof List<?> refs && !refs.isEmpty()
                    && refs.stream().allMatch(ref->ref instanceof String && authorized.contains(ref));
                response.put("reportReferenceValidation",Map.of("valid",valid,
                    "authorizedDatasetReferences",List.copyOf(authorized),"repairRequired",!valid));
            }
            return response;
        });
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
            contract(body, call);
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
            CodingTaskContract contract = contract(body, call);
            JsonNode request = body.path("request");
            String operationId = operationId(request.path("operationId").asText(null));
            boolean supportsModelFeedback = "runtime_os.coding.model_feedback.v1".equals(request.path("modelResponseFeedbackVersion").asText());
            boolean managedAttempt = request.path("managedAttempt").asBoolean(false);
            var accounting = body.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) accounting).set("model", mapper.valueToTree(
                Map.of("operationId", operationId, "modelCalls", 1, "maxTokens", 1)));
            var prior = receipts.response(call.scope(), call.taskId(), operationId);
            if (prior.isPresent()) {
                if (prior.get().containsKey("error") && !(supportsModelFeedback && repairableModelResponse(prior.get()))) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Archived model response contains invalid tool arguments");
                if (managedAttempt) settle(accounting);
                return prior.get();
            }
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
                if (supportsModelFeedback && repairableModelResponse(invalid.receipt())) {
                    assertStillOwned(call);
                    if (managedAttempt) settle(accounting);
                    return invalid.receipt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Model response rejected; provider stop and usage receipt archived", invalid);
            } catch (RuntimeException providerFailure) {
                LOG.warn("Bound Coding model operation {} failed after its durable start intent",
                    operationId, providerFailure);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Runtime model request failed; execution outcome must be reconciled", providerFailure);
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
            contract(body, call);
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
            current = CodingTaskRevisions.mutate(tasks, call.scope(), call.taskId(), revision ->
                tasks.settle(call.scope(), call.taskId(), call.lease(), revision,
                operationId, new CodingTaskOutcome.Counters(1, Math.addExact(input, output),
                    0, 0, 0, 0, 0, "0"), false));
            return Map.of("revision", current.revision(), "meteringComplete", false,
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
                && Set.of("MODEL_STARTED", "MODEL_FAILED").contains(type);
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
        for (String key : List.of("type", "timestamp", "stage", "source", "attemptId", "tool", "status",
                "scriptPath", "codeSha256", "fileName", "proposedOperationId", "skillFingerprint", "selectedModel", "providerModel", "protocol", "startedAt", "finishedAt", "validationStatus", "outputStatus",
                "resultDatasetRef", "validationRecordRef", "evidenceBundleRef", "errorCategory", "diagnosticCode", "parentCodeHash", "changeKind", "proposalHash", "toolCallId", "responseMode", "strategy", "reason", "requestHash")) {
            JsonNode value = event.path(key);
            if (value.isTextual() && value.asText().length() <= 512) payload.put(key, value.asText());
        }
        for (String key : List.of("scriptVersion", "modelCall", "sourceBytes", "durationMs", "maxOutputTokens", "repairAttempts", "repairAttempt", "maxRepairAttempts", "queueTimeMs", "firstTokenMs", "generationTimeMs", "retryTimeMs", "parentCodeVersion", "codeVersion", "toolDurationMs", "messageCount", "requestBytes", "cpuTimeMs", "maxRssBytes", "callbackFirstEventMs", "responseBytes", "toolCount", "validationDurationMs")) {
            if (event.path(key).isIntegralNumber() && event.path(key).asLong() >= 0)
                payload.put(key, event.path(key).asLong());
        }
        if (event.path("resultAvailable").isBoolean())
            payload.put("resultAvailable", event.path("resultAvailable").asBoolean());
        if (event.path("repairAllowed").isBoolean())payload.put("repairAllowed",event.path("repairAllowed").asBoolean());
        if (event.path("usage").isObject()) {
            var usage = new java.util.LinkedHashMap<String,Object>();
            for (String key : List.of("inputTokens", "outputTokens"))
                if (event.path("usage").path(key).isIntegralNumber() && event.path("usage").path(key).asLong() >= 0)
                    usage.put(key, event.path("usage").path(key).asLong());
            payload.put("usage", usage);
        }
        payload.put("source","NODE_CLINE");
        if (!payload.containsKey("stage")) {
            String type=event.path("type").asText();
            String tool=event.path("tool").asText();
            payload.put("stage", "CODE_READY".equals(type)?"CODE_READY"
                : Set.of("CONTEXT_READY","SKILLS_LOADED").contains(type)?"CONTEXT_READY"
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
            if ("failed".equals(sdkStatus))
                return failed(call);
            if(CodingWorkspaceService.supports(contract)) {
                if(workspace==null || !"completed".equals(sdkStatus))
                    throw new IllegalStateException("Workspace Coding did not complete");
                if(operationId==null)return failed(call);
                return completion(workspace.complete(contract,call.lease(),operationId));
            }
            if (!"completed".equals(sdkStatus)
                    || operationId == null || !operationId.matches("cline-[0-9a-f]{64}"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SDK run has no completed, independently verifiable Python operation");
            var input = inputs.requireAuthorized(contract);
            var collected = collector.collect(call.scope(), contract, operationId, tasks.scriptFile(call.scope(),call.taskId(),operationId),
                contract.outputContract().requiredFiles().get(0),
                List.of(new CodingEvidenceCollector.InputFile(input.fileId(), input.fileName(), input.sha256())),
                plans.validator(contract));
            if(com.chatchat.common.runtime.coding.VisualReportMode.active(contract.runtimeContext())) {
                var visual=collector.visualReport(call.scope(),call.taskId(),collected.resultDatasetRef());
                if(visual.isEmpty() || "FAILED".equals(visual.get().get("artifactValidationStatus")))
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
                "Coding execution archived; validation " + collected.validationStatus(),
                current.limitations(), collected.inputSnapshotRef(), collected.codeArtifactRef(),
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
            false, CodingErrorCode.EXECUTION_FAILED, "Coding loop failed without a verified result",
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
        try {
            CodingTaskContract contract = mapper.treeToValue(body.path("contract"), CodingTaskContract.class);
            call.snapshot().validateAgainst(contract);
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
