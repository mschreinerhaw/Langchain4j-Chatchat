package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** SSH command template workflow. */
public final class SshTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public SshTemplateWorkflowPlugin() {
        super("ssh-template-workflow.v1", Set.of("mcp.ssh-template.v1"),
            Set.of("ssh_host", "host", "ssh", "linux_host"));
    }

    @Override
    public String templateIdInputPath() {
        return "$.template";
    }

    @Override
    public java.util.Map<String, Object> templateDiscoveryInput(java.util.Map<String, Object> assetInput) {
        if (!java.util.List.of("asset", "asset_selection").contains(assetInput.getOrDefault("stage", "")))
            return java.util.Map.of();
        var input = new java.util.LinkedHashMap<String, Object>(assetInput);
        input.put("stage", "template");
        return input;
    }
}
