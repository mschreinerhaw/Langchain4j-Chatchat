package com.chatchat.runtime.temporal.workflow.core;

import com.chatchat.runtime.temporal.contract.core.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.core.TemporalWorkflowResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsTemporalWorkflow {

    @WorkflowMethod(name = "runtime-os-workflow")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
