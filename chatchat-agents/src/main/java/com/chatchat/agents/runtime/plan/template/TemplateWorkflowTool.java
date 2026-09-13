package com.chatchat.agents.runtime.plan.template;

import com.chatchat.common.tool.ToolWorkflowRole;

/** Immutable publisher-declared identity used to select a template workflow plugin. */
public record TemplateWorkflowTool(
    String toolName,
    ToolWorkflowRole role,
    String protocolFamily,
    String assetType
) {
}
