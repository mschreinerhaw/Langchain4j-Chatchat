package com.chatchat.agents.runtime.execution;

import com.chatchat.agents.runtime.AgentRunExecutor;
import com.chatchat.agents.runtime.AgentRunHandle;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;

import com.chatchat.agents.runtime.config.AgentRuntimeExecutorConfig;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.run.AgentRunStep;
import com.chatchat.agents.runtime.store.AgentRunStore;

import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.kernel.KernelViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Service
public class DefaultAgentRuntime implements AgentRuntime {

    private final AgentRunExecutor runExecutor;
    private final AgentRunStore runStore;
    private final Executor executor;
    private final TenantFairExecutor tenantExecutor;
    private final Map<String, AtomicBoolean> cancellationSignals = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public DefaultAgentRuntime(AgentRunExecutor runExecutor, AgentRunStore runStore) {
        this(runExecutor, runStore, ForkJoinPool.commonPool(), new AgentRuntimeProperties());
    }

    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               @Qualifier(AgentRuntimeExecutorConfig.AGENT_RUNTIME_EXECUTOR) Executor executor) {
        this(runExecutor, runStore, executor, new AgentRuntimeProperties());
    }

    @Autowired
    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               @Qualifier(AgentRuntimeExecutorConfig.AGENT_RUNTIME_EXECUTOR) Executor executor,
                               AgentRuntimeProperties properties) {
        this.runExecutor = runExecutor;
        this.runStore = runStore;
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
        this.tenantExecutor = new TenantFairExecutor(this.executor, properties);
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        KernelInvocation<AgentRunRequest> invocation = KernelInvocation.of(
            "agent.run",
            kernelProtocol(),
            kernelScope(request),
            Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE,
                KernelDataDomain.OBSERVATIONS, KernelDataDomain.EVENTS),
            request
        );
        KernelResult<AgentRunResult> result = invoke(invocation);
        if (result.successful()) return result.data();
        throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
    }

    @Override
    public AgentRunResult executeKernel(AgentRunRequest request, KernelDataScope scope) {
        if (request == null) {
            throw new KernelViolationException("KERNEL_PAYLOAD_REQUIRED", "Agent run request is required");
        }
        if (request.getTenantId() != null && !request.getTenantId().isBlank()
            && !request.getTenantId().equals(scope.tenantId())) {
            throw new KernelViolationException("KERNEL_TENANT_MISMATCH",
                "Agent request tenant does not match Kernel scope");
        }
        return runExecutor.execute(request, scope);
    }

    @Override
    public AgentRunHandle submit(AgentRunRequest request) {
        AgentRun submitted = runStore.submit(request);
        AtomicBoolean cancellationSignal = installCancellationSignal(request, submitted.runId());
        try {
            CompletableFuture<AgentRunResult> completion = new CompletableFuture<>();
            tenantExecutor.execute(request == null ? null : request.getTenantId(), () -> {
                    runningThreads.put(submitted.runId(), Thread.currentThread());
                    try {
                        if (cancellationSignal.get()) {
                            completion.complete(cancelledRunResult(
                                runStore.cancel(submitted.runId(), "Agent run cancellation requested")));
                            return;
                        }
                        completion.complete(run(request));
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        runningThreads.remove(submitted.runId());
                        cancellationSignals.remove(submitted.runId());
                    }
                }, rejection -> {
                    cancellationSignals.remove(submitted.runId());
                    AgentRun failed = runStore.fail(submitted.runId(),
                        new RejectedExecutionException("Agent runtime executor rejected run", rejection));
                    completion.complete(failedRunResult(failed));
                });
            return new AgentRunHandle(submitted.runId(), completion);
        } catch (RejectedExecutionException ex) {
            cancellationSignals.remove(submitted.runId());
            AgentRun failed = runStore.fail(submitted.runId(), new RejectedExecutionException("Agent runtime executor rejected run", ex));
            return new AgentRunHandle(submitted.runId(), CompletableFuture.completedFuture(failedRunResult(failed)));
        }
    }

    @Override
    public AgentRun cancel(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        AtomicBoolean signal = cancellationSignals.get(runId);
        if (signal != null) {
            signal.set(true);
        }
        Thread runningThread = runningThreads.get(runId);
        if (runningThread != null) {
            runningThread.interrupt();
        }
        return runStore.cancel(runId, "Agent run cancellation requested");
    }

    @Override
    public Optional<AgentRun> find(String runId) {
        return runStore.find(runId);
    }

    @Override
    public List<AgentRun> list(AgentRunQuery query) {
        return runStore.list(query);
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        return runStore.events(runId);
    }

    @Override
    public List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit) {
        return runStore.events(runId, afterCreatedAt, limit);
    }

    @Override
    public List<AgentRunStep> steps(String runId) {
        return runStore.steps(runId);
    }

    @Override
    public List<AgentRunStep> steps(String runId, int afterStep, int limit) {
        return runStore.steps(runId, afterStep, limit);
    }

    @Override
    public List<AgentObservation> observations(String runId) {
        return runStore.observations(runId);
    }

    @Override
    public List<AgentObservation> observations(String runId, int offset, int limit) {
        return runStore.observations(runId, offset, limit);
    }

    @Override
    public Optional<Object> evidence(String documentId) {
        return runStore.evidence(documentId);
    }

    @Override
    public AgentRuntimeSnapshot snapshot() {
        return runStore.snapshot().withActiveCancellationSignals(cancellationSignals.size());
    }

    private KernelDataScope kernelScope(AgentRunRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of() : request.getAttributes();
        String requestId = firstText(request == null ? null : request.getRequestId(), UUID.randomUUID().toString());
        return new KernelDataScope(
            firstText(request == null ? null : request.getTenantId(), "default"),
            request == null ? null : request.getUserId(),
            requestId,
            request == null ? null : request.getConversationId(),
            request == null ? null : request.getRunId(),
            firstText(stringValue(attributes.get("agentRuntimeEnvironment")),
                stringValue(attributes.get("environment")), stringValue(attributes.get("env"))),
            Map.of("source", "default-agent-runtime")
        );
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private AtomicBoolean installCancellationSignal(AgentRunRequest request, String runId) {
        AtomicBoolean signal = new AtomicBoolean(false);
        cancellationSignals.put(runId, signal);
        Map<String, Object> attributes = request.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.getAttributes());
        attributes.putIfAbsent("timeoutMs",
            request.getTimeoutMs() == null ? AgentRunRequest.DEFAULT_TIMEOUT_MS : request.getTimeoutMs());
        Object existing = attributes.get("__agentCancellation");
        if (existing instanceof BooleanSupplier existingSupplier) {
            attributes.put("__agentCancellation", (BooleanSupplier) () -> signal.get() || existingSupplier.getAsBoolean());
        } else {
            attributes.put("__agentCancellation", (BooleanSupplier) signal::get);
        }
        request.setAttributes(attributes);
        return signal;
    }

    private AgentRunResult cancelledRunResult(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.CANCELLED)
            .answer("")
            .stopReason("cancelled")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }

    private AgentRunResult failedRunResult(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.FAILED)
            .answer("")
            .stopReason("failed")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }
}
