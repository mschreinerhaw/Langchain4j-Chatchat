package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;

/** Entry contract for the deterministic fine-grained plan execution Workflow. */
public record TemporalPlanExecutionCommand(
    String schemaVersion,
    PlanExecutionContinuation continuation,
    long activityStartToCloseSeconds,
    long activityHeartbeatSeconds,
    boolean commitIndependentSuccesses
) {
    public static final String SCHEMA_VERSION = "temporal_plan_execution.v1";

    public TemporalPlanExecutionCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (continuation == null) {
            throw new IllegalArgumentException("Plan execution continuation is required");
        }
        activityStartToCloseSeconds = Math.max(1L, activityStartToCloseSeconds);
        activityHeartbeatSeconds = Math.max(1L, activityHeartbeatSeconds);
    }
}
