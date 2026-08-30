package com.chatchat.agents.orchestration.analysis.summary;

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
        if (metadata == null
            || !CONTRACT_VERSION.equals(metadata.get("modelAnalysisReviewContractVersion"))
            || !coverageComplete || !evidenceTraceComplete || !hasNarrativeAnalysis(answer)) {
            return false;
        }
        metadata.put("governedGlobalSynthesisRetained", true);
        metadata.put("governedNarrativeAnalysisReplacedOperationalDraft", false);
        metadata.put("returnedDataAnalysisRequired", true);
        metadata.put("ungovernedCandidateWithheld", false);
        metadata.put("governedNarrativeAnalysisSource", "GLOBAL_DRIVER_SYNTHESIS");
        return true;
    }

    private static boolean hasNarrativeAnalysis(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String narrative = Arrays.stream(answer.split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("#") && !line.startsWith("|") && !line.startsWith("```"))
            .filter(line -> !line.matches("^[-:| ]+$"))
            .filter(line -> !line.matches("^(?:[-*]\\s*)?(?:数据来源|来源|数据集|共?\\s*\\d+\\s*(?:行|个数据集)).*$"))
            .map(line -> line.replaceAll("[`*_>#]", "").trim())
            .collect(Collectors.joining(" "));
        return narrative.length() >= 40;
    }
}
