package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
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
    private final URI node;
    private final String secret;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String workerId = "cline-adapter:" + UUID.randomUUID();

    public ClineCodingAdapter(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            URI node, String secret, ObjectMapper mapper) {
        this.tasks = tasks; this.python = python; this.node = node; this.secret = secret; this.mapper = mapper;
        if (node == null || !"http".equals(node.getScheme()) || node.getHost() == null
                || !List.of("localhost", "127.0.0.1").contains(node.getHost()) || node.getUserInfo() != null)
            throw new IllegalArgumentException("D1 Node SDK service must use explicit loopback HTTP");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Cline shared secret must have at least 32 characters");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CodingTaskOutcome submit(CodingTaskContract contract) {
        if (contract.authorization().networkPolicy() != CodingTaskContract.NetworkPolicy.NONE)
            throw new SecurityException("D1 Coding Sandbox requires a no-network authorization");
        CodingTaskOutcome accepted = tasks.submit(contract); // A2 persists acceptance before any Node dispatch.
        if (accepted.status().terminal()) return accepted;
        KernelDataScope scope = contract.scope();
        CodingJournal.Lease lease = tasks.ownership(scope, contract.taskId());
        if (lease.leaseUntil().isAfter(Instant.now()) && !workerId.equals(lease.owner())) return accepted;
        if (!lease.leaseUntil().isAfter(Instant.now()))
            lease = tasks.claim(scope, contract.taskId(), workerId, Duration.ofSeconds(90), accepted.revision());
        // Unknown starts belong to recovery; another model request must never be invented by submit.
        if (!tasks.unresolvedOperations(scope, contract.taskId()).isEmpty()) return tasks.get(scope, contract.taskId());
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
        CodingJournal.Lease lease = tasks.ownership(scope, taskId);
        try { request("/v1/runs/get", Map.of("scope", scope, "taskId", taskId,
            "contractHash", current.contractSha256(), "fencingToken", lease.fencingToken())); }
        catch (RuntimeException unavailable) { /* A2 remains authoritative; unknown Node state cannot be replayed. */ }
        // Query execution identity first. An unavailable/unknown Node session is never replayed here.
        for (String operationId : tasks.unresolvedOperations(scope, taskId)) {
            try { python.get(scope, taskId, operationId); }
            catch (RuntimeException unavailable) { return tasks.get(scope, taskId); }
        }
        return current;
    }

    @Override public Subscription subscribe(KernelDataScope scope, String taskId, long afterSequence, Observer observer) {
        return tasks.subscribe(scope, taskId, afterSequence, observer);
    }

    private void request(String path, Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            HttpRequest call = HttpRequest.newBuilder(node.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            HttpResponse<Void> response = http.send(call, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Cline Node service rejected dispatch: HTTP " + response.statusCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cline dispatch interrupted; reconcile before retry", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cline dispatch outcome unknown; reconcile before retry", failure);
        }
    }
}
