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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
    private static final Logger LOG = LoggerFactory.getLogger(ClinePythonCallbackController.class);
    private static final String CODE_FILE = "main.py";
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

    @PostMapping("/execute-python")
    public Map<String, Object> execute(@RequestBody JsonNode body) {
        return bound(body, false, call -> {
            CodingTaskContract contract = call.contract();
            if (!contract.authorization().allowedTools().contains("python_execute")
                    || !contract.authorization().allowedSideEffects().contains(CodingTaskContract.SideEffect.CODE_EXECUTION))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Python tool is not authorized");
            JsonNode request = body.path("execution");
            String operationId = operationId(call, request);
            if (!CODE_FILE.equals(request.path("fileName").asText()))
                throw new IllegalArgumentException("D1 executes only main.py");
            String source = request.path("source").asText("");
            if (source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 256_000)
                throw new IllegalArgumentException("Python source exceeds the D1 limit");
            var input = inputs.requireAuthorized(contract);
            plans.validator(contract); // Fail before dispatch if no independent trusted plan exists.
            long executions = contract.budget().maxCodeExecutions();
            if (executions < 1 || contract.budget().maxToolCalls() < 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Python tool budget is unavailable");
            long cpu = ceil(contract.budget().maxCpuMillis(), executions);
            long output = ceil(contract.budget().maxOutputBytes(), executions);
            var reservation = new CodingTaskOutcome.Counters(0, 0, 1, 1,
                cpu, 0, output, "0");
            McpPythonControlPlanePort.OperationView view;
            try {
                view = python.start(call.scope(), contract, call.lease(), operationId,
                    contract.environmentRef(), "coding-task:" + contract.taskId(), CODE_FILE,
                    source, Map.of(), "{}", Map.of(input.fileId(), input.sha256()), reservation);
            } catch (RuntimeException executionFailure) {
                LOG.warn("Bound Coding Python operation {} was not acknowledged by the MCP control plane",
                    operationId, executionFailure);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Python operation outcome is unknown; reconcile before retry", executionFailure);
            }
            return view(view);
        });
    }

    @PostMapping("/get-python")
    public Map<String, Object> get(@RequestBody JsonNode body) {
        return bound(body, true, call -> {
            String operationId = operationId(body.path("operationId").asText(null));
            var result = python.get(call.scope(), call.taskId(), operationId);
            if (result.executorStopped() && result.manifestHash() != null)
                python.reconcile(call.scope(), call.taskId(), call.lease(), operationId);
            Map<String, Object> response = view(result);
            if (result.executorStopped() && result.manifestHash()!=null) {
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
                try { collected = collector.collect(call.scope(), call.contract(), operationId, CODE_FILE,
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
                        +"Each call starts a fresh sandbox: write the required JSONL file under /workspace/output/results/ in the same program.");
                    return response;
                } catch(CodingEvidenceCollector.InvalidStructuredResultException invalid) {
                    response.put("outputStatus","INVALID_RESULT_SCHEMA");response.put("resultAvailable",false);
                    response.put("diagnostic","Structured result does not match the contracted row schema; repair field names and types in a complete new program.");return response;
                }
                response.put("inputSnapshotRef", collected.inputSnapshotRef());
                response.put("codeArtifactRef", collected.codeArtifactRef());
                response.put("executionReceiptRef", collected.executionReceiptRef());
                response.put("resultDatasetRef", collected.resultDatasetRef());
                response.put("validationRecordRef", collected.validationRecordRef());
                response.put("evidenceBundleRef", collected.evidenceBundleRef());
                response.put("businessValidationPassed", collected.businessValidationPassed());
                response.put("validationStatus", collected.validationStatus().name());
            }
            return response;
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
    private static long ceil(long amount, long count) {
        return amount / count + (amount % count == 0 ? 0 : 1);
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
        return diagnostic;
    }
}
