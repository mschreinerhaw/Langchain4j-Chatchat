package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A2 admission followed by the MCP node's idempotent Docker operation. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class CodingPythonExecutionBridge {
    private final DurableCodingTaskService tasks;
    private final McpPythonControlPlanePort python;
    private final ObjectMapper mapper;

    public CodingPythonExecutionBridge(DurableCodingTaskService tasks, McpPythonControlPlanePort python, ObjectMapper mapper) {
        this.tasks = tasks; this.python = python; this.mapper = mapper;
    }

    public McpPythonControlPlanePort.OperationView start(KernelDataScope scope, CodingTaskContract contract,
            CodingJournal.Lease lease, String operationId, String environmentId, String assetId,
            String fileName, String source, Map<String,Object> parameters, String inputSchemaJson,
            Map<String,String> authorizedFiles, CodingTaskOutcome.Counters reservation) {
        Objects.requireNonNull(scope); Objects.requireNonNull(contract); Objects.requireNonNull(lease);
        Objects.requireNonNull(source); Objects.requireNonNull(reservation);
        if (!contract.scope().tenantId().equals(scope.tenantId())
                || !contract.scope().userId().equals(scope.userId())
                || !contract.scope().runId().equals(scope.runId())) throw new SecurityException("Coding task scope mismatch");
        CodingTaskOutcome snapshot = tasks.get(scope, contract.taskId());
        snapshot.validateAgainst(contract);
        if (!contract.authorization().allowedSideEffects().contains(CodingTaskContract.SideEffect.CODE_EXECUTION)
                || !contract.environmentRef().equals(environmentId)) throw new SecurityException("Code execution is not authorized");
        Map<String,String> files = authorizedFiles == null ? Map.of() : Map.copyOf(authorizedFiles);
        for (String fileId : files.keySet()) {
            if (!contract.authorization().allowedDataRefs().contains(fileId)
                    && !contract.authorization().allowedDataRefs().contains("mcp-python:data-file:" + fileId))
                throw new SecurityException("Input file is outside the task authorization");
        }
        McpPythonControlPlanePort.EnvironmentView environment = python.environment(environmentId);
        if (environment == null || !"PUBLISHED".equals(environment.status())) throw new IllegalStateException("Python environment is unavailable");
        if (contract.authorization().networkPolicy() == CodingTaskContract.NetworkPolicy.NONE
                && !"NONE".equals(environment.networkPolicy())) throw new SecurityException("Python network policy exceeds task authorization");
        if ("NAMED".equals(environment.networkPolicy())
                && !contract.authorization().networkAllowlist().contains(environment.networkName()))
            throw new SecurityException("Python network is outside task authorization");
        if (reservation.codeExecutions() != 1 || reservation.peakMemoryBytes() != 0)
            throw new IllegalArgumentException("One execution and an explicit cumulative reservation are required");
        String reservationRef = "coding-budget:" + contract.budget().budgetId() + ":" + operationId;
        if (reservationRef.length() > 256) throw new IllegalArgumentException("Budget reservation reference exceeds node protocol");
        String sourceHash = hash(source.getBytes(StandardCharsets.UTF_8));
        String intentHash = hash(json(Map.of("operationId", operationId, "environmentId", environmentId,
                "sourceHash", sourceHash, "inputHashes", files, "reservation", reservation)));
        var reserved = tasks.reserve(scope, contract.taskId(), lease, snapshot.revision(), operationId, intentHash, reservation);
        if (!reserved.admitted()) throw new IllegalStateException("Coding budget reservation was denied");
        String handle = "mcp-python:" + hash((scope.tenantId() + "\u0000" + scope.userId() + "\u0000"
                + scope.runId() + "\u0000" + contract.taskId() + "\u0000" + operationId).getBytes(StandardCharsets.UTF_8));
        var intent = tasks.startIntent(scope, contract.taskId(), lease, reserved.outcome().revision(), operationId, handle);
        if (!intent.dispatchIntentCreated())
            return python.get(scope.tenantId(), scope.userId(), scope.runId(), contract.taskId(), operationId);
        return python.start(new McpPythonControlPlanePort.StartRequest(scope.tenantId(), scope.userId(), scope.runId(),
                contract.taskId(), operationId, 1, lease.fencingToken(), environmentId, assetId, fileName,
                source, parameters == null ? Map.of() : parameters, inputSchemaJson == null ? "{}" : inputSchemaJson,
                new ArrayList<>(files.keySet()), files, sourceHash, contract.budget().deadlineAt(), reservationRef));
    }

    public McpPythonControlPlanePort.OperationView get(KernelDataScope scope, String taskId, String operationId) {
        tasks.get(scope, taskId); // A2 access check before crossing the process boundary.
        return python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
    }

    /** B's archived stderr is the only technical diagnostic returned to the Coding loop. */
    public String stderr(KernelDataScope scope, String taskId, String operationId) {
        tasks.get(scope, taskId);
        var stopped = get(scope, taskId, operationId);
        if (!stopped.executorStopped() || "UNKNOWN".equals(stopped.status()))
            throw new IllegalStateException("Python diagnostic is not archived");
        byte[] bytes = python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId,
            operationId, "logs", "stderr.txt");
        return new String(bytes, 0, Math.min(bytes.length, 8_192), StandardCharsets.UTF_8);
    }

    public McpPythonControlPlanePort.OperationView cancel(KernelDataScope scope, String taskId,
                                                            String operationId, String reason) {
        CodingTaskOutcome stopped = tasks.cancel(scope, taskId, reason);
        if (!stopped.cancellationRequested()) return get(scope, taskId, operationId);
        CodingJournal.Lease current = tasks.ownership(scope, taskId);
        return python.cancel(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId, current.fencingToken());
    }

    /** Reconciles confirmed stop and known lower-bound usage; unknown CPU/cost keep their A2 reservations. */
    public CodingTaskOutcome reconcile(KernelDataScope scope, String taskId, CodingJournal.Lease lease, String operationId) {
        var operation = get(scope, taskId, operationId);
        if (!operation.executorStopped() || operation.manifestHash() == null) return tasks.get(scope, taskId);
        byte[] receipt = python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId,
                "logs", "receipt.json");
        String proofRef = "mcp-python-receipt:" + McpPythonExecutionProofVerifier.sha256(receipt);
        CodingTaskOutcome current = tasks.get(scope, taskId);
        current = tasks.reconcileStopped(scope, taskId, lease, current.revision(), operationId, proofRef);
        try {
            byte[] manifest = python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId,
                    "manifest", "manifest.json");
            long outputBytes = 0;
            for (var item : mapper.readTree(manifest).path("artifacts"))
                outputBytes = Math.addExact(outputBytes, item.path("size").asLong());
            boolean actuallyStarted = mapper.readTree(receipt).path("startedAt").isTextual();
            return tasks.settle(scope, taskId, lease, current.revision(), operationId,
                    new CodingTaskOutcome.Counters(0, 0, 0, actuallyStarted ? 1 : 0,
                            0, 0, outputBytes, "0"), false);
        } catch (Exception ex) { throw new IllegalStateException("Python usage requires reconciliation", ex); }
    }

    private byte[] json(Object value) {
        try { return mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot canonicalize Python execution intent", ex); }
    }
    private String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
