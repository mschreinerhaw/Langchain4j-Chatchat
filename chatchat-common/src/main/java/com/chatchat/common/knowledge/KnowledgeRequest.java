package com.chatchat.common.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Runtime request for task-oriented, budgeted knowledge rather than raw documents. */
public record KnowledgeRequest(
    String schemaVersion,
    String query,
    String taskType,
    int maxTokens,
    KnowledgeScope scope,
    Set<KnowledgeSkillType> allowedSkillTypes,
    Map<String, Object> attributes
) {
    public static final String SCHEMA_VERSION = "knowledge_request.v1";
    public static final int DEFAULT_MAX_TOKENS = 1200;
    public static final int HARD_MAX_TOKENS = 4000;

    public KnowledgeRequest {
        schemaVersion = SCHEMA_VERSION;
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        query = query.trim();
        taskType = taskType == null || taskType.isBlank() ? "GENERAL" : taskType.trim();
        maxTokens = Math.max(1, Math.min(maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens, HARD_MAX_TOKENS));
        if (scope == null) throw new IllegalArgumentException("knowledge scope is required");
        allowedSkillTypes = allowedSkillTypes == null || allowedSkillTypes.isEmpty()
            ? Set.of(KnowledgeSkillType.values()) : Set.copyOf(allowedSkillTypes);
        attributes = attributes == null || attributes.isEmpty()
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }
}
