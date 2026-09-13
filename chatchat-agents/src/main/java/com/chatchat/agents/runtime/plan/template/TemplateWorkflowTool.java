package com.chatchat.agents.runtime.plan.template;

import com.chatchat.common.tool.ToolWorkflowRole;

/** Immutable publisher-declared identity used to select a template workflow plugin. */
public record TemplateWorkflowTool(
    String toolName,
    ToolWorkflowRole role,
    String protocolFamily,
    String assetType,
    String parentToolName
) {
    public TemplateWorkflowTool(String toolName,
                                ToolWorkflowRole role,
                                String protocolFamily,
                                String assetType) {
        this(toolName, role, protocolFamily, assetType, null);
    }

    /** A published template group is a child capability invoked through its stable parent. */
    public boolean groupedTemplateDiscovery() {
        return role == ToolWorkflowRole.TEMPLATE_DISCOVERY
            && parentToolName != null && !parentToolName.isBlank();
    }
}
