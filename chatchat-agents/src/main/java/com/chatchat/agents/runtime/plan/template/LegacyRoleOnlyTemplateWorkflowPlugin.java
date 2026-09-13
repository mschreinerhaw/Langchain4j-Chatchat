package com.chatchat.agents.runtime.plan.template;

/**
 * Generic publisher-contract fallback. It joins only identical protocol families (or legacy
 * role-only tools), so an unknown extension remains pluggable without enabling cross-asset edges.
 */
public final class LegacyRoleOnlyTemplateWorkflowPlugin implements TemplateWorkflowPlugin {
    @Override
    public String id() {
        return "publisher-contract-template-workflow.v1";
    }

    @Override
    public int priority() {
        return -1000;
    }

    @Override
    public boolean supports(TemplateWorkflowTool tool) {
        return tool != null;
    }

    @Override
    public boolean accepts(TemplateWorkflowTool candidate, TemplateWorkflowTool executionTool) {
        if (!accepts(candidate) || executionTool == null) return false;
        String candidateFamily = normalized(candidate.protocolFamily());
        String executionFamily = normalized(executionTool.protocolFamily());
        return candidateFamily.equals(executionFamily);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }
}
