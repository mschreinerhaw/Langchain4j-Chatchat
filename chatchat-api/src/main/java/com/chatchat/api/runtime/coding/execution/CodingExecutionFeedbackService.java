package com.chatchat.api.runtime.coding.execution;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.assets.RuntimeAssetArchive;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;

/** Java owns classification/admission; Cline repairs code in its existing session. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public class CodingExecutionFeedbackService {
    public static final class RepairDeniedException extends IllegalStateException {
        RepairDeniedException(String safeMessage){super(safeMessage);}
    }
    private final DurableCodingTaskService tasks;
    private final CodingPythonExecutionBridge python;
    private final ObjectMapper json;
    private final int maxRepairs;
    private RuntimeAssetArchive archive;
    @Autowired(required=false) public void setArchive(RuntimeAssetArchive value) { archive=value; }
    public CodingExecutionFeedbackService(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            ObjectMapper json, @Value("${chatchat.coding.max-auto-repairs:3}") int maxRepairs) {
        this.tasks=tasks;this.python=python;this.json=json;this.maxRepairs=Math.max(0,Math.min(maxRepairs,31));
    }
    public ExecutionFeedback cached(KernelDataScope scope,String task,String operation) {
        var saved=tasks.privateState(scope,task,"feedback:"+operation);
        return saved==null?null:json.convertValue(saved,ExecutionFeedback.class);
    }
    public List<ExecutionFeedback> timeline(KernelDataScope scope,String task) {
        return tasks.executionFeedback(scope,task);
    }
    public Map<String,Object> response(ExecutionFeedback feedback) {
        var result=new LinkedHashMap<String,Object>();
        result.put("operationId",feedback.executionId());result.put("status",feedback.status());result.put("executorStopped",true);
        result.put("exitCode",feedback.exitCode());result.put("codeHash",feedback.codeHash());result.put("manifestHash",feedback.manifestHash());
        result.putAll(feedback.artifactRefs());result.put("resultAvailable",feedback.resultAvailable());
        result.put("validationStatus",feedback.validationStatus());result.put("businessValidationPassed",feedback.businessValidationPassed());
        if(feedback.outputStatus()!=null)result.put("outputStatus",feedback.outputStatus());
        if(feedback.error()!=null)result.put("diagnostic",feedback.error().message());
        var diagnostics=new LinkedHashMap<String,Object>();diagnostics.put("archived",true);diagnostics.put("bytes",feedback.stderrBytes());
        if(feedback.error()!=null){
            diagnostics.put("exceptionType",feedback.error().exceptionType());diagnostics.put("diagnosticCode",feedback.error().diagnosticCode());
            diagnostics.put("guidance",feedback.error().message());
            if(!feedback.error().stack().isEmpty())diagnostics.put("line",feedback.error().stack().get(feedback.error().stack().size()-1).line());
        }
        result.put("diagnostics",diagnostics);result.put("stdoutBytes",feedback.stdoutBytes());
        result.put("executionFeedback",feedback);result.put("diagnosticPolicy","METADATA_ONLY");
        return result;
    }
    /** Gate each new attempt. A replay of the same operation uses A2/MCP idempotency. */
    public void admit(CodingTaskContract contract,CodingJournal.Lease lease,String operation,String codeHash) {
        var policy=tasks.privateState(contract.scope(),contract.taskId(),"execution-repair-policy");
        if(policy==null)tasks.privateState(contract.scope(),contract.taskId(),lease,"execution-repair-policy",null,
            json.valueToTree(Map.of("maxAutoRepairs",maxRepairs)));
        var previous=tasks.startedCodeOperations(contract.scope(),contract.taskId());
        if(previous.contains(operation)||previous.isEmpty())return;
        var last=cached(contract.scope(),contract.taskId(),previous.get(previous.size()-1));
        if(last==null)throw new RepairDeniedException("RECONCILIATION_REQUIRED: previous Python feedback is not committed");
        if(last.resultAvailable())return; // Existing reviewed tasks may perform another authorized calculation.
        if(!last.repair().allowed())throw new RepairDeniedException("AUTO_REPAIR_DENIED: Runtime policy requires STOP");
        if(Objects.equals(last.codeHash(),codeHash))throw new RepairDeniedException("UNCHANGED_REPAIR: correct the failed program before executing again");
    }
    public ExecutionFeedback finish(CodingTaskContract contract,CodingJournal.Lease lease,
            McpPythonControlPlanePort.OperationView operation,Map<String,Object> result) {
        var saved=cached(contract.scope(),contract.taskId(),operation.operationId());
        if(saved!=null)return saved;
        JsonNode diagnostic=read(()->python.diagnosticRecord(contract.scope(),contract.taskId(),operation.operationId()));
        JsonNode receipt=read(()->python.executionReceipt(contract.scope(),contract.taskId(),operation.operationId()));
        String exception=diagnostic.path("exceptionType").asText(null);
        // Only Python built-in exception identifiers survive the sandbox boundary.
        if(exception!=null&&!exception.matches("[A-Za-z]{1,40}(Error|Exception)|StopIteration|SystemExit|KeyboardInterrupt"))exception=null;
        String code=diagnostic.path("diagnosticCode").asText();
        if(!ExecutionFailureClassifier.known(code))code=ExecutionFailureClassifier.code(exception);
        boolean resultAvailable=Boolean.TRUE.equals(result.get("resultAvailable"));
        boolean outputFailed="SUCCEEDED".equals(operation.status())&&!resultAvailable;
        String category=ExecutionFailureClassifier.category(code);
        if(outputFailed){code="VISUAL_REPORT_INVALID".equals(result.get("outputStatus"))?"VISUAL_REPORT_INVALID":"OUTPUT_CONTRACT";category="OUTPUT_CONTRACT";}
        if(receipt.path("oomKilled").asBoolean()){code="RESOURCE_EXHAUSTED";category="RESOURCE";}
        if("TIMED_OUT".equals(operation.status())){code="TIMEOUT";category="TIMEOUT";}
        if("CANCELLED".equals(operation.status())){code="CANCELLED";category="CANCELLED";}
        boolean failed=!resultAvailable;
        var operations=tasks.startedCodeOperations(contract.scope(),contract.taskId());
        int attempt=Math.max(1,operations.indexOf(operation.operationId())+1);
        int repairsUsed=0;
        if(attempt>1){
            var previous=cached(contract.scope(),contract.taskId(),operations.get(attempt-2));
            if(previous!=null)repairsUsed=previous.repair().attemptsUsed()+(previous.resultAvailable()?0:1);
        }
        int repairLimit=(int)Math.min(maxRepairs,Math.max(0,contract.budget().maxCodeExecutions()-1));
        var policy=tasks.privateState(contract.scope(),contract.taskId(),"execution-repair-policy");
        if(policy!=null)repairLimit=Math.min(repairLimit,policy.path("maxAutoRepairs").asInt(0));
        if(!operations.isEmpty()){
            var first=cached(contract.scope(),contract.taskId(),operations.get(0));
            if(first!=null)repairLimit=Math.min(repairLimit,first.repair().maxAttempts());
        }
        var state=tasks.get(contract.scope(),contract.taskId());
        boolean stopped=state.cancellationRequested()||state.executionControl().stopReason()!=null||state.status().terminal();
        boolean timeLeft=contract.budget().deadlineAt().isAfter(Instant.now());
        boolean budgetLeft=state.usage().consumed().modelCalls()+state.usage().reserved().modelCalls()<contract.budget().maxModelCalls()
            &&state.usage().consumed().modelTokens()+state.usage().reserved().modelTokens()<contract.budget().maxModelTokens();
        boolean allowed=failed&&!stopped&&timeLeft&&budgetLeft&&repairsUsed<repairLimit&&ExecutionFailureClassifier.repairable(code)
            &&!Boolean.TRUE.equals(result.get("replanningRequired"))&&operation.executorStopped()&&operation.manifestHash()!=null;
        String reason=resultAvailable?"RESULT_VERIFIED":stopped?"TASK_STOPPING":!timeLeft?"DEADLINE_EXCEEDED":!budgetLeft?"BUDGET_EXCEEDED":
            repairsUsed>=repairLimit?"REPAIR_LIMIT_REACHED":allowed?"TECHNICAL_REPAIR":"NON_REPAIRABLE";
        var refs=new LinkedHashMap<String,String>();
        for(String key:List.of("inputSnapshotRef","codeArtifactRef","executionReceiptRef","resultDatasetRef","validationRecordRef","evidenceBundleRef"))
            if(result.get(key) instanceof String ref)refs.put(key,ref);
        String detail="execution:"+operation.operationId()+":logs:runtime-diagnostic.json";
        refs.put("diagnosticRef",detail);refs.put("stderrRef","execution:"+operation.operationId()+":logs:stderr.txt");
        String scriptFile=tasks.scriptFile(contract.scope(),contract.taskId(),operation.operationId());
        if(scriptFile==null)scriptFile=com.chatchat.common.runtime.coding.CodingScript.LEGACY_FILE;
        var frames=new ArrayList<ExecutionFeedback.Frame>();
        for(var frame:diagnostic.path("stack"))if(frames.size()<16&&scriptFile.equals(frame.path("file").asText())&&frame.path("line").asInt()>0)
            frames.add(new ExecutionFeedback.Frame(scriptFile,frame.path("line").asInt(),null));
        if(frames.isEmpty()&&diagnostic.path("line").asInt()>0)frames.add(new ExecutionFeedback.Frame(scriptFile,diagnostic.path("line").asInt(),null));
        Long duration=null;
        try{duration=Math.max(0,Duration.between(Instant.parse(receipt.path("startedAt").asText()),Instant.parse(receipt.path("finishedAt").asText())).toMillis());}
        catch(java.time.DateTimeException missing){/* Missing metering remains explicitly unknown. */}
        String guidance=failed?ExecutionFailureClassifier.guidance(code):"Result archived; business correctness remains governed by validationStatus.";
        var inputFiles=new ArrayList<ExecutionFeedback.InputFile>();
        var frozen=tasks.privateState(contract.scope(),contract.taskId(),"inputs:"+operation.operationId());
        if(frozen!=null&&frozen.isArray())for(var file:frozen){
            inputFiles.add(new ExecutionFeedback.InputFile(file.path("fileId").asText(),file.path("fileName").asText(),file.path("sha256").asText()));
        }
        var inputSchema=new LinkedHashMap<String,String>();
        if(contract.runtimeContext().get("inputFiles") instanceof Map<?,?> files)
            for(String key:List.of("format","schemaFileId","manifestFileId","manifestSha256"))
                if(files.get(key) instanceof String value)inputSchema.put(key,value);
        var feedback=new ExecutionFeedback(ExecutionFeedback.VERSION,contract.taskId(),operation.operationId(),contract.taskId()+":execution-"+attempt,
            contract.scope().runId(),operation.status(),resultAvailable?"VALIDATED":outputFailed?"RESULT_VALIDATION_FAILED":"EXECUTION_FAILED",
            operation.exitCode(),duration,count(diagnostic,"cpuTimeMs"),count(diagnostic,"maxRssBytes"),operation.codeHash(),"[raw stdout omitted]",failed?guidance:"[raw stderr omitted]",
            count(diagnostic,"stdoutBytes"),count(diagnostic,"stderrBytes"),
            failed?new ExecutionFeedback.Failure(category,exception,code,guidance,List.copyOf(frames),detail):null,
            List.of(contract.inputSnapshotRef()),contract.outputContract().resultSchemaRef(),
            List.copyOf(inputFiles),Map.copyOf(inputSchema),Map.copyOf(refs),
            outputFailed?"FAILED":String.valueOf(result.getOrDefault("validationStatus","NOT_RUN")),resultAvailable,
            operation.manifestHash(),result.get("outputStatus") instanceof String output?output:null,
            Boolean.TRUE.equals(result.get("businessValidationPassed")),
            new ExecutionFeedback.Repair(allowed,allowed?"REPAIR_IN_CURRENT_CONTEXT":resultAvailable?"COMPLETE":"STOP",reason,repairsUsed,repairLimit,
                operation.codeHash(),guidance),"METADATA_ONLY_V1",tasks.script(contract.scope(),contract.taskId(),operation.operationId()));
        if(archive!=null)try{archive.save(contract.scope(),contract.taskId(),UserAssetService.Kind.LOG,
            "feedback:"+operation.operationId(),"execution-feedback.json",json.writeValueAsBytes(feedback));}
        catch(java.io.IOException invalid){throw new IllegalStateException("Execution feedback archive failed",invalid);}
        if(tasks.recordExecutionFeedback(contract.scope(),contract.taskId(),lease,feedback))log("PYTHON_EXECUTION_FEEDBACK",contract.scope(),feedback);
        return Objects.requireNonNullElse(cached(contract.scope(),contract.taskId(),operation.operationId()),feedback);
    }
    private JsonNode read(java.util.function.Supplier<String> source){
        try{String value=source.get();return value==null?json.createObjectNode():json.readTree(value);}
        catch(Exception missing){return json.createObjectNode();}
    }
    private static Long count(JsonNode value,String name){
        var count=value.path(name);return count.isIntegralNumber()&&count.canConvertToLong()&&count.asLong()>=0?count.asLong():null;
    }
    private void log(String event,KernelDataScope scope,ExecutionFeedback feedback){
        var facts=new LinkedHashMap<String,Object>();facts.put("attemptId",feedback.attemptId());facts.put("status",feedback.status());
        facts.put("stage",feedback.stage());facts.put("codeHash",feedback.codeHash());facts.put("durationMs",feedback.durationMs());
        facts.put("cpuTimeMs",feedback.cpuTimeMs());facts.put("maxRssBytes",feedback.maxRssBytes());
        facts.put("exitCode",feedback.exitCode());facts.put("validationStatus",feedback.validationStatus());facts.put("resultAvailable",feedback.resultAvailable());
        facts.put("repairAttempts",feedback.repair().attemptsUsed());facts.put("repairAllowed",feedback.repair().allowed());
        facts.put("reasonCode",feedback.repair().reason());
        if(feedback.script()!=null){facts.put("scriptPath",feedback.script().scriptPath());facts.put("scriptVersion",feedback.script().scriptVersion());facts.put("scriptHash",feedback.codeHash());}
        if(feedback.error()!=null){facts.put("errorCategory",feedback.error().category());facts.put("exceptionType",feedback.error().exceptionType());
            facts.put("diagnosticCode",feedback.error().diagnosticCode());facts.put("evidenceSnapshotRef",feedback.error().detailRef());}
        com.chatchat.common.runtime.event.RuntimeEventLog.execution(event,scope,feedback.taskId(),feedback.executionId(),facts);
    }
}
