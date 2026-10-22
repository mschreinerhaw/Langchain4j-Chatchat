package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Vendor-neutral Runtime facade; Node SDK is a replaceable, non-authoritative worker. */
public final class ClineCodingAdapter implements CodingAgentSPI {
    private final DurableCodingTaskService tasks;
    private final CodingPythonExecutionBridge python;
    private final CodingInputSnapshotService inputs;
    private final CodingValidationPlanService plans;
    private final CodingEvidenceCollector collector;
    private final ClineModelReceiptStore receipts;
    private final URI node;
    private final String secret;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final java.time.Clock clock;
    private final String workerId = "cline-adapter:" + UUID.randomUUID();
    private CodingWorkspaceService workspace;
    public void setWorkspace(CodingWorkspaceService workspace) { this.workspace = workspace; }
    private CodingDataSupplyService dataSupply;
    public void setDataSupply(CodingDataSupplyService value) {this.dataSupply=value;}
    private McpLicenseEntitlementPort licenseEntitlements = new McpLicenseEntitlementPort() { };
    public void setLicenseEntitlements(McpLicenseEntitlementPort value) {
        this.licenseEntitlements = java.util.Objects.requireNonNull(value);
    }

    public ClineCodingAdapter(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingEvidenceCollector collector, ClineModelReceiptStore receipts,
            URI node, String secret, ObjectMapper mapper) {
        this(tasks,python,inputs,plans,collector,receipts,node,secret,mapper,java.time.Clock.systemUTC());
    }
    ClineCodingAdapter(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingEvidenceCollector collector, ClineModelReceiptStore receipts,
            URI node, String secret, ObjectMapper mapper, java.time.Clock clock) {
        this.tasks = tasks; this.python = python; this.inputs = inputs; this.plans = plans;
        this.collector = collector; this.receipts = receipts;
        this.node = node; this.secret = secret; this.mapper = mapper;
        this.clock=clock;
        if (node == null || !"http".equals(node.getScheme()) || node.getHost() == null
                || !List.of("localhost", "127.0.0.1").contains(node.getHost()) || node.getUserInfo() != null)
            throw new IllegalArgumentException("D1 Node SDK service must use explicit loopback HTTP");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Cline shared secret must have at least 32 characters");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CodingTaskOutcome submit(CodingTaskContract contract) {
        requireDispatchLicense();
        if (CodingWorkspaceService.supports(contract)) {
            if (workspace == null) throw new IllegalStateException("Workspace Coding profile is not enabled");
            workspace.requireAuthorized(contract);
        } else {
        boolean dynamic=CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion());
        if(dynamic) {
            if(dataSupply==null)throw new SecurityException("Dynamic analysis is unavailable");
            if(contract.authorization().allowedTools().contains("data_request"))dataSupply.requireAuthorized(contract);
        }
        if (!(dynamic?java.util.Set.of("python_execute","artifact_read","data_request","sql_validate"):java.util.Set.of("python_execute")).containsAll(contract.authorization().allowedTools()))
            throw new IllegalArgumentException("D1 permits only the implemented Runtime Python callback");
        inputs.requireAuthorized(contract);
        plans.validator(contract);
        if (contract.authorization().allowedTools().contains("python_execute")
                && (contract.outputContract().requiredFiles().size() != 1
                    || !contract.outputContract().requiredFiles().get(0)
                        .matches("[A-Za-z0-9_-]+\\.jsonl")))
            throw new IllegalArgumentException("D1 Python execution requires one JSONL result file");
        if (contract.authorization().networkPolicy() != CodingTaskContract.NetworkPolicy.NONE)
            throw new SecurityException("D1 Coding Sandbox requires a no-network authorization");
        }
        requireInputCapabilities(contract);
        CodingTaskOutcome accepted = tasks.submit(contract); // A2 persists acceptance before any Node dispatch.
        if (accepted.status().terminal() || accepted.executionControl().stopReason() != null) return accepted;
        KernelDataScope scope = contract.scope();
        CodingJournal.Lease lease = tasks.ownership(scope, contract.taskId());
        if (lease.leaseUntil().isAfter(clock.instant()) && !workerId.equals(lease.owner())) return accepted;
        if (!lease.leaseUntil().isAfter(clock.instant()))
            lease = tasks.claim(scope, contract.taskId(), workerId, Duration.ofSeconds(90), accepted.revision());
        accepted = tasks.get(scope, contract.taskId());
        if (accepted.status().terminal() || accepted.executionControl().stopReason() != null) return accepted;
        // Unknown starts belong to recovery; another model request must never be invented by submit.
        if (tasks.hasStartedOperations(scope, contract.taskId())) return tasks.get(scope, contract.taskId());
        request("/v1/runs", Map.of("contract", contract, "contractHash", CodingCanonicalHash.sha256(contract),
            "fencingToken", lease.fencingToken()));
        return tasks.get(scope, contract.taskId());
    }

    @Override public CodingTaskOutcome get(KernelDataScope scope, String taskId) { return tasks.get(scope, taskId); }

    @Override public CodingTaskOutcome cancel(KernelDataScope scope, String taskId, String reason) {
        CodingTaskOutcome stopped = tasks.cancel(scope, taskId, reason);
        CodingJournal.Lease lease = tasks.ownership(scope, taskId);
        try { request("/v1/runs/cancel", Map.of("scope", scope, "taskId", taskId,
            "contractHash", stopped.contractSha256(), "fencingToken", lease.fencingToken())); }
        catch (RuntimeException unavailable) { /* A2 stop request is durable; still stop any known Python process. */ }
        for (String operationId : tasks.unresolvedOperations(scope, taskId)) {
            if (!operationId.matches("cline-[0-9a-f]{64}")) continue;
            try { python.cancel(scope, taskId, operationId, reason); }
            catch (RuntimeException unavailable) { /* Leave reconciliationRequired/stop request visible to Runtime. */ }
        }
        return tasks.get(scope, taskId);
    }

    @Override public CodingTaskOutcome resume(KernelDataScope scope, String taskId) {
        CodingTaskOutcome current = tasks.resume(scope, taskId);
        if (current.status().terminal()) return current;
        CodingJournal.Lease lease = tasks.ownership(scope, taskId);
        if (!lease.leaseUntil().isAfter(clock.instant())) {
            lease = tasks.claim(scope, taskId, workerId, Duration.ofMinutes(5), current.revision());
            current = tasks.get(scope, taskId);
        }
        if (current.executionControl().stopReason() != null) {
            try { request("/v1/runs/cancel", Map.of("scope", scope, "taskId", taskId,
                "contractHash", current.contractSha256(), "fencingToken", lease.fencingToken())); }
            catch (RuntimeException unavailable) { /* Keep the durable stop request for later reconciliation. */ }
            for (String operationId : tasks.unresolvedOperations(scope, taskId)) {
                if (!operationId.matches("cline-[0-9a-f]{64}")) continue;
                try { python.cancel(scope, taskId, operationId, "Runtime stop request"); }
                catch (RuntimeException unavailable) { /* MCP outcome remains unknown. */ }
            }
        }
        JsonNode remote = null;
        boolean sessionMissing = false;
        try { remote = request("/v1/runs/get", Map.of("scope", scope, "taskId", taskId,
            "contractHash", current.contractSha256(), "fencingToken", lease.fencingToken())); }
        catch (NodeHttpException unavailable) { sessionMissing = unavailable.status == 404; }
        catch (RuntimeException unavailable) { /* A2 remains authoritative; unknown Node state cannot be replayed. */ }
        if (remote != null && remote.path("fencingToken").asLong() < lease.fencingToken()
                && "RUNNING".equals(remote.path("status").asText())) {
            try { request("/v1/runs/cancel", Map.of("scope", scope, "taskId", taskId,
                "contractHash", current.contractSha256(), "fencingToken", lease.fencingToken())); }
            catch (RuntimeException unavailable) { /* Old worker remains fenced by A2. */ }
        }
        // A current live SDK owns settlement. Racing its callbacks with recovery
        // advances the same revision twice and can cancel a healthy invocation.
        // A missing/ended/fenced session is the boundary that permits recovery.
        boolean recoveryOwned = sessionMissing || remote != null
            && (remote.path("fencingToken").asLong() < lease.fencingToken()
                || List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText()));
        if (!recoveryOwned) return tasks.get(scope, taskId);
        // Only Python operations have MCP execution identities. Model starts retain their
        // reservations until the receipt can be reconciled; they are never dispatched again.
        for (String operationId : tasks.unresolvedOperations(scope, taskId)) {
            if(operationId.matches("data-[0-9a-f]{64}")) {
                if(dataSupply!=null&&workerId.equals(lease.owner()))dataSupply.recover(tasks.contract(scope,taskId),lease,operationId);
                continue;
            }
            if (operationId.matches("model-[1-9][0-9]{0,5}")) {
                if (workerId.equals(lease.owner())) reconcileModel(scope, taskId, lease, operationId);
                continue;
            }
            if (!operationId.matches("cline-[0-9a-f]{64}")) continue;
            try {
                var operation = python.get(scope, taskId, operationId);
                if (workerId.equals(lease.owner()) && operation.executorStopped()
                        && operation.manifestHash() != null)
                    python.reconcile(scope, taskId, lease, operationId);
            } catch (RuntimeException unavailable) { return tasks.get(scope, taskId); }
        }
        current = tasks.get(scope, taskId);
        // A live SDK is reused above. The SDK has no cross-process reconnect API;
        // only a Java-committed, fully settled boundary can seed a NEW instance.
        if (sessionMissing && workerId.equals(lease.owner())
                && current.executionControl().stopReason() == null
                && tasks.unresolvedOperations(scope, taskId).isEmpty()) {
            JsonNode checkpoint = tasks.sdkCheckpoint(scope, taskId);
            if (checkpoint != null) {
                requireInputCapabilities(tasks.contract(scope,taskId));
                request("/v1/runs", Map.of("contract", tasks.contract(scope, taskId),
                    "contractHash", current.contractSha256(), "fencingToken", lease.fencingToken(),
                    "checkpoint", checkpoint));
                return tasks.get(scope, taskId);
            }
        }
        // A durable stop rejects new model/tool admissions. Once every started operation has
        // verified stop evidence, a lost SDK session must not keep cancellation pending forever.
        if (workerId.equals(lease.owner()) && current.executionControl().stopReason() != null
                && (sessionMissing || remote != null && List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText()))
                && tasks.unresolvedOperations(scope, taskId).isEmpty())
            return tasks.finishStoppedAfterReconciliation(scope, taskId, lease, current.revision());
        if (workerId.equals(lease.owner()) && current.executionControl().stopReason() == null
                && tasks.unresolvedOperations(scope, taskId).isEmpty()
                && tasks.hasStartedOperations(scope, taskId)
                && (sessionMissing || remote != null
                    && List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText()))) {
            CodingTaskOutcome archived = completeVerifiedOperation(scope, taskId, lease);
            if (archived != null) return archived;
            var operations=tasks.startedCodeOperations(scope,taskId);
            if(!operations.isEmpty()) {
                String operationId=operations.get(operations.size()-1);
                var operation=python.get(scope,taskId,operationId);
                if(operation.executorStopped() && operation.manifestHash()!=null
                        && java.util.Set.of("FAILED","CANCELLED","TIMED_OUT").contains(operation.status())
                        && tasks.verifiedOperation(scope,taskId,operationId).isPresent()) {
                    var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                    if(at.isBefore(current.updatedAt()))at=current.updatedAt();
                    var failed=new CodingTaskOutcome(current.schemaVersion(),current.taskId(),current.scope(),current.contractSha256(),
                        CodingTaskOutcome.Status.FAILED,current.revision()+1,current.lastEventSequence()+1,current.createdAt(),at,current.startedAt(),at,
                        false,CodingErrorCode.EXECUTION_FAILED,"Worker session ended after a verified failed execution; no safe SDK checkpoint is available",
                        List.of("Execution diagnostics remain archived; retry through the existing parent workflow"),current.inputSnapshotRef(),
                        null,null,null,null,null,CodingTaskOutcome.ValidationStatus.NOT_RUN,current.reproducibility(),current.usage(),
                        new CodingTaskOutcome.ExecutionControl(null,null,true,false),current.parentTaskId(),current.supersedesTaskId(),Map.of());
                    return commitRecoveredOutcome(scope,taskId,lease,current.revision(),failed);
                }
            }
            // The SDK has ended and all physical starts have stop proofs, but no
            // checkpoint or usable result survived. Waiting cannot produce more work.
            current = tasks.get(scope, taskId);
            var at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            if (at.isBefore(current.updatedAt())) at = current.updatedAt();
            var limitations = new java.util.ArrayList<>(current.limitations());
            limitations.add("Worker ended without a completed, independently verified result");
            var failed = new CodingTaskOutcome(current.schemaVersion(),current.taskId(),current.scope(),current.contractSha256(),
                CodingTaskOutcome.Status.FAILED,current.revision()+1,current.lastEventSequence()+1,current.createdAt(),at,current.startedAt(),at,
                false,CodingErrorCode.EXECUTION_FAILED,"Worker session ended without a recoverable result; all started operations reconciled",
                limitations,current.inputSnapshotRef(),null,null,null,null,null,CodingTaskOutcome.ValidationStatus.NOT_RUN,
                current.reproducibility(),current.usage(),new CodingTaskOutcome.ExecutionControl(null,null,true,false),
                current.parentTaskId(),current.supersedesTaskId(),Map.of());
            return commitRecoveredOutcome(scope,taskId,lease,current.revision(),failed);
        }
        if (workerId.equals(lease.owner()) && current.executionControl().stopReason() == null
                && !tasks.hasStartedOperations(scope, taskId)
                && (sessionMissing || remote != null && remote.path("fencingToken").asLong() < lease.fencingToken()
                    && List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText()))) {
            CodingTaskContract contract = tasks.contract(scope, taskId);
            requireInputCapabilities(contract);
            request("/v1/runs", Map.of("contract", contract, "contractHash", current.contractSha256(),
                "fencingToken", lease.fencingToken()));
        }
        return tasks.get(scope, taskId);
    }

    /** A lost SDK session cannot replay code, but a verified completed operation is sufficient evidence. */
    private CodingTaskOutcome completeVerifiedOperation(KernelDataScope scope, String taskId,
            CodingJournal.Lease lease) {
        CodingTaskContract contract = tasks.contract(scope, taskId);
        if (CodingWorkspaceService.supports(contract)) {
            if (workspace == null) return null;
            return workspace.recover(contract, lease);
        }
        List<String> operations = tasks.startedCodeOperations(scope, taskId);
        if (operations.isEmpty()) return null;
        String operationId = operations.get(operations.size() - 1);
        if(CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion()) && (dataSupply==null
            || !dataSupply.snapshots(contract,null).equals(dataSupply.snapshots(contract,operationId))))return null;
        if (tasks.verifiedOperation(scope, taskId, operationId).isEmpty()) return null;
        var operation = python.get(scope, taskId, operationId);
        if (!operation.executorStopped() || !"SUCCEEDED".equals(operation.status())
                || operation.manifestHash() == null) return null;
        var input = inputs.requireAuthorized(contract);
        CodingEvidenceCollector.Collected collected;
        try {
            collected = collector.collect(scope, contract, operationId, tasks.scriptFile(scope,taskId,operationId),
                contract.outputContract().requiredFiles().get(0),
                List.of(new CodingEvidenceCollector.InputFile(
                    input.fileId(), input.fileName(), input.sha256())), plans.validator(contract));
        } catch (CodingEvidenceCollector.RequiredOutputMissingException
                | CodingEvidenceCollector.InvalidStructuredResultException unfulfilled) {
            // A stopped probe is not a completed analysis. Return a settled failure receipt
            // through recovery instead of throwing into and cancelling the parent workflow.
            return null;
        }
        CodingTaskOutcome current = tasks.get(scope, taskId);
        Instant at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (at.isBefore(current.updatedAt())) at = current.updatedAt();
        var proposal = new CodingTaskOutcome(current.schemaVersion(), current.taskId(),
            current.scope(), current.contractSha256(), CodingTaskOutcome.Status.COMPLETED,
            current.revision() + 1, current.lastEventSequence() + 1, current.createdAt(), at,
            current.startedAt(), at, false, null,
            "Verified Coding execution recovered after worker session loss",
            current.limitations(), collected.inputSnapshotRef(), collected.codeArtifactRef(),
            collected.executionReceiptRef(), collected.resultDatasetRef(),
            collected.validationRecordRef(), collected.evidenceBundleRef(),
            collected.validationStatus(),
            current.reproducibility(), current.usage(),
            new CodingTaskOutcome.ExecutionControl(null, null, true, false),
            current.parentTaskId(), current.supersedesTaskId(), Map.of());
        return commitRecoveredOutcome(scope, taskId, lease, current.revision(), proposal);
    }

    private CodingTaskOutcome commitRecoveredOutcome(KernelDataScope scope,String taskId,
            CodingJournal.Lease lease,long revision,CodingTaskOutcome proposal) {
        try { return tasks.commitOutcome(scope,taskId,lease,revision,proposal); }
        catch(CodingContractException conflict) {
            if(conflict.error().code()==CodingErrorCode.INVALID_STATE_TRANSITION) {
                var authoritative=tasks.get(scope,taskId);
                if(authoritative.status().terminal()
                        && authoritative.contractSha256().equals(proposal.contractSha256()))return authoritative;
            }
            throw conflict;
        }
    }

    private void reconcileModel(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
            String operationId) {
        var response = receipts.response(scope, taskId, operationId);
        if (response.isEmpty()) return; // Unknown provider outcome retains the reservation.
        var current = tasks.get(scope, taskId);
        if (tasks.verifiedOperation(scope, taskId, operationId).isEmpty())
            current = tasks.reconcileStopped(scope, taskId, lease, current.revision(),
                operationId, receipts.proofRef(scope, taskId, operationId));
        Object raw = response.get().get("usage");
        if (!(raw instanceof Map<?, ?> usage)) return;
        Long input = usageCount(usage.get("inputTokens"));
        Long output = usageCount(usage.get("outputTokens"));
        if (input == null || output == null) return;
        tasks.settle(scope, taskId, lease, current.revision(), operationId,
            new CodingTaskOutcome.Counters(1, Math.addExact(input, output),
                0, 0, 0, 0, 0, "0"), false);
    }

    private static Long usageCount(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0) return null;
        return number.longValue();
    }

    @Override public Subscription subscribe(KernelDataScope scope, String taskId, long afterSequence, Observer observer) {
        return tasks.subscribe(scope, taskId, afterSequence, observer);
    }

    private JsonNode request(String path, Object value) {
        if ("/v1/runs".equals(path)) requireDispatchLicense();
        var envelope=mapper.valueToTree(value);
        var binding=envelope.has("contract")?envelope.path("contract"):envelope;
        KernelDataScope logScope=null;
        try{if(binding.path("scope").isObject())logScope=mapper.treeToValue(binding.path("scope"),KernelDataScope.class);}
        catch(java.io.IOException ignored){/* Logging does not alter callback binding. */}
        String logTask=binding.path("taskId").asText(null);
        long started=System.nanoTime();
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            HttpRequest call = HttpRequest.newBuilder(node.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
            com.chatchat.common.runtime.event.RuntimeEventLog.execution(
                "NODE_RESPONSE",logScope,logTask,null,Map.of("httpStatus",response.statusCode(),"durationMs",(System.nanoTime()-started)/1_000_000));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new NodeHttpException(response.statusCode());
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cline dispatch interrupted; reconcile before retry", interrupted);
        } catch (java.io.IOException failure) {
            com.chatchat.common.runtime.event.RuntimeEventLog.execution(
                "NODE_RESPONSE_UNKNOWN",logScope,logTask,null,Map.of("status","UNKNOWN","exceptionType",failure.getClass().getSimpleName()));
            throw new IllegalStateException("Cline dispatch outcome unknown; reconcile before retry", failure);
        }
    }
    private void requireDispatchLicense() {
        McpLicenseEntitlementPort.CodingDispatchEntitlement entitlement;
        try {
            entitlement = licenseEntitlements.codingDispatchEntitlement();
        } catch (RuntimeException unavailable) {
            entitlement = null;
        }
        if (entitlement != null && entitlement.licenseValid()
                && "VALID".equals(entitlement.licenseStatus())) return;
        String status = entitlement == null ? "UNAVAILABLE" : entitlement.licenseStatus();
        String message = "EXPIRED".equals(status)
            ? "您的产品授权已过期，任务委派已暂停。请联系管理员更新授权后重试。"
            : "UNAVAILABLE".equals(status) || status == null || status.isBlank()
                ? "暂时无法核验产品授权，Cline 任务委派已暂停。请联系管理员检查授权服务后重试。"
                : "当前产品授权无效，Cline 任务委派已暂停。请联系管理员更新有效授权后重试。";
        throw new CodingContractException(CodingErrorCode.AUTHORIZATION_DENIED, message);
    }
    private void requireInputCapabilities(CodingTaskContract contract) {
        boolean fast=contract.metadataRefs().contains("runtime-coding:analysis-fast-path:v1");
        Object descriptor=contract.runtimeContext().get("inputFiles");
        if(descriptor==null && !fast)return;
        JsonNode capabilities=request("/v1/input-capabilities",Map.of());
        if(descriptor instanceof Map<?,?> input &&
                (!com.chatchat.common.runtime.coding.CodingInputManifest.VERSION.equals(capabilities.path("schemaVersion").asText())
                || !capabilities.path("formats").toString().contains("\""+input.get("format")+"\"")))
            throw new IllegalStateException("Cline worker has not negotiated this input descriptor");
        if(fast) {
            boolean supported=false;
            for(var profile:capabilities.path("analysisProfiles"))
                if("runtime-coding:analysis-fast-path:v1".equals(profile.asText()))supported=true;
            if(!supported)throw new IllegalStateException("Cline worker has not negotiated the analysis execution profile");
        }
    }

    private static final class NodeHttpException extends IllegalStateException {
        private final int status;
        private NodeHttpException(int status) {
            super("Cline Node service rejected request: HTTP " + status);
            this.status = status;
        }
    }
}
