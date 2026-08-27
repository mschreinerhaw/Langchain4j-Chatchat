package com.chatchat.agents.runtime.plan.transformation;

/** Internal implementation port used by the built-in pass catalog. */
public interface BuiltInPlanPassOperations {

    boolean unlocked(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void sanitizeDocumentSearchInput(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void pruneNoop(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void dedupeToolCalls(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void constrainBusinessCapabilityScope(PlanTransformationWorkspace workspace,
                                          PlanTransformationContext context);

    void repairAuthoritativeWorkflowDag(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void repairTemplateExecutionDag(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void repairLockedBindingEdges(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void applyPolicyAwareOrdering(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void applyParallelHint(PlanTransformationWorkspace workspace, PlanTransformationContext context);

    void guardRetrievalPolicy(PlanTransformationWorkspace workspace, PlanTransformationContext context);
}
