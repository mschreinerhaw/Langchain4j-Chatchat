package com.chatchat.agents.runtime.plan.template;

import java.util.Set;

/** Database/SQL template workflow. */
public final class SqlTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public SqlTemplateWorkflowPlugin() {
        super("sql-template-workflow.v1", Set.of("mcp.sql-template.v1"),
            Set.of("database", "database_schema", "sql"));
    }

    @Override
    public boolean acceptsTemplateIdBinding(String outputPath, String inputPath) {
        return super.acceptsTemplateIdBinding(outputPath, inputPath)
            || canonicalOutput(outputPath) && "template".equals(normalize(inputPath));
    }

    @Override
    public Set<String> runtimeOwnedExecutionInputs() {
        return Set.of("parameters", "params", "arguments", "executionContext", "mcpExecutionContext");
    }

    private boolean canonicalOutput(String value) {
        return "templates0templateid".equals(normalize(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
