package com.chatchat.common.runtime.summary.analysis.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral enterprise reasoning contract shared by analysis coordinators and Workers.
 * It defines analytical discipline without recognizing any business domain or payload shape.
 */
public record ProfessionalDataAnalysisContract(
    String schemaVersion,
    List<String> mandatoryStages,
    List<String> claimClasses,
    List<String> qualityGates,
    List<String> presentationRules
) {
    public static final String SCHEMA_VERSION = "professional_data_analysis.v1";

    public ProfessionalDataAnalysisContract {
        schemaVersion = SCHEMA_VERSION;
        mandatoryStages = immutable(mandatoryStages);
        claimClasses = immutable(claimClasses);
        qualityGates = immutable(qualityGates);
        presentationRules = immutable(presentationRules);
        if (mandatoryStages.isEmpty() || claimClasses.isEmpty() || qualityGates.isEmpty()) {
            throw new IllegalArgumentException("professional analysis contract sections are required");
        }
    }

    public static ProfessionalDataAnalysisContract enterpriseDefault() {
        return new ProfessionalDataAnalysisContract(SCHEMA_VERSION, List.of(
            "ESTABLISH_OBJECTIVE_SCOPE_AND_AUTHORIZED_RELATIONSHIPS",
            "AUDIT_COVERAGE_QUALITY_CONFLICTS_AND_GRAIN",
            "MEASURE_OBJECTIVE_RELEVANT_LEVELS_DELTAS_DISTRIBUTIONS_AND_CONCENTRATION",
            "IDENTIFY_MATERIAL_PATTERNS_EXCEPTIONS_AND_ALTERNATIVE_EXPLANATIONS",
            "CALIBRATE_CLAIM_STRENGTH_TO_TIME_RANGE_SAMPLE_SIZE_AND_COMPLETENESS",
            "SYNTHESIZE_DECISION_RELEVANT_FINDINGS_AND_RESIDUAL_UNCERTAINTY"
        ), List.of(
            "OBSERVED_RETURNED_FACT",
            "AUTHORIZED_DERIVED_MEASURE",
            "CALIBRATED_INFERENCE"
        ), List.of(
            "EVERY_MATERIAL_CLAIM_HAS_TRACEABLE_SUPPORT",
            "DERIVED_MEASURES_DECLARE_FORMULA_INPUTS_SCOPE_AND_UNIT",
            "DERIVED_AND_PROXY_CLAIMS_REQUIRE_VALIDATED_PRODUCER_SEMANTIC_BASIS",
            "INFERENCE_IS_LABELLED_AND_HAS_ALTERNATIVE_EXPLANATIONS",
            "CROSS_DATASET_CLAIMS_REQUIRE_AN_AUTHORIZED_RELATIONSHIP",
            "CONFLICTS_ARE_RECONCILED_OR_EXPLICITLY_PRESERVED",
            "CONCLUSION_STRENGTH_NEVER_EXCEEDS_EVIDENCE_SCOPE"
        ), List.of(
            "LEAD_WITH_MATERIAL_BUSINESS_MEANING_NOT_RECORD_INVENTORY",
            "RANK_INSIGHTS_BY_OBJECTIVE_RELEVANCE_AND_MATERIALITY",
            "DISTINGUISH_FACT_DERIVATION_AND_INFERENCE",
            "ACCOUNT_FOR_IRRELEVANT_DATASETS_INTERNALLY_BUT_EXCLUDE_THEM_FROM_NARRATIVE",
            "KEEP_FULL_DETAIL_OUT_OF_THE_NARRATIVE_UNLESS_REQUESTED",
            "STATE_EACH_MATERIAL_LIMITATION_ONCE"
        ));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("mandatoryStages", mandatoryStages);
        result.put("claimClasses", claimClasses);
        result.put("qualityGates", qualityGates);
        result.put("presentationRules", presentationRules);
        return Collections.unmodifiableMap(result);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }
}
