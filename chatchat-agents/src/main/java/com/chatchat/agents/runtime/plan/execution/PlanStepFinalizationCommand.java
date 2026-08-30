package com.chatchat.agents.runtime.plan.execution;

import java.util.List;
import java.util.Map;

/** Serializable request to resume one prepared step with recorded Tool Child results. */
public record PlanStepFinalizationCommand(
    String schemaVersion,
    PlanExecutionContinuation continuation,
    int stepId,
    Map<Integer, Map<String, Object>> parameterOverrides,
    List<PlanToolExecutionReceipt> receipts
) {
    public static final String SCHEMA_VERSION = "plan_step_finalization.v1";

    public PlanStepFinalizationCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (continuation == null) {
            throw new IllegalArgumentException("Plan continuation is required");
        }
        parameterOverrides = Map.copyOf(parameterOverrides == null ? Map.of() : parameterOverrides);
        receipts = List.copyOf(receipts == null ? List.of() : receipts);
        if (receipts.isEmpty()) {
            throw new IllegalArgumentException("At least one Tool Child receipt is required");
        }
    }
}
