package com.chatchat.agents.orchestration.analysis.governance;

import java.util.Map;

/** Preserves a contract-governed global synthesis as the sole business narrative. */
public final class GovernedGlobalSynthesisPolicy {

    private static final String CONTRACT_VERSION = "model_analysis_repair_v1";

    private GovernedGlobalSynthesisPolicy() {
    }

    public static boolean retain(String answer,
                                 boolean coverageComplete,
                                 boolean evidenceTraceComplete,
                                 Map<String, Object> metadata) {
        boolean supervisedDriverSynthesis = metadata != null
            && Boolean.TRUE.equals(metadata.get("analysisSynthesisBarrierReady"));
        boolean reviewedDriverSynthesis = metadata != null
            && CONTRACT_VERSION.equals(metadata.get("modelAnalysisReviewContractVersion"));
        if (metadata == null
            || (!supervisedDriverSynthesis && !reviewedDriverSynthesis)
            || Boolean.TRUE.equals(metadata.get("interpretationPlanDeterministicSummaryFallback"))
            || answer == null || answer.isBlank()) {
            return false;
        }
        metadata.put("governedGlobalSynthesisRetained", true);
        metadata.put("governedNarrativeAnalysisReplacedOperationalDraft", false);
        metadata.put("returnedDataAnalysisRequired", true);
        metadata.put("ungovernedCandidateWithheld", false);
        metadata.put("governedNarrativeAnalysisSource", "GLOBAL_DRIVER_SYNTHESIS");
        return true;
    }
}
