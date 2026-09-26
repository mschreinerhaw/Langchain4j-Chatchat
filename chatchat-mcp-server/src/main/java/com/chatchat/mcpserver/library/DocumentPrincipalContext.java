package com.chatchat.mcpserver.library;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Identity asserted by the authenticated API gateway for internal document requests. */
final class DocumentPrincipalContext {
    static final String CURRENT_USER_ID = "document.currentUserId";
    static final String CURRENT_USERNAME = "document.currentUsername";
    static final String CURRENT_TENANT_ID = "document.currentTenantId";
    static final String CURRENT_ROLES = "document.currentRoles";
    static final String CURRENT_PERMISSIONS = "document.currentPermissions";

    private DocumentPrincipalContext() {
    }

    static String attribute(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object value = attributes.getRequest().getAttribute(name);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    static void attach(HttpServletRequest request, String tenantId, String userId,
                       String username, String roles, String permissions) {
        request.setAttribute(CURRENT_TENANT_ID, tenantId);
        request.setAttribute(CURRENT_USER_ID, userId);
        request.setAttribute(CURRENT_USERNAME, username);
        request.setAttribute(CURRENT_ROLES, roles);
        request.setAttribute(CURRENT_PERMISSIONS, permissions);
    }
}
