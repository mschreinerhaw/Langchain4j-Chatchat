package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.chatchat.common.runtime.coding.CodingErrorCode;
import com.chatchat.common.runtime.coding.VisualReportMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Routes Cline code execution exclusively through the existing A2/MCP Python control plane. */
@RestController
@RequestMapping("/internal/coding/cline")
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class ClinePythonCallbackController {
    private JsonNode readProject(String payload) {
        try{return mapper.readTree(payload);}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid Python project payload",invalid);}
    }
    private com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService feedback;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setFeedback(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService value) { feedback=value; }
    private CodingCodeArtifactStore codeArtifacts;
    private com.chatchat.api.assets.RuntimePythonAssetArchiver monitorAssets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMonitorAssets(com.chatchat.api.assets.RuntimePythonAssetArchiver value){monitorAssets=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCodeArtifacts(CodingCodeArtifactStore value) { codeArtifacts = value; }
    private static final Logger LOG = LoggerFactory.getLogger(ClinePythonCallbackController.class);

    private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;
    private final CodingInputSnapshotService inputs;
    private final CodingValidationPlanService plans;
    private final CodingPythonExecutionBridge python;
    private final CodingEvidenceCollector collector;
    private final ObjectMapper mapper;

    public ClinePythonCallbackController(CodingInvocationContext identity, DurableCodingTaskService tasks,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingPythonExecutionBridge python, CodingEvidenceCollector collector, ObjectMapper mapper) {
        this.identity = identity; this.tasks = tasks; this.inputs = inputs;
        this.plans = plans; this.python = python; this.collector = collector; this.mapper = mapper;
    }

    /** Signed, peer-restricted operator projection. READ only; never renews leases or resumes a task. */
    @PostMapping("/monitor-python")
    public Map<String,Object> monitorPython(@RequestBody JsonNode body) {
        if(body==null||!body.isObject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        final KernelDataScope requested;
        try { requested=mapper.treeToValue(body.path("scope"),KernelDataScope.class); }
        catch(Exception invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Monitor scope required");}
        String taskId=body.path("taskId").asText("");
        if(requested==null||taskId.isBlank()||taskId.length()>256||requested.tenantId()==null||requested.userId()==null||requested.runId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Monitor task identity required");
        return identity.as(requested,EnumSet.of(CodingTaskAccess.Action.READ),()->{
            var outcome=tasks.get(requested,taskId);var contract=tasks.contract(requested,taskId);var scope=contract.scope();
            if(!java.util.Objects.equals(scope.tenantId(),requested.tenantId())||!java.util.Objects.equals(scope.userId(),requested.userId())
                ||!java.util.Objects.equals(scope.runId(),requested.runId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var executions=feedback==null?List.<com.chatchat.common.runtime.coding.ExecutionFeedback>of():feedback.timeline(scope,taskId);
            String operation=body.path("operationId").asText("");
            if(!operation.isBlank()) {
                if(!operation.matches("cline-[0-9a-f]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                var execution=executions.stream().filter(e->operation.equals(e.executionId())).findFirst()
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived execution unavailable"));
                String kind=body.path("kind").asText("code");
                if("timing".equals(kind)) {
                    var bytes=monitorAssets==null?java.util.Optional.<byte[]>empty():monitorAssets.log(scope,taskId,operation,"receipt.json");
                    if(bytes.isEmpty())return Map.of("operationId",operation,"sha256",execution.codeHash(),"available",false);
                    if(bytes.get().length>8192)throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid archived timing");
                    try {
                        var receipt=mapper.readTree(bytes.get());
                        if(!operation.equals(receipt.path("operationId").asText())||!execution.codeHash().equals(receipt.path("codeHash").asText()))throw new IllegalArgumentException();
                        var timing=new LinkedHashMap<String,Object>();timing.put("source","MCP_ARCHIVED_RECEIPT");timing.put("operationId",operation);timing.put("sha256",execution.codeHash());timing.put("available",true);
                        for(String field:List.of("startedAt","finishedAt"))timing.put(field,receipt.path(field).isTextual()?Instant.parse(receipt.path(field).asText()).toString():null);
                        return timing;
                    }catch(java.io.IOException|IllegalArgumentException|java.time.DateTimeException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid archived timing");}
                }
                if(!"code".equals(kind))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if(codeArtifacts==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                var artifact=codeArtifacts.find(scope,taskId,operation).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived code unavailable"));
                String source=(String)artifact.get("content"),hash=com.chatchat.common.runtime.coding.PythonCodeProject.hash(source);
                if(!execution.codeHash().equals(hash)||!hash.equals(artifact.get("sha256")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Archived code hash differs from execution");
                var decoded=com.chatchat.common.runtime.coding.PythonCodeProject.decode(source);
                Object files;
                if(decoded.isPresent())try {
                    files=com.chatchat.common.runtime.coding.PythonCodeProject.validate(mapper.readValue(decoded.get(),com.chatchat.common.runtime.coding.PythonCodeProject.Project.class)).files();
                }catch(java.io.IOException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid archived source project");}
                else files=List.of(Map.of("path",artifact.get("fileName"),"content",source));
                return Map.of("source","IMMUTABLE_CODE_ARTIFACT","taskId",taskId,"operationId",operation,"sha256",hash,"files",files);
            }
            var result=new LinkedHashMap<String,Object>();result.put("source","DURABLE_CODING_JOURNAL");result.put("taskId",taskId);
            result.put("status",outcome.status());result.put("revision",outcome.revision());result.put("cancellationRequested",outcome.cancellationRequested());
            result.put("executions",executions);result.put("diagnosticsAvailable",feedback!=null);result.put("rawLogsAvailable",false);
            result.put("lastEventSequence",outcome.lastEventSequence());return result;
        });
    }

    @PostMapping("/prepare-python")
    public Map<String,Object> prepare(@RequestBody JsonNode body) {
        return bound(body,false,call->{
            if(tasks.privateState(call.scope(),call.taskId(),"text-result-submission")!=null)
                throw new SecurityException("A final text result was already submitted; code execution is closed");
            if(!call.contract().authorization().allowedTools().contains("python_execute")
                ||!call.contract().authorization().allowedSideEffects().contains(CodingTaskContract.SideEffect.CODE_EXECUTION))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Python tool is not authorized");
            String operation=operationId(call,body.path("execution"));
            return mapper.convertValue(tasks.allocateScript(call.scope(),call.taskId(),call.lease(),operation),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        });
    }

    @PostMapping("/execute-python")
    public Map<String, Object> execute(@RequestBody JsonNode body) {
        return bound(body, false, call -> {
            if(tasks.privateState(call.scope(),call.taskId(),"text-result-submission")!=null)
                throw new SecurityException("A final text result was already submitted; code execution is closed");
            CodingTaskContract contract = call.contract();
            if (!contract.authorization().allowedTools().contains("python_execute")
                    || !contract.authorization().allowedSideEffects().contains(CodingTaskContract.SideEffect.CODE_EXECUTION))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Python tool is not authorized");
            JsonNode request = body.path("execution");
            String operationId = operationId(call, request);
            var script=tasks.allocateScript(call.scope(),call.taskId(),call.lease(),operationId);
            if(request.hasNonNull("script") && !script.equals(mapper.convertValue(request.path("script"),com.chatchat.common.runtime.coding.CodingScript.class)))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Script allocation differs from Runtime assignment");
            String requested=request.path("fileName").asText("");
            if(!script.fileName().equals(requested) && !(com.chatchat.common.runtime.coding.CodingScript.LEGACY_FILE.equals(requested)&&!request.hasNonNull("script")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Script file name differs from Runtime assignment");
            String source = request.path("source").asText("");
            if(contract.runtimeContext().containsKey("codeProject")) {
                Object frozen=contract.runtimeContext().get("codeProject");
                String payload=com.chatchat.common.runtime.coding.PythonCodeProject.decode(source,frozen).orElseThrow(()->new SecurityException("Runtime-managed project adapter required"));
                var project=com.chatchat.common.runtime.coding.PythonCodeProject.validate(mapper.convertValue(readProject(payload),com.chatchat.common.runtime.coding.PythonCodeProject.Project.class));
                var original=com.chatchat.common.runtime.coding.PythonCodeProject.validate(mapper.convertValue(readProject(com.chatchat.common.runtime.coding.PythonCodeProject.fromContext(frozen)),com.chatchat.common.runtime.coding.PythonCodeProject.Project.class));
                var paths=project.files().stream().map(com.chatchat.common.runtime.coding.PythonCodeProject.File::path).collect(java.util.stream.Collectors.toSet());
                if(!original.files().stream().allMatch(file->paths.contains(file.path())))throw new SecurityException("Original project files cannot be removed from an execution proposal");
            }
            if (source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 256_000)
                throw new IllegalArgumentException("Python source exceeds the D1 limit");
            if (request.path("codeSha256").isTextual() && !sha(source).equals(request.path("codeSha256").asText()))
                throw new IllegalArgumentException("Python source hash mismatch");
            if(feedback!=null)try{feedback.admit(contract,call.lease(),operationId,sha(source));}
            catch(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService.RepairDeniedException denied){
                throw new ResponseStatusException(HttpStatus.CONFLICT,denied.getMessage());
            }
            // Freeze the exact source before Sandbox admission; rejected code remains inspectable.
            tasks.bindScriptHash(call.scope(),call.taskId(),call.lease(),operationId,sha(source));
            if (codeArtifacts != null) codeArtifacts.commit(contract, operationId, source,
                request.path("codeSha256").isTextual() ? request.path("codeSha256").asText() : null,script);
            com.chatchat.common.runtime.event.RuntimeEventLog.execution("PYTHON_CODE_SUBMITTED",call.scope(),call.taskId(),operationId,
                Map.of("status","SUBMITTED","scriptPath",script.scriptPath(),"scriptVersion",script.scriptVersion(),"scriptHash",sha(source)));
            var input = inputs.requireAuthorized(contract);
            plans.validator(contract); // Fail before dispatch if no independent trusted plan exists.
            long executions = contract.budget().maxCodeExecutions();
            if (executions < 1 || contract.budget().maxToolCalls() < 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Python tool budget is unavailable");
            long cpu = CodingBudgetShare.units(contract.budget().maxCpuMillis(), executions);
            long output = CodingBudgetShare.units(contract.budget().maxOutputBytes(), executions);
            var reservation = new CodingTaskOutcome.Counters(0, 0, 1, 1,
                cpu, 0, output, "0");
            McpPythonControlPlanePort.OperationView view;
            try {
                view = python.start(call.scope(), contract, call.lease(), operationId,
                    contract.environmentRef(), "coding-task:" + contract.taskId(), script.fileName(),
                    source, Map.of(), "{}", Map.of(input.fileId(), input.sha256()), reservation);
            } catch (RuntimeException executionFailure) {
                com.chatchat.common.runtime.event.RuntimeEventLog.execution("PYTHON_DISPATCH_UNKNOWN",call.scope(),call.taskId(),operationId,
                    Map.of("status","UNKNOWN","exceptionType",executionFailure.getClass().getSimpleName(),
                        "scriptPath",script.scriptPath(),"scriptVersion",script.scriptVersion(),"scriptHash",sha(source)));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Python operation outcome is unknown; reconcile before retry", executionFailure);
            }
            var response=view(view);response.put("script",script);return response;
        });
    }

    @PostMapping("/get-python")
    public Map<String, Object> get(@RequestBody JsonNode body) {
        return bound(body, true, call -> {
            String operationId = operationId(body.path("operationId").asText(null));
            if(feedback!=null){
                var cached=feedback.cached(call.scope(),call.taskId(),operationId);
                if(cached!=null){
                    var response=feedback.response(cached);var layout=CodingInputLayout.describe(call.contract().runtimeContext());
                    if(!layout.isEmpty())response.put("inputLayout",layout);return withOutputContract(call,response,cached);
                }
            }
            var result = python.get(call.scope(), call.taskId(), operationId);
            if (result.executorStopped() && result.manifestHash() != null) {
                try { python.reconcile(call.scope(), call.taskId(), call.lease(), operationId); }
                catch(com.chatchat.common.runtime.coding.CodingContractException pending) {
                    if(pending.error().code()!=CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN)throw pending;
                    // Poll the same admission until its stop proof is verifiable; never spend a new execution.
                    var response=view(result);
                    response.put("status","RECONCILING");response.put("executorStopped",false);
                    response.put("resultAvailable",false);response.put("reconciliationRequired",true);
                    response.put("diagnosticCode",CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN.name());
                    return response;
                }
            }
            Map<String, Object> response = view(result);
            var layout = CodingInputLayout.describe(call.contract().runtimeContext());
            if (!layout.isEmpty()) response.put("inputLayout", layout);
            if (feedback==null && result.executorStopped() && result.manifestHash()!=null) {
                try { var stderr=python.stderr(call.scope(), call.taskId(), operationId);
                    var stdout=python.stdout(call.scope(),call.taskId(),operationId);
                    // Process output is untrusted data: printing a source row must not
                    // copy it into the next model request. Full logs remain artifacts.
                    response.put("diagnostics", diagnosticMetadata(stderr));
                    response.put("stdoutBytes",stdout==null?0:stdout.getBytes(StandardCharsets.UTF_8).length);
                    response.put("diagnosticPolicy","METADATA_ONLY"); }
                catch (RuntimeException unavailable) {
                    response.put("diagnosticUnavailable", true);
                }
            }
            if ("SUCCEEDED".equals(result.status()) && result.executorStopped()) {
                var input = inputs.requireAuthorized(call.contract());
                CodingEvidenceCollector.Collected collected;
                try { collected = collector.collect(call.scope(), call.contract(), operationId, tasks.scriptFile(call.scope(),call.taskId(),operationId),
                    call.contract().outputContract().requiredFiles().get(0),
                    java.util.List.of(new CodingEvidenceCollector.InputFile(
                        input.fileId(), input.fileName(), input.sha256())),
                    plans.validator(call.contract()));
                } catch (CodingEvidenceCollector.RequiredOutputMissingException missing) {
                    response.put("outputStatus","REQUIRED_OUTPUT_MISSING");
                    response.put("requiredOutput",missing.requiredFile());
                    response.put("resultAvailable",false);
                    response.put("diagnostic","The process succeeded but no contracted result was archived. "
                        +"Use the input descriptor; read JSONL as one JSON object per line. "
                        +"Stdout and auxiliary file contents are not returned to the model: repeating print/file probes cannot reveal source samples. "
                        +"Inspect and parse data within the same Python program that computes and writes the result. "
                        +"Each call starts a fresh sandbox: write the required JSONL file under /workspace/output/results/ in the same program.");
                    return withFeedback(call,result,response);
                } catch(CodingEvidenceCollector.InvalidStructuredResultException invalid) {
                    response.put("outputStatus","INVALID_RESULT_SCHEMA");response.put("resultAvailable",false);
                    response.put("diagnostic","Structured result does not match the contracted row schema; repair field names and types using the code-version repair protocol returned by python_execute. Do not combine source with repair.");return withFeedback(call,result,response);
                }
                if (collected.validationStatus() == com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus.FAILED) {
                    if (collector.copiedSourceResult(call.scope(), call.taskId(), collected.validationRecordRef())
                            && rejectSourceCopy(call, collected)) {
                        response.put("outputStatus", "SOURCE_COPY_REJECTED");
                        response.put("resultAvailable", false);
                        response.put("validationStatus", "FAILED");
                        response.put("replanningRequired", true);
                        response.put("diagnostic", "The immutable-input comparison rejected a raw source copy. "
                            + "This Coding delegation has ended without admitting a dataset. Runtime must revise the analytical objective "
                            + "and output contract under a new requestId; do not repeat source inspection or source-row reproduction.");
                        return withFeedback(call,result,response);
                    }
                    response.put("outputStatus", "RESULT_VALIDATION_FAILED");
                    response.put("resultAvailable", false);
                    response.put("validationStatus", "FAILED");
                    response.put("diagnostic", "Result validation failed. Match the contracted field names and types, "
                        + "and replace bulk source text with metrics and concise findings calculated in Python. "
                        + "Unchanged source documents or record collections are not analytical results. Use short quotations or input row references alongside computed findings. Original long-form analysis is allowed; do not split raw documents across fields or rows. "
                        + "Repair using the code-version protocol returned by python_execute and execute again. Do not combine source with repair.");
                    return withFeedback(call,result,response);
                }
                response.put("inputSnapshotRef", collected.inputSnapshotRef());
                response.put("codeArtifactRef", collected.codeArtifactRef());
                response.put("executionReceiptRef", collected.executionReceiptRef());
                response.put("resultDatasetRef", collected.resultDatasetRef());
                response.put("validationRecordRef", collected.validationRecordRef());
                response.put("evidenceBundleRef", collected.evidenceBundleRef());
                response.put("resultAvailable", true);
                response.put("businessValidationPassed", collected.businessValidationPassed());
                response.put("validationStatus", collected.validationStatus().name());
                if(VisualReportMode.active(call.contract().runtimeContext())) {
                    var visual=collector.visualReport(call.scope(),call.taskId(),collected.resultDatasetRef());
                    if(visual.isEmpty() && plans.requiresVisualReport(call.contract())
                            || visual.isPresent() && "FAILED".equals(visual.get().get("artifactValidationStatus"))) {
                        response.put("resultAvailable",false);
                        response.put("outputStatus","VISUAL_REPORT_INVALID");
                        response.put("diagnostic","Visual report artifact contract failed. Inspect artifact_read visualReportValidation, repair only the technical report schema, and preserve actual failed or unresolved business checks.");
                    }
                }
            }
            return withFeedback(call,result,response);
        });
    }

    @PostMapping("/cancel-python")
    public Map<String, Object> cancel(@RequestBody JsonNode body) {
        return bound(body, true, call -> {
            String operationId = operationId(body.path("operationId").asText(null));
            return view(python.cancelExecution(call.scope(), call.taskId(), operationId));
        });
    }

    private record Call(KernelDataScope scope, String taskId, CodingTaskContract contract,
                        CodingJournal.Lease lease) { }

    private Map<String,Object> withFeedback(Call call,McpPythonControlPlanePort.OperationView operation,Map<String,Object> response){
        if(feedback!=null&&operation.executorStopped()&&operation.manifestHash()!=null){
            var completed=feedback.finish(call.contract(),call.lease(),operation,response);
            response.putAll(feedback.response(completed));
            return withOutputContract(call,response,completed);
        }
        return response;
    }
    private Map<String,Object> withOutputContract(Call call,Map<String,Object> response,
            com.chatchat.common.runtime.coding.ExecutionFeedback completed) {
        if(completed.repair().allowed() && "OUTPUT_CONTRACT".equals(completed.error()==null?null:completed.error().category())) {
            var schema=plans.rowSchema(call.contract());
            if(!schema.isEmpty())response.put("expectedOutput",Map.of("rowSchema",schema,
                "requiredFiles",call.contract().outputContract().requiredFiles(),
                "instruction","Preserve the existing computation unless you identify a concrete calculation error. Write results into exactly this report row schema; put tables and computed summaries inside reportMarkdown. Do not replace report rows with a different metrics schema or repeat data acquisition. Business correctness has not been independently certified."));
        }
        return response;
    }

    private boolean rejectSourceCopy(Call call, CodingEvidenceCollector.Collected collected) {
        var current = tasks.get(call.scope(), call.taskId());
        if (current.status().terminal() || current.cancellationRequested()
                || current.executionControl().stopReason() != null) return false;
        if (!tasks.unresolvedOperations(call.scope(), call.taskId()).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Rejected result requires execution reconciliation before task revision");
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (at.isBefore(current.updatedAt())) at = current.updatedAt();
        var proposal = new CodingTaskOutcome(current.schemaVersion(), current.taskId(), current.scope(),
            current.contractSha256(), CodingTaskOutcome.Status.FAILED, current.revision() + 1,
            current.lastEventSequence() + 1, current.createdAt(), at, current.startedAt(), at, false,
            CodingErrorCode.OUTPUT_CONTRACT_VIOLATION,
            "Source copy rejected; Runtime must revise the analytical objective and output contract",
            List.of("No analytical dataset was admitted; raw source copies cannot be returned to the model"),
            current.inputSnapshotRef(), collected.codeArtifactRef(), collected.executionReceiptRef(), null,
            collected.validationRecordRef(), collected.evidenceBundleRef(), CodingTaskOutcome.ValidationStatus.FAILED,
            current.reproducibility(), current.usage(), new CodingTaskOutcome.ExecutionControl(null, null, true, false),
            current.parentTaskId(), current.supersedesTaskId(), Map.of());
        // Only this server-verified transition receives EVIDENCE; the worker callback
        // retains READ/WORKER/STOP and cannot submit arbitrary evidence or outcomes.
        var committed = identity.as(call.scope(), EnumSet.of(CodingTaskAccess.Action.READ, CodingTaskAccess.Action.EVIDENCE),
            () -> tasks.commitOutcome(call.scope(), call.taskId(), call.lease(), current.revision(), proposal));
        return committed.status() == CodingTaskOutcome.Status.FAILED
            && committed.reasonCode() == CodingErrorCode.OUTPUT_CONTRACT_VIOLATION;
    }

    private <T> T bound(JsonNode body, boolean allowStop, Function<Call, T> action) {
        try {
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Callback body is required");
            KernelDataScope scope = mapper.treeToValue(body.path("scope"), KernelDataScope.class);
            String taskId = body.path("taskId").asText(null);
            String hash = body.path("contractHash").asText(null);
            long token = body.path("fencingToken").asLong(-1);
            if (scope == null || taskId == null || hash == null || token < 1)
                throw new IllegalArgumentException("Complete callback binding is required");
            CodingTaskContract contract = mapper.treeToValue(body.path("contract"), CodingTaskContract.class);
            Set<CodingTaskAccess.Action> grants = EnumSet.of(CodingTaskAccess.Action.READ,
                CodingTaskAccess.Action.WORKER, CodingTaskAccess.Action.STOP);
            return identity.as(scope, grants, () -> {
                var current = tasks.get(scope, taskId);
                var lease = tasks.ownership(scope, taskId);
                if (!current.contractSha256().equals(hash) || lease.fencingToken() != token
                        || lease.owner() == null || !lease.owner().startsWith("cline-adapter:")
                        || !lease.leaseUntil().isAfter(Instant.now()) || current.status().terminal()
                        || !allowStop && (current.cancellationRequested()
                            || current.executionControl().stopReason() != null))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Callback task, lease, or stop state is no longer current");
                current.validateAgainst(contract);
                if (contract.authorization().networkPolicy() != CodingTaskContract.NetworkPolicy.NONE)
                    throw new SecurityException("Python execution requires an isolated no-network environment");
                inputs.requireAuthorized(contract);
                if (lease.leaseUntil().isBefore(Instant.now().plusSeconds(60)))
                    lease = tasks.renew(scope, taskId, lease, Duration.ofMinutes(5), current.revision());
                return action.apply(new Call(scope, taskId, contract, lease));
            });
        } catch (ResponseStatusException known) { throw known; }
        catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Coding Python callback binding", invalid);
        }
    }

    private static String operationId(Call call, JsonNode request) {
        String toolCallId = request.path("toolCallId").asText(null);
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 512)
            throw new IllegalArgumentException("Bounded tool call ID is required");
        String expected = "cline-" + sha(call.scope().tenantId() + "\0" + call.scope().userId()
            + "\0" + call.scope().runId() + "\0" + call.taskId() + "\0" + toolCallId);
        String supplied = operationId(request.path("operationId").asText(null));
        if (!expected.equals(supplied)) throw new SecurityException("Python operation ID is not bound to the tool call");
        return supplied;
    }
    private static String operationId(String value) {
        if (value == null || !value.matches("cline-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Coding Python operation ID is invalid");
        return value;
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<String, Object> view(McpPythonControlPlanePort.OperationView operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationId", operation.operationId());
        result.put("status", operation.status());
        result.put("executorStopped", operation.executorStopped());
        result.put("exitCode", operation.exitCode());
        result.put("technicalError", operation.failure()==null?null:diagnosticMetadata(operation.failure()));
        result.put("codeHash", operation.codeHash());
        result.put("manifestHash", operation.manifestHash());
        return result;
    }

    static Map<String,Object> diagnosticMetadata(String stderr) {
        Map<String,Object> diagnostic=new LinkedHashMap<>();
        diagnostic.put("archived",stderr!=null);
        if(stderr==null)return diagnostic;
        diagnostic.put("bytes",stderr.getBytes(StandardCharsets.UTF_8).length);
        var error=java.util.regex.Pattern.compile("(?m)^(AssertionError|AttributeError|EOFError|ImportError|ModuleNotFoundError|IndexError|KeyError|NameError|UnboundLocalError|OSError|FileNotFoundError|PermissionError|RuntimeError|NotImplementedError|RecursionError|StopIteration|SyntaxError|IndentationError|TabError|TypeError|ValueError|UnicodeError|UnicodeDecodeError|UnicodeEncodeError|ZeroDivisionError|OverflowError|MemoryError|TimeoutError)(?=:|$)").matcher(stderr);
        while(error.find())diagnostic.put("exceptionType",error.group(1));
        var line=java.util.regex.Pattern.compile("(?m)^\\s*File \"/workspace/(?:code/)?main\\.py\", line ([0-9]{1,8})").matcher(stderr);
        while(line.find())diagnostic.put("line",Integer.parseInt(line.group(1)));
        var code=java.util.regex.Pattern.compile("(?m)^Runtime diagnostic: ([A-Z_]{1,60})$").matcher(stderr);
        if(code.find()&&com.chatchat.common.runtime.coding.ExecutionFailureClassifier.known(code.group(1))){
            diagnostic.put("diagnosticCode",code.group(1));
            diagnostic.put("guidance",com.chatchat.common.runtime.coding.ExecutionFailureClassifier.guidance(code.group(1)));
        }
        return diagnostic;
    }
}
