package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.activity.RuntimeOsWorkflowActivity;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class RuntimeOsTemporalWorkflowImpl implements RuntimeOsTemporalWorkflow {

    public static final String EXECUTION_CHILD_SUFFIX = "::agent-execution-v1";
    public static final String EXECUTION_CHILD_CHANGE_ID = "runtime-os-agent-execution-child-v1";

    @Override
    public TemporalWorkflowResult execute(TemporalWorkflowCommand command) {
        int version = Workflow.getVersion(
            EXECUTION_CHILD_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        if (version == Workflow.DEFAULT_VERSION) {
            return executeLegacyActivity(command);
        }
        String childWorkflowId = Workflow.getInfo().getWorkflowId() + EXECUTION_CHILD_SUFFIX;
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
            .setWorkflowId(childWorkflowId)
            .setTaskQueue(Workflow.getInfo().getTaskQueue())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
            .build();
        RuntimeOsAgentExecutionWorkflow child = Workflow.newChildWorkflowStub(
            RuntimeOsAgentExecutionWorkflow.class, options);
        return child.execute(command);
    }

    /** Replay-compatible branch for phase-two Workflow histories created before the child split. */
    private TemporalWorkflowResult executeLegacyActivity(TemporalWorkflowCommand command) {
        ActivityOptions options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(command.activityStartToCloseSeconds()))
            .setHeartbeatTimeout(Duration.ofSeconds(command.activityHeartbeatSeconds()))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(command.activityMaximumAttempts())
                .build())
            .build();
        return Workflow.newActivityStub(RuntimeOsWorkflowActivity.class, options).execute(command);
    }
}
