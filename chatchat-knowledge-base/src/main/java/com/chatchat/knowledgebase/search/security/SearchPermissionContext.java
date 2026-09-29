package com.chatchat.knowledgebase.search.security;

import java.util.List;

public record SearchPermissionContext(
    String tenantId,
    String userId,
    List<String> roles,
    String agentId
) {
    public SearchPermissionContext(String tenantId, String userId, List<String> roles) {
        this(tenantId, userId, roles, null);
    }

    public SearchPermissionContext withAgentId(String id) {
        return new SearchPermissionContext(tenantId, userId, roles, id);
    }

    public java.util.Set<String> allowedDocuments(com.chatchat.common.retrieval.ResourceAuthorizationPort authorization,
                                                  java.util.Set<String> ids) {
        return agentId == null || agentId.isBlank()
            ? authorization.allowedIds("KNOWLEDGE", tenantId, userId, java.util.Set.copyOf(roles), ids)
            : authorization.allowedIdsForAgent("KNOWLEDGE", tenantId, userId, java.util.Set.copyOf(roles), ids, agentId);
    }

    public static final String DEFAULT_TENANT = "default";
    public static final String ANONYMOUS_USER = "anonymous";
    public static SearchPermissionContext system() {
        return of(DEFAULT_TENANT, ANONYMOUS_USER, List.of());
    }

    public static SearchPermissionContext of(String tenantId, String userId, List<String> roles) {
        return new SearchPermissionContext(
            hasText(tenantId) ? tenantId.trim() : DEFAULT_TENANT,
            hasText(userId) ? userId.trim() : ANONYMOUS_USER,
            roles == null ? List.of() : roles.stream()
                .filter(SearchPermissionContext::hasText)
                .map(String::trim)
                .distinct()
                .toList()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
