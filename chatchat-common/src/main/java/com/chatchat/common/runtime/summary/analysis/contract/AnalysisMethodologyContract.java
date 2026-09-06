package com.chatchat.common.runtime.summary.analysis.contract;

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
        result.put("acceptancePolicy", AnalysisAcceptanceContract.standard().toMap());
        result.put("reasoningSequence", reasoningSequence);
        result.put("baselinePolicy", baselinePolicy);
        result.put("decompositionQuestions", decompositionQuestions);
        result.put("anomalyDimensions", anomalyDimensions);
        result.put("claimTypes", claimTypes);
        result.put("findingPriority", findingPriority);
        result.put("reportSections", reportSections);
        result.put("claimBoundaryPolicy", Map.of(
            "factRule", "Preserve producer field meaning, unit, realization basis and period. Numeric equality does not establish semantic equivalence. Undeclared field meanings remain unresolved.",
            "sampleRule", "Returned rows are not the population unless completeness is explicitly established. Single-date data and a few closed positions cannot establish habitual frequency, typical holding periods, motives or long-term performance.",
            "inferenceRule", "Keep observation, derived metric and hypothesis distinct. Asset allocation cannot establish intent; holding count cannot establish diversification quality; a few profitable trades cannot establish win rate or strategy effectiveness.",
            "consistencyRule", "Use the same metric definition and evidence scope in executive summary, body, profile and recommendations. A limitations section cannot repair an overstatement elsewhere.",
            "reconciliationRule", "Do not compute other-items residuals from different dates, populations or measures. Rank and concentration require a verified comparison population.",
            "recommendationRule", "Recommend verification or monitoring supported by the observed state; do not prescribe a strategy based on an unverified customer identity or inferred motive."));
        result.put("partialEvidencePolicy", Map.of(
            "disposition", "ANALYZE_AVAILABLE_EVIDENCE_FIRST",
            "minimumUsableDataRule", "One usable returned record requires an analysis of every conclusion that record can support.",
            "scopeRule", "State the actual returned population and period; never extrapolate a partial sample to the full business population.",
            "missingDataRule", "Missing fields, history or datasets restrict only dependent claims, not analysis of other available evidence. Missing is not zero.",
            "baselineRule", "Without history, analyze the current state, composition and supported cross-sectional differences; withhold unsupported trends and abnormality labels.",
            "recommendationRule", "Tie each recommendation to a supported finding and explain the business consequence. Qualify conditional actions and their verification needs; do not invent generic advice to fill gaps.",
            "reportOrder", List.of("SUPPORTED_FINDINGS", "BUSINESS_IMPLICATIONS", "EVIDENCE_BOUND_ACTIONS", "MATERIAL_LIMITATIONS", "TARGETED_FOLLOWUP"),
            "completionRule", "Publish supported partial analysis with explicit limitations. Do not substitute an indicator framework or a request for more data for available analysis.",
            "failureRule", "A runtime failure or absence of analysis products is not evidence that source data is empty.",
            "responsibilityRule", "Runtime validates structure, cited evidence existence and data lineage, then organizes publication. The model reviews semantic scope, logic, consistency and analytical quality. Humans judge usefulness."));
        result.put("analysisAuthorityPolicy", Map.of(
            "boundary", "MODEL_DECIDES_HOW_TO_ANALYZE_RUNTIME_DECIDES_WHAT_IS_LEGAL_TO_EXECUTE",
            "analysisOwner", "MODEL_SELECTS_QUESTION_RELEVANT_ANALYSIS",
            "formulaOwner", "MODEL_SELECTS_ONLY_FROM_DECLARED_SEMANTIC_CONTRACT_OR_EXPLICITLY_PROPOSES_A_FORMULA",
            "runtimeRole", "VALIDATE_PROTOCOL_PERMISSION_PARAMETERS_RESOURCE_BUDGET_READ_ONLY_POLICY_AND_EVIDENCE_LINEAGE_THEN_EXECUTE",
            "semanticBoundary", "Runtime does not decide analytical meaning, formula suitability, causal validity, conclusion strength or business usefulness.",
            "noImplicitFormulaRule", "Runtime must never infer SUM, AVG, ratio, rate, denominator, weighting or time comparison from a numeric column, field name or data type.",
            "unverifiedFormulaRule", "A model-proposed formula without an authorized semantic definition remains a qualified analytical proposal and is not published as a verified metric."));
        result.put("adaptivePromptPolicy", Map.of(
            "defaultMode", "MODEL_SYNTHESIZED_FROM_QUESTION_ROLE_AND_SEMANTIC_METADATA",
            "artifact", "STRUCTURED_DYNAMIC_ANALYSIS_PROMPT_CONTRACT",
            "invocationScope", "ONCE_PER_QUESTION_NOT_PER_DATASET_OR_CHUNK",
            "inputBoundary", "QUESTION_ROLE_OBJECTIVE_AND_SEMANTIC_METADATA_NO_RAW_RECORDS",
            "cachePolicy", "REUSE_BY_QUESTION_CONTEXT_AND_MODEL_FINGERPRINT",
            "fallbackPolicy", "COMPILE_SAFE_GENERIC_GUIDANCE_AND_CONTINUE_ANALYZING_AVAILABLE_EVIDENCE",
            "fixedPromptPolicy", "ONLY_EXPLICIT_PRODUCER_GOVERNED_CONTRACTS",
            "authorityBoundary", "PROMPT_GUIDANCE_NEVER_GRANTS_TOOL_FORMULA_JOIN_OR_DATA_ACCESS_AUTHORITY"));
        result.put("modelReportQualityPolicy", Map.of(
            "findingUnit", List.of("QUESTION", "OBSERVATION", "INTERPRETATION", "IMPLICATION", "EVIDENCE", "CONFIDENCE", "CAVEAT"),
            "canonicalMetricRule", "Use one canonical value, unit, period, population and definition for the same metric throughout the report.",
            "logicalStrengthRule", "Interpretation must not be stronger than observation; implication must not be stronger than interpretation; recommendation must identify the finding that motivates it.",
            "crossSectionRule", "Executive summary, detail, limitation and recommendation must not contradict each other. Qualify a claim where it first appears rather than repairing it later with a caveat.",
            "readerRule", "Use meaningful finding titles, lead with the answer, place evidence next to the claim, avoid internal IDs and remove repeated or empty sections.",
            "selfReview", List.of("QUESTION_ANSWERED", "METRIC_DEFINITION_STABLE", "TIME_SCOPE_STABLE",
                "POPULATION_SCOPE_STABLE", "NO_INTERNAL_CONTRADICTION", "NO_UNSUPPORTED_CAUSE",
                "NO_SAMPLE_TO_LONG_TERM_EXPANSION", "ACTION_TRACES_TO_FINDING", "READABLE_WITHOUT_RUNTIME_CONTEXT")));
        result.put("insightBlockPolicy", Map.of(
            "minimumPrimaryExpressions", 2,
            "requiredDataExpression", List.of("VERIFIED_CHART", "VERIFIED_TABLE", "VERIFIED_METRIC", "VERIFIED_RECORD_EVIDENCE"),
            "textRole", "EXPLAIN_DATA_NOT_REPLACE_DATA",
            "chartValueOwner", "RUNTIME_DATA_EXECUTOR",
            "missingDataDisposition", "DATA_STATUS_BLOCK_EXCLUDED_FROM_EXECUTIVE_CONCLUSIONS",
            "compositionOrder", List.of("QUESTION", "DATA", "VISUALIZATION", "OBSERVATION",
                "INTERPRETATION", "IMPLICATION", "EVIDENCE", "CONFIDENCE", "CAVEAT")));
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
