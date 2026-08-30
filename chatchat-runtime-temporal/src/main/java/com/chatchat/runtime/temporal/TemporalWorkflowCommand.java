package com.chatchat.runtime.temporal;

public record TemporalWorkflowCommand(
    String workflowType,
    String inputJson,
    long activityStartToCloseSeconds,
    long activityHeartbeatSeconds,
    int activityMaximumAttempts
) {
}
