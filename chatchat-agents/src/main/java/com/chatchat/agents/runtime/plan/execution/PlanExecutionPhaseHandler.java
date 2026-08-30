package com.chatchat.agents.runtime.plan.execution;

/**
 * Business-owned phase port called by workflow-engine Activities.
 * Implementations may use models, registries and persistence stores; Workflow code may not.
 */
public interface PlanExecutionPhaseHandler {

    PlanModelArbitrationResult arbitrate(PlanModelArbitrationCommand command);

    PlanStepPreparationResult prepare(PlanStepPreparationCommand command);

    PlanNodePersistenceResult persist(PlanNodePersistenceCommand command);
}
