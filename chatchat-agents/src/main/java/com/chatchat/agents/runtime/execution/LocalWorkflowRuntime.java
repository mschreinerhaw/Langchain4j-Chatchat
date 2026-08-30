package com.chatchat.agents.runtime.execution;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.workflow.WorkflowDefinition;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionContext;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionSnapshot;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionStatus;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.workflow.WorkflowRegistration;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local workflow adapter used until a distributed durable engine is configured.
 *
 * <p>It guarantees one active execution per workflow id, shared completion for duplicate starts,
 * tenant-fair admission and cooperative cancellation. Durable state remains owned by the Agent run
 * store; this adapter deliberately does not introduce a second persistent state authority.</p>
 */
public final class LocalWorkflowRuntime implements WorkflowRuntime {

    private final TenantFairExecutor executor;
    private final ConcurrentMap<String, ExecutionSlot<?>> executions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowRegistration<?, ?>> registrations = new ConcurrentHashMap<>();

    public LocalWorkflowRuntime(Executor executor, AgentRuntimeProperties properties) {
        this.executor = new TenantFairExecutor(
            executor == null ? ForkJoinPool.commonPool() : executor,
            properties == null ? new AgentRuntimeProperties() : properties
        );
    }

    @Override
    public <I, O> void register(String workflowType, Class<I> inputType, Class<O> outputType,
                                WorkflowDefinition<I, O> definition) {
        WorkflowRegistration<I, O> registration =
            new WorkflowRegistration<>(workflowType, inputType, outputType, definition);
        WorkflowRegistration<?, ?> existing = registrations.putIfAbsent(
            registration.workflowType(), registration);
        if (existing != null && (!existing.inputType().equals(inputType)
            || !existing.outputType().equals(outputType))) {
            throw new IllegalStateException("Workflow type is already registered with another contract: "
                + workflowType);
        }
    }

    @Override
    public <I, O> WorkflowHandle<O> start(WorkflowStartRequest<I> request) {
        if (request == null) {
            throw new IllegalArgumentException("Workflow start request is required");
        }
        WorkflowRegistration<I, O> registration = registration(request);

        ExecutionSlot<O> created = new ExecutionSlot<>(request);
        ExecutionSlot<?> existing = executions.putIfAbsent(request.workflowId(), created);
        if (existing != null) {
            validateDuplicateRequest(existing, request);
            return new WorkflowHandle<>(request.workflowId(), false, castCompletion(existing));
        }

        try {
            executor.execute(request.tenantId(),
                () -> execute(created, request.input(), registration.definition()),
                failure -> reject(created, failure));
        } catch (RejectedExecutionException failure) {
            reject(created, failure);
        }
        return new WorkflowHandle<>(request.workflowId(), true, created.completion);
    }

    @SuppressWarnings("unchecked")
    private <I, O> WorkflowRegistration<I, O> registration(WorkflowStartRequest<I> request) {
        WorkflowRegistration<?, ?> registration = registrations.get(request.workflowType());
        if (registration == null) {
            throw new IllegalStateException("Workflow type is not registered: " + request.workflowType());
        }
        if (request.input() != null && !registration.inputType().isInstance(request.input())) {
            throw new IllegalArgumentException("Workflow input does not match registered type: "
                + request.workflowType());
        }
        return (WorkflowRegistration<I, O>) registration;
    }

