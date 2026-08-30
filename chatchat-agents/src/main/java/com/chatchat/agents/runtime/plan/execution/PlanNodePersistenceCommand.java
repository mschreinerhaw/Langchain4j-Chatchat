package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.List;

/** Serializable commit/reject journal command emitted only after the Workflow barrier decision. */
public record PlanNodePersistenceCommand(
    String schemaVersion,
    PlanExecutionContinuation continuation,
    long workflowRevision,
    String barrierAction,
    List<InterpretationPlanRuntime.StepExecution> waveResults
) {
    public static final String SCHEMA_VERSION = "plan_node_persistence.v1";

    public PlanNodePersistenceCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (continuation == null || barrierAction == null || barrierAction.isBlank()) {
            throw new IllegalArgumentException("Plan continuation and barrier action are required");
        }
        workflowRevision = Math.max(0L, workflowRevision);
        barrierAction = barrierAction.trim();
        waveResults = List.copyOf(waveResults == null ? List.of() : waveResults);
    }
}
