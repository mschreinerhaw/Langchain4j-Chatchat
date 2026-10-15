package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            if ("FAILED".equals(result.status()) && result.executorStopped()) {
                try { response.put("stderr", python.stderr(call.scope(), call.taskId(), operationId)); }
                catch (RuntimeException unavailable) {
                    response.put("diagnosticUnavailable", true);
                }
            }
            if ("SUCCEEDED".equals(result.status()) && result.executorStopped()) {
                var input = inputs.requireAuthorized(call.contract());
                var collected = collector.collect(call.scope(), call.contract(), operationId, CODE_FILE,
                    call.contract().outputContract().requiredFiles().get(0),
                    java.util.List.of(new CodingEvidenceCollector.InputFile(
                        input.fileId(), input.fileName(), input.sha256())),
                    plans.validator(call.contract()));
                response.put("inputSnapshotRef", collected.inputSnapshotRef());
                response.put("codeArtifactRef", collected.codeArtifactRef());
                response.put("executionReceiptRef", collected.executionReceiptRef());
                response.put("resultDatasetRef", collected.resultDatasetRef());
                response.put("validationRecordRef", collected.validationRecordRef());
                response.put("evidenceBundleRef", collected.evidenceBundleRef());
                response.put("businessValidationPassed", collected.businessValidationPassed());
            }
            return response;
        });
    }

    @PostMapping("/cancel-python")
    public Map<String, Object> cancel(@RequestBody JsonNode body) {
        return bound(body, true, call -> {
            String operationId = operationId(body.path("operationId").asText(null));
            return view(python.cancel(call.scope(), call.taskId(), operationId, "Runtime Coding cancellation"));
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
        result.put("technicalError", operation.failure());
        result.put("codeHash", operation.codeHash());
        result.put("manifestHash", operation.manifestHash());
        return result;
    }
}
