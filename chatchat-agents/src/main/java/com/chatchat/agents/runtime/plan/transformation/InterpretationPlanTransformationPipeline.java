package com.chatchat.agents.runtime.plan.transformation;

import java.util.List;

/** Executes model-plan passes in deterministic order with optional-pass rollback. */
public final class InterpretationPlanTransformationPipeline
    implements Optimizer<PlanTransformationWorkspace, PlanTransformationContext, PlanTransformationWorkspace> {

    private final List<InterpretationPlanPass> passes;

    public InterpretationPlanTransformationPipeline(List<InterpretationPlanPass> passes) {
        this.passes = passes == null ? List.of() : List.copyOf(passes);
    }

    @Override
    public PlanTransformationWorkspace optimize(PlanTransformationWorkspace workspace,
                                                PlanTransformationContext context) {
        if (workspace == null) {
            throw new IllegalArgumentException("Plan transformation workspace is required");
        }
        for (InterpretationPlanPass pass : passes) {
            if (pass == null || !pass.supports(workspace, context)) {
                continue;
            }
            PlanTransformationWorkspace checkpoint = workspace.snapshot();
            try {
                pass.apply(workspace, context);
            } catch (RuntimeException ex) {
                if (pass.kind() != PlanPassKind.OPTIONAL_OPTIMIZATION) {
                    throw new PlanTransformationException(pass.id(), pass.kind(), ex);
                }
                workspace.restore(checkpoint);
                workspace.recordFailure(PlanPassFailure.optionalRollback(pass, ex));
            }
        }
        return workspace;
    }

    public List<InterpretationPlanPass> passes() {
        return passes;
    }

    public static final class PlanTransformationException extends RuntimeException {
        private final String passId;
        private final PlanPassKind passKind;

        public PlanTransformationException(String passId, PlanPassKind passKind, Throwable cause) {
            super("InterpretationPlan pass failed: " + passId + " (" + passKind + ")", cause);
            this.passId = passId;
            this.passKind = passKind;
        }

        public String passId() {
            return passId;
        }

        public PlanPassKind passKind() {
            return passKind;
        }
    }
}
