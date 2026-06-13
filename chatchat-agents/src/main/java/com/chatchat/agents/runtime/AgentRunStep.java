package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;

@Builder
public record AgentRunStep(
    int step,
    String action,
    String toolName,
    String resolvedToolName,
    String reason,
    Map<String, Object> executionPlan,
    String answerPreview,
    long plannedAt,
    int observationCount
) {

    public AgentRunStep {
        executionPlan = executionPlan == null ? Map.of() : new LinkedHashMap<>(executionPlan);
    }
}
