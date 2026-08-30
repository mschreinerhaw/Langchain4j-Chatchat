package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Durable child boundary for one Agent execution owned by its parent Agent Run workflow. */
@WorkflowInterface
public interface RuntimeOsAgentExecutionWorkflow {

    @WorkflowMethod(name = "runtime-os-agent-execution-v1")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
