package com.chatchat.runtime.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsTemporalWorkflow {

    @WorkflowMethod(name = "runtime-os-workflow")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
