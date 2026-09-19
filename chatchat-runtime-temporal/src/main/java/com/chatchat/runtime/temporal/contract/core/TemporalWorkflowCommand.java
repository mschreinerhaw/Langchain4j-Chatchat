package com.chatchat.runtime.temporal.contract.core;

public record TemporalWorkflowCommand(
    String workflowType,
    String inputJson,
    long activityStartToCloseSeconds,
    long activityHeartbeatSeconds,
    int activityMaximumAttempts
) {
}
