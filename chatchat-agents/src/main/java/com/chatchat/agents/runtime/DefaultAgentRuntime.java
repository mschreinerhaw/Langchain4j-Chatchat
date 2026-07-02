package com.chatchat.agents.runtime;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Service
public class DefaultAgentRuntime implements AgentRuntime {

    private final AgentOrchestrator orchestrator;
    private final AgentRunStore runStore;
    private final Executor executor;
    private final Map<String, AtomicBoolean> cancellationSignals = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public DefaultAgentRuntime(AgentOrchestrator orchestrator, AgentRunStore runStore) {
        this(orchestrator, runStore, ForkJoinPool.commonPool());
    }

    @Autowired
    public DefaultAgentRuntime(AgentOrchestrator orchestrator,
                               AgentRunStore runStore,
                               @Qualifier(AgentRuntimeExecutorConfig.AGENT_RUNTIME_EXECUTOR) Executor executor) {
        this.orchestrator = orchestrator;
        this.runStore = runStore;
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        return orchestrator.execute(request);
    }

    @Override
    public AgentRunHandle submit(AgentRunRequest request) {
        AgentRun submitted = runStore.submit(request);
        AtomicBoolean cancellationSignal = installCancellationSignal(request, submitted.runId());
        try {
            CompletableFuture<AgentRunResult> completion = CompletableFuture.supplyAsync(
                () -> {
                    runningThreads.put(submitted.runId(), Thread.currentThread());
                    try {
                        if (cancellationSignal.get()) {
                            return cancelledRunResult(runStore.cancel(submitted.runId(), "Agent run cancellation requested"));
                        }
                        return orchestrator.execute(request);
                    } finally {
                        runningThreads.remove(submitted.runId());
                        cancellationSignals.remove(submitted.runId());
                    }
                },
                executor
            );
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
    public AgentRuntimeSnapshot snapshot() {
        return runStore.snapshot().withActiveCancellationSignals(cancellationSignals.size());
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
