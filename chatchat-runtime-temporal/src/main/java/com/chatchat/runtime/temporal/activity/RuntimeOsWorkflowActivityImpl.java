package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.workflow.WorkflowDefinition;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionContext;
import com.chatchat.agents.runtime.workflow.WorkflowRegistration;
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

public final class RuntimeOsWorkflowActivityImpl implements RuntimeOsWorkflowActivity, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeOsWorkflowActivityImpl.class);

    private final TemporalWorkflowDefinitionRegistry registry;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatExecutor;

    public RuntimeOsWorkflowActivityImpl(TemporalWorkflowDefinitionRegistry registry,
                                         ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        int heartbeatThreads = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        this.heartbeatExecutor = Executors.newScheduledThreadPool(
            heartbeatThreads, new HeartbeatThreadFactory());
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
