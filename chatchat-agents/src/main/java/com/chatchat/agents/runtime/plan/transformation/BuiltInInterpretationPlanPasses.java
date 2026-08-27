package com.chatchat.agents.runtime.plan.transformation;

import java.util.List;

/** Canonical ordering and failure classification of built-in model-plan passes. */
public final class BuiltInInterpretationPlanPasses {

    private BuiltInInterpretationPlanPasses() {
    }

    public static List<InterpretationPlanPass> create(BuiltInPlanPassOperations operations) {
        return List.of(
            pass("DocumentSearchInputSanitizerPass", PlanPassKind.NORMALIZATION, null,
                operations::sanitizeDocumentSearchInput),
            pass("PruneNoopPass", PlanPassKind.OPTIONAL_OPTIMIZATION, operations::unlocked,
                operations::pruneNoop),
            pass("DedupeToolCallPass", PlanPassKind.OPTIONAL_OPTIMIZATION, operations::unlocked,
                operations::dedupeToolCalls),
            pass("AuthoritativeWorkflowDagPass", PlanPassKind.REQUIRED_REPAIR, null,
                operations::repairAuthoritativeWorkflowDag),
            pass("TemplateExecutionDagRepairPass", PlanPassKind.REQUIRED_REPAIR, null,
                operations::repairTemplateExecutionDag),
            pass("LockedBindingEdgeContractRepairPass", PlanPassKind.REQUIRED_REPAIR, null,
                operations::repairLockedBindingEdges),
            pass("PolicyAwareOrderingPass", PlanPassKind.OPTIONAL_OPTIMIZATION, null,
                operations::applyPolicyAwareOrdering),
            pass("ParallelHintPass", PlanPassKind.OPTIONAL_OPTIMIZATION, null,
                operations::applyParallelHint),
            pass("RetrievalPolicyGuardPass", PlanPassKind.POLICY_GUARD, null,
                operations::guardRetrievalPolicy)
        );
    }

    private static InterpretationPlanPass pass(
        String id,
        PlanPassKind kind,
        java.util.function.BiPredicate<PlanTransformationWorkspace, PlanTransformationContext> supports,
        java.util.function.BiConsumer<PlanTransformationWorkspace, PlanTransformationContext> action
    ) {
        return new FunctionalInterpretationPlanPass(id, kind, supports, action);
    }
}
