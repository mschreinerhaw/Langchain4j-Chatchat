package com.chatchat.common.knowledge;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** The only knowledge object consumed by Agent Runtime. */
public record KnowledgeContext(
    String schemaVersion,
    KnowledgeSkillPlan plan,
    List<KnowledgeIR> knowledgeUnits,
    String compiledContext,
    List<KnowledgeSourceReference> sources,
    int estimatedTokens,
    int maxTokens,
    boolean truncated,
    String status
) {
    public static final String SCHEMA_VERSION = "knowledge_context.v1";
    /** Stable Agent Runtime attribute used to carry knowledge through every analysis stage. */
    public static final String RUNTIME_ATTRIBUTE = "domainKnowledgeContext";

    public KnowledgeContext {
        schemaVersion = SCHEMA_VERSION;
        knowledgeUnits = knowledgeUnits == null ? List.of() : List.copyOf(knowledgeUnits);
        compiledContext = compiledContext == null ? "" : compiledContext;
        sources = sources == null ? List.of() : List.copyOf(sources);
        estimatedTokens = Math.max(0, estimatedTokens);
        maxTokens = Math.max(0, maxTokens);
        if (maxTokens > 0 && estimatedTokens > maxTokens) {
            throw new IllegalArgumentException("Compiled knowledge exceeds token budget");
        }
        status = status == null || status.isBlank() ? "empty" : status.trim();
    }

    public boolean used() {
        return !compiledContext.isBlank();
    }

    /**
     * Produces the bounded, serialization-safe runtime projection. Raw document chunks and
     * provider-specific search responses deliberately stay behind the Knowledge Runtime port.
     */
    public Map<String, Object> toRuntimeProjection() {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", SCHEMA_VERSION);
        projection.put("status", status);
        projection.put("used", used());
        projection.put("compiledContext", compiledContext);
        projection.put("estimatedTokens", estimatedTokens);
        projection.put("maxTokens", maxTokens);
        projection.put("truncated", truncated);
        projection.put("skillTypes", plan == null ? List.of() : plan.skills().stream()
            .map(skill -> skill.skillType().name()).distinct().toList());
        projection.put("activatedSkills", plan == null ? List.of() : plan.skills().stream().map(skill -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", skill.instanceId());
            item.put("skillType", skill.skillType().name());
            putIfPresent(item, "domain", skill.domain());
            putIfPresent(item, "goal", skill.goal());
            item.put("queryHints", skill.queryHints());
            item.put("priority", skill.priority());
            return Map.copyOf(item);
        }).toList());
        projection.put("sources", sources.stream().filter(java.util.Objects::nonNull).map(source -> {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "sourceId", source.sourceId());
            putIfPresent(item, "documentId", source.documentId());
            putIfPresent(item, "chunkId", source.chunkId());
            putIfPresent(item, "documentName", source.documentName());
            putIfPresent(item, "section", source.section());
            putIfPresent(item, "version", source.version());
            putIfPresent(item, "citation", source.citation());
            return Map.copyOf(item);
        }).toList());
        projection.put("usageContract", Map.of(
            "role", "DOMAIN_DEFINITIONS_RULES_METHODS_AND_CONSTRAINTS",
            "currentFacts", false,
            "toolEvidenceRequiredForCurrentFacts", true,
            "examplesAreCurrentFacts", false));
        return Map.copyOf(projection);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }

    public static KnowledgeContext empty(String status, int maxTokens) {
        return new KnowledgeContext(SCHEMA_VERSION, null, List.of(), "", List.of(), 0,
            Math.max(0, maxTokens), false, status);
    }
}
