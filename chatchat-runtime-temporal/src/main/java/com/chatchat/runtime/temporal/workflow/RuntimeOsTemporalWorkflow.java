package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsTemporalWorkflow {

    @WorkflowMethod(name = "runtime-os-workflow")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
