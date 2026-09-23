package com.chatchat.common.knowledge.model;

import java.util.List;

/** Authorized knowledge boundary. Every retrieval implementation must enforce this scope. */
public record KnowledgeScope(
    String agentId,
    String tenantId,
    String userId,
    List<String> documentIds,
    List<String> tags,
    List<String> domains,
    List<String> roles
) {
    public KnowledgeScope(String agentId, String tenantId, String userId,
                          List<String> documentIds, List<String> tags, List<String> domains) {
        this(agentId, tenantId, userId, documentIds, tags, domains, List.of());
    }

    public KnowledgeScope {
        agentId = clean(agentId);
        tenantId = clean(tenantId);
        userId = clean(userId);
        documentIds = clean(documentIds);
        tags = clean(tags);
        domains = clean(domains);
        roles = clean(roles);
        if (documentIds.isEmpty() && tags.isEmpty() && domains.isEmpty()) {
            throw new IllegalArgumentException("At least one knowledge scope selector is required");
        }
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
