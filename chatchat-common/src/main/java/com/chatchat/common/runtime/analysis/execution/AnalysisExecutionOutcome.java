package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;

import java.util.Map;

public record AnalysisExecutionOutcome(String schemaVersion, AnalysisWorkflowType workflowType,
                                       WorkflowPlan plan, VerificationResult verification,
                                       EvidenceBundle evidenceBundle, String synthesis,
                                       Map<String, Object> metadata) {
    public static final String SCHEMA_VERSION = "analysis_execution_outcome.v1";
    public AnalysisExecutionOutcome {
        schemaVersion = SCHEMA_VERSION;
        evidenceBundle = evidenceBundle == null ? EvidenceBundle.empty("no evidence") : evidenceBundle;
        synthesis = synthesis == null ? "" : synthesis;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
