package com.chatchat.api.agent.task;

final class AgentEventKeyBuilder {

    private AgentEventKeyBuilder() {
    }

    static String build(AgentEvent event) {
        long timestamp = event.getCreateTime() <= 0 ? System.currentTimeMillis() : event.getCreateTime();
        return "tenant:%s:session:%s:time:%013d:event:%s".formatted(
            safe(event.getTenantId()),
            safe(event.getSessionId()),
            timestamp,
            safe(event.getEventId())
        );
    }

    static String sessionPrefix(String tenantId, String sessionId) {
        return "tenant:%s:session:%s:".formatted(safe(tenantId), safe(sessionId));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().replace(':', '_');
    }
}
