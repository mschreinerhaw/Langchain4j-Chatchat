package com.chatchat.agents.runtime.plan.execution;

import java.util.List;
import java.util.Map;

/** Serializable request for preparing one admitted Ready-node wave. */
public record PlanStepPreparationCommand(
    String schemaVersion,
    PlanExecutionContinuation continuation,
    List<Integer> selectedStepIds,
    Map<Integer, Map<String, Object>> parameterOverrides
) {
    public static final String SCHEMA_VERSION = "plan_step_preparation.v1";

    public PlanStepPreparationCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (continuation == null) {
            throw new IllegalArgumentException("Plan continuation is required");
        }
        selectedStepIds = selectedStepIds == null ? List.of() : selectedStepIds.stream()
            .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        parameterOverrides = Map.copyOf(parameterOverrides == null ? Map.of() : parameterOverrides);
    }
}
