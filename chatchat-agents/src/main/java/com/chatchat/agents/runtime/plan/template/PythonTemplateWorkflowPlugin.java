package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** Python analysis template workflow. */
public final class PythonTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public PythonTemplateWorkflowPlugin() {
        super("python-template-workflow.v1", Set.of("mcp.python-template.v1"),
            Set.of("python_analysis", "python"));
    }
}
