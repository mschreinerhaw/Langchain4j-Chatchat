package com.chatchat.agents.runtime.execution;

import com.chatchat.agents.runtime.AgentRunExecutor;
import com.chatchat.agents.runtime.AgentRunHandle;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.run.AgentRunStep;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;

import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.kernel.KernelViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

@Service
public class DefaultAgentRuntime implements AgentRuntime {

    private static final String AGENT_WORKFLOW_TYPE = "agent-run-v1";

    private final AgentRunExecutor runExecutor;
    private final AgentRunStore runStore;
    private final WorkflowRuntime workflowRuntime;

    public DefaultAgentRuntime(AgentRunExecutor runExecutor, AgentRunStore runStore) {
        this(runExecutor, runStore,
            new LocalWorkflowRuntime(ForkJoinPool.commonPool(), new AgentRuntimeProperties()));
    }

    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               Executor executor) {
        this(runExecutor, runStore,
            new LocalWorkflowRuntime(executor, new AgentRuntimeProperties()));
    }

    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               AgentRuntimeProperties properties) {
        this(runExecutor, runStore,
            new LocalWorkflowRuntime(ForkJoinPool.commonPool(), properties));
    }

    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               Executor executor,
                               AgentRuntimeProperties properties) {
        this(runExecutor, runStore, new LocalWorkflowRuntime(executor, properties));
    }

    @Autowired
    public DefaultAgentRuntime(AgentRunExecutor runExecutor,
                               AgentRunStore runStore,
                               WorkflowRuntime workflowRuntime) {
        this.runExecutor = runExecutor;
        this.runStore = runStore;
        this.workflowRuntime = workflowRuntime == null
            ? new LocalWorkflowRuntime(ForkJoinPool.commonPool(), new AgentRuntimeProperties())
            : workflowRuntime;
        this.workflowRuntime.register(
            AGENT_WORKFLOW_TYPE,
            AgentRunRequest.class,
            AgentRunResult.class,
            this::executeRegisteredWorkflow
        );
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
        if (isTerminal(submitted.status())) {
            return new AgentRunHandle(submitted.runId(),
                CompletableFuture.completedFuture(storedRunResult(submitted)));
        }

        String tenantId = firstText(request == null ? null : request.getTenantId(), "default");
        String requestId = firstText(request == null ? null : request.getRequestId(), submitted.runId());
        WorkflowHandle<AgentRunResult> workflow = workflowRuntime.start(
            new WorkflowStartRequest<>(
                submitted.runId(),
                AGENT_WORKFLOW_TYPE,
                tenantId,
                tenantId + ":" + requestId,
                request
            ));
        CompletableFuture<AgentRunResult> completion = workflow.completion()
            .handle((result, failure) -> finishWorkflow(submitted.runId(), result, failure));
        return new AgentRunHandle(submitted.runId(), completion);
    }

    @Override
    public AgentRun cancel(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        workflowRuntime.cancel(runId, "Agent run cancellation requested");
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
        return runStore.snapshot().withActiveCancellationSignals(workflowRuntime.activeExecutionCount());
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

    private void installCancellationSignal(AgentRunRequest request, BooleanSupplier workflowCancellation) {
        Map<String, Object> attributes = request.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.getAttributes());
        attributes.putIfAbsent("timeoutMs",
            request.getTimeoutMs() == null ? AgentRunRequest.DEFAULT_TIMEOUT_MS : request.getTimeoutMs());
        Object existing = attributes.get("__agentCancellation");
        if (existing instanceof BooleanSupplier existingSupplier) {
            attributes.put("__agentCancellation",
                (BooleanSupplier) () -> workflowCancellation.getAsBoolean() || existingSupplier.getAsBoolean());
        } else {
            attributes.put("__agentCancellation", workflowCancellation);
        }
        request.setAttributes(attributes);
    }

    private AgentRunResult finishWorkflow(String runId, AgentRunResult result, Throwable failure) {
        if (failure == null) {
            return result;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof CancellationException || cause instanceof InterruptedException) {
            return cancelledRunResult(runStore.cancel(runId, "Agent run cancellation requested"));
        }
        if (cause instanceof RejectedExecutionException) {
            AgentRun failed = runStore.fail(runId,
                new RejectedExecutionException("Agent runtime executor rejected run", cause));
            return failedRunResult(failed);
        }
        runStore.fail(runId, cause);
        throw new CompletionException(cause);
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.WAITING_CONFIRMATION
            || status == AgentRunStatus.COMPLETED
            || status == AgentRunStatus.FAILED
            || status == AgentRunStatus.CANCELLED;
    }

    /**
     * Durable workflow entry point. Temporal may dispatch an Activity again after a
     * worker process is lost, so a terminal store record is the authoritative result.
     */
    private AgentRunResult executeRegisteredWorkflow(
        AgentRunRequest input,
        com.chatchat.agents.runtime.workflow.WorkflowExecutionContext context
    ) {
        AgentRun existing = runStore.find(input.getRunId()).orElse(null);
        if (existing != null && isTerminal(existing.status())) {
            return storedRunResult(existing);
        }
        installCancellationSignal(input, context::cancellationRequested);
        context.checkCancellation();
        return persistWorkflowResult(input.getRunId(), run(input));
    }

    private AgentRunResult persistWorkflowResult(String runId, AgentRunResult result) {
        AgentRun current = runStore.find(runId).orElse(null);
        AgentRun persisted = current != null && isTerminal(current.status())
            ? current
            : runStore.complete(runId, result);
        AgentRunResult effective = persisted.result() == null ? result : persisted.result();
        return effective.withStatusAndEvents(persisted.status(), persisted.events());
    }

    private AgentRunResult storedRunResult(AgentRun run) {
        if (run.result() != null) {
            return run.result().withStatusAndEvents(run.status(), run.events());
        }
        if (run.status() == AgentRunStatus.CANCELLED) {
            return cancelledRunResult(run);
        }
        return failedRunResult(run);
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
