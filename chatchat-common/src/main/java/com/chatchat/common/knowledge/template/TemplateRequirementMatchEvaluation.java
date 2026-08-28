package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-neutral semantic admission decision for one template candidate. */
public record TemplateRequirementMatchEvaluation(
    String templateId,
    String businessGroup,
    double relevance,
    double evidenceFit,
    double parameterReadiness,
    double totalScore,
    String decision,
    String relevanceLevel,
    TemplateAnalysisRole analysisRole,
    List<String> reasons,
    List<String> missingParameters,
    List<String> matchedQuestionAspects,
    List<String> relationshipHints
) {
    public TemplateRequirementMatchEvaluation {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId is required");
        }
        templateId = templateId.trim();
        businessGroup = clean(businessGroup);
        relevance = score(relevance);
        evidenceFit = score(evidenceFit);
        parameterReadiness = score(parameterReadiness);
        totalScore = score(totalScore);
        decision = clean(decision);
        relevanceLevel = clean(relevanceLevel);
        analysisRole = analysisRole == null
            ? ("REJECT".equalsIgnoreCase(decision)
                ? TemplateAnalysisRole.IRRELEVANT : TemplateAnalysisRole.CONTEXT)
            : analysisRole;
        reasons = immutable(reasons);
        missingParameters = immutable(missingParameters);
        matchedQuestionAspects = immutable(matchedQuestionAspects);
        relationshipHints = immutable(relationshipHints);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("templateId", templateId);
        if (businessGroup != null) value.put("businessGroup", businessGroup);
        value.put("relevance", relevance);
        value.put("evidenceFit", evidenceFit);
        value.put("parameterReadiness", parameterReadiness);
        value.put("totalScore", totalScore);
        value.put("decision", decision == null ? "UNDECIDED" : decision);
        value.put("relevanceLevel", relevanceLevel == null ? level(totalScore) : relevanceLevel);
        value.put("analysisRole", analysisRole.name());
        value.put("reasons", reasons);
        value.put("missingParameters", missingParameters);
        value.put("matchedQuestionAspects", matchedQuestionAspects);
        value.put("relationshipHints", relationshipHints);
        return Collections.unmodifiableMap(value);
    }

    private static double score(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value > 1.0 ? value / 100.0 : value));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String level(double score) {
        return score >= 0.8 ? "HIGH" : score >= 0.5 ? "MEDIUM" : "LOW";
    }

    private static List<String> immutable(List<String> value) {
        return value == null ? List.of() : value.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
