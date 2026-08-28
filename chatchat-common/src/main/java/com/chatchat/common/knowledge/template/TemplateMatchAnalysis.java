package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Immutable output of Query -> Business Intent -> Data Requirement -> Template Relationship.
 */
public record TemplateMatchAnalysis(
    String schemaVersion,
    String originalUserQuestion,
    Map<String, Object> globalAnalysisContext,
    List<String> businessGroups,
    BusinessAnalysisIntent analysisIntent,
    List<TemplateRequirementMatchEvaluation> templateMatches,
    List<TemplateRelationship> templateRelationships,
    String decisionReason,
    String selectionAuthority
) {
    public static final String SCHEMA_VERSION = "template_match_analysis.v2";
    public static final String OBJECT_TYPE = "TEMPLATE_MATCH_ANALYSIS";
    public static final String EVENT_TYPE = "BUSINESS_TEMPLATE_REQUIREMENT_MATCHING";
    public static final String ANALYSIS_CONTEXT_KEY = "templateMatchAnalysis";

    public TemplateMatchAnalysis {
        schemaVersion = SCHEMA_VERSION;
        if (originalUserQuestion == null || originalUserQuestion.isBlank()) {
            throw new IllegalArgumentException("originalUserQuestion is required");
        }
        originalUserQuestion = originalUserQuestion.trim();
        globalAnalysisContext = globalAnalysisContext == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(globalAnalysisContext));
        businessGroups = strings(businessGroups);
        analysisIntent = analysisIntent == null
            ? new BusinessAnalysisIntent(null, null, List.of(), List.of(), List.of(),
                List.of(), null, List.of()) : analysisIntent;
        templateMatches = templateMatches == null ? List.of() : List.copyOf(templateMatches);
        templateRelationships = templateRelationships == null ? List.of() : List.copyOf(templateRelationships);
        decisionReason = clean(decisionReason);
        selectionAuthority = clean(selectionAuthority);
        if (selectedTemplateIds(templateMatches).isEmpty()) {
            throw new IllegalArgumentException("at least one non-irrelevant template match is required");
        }
    }

    public List<String> selectedTemplateIds() {
        return selectedTemplateIds(templateMatches);
    }

    public List<String> excludedTemplateIds() {
        return templateMatches.stream()
            .filter(match -> match.analysisRole() == TemplateAnalysisRole.IRRELEVANT
                || "REJECT".equalsIgnoreCase(match.decision()))
            .map(TemplateRequirementMatchEvaluation::templateId).distinct().toList();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("objectType", OBJECT_TYPE);
        value.put("event", EVENT_TYPE);
        value.put("userQuestion", originalUserQuestion);
        value.put("globalAnalysisContext", globalAnalysisContext);
        value.put("businessGroups", businessGroups);
        value.put("analysisIntent", analysisIntent.toMap());
        value.put("templateMatches", templateMatches.stream()
            .filter(match -> selectedTemplateIds().contains(match.templateId()))
            .map(TemplateRequirementMatchEvaluation::toMap).toList());
        value.put("excludedTemplates", templateMatches.stream()
            .filter(match -> excludedTemplateIds().contains(match.templateId()))
            .map(TemplateRequirementMatchEvaluation::toMap).toList());
        value.put("templateRelationships", templateRelationships.stream()
            .map(TemplateRelationship::toMap).toList());
        value.put("selectedTemplateIds", selectedTemplateIds());
        value.put("excludedTemplateIds", excludedTemplateIds());
        value.put("decisionReason", decisionReason == null ? "" : decisionReason);
        value.put("selectionAuthority", selectionAuthority == null
            ? "RUNTIME_EVIDENCE_MODEL_REVIEW" : selectionAuthority);
        value.put("contextRole", "IMMUTABLE_SEMANTIC_ANALYSIS_CONTEXT_NOT_RETURNED_FACT");
        return Collections.unmodifiableMap(value);
    }

    private static List<String> selectedTemplateIds(List<TemplateRequirementMatchEvaluation> matches) {
        if (matches == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(matches.stream()
            .filter(match -> match != null
                && match.analysisRole() != TemplateAnalysisRole.IRRELEVANT
                && !"REJECT".equalsIgnoreCase(match.decision()))
            .map(TemplateRequirementMatchEvaluation::templateId).toList()));
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
