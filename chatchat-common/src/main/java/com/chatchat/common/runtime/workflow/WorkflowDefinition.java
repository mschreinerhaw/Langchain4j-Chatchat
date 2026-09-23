package com.chatchat.common.runtime.workflow;

@FunctionalInterface
public interface WorkflowDefinition<I, O> {

    O execute(I input, WorkflowExecutionContext context) throws Exception;
}
