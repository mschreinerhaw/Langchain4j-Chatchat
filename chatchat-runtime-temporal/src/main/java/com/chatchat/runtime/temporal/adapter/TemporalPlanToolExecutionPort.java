package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionCommand;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.workflow.RuntimeOsToolExecutionWorkflow;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Routes real InterpretationPlan tool calls through independently persisted Temporal Activities. */
public final class TemporalPlanToolExecutionPort implements PlanToolExecutionPort {

    public static final String WORKFLOW_ID_PREFIX = "plan-tool::";

    private final WorkflowClient client;
    private final TemporalWorkflowProperties properties;
    private final ToolRuntimeService toolRuntimeService;

    public TemporalPlanToolExecutionPort(WorkflowClient client,
                                         TemporalWorkflowProperties properties,
                                         ToolRuntimeService toolRuntimeService) {
        this.client = Objects.requireNonNull(client, "client");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.toolRuntimeService = Objects.requireNonNull(toolRuntimeService, "toolRuntimeService");
    }

    @Override
    public ToolRuntimeExecution execute(PlanToolExecutionCommand command) {
        Objects.requireNonNull(command, "command");
        String workflowId = workflowId(command.idempotencyKey());
        TemporalToolActivityCommand activityCommand = TemporalToolActivityCommand.governed(
            command.request(),
            toolRuntimeService.metadata(command.request().getToolName()),
            command.idempotencyKey(),
            properties.activityStartToCloseSeconds());
        RuntimeOsToolExecutionWorkflow workflow = client.newWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.taskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setMemo(Map.of(
                    "planToolSchemaVersion", command.schemaVersion(),
                    "planRunId", command.runId(),
                    "planExecutionScope", command.planExecutionScope(),
                    "planStepId", command.stepId(),
                    "planInvocationRole", command.invocationRole(),
                    "planToolIdempotencyKey", command.idempotencyKey()
                ))
                .build());
        try {
            WorkflowClient.start(workflow::execute, activityCommand);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // Stable identity means retries and resumed plan attempts attach to the persisted result.
        }
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        try {
            return awaitResult(stub, command);
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted() || causedByCancellation(failure)) {
                stub.cancel();
            }
            throw failure;
        }
    }

    private ToolRuntimeExecution awaitResult(WorkflowStub stub,
                                             PlanToolExecutionCommand command) {
        ActivityExecutionContext activityContext = currentActivityContext();
        if (activityContext == null) {
            return stub.getResult(ToolRuntimeExecution.class);
        }
        var completion = stub.getResultAsync(ToolRuntimeExecution.class);
        long heartbeatSeconds = Math.max(1L, properties.activityHeartbeatSeconds());
        while (true) {
            try {
                activityContext.heartbeat(Map.of(
                    "state", "WAITING_FOR_TOOL_WORKFLOW",
                    "toolWorkflowId", stub.getExecution().getWorkflowId(),
                    "planStepId", command.stepId(),
                    "idempotencyKey", command.idempotencyKey()
                ));
                return completion.get(heartbeatSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                // Heartbeat again until the independently durable tool result is available.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stub.cancel();
                throw new CancellationException("Interrupted while waiting for plan tool Workflow");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new IllegalStateException("Plan tool Workflow failed", cause);
            } catch (io.temporal.failure.CanceledFailure cancelled) {
                stub.cancel();
                throw cancelled;
            }
        }
    }

    @Override
    public Object resolveOutputForEvidenceReview(ToolOutput output) {
        return toolRuntimeService.resolveOutputForEvidenceReview(output);
    }

    public static String workflowId(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return WORKFLOW_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to derive plan tool Workflow id", ex);
        }
    }

    private boolean causedByCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException
                || current instanceof io.temporal.failure.CanceledFailure) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ActivityExecutionContext currentActivityContext() {
        try {
            return Activity.getExecutionContext();
        } catch (IllegalStateException outsideActivity) {
            return null;
        }
    }
}
