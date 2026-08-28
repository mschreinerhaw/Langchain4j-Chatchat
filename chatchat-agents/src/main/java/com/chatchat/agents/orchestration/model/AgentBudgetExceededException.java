package com.chatchat.agents.orchestration.model;

/** Raised before another model invocation can exceed an enforced execution budget. */
public class AgentBudgetExceededException extends RuntimeException {
    public AgentBudgetExceededException(String message) {
        super(message);
    }
}
