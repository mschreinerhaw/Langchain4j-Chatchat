package com.chatchat.agents.runtime.governance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-owned isolation identity for governed evidence and derived summaries.
 * Values must come from the authenticated runtime request, never from tool payloads or model output.
 */
public record GovernanceIsolationScope(
    String schemaVersion,
    String tenantId,
    String userId,
    String runId,
    String requestId,
    String conversationId,
    String authority
) {

    public static final String SCHEMA_VERSION = "governance_isolation_scope.v1";
    public static final String RUNTIME_AUTHORITY = "RUNTIME_REQUEST_CONTEXT";

    public GovernanceIsolationScope {
        schemaVersion = SCHEMA_VERSION;
        tenantId = text(tenantId, "default");
        userId = text(userId, "anonymous");
        requestId = text(requestId, "unknown-request");
        runId = text(runId, requestId);
        conversationId = text(conversationId, "unknown-conversation");
        authority = RUNTIME_AUTHORITY;
    }

    public static GovernanceIsolationScope runtime(String tenantId,
                                                   String userId,
                                                   String runId,
                                                   String requestId,
                                                   String conversationId) {
        return new GovernanceIsolationScope(
            SCHEMA_VERSION, tenantId, userId, runId, requestId, conversationId, RUNTIME_AUTHORITY);
    }

    public String partitionKey() {
        return tenantId + ":" + runId;
    }

    public boolean samePartition(GovernanceIsolationScope other) {
        return other != null
            && tenantId.equals(other.tenantId)
            && runId.equals(other.runId);
    }

    public void requireSamePartition(GovernanceIsolationScope other) {
        if (!samePartition(other)) {
            throw new IllegalArgumentException("Cross-tenant or cross-run governance result merge rejected");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", schemaVersion);
        values.put("tenantId", tenantId);
        values.put("userId", userId);
        values.put("runId", runId);
        values.put("requestId", requestId);
        values.put("conversationId", conversationId);
        values.put("partitionKey", partitionKey());
        values.put("authority", authority);
        return Map.copyOf(values);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
