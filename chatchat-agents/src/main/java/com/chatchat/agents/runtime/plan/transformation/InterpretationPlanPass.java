package com.chatchat.agents.runtime.plan.transformation;

public interface InterpretationPlanPass {

    String id();

    PlanPassKind kind();

    default boolean supports(PlanTransformationWorkspace workspace,
                             PlanTransformationContext context) {
        return true;
    }

    void apply(PlanTransformationWorkspace workspace,
               PlanTransformationContext context);
}
