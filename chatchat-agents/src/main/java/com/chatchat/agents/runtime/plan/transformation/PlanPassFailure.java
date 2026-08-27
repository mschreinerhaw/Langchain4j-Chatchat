package com.chatchat.agents.runtime.plan.transformation;

public record PlanPassFailure(
    String passId,
    PlanPassKind passKind,
    String errorType,
    String message,
    boolean rolledBack
) {
    public static PlanPassFailure optionalRollback(InterpretationPlanPass pass, RuntimeException error) {
        return new PlanPassFailure(
            pass.id(),
            pass.kind(),
            error.getClass().getSimpleName(),
            error.getMessage(),
            true
        );
    }
}
