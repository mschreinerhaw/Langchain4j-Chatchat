package com.chatchat.common.runtime.summary.analysis.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral contract that distinguishes an analytical conclusion from a data inventory.
 * Domain meaning and thresholds remain producer-owned; Runtime only governs reasoning depth.
 */
public record ProfessionalAnalysisDepthContract(
    String schemaVersion,
    List<String> objectiveModes,
    List<String> reasoningDimensions,
    List<String> qualityGates,
    Map<String, List<String>> minimumDimensionsByMode
) {
    public static final String SCHEMA_VERSION = "professional_analysis_depth.v1";

    public ProfessionalAnalysisDepthContract {
        schemaVersion = SCHEMA_VERSION;
        objectiveModes = immutable(objectiveModes);
        reasoningDimensions = immutable(reasoningDimensions);
        qualityGates = immutable(qualityGates);
        minimumDimensionsByMode = immutableMap(minimumDimensionsByMode);
        if (objectiveModes.isEmpty() || reasoningDimensions.isEmpty()
            || qualityGates.isEmpty() || minimumDimensionsByMode.isEmpty()) {
            throw new IllegalArgumentException("professional analysis depth sections are required");
        }
    }

    public static ProfessionalAnalysisDepthContract enterpriseDefault() {
        return new ProfessionalAnalysisDepthContract(SCHEMA_VERSION, List.of(
            "DESCRIBE", "COMPARE", "DIAGNOSE", "FORECAST", "DECIDE"
        ), List.of(
            "STATE", "BASELINE", "DEVIATION", "MATERIALITY", "IMPACT",
            "HYPOTHESIS", "ALTERNATIVE_EXPLANATION", "VERIFICATION", "ACTION"
        ), List.of(
            "A_DATA_INVENTORY_IS_NOT_AN_ANALYTICAL_CONCLUSION",
            "STATUS_REQUIRES_A_DECLARED_BASELINE_OR_COMPARABLE_REFERENCE",
            "DEVIATION_REQUIRES_AUTHORIZED_COMPARISON_OR_DERIVATION",
            "CAUSE_REQUIRES_DISCRIMINATING_EVIDENCE_AND_ALTERNATIVES",
            "ACTION_REQUIRES_A_SUPPORTED_FINDING_IMPACT_AND_CONFIDENCE",
            "UNSUPPORTED_REQUIRED_DEPTH_BECOMES_AN_EVIDENCE_GAP",
            "OBSERVATION_ONLY_OUTPUT_MUST_NOT_IMPLY_DIAGNOSIS_OR_CAUSALITY"
        ), Map.of(
            "DESCRIBE", List.of("STATE"),
            "COMPARE", List.of("STATE", "BASELINE", "DEVIATION"),
            "DIAGNOSE", List.of("STATE", "BASELINE", "DEVIATION", "IMPACT",
                "HYPOTHESIS", "ALTERNATIVE_EXPLANATION", "VERIFICATION"),
            "FORECAST", List.of("STATE", "BASELINE", "DEVIATION", "HYPOTHESIS",
                "ALTERNATIVE_EXPLANATION", "VERIFICATION"),
            "DECIDE", List.of("STATE", "MATERIALITY", "IMPACT", "ACTION")
        ));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("objectiveModes", objectiveModes);
        result.put("reasoningDimensions", reasoningDimensions);
        result.put("qualityGates", qualityGates);
        result.put("minimumDimensionsByMode", minimumDimensionsByMode);
        return Collections.unmodifiableMap(result);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static Map<String, List<String>> immutableMap(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank()) result.put(key, immutable(value));
        });
        return Collections.unmodifiableMap(result);
    }
}
