package com.chatchat.agents.orchestration.analysis.contract;

import java.util.Map;

/** Shared routing and fail-closed boundary for the retired Java data-exploration path. */
public final class RuntimeAnalysisOwnership {
    private RuntimeAnalysisOwnership() { }

    public static boolean codingOwnsAnalysis(Map<String, Object> attributes) {
        return attributes != null && (java.util.Set.of("DATA_ANALYSIS", "DOCUMENT", "DIRECT_ANSWER", "ASSET_GUIDANCE")
                .contains(String.valueOf(attributes.get("workflowFamily")))
            || java.util.Set.of("DATA_ANALYSIS", "QA").contains(String.valueOf(attributes.get("taskType")))
            || Boolean.TRUE.equals(attributes.get("codingOwnedAnalysisActive"))
            || "CODING_OWNED_DATA_ANALYSIS".equals(attributes.get("analysisExecutionMode")));
    }

    public static void requireJavaExplorationAllowed(Map<String, Object> attributes, Map<String, Object> metadata) {
        if (codingOwnsAnalysis(attributes) || codingOwnsAnalysis(metadata)) {
            throw new IllegalStateException("CODING_ANALYSIS_REQUIRED: delivered data analysis cannot enter Java model exploration");
        }
    }
}
