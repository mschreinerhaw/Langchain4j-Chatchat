package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

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
    private final ClineModelReceiptStore receipts;
    private final URI node;
    private final String secret;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String workerId = "cline-adapter:" + UUID.randomUUID();

    public ClineCodingAdapter(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            ClineModelReceiptStore receipts, URI node, String secret, ObjectMapper mapper) {
        this.tasks = tasks; this.python = python; this.inputs = inputs; this.plans = plans;
        this.receipts = receipts;
        this.node = node; this.secret = secret; this.mapper = mapper;
        if (node == null || !"http".equals(node.getScheme()) || node.getHost() == null
                || !List.of("localhost", "127.0.0.1").contains(node.getHost()) || node.getUserInfo() != null)
            throw new IllegalArgumentException("D1 Node SDK service must use explicit loopback HTTP");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Cline shared secret must have at least 32 characters");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CodingTaskOutcome submit(CodingTaskContract contract) {
        if (!java.util.Set.of("python_execute").containsAll(contract.authorization().allowedTools()))
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
        CodingTaskOutcome accepted = tasks.submit(contract); // A2 persists acceptance before any Node dispatch.
        if (accepted.status().terminal() || accepted.executionControl().stopReason() != null) return accepted;
        KernelDataScope scope = contract.scope();
        CodingJournal.Lease lease = tasks.ownership(scope, contract.taskId());
        if (lease.leaseUntil().isAfter(Instant.now()) && !workerId.equals(lease.owner())) return accepted;
        if (!lease.leaseUntil().isAfter(Instant.now()))
            lease = tasks.claim(scope, contract.taskId(), workerId, Duration.ofSeconds(90), accepted.revision());
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
            try { python.cancel(scope, taskId, operationId, reason); }
            catch (RuntimeException unavailable) { /* Leave reconciliationRequired/stop request visible to Runtime. */ }
        }
        return tasks.get(scope, taskId);
    }

    @Override public CodingTaskOutcome resume(KernelDataScope scope, String taskId) {
        CodingTaskOutcome current = tasks.resume(scope, taskId);
        if (current.status().terminal()) return current;
        CodingJournal.Lease lease = tasks.ownership(scope, taskId);
        if (!lease.leaseUntil().isAfter(Instant.now())) {
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
        // Only Python operations have MCP execution identities. Model starts retain their
        // reservations until the receipt can be reconciled; they are never dispatched again.
        for (String operationId : tasks.unresolvedOperations(scope, taskId)) {
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
        if (workerId.equals(lease.owner()) && current.executionControl().stopReason() != null
                && remote != null && List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText())
                && tasks.unresolvedOperations(scope, taskId).isEmpty())
            return tasks.finishStoppedAfterReconciliation(scope, taskId, lease, current.revision());
        if (workerId.equals(lease.owner()) && current.executionControl().stopReason() == null
                && !tasks.hasStartedOperations(scope, taskId)
                && (sessionMissing || remote != null && remote.path("fencingToken").asLong() < lease.fencingToken()
                    && List.of("COMPLETED", "FAILED", "CANCELLED").contains(remote.path("status").asText()))) {
            CodingTaskContract contract = tasks.contract(scope, taskId);
            request("/v1/runs", Map.of("contract", contract, "contractHash", current.contractSha256(),
                "fencingToken", lease.fencingToken()));
        }
        return tasks.get(scope, taskId);
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
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            HttpRequest call = HttpRequest.newBuilder(node.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new NodeHttpException(response.statusCode());
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cline dispatch interrupted; reconcile before retry", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cline dispatch outcome unknown; reconcile before retry", failure);
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
