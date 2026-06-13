package com.chatchat.agents.runtime;

public record AgentRunQuery(
    AgentRunStatus status,
    String tenantId,
    String userId,
    String conversationId,
    int limit,
    int offset
) {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    public AgentRunQuery {
        tenantId = clean(tenantId);
        userId = clean(userId);
        conversationId = clean(conversationId);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        offset = Math.max(0, offset);
    }

    public static AgentRunQuery recent(int limit) {
        return new AgentRunQuery(null, null, null, null, limit, 0);
    }

    public static AgentRunQuery byStatus(AgentRunStatus status, int limit) {
        return new AgentRunQuery(status, null, null, null, limit, 0);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
