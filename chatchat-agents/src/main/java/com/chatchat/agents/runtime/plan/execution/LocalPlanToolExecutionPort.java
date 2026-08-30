package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.common.tool.ToolOutput;

import java.util.Objects;

/** In-process adapter retained for the local workflow engine and compatibility tests. */
public final class LocalPlanToolExecutionPort implements PlanToolExecutionPort {

    private final ToolRuntimeService toolRuntimeService;

    public LocalPlanToolExecutionPort(ToolRuntimeService toolRuntimeService) {
        this.toolRuntimeService = Objects.requireNonNull(toolRuntimeService, "toolRuntimeService");
    }

    @Override
    public ToolRuntimeExecution execute(PlanToolExecutionCommand command) {
        return toolRuntimeService.execute(command.request());
    }

    @Override
    public Object resolveOutputForEvidenceReview(ToolOutput output) {
        return toolRuntimeService.resolveOutputForEvidenceReview(output);
    }
}
