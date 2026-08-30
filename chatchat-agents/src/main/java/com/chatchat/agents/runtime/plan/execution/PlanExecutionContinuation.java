package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.List;
import java.util.Map;

/** Serializable continuation owned by a deterministic plan execution Workflow. */
public record PlanExecutionContinuation(
    String schemaVersion,
    String sessionId,
    InterpretationPlan plan,
    List<Integer> remainingStepIds,
    List<InterpretationPlanRuntime.StepExecution> completedSteps,
    List<Integer> skippedStepIds,
    List<Integer> failedStepIds,
    int decisionCount,
    Map<String, Object> context
) {
    public static final String SCHEMA_VERSION = "plan_execution_continuation.v1";

    public PlanExecutionContinuation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (sessionId == null || sessionId.isBlank() || plan == null) {
            throw new IllegalArgumentException("Plan execution session and plan are required");
        }
        sessionId = sessionId.trim();
        remainingStepIds = copyIds(remainingStepIds);
        completedSteps = List.copyOf(completedSteps == null ? List.of() : completedSteps);
        skippedStepIds = copyIds(skippedStepIds);
        failedStepIds = copyIds(failedStepIds);
        decisionCount = Math.max(0, decisionCount);
        context = Map.copyOf(context == null ? Map.of() : context);
    }

    private static List<Integer> copyIds(List<Integer> values) {
        return values == null ? List.of() : values.stream()
            .filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }
}
