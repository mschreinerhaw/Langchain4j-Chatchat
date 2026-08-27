package com.chatchat.agents.runtime.plan.transformation;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public final class FunctionalInterpretationPlanPass implements InterpretationPlanPass {

    private final String id;
    private final PlanPassKind kind;
    private final BiPredicate<PlanTransformationWorkspace, PlanTransformationContext> supports;
    private final BiConsumer<PlanTransformationWorkspace, PlanTransformationContext> action;

    public FunctionalInterpretationPlanPass(
        String id,
        PlanPassKind kind,
        BiPredicate<PlanTransformationWorkspace, PlanTransformationContext> supports,
        BiConsumer<PlanTransformationWorkspace, PlanTransformationContext> action
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.supports = supports == null ? (workspace, context) -> true : supports;
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PlanPassKind kind() {
        return kind;
    }

    @Override
    public boolean supports(PlanTransformationWorkspace workspace,
                            PlanTransformationContext context) {
        return supports.test(workspace, context);
    }

    @Override
    public void apply(PlanTransformationWorkspace workspace,
                      PlanTransformationContext context) {
        action.accept(workspace, context);
    }
}
