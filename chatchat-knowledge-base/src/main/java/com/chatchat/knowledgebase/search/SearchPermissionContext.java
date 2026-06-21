package com.chatchat.knowledgebase.search;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SearchPermissionContext(
    String tenantId,
    String userId,
    List<String> roles
) {

    public static final String DEFAULT_TENANT = "default";
    public static final String ANONYMOUS_USER = "anonymous";
    private static final Set<String> SUPER_ADMIN_ROLES = Set.of(
        "superadmin",
        "rolesuperadmin",
        "superadministrator",
        "rolesuperadministrator",
        "超级管理员"
    );

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

    public boolean isSuperAdmin() {
        return roles != null && roles.stream()
            .map(SearchPermissionContext::normalizeRole)
            .anyMatch(SUPER_ADMIN_ROLES::contains);
    }

    public static boolean isSuperAdminRole(String role) {
        return SUPER_ADMIN_ROLES.contains(normalizeRole(role));
    }

    private static String normalizeRole(String role) {
        if (!hasText(role)) {
            return "";
        }
        return role.trim()
            .toLowerCase(Locale.ROOT)
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
