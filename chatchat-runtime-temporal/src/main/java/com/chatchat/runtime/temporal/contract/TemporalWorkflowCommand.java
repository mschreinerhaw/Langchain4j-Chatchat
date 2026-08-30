package com.chatchat.runtime.temporal.contract;

public record TemporalWorkflowCommand(
    String workflowType,
    String inputJson,
    long activityStartToCloseSeconds,
    long activityHeartbeatSeconds,
    int activityMaximumAttempts
) {
}
