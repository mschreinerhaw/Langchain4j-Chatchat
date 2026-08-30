package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;

/** Durable input/output pair used to resume mature step processing after a Tool Child Workflow. */
public record PlanToolExecutionReceipt(
    PlanToolExecutionCommand command,
    ToolRuntimeExecution execution
) {
    public PlanToolExecutionReceipt {
        if (command == null || execution == null) {
            throw new IllegalArgumentException("Tool execution receipt requires command and execution");
        }
    }
}
