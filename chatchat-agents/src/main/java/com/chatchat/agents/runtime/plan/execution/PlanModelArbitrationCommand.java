package com.chatchat.agents.runtime.plan.execution;

import java.util.List;

/** Serializable request for the non-deterministic model-arbitration Activity. */
public record PlanModelArbitrationCommand(
    String schemaVersion,
    PlanExecutionContinuation continuation,
    List<Integer> readyStepIds,
    String purpose
) {
    public static final String SCHEMA_VERSION = "plan_model_arbitration.v1";

    public PlanModelArbitrationCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (continuation == null) {
            throw new IllegalArgumentException("Plan continuation is required");
        }
        readyStepIds = readyStepIds == null ? List.of() : readyStepIds.stream()
            .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        purpose = purpose == null ? "DAG_ARBITRATION" : purpose.trim();
    }
}
