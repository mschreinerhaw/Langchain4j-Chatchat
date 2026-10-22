package com.chatchat.api.runtime.coding.execution;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** A2 admission followed by the MCP node's idempotent Docker operation. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class CodingPythonExecutionBridge {
    private com.chatchat.api.assets.RuntimePythonAssetArchiver assetArchiver;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAssetArchiver(com.chatchat.api.assets.RuntimePythonAssetArchiver value){assetArchiver=value;}
    private final DurableCodingTaskService tasks;
    private final McpPythonControlPlanePort python;
    private final ObjectMapper mapper;
    private CodingDataSupplyService dataSupply;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDataSupply(CodingDataSupplyService value){this.dataSupply=value;}

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
        if(CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())) {
            if(dataSupply==null)throw new SecurityException("Dynamic inputs unavailable");
            dataSupply.bindInputs(contract,lease,operationId);
            var all=new TreeMap<String,String>();dataSupply.mountedFiles(contract,operationId)
                .forEach(s->all.put(s.fileId(),s.sha256()));files=Map.copyOf(all);
        }
        for (String fileId : files.keySet()) {
            if (!contract.authorization().allowedDataRefs().contains(fileId)
                    && !contract.authorization().allowedDataRefs().contains("mcp-python:data-file:" + fileId)
                    && !(dataSupply!=null&&dataSupply.authorizes(contract,operationId,files)))
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
        var environmentSnapshot = contract.runtimeContext().get("pythonEnvironment") instanceof Map<?,?> value ? value : Map.of();
        Integer environmentVersion = environmentSnapshot.get("environmentVersion") instanceof Number version ? version.intValue() : null;
        String imageDigest = environmentSnapshot.get("imageDigest") instanceof String digest ? digest : null;
        return python.start(new McpPythonControlPlanePort.StartRequest(scope.tenantId(), scope.userId(), scope.runId(),
                contract.taskId(), operationId, 1, lease.fencingToken(), environmentId, assetId, fileName,
                source, parameters == null ? Map.of() : parameters, inputSchemaJson == null ? "{}" : inputSchemaJson,
                new ArrayList<>(files.keySet()), files, sourceHash, contract.budget().deadlineAt(), reservationRef,
                environmentVersion, imageDigest,tasks.script(scope,contract.taskId(),operationId)));
    }

    public McpPythonControlPlanePort.OperationView get(KernelDataScope scope, String taskId, String operationId) {
        tasks.get(scope, taskId); // A2 access check before crossing the process boundary.
        var operation=python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
        if(assetArchiver!=null)assetArchiver.stopped(scope,taskId,operation);
        return operation;
    }

    /** B's archived stderr is the only technical diagnostic returned to the Coding loop. */
    public String stderr(KernelDataScope scope, String taskId, String operationId) {
        return diagnostic(scope,taskId,operationId,"stderr.txt");
    }
    public String stdout(KernelDataScope scope,String taskId,String operationId) {
        return diagnostic(scope,taskId,operationId,"stdout.txt");
    }
    public String diagnosticRecord(KernelDataScope scope,String taskId,String operationId) {
        return diagnostic(scope,taskId,operationId,"runtime-diagnostic.json");
    }
    public String executionReceipt(KernelDataScope scope,String taskId,String operationId) {
        return diagnostic(scope,taskId,operationId,"receipt.json");
    }
    private String diagnostic(KernelDataScope scope,String taskId,String operationId,String logName) {
        tasks.get(scope, taskId);
        if(assetArchiver!=null){
            var archived=assetArchiver.log(scope,taskId,operationId,logName);
            if(archived.isPresent())return new String(archived.get(),0,Math.min(archived.get().length,8_192),StandardCharsets.UTF_8);
        }
        var stopped = get(scope, taskId, operationId);
        if (!stopped.executorStopped() || "UNKNOWN".equals(stopped.status()))
            throw new IllegalStateException("Python diagnostic is not archived");
        byte[] bytes = python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId,
            operationId, "logs", logName);
        return new String(bytes, 0, Math.min(bytes.length, 8_192), StandardCharsets.UTF_8);
    }

    public McpPythonControlPlanePort.OperationView cancel(KernelDataScope scope, String taskId,
                                                            String operationId, String reason) {
        CodingTaskOutcome stopped = tasks.cancel(scope, taskId, reason);
        if (stopped.executionControl().stopReason() == null) return get(scope, taskId, operationId);
        CodingJournal.Lease current = tasks.ownership(scope, taskId);
        return python.cancel(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId, current.fencingToken());
    }
    /** Stop one tool operation after transport/SDK interruption without inventing a user task cancellation. */
    public McpPythonControlPlanePort.OperationView cancelExecution(KernelDataScope scope,String taskId,String operationId) {
        tasks.get(scope,taskId);
        if (!tasks.startedCodeOperations(scope, taskId).contains(operationId))
            throw new SecurityException("Only an admitted task operation can be cancelled");
        return python.cancel(scope.tenantId(),scope.userId(),scope.runId(),taskId,operationId,tasks.ownership(scope,taskId).fencingToken());
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
                    new CodingTaskOutcome.Counters(0, 0, tasks.contract(scope,taskId).authorization().allowedTools().contains("python_execute") ? 1 : 0, actuallyStarted ? 1 : 0,
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
