package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.workflow.WorkflowDefinition;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionContext;
import com.chatchat.agents.runtime.workflow.WorkflowRegistration;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.AgentRunExecutionSlice;
import com.chatchat.agents.runtime.plan.execution.ResumableAgentRunExecutor;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.runtime.temporal.contract.TemporalAgentExecutionSlice;
import com.chatchat.runtime.temporal.contract.TemporalAgentResumeCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import com.chatchat.runtime.temporal.core.TemporalWorkflowDefinitionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

public final class RuntimeOsWorkflowActivityImpl implements RuntimeOsWorkflowActivity, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeOsWorkflowActivityImpl.class);

    private final TemporalWorkflowDefinitionRegistry registry;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ResumableAgentRunExecutor resumableAgentRunExecutor;

    public RuntimeOsWorkflowActivityImpl(TemporalWorkflowDefinitionRegistry registry,
                                         ObjectMapper objectMapper) {
        this(registry, objectMapper, null);
    }

    public RuntimeOsWorkflowActivityImpl(TemporalWorkflowDefinitionRegistry registry,
                                         ObjectMapper objectMapper,
                                         ResumableAgentRunExecutor resumableAgentRunExecutor) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.resumableAgentRunExecutor = resumableAgentRunExecutor;
        int heartbeatThreads = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        this.heartbeatExecutor = Executors.newScheduledThreadPool(
            heartbeatThreads, new HeartbeatThreadFactory());
    }

    @Override
    public TemporalAgentExecutionSlice bootstrapAgent(TemporalWorkflowCommand command) {
        ResumableAgentRunExecutor executor = requiredResumableExecutor();
        try {
            AgentRunRequest request = objectMapper.readValue(command.inputJson(), AgentRunRequest.class);
            return temporalSlice(executor.executeUntilPlanSuspension(request, kernelScope(request)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Temporal Agent bootstrap payload conversion failed", failure);
        }
    }

    @Override
    public TemporalAgentExecutionSlice resumeAgent(TemporalAgentResumeCommand command) {
        ResumableAgentRunExecutor executor = requiredResumableExecutor();
        AgentRunRequest request = command.continuation().request();
        var result = command.planResult();
        InterpretationPlanRuntime.ExecutionResult executionResult =
            new InterpretationPlanRuntime.ExecutionResult(
                result.status(), "COMPLETED".equalsIgnoreCase(result.status()), false,
                result.reason(), result.finalAnswer(), result.executions(),
                Map.of("temporalPlanExecutionStatus", result.status()), 0L);
        return temporalSlice(executor.resumeAfterPlanExecution(
            command.continuation(), executionResult, kernelScope(request)));
    }

    private TemporalAgentExecutionSlice temporalSlice(AgentRunExecutionSlice slice) {
        try {
            String output = slice.completedResult() == null ? null
                : objectMapper.writeValueAsString(slice.completedResult());
            return new TemporalAgentExecutionSlice(slice.status(), output, slice.suspendedPlan());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Temporal Agent result conversion failed", failure);
        }
    }

    private ResumableAgentRunExecutor requiredResumableExecutor() {
        if (resumableAgentRunExecutor == null) {
            throw new IllegalStateException("Resumable Agent executor is not configured");
        }
        return resumableAgentRunExecutor;
    }

    private KernelDataScope kernelScope(AgentRunRequest request) {
        Map<String, Object> attributes = request.getAttributes() == null
            ? Map.of() : request.getAttributes();
        return new KernelDataScope(
            request.getTenantId(), request.getUserId(), request.getRequestId(),
            request.getConversationId(), request.getRunId(),
            String.valueOf(attributes.getOrDefault("agentRuntimeEnvironment", "")), attributes);
    }

    @Override
    public TemporalWorkflowResult execute(TemporalWorkflowCommand command) {
        WorkflowRegistration<?, ?> registration = registry.required(command.workflowType());
        AtomicBoolean cancellation = new AtomicBoolean(false);
        Thread activityThread = Thread.currentThread();
        ActivityExecutionContext temporalContext = Activity.getExecutionContext();
        WorkflowExecutionContext context = new WorkflowExecutionContext() {
            @Override
            public String workflowId() {
                return temporalContext.getInfo().getWorkflowId();
            }

            @Override
            public int attempt() {
                return temporalContext.getInfo().getAttempt();
            }

            @Override
            public boolean cancellationRequested() {
                return cancellation.get() || activityThread.isInterrupted();
            }
        };
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                temporalContext.heartbeat(null);
            } catch (ActivityCompletionException cancellationOrShutdown) {
                cancellation.set(true);
                activityThread.interrupt();
            } catch (RuntimeException transientFailure) {
                LOGGER.warn("Temporal Activity heartbeat failed; the next heartbeat will retry",
                    transientFailure);
            }
        }, Math.max(1L, command.activityHeartbeatSeconds()),
            Math.max(1L, command.activityHeartbeatSeconds()), TimeUnit.SECONDS);
        try {
            Object input = objectMapper.readValue(command.inputJson(), registration.inputType());
            Object output = executeRegistered(registration, input, context);
            context.checkCancellation();
            return new TemporalWorkflowResult(objectMapper.writeValueAsString(output));
        } catch (CancellationException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Temporal workflow payload conversion failed", failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Temporal workflow activity failed", failure);
        } finally {
            heartbeat.cancel(true);
        }
    }

    @SuppressWarnings("unchecked")
    private <I, O> O executeRegistered(WorkflowRegistration<?, ?> raw, Object input,
                                       WorkflowExecutionContext context) throws Exception {
        WorkflowRegistration<I, O> registration = (WorkflowRegistration<I, O>) raw;
        WorkflowDefinition<I, O> definition = registration.definition();
        return definition.execute((I) input, context);
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private static final class HeartbeatThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "temporal-agent-heartbeat");
            thread.setDaemon(true);
            return thread;
        }
    }
}
