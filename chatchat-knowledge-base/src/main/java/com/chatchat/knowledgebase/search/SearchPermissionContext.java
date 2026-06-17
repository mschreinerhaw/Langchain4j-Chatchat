package com.chatchat.knowledgebase.search;

import java.util.List;

public record SearchPermissionContext(
    String tenantId,
    String userId,
    List<String> roles
) {

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
