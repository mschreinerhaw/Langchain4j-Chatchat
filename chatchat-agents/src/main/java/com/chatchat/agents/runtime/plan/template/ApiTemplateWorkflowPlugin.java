package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** API service template workflow. */
public final class ApiTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public ApiTemplateWorkflowPlugin() {
        super("api-template-workflow.v1",
            Set.of("mcp.api-template.v1", "mcp.api-template-discovery.v1", "mcp.api-service-asset.v1"),
            Set.of("api_service"));
    }
}
