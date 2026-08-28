package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable template-semantic context copied into every Worker record chunk. */
public record TemplateWorkerAnalysisContext(
    String originalUserQuestion,
    Map<String, Object> globalAnalysisContext,
    Map<String, Object> businessIntent,
    Map<String, Object> templateMatchAnalysis,
    Map<String, Object> currentTemplate,
    List<Map<String, Object>> relatedTemplates
) {
    public static final String SCHEMA_VERSION = "worker_analysis_context.v2";
    public static final String ANALYSIS_CONTEXT_KEY = "workerAnalysisContext";

    public TemplateWorkerAnalysisContext {
        if (originalUserQuestion == null || originalUserQuestion.isBlank()) {
            throw new IllegalArgumentException("originalUserQuestion is required");
        }
        originalUserQuestion = originalUserQuestion.trim();
        globalAnalysisContext = immutable(globalAnalysisContext);
        businessIntent = immutable(businessIntent);
        templateMatchAnalysis = immutable(templateMatchAnalysis);
        currentTemplate = immutable(currentTemplate);
        relatedTemplates = relatedTemplates == null ? List.of() : List.copyOf(relatedTemplates);
        if (currentTemplate.isEmpty()) throw new IllegalArgumentException("currentTemplate is required");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("originalUserQuestion", originalUserQuestion);
        value.put("globalAnalysisContext", globalAnalysisContext);
        value.put("businessIntent", businessIntent);
        value.put("templateMatchAnalysis", templateMatchAnalysis);
        value.put("currentTemplate", currentTemplate);
        value.put("relatedTemplates", relatedTemplates);
        value.put("inheritancePolicy", "COPY_TO_EVERY_DATASET_CHUNK");
        return Collections.unmodifiableMap(value);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
