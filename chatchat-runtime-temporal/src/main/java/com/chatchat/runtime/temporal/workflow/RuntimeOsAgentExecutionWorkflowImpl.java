package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.activity.RuntimeOsWorkflowActivity;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * First phase-three child workflow. It preserves the mature Agent executor as one Activity while
 * providing the durable boundary where plan branches and tool Activities will be introduced.
 */
public class RuntimeOsAgentExecutionWorkflowImpl implements RuntimeOsAgentExecutionWorkflow {

    @Override
    public TemporalWorkflowResult execute(TemporalWorkflowCommand command) {
        ActivityOptions options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(command.activityStartToCloseSeconds()))
            .setHeartbeatTimeout(Duration.ofSeconds(command.activityHeartbeatSeconds()))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(command.activityMaximumAttempts())
                .build())
            .build();
        RuntimeOsWorkflowActivity activity =
            Workflow.newActivityStub(RuntimeOsWorkflowActivity.class, options);
        return activity.execute(command);
    }
}
