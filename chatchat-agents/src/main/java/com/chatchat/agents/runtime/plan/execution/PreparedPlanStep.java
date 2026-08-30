package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.Map;

/** Activity output describing either a tool child invocation or an immediate deterministic result. */
public record PreparedPlanStep(
    int stepId,
    String actionType,
    String toolName,
    PlanToolExecutionCommand toolCommand,
    int toolActivityMaximumAttempts,
    long toolActivityStartToCloseSeconds,
    boolean toolActivityRetrySafe,
    String toolActivityRetryReason,
    InterpretationPlanRuntime.StepExecution immediateResult,
    Map<String, Object> metadata
) {
    public PreparedPlanStep {
        actionType = actionType == null ? "" : actionType.trim();
        toolName = toolName == null ? "" : toolName.trim();
        toolActivityMaximumAttempts = Math.max(1, toolActivityMaximumAttempts);
        toolActivityStartToCloseSeconds = Math.max(1L, toolActivityStartToCloseSeconds);
        toolActivityRetryReason = toolActivityRetryReason == null
            ? "not_retry_admitted" : toolActivityRetryReason.trim();
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if ((toolCommand == null) == (immediateResult == null)) {
            throw new IllegalArgumentException(
                "Prepared step requires exactly one tool command or immediate result");
        }
    }
}
