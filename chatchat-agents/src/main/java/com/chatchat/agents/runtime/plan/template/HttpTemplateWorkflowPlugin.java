package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** HTTP endpoint template workflow. */
public final class HttpTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public HttpTemplateWorkflowPlugin() {
        super("http-template-workflow.v1", Set.of("mcp.http-template.v1"),
            Set.of("http_endpoint", "http_service"));
    }

    @Override
    public String templateIdInputPath() {
        return "$.template";
    }
}
