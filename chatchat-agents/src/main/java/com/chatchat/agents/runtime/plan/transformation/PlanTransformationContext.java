package com.chatchat.agents.runtime.plan.transformation;

import com.chatchat.agents.tool.ToolRegistry;

public record PlanTransformationContext(
    ToolRegistry toolRegistry,
    Object authoritativeWorkflowDag
) {
}
