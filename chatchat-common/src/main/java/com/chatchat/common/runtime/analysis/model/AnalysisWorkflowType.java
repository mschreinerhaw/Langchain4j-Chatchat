package com.chatchat.common.runtime.analysis.model;

public enum AnalysisWorkflowType {
    DOCUMENT,
    ASSET_GUIDANCE,
    STRUCTURED_DATA,
    TOOL,
    COMPUTATION,
    EXTERNAL_RESEARCH,
    FEDERATED_AGENT,
    DOMAIN_INTELLIGENCE,
    COMPOSITE;

    public RuntimeWorkflowFamily family() {
        return switch (this) {
            case DOCUMENT -> RuntimeWorkflowFamily.DOCUMENT;
            case ASSET_GUIDANCE -> RuntimeWorkflowFamily.ASSET_GUIDANCE;
            case TOOL -> RuntimeWorkflowFamily.ACTION;
            default -> RuntimeWorkflowFamily.DATA_ANALYSIS;
        };
    }
}
