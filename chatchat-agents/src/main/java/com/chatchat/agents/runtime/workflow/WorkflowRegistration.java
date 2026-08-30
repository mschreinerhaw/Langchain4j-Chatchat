package com.chatchat.agents.runtime.workflow;

public record WorkflowRegistration<I, O>(
    String workflowType,
    Class<I> inputType,
    Class<O> outputType,
    WorkflowDefinition<I, O> definition
) {
    public WorkflowRegistration {
        if (workflowType == null || workflowType.isBlank()) {
            throw new IllegalArgumentException("Workflow type is required");
        }
        if (inputType == null || outputType == null || definition == null) {
            throw new IllegalArgumentException("Workflow input, output and definition are required");
        }
        workflowType = workflowType.trim();
    }
}
