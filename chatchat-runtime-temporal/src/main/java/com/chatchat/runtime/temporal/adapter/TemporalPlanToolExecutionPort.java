package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionCommand;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.tool.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.workflow.tool.RuntimeOsToolExecutionWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
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
        Map<String, Object> memo = new LinkedHashMap<>(Map.of(
            "planToolSchemaVersion", command.schemaVersion(),
            "planRunId", command.runId(),
            "planExecutionScope", command.planExecutionScope(),
            "planStepId", command.stepId(),
            "planInvocationRole", command.invocationRole(),
            "planToolIdempotencyKey", command.idempotencyKey(),
            "planInvocationFingerprint", command.invocationFingerprint(),
            "planToolName", command.request().getToolName()));
        if (command.request().getTenantId() != null && !command.request().getTenantId().isBlank()) {
            memo.put("planTenantId", command.request().getTenantId());
        }
        if (command.request().getUserId() != null && !command.request().getUserId().isBlank()) {
            memo.put("planUserId", command.request().getUserId());
        }
        RuntimeOsToolExecutionWorkflow workflow = client.newWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.taskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setMemo(memo)
                .build());
        try {
            WorkflowClient.start(workflow::execute, activityCommand);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // A reused Workflow id is only safe when the persisted invocation is the same one.
            validatePersistedIdentity(workflowId, command);
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
        long heartbeatSeconds = Math.max(1L, properties.activityHeartbeatSeconds() / 3L);
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

    private void validatePersistedIdentity(String workflowId, PlanToolExecutionCommand command) {
        var response = client.getWorkflowServiceStubs().blockingStub()
            .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                .build());
        Map<String, String> persisted = new LinkedHashMap<>();
        response.getWorkflowExecutionInfo().getMemo().getFieldsMap().forEach((key, payload) -> {
            if (key.startsWith("plan")) {
                Object decoded = client.getOptions().getDataConverter()
                    .fromPayload(payload, Object.class, Object.class);
                persisted.put(key, decoded == null ? "" : String.valueOf(decoded));
            }
        });
        validatePersistedIdentity(persisted, command);
    }

    static void validatePersistedIdentity(Map<String, String> persisted,
                                          PlanToolExecutionCommand command) {
        Map<String, String> required = Map.of(
            "planToolSchemaVersion", command.schemaVersion(),
            "planRunId", command.runId(),
            "planExecutionScope", command.planExecutionScope(),
            "planStepId", String.valueOf(command.stepId()),
            "planInvocationRole", command.invocationRole(),
            "planToolIdempotencyKey", command.idempotencyKey());
        required.forEach((key, expected) -> {
            if (!expected.equals(persisted.get(key))) {
                throw new IllegalStateException("Plan tool Workflow identity mismatch: " + key);
            }
        });
        // These fields were added after the first durable tool Workflow version. Validate them
        // when present while allowing existing histories to reattach through the older identity.
        Map<String, String> newer = Map.of(
            "planInvocationFingerprint", command.invocationFingerprint(),
            "planToolName", command.request().getToolName());
        newer.forEach((key, expected) -> {
            if (persisted.containsKey(key) && !expected.equals(persisted.get(key))) {
                throw new IllegalStateException("Plan tool Workflow identity mismatch: " + key);
            }
        });
        if (persisted.containsKey("planTenantId")
            && !persisted.get("planTenantId").equals(command.request().getTenantId())) {
            throw new IllegalStateException("Plan tool Workflow identity mismatch: planTenantId");
        }
        if (persisted.containsKey("planUserId")
            && !persisted.get("planUserId").equals(command.request().getUserId())) {
            throw new IllegalStateException("Plan tool Workflow identity mismatch: planUserId");
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
