package com.chatchat.common.runtime.summary.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-neutral method contract that turns evidence summarization into a repeatable analysis.
 * Business metrics, thresholds and causal semantics remain producer-owned.
 */
public record AnalysisMethodologyContract(
    String schemaVersion,
    List<String> reasoningSequence,
    Map<String, Object> baselinePolicy,
    List<String> decompositionQuestions,
    List<String> anomalyDimensions,
    List<String> claimTypes,
    Map<String, Object> findingPriority,
    List<String> reportSections
) {
    public static final String SCHEMA_VERSION = "analysis_methodology.v1";

    public AnalysisMethodologyContract {
        schemaVersion = SCHEMA_VERSION;
        reasoningSequence = immutable(reasoningSequence);
        baselinePolicy = immutableMap(baselinePolicy);
        decompositionQuestions = immutable(decompositionQuestions);
        anomalyDimensions = immutable(anomalyDimensions);
        claimTypes = immutable(claimTypes);
        findingPriority = immutableMap(findingPriority);
        reportSections = immutable(reportSections);
        if (reasoningSequence.isEmpty() || baselinePolicy.isEmpty()
            || decompositionQuestions.isEmpty() || claimTypes.isEmpty()
            || reportSections.isEmpty()) {
            throw new IllegalArgumentException("analysis methodology sections are required");
        }
    }

    public static AnalysisMethodologyContract enterpriseDefault() {
        return new AnalysisMethodologyContract(SCHEMA_VERSION, List.of(
            "DEFINE_QUESTION", "UNDERSTAND_DATA", "ESTABLISH_BASELINE", "OBSERVE",
            "COMPARE", "DECOMPOSE", "ATTRIBUTE_CONTRIBUTION", "EXPLAIN",
            "CROSS_VALIDATE", "ASSESS_IMPACT", "FORM_CONCLUSION", "RECOMMEND_ACTION"
        ), Map.of(
            "requiredExcept", List.of("PURE_DETAIL_LOOKUP"),
            "allowedTypes", List.of("PERIOD_OVER_PERIOD", "YEAR_OVER_YEAR", "HISTORICAL",
                "TARGET", "BUDGET", "PEER", "INDUSTRY"),
            "missingBaselineDisposition", "LIMIT_COMPARISON_TREND_AND_ABNORMALITY_CLAIMS_ONLY",
            "rule", "Never label a value good, bad, high, low or abnormal without a declared comparable reference."
        ), List.of(
            "WHAT_HAPPENED_OVERALL", "WHERE_DID_CHANGE_OR_DIFFERENCE_OCCUR",
            "WHO_OR_WHAT_CONTRIBUTED_MOST", "WHAT_DRIVES_THE_CONTRIBUTION",
            "WHAT_COMPETING_EXPLANATIONS_EXIST", "WHAT_EVIDENCE_DISCRIMINATES_THEM",
            "WHAT_IS_THE_BUSINESS_IMPACT"
        ), List.of(
            "MAGNITUDE", "VELOCITY", "PERSISTENCE", "CONCENTRATION", "DEVIATION", "CONTRIBUTION"
        ), List.of(
            "FACT", "DERIVED_FACT", "COMPARISON", "TREND", "ANOMALY", "CONTRIBUTION",
            "CORRELATION", "INFERENCE", "CONCLUSION", "RECOMMENDATION"
        ), Map.of(
            "rankingRule", "OBJECTIVE_RELEVANCE_X_MATERIALITY_X_CONFIDENCE",
            "anomalyPriorityRule", "ANOMALY_DEGREE_X_BUSINESS_IMPACT",
            "maximumPrimaryFindings", 5,
            "levels", List.of("PRIMARY", "SECONDARY", "SUPPORTING", "MINOR")
        ), List.of(
            "EXECUTIVE_SUMMARY", "OVERALL_PERFORMANCE", "KEY_DRIVERS", "DEEP_DIVE",
            "RISKS_AND_OPPORTUNITIES", "LIMITATIONS"
        ));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("reasoningSequence", reasoningSequence);
        result.put("baselinePolicy", baselinePolicy);
        result.put("decompositionQuestions", decompositionQuestions);
        result.put("anomalyDimensions", anomalyDimensions);
        result.put("claimTypes", claimTypes);
        result.put("findingPriority", findingPriority);
        result.put("reportSections", reportSections);
        return Collections.unmodifiableMap(result);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return values == null || values.isEmpty() ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
