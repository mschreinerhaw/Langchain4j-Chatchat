package com.chatchat.agents.runtime.plan.execution;

import java.util.List;
import java.util.Map;

/** Serializable decision returned by the model-arbitration Activity. */
public record PlanModelArbitrationResult(
    String action,
    List<Integer> selectedStepIds,
    Map<Integer, Map<String, Object>> parameterOverrides,
    String finalAnswer,
    String reason
) {
    public PlanModelArbitrationResult {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Arbitration action is required");
        }
        action = action.trim();
        selectedStepIds = selectedStepIds == null ? List.of() : selectedStepIds.stream()
            .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        parameterOverrides = Map.copyOf(parameterOverrides == null ? Map.of() : parameterOverrides);
    }
}
