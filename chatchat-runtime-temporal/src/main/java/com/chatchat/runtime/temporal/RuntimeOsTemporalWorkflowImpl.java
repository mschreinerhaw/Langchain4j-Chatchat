package com.chatchat.runtime.temporal;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class RuntimeOsTemporalWorkflowImpl implements RuntimeOsTemporalWorkflow {

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
