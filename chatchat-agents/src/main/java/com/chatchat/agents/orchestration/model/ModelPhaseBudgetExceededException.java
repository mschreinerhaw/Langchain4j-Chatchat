package com.chatchat.agents.orchestration.model;

/** A phase timeout is not user cancellation or a semantic rejection of returned candidates. */
public final class ModelPhaseBudgetExceededException extends RuntimeException {
    public ModelPhaseBudgetExceededException() {
        super("MODEL_REVIEW_DEADLINE_EXCEEDED: model review phase exceeded its configured wall-clock budget");
    }
}
