package com.chatchat.enterprise.workspace;

/** Request-local presentation provenance, never propagated as tool authorization. */
public final class WorkspaceRequestContext {
    public record Scope(String tenantId, String userId, String workspaceId) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private WorkspaceRequestContext() {}
    public static Scope current() { return CURRENT.get(); }
    public static void set(Scope scope) { CURRENT.set(scope); }
    public static void clear() { CURRENT.remove(); }
}
