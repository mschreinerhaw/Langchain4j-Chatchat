package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.common.tool.ToolOutput;

/** Engine-neutral boundary that lets local and durable runtimes execute the same plan command. */
public interface PlanToolExecutionPort {

    ToolRuntimeExecution execute(PlanToolExecutionCommand command);

    default Object resolveOutputForEvidenceReview(ToolOutput output) {
        return output == null ? null : output.getData();
    }
}
