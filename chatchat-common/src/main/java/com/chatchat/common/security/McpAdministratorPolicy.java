package com.chatchat.common.security;

/** Shared MCP administrator semantics. Arguments must come from persisted identity records. */
public final class McpAdministratorPolicy {
    private McpAdministratorPolicy() {}

    public static boolean isAdministratorRole(String callerTenant, String roleTenant,
                                               String roleCode, String roleStatus) {
        return callerTenant != null && !callerTenant.isBlank()
            && callerTenant.equals(roleTenant)
            && "enabled".equalsIgnoreCase(roleStatus)
            && "SUPER_ADMIN".equalsIgnoreCase(roleCode);
    }
}
