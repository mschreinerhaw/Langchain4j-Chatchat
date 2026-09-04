package com.chatchat.agents.orchestration.analysis.governance;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

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
            || !AnalysisOutputAdmissionPolicy.admit(answer).admitted()
            || !hasNarrativeAnalysis(answer, supervisedDriverSynthesis)) {
            return false;
        }
        metadata.put("governedGlobalSynthesisRetained", true);
        metadata.put("governedNarrativeAnalysisReplacedOperationalDraft", false);
        metadata.put("returnedDataAnalysisRequired", true);
        metadata.put("ungovernedCandidateWithheld", false);
        metadata.put("governedNarrativeAnalysisSource", "GLOBAL_DRIVER_SYNTHESIS");
        return true;
    }

    private static boolean hasNarrativeAnalysis(String answer, boolean supervisedDriverSynthesis) {
        if (answer == null || answer.isBlank()) return false;
        String narrative = Arrays.stream(answer.split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("#") && !line.startsWith("|") && !line.startsWith("```"))
            .filter(line -> !line.matches("^[-:| ]+$"))
            .filter(line -> !line.matches("^(?:[-*]\\s*)?(?:数据来源|来源|数据集|共?\\s*\\d+\\s*(?:行|个数据集)).*$"))
            .map(line -> line.replaceAll("[`*_>#]", "").trim())
            .collect(Collectors.joining(" "));
        return narrative.length() >= (supervisedDriverSynthesis ? 12 : 40);
    }
}