    @Override
    public boolean cancel(String workflowId, String reason) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("Workflow id is required");
        }
        ExecutionSlot<?> slot = executions.get(workflowId.trim());
        if (slot == null || slot.isTerminal()) {
            return false;
        }
        slot.cancellationReason = firstText(reason, "Workflow cancellation requested");
        slot.cancellationRequested.set(true);
        Thread runningThread = slot.runningThread;
        if (runningThread != null) {
            runningThread.interrupt();
        }
        return true;
    }

    @Override
    public Optional<WorkflowExecutionSnapshot> find(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        ExecutionSlot<?> slot = executions.get(workflowId.trim());
        return slot == null ? Optional.empty() : Optional.of(slot.snapshot());
    }

    @Override
    public int activeExecutionCount() {
        return executions.size();
    }

    private <I, O> void execute(
        ExecutionSlot<O> slot,
        I input,
        WorkflowDefinition<I, O> definition
    ) {
        slot.runningThread = Thread.currentThread();
        slot.status = WorkflowExecutionStatus.RUNNING;
        try {
            slot.context.checkCancellation();
            O result = definition.execute(input, slot.context);
            slot.context.checkCancellation();
            slot.status = WorkflowExecutionStatus.COMPLETED;
            slot.completion.complete(result);
        } catch (CancellationException failure) {
            slot.status = WorkflowExecutionStatus.CANCELLED;
            slot.errorMessage = firstText(failure.getMessage(), slot.cancellationReason);
            slot.completion.completeExceptionally(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            slot.status = WorkflowExecutionStatus.CANCELLED;
            slot.errorMessage = firstText(slot.cancellationReason, failure.getMessage());
            slot.completion.completeExceptionally(
                new CancellationException(firstText(slot.cancellationReason, "Workflow execution was interrupted")));
        } catch (Throwable failure) {
            slot.status = WorkflowExecutionStatus.FAILED;
            slot.errorMessage = firstText(failure.getMessage(), failure.getClass().getSimpleName());
            slot.completion.completeExceptionally(failure);
        } finally {
            slot.finishedAt = System.currentTimeMillis();
            slot.runningThread = null;
            executions.remove(slot.workflowId, slot);
        }
    }

    private void reject(ExecutionSlot<?> slot, RejectedExecutionException failure) {
        slot.status = WorkflowExecutionStatus.FAILED;
        slot.errorMessage = firstText(failure.getMessage(), "Workflow executor rejected execution");
        slot.finishedAt = System.currentTimeMillis();
        slot.completion.completeExceptionally(failure);
        executions.remove(slot.workflowId, slot);
    }

    private void validateDuplicateRequest(ExecutionSlot<?> existing, WorkflowStartRequest<?> request) {
        if (!existing.workflowType.equals(request.workflowType())
            || !existing.idempotencyKey.equals(request.idempotencyKey())
            || !existing.tenantId.equals(request.tenantId())) {
            throw new IllegalStateException(
                "Workflow id is already active with a different type, tenant or idempotency key: "
                    + request.workflowId());
        }
    }

    @SuppressWarnings("unchecked")
    private <O> CompletableFuture<O> castCompletion(ExecutionSlot<?> slot) {
        return (CompletableFuture<O>) slot.completion;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static final class ExecutionSlot<O> {
        private final String workflowId;
        private final String workflowType;
        private final String tenantId;
        private final String idempotencyKey;
        private final long startedAt = System.currentTimeMillis();
        private final CompletableFuture<O> completion = new CompletableFuture<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        private final WorkflowExecutionContext context;
        private volatile WorkflowExecutionStatus status = WorkflowExecutionStatus.PENDING;
        private volatile Thread runningThread;
        private volatile Long finishedAt;
        private volatile String cancellationReason;
        private volatile String errorMessage;

        private ExecutionSlot(WorkflowStartRequest<?> request) {
            this.workflowId = request.workflowId();
            this.workflowType = request.workflowType();
            this.tenantId = request.tenantId();
            this.idempotencyKey = request.idempotencyKey();
            this.context = new LocalWorkflowExecutionContext(workflowId, cancellationRequested);
        }

        private boolean isTerminal() {
            return status == WorkflowExecutionStatus.COMPLETED
                || status == WorkflowExecutionStatus.FAILED
                || status == WorkflowExecutionStatus.CANCELLED;
        }

        private WorkflowExecutionSnapshot snapshot() {
            return new WorkflowExecutionSnapshot(
                workflowId,
                workflowType,
                tenantId,
                idempotencyKey,
                status,
                1,
                startedAt,
                finishedAt,
                cancellationReason,
                errorMessage
            );
        }
    }

    private record LocalWorkflowExecutionContext(
        String workflowId,
        AtomicBoolean cancellationSignal
    ) implements WorkflowExecutionContext {

        @Override
        public int attempt() {
            return 1;
        }

        @Override
        public boolean cancellationRequested() {
            return cancellationSignal.get();
        }
    }
}
