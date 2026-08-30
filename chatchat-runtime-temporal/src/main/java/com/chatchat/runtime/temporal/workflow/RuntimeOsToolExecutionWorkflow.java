package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.contract.TemporalToolExecutionSnapshot;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.QueryMethod;

/** Durable workflow entry used by plan children to schedule one tool Activity. */
@WorkflowInterface
public interface RuntimeOsToolExecutionWorkflow {

    @WorkflowMethod(name = "runtime-os-tool-execution-v1")
    ToolRuntimeExecution execute(TemporalToolActivityCommand command);

    @QueryMethod(name = "runtime-os-tool-status-v1")
    TemporalToolExecutionSnapshot status();
}
