package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.runtime.temporal.activity.RuntimeOsToolActivity;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.contract.TemporalToolExecutionSnapshot;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/** Deterministic controller for an independently persisted tool Activity. */
public class RuntimeOsToolExecutionWorkflowImpl implements RuntimeOsToolExecutionWorkflow {

    private String workflowId;
    private String toolName;
    private String idempotencyKey;
    private String status = "PENDING";
    private int maximumAttempts = 1;

    @Override
    public ToolRuntimeExecution execute(TemporalToolActivityCommand command) {
        workflowId = Workflow.getInfo().getWorkflowId();
        toolName = command.request().getToolName();
        idempotencyKey = command.idempotencyKey();
        maximumAttempts = command.maximumAttempts();
        status = "RUNNING";
        ActivityOptions options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(command.startToCloseSeconds()))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(command.maximumAttempts())
                .build())
            .build();
        try {
            ToolRuntimeExecution result = Workflow.newActivityStub(
                RuntimeOsToolActivity.class, options).execute(command);
            status = "COMPLETED";
            return result;
        } catch (CanceledFailure cancelled) {
            status = "CANCELLED";
            throw cancelled;
        } catch (RuntimeException failed) {
            status = "FAILED";
            throw failed;
        }
    }

    @Override
    public TemporalToolExecutionSnapshot status() {
        return new TemporalToolExecutionSnapshot(
            workflowId, toolName, idempotencyKey, status, maximumAttempts);
    }
}
