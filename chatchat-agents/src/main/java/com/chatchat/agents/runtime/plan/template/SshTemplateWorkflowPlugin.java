package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** SSH command template workflow. */
public final class SshTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public SshTemplateWorkflowPlugin() {
        super("ssh-template-workflow.v1", Set.of("mcp.ssh-template.v1"),
            Set.of("host", "ssh", "linux_host"));
    }

    @Override
    public String templateIdInputPath() {
        return "$.template";
    }
}
