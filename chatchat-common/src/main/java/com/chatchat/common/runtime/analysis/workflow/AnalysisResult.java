package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record AnalysisResult(String schemaVersion, AnalysisWorkflowType workflowType,
                             WorkflowPlan plan, VerificationResult verification,
                             EvidenceBundle evidenceBundle, String synthesis,
                             Map<String, Object> metadata) {
    public static final String SCHEMA_VERSION = "analysis_workflow_result.v1";
    public AnalysisResult {
        schemaVersion = SCHEMA_VERSION;
        evidenceBundle = evidenceBundle == null ? EvidenceBundle.empty("no evidence") : evidenceBundle;
        synthesis = synthesis == null ? "" : synthesis;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
