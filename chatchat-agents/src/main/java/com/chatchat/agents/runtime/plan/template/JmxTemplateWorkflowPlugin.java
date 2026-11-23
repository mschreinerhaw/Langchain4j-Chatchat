package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** Binds a published JMX registry ID to the read-only monitoring executor. */
public final class JmxTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public JmxTemplateWorkflowPlugin() {
        super("jmx-template-workflow.v1", Set.of("mcp.jmx-template.v1"), Set.of("jmx_endpoint"));
    }

    @Override
    public String templateIdInputPath() {
        return "$.template";
    }
}
